package org.opensearch.storage.tiering;

import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.DiskThresholdEvaluator;
import org.opensearch.cluster.routing.allocation.WarmNodeDiskThresholdEvaluator;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.indices.ShardLimitValidator;
import org.opensearch.storage.metrics.TierActionMetrics;

import java.util.Set;

import static org.opensearch.index.IndexModule.INDEX_COMPOSITE_STORE_TYPE_SETTING;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.IS_WARM_INDEX_SETTING;
import static org.opensearch.index.IndexModule.TieringState.HOT;
import static org.opensearch.index.IndexModule.TieringState.HOT_TO_WARM;
import static org.opensearch.index.IndexModule.TieringState.WARM;
import static org.opensearch.storage.TieredStoragePlugin.TIERED_COMPOSITE_INDEX_TYPE;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.validateHotToWarmTiering;
import static org.opensearch.storage.common.tiering.TieringUtils.FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_TIERING_START_TIME_KEY;

/**
 * Service responsible for tiering indices from hot to warm
 */
public class HotToWarmTieringService extends TieringService {

    /**
     * Disk Threshold Evaluator
     */
    private final DiskThresholdEvaluator diskThresholdEvaluator;

    /**
     * The threshold percentage for file cache active usage when tiering from hot to warm.
     */
    private Integer fileCacheActiveUsageThresholdPercent;

    /**
     * Constructs a new HotToWarmTieringService.
     *
     * @param settings cluster settings configuration
     * @param clusterService service for cluster operations
     * @param indexNameExpressionResolver resolver for index names
     * @param allocationService service for shard allocation
     */
    @Inject
    public HotToWarmTieringService(
            final Settings settings,
            final ClusterService clusterService,
            final ClusterInfoService clusterInfoService,
            final IndexNameExpressionResolver indexNameExpressionResolver,
            final AllocationService allocationService,
            final TierActionMetrics tierActionMetrics,
            final NodeEnvironment nodeEnvironment,
            final ShardLimitValidator shardLimitValidator
    ) {
        super(settings, clusterService, clusterInfoService, indexNameExpressionResolver,allocationService, tierActionMetrics, nodeEnvironment, shardLimitValidator);
        this.diskThresholdEvaluator = new WarmNodeDiskThresholdEvaluator(this.diskThresholdSettings, this.fileCacheSettings::getRemoteDataRatio);
        ClusterSettings clusterSettings = clusterService.getClusterSettings();
        clusterSettings.addSettingsUpdateConsumer(FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT, this::setFileCacheActiveUsageThreshold);
        setFileCacheActiveUsageThreshold(clusterSettings.get(FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT));
    }

    @Override
    protected void validateTieringRequest(final ClusterState clusterState,
                                          final ClusterInfoService clusterInfoService,
                                          final Set<Index> tieringIndices,
                                          final Integer maxConcurrentTieringRequests,
                                          Integer jvmActiveUsageThresholdPercent,
                                          final Index index){
        validateHotToWarmTiering(clusterState, clusterInfoService.getClusterInfo(), tieringIndices,
                maxConcurrentTieringRequests, diskThresholdEvaluator,
                fileCacheActiveUsageThresholdPercent, jvmActiveUsageThresholdPercent, index, shardLimitValidator);
    }

    @Override
    protected Settings getTieringStartSettingsToAdd() {
        return Settings.builder()
            .put(IS_WARM_INDEX_SETTING.getKey(), true)
            .put(INDEX_TIERING_STATE.getKey(), HOT_TO_WARM)
            .put(INDEX_COMPOSITE_STORE_TYPE_SETTING.getKey(), TIERED_COMPOSITE_INDEX_TYPE)
            .build();
    }

    @Override
    protected Settings getIndexTierSettingsToRestoreAfterCancellation() {
        return Settings.builder()
            .put(IS_WARM_INDEX_SETTING.getKey(), false)
            .put(INDEX_TIERING_STATE.getKey(), HOT)
            .put(INDEX_COMPOSITE_STORE_TYPE_SETTING.getKey(), "default")
            .build();
    }

    @Override
    protected Setting<Integer> getMaxConcurrentTieringRequestsSetting() {
        return H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS;
    }

    @Override
    protected String getTieringStartTimeKey() {
        return H2W_TIERING_START_TIME_KEY;
    }

    @Override
    protected IndexModule.TieringState getTargetTieringState() {
        return WARM;
    }

    @Override
    protected IndexModule.TieringState getTieringType() { return HOT_TO_WARM; }

    /**
     * Sets the file cache active usage threshold for tiering validations.
     *
     * @param fileCacheActiveUsageThreshold the threshold value to set
     */
    public void setFileCacheActiveUsageThreshold(Integer fileCacheActiveUsageThreshold) {
        this.fileCacheActiveUsageThresholdPercent = fileCacheActiveUsageThreshold;
    }

}
