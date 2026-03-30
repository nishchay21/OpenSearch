/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.datafusion.search.cache;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.opensearch.common.settings.Setting;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;

public class CacheSettings {

    public static final String METADATA_CACHE_SIZE_LIMIT_KEY = "datafusion.metadata.cache.size.limit";
    public static final String STATISTICS_CACHE_SIZE_LIMIT_KEY = "datafusion.statistics.cache.size.limit";
    public static final Setting<ByteSizeValue> METADATA_CACHE_SIZE_LIMIT =
        new Setting<>(METADATA_CACHE_SIZE_LIMIT_KEY, "250mb",
            (s) -> ByteSizeValue.parseBytesSizeValue(s, new ByteSizeValue(1000, ByteSizeUnit.KB),METADATA_CACHE_SIZE_LIMIT_KEY), Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> STATISTICS_CACHE_SIZE_LIMIT =
        new Setting<>(STATISTICS_CACHE_SIZE_LIMIT_KEY, "100mb",
            (s) -> ByteSizeValue.parseBytesSizeValue(s, new ByteSizeValue(0, ByteSizeUnit.KB),STATISTICS_CACHE_SIZE_LIMIT_KEY), Setting.Property.NodeScope, Setting.Property.Dynamic);


    public static final Setting<String> METADATA_CACHE_EVICTION_TYPE = new Setting<String>(
        "datafusion.metadata.cache.eviction.type",
        "LRU",
        Function.identity(),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    public static final Setting<String> STATISTICS_CACHE_EVICTION_TYPE = new Setting<String>(
        "datafusion.statistics.cache.eviction.type",
        "LRU",
        Function.identity(),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );


    public static final String METADATA_CACHE_ENABLED_KEY = "datafusion.metadata.cache.enabled";
    public static final Setting<Boolean> METADATA_CACHE_ENABLED =
        Setting.boolSetting(METADATA_CACHE_ENABLED_KEY, true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final String STATISTICS_CACHE_ENABLED_KEY = "datafusion.statistics.cache.enabled";
    public static final Setting<Boolean> STATISTICS_CACHE_ENABLED =
        Setting.boolSetting(STATISTICS_CACHE_ENABLED_KEY, true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    // --- Page Cache (Cache Layer 3: Foyer hybrid memory+disk cache for Parquet column chunk byte ranges) ---

    /**
     * L1 memory budget for the Foyer page cache.
     * Hot byte ranges (recently read Parquet column chunk pages) are kept here.
     * Default: 256 MB.
     */
    public static final String PAGE_CACHE_SIZE_LIMIT_KEY = "datafusion.page.cache.size.limit";
    public static final Setting<ByteSizeValue> PAGE_CACHE_SIZE_LIMIT =
        new Setting<>(PAGE_CACHE_SIZE_LIMIT_KEY, "256mb",
            (s) -> ByteSizeValue.parseBytesSizeValue(s, new ByteSizeValue(64, ByteSizeUnit.MB), PAGE_CACHE_SIZE_LIMIT_KEY),
            Setting.Property.NodeScope, Setting.Property.Dynamic);

    /**
     * L2 disk budget for the Foyer page cache.
     * Warm byte ranges evicted from L1 memory are spilled to this disk store
     * to avoid re-fetching from S3/GCS/Azure. Must be larger than PAGE_CACHE_SIZE_LIMIT.
     * Default: 10 GB.
     */
    public static final String PAGE_CACHE_DISK_CAPACITY_KEY = "datafusion.page.cache.disk.capacity";
    public static final Setting<ByteSizeValue> PAGE_CACHE_DISK_CAPACITY =
        new Setting<>(PAGE_CACHE_DISK_CAPACITY_KEY, "10gb",
            (s) -> ByteSizeValue.parseBytesSizeValue(s, new ByteSizeValue(1, ByteSizeUnit.GB), PAGE_CACHE_DISK_CAPACITY_KEY),
            Setting.Property.NodeScope, Setting.Property.Dynamic);

    /**
     * Directory on local NVMe where Foyer stores the L2 disk cache files.
     * Should be a fast local path (not S3/NFS). Foyer creates it if it doesn't exist.
     * Default: "/tmp/foyer-page-cache".
     */
    public static final String PAGE_CACHE_DIR_KEY = "datafusion.page.cache.dir";
    public static final Setting<String> PAGE_CACHE_DIR = new Setting<>(
        PAGE_CACHE_DIR_KEY,
        "/tmp/foyer-page-cache",
        Function.identity(),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    public static final String PAGE_CACHE_ENABLED_KEY = "datafusion.page.cache.enabled";
    public static final Setting<Boolean> PAGE_CACHE_ENABLED =
        Setting.boolSetting(PAGE_CACHE_ENABLED_KEY, true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final List<Setting<?>> CACHE_SETTINGS = Arrays.asList(
        METADATA_CACHE_SIZE_LIMIT,
        METADATA_CACHE_EVICTION_TYPE,
        STATISTICS_CACHE_SIZE_LIMIT,
        STATISTICS_CACHE_EVICTION_TYPE,
        PAGE_CACHE_SIZE_LIMIT,
        PAGE_CACHE_DISK_CAPACITY,
        PAGE_CACHE_DIR
    );

    public static final List<Setting<Boolean>> CACHE_ENABLED = Arrays.asList(
        METADATA_CACHE_ENABLED,
        STATISTICS_CACHE_ENABLED,
        PAGE_CACHE_ENABLED
    );
}
