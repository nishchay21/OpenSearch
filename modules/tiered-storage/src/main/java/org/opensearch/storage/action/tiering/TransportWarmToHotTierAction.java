package org.opensearch.storage.action.tiering;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.storage.tiering.WarmToHotTieringService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

/**
 * Transport Tiering action to move indices from warm to hot
 */
public class TransportWarmToHotTierAction extends TransportTierAction {

    @Inject
    public TransportWarmToHotTierAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        WarmToHotTieringService warmToHotTieringService
    ) {
        super(
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            indexNameExpressionResolver,
            WarmToHotTierAction.NAME,
            warmToHotTieringService
        );
    }
}
