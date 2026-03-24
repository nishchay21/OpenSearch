/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.prefetch;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;

import java.util.List;

public class TieredStoragePrefetchSettings {

    public static final int DEFAULT_READ_AHEAD_BLOCK_COUNT = 4;
    public static final String DVD_FILE_SUFFIX = "dvd";
    public static final String CFS_FILE_SUFFIX = "cfs";
    public static final Setting<Integer> READ_AHEAD_BLOCK_COUNT = Setting.intSetting(
        "tiering.service.prefetch.read_ahead.block_count",
        DEFAULT_READ_AHEAD_BLOCK_COUNT,
        0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Boolean> STORED_FIELDS_PREFETCH_ENABLED_SETTING = Setting.boolSetting(
        "tiering.service.prefetch.stored_fields.enabled",
        true,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final List<String> READ_AHEAD_ENABLE_FILE_FORMATS = List.of(DVD_FILE_SUFFIX);
    private int readAheadBlockCount;
    private final List<String> readAheadEnableFileFormats;
    private boolean storedFieldsPrefetchEnabled;

    public TieredStoragePrefetchSettings(ClusterSettings clusterSettings) {
        this.readAheadBlockCount = clusterSettings.get(READ_AHEAD_BLOCK_COUNT);
        clusterSettings.addSettingsUpdateConsumer(READ_AHEAD_BLOCK_COUNT, this::setReadAheadBlockCount);
        this.readAheadEnableFileFormats = READ_AHEAD_ENABLE_FILE_FORMATS;
        this.storedFieldsPrefetchEnabled = clusterSettings.get(STORED_FIELDS_PREFETCH_ENABLED_SETTING);
        clusterSettings.addSettingsUpdateConsumer(STORED_FIELDS_PREFETCH_ENABLED_SETTING, this::setStoredFieldsPrefetchEnabled);
    }

    public void setReadAheadBlockCount(int readAheadBlockCount) {
        this.readAheadBlockCount = readAheadBlockCount;
    }

    public void setStoredFieldsPrefetchEnabled(boolean storedFieldsPrefetchEnabled) {
        this.storedFieldsPrefetchEnabled = storedFieldsPrefetchEnabled;
    }

    public boolean isStoredFieldsPrefetchEnabled() {
        return storedFieldsPrefetchEnabled;
    }

    public int getReadAheadBlockCount() {
        return this.readAheadBlockCount;
    }

    public List<String> getReadAheadEnableFileFormats() {
        return this.readAheadEnableFileFormats;
    }
}
