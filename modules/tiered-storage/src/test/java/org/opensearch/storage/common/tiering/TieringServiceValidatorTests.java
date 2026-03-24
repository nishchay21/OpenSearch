package org.opensearch.storage.common.tiering;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterInfo;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.DiskUsage;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.cluster.routing.allocation.DiskThresholdSettings;
import org.opensearch.cluster.routing.allocation.WarmNodeDiskThresholdEvaluator;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexModule;
import org.opensearch.index.store.remote.filecache.AggregateFileCacheStats;
import org.opensearch.index.store.remote.filecache.FileCacheSettings;
import org.opensearch.index.store.remote.filecache.FileCacheStats;
import org.opensearch.indices.ShardLimitValidator;
import org.opensearch.node.NodeResourceUsageStats;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.Before;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.TieringState.HOT_TO_WARM;
import static org.opensearch.index.IndexModule.TieringState.WARM;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.checkJVMMemoryUtilizationThreshold;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.validateCommon;
import static org.opensearch.storage.common.tiering.TieringServiceValidator.validateEligibleHotNodesCapacity;
import static org.opensearch.storage.common.tiering.TieringUtils.Tier.HOT;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class TieringServiceValidatorTests extends OpenSearchTestCase {

    private ClusterState clusterState;
    private Index testIndex;
    private IndexMetadata indexMetadata;
    private ClusterInfo clusterInfo;
    private Set<Index> inProgressTieringEntries;
    private ClusterSettings clusterSettings;
    private ShardLimitValidator shardLimitValidator;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        testIndex = new Index("test-index", "uuid");
        inProgressTieringEntries = new HashSet<>();
        shardLimitValidator = mock(ShardLimitValidator.class);
    }

    public void testValidateCommon_SuccessfulHotToWarmTransition() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1,100, 50, 10L);
        TieringServiceValidator.validateCommon(clusterState, clusterInfo,
                testIndex, 2, 3,99,WARM,HOT_TO_WARM,shardLimitValidator);
    }

    public void testValidateCommon_FailsWithNoWarmNodes() {
        setupClusterState(0, HOT.toString(), true, false);
        setupClusterInfo(1, 100, 50, 10L);
        assertValidationException(testIndex,"no nodes found with the warm role");
    }

    public void testValidateCommon_FailsWithIncorrectInitialState() {
        setupClusterState(1, WARM.toString(), true, false);
        setupClusterInfo(1,100, 50, 10L);
        assertValidationException(testIndex,"Cannot migrate index", TieringRejectionException.class);
    }

    public void testValidateCommon_FailsWithMaxTieringConcurrentRequestsLimitBreached() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1,100, 50, 10L);
        assertThrows(TieringRejectionException.class, ()->
                validateCommon(clusterState,clusterInfo,testIndex,
                        4,4,90,WARM,HOT_TO_WARM,shardLimitValidator));
    }

    @AwaitsFix(bugUrl = "") // TODO: Need to fix
   public void testValidateCommon_FailsWithRedHealth() {
       setupClusterState(1, HOT.toString(), false, false);
       setupClusterInfo(1,100, 50, 10L);
       assertValidationException(testIndex, "is in RED status");
   }

    public void testValidateCommon_FailsWithRemoteStoreDisabled() {
        setupClusterStateWithoutRemoteStore(1, HOT.toString(), true, false, false);
        setupClusterInfo(1,100, 50, 10L);
        assertValidationException(testIndex, "does not have remote store enabled", TieringRejectionException.class);
    }

    public void testValidateCommon_SucceedsWithoutNullJVMStats() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1, 100, 50, 10L,40,20);
        TieringServiceValidator.validateCommon(clusterState,
                clusterInfo,testIndex,2,5,90, WARM,HOT_TO_WARM,shardLimitValidator);
    }

    public void testValidateCommon_FailsWithHighJVMUtilization() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1, 100, 50, 10L,99,20);
        assertValidationException(testIndex,"Rejecting tiering request as JVM Utilization is high");
    }

    @AwaitsFix(bugUrl = "") // TODO: Need to fix
    public void testValidateFileCacheUsageThreshold() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1, 100, 50, 10L,99,99);
        assertThrows(TieringRejectionException.class,
                () -> checkJVMMemoryUtilizationThreshold(clusterState, clusterInfo, 90, IndexModule.TieringState.HOT)); // Should fail
    }

    public void testValidateEligibleNodesCapacityW2H() {
        // Success case
        validateCapacity(100, 50, 20L, IndexModule.TieringState.HOT); // Should pass

        // Failure case
        assertThrows(TieringRejectionException.class,
                () -> validateCapacity(100, 5, 20L, IndexModule.TieringState.HOT)); // Should fail
    }

    public void testValidateEligibleNodesCapacityW2H_IndexWithNoReplica() {
        // Success case
        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test_index";
        Index testIndex = new Index(indexName, indexUuid);

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, Settings.EMPTY, IndexMetadata.State.OPEN,null,0))
                .nodes(createNodes(2, 1, 0))
                .build();

        Map<String, Long> shardSizes = Collections.singletonMap("[test_index][0][p]", 60L);

        ClusterInfo clusterInfo = new ClusterInfo(
                diskUsages(1, 2, 100, 70),
                diskUsages(1,2, 100, 70),
                shardSizes,
                null,
                Map.of(),
                Map.of(),
                Map.of());

        validateEligibleHotNodesCapacity(state, clusterInfo, new HashSet<>(), testIndex);
    }

    public void testValidateEligibleNodesCapacityW2H_WithYellowIndex() {
        // Success case
        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test_index";
        Index testIndex = new Index(indexName, indexUuid);

        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        routingTableBuilder.add(createRoutingTable(testIndex,"warm-0",true,true));

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, Settings.EMPTY, IndexMetadata.State.OPEN,routingTableBuilder.build(),0))
                .nodes(createNodes(2, 2, 0))
                .build();

        Map<String, Long> shardSizes = Collections.singletonMap("[test_index][0][p]", 60L);

        ClusterInfo clusterInfo = new ClusterInfo(
                diskUsages(1, 2, 100, 40),
                diskUsages(1,2, 100, 70),
                shardSizes,
                null,
                Map.of(),
                Map.of(),
                Map.of());

        validateEligibleHotNodesCapacity(state, clusterInfo, new HashSet<>(), testIndex);
    }

    private void validateCapacity(long totalBytes, long freeBytes, long shardSize, IndexModule.TieringState targetTier) {
        validateCapacity(totalBytes,freeBytes,shardSize,2,2,targetTier);
    }

    private void validateCapacity(long totalBytes, long freeBytes, long shardSize, int dataNodes, int warmNodes, IndexModule.TieringState targetTier) {
        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test_index";
        Index testIndex = new Index(indexName, indexUuid);
        Set<Index> tieringIndices = Collections.singleton(testIndex);

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, Settings.EMPTY,null))
                .nodes(createNodes(warmNodes, dataNodes, 0))
                .build();

        Map<String, Long> shardSizes = Collections.singletonMap("[test_index][0][p]", shardSize);

        ClusterInfo clusterInfo = new ClusterInfo(
                diskUsages(warmNodes, dataNodes, totalBytes, freeBytes),
                diskUsages(warmNodes,dataNodes, totalBytes, freeBytes),
                shardSizes,
                null,
                Map.of(),
                Map.of(),
                null);

        validateEligibleHotNodesCapacity(state, clusterInfo, tieringIndices, testIndex);
    }

    private void setupClusterStateWithoutRemoteStore(int includeWarmNodes, String tierState,
                                                     boolean isHealthy, boolean isYellow, boolean remoteStoreEnabled) {

        Settings indexSettings = createIndexSettings(tierState, remoteStoreEnabled);
        indexMetadata = createIndexMetadata(indexSettings);

        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        if (!isHealthy) {
            routingTableBuilder.add(createRoutingTable(testIndex, "warm-0", false, isHealthy));
        } else{
            routingTableBuilder.add(createRoutingTable(testIndex, "warm-0", isYellow, isHealthy));
        }

        clusterState = ClusterState.builder(buildClusterState("test-index", "na", Settings.EMPTY, null))
                .nodes(createNodes(includeWarmNodes, 0, 0))
                .metadata(Metadata.builder().put(indexMetadata, false))
                .routingTable(routingTableBuilder.build())
                .build();
    }


    private void setupClusterState(int includeWarmNodes, String tierState,
                                   boolean isHealthy, boolean isYellow) {

        Settings indexSettings = createIndexSettings(tierState, true);
        indexMetadata = createIndexMetadata(indexSettings);

        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        if (!isHealthy) {
            routingTableBuilder.add(createRoutingTable(testIndex,"warm-0",false,isHealthy));
        } else{
            routingTableBuilder.add(createRoutingTable(testIndex,"warm-0",isYellow,isHealthy));
        }

        clusterState = ClusterState.builder(buildClusterState("test-index", "na", Settings.EMPTY,null))
                .nodes(createNodes(includeWarmNodes, 0, 0))
                .metadata(Metadata.builder().put(indexMetadata, false))
                .routingTable(routingTableBuilder.build())
                .build();
    }

    private void setupClusterInfo(int warmNodeCount,long totalBytes, long freeBytes, long shardSize) {
        setupClusterInfo(warmNodeCount,totalBytes,freeBytes,shardSize,95,20);
    }

    private void setupClusterInfo(int warmNodeCount,long totalBytes, long freeBytes, long shardSize, int jvmUtilizationPercent, int fileCacheActiveUsagePercent) {
        Map<String, Long> shardSizes = Collections.singletonMap("[test-index][0][p]", shardSize);
        clusterInfo = new ClusterInfo(
                diskUsages(warmNodeCount, 0,totalBytes, freeBytes),
                null,
                shardSizes,
                null,
                Map.of(),
                nodeFileCacheStatsMap(2,fileCacheActiveUsagePercent),
                nodeResourceUsageStatsMap(warmNodeCount,2,jvmUtilizationPercent));
    }

    private void assertValidationException(Index index, String expectedMessage) {
        assertValidationException(index,expectedMessage, TieringRejectionException.class);
    }

    private void assertValidationException(Index index, String expectedMessage, Class<? extends Exception> exceptionClass) {
        Exception exception = expectThrows(
                exceptionClass,
                () -> TieringServiceValidator.validateCommon(clusterState, clusterInfo,
                        testIndex, 1, 3, 90, WARM,HOT_TO_WARM,shardLimitValidator)
        );
        assertTrue(exception.getMessage().contains(expectedMessage));
    }

    private Settings createIndexSettings(String tierState, boolean remoteStoreEnabled) {
        return Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(IndexMetadata.SETTING_INDEX_UUID, testIndex.getUUID())
                .put(INDEX_TIERING_STATE.getKey(), tierState)
                .put(IndexMetadata.SETTING_REMOTE_STORE_ENABLED, remoteStoreEnabled)
                .build();
    }

    private IndexMetadata createIndexMetadata(Settings settings) {
        return IndexMetadata.builder(testIndex.getName())
                .settings(settings)
                .numberOfShards(1)
                .numberOfReplicas(1)
                .build();
    }

    private IndexRoutingTable createRoutingTable(Index index, String nodeId, boolean isYellow, boolean isHealthy) {
        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(index);
        ShardRouting primaryShard = TestShardRouting.newShardRouting(
                new ShardId(index,0),
                isHealthy? nodeId :null ,
                true,
                isHealthy? ShardRoutingState.STARTED: ShardRoutingState.UNASSIGNED
        );

        builder.addShard(primaryShard);

        if(isYellow) {
            ShardRouting replicaShard = TestShardRouting.newShardRouting(
                    new ShardId(index, 0),
                    isHealthy?  null: nodeId,
                    false,
                    isHealthy?  ShardRoutingState.UNASSIGNED: ShardRoutingState.STARTED
            );
            builder.addShard(replicaShard);
        }


        return builder.build();
    }

    public void testValidateSpaceForLargestShard_Success() {
        setupClusterState(2, HOT.toString(), true, false);
        setupClusterInfo(2,1000, 500, 10L); // Plenty of space available

        TieringServiceValidator.validateSpaceForLargestShard(
                clusterState,
                clusterInfo,
                testIndex,
                WARM,
                20L * 1024 * 1024 * 1024L // 20GB buffer
        );
        // Test passes if no exception is thrown
    }

    public void testValidateSpaceForLargestShard_Failure() {
        setupClusterState(1, HOT.toString(), true, false);
        setupClusterInfo(1,100, 5, 50L); // Very little free space

        TieringRejectionException exception = expectThrows(
            TieringRejectionException.class,
                () -> TieringServiceValidator.validateSpaceForLargestShard(
                        clusterState,
                        clusterInfo,
                        testIndex,
                        WARM,
                        20L * 1024 * 1024 * 1024L // 20GB buffer
                )
        );
        assertTrue(exception.getMessage().contains("don't have"));
    }

    private static ClusterState buildClusterState(String indexName, String indexUuid, Settings settings, IndexMetadata.State state, RoutingTable routingTable, int numberOfReplicaShards) {
        Settings combinedSettings = Settings.builder().put(settings).put(createDefaultIndexSettings(indexUuid, numberOfReplicaShards)).build();

        Metadata metadata = Metadata.builder().put(IndexMetadata.builder(indexName).settings(combinedSettings).state(state)).build();

        if(routingTable == null) {
            routingTable = RoutingTable.builder().addAsNew(metadata.index(indexName)).build();
        }
        return ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(settings))
                .metadata(metadata)
                .routingTable(routingTable)
                .build();
    }

    private static ClusterState buildClusterState(String indexName, String indexUuid, Settings settings, RoutingTable routingTable) {
        return buildClusterState(indexName, indexUuid, settings, IndexMetadata.State.OPEN,routingTable,1);
    }

    private static Settings createDefaultIndexSettings(String indexUuid, int numberOfReplica) {
        return Settings.builder()
                .put("index.version.created", Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, indexUuid)
                .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 2)
                .put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), numberOfReplica)
                .build();
    }

    private static Settings createDefaultIndexSettings(String indexUuid) {
        return createDefaultIndexSettings(indexUuid,1);
    }

    private DiscoveryNodes createNodes(int numOfSearchNodes, int numOfDataNodes, int numOfIngestNodes) {
        DiscoveryNodes.Builder discoveryNodesBuilder = DiscoveryNodes.builder();
        for (int i = 0; i < numOfSearchNodes; i++) {
            discoveryNodesBuilder.add(
                    new DiscoveryNode(
                            "warm-" + i,
                            buildNewFakeTransportAddress(),
                            Collections.emptyMap(),
                            Collections.singleton(DiscoveryNodeRole.WARM_ROLE),
                            Version.CURRENT
                    )
            );
        }
        for (int i = 0; i < numOfDataNodes; i++) {
            discoveryNodesBuilder.add(
                    new DiscoveryNode(
                            "data-" + i,
                            buildNewFakeTransportAddress(),
                            Collections.emptyMap(),
                            Collections.singleton(DiscoveryNodeRole.DATA_ROLE),
                            Version.CURRENT
                    )
            );
        }
        for (int i = 0; i < numOfIngestNodes; i++) {
            discoveryNodesBuilder.add(
                    new DiscoveryNode(
                            "node-i" + i,
                            buildNewFakeTransportAddress(),
                            Collections.emptyMap(),
                            Collections.singleton(DiscoveryNodeRole.INGEST_ROLE),
                            Version.CURRENT
                    )
            );
        }
        return discoveryNodesBuilder.build();
    }

    private static Map<String, DiskUsage> diskUsages(int noOfWarmNodes, int noOfHotNodes, long totalBytes, long freeBytes) {
        final Map<String, DiskUsage> diskUsages = new HashMap<>();
        for (int i = 0; i < noOfWarmNodes; i++) {
            diskUsages.put("warm-" + i, new DiskUsage("node-s" + i, "node-s" + i, "/foo/bar", totalBytes, freeBytes));
        }
        for (int i = 0; i < noOfHotNodes; i++) {
            diskUsages.put("data-" + i, new DiskUsage("node-s" + i, "node-s" + i, "/foo/bar", totalBytes, freeBytes));
        }
        return diskUsages;
    }

    public void testValidateWarmNodeDiskThresholdWaterMarkLow_BreachOnOneNode() {

        Settings diskSettings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "150b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "100b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "50b")
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 2.0)
                .build();

        DiskThresholdSettings diskThresholdSettings = diskThresholdSettings("150b", "100b", "50b");

        Set<Index> tieringIndices = new HashSet<>();
        final Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 1750L); // 1750 bytes
        shardSizes.put("[test][0][r]", 1750L);
        shardSizes.put("[test2][0][p]", 1500L); // 1500 bytes
        shardSizes.put("[test2][0][r]", 1500L);

        Map<String, DiskUsage> diskUsages = new HashMap<>();
        diskUsages.put("warm-0", new DiskUsage("warm_node", "warm_node", "/foo/bar", 2000, 250));
        diskUsages.put("warm-1", new DiskUsage("warm_node", "warm_node", "/foo/bar", 2000, 500));

        Map<String, AggregateFileCacheStats> fileCacheStatsMap = new HashMap<>();

        fileCacheStatsMap.put("warm-0", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 1000, 100, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null, null));
        fileCacheStatsMap.put("warm-1", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 1000, 100, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null,null));

        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test";
        Index testIndex = new Index(indexName, indexUuid);

        Map<Index, String> indicesNodeMap = new HashMap<>();
        indicesNodeMap.put(testIndex,"warm-0");
        indicesNodeMap.put(new Index("test-2","na"),"warm-0");

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, diskSettings,buildRoutingTable(indicesNodeMap)))
                .nodes(createNodes(2, 0, 0))
                .build();

        ClusterInfo clusterInfo = new ClusterInfo(
                diskUsages,
                diskUsages,
                shardSizes,
                null,
                Map.of(),
                fileCacheStatsMap,
                Map.of());

        TieringServiceValidator.validateWarmNodeDiskThresholdWaterMarkLow(state,clusterInfo, tieringIndices, testIndex, new WarmNodeDiskThresholdEvaluator(diskThresholdSettings, fileCacheSettings(2.0)::getRemoteDataRatio));
    }

    private RoutingTable buildRoutingTable(Map<Index, String> indicesNodeMap) {
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();

        // Add each index to the routing table
        indicesNodeMap.forEach((index, nodeId) ->
                routingTableBuilder.add(createRoutingTable(index,nodeId,false,true)));

        return routingTableBuilder.build();
    }

    public void testValidateWarmNodeDiskThresholdWaterMarkLow_NoNodesBreached() {

        Settings diskSettings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "150b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "100b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "50b")
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 2.0)
                .build();

        DiskThresholdSettings diskThresholdSettings = diskThresholdSettings("150b", "100b", "50b");

        final Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 1600L); // 1600 bytes
        shardSizes.put("[test][0][r]", 1600L);
        shardSizes.put("[test2][0][p]", 1500L); // 1500 bytes
        shardSizes.put("[test2][0][r]", 1500L);

        Map<String, DiskUsage> diskUsages = new HashMap<>();
        diskUsages.put("warm-0", new DiskUsage("warm_node", "warm_node", "/foo/bar", 2000, 500));
        diskUsages.put("warm-1", new DiskUsage("warm_node", "warm_node", "/foo/bar", 2000, 400));

        Map<String, AggregateFileCacheStats> fileCacheStatsMap = new HashMap<>();
        fileCacheStatsMap.put("warm-0", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 1000, 100, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null, null));
        fileCacheStatsMap.put("warm-1", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 1000, 100, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null,null));

        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test";
        Index testIndex = new Index(indexName, indexUuid);

        Map<Index, String> indicesNodeMap = new HashMap<>();
        indicesNodeMap.put(testIndex,"warm-0");

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, diskSettings,buildRoutingTable(indicesNodeMap)))
                .nodes(createNodes(2, 0, 0))
                .build();

        //  Map<String, Long> shardSizes = Collections.singletonMap("[test_index][0][p]", shardSize);
        ClusterInfo clusterInfo = new ClusterInfo(
                diskUsages,
                diskUsages,
                shardSizes,
                null,
                Map.of(),
                fileCacheStatsMap,
                null);

        TieringServiceValidator.validateWarmNodeDiskThresholdWaterMarkLow(state, clusterInfo, new HashSet<>(), testIndex, new WarmNodeDiskThresholdEvaluator(diskThresholdSettings, fileCacheSettings(2.0)::getRemoteDataRatio));
    }

    public void testValidateWarmNodeDiskThresholdWaterMarkLow_BreachedTrue() {

        Settings diskSettings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "3000b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "2000b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "100b")
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 5.0)
                .build();

        DiskThresholdSettings diskThresholdSettings = diskThresholdSettings("10b", "10b", "5b");

        final Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 2000L); // 2000 bytes
        shardSizes.put("[test][0][r]", 200L);
        shardSizes.put("[test-2][0][p]", 1000L); // 1000 bytes
        shardSizes.put("[test-2][0][r]", 300L);

        Map<String, AggregateFileCacheStats> fileCacheStatsMap = new HashMap<>();
        fileCacheStatsMap.put("warm-0", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 995, 10, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null, null));
        fileCacheStatsMap.put("warm-1", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 995, 10, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null,null));

        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test";
        Index testIndex = new Index(indexName, indexUuid);

        Map<Index, String> indicesNodeMap = new HashMap<>();
        indicesNodeMap.put(testIndex,"warm-0");
        indicesNodeMap.put(new Index("test-2","na"),"warm-1");

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, diskSettings,buildRoutingTable(indicesNodeMap)))
                .nodes(createNodes(2, 0, 0))
                .build();

        ClusterInfo clusterInfo = new ClusterInfo(
                Map.of(),
                Map.of(),
                shardSizes,
                null,
                Map.of(),
                fileCacheStatsMap,
                Map.of());

        assertThrows(TieringRejectionException.class,
                ()->TieringServiceValidator.validateWarmNodeDiskThresholdWaterMarkLow(state, clusterInfo, new HashSet<>(), testIndex, new WarmNodeDiskThresholdEvaluator(diskThresholdSettings, fileCacheSettings(1.0)::getRemoteDataRatio)));
    }

    public void testValidateWarmNodeDiskThresholdWaterMarkLow_HeavyTieringIndices() {

        Settings diskSettings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "3000b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "2000b")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "100b")
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 1.0)
                .build();

        DiskThresholdSettings diskThresholdSettings = diskThresholdSettings("10b", "10b", "5b");

        final Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 400L); // 2000 bytes
        shardSizes.put("[test][0][r]", 200L);
        shardSizes.put("[test-2][0][p]", 200L); // 1000 bytes
        shardSizes.put("[test-2][0][r]", 300L);
        shardSizes.put("[tiering][0][p]", 2000L); // 1000 bytes
        shardSizes.put("[tiering][0][r]", 300L);

        Map<String, AggregateFileCacheStats> fileCacheStatsMap = new HashMap<>();
        fileCacheStatsMap.put("warm-0", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 995, 200, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null, null));
        fileCacheStatsMap.put("warm-1", new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(0, 995, 200, 0, 0, 0, 0, 0, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null,null));

        String indexUuid = UUID.randomUUID().toString();
        String indexName = "test";
        Index testIndex = new Index(indexName, indexUuid);

        Map<Index, String> indicesNodeMap = new HashMap<>();
        indicesNodeMap.put(testIndex,"warm-0");
        indicesNodeMap.put(new Index("test-2","na"),"warm-1");
        indicesNodeMap.put(new Index("tiering","na"),"data-1");

        Set<Index> tieringIndices = new HashSet<>();
        tieringIndices.add(new Index("tiering","na"));

        ClusterState state = ClusterState.builder(buildClusterState(indexName, indexUuid, diskSettings,buildRoutingTable(indicesNodeMap)))
                .nodes(createNodes(2, 2, 0))
                .build();

        ClusterInfo clusterInfo = new ClusterInfo(
                Map.of(),
                Map.of(),
                shardSizes,
                null,
                Map.of(),
                fileCacheStatsMap,
                null);

        assertThrows(TieringRejectionException.class,
                ()->TieringServiceValidator.validateWarmNodeDiskThresholdWaterMarkLow(state, clusterInfo, tieringIndices, testIndex,new WarmNodeDiskThresholdEvaluator(diskThresholdSettings, fileCacheSettings(1.0)::getRemoteDataRatio)));
    }

    private static DiskThresholdSettings diskThresholdSettings(String low, String high, String flood) {
        return new DiskThresholdSettings(
                Settings.builder()
                        .put(CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), low)
                        .put(CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), high)
                        .put(CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), flood)
                        .build(),
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
        );
    }

    private static FileCacheSettings fileCacheSettings(double ratio) {
        HashSet<Setting<?>> clusterSettings = new HashSet<>();
        clusterSettings.addAll(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        clusterSettings.add(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING);
        return new FileCacheSettings(
            Settings.builder()
                .put(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), ratio)
                .build(),
                new ClusterSettings(Settings.EMPTY, clusterSettings)
        );
    }

    private static Map<String, NodeResourceUsageStats> nodeResourceUsageStatsMap(int noOfWarmNodes, int noOfHotNodes, double jvmMemoryUtilizationPercent) {
        final Map<String, NodeResourceUsageStats> usageStatsMap = new HashMap<>();
        for (int i = 0; i < noOfWarmNodes; i++) {
            usageStatsMap.put("warm-" + i, new NodeResourceUsageStats("node-s" + i, System.currentTimeMillis(), jvmMemoryUtilizationPercent, 0, null));
        }
        for (int i = 0; i < noOfHotNodes; i++) {
            usageStatsMap.put("data-" + i, new NodeResourceUsageStats("node-s" + i, System.currentTimeMillis(), jvmMemoryUtilizationPercent, 0, null));
        }
        return usageStatsMap;
    }

    private static Map<String, AggregateFileCacheStats> nodeFileCacheStatsMap(int noOfWarmNodes, long filecacheActiveUsageThreshold) {
        final Map<String, AggregateFileCacheStats> fileCacheStatsMap = new HashMap<>();
        for (int i = 0; i < noOfWarmNodes; i++) {
            fileCacheStatsMap.put("warm-" + i, new AggregateFileCacheStats(System.currentTimeMillis(),new FileCacheStats(filecacheActiveUsageThreshold,100,2, 2, 2, 2,2,2, AggregateFileCacheStats.FileCacheStatsType.OVER_ALL_STATS),null,null,null));
        }
        return fileCacheStatsMap;
    }

}
