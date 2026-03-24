package org.opensearch.index.shard;

import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.UnassignedInfo;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.index.IndexModule;
import org.opensearch.index.IndexSettings;
import org.opensearch.repositories.IndexId;
import org.opensearch.snapshots.IndexType;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper functions for ultrawarm shard operations
 *
 * @opensearch.internal
 */
public final class UltrawarmShardUtils {

    public static final String NODE_BOX_TYPE_ATTRIBUTE_KEY = "box_type";

    public static final String INDEX_BOX_TYPE_SETTING_KEY = "index.routing.allocation.require.box_type";

    public static final String HOT_BOX_TYPE = "hot";

    public static final String WARM_BOX_TYPE = "warm";

    public static final String KRAKEN_REPO = "cs-ultrawarm";

    public static final String MISSING_BOX_TYPE = "box-type-missing";

    public static final String COLD_INDEX_SETTING_PREFIX = "index.cold.*";

    // keeping public for use by kraken plugin
    public static final Set<String> ULTRAWARM_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT = new HashSet<>(
        Arrays.asList(
            "index.ultrawarm.prefetch.doc_value.enabled",
            "index.snapshot.id",
            "index.archived.path",
            "index.ultrawarm.migration.snapshot.name",
            IndexMetadata.INDEX_ROUTING_SEARCH_PREFERENCE.getKey(),
            IndexModule.INDEX_STORE_TYPE_SETTING.getKey(),
            IndexModule.INDEX_MIGRATION_STATE.getKey(),
            IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(),
            UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(),
            IndexMetadata.SETTING_ULTRAWARM_ALLOW_DELETE,
            INDEX_BOX_TYPE_SETTING_KEY
        )
    );

    public static final Set<String> HOT_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT = new HashSet<>(
        Collections.singletonList(IndexModule.INDEX_MIGRATION_STATE.getKey())
    );

    public static final Set<String> COLD_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT = new HashSet<>(
        Arrays.asList(COLD_INDEX_SETTING_PREFIX, IndexMetadata.SETTING_LEVIATHAN_READ_ONLY)
    );

    private UltrawarmShardUtils() {}

    /**
     * Checks if the index is in migrating state from warm to hot
     * @param settings - settings with index state
     * @return true - index is migrating from warm to hot
     *         false - index is not migrating from warm to hot
     */
    public static boolean migratingFromWarmToHot(Settings settings) {
        return IndexType.MigrationState.WARM2HOT.toString()
            .equals(settings.get(IndexModule.INDEX_MIGRATION_STATE.getKey(), IndexType.MigrationState.HOT.toString()).toUpperCase());
    }

    /**
     * Checks if the index is in migrating state from hot to warm
     * @param settings - settings with index state
     * @return true - index is migrating from hot to warm
     *         false - index is not migrating from hot to warm
     */
    public static boolean migratingFromHotToWarm(Settings settings) {
        return IndexType.MigrationState.HOT2WARM.toString()
            .equals(settings.get(IndexModule.INDEX_MIGRATION_STATE.getKey(), IndexType.MigrationState.HOT.toString()).toUpperCase());
    }

    /**
     * Checks if the index is in migrating state from hot-to-warm or from warm-to-hot
     * @param settings - settings with index state
     * @return true - index is migrating from hot-to-warm or from warm-to-hot
     *         false - index is not migrating from hot-to-warm or from warm-to-hot
     */
    public static boolean migratingFromHotToWarmOrWarmToHot(Settings settings) {
        return UltrawarmShardUtils.migratingFromHotToWarm(settings) || UltrawarmShardUtils.migratingFromWarmToHot(settings);
    }

    public static boolean isHotIndex(Settings settings) {
        String indexMigrationState = settings.get(IndexModule.INDEX_MIGRATION_STATE.getKey());
        if (indexMigrationState == null || indexMigrationState.equals("")) {
            indexMigrationState = IndexType.MigrationState.HOT.toString();
        }
        return IndexType.MigrationState.HOT == IndexType.MigrationState.valueOf(indexMigrationState.toUpperCase());
    }

    /**
     * This utility will declare a shard as Ultrawarm shard in following cases:
     * <ul>
     *     <li>
     *         Hot to warm migrating index which is in RUNNING_SHARD_RELOCATION state (determined by migration state and box_type setting) and shard is assigned
     *         to a warm node. There can be a case where shards of a hot index is already on warm nodes and the migration request doesn't move those shards to
     *         any different node so that they are loaded again. These shards will be treated as an Ultrawarm shard which should be fine, since next time they are reloaded
     *         they wont do any state setup.
     *     </li>
     *     <li>
     *         Warm index whose shards are on warm nodes. An index warm status is determined by the migration state of WARM. It is expected that warm index will never
     *         have a shard on hot nodes otherwise we will end up doing setup for those shards and see unexpected issues.
     *     </li>
     *     <li>
     *         Warm to hot migrating index whose shards are on warm node. Here we are not checking for box_type setting of index because for warm to hot migrating index
     *         even before relocation is started based on box_type, the shards on warm nodes should be treated as an Ultrawarm shard
     *     </li>
     * </ul>
     * @param routing - {@link ShardRouting} to evaluate
     * @param settings - settings for the index
     * @return true - input {@link ShardRouting} is an Ultrawarm shard
     *         false - otherwise
     */
    public static boolean isUltrawarmShard(ShardRouting routing, Settings settings) {
        assert routing.state() != ShardRoutingState.UNASSIGNED : "Shard instance is not assigned to any node";
        final String indexBoxTypeSetting = settings.get(INDEX_BOX_TYPE_SETTING_KEY, HOT_BOX_TYPE);
        final String indexMigrationState = settings.get(IndexModule.INDEX_MIGRATION_STATE.getKey(), IndexType.MigrationState.HOT.toString())
            .toUpperCase();
        final String shardNodeBoxType = routing.getAssignedNodeBoxType();
        boolean isUltrawarmShard = false;

        if (((IndexType.MigrationState.HOT2WARM.toString().equals(indexMigrationState) && WARM_BOX_TYPE.equals(indexBoxTypeSetting))
            || (IndexType.MigrationState.WARM.toString().equals(indexMigrationState))
            || (IndexType.MigrationState.WARM2HOT.toString().equals(indexMigrationState))) && WARM_BOX_TYPE.equals(shardNodeBoxType)) {
            isUltrawarmShard = true;
        }
        return isUltrawarmShard;
    }

    public static boolean skipReplicationTrackerInvariant(
        Settings settings,
        IndexShardRoutingTable routingTable,
        String shardAllocationId
    ) {
        // routingTable will be null when shard is created for the very first time
        final ShardRouting localShardRouting = (routingTable == null) ? null : routingTable.getByAllocationId(shardAllocationId);
        return (routingTable != null && localShardRouting != null && UltrawarmShardUtils.isUltrawarmShard(localShardRouting, settings));
    }

    public static boolean isUltrawarmNode(DiscoveryNode node) {
        return node.getAttributes().getOrDefault(NODE_BOX_TYPE_ATTRIBUTE_KEY, MISSING_BOX_TYPE).equals(WARM_BOX_TYPE);
    }

    /**
     * Utility to remove index settings from the index metadata of provided indices depending upon the migration state. This is used by
     * {@link org.opensearch.snapshots.SnapshotsService} to avoid persisting internal settings while taking snapshot in any repo. Mainly,
     * this utility is introduced to handle a case where a manual snapshot of a warm index is taken by customer in its private repo, which was
     * also persisting the warm index settings and settings for warm index migrating to/from cold tier. For hot index, as well we don't want to
     * persist few settings (like cold.uuid) applied when index was migrated from cold to warm to hot. We want to avoid exposing these settings.
     * @param indices - input list of indices
     * @param metadata - current metadata
     * @return updated metadata without filtered settings
     */
    public static Metadata filterWarmSettings(List<IndexId> indices, Metadata metadata) {
        Metadata.Builder updatedMetadataBuilder = new Metadata.Builder(metadata);
        final Map<String, IndexMetadata> allIndices = metadata.indices();

        for (IndexId indexId : indices) {
            final String indexName = indexId.getName();
            final IndexMetadata indexMetadata = allIndices.get(indexName);
            // indexMetadata can be null if index is already deleted
            if (indexMetadata == null) {
                continue;
            }

            final Settings indexSettings = indexMetadata.getSettings();
            // For hot index only remove the migration state. For Leviathan indices, remove the leviathan settings.
            // For other indices (warm and migrating) remove all the warm index settings
            final Set<String> settingsToFilter = new HashSet<>(COLD_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT);
            if (isHotIndex(indexSettings)) {
                settingsToFilter.addAll(HOT_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT);
            } else {
                settingsToFilter.addAll(ULTRAWARM_INDEX_SETTINGS_FILTER_FOR_SNAPSHOT);
            }

            final Settings indexSettingsToSnapshot = new SettingsFilter(settingsToFilter).filter(indexSettings);
            // update the index settings
            final IndexMetadata.Builder updatedIdxMetadata = new IndexMetadata.Builder(indexMetadata).settings(indexSettingsToSnapshot);
            updatedMetadataBuilder = updatedMetadataBuilder.put(updatedIdxMetadata.build(), false);
        }
        return updatedMetadataBuilder.build();
    }
}
