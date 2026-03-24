/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.snapshots;

import java.util.Arrays;
import java.util.List;

import org.opensearch.common.annotation.PublicApi;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexModule;

/**
 * Types of index that are supported for ultrawarm migrations.
 *
 */
@PublicApi(since = "1.0.0")
public enum IndexType {
    HOT(-1, Arrays.asList(MigrationState.HOT)),
    WARM(1, Arrays.asList(MigrationState.HOT2WARM, MigrationState.WARM, MigrationState.WARM2HOT));

    /**
     * Types of migration states that are supported
     */
    @PublicApi(since = "1.0.0")
    public static enum MigrationState {
        HOT,
        WARM,
        HOT2WARM,
        WARM2HOT
    }

    private int maxIndicesPerSnapshot;
    private List<MigrationState> states;

    IndexType(int concurrentWrites, List<MigrationState> states) {
        this.maxIndicesPerSnapshot = concurrentWrites;
        this.states = states;
    }

    public static IndexType fromMigrationState(MigrationState migrationState) {
        for (IndexType type : IndexType.values()) {
            if (type.states.contains(migrationState)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown index migration state");
    }

    public static IndexType fromIndexSettings(Settings settings) {
        String indexMigrationState = settings.get(IndexModule.INDEX_MIGRATION_STATE.getKey(), "HOT").toUpperCase();
        MigrationState migrationState = MigrationState.valueOf(indexMigrationState.toUpperCase());
        return fromMigrationState(migrationState);
    }

    /**
     * Get maximum number of indices of this index_type that can be specified in a
     * single snapshot
     * @return number of concurrent writes, -1 if no limit
     */
    public int getMaxIndicesPerSnapshot() {
        return maxIndicesPerSnapshot;
    }
}
