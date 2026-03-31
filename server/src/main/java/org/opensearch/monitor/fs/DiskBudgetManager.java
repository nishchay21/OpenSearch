/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.monitor.fs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.vectorized.execution.jni.PageCacheProvider;

import java.util.Arrays;

/**
 * Node-level singleton that passively aggregates disk usage from all SSD consumers on a
 * warm node and validates disk budgets at startup.
 *
 * <p><b>Consumers tracked:</b>
 * <ul>
 *   <li><b>FileCache</b> — serves warm Lucene/segment files. Bounded by
 *       {@code node.search.cache.size}. Manages its own LRU eviction.</li>
 *   <li><b>Format (page) cache</b> — serves parquet column-chunk byte ranges via Foyer.
 *       Bounded by {@code format_cache.disk.total_budget}. Manages its own LRU eviction.</li>
 *   <li>Write buffers and merge temp files — short-lived, self-cleaning, NOT tracked here.</li>
 * </ul>
 *
 * <p><b>What this class does:</b>
 * <ul>
 *   <li><b>Startup validation</b> — {@link #validateAtStartup} checks that
 *       {@code FileCache capacity + page cache capacity <= headroomThresholdPercent% of physical SSD}.
 *       If violated the node refuses to start.</li>
 *   <li><b>Runtime aggregation</b> — {@link #getStats} returns a point-in-time
 *       {@link DiskBudgetStats} snapshot for {@code _nodes/stats}.</li>
 * </ul>
 *
 * <p><b>What this class does NOT do:</b>
 * This is a <em>passive observer</em>. It does not evict entries from either cache, does not
 * coordinate writes, and does not block operations. Each cache manages its own eviction
 * independently. The only enforcement point is the startup check.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class DiskBudgetManager {

    private static final Logger logger = LogManager.getLogger(DiskBudgetManager.class);

    /**
     * Default maximum fraction of physical SSD that can be committed to long-lived caches.
     * The remaining 10% is reserved for transient write buffers and merge temp files.
     */
    public static final int DEFAULT_HEADROOM_THRESHOLD_PERCENT = 90;

    private final FileCache fileCache;
    private final long formatCacheConfiguredBytes;
    private final int headroomThresholdPercent;
    private volatile PageCacheProvider pageCacheProvider;

    /**
     * @param fileCache                  the warm node FileCache, or {@code null} if not initialized
     * @param formatCacheConfiguredBytes the configured disk budget for the format (page) cache in bytes;
     *                                   {@code 0} means the format cache is disabled
     */
    public DiskBudgetManager(FileCache fileCache, long formatCacheConfiguredBytes) {
        this(fileCache, formatCacheConfiguredBytes, DEFAULT_HEADROOM_THRESHOLD_PERCENT);
    }

    /**
     * @param fileCache                  the warm node FileCache, or {@code null} if not initialized
     * @param formatCacheConfiguredBytes the configured disk budget for the format (page) cache in bytes
     * @param headroomThresholdPercent   maximum percent of SSD that may be committed (default 90)
     */
    public DiskBudgetManager(FileCache fileCache, long formatCacheConfiguredBytes, int headroomThresholdPercent) {
        this.fileCache = fileCache;
        this.formatCacheConfiguredBytes = formatCacheConfiguredBytes;
        this.headroomThresholdPercent = headroomThresholdPercent;
    }

    /**
     * Inject the format (page) cache provider. Called by {@code Node.java} after the plugin
     * implementing {@link PageCacheProvider} has been discovered and wired.
     */
    public void setPageCacheProvider(PageCacheProvider provider) {
        this.pageCacheProvider = provider;
        logger.debug("[DiskBudgetManager] PageCacheProvider set — format cache disk stats available");
    }

    /**
     * Validates that the sum of all long-lived cache budgets does not exceed
     * {@code headroomThresholdPercent} of the physical SSD.
     *
     * <p>Called once during node startup, before the node accepts traffic. If validation
     * fails the node refuses to start with a clear error message.
     *
     * @throws IllegalStateException if configured budgets exceed the threshold
     */
    public void validateAtStartup() {
        long physicalSsd = getPhysicalSsdBytes();
        if (physicalSsd <= 0) {
            logger.warn("[DiskBudgetManager] Could not determine physical SSD size — skipping budget validation");
            return;
        }

        long fileCacheCapacity = fileCache != null ? fileCache.capacity() : 0L;
        long totalCommitted = fileCacheCapacity + formatCacheConfiguredBytes;
        long threshold = (physicalSsd * headroomThresholdPercent) / 100L;

        if (totalCommitted > threshold) {
            throw new IllegalStateException(
                "[DiskBudgetManager] Disk budget misconfigured on this warm node. "
                    + "FileCache("
                    + fileCacheCapacity
                    + " B) + FormatCache("
                    + formatCacheConfiguredBytes
                    + " B) = "
                    + totalCommitted
                    + " B exceeds "
                    + headroomThresholdPercent
                    + "% of physical SSD ("
                    + physicalSsd
                    + " B = "
                    + threshold
                    + " B allowed). "
                    + "Reduce 'node.search.cache.size' or 'format_cache.disk.total_budget'."
            );
        }

        int headroom = computeHeadroomPercent(physicalSsd, fileCacheCapacity, formatCacheConfiguredBytes);
        logger.info(
            "[DiskBudgetManager] Startup validation passed: FileCache={}B, FormatCache={}B, "
                + "TotalCommitted={}B, PhysicalSSD={}B, Headroom={}%",
            fileCacheCapacity,
            formatCacheConfiguredBytes,
            totalCommitted,
            physicalSsd,
            headroom
        );
    }

    /**
     * Returns a point-in-time snapshot of all disk budget consumers.
     * Safe to call from any thread at any time.
     */
    public DiskBudgetStats getStats() {
        long physicalSsd = getPhysicalSsdBytes();
        long fileCacheCapacity = fileCache != null ? fileCache.capacity() : 0L;
        long fileCacheUsed = fileCache != null ? fileCache.usage() : 0L;

        PageCacheProvider provider = this.pageCacheProvider;
        long formatCacheUsed = provider != null ? provider.getDiskUsageBytes() : 0L;

        int headroom = computeHeadroomPercent(physicalSsd, fileCacheCapacity, formatCacheConfiguredBytes);

        return new DiskBudgetStats(
            physicalSsd,
            fileCacheCapacity,
            fileCacheUsed,
            formatCacheConfiguredBytes,
            formatCacheUsed,
            headroom
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the physical SSD capacity in bytes by scanning all data node paths and
     * summing the unique mount-point totals.
     *
     * <p>Falls back to 0 if the file system cannot be queried (e.g. in tests).
     */
    private long getPhysicalSsdBytes() {
        try {
            java.io.File[] roots = java.io.File.listRoots();
            if (roots == null || roots.length == 0) {
                return 0L;
            }
            // Use the root with the largest total space as a proxy for the primary data SSD.
            // In a real warm node the data directory is on a single dedicated SSD.
            return Arrays.stream(roots)
                .mapToLong(java.io.File::getTotalSpace)
                .max()
                .orElse(0L);
        } catch (Exception e) {
            logger.debug("[DiskBudgetManager] Could not determine physical SSD size: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Computes the percentage of SSD remaining after both cache budgets.
     * Returns 100 if {@code physicalSsd == 0} (unknown) to avoid false alarms.
     */
    static int computeHeadroomPercent(long physicalSsd, long fileCacheCapacity, long formatCacheCapacity) {
        if (physicalSsd <= 0) {
            return 100;
        }
        long committed = fileCacheCapacity + formatCacheCapacity;
        int usedPercent = (int) ((committed * 100L) / physicalSsd);
        return Math.max(0, 100 - usedPercent);
    }
}
