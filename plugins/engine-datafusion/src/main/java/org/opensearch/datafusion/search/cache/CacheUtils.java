/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.datafusion.search.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.datafusion.jni.NativeBridge;

import static org.opensearch.datafusion.search.cache.CacheSettings.METADATA_CACHE_ENABLED;
import static org.opensearch.datafusion.search.cache.CacheSettings.METADATA_CACHE_EVICTION_TYPE;
import static org.opensearch.datafusion.search.cache.CacheSettings.METADATA_CACHE_SIZE_LIMIT;
import static org.opensearch.datafusion.search.cache.CacheSettings.STATISTICS_CACHE_ENABLED;
import static org.opensearch.datafusion.search.cache.CacheSettings.STATISTICS_CACHE_EVICTION_TYPE;
import static org.opensearch.datafusion.search.cache.CacheSettings.STATISTICS_CACHE_SIZE_LIMIT;
import static org.opensearch.datafusion.search.cache.CacheSettings.PAGE_CACHE_ENABLED;
import static org.opensearch.datafusion.search.cache.CacheSettings.PAGE_CACHE_SIZE_LIMIT;
import static org.opensearch.datafusion.search.cache.CacheSettings.PAGE_CACHE_DISK_CAPACITY;
import static org.opensearch.datafusion.search.cache.CacheSettings.PAGE_CACHE_DIR;

/**
 * Utility class for cache initialization and configuration.
 * Contains the CacheType enum and methods for creating cache configurations.
 */
public final class CacheUtils {
    private static final Logger logger = LogManager.getLogger(CacheUtils.class);

    // Private constructor to prevent instantiation
    private CacheUtils() {}

    /**
     * Cache type enumeration with associated settings.
     */
    public enum CacheType {
        METADATA("METADATA", METADATA_CACHE_ENABLED, METADATA_CACHE_SIZE_LIMIT),
        STATISTICS("STATISTICS", STATISTICS_CACHE_ENABLED, STATISTICS_CACHE_SIZE_LIMIT),

        /**
         * Cache Layer 3: Foyer hybrid (memory + disk) page cache for compressed Parquet byte ranges.
         * L1 = memory (PAGE_CACHE_SIZE_LIMIT), L2 = disk (PAGE_CACHE_DISK_CAPACITY at PAGE_CACHE_DIR).
         * The eviction string passed to Rust is encoded as: {@code "<disk_bytes>|<disk_dir>"}
         * so that Rust's {@code parse_page_cache_params()} can unpack both L2 disk settings.
         */
        PAGES("PAGES", PAGE_CACHE_ENABLED, PAGE_CACHE_SIZE_LIMIT);

        private final String cacheTypeName;
        private final Setting<Boolean> enabledSetting;
        private final Setting<ByteSizeValue> sizeLimitSetting;

        CacheType(String cacheTypeName, Setting<Boolean> enabledSetting, Setting<ByteSizeValue> sizeLimitSetting) {
            this.cacheTypeName = cacheTypeName;
            this.enabledSetting = enabledSetting;
            this.sizeLimitSetting = sizeLimitSetting;
        }

        public boolean isEnabled(ClusterSettings clusterSettings) {
            return clusterSettings.get(enabledSetting);
        }

        public Setting<Boolean> getEnabledSetting() {
            return enabledSetting;
        }

        public Setting<ByteSizeValue> getSizeLimitSetting() {
            return sizeLimitSetting;
        }

        public ByteSizeValue getSizeLimit(ClusterSettings clusterSettings) {
            return clusterSettings.get(sizeLimitSetting);
        }

        public String getCacheTypeName() {
            return cacheTypeName;
        }
    }

    /**
     * Creates and configures a CacheManagerConfig pointer with all enabled caches.
     * For each cache type, calls NativeBridge.createCache() with the appropriate
     * size and configuration string.
     *
     * @param clusterSettings OpenSearch cluster settings containing cache configuration
     */
    public static long createCacheConfig(ClusterSettings clusterSettings) {
        logger.info("[FOYER-PAGE-CACHE] initializing cache configuration");

        long cacheManagerPtr = NativeBridge.createCustomCacheManager();

        // METADATA cache (Layer 1: Parquet footer/schema)
        if (CacheType.METADATA.isEnabled(clusterSettings)) {
            long size = CacheType.METADATA.getSizeLimit(clusterSettings).getBytes();
            String eviction = clusterSettings.get(CacheSettings.METADATA_CACHE_EVICTION_TYPE);
            logger.info("[CACHE INFO] Configuring METADATA cache: size={}B, eviction={}", size, eviction);
            NativeBridge.createCache(cacheManagerPtr, "METADATA", size, eviction);
        }

        // STATISTICS cache (Layer 2: row counts, min/max)
        if (CacheType.STATISTICS.isEnabled(clusterSettings)) {
            long size = CacheType.STATISTICS.getSizeLimit(clusterSettings).getBytes();
            String eviction = clusterSettings.get(CacheSettings.STATISTICS_CACHE_EVICTION_TYPE);
            logger.info("[CACHE INFO] Configuring STATISTICS cache: size={}B, eviction={}", size, eviction);
            NativeBridge.createCache(cacheManagerPtr, "STATISTICS", size, eviction);
        }

        // PAGES cache (Layer 3: Foyer hybrid memory+disk byte range cache)
        if (CacheType.PAGES.isEnabled(clusterSettings)) {
            long memBytes  = clusterSettings.get(PAGE_CACHE_SIZE_LIMIT).getBytes();
            long diskBytes = clusterSettings.get(PAGE_CACHE_DISK_CAPACITY).getBytes();
            String diskDir = clusterSettings.get(PAGE_CACHE_DIR);
            // Encode disk settings into the eviction_type string: "<disk_bytes>|<disk_dir>"
            // Rust's parse_page_cache_params() reads this format.
            String evictionEncoded = diskBytes + "|" + diskDir;
            logger.info(
                "[FOYER-PAGE-CACHE] Configuring PAGES cache: L1-mem={}B, L2-disk={}B, dir={}, encoded={}",
                memBytes, diskBytes, diskDir, evictionEncoded
            );
            NativeBridge.createCache(cacheManagerPtr, "PAGES", memBytes, evictionEncoded);
        }

        logger.info("[FOYER-PAGE-CACHE] cache configuration completed");
        return cacheManagerPtr;
    }
}
