package org.opensearch.storage.tiering;

import org.opensearch.Version;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.ack.ClusterStateUpdateResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.DiskThresholdSettings;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Priority;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.index.store.remote.filecache.FileCacheSettings;
import org.opensearch.indices.ShardLimitValidator;
import org.opensearch.storage.action.tiering.IndexTieringRequest;
import org.opensearch.storage.action.tiering.status.model.TieringStatus;
import org.opensearch.storage.common.tiering.TieringUtils;
import org.opensearch.storage.metrics.TierActionMetrics;
import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentCaptor;

import static org.opensearch.cluster.metadata.IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING;
import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.opensearch.index.IndexModule.INDEX_STORE_LOCALITY_SETTING;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.IS_WARM_INDEX_SETTING;
import static org.opensearch.index.IndexModule.TieringState;
import static org.opensearch.index.IndexModule.TieringState.HOT_TO_WARM;
import static org.opensearch.storage.action.tiering.status.model.TieringStatus.PENDING_SHARDS;
import static org.opensearch.storage.action.tiering.status.model.TieringStatus.RUNNING_SHARDS;
import static org.opensearch.storage.action.tiering.status.model.TieringStatus.SUCCEEDED_SHARDS;
import static org.opensearch.storage.action.tiering.status.model.TieringStatus.TOTAL_SHARDS;
import static org.opensearch.storage.common.tiering.TieringUtils.TIERING_CUSTOM_KEY;
import static org.opensearch.storage.common.tiering.TieringUtils.isShardStateValidForTier;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class TieringServiceTests extends OpenSearchTestCase {

    private TestTieringService tieringService;
    private ClusterService clusterService;
    private ClusterState clusterState;
    private Index testIndex;
    private IndexMetadata indexMetadata;
    private IndexNameExpressionResolver indexNameExpressionResolver;
    private Settings indexSettings;
    private TierActionMetrics tierActionMetrics;
    private NodeEnvironment nodeEnvironment;
    private ShardLimitValidator shardLimitValidator;
    Setting<Integer> maxConcurrentTieringRequestsSetting = Setting.intSetting(
            "test_max_tiering_requests",
            50,
            0,
            1000,
            Setting.Property.Dynamic,
            Setting.Property.NodeScope
    );
    Setting<Integer> jvmUsageActiveUsageThreshold = TieringUtils.JVM_USAGE_TIERING_THRESHOLD_PERCENT;

    private class TestTieringService extends TieringService {
        public TestTieringService(Settings settings, ClusterService clusterService,
                                  ClusterInfoService clusterInfoService,
                                  IndexNameExpressionResolver indexNameExpressionResolver,
                                  AllocationService allocationService,
                                  TierActionMetrics tierActionMetrics,
                                  NodeEnvironment nodeEnvironment,
                                  ShardLimitValidator shardLimitValidator) {
            super(settings, clusterService, clusterInfoService,indexNameExpressionResolver,allocationService, tierActionMetrics, nodeEnvironment, shardLimitValidator);
        }

        @Override
        protected Settings getIndexTierSettingsToRestoreAfterCancellation() {
            return Settings.builder()
                .put(INDEX_STORE_LOCALITY_SETTING.getKey(), IndexModule.DataLocalityType.PARTIAL)
                .put(IS_WARM_INDEX_SETTING.getKey(), false)
                .build();
        }

        @Override
        protected Settings getTieringStartSettingsToAdd() {
            return Settings.builder()
                .put(INDEX_STORE_LOCALITY_SETTING.getKey(), IndexModule.DataLocalityType.PARTIAL)
                .put(IS_WARM_INDEX_SETTING.getKey(), true)
                .build();
        }

        @Override
        protected String getTieringStartTimeKey() {
            return "test_tiering_start_time";
        }

        @Override
        protected Setting<Integer> getMaxConcurrentTieringRequestsSetting() {
            return maxConcurrentTieringRequestsSetting;
        }

        @Override
        protected IndexModule.TieringState getTargetTieringState() {
            return IndexModule.TieringState.WARM;
        }

        @Override
        protected void validateTieringRequest(ClusterState clusterState,
                                              ClusterInfoService service,
                                              Set<Index> tieringEntries,
                                              Integer maxConcurrentTieringRequests,
                                              Integer jvmActiveUsageThresholdPercent,
                                              Index index) {}

        @Override
        protected boolean isShardInTargetTier(ShardRouting shard, ClusterState clusterState) {
            return true;
        }

        @Override
        public void clusterChanged(ClusterChangedEvent event) {}

        @Override
        protected IndexModule.TieringState getTieringType() { return HOT_TO_WARM; }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        clusterService = mock(ClusterService.class);
        clusterState = mock(ClusterState.class);
        var metricRegistry = mock(MetricsRegistry.class);
        var mockCounter = mock(Counter.class);
        when(metricRegistry.createCounter(any(), any(), any())).thenReturn(mockCounter);
        tierActionMetrics = new TierActionMetrics(metricRegistry);
        nodeEnvironment = newNodeEnvironment();
        shardLimitValidator = mock(ShardLimitValidator.class);
        indexNameExpressionResolver = mock(IndexNameExpressionResolver.class);

        testIndex = new Index("test-index", "uuid");
        indexSettings = Settings.builder()
                .put(INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                .put(INDEX_TIERING_STATE.getKey(), "HOT")
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, "uuid")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .build();

        indexMetadata = IndexMetadata.builder("test-index")
                .settings(indexSettings)
                .numberOfShards(1)
                .numberOfReplicas(2)
                .build();

        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(maxConcurrentTieringRequestsSetting);
        clusterSettingsToAdd.add(jvmUsageActiveUsageThreshold);
        clusterSettingsToAdd.add((FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING));

        Settings deafultSettings = Settings.builder()
                .put(INDEX_STORE_LOCALITY_SETTING.getKey(), IndexModule.DataLocalityType.PARTIAL)
                .put(IS_WARM_INDEX_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "300b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "200b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "100b")
                .put(maxConcurrentTieringRequestsSetting.getKey(),50)
                .put(jvmUsageActiveUsageThreshold.getKey(),90)
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 5.0)
                .build();

        ClusterSettings mockSettings = new ClusterSettings(Settings.EMPTY, clusterSettingsToAdd);

        when(clusterService.getClusterSettings()).thenReturn(mockSettings);

        tieringService = new TestTieringService(
                deafultSettings,
                clusterService,
                mock(ClusterInfoService.class),
                indexNameExpressionResolver,
                mock(AllocationService.class),
                tierActionMetrics,
                nodeEnvironment,
                shardLimitValidator
        );
    }

    @Override
    public void tearDown() throws Exception{
        IOUtils.close(nodeEnvironment);
        super.tearDown();
    }

    @AwaitsFix(bugUrl = "")
    public void testProcessTieringInProgress_CompletesSuccessfully() {
        tieringService.tieringIndices.add(testIndex);
        RoutingTable routingTable = mock(RoutingTable.class);
        List<ShardRouting> shardRoutings = Collections.singletonList(mock(ShardRouting.class));
        Metadata metadata = mock(Metadata.class);

        when(clusterState.routingTable()).thenReturn(routingTable);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);
        when(routingTable.hasIndex(testIndex)).thenReturn(true);
        when(routingTable.allShards(testIndex.getName())).thenReturn(shardRoutings);

        tieringService.processTieringInProgress(clusterState, "test_source");

        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        ClusterStateUpdateTask capturedTask = taskCaptor.getValue();
        assertEquals(Priority.NORMAL, capturedTask.priority());
        capturedTask.clusterStateProcessed("test_source", clusterState, clusterState);

        assertTrue("Index should be removed after cluster state is processed",
            tieringService.tieringIndices.isEmpty());
    }


    public void testIsShardStateValidForTier_ValidatesCorrectly() {
        // Setup
        ShardRouting shard = mock(ShardRouting.class);
        DiscoveryNode node = mock(DiscoveryNode.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);

        when(shard.unassigned()).thenReturn(false);
        when(shard.started()).thenReturn(true);
        when(shard.currentNodeId()).thenReturn("node1");
        when(clusterState.getNodes()).thenReturn(nodes);
        when(nodes.get("node1")).thenReturn(node);
        when(node.isWarmNode()).thenReturn(true);

        assertTrue(isShardStateValidForTier(shard, clusterState, IndexModule.TieringState.WARM));

        when(node.isWarmNode()).thenReturn(false);
        assertFalse(isShardStateValidForTier(shard, clusterState, IndexModule.TieringState.WARM));
    }


    public void testTier_InitiatesSuccessfully() {
        // Setup
        IndexTieringRequest request = new IndexTieringRequest("WARM", "test-index");
        ActionListener<ClusterStateUpdateResponse> listener = mock(ActionListener.class);
        IndexNameExpressionResolver resolver = mock(IndexNameExpressionResolver.class);

        tieringService = new TestTieringService(
                Settings.EMPTY,
                clusterService,
                mock(ClusterInfoService.class),
                resolver,
                mock(AllocationService.class),
                tierActionMetrics,
                nodeEnvironment,
                shardLimitValidator
        );

        when(resolver.concreteIndices(
            eq(clusterState),
            eq(IndicesOptions.STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED),
            eq("test-index")
        )).thenReturn(new Index[]{testIndex});

        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);

        RoutingTable routingTable = mock(RoutingTable.class);
        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.hasIndex(testIndex)).thenReturn(true);

        tieringService.tier(request, listener, clusterState);

        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        ClusterStateUpdateTask capturedTask = taskCaptor.getValue();
        assertEquals(Priority.URGENT, capturedTask.priority());

        capturedTask.clusterStateProcessed("test_source", clusterState, clusterState);
        assertTrue("Index should be added to tieringInProgress",
            tieringService.tieringIndices.contains(testIndex));
    }


    public void testUpdateIndexMetadataForTieringStart_HandlesReplicaReduction() {
        Metadata.Builder metadataBuilder = mock(Metadata.Builder.class);
        RoutingTable.Builder routingTableBuilder = mock(RoutingTable.Builder.class);

        tieringService.updateIndexMetadataForTieringStart(
            metadataBuilder,
            routingTableBuilder,
            indexMetadata,
            testIndex
        );

        verify(routingTableBuilder).updateNumberOfReplicas(eq(1), any(String[].class));
        verify(metadataBuilder).updateNumberOfReplicas(eq(1), any(String[].class));
    }


    public void testUpdateIndexMetadataPostTiering_UpdatesCorrectly() {
        Metadata.Builder metadataBuilder = mock(Metadata.Builder.class);
        when(metadataBuilder.put(any(IndexMetadata.Builder.class))).thenReturn(metadataBuilder);

        tieringService.updateIndexMetadataPostTiering(metadataBuilder, indexMetadata);

        ArgumentCaptor<IndexMetadata.Builder> captor = ArgumentCaptor.forClass(IndexMetadata.Builder.class);
        verify(metadataBuilder).put(captor.capture());

        IndexMetadata updatedMetadata = captor.getValue().build();
        assertEquals(
            tieringService.getTargetTieringState().toString(),
            updatedMetadata.getSettings().get(INDEX_TIERING_STATE.getKey())
        );

        assertNull("Tiering custom metadata should be removed", updatedMetadata.getCustomData(TIERING_CUSTOM_KEY));
    }


    public void testProcessTieringInProgress_HandlesDeletedIndex() {
        tieringService.tieringIndices.add(testIndex);
        RoutingTable routingTable = mock(RoutingTable.class);
        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.hasIndex(testIndex)).thenReturn(false);

        tieringService.processTieringInProgress(clusterState, "test_source");

        verify(clusterService, never()).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
        assertTrue("Index should be removed from tieringInProgress", tieringService.tieringIndices.isEmpty());
    }


    public void testProcessTieringInProgress_HandlesIncompleteShardRelocation() {
        tieringService.tieringIndices.add(testIndex);
        RoutingTable routingTable = mock(RoutingTable.class);
        ShardRouting shard = mock(ShardRouting.class);
        List<ShardRouting> shardRoutings = Collections.singletonList(shard);

        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.hasIndex(testIndex)).thenReturn(true);
        when(routingTable.allShards(testIndex.getName())).thenReturn(shardRoutings);

        TestTieringService spyService = spy(tieringService);
        when(spyService.isShardInTargetTier(any(), any())).thenReturn(false);

        spyService.processTieringInProgress(clusterState, "test_source");

        verify(clusterService, never()).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
        assertTrue("Index should remain in tieringInProgress", spyService.tieringIndices.contains(testIndex));
    }


    public void testIsShardStateValidForTier_HandlesInvalidTierState() {
        ShardRouting shard = mock(ShardRouting.class);
        expectThrows(IllegalArgumentException.class, () ->
            isShardStateValidForTier(shard, clusterState, TieringState.HOT_TO_WARM)
        );
    }


    public void testTier_HandlesValidationFailure() {
        IndexTieringRequest request = new IndexTieringRequest("WARM", "test-index");
        ActionListener<ClusterStateUpdateResponse> listener = mock(ActionListener.class);
        IndexNameExpressionResolver resolver = mock(IndexNameExpressionResolver.class);

        TestTieringService spyService = spy(new TestTieringService(
                Settings.EMPTY,
                clusterService,
                mock(ClusterInfoService.class),
                resolver,
                mock(AllocationService.class),
                tierActionMetrics,
                nodeEnvironment,
                shardLimitValidator
        ));

        when(resolver.concreteIndices(
            eq(clusterState),
            eq(IndicesOptions.STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED),
            eq("test-index")
        )).thenReturn(new Index[]{testIndex});

        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);

        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);

        spyService.tier(request, listener, clusterState);

        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        ClusterStateUpdateTask task = taskCaptor.getValue();
        doThrow(new IllegalArgumentException("Index is already in target tier"))
            .when(spyService).validateTieringRequest(any(), any(),any(),any(),any(),any());

        Exception thrown = expectThrows(IllegalArgumentException.class, () ->
            task.execute(clusterState)
        );

        assertEquals("Index is already in target tier", thrown.getMessage());
        task.onFailure("test_source", thrown);
        verify(listener).onFailure(thrown);
        assertTrue("Index should not be added to tieringInProgress",
            spyService.tieringIndices.isEmpty());
    }

    public void testTier_getStatusWithUpdatedIndexMetadata() {
        Index mockIndex = new Index("test-index", "uuid");
        IndexNameExpressionResolver resolver = mock(IndexNameExpressionResolver.class);

        indexSettings = Settings.builder()
                .put(INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                .put(INDEX_TIERING_STATE.getKey(), "HOT_TO_WARM")
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, "uuid")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .build();

        indexMetadata = IndexMetadata.builder("test-index")
                .settings(indexSettings)
               // .putCustom(TIERING_CUSTOM_KEY,customIndexMetadata)
                .numberOfShards(1)
                .numberOfReplicas(2)
                .build();

        when(resolver.concreteIndices(
                any(),
                any(),
                eq("test-index")
        )).thenReturn(new Index[]{mockIndex});

        Metadata metadata = mock(Metadata.class);
        when(clusterState.getMetadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);
        when(clusterService.state()).thenReturn(clusterState);
        when(metadata.getIndexSafe(testIndex)).thenReturn(indexMetadata);

        TestTieringService spyService = spy(new TestTieringService(
                Settings.EMPTY,
                clusterService,
                mock(ClusterInfoService.class),
                resolver,
                mock(AllocationService.class),
                tierActionMetrics,
                nodeEnvironment,
                shardLimitValidator
        ));

        spyService.tieringIndices.add(mockIndex);
        assertEquals(0,spyService.listTieringStatus().size());

        assertThrows(IllegalArgumentException.class,
                ()-> spyService.getTieringStatus("test-index", false));

    }

    public void testTier_getAndListStatus() {
        Index mockIndex = new Index("test-index", "uuid");
        IndexNameExpressionResolver resolver = mock(IndexNameExpressionResolver.class);

        Map<String,String> tieringCustomData = new HashMap<>();
        tieringCustomData.put("test_tiering_start_time","1234567");

        indexSettings = Settings.builder()
                .put(INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                .put(INDEX_TIERING_STATE.getKey(), "HOT_TO_WARM")
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, "uuid")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .build();

        indexMetadata = IndexMetadata.builder("test-index")
                .settings(indexSettings)
                .putCustom(TIERING_CUSTOM_KEY, tieringCustomData)
                .numberOfShards(1)
                .numberOfReplicas(2)
                .build();

        when(resolver.concreteIndices(
                any(),
                any(),
                eq("test-index")
        )).thenReturn(new Index[]{mockIndex});

        // Create dummy routing table
        RoutingTable.Builder routingTableBuilder = mock(RoutingTable.Builder.class);
        RoutingTable routingTable = mock(RoutingTable.class);
        List<ShardRouting> shardRoutings = createDummyShardRoutings();

        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.allShards(mockIndex.getName())).thenReturn(shardRoutings);

        // Mock node information
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        DiscoveryNode warmNode = mock(DiscoveryNode.class);
        when(clusterState.getNodes()).thenReturn(nodes);
        when(nodes.get(anyString())).thenReturn(warmNode);
        when(warmNode.isWarmNode()).thenReturn(true);

        Metadata metadata = mock(Metadata.class);
        when(clusterState.getMetadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);
        when(clusterService.state()).thenReturn(clusterState);
        when(metadata.getIndexSafe(testIndex)).thenReturn(indexMetadata);

        TestTieringService spyService = spy(new TestTieringService(
                Settings.EMPTY,
                clusterService,
                mock(ClusterInfoService.class),
                resolver,
                mock(AllocationService.class),
                tierActionMetrics,
                nodeEnvironment,
                shardLimitValidator
        ));

        spyService.tieringIndices.add(mockIndex);
        assertEquals(1,spyService.listTieringStatus().size());
        TieringStatus status = spyService.getTieringStatus("test-index",false);
        assertNotNull(status);
        assertEquals(mockIndex.getName(), status.getIndexName());
        // Assert shard level status
        TieringStatus.ShardLevelStatus shardStatus = status.getShardLevelStatus();
        assertNotNull(shardStatus);
        assertEquals(3, shardStatus.getShardLevelCounters().get(TOTAL_SHARDS).intValue());
        assertEquals(1, shardStatus.getShardLevelCounters().get(PENDING_SHARDS).intValue());
        assertEquals(1, shardStatus.getShardLevelCounters().get(SUCCEEDED_SHARDS).intValue());
        assertEquals(1, shardStatus.getShardLevelCounters().get(RUNNING_SHARDS).intValue());

    }

    private List<ShardRouting> createDummyShardRoutings() {
        List<ShardRouting> shardRoutings = new ArrayList<>();

        // Create a STARTED shard
        ShardRouting startedShard = mock(ShardRouting.class);
        when(startedShard.state()).thenReturn(ShardRoutingState.STARTED);
        when(startedShard.currentNodeId()).thenReturn("node1");

        // Create an UNASSIGNED shard
        ShardRouting unassignedShard = mock(ShardRouting.class);
        when(unassignedShard.state()).thenReturn(ShardRoutingState.UNASSIGNED);

        // Create a RELOCATING shard
        ShardRouting relocatingShard = mock(ShardRouting.class);
        when(relocatingShard.state()).thenReturn(ShardRoutingState.RELOCATING);
        when(relocatingShard.shardId()).thenReturn(new ShardId("test-index", "uuid", 0));
        when(relocatingShard.relocatingNodeId()).thenReturn("node2");

        shardRoutings.add(startedShard);
        shardRoutings.add(unassignedShard);
        shardRoutings.add(relocatingShard);

        return shardRoutings;
    }
}
