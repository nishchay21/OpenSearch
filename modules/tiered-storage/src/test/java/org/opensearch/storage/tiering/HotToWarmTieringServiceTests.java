package org.opensearch.storage.tiering;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.core.index.Index;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.storage.common.tiering.TieringRejectionException;
import org.opensearch.storage.metrics.TierActionMetrics;
import org.opensearch.indices.ShardLimitValidator;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.Before;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.IS_WARM_INDEX_SETTING;
import static org.opensearch.index.IndexModule.TieringState;
import static org.opensearch.index.IndexModule.TieringState.HOT_TO_WARM;
import static org.opensearch.index.store.remote.filecache.FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.validateCCRIndex;
import static org.opensearch.storage.common.tiering.TieringUtils.FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS_KEY;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_TIERING_START_TIME_KEY;
import static org.opensearch.storage.common.tiering.TieringUtils.JVM_USAGE_TIERING_THRESHOLD_PERCENT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HotToWarmTieringServiceTests extends OpenSearchTestCase {

    private HotToWarmTieringService service;
    private ClusterService clusterService;
    private ClusterState clusterState;
    private Index testIndex;
    private IndexMetadata indexMetadata;
    private Settings indexSettings;
    private TierActionMetrics tierActionMetrics;
    private NodeEnvironment nodeEnvironment;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        clusterService = mock(ClusterService.class);
        clusterState = mock(ClusterState.class);
        tierActionMetrics = new TierActionMetrics(mock(MetricsRegistry.class));
        nodeEnvironment = newNodeEnvironment();


        testIndex = new Index("test-index", "uuid");
        indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_INDEX_UUID, "uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .build();

        indexMetadata = IndexMetadata.builder("test-index")
            .settings(indexSettings)
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();

        Settings defaultSettings = Settings.builder()
                .put(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS.getKey(),50)
                .put(JVM_USAGE_TIERING_THRESHOLD_PERCENT.getKey(),99)
                .put(FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT.getKey(),90)
                .build();

        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS);
        clusterSettingsToAdd.add(JVM_USAGE_TIERING_THRESHOLD_PERCENT);
        clusterSettingsToAdd.add(FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT);
        clusterSettingsToAdd.add(DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING);

        ClusterSettings mockSettings = new ClusterSettings(defaultSettings, clusterSettingsToAdd);
        when(clusterService.getClusterSettings()).thenReturn(mockSettings);

        service = new HotToWarmTieringService(
            defaultSettings,
            clusterService,
            mock(ClusterInfoService.class),
            mock(IndexNameExpressionResolver.class),
            mock(AllocationService.class),
            tierActionMetrics,
            nodeEnvironment,
            mock(ShardLimitValidator.class)
        );
    }

    @Override
    public void tearDown() throws Exception{
        IOUtils.close(nodeEnvironment);
        super.tearDown();
    }

    public void testGetTieringStartSettingsToAdd() {
        Settings settings = service.getTieringStartSettingsToAdd();

        assertEquals("true", settings.get(IS_WARM_INDEX_SETTING.getKey()));
        assertEquals(HOT_TO_WARM.toString(), settings.get(INDEX_TIERING_STATE.getKey()));
    }

    public void testGetTieringStartTimeKey() {
        assertEquals(H2W_TIERING_START_TIME_KEY, service.getTieringStartTimeKey());
    }

    public void testGetTieringMaxConcurrentRequests() {
        assertEquals(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS_KEY, service.getMaxConcurrentTieringRequestsSetting().getKey());
    }

    public void testGetTargetTieringState() {
        assertEquals(TieringState.WARM, service.getTargetTieringState());
    }

    public void testIsShardInTargetTier() {
        ShardRouting shard = mock(ShardRouting.class);
        DiscoveryNode node = mock(DiscoveryNode.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);

        when(shard.unassigned()).thenReturn(false);
        when(shard.started()).thenReturn(true);
        when(shard.currentNodeId()).thenReturn("node1");
        when(clusterState.getNodes()).thenReturn(nodes);
        when(nodes.get("node1")).thenReturn(node);
        when(node.isWarmNode()).thenReturn(true);

        assertTrue(service.isShardInTargetTier(shard, clusterState));
    }

    public void testClusterChanged_WithRoutingTableChange() {
        ClusterChangedEvent event = mock(ClusterChangedEvent.class);
        RoutingTable routingTable = mock(RoutingTable.class);
        ClusterState previousState = mock(ClusterState.class);
        DiscoveryNodes previousNodes = mock(DiscoveryNodes.class);

        // Setup master node checks
        when(event.localNodeClusterManager()).thenReturn(true);
        when(event.previousState()).thenReturn(previousState);
        when(previousState.nodes()).thenReturn(previousNodes);
        when(previousNodes.isLocalNodeElectedClusterManager()).thenReturn(true);

        // Setup routing table change
        when(event.routingTableChanged()).thenReturn(true);
        when(event.state()).thenReturn(clusterState);
        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.hasIndex(testIndex)).thenReturn(false);  // Simulate index not found case

        // Add metadata mocking
        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);

        service.tieringIndices.add(testIndex);
        service.clusterChanged(event);

        verify(event).localNodeClusterManager();
        verify(event).previousState();
        verify(event).routingTableChanged();
        verify(event, atLeastOnce()).state();
        verify(routingTable).hasIndex(testIndex);

        // Index should be removed as it's not found in routing table
        assertTrue(service.tieringIndices.isEmpty());
    }

    public void testClusterChanged_WithRoutingTableChange_AndExistingIndex() {
        ClusterChangedEvent event = mock(ClusterChangedEvent.class);
        RoutingTable routingTable = mock(RoutingTable.class);
        ClusterState previousState = mock(ClusterState.class);
        DiscoveryNodes previousNodes = mock(DiscoveryNodes.class);
        List<ShardRouting> shardRoutings = Collections.singletonList(mock(ShardRouting.class));

        // Setup master node checks
        when(event.localNodeClusterManager()).thenReturn(true);
        when(event.previousState()).thenReturn(previousState);
        when(previousState.nodes()).thenReturn(previousNodes);
        when(previousNodes.isLocalNodeElectedClusterManager()).thenReturn(true);

        // Setup routing table change
        when(event.routingTableChanged()).thenReturn(true);
        when(event.state()).thenReturn(clusterState);
        when(clusterState.routingTable()).thenReturn(routingTable);
        when(routingTable.hasIndex(testIndex)).thenReturn(true);
        when(routingTable.allShards(testIndex.getName())).thenReturn(shardRoutings);

        // Setup shard routing
        ShardRouting shardRouting = shardRoutings.get(0);
        when(shardRouting.primary()).thenReturn(true);
        when(shardRouting.unassigned()).thenReturn(false);
        when(shardRouting.started()).thenReturn(true);
        when(shardRouting.currentNodeId()).thenReturn("node1");

        // Setup node checks
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        DiscoveryNode node = mock(DiscoveryNode.class);
        when(clusterState.getNodes()).thenReturn(nodes);
        when(nodes.get("node1")).thenReturn(node);
        when(node.isWarmNode()).thenReturn(true);

        // Add metadata mocking
        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);

        service.tieringIndices.add(testIndex);
        service.clusterChanged(event);

        verify(event).localNodeClusterManager();
        verify(event).previousState();
        verify(event).routingTableChanged();
        verify(event, atLeastOnce()).state();
        verify(routingTable).hasIndex(testIndex);
        verify(routingTable).allShards(testIndex.getName());
    }

    public void testClusterChanged_NewMasterNode() {
        ClusterChangedEvent event = mock(ClusterChangedEvent.class);
        DiscoveryNodes previousNodes = mock(DiscoveryNodes.class);
        DiscoveryNodes currentNodes = mock(DiscoveryNodes.class);
        ClusterState previousState = mock(ClusterState.class);
        Metadata metadata = mock(Metadata.class);

        // Setup for master node change scenario
        when(event.localNodeClusterManager()).thenReturn(true);
        when(event.previousState()).thenReturn(previousState);
        when(previousState.nodes()).thenReturn(previousNodes);
        when(previousNodes.isLocalNodeElectedClusterManager()).thenReturn(false);
        when(event.state()).thenReturn(clusterState);
        when(clusterState.metadata()).thenReturn(metadata);

        service.clusterChanged(event);

        verify(event).localNodeClusterManager();
        verify(event).previousState();
        verify(previousState).nodes();
        verify(previousNodes).isLocalNodeElectedClusterManager();
    }

    public void testClusterChanged_ExistingMaster() {
        ClusterChangedEvent event = mock(ClusterChangedEvent.class);
        ClusterState previousState = mock(ClusterState.class);
        DiscoveryNodes previousNodes = mock(DiscoveryNodes.class);

        when(event.localNodeClusterManager()).thenReturn(true);
        when(event.previousState()).thenReturn(previousState);
        when(previousState.nodes()).thenReturn(previousNodes);
        when(previousNodes.isLocalNodeElectedClusterManager()).thenReturn(true);
        service.clusterChanged(event);

        verify(event).localNodeClusterManager();
        verify(event).previousState();
        verify(previousNodes).isLocalNodeElectedClusterManager();
        verify(event).routingTableChanged();
    }

    public void testClusterChanged_NonMasterNode() {
        ClusterChangedEvent event = mock(ClusterChangedEvent.class);
        when(event.localNodeClusterManager()).thenReturn(false);

        service.clusterChanged(event);

        verify(event).localNodeClusterManager();
        verify(event, never()).previousState();
        verify(event, never()).routingTableChanged();
    }

    private IndexMetadata createIndexMetadata(String name, String tieringState) {
        Settings settings = Settings.builder()
            .put(indexSettings)
            .put(INDEX_TIERING_STATE.getKey(), tieringState)
            .build();

        return IndexMetadata.builder(name)
            .settings(settings)
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();
    }

    public void testReconstructInProgressTiering_WithExistingRequests() {
        // Create test indices with tiering state
        IndexMetadata index1 = createIndexMetadata("index1", HOT_TO_WARM.toString());
        IndexMetadata index2 = createIndexMetadata("index2", HOT_TO_WARM.toString());
        IndexMetadata index3 = createIndexMetadata("index3", "some_other_state");

        // Setup metadata with test indices
        Metadata metadata = Metadata.builder()
            .put(index1, false)
            .put(index2, false)
            .put(index3, false)
            .build();

        when(clusterState.metadata()).thenReturn(metadata);

        service.reconstructInProgressTieringRequests(clusterState, HOT_TO_WARM, "hot");

        assertEquals(2, service.tieringIndices.size());
        assertTrue(service.tieringIndices.contains(index1.getIndex()));
        assertTrue(service.tieringIndices.contains(index2.getIndex()));
        assertFalse(service.tieringIndices.contains(index3.getIndex()));
    }

    public void testReconstructInProgressTiering_WithNoRequests() {
        // Create test index with different tiering state
        IndexMetadata index1 = createIndexMetadata("index1", "some_other_state");

        // Setup metadata with test index
        Metadata metadata = Metadata.builder()
            .put(index1, false)
            .build();

        when(clusterState.metadata()).thenReturn(metadata);

        service.reconstructInProgressTieringRequests(clusterState, HOT_TO_WARM, "hot");

        assertTrue(service.tieringIndices.isEmpty());
    }

    public void testValidateCCRIndex() {
        // Mock IndexMetadata
        IndexMetadata indexMetadata = mock(IndexMetadata.class);
        Settings settings = Settings.builder()
            .put("index.plugins.replication.follower.leader_index", "leader_index")  // Make it a CCR index
            .build();

        when(indexMetadata.getSettings()).thenReturn(settings);

        ClusterState clusterState = mock(ClusterState.class);
        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.getIndexSafe(any(Index.class))).thenReturn(indexMetadata);

        Index testIndex = new Index("test-index", "uuid");

        // Test the validation
        try {
            validateCCRIndex(clusterState, testIndex, TieringState.WARM);
            fail("Expected TieringRejectionException");
        } catch (TieringRejectionException e) {
            assertTrue(e.getMessage().contains("cross-cluster-replicated index and cannot be migrated"));
        }
    }
}
