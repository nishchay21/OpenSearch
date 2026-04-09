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

/**
 * Settings for the Foyer disk-backed page cache that is embedded inside
 * {@code TieredObjectStore}.
 *
 * <p>These settings are deliberately separated from {@link CacheSettings}
 * (which owns Parquet metadata / statistics caches) because the page cache
 * belongs to the tiered-storage read path, not to the DataFusion generic
 * cache manager.
 *
 * <p>The values are read once at {@code TieredObjectStore} construction time
 * and passed directly to the native
 * {@code nativeCreateTieredObjectStore(diskCacheBytes, diskCacheDir)} JNI call.
 * No Java-side cache handle is held.
 */
public final class TieredCacheSettings {

    private TieredCacheSettings() {}

    // ── Enabled flag ────────────────────────────────────────────────────────

    public static final String PAGE_CACHE_ENABLED_KEY = "datafusion.tiered.page.cache.enabled";

    /**
     * Master switch for the Foyer page cache inside {@code TieredObjectStore}.
     * When {@code false} the store performs all reads without caching.
     */
    public static final Setting<Boolean> PAGE_CACHE_ENABLED =
        Setting.boolSetting(PAGE_CACHE_ENABLED_KEY, true,
            Setting.Property.NodeScope, Setting.Property.Dynamic);

    // ── Disk budget ──────────────────────────────────────────────────────────

    public static final String PAGE_CACHE_DISK_CAPACITY_KEY = "datafusion.tiered.page.cache.disk.capacity";

    /**
     * Total disk space allocated to the Foyer page cache (e.g. {@code 10gb}).
     * Foyer manages eviction within this budget automatically.
     */
    public static final Setting<ByteSizeValue> PAGE_CACHE_DISK_CAPACITY =
        new Setting<>(PAGE_CACHE_DISK_CAPACITY_KEY, "10gb",
            (s) -> ByteSizeValue.parseBytesSizeValue(
                s, new ByteSizeValue(1, ByteSizeUnit.GB), PAGE_CACHE_DISK_CAPACITY_KEY),
            Setting.Property.NodeScope, Setting.Property.Dynamic);

    // ── Disk directory ───────────────────────────────────────────────────────

    public static final String PAGE_CACHE_DIR_KEY = "datafusion.tiered.page.cache.dir";

    /**
     * Local NVMe directory where Foyer stores its cache data files.
     * The directory must be writable by the OpenSearch process.
     */
    public static final Setting<String> PAGE_CACHE_DIR = new Setting<>(
        PAGE_CACHE_DIR_KEY,
        "/tmp/foyer-page-cache",
        Function.identity(),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic);

    // ── Convenience list for Plugin.getSettings() ────────────────────────────

    public static final List<Setting<?>> TIERED_CACHE_SETTINGS = Arrays.asList(
        PAGE_CACHE_ENABLED,
        PAGE_CACHE_DISK_CAPACITY,
        PAGE_CACHE_DIR
    );
}
