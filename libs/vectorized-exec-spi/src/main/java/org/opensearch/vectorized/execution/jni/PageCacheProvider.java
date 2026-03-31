/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.vectorized.execution.jni;

/**
 * Provider for a byte-range page cache used to serve Parquet file reads on warm indices.
 * <p>
 * Implemented by search-engine plugins (e.g. {@code DataFusionPlugin}) that own an
 * in-process page cache and want to expose it to the tiered-storage module so that
 * {@code CachedParquetCacheStrategy} can serve Parquet column-chunk byte ranges from the
 * cache instead of re-fetching from object storage (S3/GCS/Azure) on every
 * {@code openIndexInput()} call.
 * <p>
 * The cache key is the local filesystem path of the Parquet file (without leading slash)
 * combined with the byte range, e.g. {@code "data/nodes/0/.../parquet/_parquet_0.parquet:4096-8192"}.
 * The exact key format is an implementation detail of the provider.
 */
public interface PageCacheProvider {

    /**
     * Look up a cached byte range for a Parquet file.
     *
     * @param path  the local file path used as cache key (e.g. "data/nodes/0/.../parquet/_parquet_0.parquet")
     * @param start byte range start (inclusive)
     * @param end   byte range end (exclusive)
     * @return the cached bytes, or {@code null} on cache miss
     */
    byte[] getPageRange(String path, int start, int end);

    /**
     * Store a byte range for a Parquet file in the cache.
     *
     * @param path  the local file path used as cache key
     * @param start byte range start (inclusive)
     * @param end   byte range end (exclusive)
     * @param data  the bytes to cache (must have length == end - start)
     */
    void putPageRange(String path, int start, int end, byte[] data);

    /**
     * Evict all cached byte ranges for a given Parquet file.
     * Called when a file is deleted (merged, compacted, or tiered out).
     *
     * @param path the local file path whose cached ranges should be removed
     */
    void evictFile(String path);

    /**
     * Returns the number of bytes currently stored in the page cache's disk tier (L2).
     * Used by {@code DiskBudgetManager} to aggregate disk usage across all SSD consumers.
     * <p>
     * Default implementation returns {@code 0} so existing implementors are not broken.
     *
     * @return disk bytes currently in use by the page cache, or {@code 0} if unknown
     */
    default long getDiskUsageBytes() {
        return 0L;
    }

    /**
     * Returns the configured disk capacity of the page cache's disk tier (L2).
     * Used by {@code DiskBudgetManager} to validate that total cache budgets do not
     * exceed available SSD space at node startup.
     * <p>
     * Default implementation returns {@code 0} so existing implementors are not broken.
     *
     * @return disk capacity configured for the page cache in bytes, or {@code 0} if disabled
     */
    default long getDiskCapacityBytes() {
        return 0L;
    }
}
