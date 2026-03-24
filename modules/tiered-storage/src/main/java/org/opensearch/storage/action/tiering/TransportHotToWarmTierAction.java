package org.opensearch.storage.action.tiering;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.storage.tiering.HotToWarmTieringService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

/**
 * Transport Tiering action to move indices from hot to warm
 */
public class TransportHotToWarmTierAction extends TransportTierAction {

    @Inject
    public TransportHotToWarmTierAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        HotToWarmTieringService hotToWarmTieringService
    ) {
        super(
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            indexNameExpressionResolver,
            HotToWarmTierAction.NAME,
            hotToWarmTieringService
        );
    }
}
