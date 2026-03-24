package org.opensearch.storage.tiering;

import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.storage.TieredStoragePlugin;
import org.opensearch.storage.metrics.TierActionMetrics;
import org.opensearch.indices.ShardLimitValidator;

import java.util.Set;

import static org.opensearch.index.IndexModule.INDEX_COMPOSITE_STORE_TYPE_SETTING;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.IS_WARM_INDEX_SETTING;
import static org.opensearch.index.IndexModule.TieringState.HOT;
import static org.opensearch.index.IndexModule.TieringState.WARM;
import static org.opensearch.index.IndexModule.TieringState.WARM_TO_HOT;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.validateWarmToHotTiering;
import static org.opensearch.storage.common.tiering.TieringUtils.W2H_MAX_CONCURRENT_TIEIRNG_REQUESTS;
import static org.opensearch.storage.common.tiering.TieringUtils.W2H_TIERING_START_TIME_KEY;

/**
 * Service responsible for tiering indices from warm to hot
 */
public class WarmToHotTieringService extends TieringService {

    @Inject
    public WarmToHotTieringService(
            final Settings settings,
            final ClusterService clusterService,
            final ClusterInfoService clusterInfoService,
            final IndexNameExpressionResolver indexNameExpressionResolver,
            final AllocationService allocationService,
            final TierActionMetrics tierActionMetrics,
            final NodeEnvironment nodeEnvironment,
            final ShardLimitValidator shardLimitValidator
    ) {
        super(settings, clusterService, clusterInfoService, indexNameExpressionResolver, allocationService, tierActionMetrics, nodeEnvironment, shardLimitValidator);
    }

    @Override
    protected void validateTieringRequest(final ClusterState clusterState,
                                          final ClusterInfoService clusterInfoService,
                                          final Set<Index> tieringIndices,
                                          Integer maxConcurrentTieringRequests,
                                          Integer jvmActiveUsageThresholdPercent,
                                          final Index index) {
        validateWarmToHotTiering(clusterState, clusterInfoService.getClusterInfo(),
                tieringIndices, maxConcurrentTieringRequests, jvmActiveUsageThresholdPercent,
                index, shardLimitValidator);
    }

    @Override
    protected Settings getTieringStartSettingsToAdd() {
        return Settings.builder()
            .put(IS_WARM_INDEX_SETTING.getKey(), false)
            .put(INDEX_TIERING_STATE.getKey(), WARM_TO_HOT)
            .put(INDEX_COMPOSITE_STORE_TYPE_SETTING.getKey(), "default")
            .build();
    }

    @Override
    protected Settings getIndexTierSettingsToRestoreAfterCancellation()
    {
        return Settings.builder()
            .put(IS_WARM_INDEX_SETTING.getKey(), true)
            .put(INDEX_TIERING_STATE.getKey(), WARM)
            .put(INDEX_COMPOSITE_STORE_TYPE_SETTING.getKey(), TieredStoragePlugin.TIERED_COMPOSITE_INDEX_TYPE)
            .build();
    }

    @Override
    protected String getTieringStartTimeKey() {
        return W2H_TIERING_START_TIME_KEY;
    }

    @Override
    protected Setting<Integer> getMaxConcurrentTieringRequestsSetting() {
        return W2H_MAX_CONCURRENT_TIEIRNG_REQUESTS;
    }

    @Override
    protected IndexModule.TieringState getTargetTieringState() {
        return HOT;
    }

    @Override
    protected IndexModule.TieringState getTieringType() { return WARM_TO_HOT; }

}
