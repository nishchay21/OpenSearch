/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.action.tiering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.broadcast.BroadcastResponse;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Priority;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.index.IndexModule;
import org.opensearch.storage.common.tiering.TieringUtils;
import org.opensearch.storage.tiering.HotToWarmTieringService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import static org.opensearch.storage.common.tiering.TieringUtils.resolveRequestIndex;

/**
 * Transport Tiering action to move indices from hot to warm.
 * For DFA (pluggable data format) indices, this action:
 * 1. Adds a write block to prevent new writes
 * 2. Performs pre-tiering sync (flush + refresh + remote store sync) on all primary shards
 * 3. Proceeds with the tiering operation
 *
 * Non-DFA indices skip steps 1 and 2 and go directly to tiering.
 */
public class TransportHotToWarmTierAction extends TransportTierAction {

    private static final Logger logger = LogManager.getLogger(TransportHotToWarmTierAction.class);
    private static final int MAX_PREPARE_RETRIES = 3;

    private final TransportPrepareTieringAction prepareTieringAction;
    private final HotToWarmTieringService hotToWarmTieringService;

    /**
     * Constructs a TransportHotToWarmTierAction.
     *
     * @param transportService the transport service
     * @param clusterService the cluster service
     * @param threadPool the thread pool
     * @param actionFilters the action filters
     * @param indexNameExpressionResolver the index name expression resolver
     * @param hotToWarmTieringService the hot to warm tiering service
     * @param prepareTieringAction the prepare tiering action for DFA indices
     */
    @Inject
    public TransportHotToWarmTierAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        HotToWarmTieringService hotToWarmTieringService,
        TransportPrepareTieringAction prepareTieringAction
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
        this.prepareTieringAction = prepareTieringAction;
        this.hotToWarmTieringService = hotToWarmTieringService;
    }

    @Override
    protected void clusterManagerOperation(IndexTieringRequest request, ClusterState state, ActionListener<AcknowledgedResponse> listener)
        throws Exception {
        if (TieringUtils.isDfaIndex(state.metadata().index(request.getIndex()))) {
            // Validate FIRST — before any state-mutating or expensive operations.
            // If validation fails (e.g. warm nodes full, too many concurrent requests),
            // reject immediately without adding a write block or running prepare.
            // Note: validation also runs inside TieringService.tier() for double-safety.
            try {
                Index index = resolveRequestIndex(indexNameExpressionResolver, request.getIndex(), state);
                hotToWarmTieringService.preflightValidate(state, index);
            } catch (Exception e) {
                logger.info("Preflight validation failed for DFA index [{}]: {}", request.getIndex(), e.getMessage());
                listener.onFailure(e);
                return;
            }
            logger.info("Index [{}] is a DFA index, adding write block and performing pre-tiering sync", request.getIndex());
            addWriteBlockAndPrepare(request, state, listener);
        } else {
            super.clusterManagerOperation(request, state, listener);
        }
    }

    /**
     * Step 1: Mark the DFA index as {@code PREPARING} and add a write block to prevent new writes during prepare.
     * Both the {@code blocks.write} index setting and the {@link IndexMetadata#INDEX_WRITE_BLOCK} cluster block are
     * applied: the setting persists the block in index metadata (so it survives and can be cleanly reverted on
     * cancel or failure), while the cluster block enforces it immediately. The {@code PREPARING} tiering state
     * freezes the engine (blocks merges/refresh/flush) for the duration of prepare.
     * On success, proceeds to step 2 (prepare tiering).
     */
    private void addWriteBlockAndPrepare(IndexTieringRequest request, ClusterState state, ActionListener<AcknowledgedResponse> listener) {
        clusterService.submitStateUpdateTask(
            "add-write-block-for-tiering [" + request.getIndex() + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    IndexMetadata indexMetadata = currentState.metadata().index(request.getIndex());
                    if (indexMetadata == null) {
                        throw new IllegalStateException("Index [" + request.getIndex() + "] not found");
                    }
                    // Block writes before pre-tiering sync. Both the blocks.write setting and the
                    // INDEX_WRITE_BLOCK cluster block are applied: the setting persists in index metadata
                    // (and, unlike read_only_allow_delete, cannot be auto-removed by DiskThresholdMonitor),
                    // while the cluster block enforces the block immediately. PREPARING freezes the engine.
                    Settings.Builder indexSettingsBuilder = Settings.builder()
                        .put(indexMetadata.getSettings())
                        .put(IndexMetadata.INDEX_BLOCKS_WRITE_SETTING.getKey(), true)
                        .put(IndexModule.INDEX_TIERING_STATE.getKey(), IndexModule.TieringState.PREPARING.name());

                    IndexMetadata.Builder indexMetadataBuilder = IndexMetadata.builder(indexMetadata)
                        .settings(indexSettingsBuilder)
                        .settingsVersion(1 + indexMetadata.getSettingsVersion());

                    Metadata.Builder metadataBuilder = Metadata.builder(currentState.metadata()).put(indexMetadataBuilder);
                    ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                    blocks.addIndexBlock(request.getIndex(), IndexMetadata.INDEX_WRITE_BLOCK);

                    return ClusterState.builder(currentState).metadata(metadataBuilder).blocks(blocks).build();
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    logger.info("Write block added for index [{}], proceeding with pre-tiering sync", request.getIndex());
                    executePrepareTiering(request, newState, listener, 1);
                }

                @Override
                public void onFailure(String source, Exception e) {
                    logger.error(() -> "Failed to add write block for index [" + request.getIndex() + "]", e);
                    listener.onFailure(
                        new IllegalStateException("Failed to add write block for DFA index [" + request.getIndex() + "]. Please retry.", e)
                    );
                }
            }
        );
    }

    /**
     * Step 2: Execute the prepare tiering action (flush + refresh + waitForRemoteStoreSync) on primary shards.
     * Retries up to MAX_PREPARE_RETRIES times on shard failures before giving up.
     * On success, proceeds to step 3 (tier).
     * On final failure, rolls back the PREPARING state (removes the write block and resets the tiering
     * state to HOT) to avoid leaving the index in a stuck, write-blocked, frozen state.
     */
    private void executePrepareTiering(
        IndexTieringRequest request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener,
        int attempt
    ) {
        PrepareTieringRequest prepareTieringRequest = new PrepareTieringRequest(request.getIndex());
        // Use the cluster setting for timeout instead of the short AcknowledgedRequest default (30s).
        // This controls both the transport channel timeout and the merge drain timeout on the data node.
        prepareTieringRequest.timeout(TieringUtils.PREPARE_TIERING_TIMEOUT.get(clusterService.getSettings()));

        prepareTieringAction.execute(prepareTieringRequest, new ActionListener<BroadcastResponse>() {
            @Override
            public void onResponse(BroadcastResponse broadcastResponse) {
                if (broadcastResponse.getFailedShards() > 0) {
                    if (attempt < MAX_PREPARE_RETRIES) {
                        logger.warn(
                            "Pre-tiering sync attempt [{}/{}] had {} failed shard(s) for index [{}], retrying",
                            attempt,
                            MAX_PREPARE_RETRIES,
                            broadcastResponse.getFailedShards(),
                            request.getIndex()
                        );
                        executePrepareTiering(request, state, listener, attempt + 1);
                        return;
                    }
                    String errorMsg = "Pre-tiering sync failed for index ["
                        + request.getIndex()
                        + "] after "
                        + MAX_PREPARE_RETRIES
                        + " attempts: "
                        + broadcastResponse.getFailedShards()
                        + " shard(s) failed. Please retry the tiering request.";
                    logger.error(errorMsg);
                    rollbackPreparing(request.getIndex());
                    listener.onFailure(new IllegalStateException(errorMsg));
                    return;
                }
                logger.info("Pre-tiering sync completed for index [{}], proceeding with tiering", request.getIndex());
                try {
                    TransportHotToWarmTierAction.super.clusterManagerOperation(request, state, listener);
                } catch (Exception e) {
                    rollbackPreparing(request.getIndex());
                    listener.onFailure(e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                if (attempt < MAX_PREPARE_RETRIES) {
                    logger.warn(
                        "Pre-tiering sync attempt [{}/{}] failed for index [{}], retrying: {}",
                        attempt,
                        MAX_PREPARE_RETRIES,
                        request.getIndex(),
                        e
                    );
                    executePrepareTiering(request, state, listener, attempt + 1);
                    return;
                }
                String errorMsg = "Pre-tiering sync failed for DFA index ["
                    + request.getIndex()
                    + "] after "
                    + MAX_PREPARE_RETRIES
                    + " attempts. Please retry.";
                logger.error(errorMsg, e);
                rollbackPreparing(request.getIndex());
                listener.onFailure(new IllegalStateException(errorMsg, e));
            }
        });
    }

    /**
     * Rolls back the {@code PREPARING} transition on prepare failure: removes the write block and, if the index
     * is still in {@code PREPARING}, resets the tiering state back to {@code HOT} so the engine unfreezes and
     * normal indexing/merges resume. The tiering state is only reset when still {@code PREPARING} to avoid
     * clobbering a state that already advanced to {@code HOT_TO_WARM} (e.g. when {@code tier()} partially
     * succeeded before throwing).
     * Best-effort — if this fails, the index may remain write-blocked and/or frozen in {@code PREPARING};
     * both can be reverted manually via index settings.
     */
    private void rollbackPreparing(String indexName) {
        clusterService.submitStateUpdateTask(
            "rollback-preparing-for-tiering [" + indexName + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    IndexMetadata indexMetadata = currentState.metadata().index(indexName);
                    if (indexMetadata == null) {
                        return currentState;
                    }
                    Settings.Builder indexSettingsBuilder = Settings.builder()
                        .put(indexMetadata.getSettings())
                        .put(IndexMetadata.INDEX_BLOCKS_WRITE_SETTING.getKey(), false);

                    // Only revert the tiering state if it is still PREPARING. If tier() already advanced it to
                    // HOT_TO_WARM, leave it untouched so we don't corrupt a partially-succeeded migration.
                    String currentTieringState = indexMetadata.getSettings()
                        .get(IndexModule.INDEX_TIERING_STATE.getKey(), IndexModule.TieringState.HOT.name());
                    if (IndexModule.TieringState.PREPARING.name().equals(currentTieringState)) {
                        indexSettingsBuilder.put(IndexModule.INDEX_TIERING_STATE.getKey(), IndexModule.TieringState.HOT.name());
                    }

                    IndexMetadata.Builder indexMetadataBuilder = IndexMetadata.builder(indexMetadata)
                        .settings(indexSettingsBuilder)
                        .settingsVersion(1 + indexMetadata.getSettingsVersion());

                    Metadata.Builder metadataBuilder = Metadata.builder(currentState.metadata()).put(indexMetadataBuilder);
                    ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                    blocks.removeIndexBlock(indexName, IndexMetadata.INDEX_WRITE_BLOCK);

                    return ClusterState.builder(currentState).metadata(metadataBuilder).blocks(blocks).build();
                }

                @Override
                public void onFailure(String source, Exception e) {
                    logger.warn(
                        () -> "Failed to roll back PREPARING state for index ["
                            + indexName
                            + "] after tiering failure. The write block and/or PREPARING tiering state may "
                            + "need to be reverted manually via index settings.",
                        e
                    );
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    logger.info("Rolled back PREPARING state (removed write block, reset tiering state) for index [{}]", indexName);
                }
            }
        );
    }
}
