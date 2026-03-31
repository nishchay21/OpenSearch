/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.monitor.fs;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentFragment;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Aggregated disk budget statistics for a warm node.
 * <p>
 * A warm node has multiple independent SSD consumers:
 * <ol>
 *   <li><b>FileCache</b> — serves warm Lucene/segment files, bounded by
 *       {@code node.search.cache.size}.</li>
 *   <li><b>Page cache (Foyer)</b> — serves parquet column-chunk byte ranges, bounded by
 *       {@code format_cache.disk.total_budget}.</li>
 *   <li>Short-lived write buffers and merge temp files (not tracked here — self-cleaning).</li>
 * </ol>
 *
 * <p>This class exposes a read-only snapshot that can be surfaced via {@code _nodes/stats}.
 * It does not coordinate eviction — each cache manages its own eviction independently.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class DiskBudgetStats implements Writeable, ToXContentFragment {

    /** Total physical SSD capacity on this node (bytes). */
    private final long totalPhysicalSsdBytes;

    /** Bytes configured as the FileCache capacity ({@code node.search.cache.size}). */
    private final long fileCacheConfiguredBytes;

    /** Bytes currently stored in the FileCache. */
    private final long fileCacheUsedBytes;

    /** Bytes configured as the format (page) cache disk budget ({@code format_cache.disk.total_budget}). */
    private final long formatCacheConfiguredBytes;

    /** Bytes currently stored in the format (page) cache disk tier. */
    private final long formatCacheUsedBytes;

    /**
     * Percentage of physical SSD remaining after accounting for both configured cache budgets.
     * Computed as {@code 100 - ((fileCacheConfigured + formatCacheConfigured) * 100 / totalPhysicalSsd)}.
     * A negative value means the budgets are over-provisioned.
     */
    private final int headroomPercent;

    public DiskBudgetStats(
        long totalPhysicalSsdBytes,
        long fileCacheConfiguredBytes,
        long fileCacheUsedBytes,
        long formatCacheConfiguredBytes,
        long formatCacheUsedBytes,
        int headroomPercent
    ) {
        this.totalPhysicalSsdBytes = totalPhysicalSsdBytes;
        this.fileCacheConfiguredBytes = fileCacheConfiguredBytes;
        this.fileCacheUsedBytes = fileCacheUsedBytes;
        this.formatCacheConfiguredBytes = formatCacheConfiguredBytes;
        this.formatCacheUsedBytes = formatCacheUsedBytes;
        this.headroomPercent = headroomPercent;
    }

    /**
     * Deserialization constructor — must read fields in the same order as {@link #writeTo}.
     */
    public DiskBudgetStats(StreamInput in) throws IOException {
        this.totalPhysicalSsdBytes = in.readVLong();
        this.fileCacheConfiguredBytes = in.readVLong();
        this.fileCacheUsedBytes = in.readVLong();
        this.formatCacheConfiguredBytes = in.readVLong();
        this.formatCacheUsedBytes = in.readVLong();
        this.headroomPercent = in.readInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(totalPhysicalSsdBytes);
        out.writeVLong(fileCacheConfiguredBytes);
        out.writeVLong(fileCacheUsedBytes);
        out.writeVLong(formatCacheConfiguredBytes);
        out.writeVLong(formatCacheUsedBytes);
        out.writeInt(headroomPercent);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public long getTotalPhysicalSsdBytes() { return totalPhysicalSsdBytes; }
    public long getFileCacheConfiguredBytes() { return fileCacheConfiguredBytes; }
    public long getFileCacheUsedBytes() { return fileCacheUsedBytes; }
    public long getFormatCacheConfiguredBytes() { return formatCacheConfiguredBytes; }
    public long getFormatCacheUsedBytes() { return formatCacheUsedBytes; }
    public int getHeadroomPercent() { return headroomPercent; }

    // ── XContent ──────────────────────────────────────────────────────────────

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(Fields.DISK_BUDGET);

        builder.humanReadableField(Fields.TOTAL_PHYSICAL_SSD_IN_BYTES, Fields.TOTAL_PHYSICAL_SSD,
            new ByteSizeValue(totalPhysicalSsdBytes));

        // FileCache sub-object
        builder.startObject(Fields.FILE_CACHE);
        builder.humanReadableField(Fields.CONFIGURED_IN_BYTES, Fields.CONFIGURED,
            new ByteSizeValue(fileCacheConfiguredBytes));
        builder.humanReadableField(Fields.USED_IN_BYTES, Fields.USED,
            new ByteSizeValue(fileCacheUsedBytes));
        builder.endObject();

        // Format (page) cache sub-object
        builder.startObject(Fields.FORMAT_CACHE);
        builder.humanReadableField(Fields.CONFIGURED_IN_BYTES, Fields.CONFIGURED,
            new ByteSizeValue(formatCacheConfiguredBytes));
        builder.humanReadableField(Fields.USED_IN_BYTES, Fields.USED,
            new ByteSizeValue(formatCacheUsedBytes));
        builder.endObject();

        builder.field(Fields.HEADROOM_PERCENT, headroomPercent);

        builder.endObject();
        return builder;
    }

    // ── Field name constants ──────────────────────────────────────────────────

    static final class Fields {
        static final String DISK_BUDGET                  = "disk_budget";
        static final String TOTAL_PHYSICAL_SSD           = "total_physical_ssd";
        static final String TOTAL_PHYSICAL_SSD_IN_BYTES  = "total_physical_ssd_in_bytes";
        static final String FILE_CACHE                   = "file_cache";
        static final String FORMAT_CACHE                 = "format_cache";
        static final String CONFIGURED                   = "configured";
        static final String CONFIGURED_IN_BYTES          = "configured_in_bytes";
        static final String USED                         = "used";
        static final String USED_IN_BYTES                = "used_in_bytes";
        static final String HEADROOM_PERCENT             = "headroom_percent";
    }
}
