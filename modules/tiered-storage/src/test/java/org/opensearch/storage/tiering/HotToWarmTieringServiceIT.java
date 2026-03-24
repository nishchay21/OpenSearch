/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.tiering;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.InternalClusterInfoService;
import org.opensearch.cluster.MockInternalClusterInfoService;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.index.IndexModule;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.monitor.fs.FsInfo;
import org.opensearch.node.Node;
import org.opensearch.node.NodeResourceUsageStats;
import org.opensearch.node.resource.tracker.ResourceTrackerSettings;
import org.opensearch.plugins.Plugin;
import org.opensearch.remotestore.RemoteStoreBaseIntegTestCase;
import org.opensearch.search.SearchHit;
import org.opensearch.storage.TieredStoragePlugin;
import org.opensearch.storage.action.tiering.HotToWarmTierAction;
import org.opensearch.storage.action.tiering.IndexTieringRequest;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.junit.After;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING;
import static org.opensearch.cluster.routing.allocation.DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING;
import static org.opensearch.index.store.remote.filecache.FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING;
import static org.opensearch.storage.common.tiering.TieringUtils.FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_KEY;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS_KEY;
import static org.opensearch.storage.tiering.TieringTestUtils.buildDisabledAllocationSettings;
import static org.opensearch.storage.tiering.TieringTestUtils.buildEnabledAllocationSettings;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.containsString;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class HotToWarmTieringServiceIT extends RemoteStoreBaseIntegTestCase {
    protected final String INDEX_NAME = "h2w-test-idx-1";
    protected final String INDEX_NAME_2 = "h2w-test-idx-2";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MIGRATION_TIMEOUT_SECONDS = 5;

    protected static final int NUM_DOCS_IN_BULK = 10;
    private static final long TOTAL_SPACE_BYTES = new ByteSizeValue(1000, ByteSizeUnit.KB).getBytes();
    private static final List<Class<? extends Plugin>> defaultPlugins = List.of(TieredStoragePlugin.class);

    protected static final Map<String, ByteSizeValue> TEST_SPECIFIC_CACHE_SIZES = new HashMap<>();
    protected static final Map<String, List<Class<? extends Plugin>>> TEST_SPECIFIC_CUSTOM_CLUSTER_INFO = new HashMap<>();

    // Test-specific cache sizes
    static {
        TEST_SPECIFIC_CACHE_SIZES.put("testWarmMigrationWithLargeTieringIndicesQueue", new ByteSizeValue(300, ByteSizeUnit.BYTES));
        TEST_SPECIFIC_CACHE_SIZES.put("testWarmMigrationWithAllNodesWaterMarkBreached", new ByteSizeValue(100, ByteSizeUnit.BYTES));
        TEST_SPECIFIC_CACHE_SIZES.put("testWarmMigrationWithAllNodesWaterMarkBreachedWithExistingTieredIndices", new ByteSizeValue(1000, ByteSizeUnit.BYTES));

        TEST_SPECIFIC_CUSTOM_CLUSTER_INFO.put("testWarmMigrationWithAllNodesWaterMarkBreachedWithExistingTieredIndices",List.of(MockInternalClusterInfoService.TestPlugin.class));
        TEST_SPECIFIC_CUSTOM_CLUSTER_INFO.put("testWarmMigrationWithAllNodesJVMUtilizationBreached",List.of(MockInternalClusterInfoService.TestPlugin.class));
    }
    /*
    Disabling MockFSIndexStore plugin as the MockFSDirectoryFactory wraps the FSDirectory over a OpenSearchMockDirectoryWrapper which extends FilterDirectory (whereas FSDirectory extends BaseDirectory)
    As a result of this wrapping the local directory of Composite Directory does not satisfy the assertion that local directory must be of type FSDirectory
     */
    @Override
    protected boolean addMockIndexStorePlugin() {
        return false;
    }

    @Override
    protected boolean ignoreExternalCluster() {
        return true;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        String testName = getTestName();

        Collection<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.addAll(defaultPlugins);
        plugins.addAll(TEST_SPECIFIC_CUSTOM_CLUSTER_INFO.getOrDefault(testName, List.of()));

        return Collections.unmodifiableCollection(plugins);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        String testName = getTestName();
        ByteSizeValue cacheSize = TEST_SPECIFIC_CACHE_SIZES.getOrDefault(
                testName,
                new ByteSizeValue(1, ByteSizeUnit.GB)  // default value
        );

        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Node.NODE_SEARCH_CACHE_SIZE_SETTING.getKey(), cacheSize.toString())
                .build();
    }

    @Override
    protected Settings featureFlagSettings() {
        Settings.Builder featureSettings = Settings.builder();
        featureSettings.put(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG, true);
        return featureSettings.build();
    }

    // Helper Methods
    protected void setupCluster(int numberOfReplicas, String lowDiskWaterMark, String dataToFileCacheRatio) {
        setupCluster(numberOfReplicas,lowDiskWaterMark,dataToFileCacheRatio,230L,null);
    }

    protected void setupCluster(int numberOfReplica, Settings clusterSettings) {
        setupCluster(numberOfReplica,null,null,230L,clusterSettings);
    }

    protected void setupCluster(int numberOfReplicas, String lowDiskWaterMark, String dataToFileCacheRatio, long shardSizes, Settings clusterSettings){
        Settings.Builder settingsBuilder = Settings.builder()
                .put(CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), (lowDiskWaterMark != null)? lowDiskWaterMark:"10b")
                .put(CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(),"10b")
                .put(CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "0b")
                .put(CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey(), "0ms")
                .put(DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), (dataToFileCacheRatio != null)? Long.parseLong(dataToFileCacheRatio) :5.0)
                .put(clusterSettings == null ? Settings.EMPTY : clusterSettings);


        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startDataOnlyNodes(numberOfReplicas + 1,settingsBuilder.build());
        internalCluster().startWarmOnlyNodes(2,settingsBuilder.build());

    }

    @After
    public void cleanup() throws Exception {
        assertAcked(
                client().admin()
                        .cluster()
                        .prepareUpdateSettings()
                        .setTransientSettings(Settings.builder()
                                .putNull(DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey())
                                .putNull(CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey())
                                .putNull(CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey())
                                .putNull(CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey())
                                .putNull(CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey())
                        )

        );
    }


    protected void createTestIndex(String indexName, int numberOfShards, int numberOfReplicas) {
        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numberOfShards)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)
            .build();

        createIndex(indexName, indexSettings);
        refreshClusterInfo();
    }

    protected void migrateToWarm(String indexName) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ClusterStateListener listener = new TieringTestUtils.MockTieringCompletionListener(
            latch,
            indexName,
            IndexModule.TieringState.HOT_TO_WARM.toString()
        );

        try {
            clusterService().addListener(listener);

            final IndexTieringRequest request = new IndexTieringRequest("warm", indexName);
            AcknowledgedResponse response = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, request)
                .actionGet();
            assertTrue(response.isAcknowledged());

            assertTrue("Hot to Warm migration timed out",
                latch.await(MIGRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertBusy(() -> {
                assertIndexShardLocation(indexName, true);
                verifyWarmIndexSettings(indexName);
            }, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw e;
        } finally {
            clusterService().removeListener(listener);
        }
    }

    protected void migrateMultipleIndicesToWarm(String[] indices) throws Exception {
        CountDownLatch migrationLatch = new CountDownLatch(indices.length);
        List<ClusterStateListener> listeners = new ArrayList<>();

        for (String index : indices) {
            final IndexTieringRequest request = new IndexTieringRequest("warm", index);
            client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, request)
                .actionGet();

            ClusterStateListener listener = new TieringTestUtils.MockTieringCompletionListener(
                migrationLatch,
                index,
                IndexModule.TieringState.HOT_TO_WARM.toString()
            );
            listeners.add(listener);
            clusterService().addListener(listener);
        }

        try {
            assertTrue("Migration timed out",
                migrationLatch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertBusy(() -> {
                for (String index : indices) {
                    assertIndexShardLocation(index, true);
                    verifyWarmIndexSettings(index);
                }
            }, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            listeners.forEach(listener -> clusterService().removeListener(listener));
        }
    }

    protected CountDownLatch waitForMigrationToStart(String indexName) {
        CountDownLatch migrationStarted = new CountDownLatch(1);
        ClusterStateListener listener = new ClusterStateListener() {
            @Override
            public void clusterChanged(ClusterChangedEvent event) {
                if (event.state().routingTable().hasIndex(indexName)) {
                    boolean isRelocating = event.state()
                        .routingTable()
                        .allShards(indexName)
                        .stream()
                        .anyMatch(ShardRouting::relocating);

                    if (isRelocating) {
                        migrationStarted.countDown();
                    }
                }
            }
        };
        clusterService().addListener(listener);
        return migrationStarted;
    }

    protected void verifyMigrationError(String indexName, String expectedErrorMessage) {
        final IndexTieringRequest request = new IndexTieringRequest("warm", indexName);
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, request)
                .actionGet()
        );
        assertThat(e.getMessage(), containsString(expectedErrorMessage));
    }

    // Helper method to verify index shard location
    protected static void assertIndexShardLocation(String indexName, boolean onWarmNodes) {
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        IndexRoutingTable indexRoutingTable = state.routingTable().index(indexName);

        for (IndexShardRoutingTable shardRoutingTable : indexRoutingTable) {
            ShardRouting shardRouting = shardRoutingTable.primaryShard();
            String assignedNodeId = shardRouting.currentNodeId();
            DiscoveryNode assignedNode = state.nodes().get(assignedNodeId);

            if (shardRouting.unassigned() && shardRouting.primary() == false) {
                // continue if replica shard is unassigned.
                continue;
            }
            if (onWarmNodes) {
                assertTrue("Shard should be on node warm node ", assignedNode.getRoles().contains(DiscoveryNodeRole.WARM_ROLE));
            } else {
                assertFalse("Shard should not be on warm node ", assignedNode.getRoles().contains(DiscoveryNodeRole.WARM_ROLE));
            }
        }
    }
    protected void verifyWarmIndexSettings(String indexName){
        // Verify settings
        Settings currentSettings = internalCluster().clusterService()
            .state()
            .metadata()
            .index(indexName)
            .getSettings();

        // Verify all expected warm settings
        Map<String, Object> expectedSettings = TieringTestUtils.getExpectedWarmIndexSettings();
        for (Map.Entry<String, Object> entry : expectedSettings.entrySet()) {
            assertEquals(
                "Incorrect value for setting: " + entry.getKey(),
                entry.getValue().toString(),
                currentSettings.get(entry.getKey())
            );
        }
    }

    // Helper method for document deletion
    private List<String> deleteRandomDocuments(String indexName, int count) {
        SearchResponse searchResponse = client().prepareSearch(indexName)
            .setQuery(QueryBuilders.matchAllQuery())
            .setSize(count)
            .get();

        List<String> deletedIds = new ArrayList<>();
        for (SearchHit hit : searchResponse.getHits()) {
            String docId = hit.getId();
            deletedIds.add(docId);
            assertEquals(
                DocWriteResponse.Result.DELETED,
                client().prepareDelete(indexName, docId).get().getResult()
            );
        }
        return deletedIds;
    }

    public void testHotToWarmMigrationWithAndWithoutReplica() throws Exception {
        int numberOfReplicas = randomIntBetween(0, 2);
        int numberOfShards = randomIntBetween(1, 2);
        logger.info("Testing hot to warm migration with {} replicas and {} shards",
            numberOfReplicas, numberOfShards);

        // Setup and create index
        setupCluster(numberOfReplicas, null, null);
        createTestIndex(INDEX_NAME, numberOfShards, numberOfReplicas);
        refreshClusterInfo();

        // Verify initial state
        assertIndexShardLocation(INDEX_NAME, false);

        // Migrate to warm
        migrateToWarm(INDEX_NAME);

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testHotToWarmMigrationWithAndWithoutReplicaWithData() throws Exception {
        int numberOfReplicas = randomIntBetween(1,2);
        int numberOfShards = randomIntBetween(1, 2);
        logger.info("Testing migration with data, replicas: {}, shards: {}",
            numberOfReplicas, numberOfShards);

        // Setup and create index
        setupCluster(numberOfReplicas, null, null);
        createTestIndex(INDEX_NAME, numberOfShards, numberOfReplicas);

        // Index documents
        Map<String, Long> indexStats = indexData(randomIntBetween(1,3), false, INDEX_NAME);
        long expectedDocCount = indexStats.get(TOTAL_OPERATIONS);
        refresh(INDEX_NAME);

        // Verify initial state
        assertIndexShardLocation(INDEX_NAME, false);

        // Migrate to warm
        migrateToWarm(INDEX_NAME);

        // Verify document count
        refresh(INDEX_NAME);
        flush(INDEX_NAME);
        assertEquals(
            "Document count changed after migration",
            expectedDocCount,
            client().prepareSearch(INDEX_NAME).setSize(0).get().getHits().getTotalHits().value()
        );

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testHotToWarmMigrationWithDeletedDocumentSuccess() throws Exception {
        int numberOfReplicas = randomIntBetween(1, 2);
        logger.info("Testing migration with deletions, replicas: {}", numberOfReplicas);

        // Setup and create index
        setupCluster(numberOfReplicas, null, null);
        createTestIndex(INDEX_NAME, 1, numberOfReplicas);

        // Index and delete documents
        Map<String, Long> indexStats = indexData(randomIntBetween(1,3), true, INDEX_NAME);
        long initialDocCount = indexStats.get(TOTAL_OPERATIONS);

        // Delete some documents
        List<String> deletedIds = deleteRandomDocuments(INDEX_NAME, 2);
        long expectedDocsAfterDelete = initialDocCount - deletedIds.size();
        refresh(INDEX_NAME);

        // Verify initial state
        assertIndexShardLocation(INDEX_NAME, false);

        // Migrate to warm
        migrateToWarm(INDEX_NAME);

        // Verify document count
        refresh(INDEX_NAME);
        assertEquals(
            "Document count changed after migration",
            expectedDocsAfterDelete,
            client().prepareSearch(INDEX_NAME).setSize(0).get().getHits().getTotalHits().value()
        );

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testHotToWarmMigrationWithIndexDeletion() throws Exception {
        int numberOfReplicas = randomIntBetween(0, 2);
        int numberOfShards = randomIntBetween(1, 2);

        // Setup and create index
        setupCluster(numberOfReplicas, null, null);
        createTestIndex(INDEX_NAME, numberOfShards, numberOfReplicas);

        // Verify initial state
        assertIndexShardLocation(INDEX_NAME, false);

        // Start migration and wait for it to begin
        CountDownLatch migrationStarted = waitForMigrationToStart(INDEX_NAME);

        final IndexTieringRequest indexTieringRequest = new IndexTieringRequest("warm", INDEX_NAME);
        client().admin().indices()
            .execute(HotToWarmTierAction.INSTANCE, indexTieringRequest)
            .actionGet();

        assertTrue("Migration didn't start", migrationStarted.await(MIGRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Delete index during migration
        client().admin().indices()
            .delete(new DeleteIndexRequest(INDEX_NAME))
            .actionGet();

        // Verify index deletion
        assertBusy(() -> {
            ClusterState state = client().admin().cluster().prepareState().get().getState();
            assertFalse("Index should be deleted", state.metadata().hasIndex(INDEX_NAME));
        }, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void testMultipleIndicesHotToWarmMigration() throws Exception {
        // Setup cluster
        setupCluster(1, null, null);

        // Create multiple indices
        String[] indices = new String[]{"test-1", "test-2", "test-3", "test-4"};
        for (String index : indices) {
            createTestIndex(index, 1, 1);
        }
        ensureGreen(indices);

        // Verify initial state
        for (String index : indices) {
            assertIndexShardLocation(index, false);
        }

        // Migrate all indices to warm
        migrateMultipleIndicesToWarm(indices);

        // Cleanup
        for (String index : indices) {
            client().admin().indices()
                .delete(new DeleteIndexRequest(index))
                .actionGet();
        }
    }

    public void testWarmMigrationForAlreadyMigratedIndex() throws Exception {
        // Setup and create index
        setupCluster(1,null,null);
        createTestIndex(INDEX_NAME, 1, 1);

        // Start first migration
        migrateToWarm(INDEX_NAME);

        // Try migrating again and verify error
        verifyMigrationError(INDEX_NAME,
            "Cannot migrate index [" + INDEX_NAME + "] to WARM tier");

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testWarmMigrationForExistingMigratingIndex() throws Exception {
        // Start first migration
        setupCluster(1, null, null);
        createTestIndex(INDEX_NAME, 1, 1);

        ensureGreen(INDEX_NAME);

        // Pausing Relocation to test the consideration of memory for in-progress tiering indices
        try {
            client().admin().cluster().prepareUpdateSettings()
                    .setTransientSettings(buildDisabledAllocationSettings())
                    .get();
            // Trigger first migration
            logger.info("--> Starting migration for stuck index: {}", INDEX_NAME);

            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", INDEX_NAME);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                    .actionGet();
            assertTrue(stuckResponse.isAcknowledged());

            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(INDEX_NAME).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(INDEX_NAME, false);
            }, 30, TimeUnit.SECONDS);

            // Trigger migration again
            logger.info("--> Starting migration for second index while first is stuck");
            final IndexTieringRequest secondRequest = new IndexTieringRequest("warm", INDEX_NAME);
            AcknowledgedResponse secondResponse = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, secondRequest)
                    .actionGet();

            // Assert we receive Ack response
            assertTrue(secondResponse.isAcknowledged());
        } catch (Exception e){
            throw e;
        }
        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testWarmMigrationForInvalidIndex() throws Exception {
        // Setup and create index
        setupCluster(1,null,null);
        final String invalidIndex = "invalid-index" ;
        // Start first migration
        Exception exception = expectThrows(IllegalArgumentException.class, () -> {
            migrateToWarm(invalidIndex);
        });
        // Verify the error message
        assertTrue("Expected error message about invalid index",
            exception.getMessage().contains("Failed to resolve index: " + invalidIndex));
    }

    public void testConcurrentMigrationWithStuckIndex() throws Exception {
        logger.info("--> Starting concurrent migration test with stuck index");

        setupCluster(1, buildDisabledAllocationSettings());

        final String STUCK_INDEX = "stuck-index";
        final String SECOND_INDEX = "second-index";

        // Create indices and add data
        createTestIndex(STUCK_INDEX, 1, 1);
        createTestIndex(SECOND_INDEX, 1, 1);
        ensureGreen(STUCK_INDEX, SECOND_INDEX);

        // Add some data to both indices
        logger.info("--> Adding test data to both indices");
        Map<String, Long> stuckIndexStats = indexData(1, false, STUCK_INDEX);
        Map<String, Long> secondIndexStats = indexData(1, false, SECOND_INDEX);

        long stuckIndexDocs = stuckIndexStats.get(TOTAL_OPERATIONS);
        long secondIndexDocs = secondIndexStats.get(TOTAL_OPERATIONS);

        logger.info("--> Document counts - Stuck Index: {}, Second Index: {}",
            stuckIndexDocs, secondIndexDocs);

        refresh(STUCK_INDEX);
        refresh(SECOND_INDEX);

        try {
            // Trigger first migration
            logger.info("--> Starting migration for stuck index: {}", STUCK_INDEX);
            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", STUCK_INDEX);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                .actionGet();
            assertTrue(stuckResponse.isAcknowledged());
            // sleep to finalize the shard relocation start decision.
            Thread.sleep(5000);
            // Verify first index hasn't started relocation
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(STUCK_INDEX, false);
            }, 30, TimeUnit.SECONDS);

            // Trigger second migration
            logger.info("--> Starting migration for second index while first is stuck");
            final IndexTieringRequest secondRequest = new IndexTieringRequest("warm", SECOND_INDEX);
            AcknowledgedResponse secondResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, secondRequest)
                .actionGet();
            assertTrue(secondResponse.isAcknowledged());

            // Allow recoveries by updating the setting
            logger.info("--> Updating cluster setting to allow recoveries");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            // Verify both migrations complete
            assertBusy(() -> {
                assertIndexShardLocation(STUCK_INDEX, true);
                assertIndexShardLocation(SECOND_INDEX, true);
                verifyWarmIndexSettings(STUCK_INDEX);
                verifyWarmIndexSettings(SECOND_INDEX);
            }, 30, TimeUnit.SECONDS);

            // Verify data consistency
            refresh(STUCK_INDEX);
            refresh(SECOND_INDEX);

            long finalStuckIndexDocs = client().prepareSearch(STUCK_INDEX)
                .setSize(0).get().getHits().getTotalHits().value();
            long finalSecondIndexDocs = client().prepareSearch(SECOND_INDEX)
                .setSize(0).get().getHits().getTotalHits().value();

            logger.info("--> Final document counts - Stuck Index: {} (expected: {}), Second Index: {} (expected: {})",
                finalStuckIndexDocs, stuckIndexDocs, finalSecondIndexDocs, secondIndexDocs);

            assertEquals("Document count changed for stuck index", stuckIndexDocs, finalStuckIndexDocs);
            assertEquals("Document count changed for second index", secondIndexDocs, finalSecondIndexDocs);

        } finally {
            // Cleanup
            client().admin().indices().delete(new DeleteIndexRequest(STUCK_INDEX)).actionGet();
            client().admin().indices().delete(new DeleteIndexRequest(SECOND_INDEX)).actionGet();
        }
    }

    public void testConcurrentMigrationWithStuckIndexAndDataNodesRestarts() throws Exception {
        logger.info("--> Starting migration test with stuck index and data node restart without replicas");

        // Setup cluster with allocation disabled
        setupCluster(1, buildDisabledAllocationSettings());

        final String STUCK_INDEX = "stuck-index";

        // Create indices and add data
        createTestIndex(STUCK_INDEX, 1, 1);
        ensureGreen(STUCK_INDEX);

        // Add some test data
        logger.info("--> Adding test data to both indices");
        Map<String, Long> stuckIndexStats = indexData(1, false, STUCK_INDEX);

        long stuckIndexDocs = stuckIndexStats.get(TOTAL_OPERATIONS);
        refreshAndWaitForReplication(STUCK_INDEX);

        try {
            // Trigger first migration
            logger.info("--> Starting migration for stuck index: {}", STUCK_INDEX);
            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", STUCK_INDEX);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                .actionGet();
            assertTrue(stuckResponse.isAcknowledged());

            Thread.sleep(4000);

            // Verify first index hasn't started relocation
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(STUCK_INDEX, false);
            }, 30, TimeUnit.SECONDS);

            // Get current data nodes
            ClusterState state = client().admin().cluster().prepareState().get().getState();
            List<String> dataNodeNames = state.nodes().getNodes().values().stream()
                .filter(node -> node.getRoles().contains(DiscoveryNodeRole.DATA_ROLE))
                .map(DiscoveryNode::getName)
                .collect(Collectors.toList());

            // Bounce data nodes one by one
            logger.info("--> Bouncing data nodes while migration is stuck");
            for (String nodeName : dataNodeNames) {
                logger.info("--> Stopping data node: {}", nodeName);
                internalCluster().restartNode(nodeName);
            }

            // Enable allocation to allow migrations to proceed
            logger.info("--> Enabling allocation to allow migrations to complete");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            // Verify first index started on warm nodes as index setting were changed.
            assertBusy(() -> {
                assertIndexShardLocation(STUCK_INDEX, true);
                // Wait for cluster to stabilize
                ensureGreen(STUCK_INDEX);
            }, 60, TimeUnit.SECONDS);

            // Verify migrations complete
            assertBusy(() -> {
                assertIndexShardLocation(STUCK_INDEX, true);
                verifyWarmIndexSettings(STUCK_INDEX);
            }, 30, TimeUnit.SECONDS);

            // Verify data consistency
            refresh(STUCK_INDEX);

            long finalStuckIndexDocs = client().prepareSearch(STUCK_INDEX)
                .setSize(0).get().getHits().getTotalHits().value();

            logger.info("--> Final document counts - Stuck Index: {} (expected: {})",
                finalStuckIndexDocs, stuckIndexDocs);
            assertEquals("Document count changed for stuck index", stuckIndexDocs, finalStuckIndexDocs);
        } finally {
            client().admin().indices().delete(new DeleteIndexRequest(STUCK_INDEX)).actionGet();
        }
    }

    public void testMigrationWithStuckIndexAndPrimaryShardDataNodeBounceWithReplicas() throws Exception {
        logger.info("--> Starting migration test with stuck index and data node with primary shard bounce with replicas");
        final String STUCK_INDEX = "stuck-index";

        // Setup cluster with allocation disabled

        Settings.Builder settingsBuilder = Settings.builder()
            .put(buildDisabledAllocationSettings());
        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        String primaryNode = internalCluster().startDataOnlyNode(settingsBuilder.build());
        createTestIndex(STUCK_INDEX, 1, 1);
        ensureYellow(STUCK_INDEX);
        String replicaNode = internalCluster().startDataOnlyNode(settingsBuilder.build());
        logger.info("Node for primary shard: {}, replica shard: {} ", primaryNode, replicaNode);
        // start 2 warm nodes.
        internalCluster().startWarmOnlyNodes(2, settingsBuilder.build());
        ensureGreen(STUCK_INDEX);
        // Add some test data
        logger.info("--> Adding test data to both indices");
        Map<String, Long> stuckIndexStats = indexData(1, false, STUCK_INDEX);

        long stuckIndexDocs = stuckIndexStats.get(TOTAL_OPERATIONS);
        refreshAndWaitForReplication(STUCK_INDEX);

        try {
            // Trigger first migration
            logger.info("--> Starting migration for index: {}", STUCK_INDEX);
            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", STUCK_INDEX);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                .actionGet();
            assertTrue(stuckResponse.isAcknowledged());
            Thread.sleep(4000);

            // Verify index hasn't started relocation
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(STUCK_INDEX, false);
            }, 30, TimeUnit.SECONDS);


            logger.info("--> Stopping data node: {}", primaryNode);
            internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNode));

            // Enable allocation to allow migrations to proceed
            logger.info("--> Enabling allocation to allow migrations to complete");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            // Verify that primary shard gets recovered on a warm node
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                DiscoveryNode node = state.nodes().get(shardRouting.currentNodeId());
                assertTrue("Primary shard should be on warm node",
                    node.getRoles().contains(DiscoveryNodeRole.WARM_ROLE));
            }, 30, TimeUnit.SECONDS);

            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                IndexRoutingTable indexRoutingTable = state.routingTable().index(STUCK_INDEX);

                // Get both primary and replica shards
                ShardRouting primaryShard = indexRoutingTable.shard(0).primaryShard();
                List<ShardRouting> replicaShards = indexRoutingTable.shard(0).replicaShards();

                // Log the current state for debugging
                logger.info("Primary shard location: {}", primaryShard.currentNodeId());
                logger.info("Replica shards: {}", replicaShards);

                // Verify replica shard state
                assertFalse("Replica should exist", replicaShards.isEmpty());
                ShardRouting replicaShard = replicaShards.get(0);
                assertTrue("Replica shard should be relocating or started", replicaShard.started() | replicaShard.relocating());
            }, 60, TimeUnit.SECONDS);

            // Verify that the shard eventually relocates to warm node
            assertBusy(() -> {
                ensureGreen(STUCK_INDEX);
                assertIndexShardLocation(STUCK_INDEX, true);
            }, 60, TimeUnit.SECONDS);

            // Verify document count remains the same after migration
            assertHitCount(client().prepareSearch(STUCK_INDEX).setSize(0).get(), stuckIndexDocs);

            // Verify no data loss occurred during node bounce and migration
            assertBusy(() -> {
                SearchResponse response = client().prepareSearch(STUCK_INDEX)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .setSize((int) stuckIndexDocs)
                    .get();
                assertEquals("Document count should match original", stuckIndexDocs, response.getHits().getTotalHits().value());
            }, 30, TimeUnit.SECONDS);
        } finally {
            client().admin().indices().delete(new DeleteIndexRequest(STUCK_INDEX)).actionGet();
        }
    }

    /*
If there is a quorum loss, restoration of indices metadata happens at a later event than the master-change.
This test creates a domain with one master node, creates a quorum loss and checks if the tieringContext
is reconstructed properly
*/
    public void testHotToWarmMigrationDuringQuorumLoss() throws Exception {
        logger.info("--> Starting test for hot-to-warm migration during quorum loss");

        // Setup cluster with disabled allocation initially to control the process
        Settings.Builder settingsBuilder = Settings.builder()
            .put(buildDisabledAllocationSettings());

        // Start cluster nodes with 1 master node for quorum
        String masterNodeId = internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startDataOnlyNodes(2, settingsBuilder.build());
        internalCluster().startWarmOnlyNodes(2, settingsBuilder.build());

        final String[] indices = new String[]{"test-1", "test-2", "test-3"};
        Map<String, Long> indexDocCounts = new HashMap<>();

        try {
            // Create indices and add data
            for (String index : indices) {
                createTestIndex(index, 1, 1);
                Map<String, Long> stats = indexData(1, false, index);
                indexDocCounts.put(index, stats.get(TOTAL_OPERATIONS));
                logger.info("--> Created index {} with {} documents", index, stats.get(TOTAL_OPERATIONS));
            }
            ensureGreen(indices);

            // Verify initial state
            for (String index : indices) {
                assertIndexShardLocation(index, false);
            }
            refresh(indices);

            // Start migration for all indices
            logger.info("--> Starting migration for all indices");
            for (String index : indices) {
                final IndexTieringRequest request = new IndexTieringRequest("warm", index);
                AcknowledgedResponse response = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, request)
                    .actionGet();
                assertTrue(response.isAcknowledged());
            }

            // Sleep to ensure migration requests are processed
            Thread.sleep(5000);

            // Verify migrations are stuck due to disabled allocation
            for (String index : indices) {
                assertBusy(() -> {
                    ClusterState state = client().admin().cluster().prepareState().get().getState();
                    ShardRouting shardRouting = state.routingTable().index(index).shard(0).primaryShard();
                    assertFalse("Shard should not be relocating", shardRouting.relocating());
                    assertIndexShardLocation(index, false);
                }, 30, TimeUnit.SECONDS);
            }

            // Restart the master node
            logger.info("--> Stopping current master node");
            internalCluster().restartNode(masterNodeId);

            // Wait for cluster to stabilize with new master
            ensureStableCluster(5); // Remaining nodes (1 master + 2 data + 2 warm)
            logger.info("--> Cluster stabilized after master node failure");

            // Enable allocation to allow migrations to proceed
            logger.info("--> Enabling allocation to allow migrations to complete");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            Thread.sleep(5000);
            ensureClusterStateConsistency();


            // Verify migrations complete on remaining master
            assertBusy(() -> {
                for (String index : indices) {
                    assertIndexShardLocation(index, true);
                    verifyWarmIndexSettings(index);
                }
            }, 60, TimeUnit.SECONDS);

            // Verify data consistency
            for (String index : indices) {
                refresh(index);
                long expectedCount = indexDocCounts.get(index);
                long actualCount = client().prepareSearch(index)
                    .setSize(0).get().getHits().getTotalHits().value();

                logger.info("--> Index {} document count - expected: {}, actual: {}",
                    index, expectedCount, actualCount);
                assertEquals("Document count mismatch for index " + index,
                    expectedCount, actualCount);
            }

        } finally {
            // Cleanup
            for (String index : indices) {
                client().admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
            }
        }
    }

    public void testMigrationWithStuckIndexAndReplicaShardDataNodeBounce() throws Exception {
        logger.info("--> Starting migration test with stuck index and data node with replica shard bounce");
        final String STUCK_INDEX = "stuck-index";

        // Setup cluster with allocation disabled
        Settings.Builder settingsBuilder = Settings.builder()
            .put(buildDisabledAllocationSettings());
        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        String primaryNode = internalCluster().startDataOnlyNode(settingsBuilder.build());
        createTestIndex(STUCK_INDEX, 1, 1);
        ensureYellow(STUCK_INDEX);
        String replicaNode = internalCluster().startDataOnlyNode(settingsBuilder.build());
        logger.info("Node for primary shard: {}, replica shard: {} ", primaryNode, replicaNode);
        // start 2 warm nodes.
        internalCluster().startWarmOnlyNodes(2, settingsBuilder.build());
        ensureGreen(STUCK_INDEX);
        // Add some test data
        logger.info("--> Adding test data to both indices");
        Map<String, Long> stuckIndexStats = indexData(1, false, STUCK_INDEX);

        long stuckIndexDocs = stuckIndexStats.get(TOTAL_OPERATIONS);
        refreshAndWaitForReplication(STUCK_INDEX);

        try {
            // Trigger first migration
            logger.info("--> Starting migration for index: {}", STUCK_INDEX);
            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", STUCK_INDEX);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                .actionGet();
            assertTrue(stuckResponse.isAcknowledged());
            Thread.sleep(4000);

            // Verify index hasn't started relocation
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(STUCK_INDEX, false);
            }, 30, TimeUnit.SECONDS);


            logger.info("--> Stopping data node: {}", replicaNode);
            internalCluster().stopRandomNode(InternalTestCluster.nameFilter(replicaNode));

            // Enable allocation to allow migrations to proceed
            logger.info("--> Enabling allocation to allow migrations to complete");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            // Verify that primary shard gets recovered on a warm node
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(STUCK_INDEX).shard(0).primaryShard();
                DiscoveryNode node = state.nodes().get(shardRouting.currentNodeId());
                assertTrue("Primary shard should be on warm node",
                    node.getRoles().contains(DiscoveryNodeRole.WARM_ROLE));
            }, 30, TimeUnit.SECONDS);

            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                IndexRoutingTable indexRoutingTable = state.routingTable().index(STUCK_INDEX);

                // Get both primary and replica shards
                ShardRouting primaryShard = indexRoutingTable.shard(0).primaryShard();
                List<ShardRouting> replicaShards = indexRoutingTable.shard(0).replicaShards();

                // Log the current state for debugging
                logger.info("Primary shard location: {}", primaryShard.currentNodeId());
                logger.info("Replica shards: {}", replicaShards);

                // Verify replica shard state
                assertFalse("Replica should exist", replicaShards.isEmpty());
                ShardRouting replicaShard = replicaShards.get(0);
                assertTrue("Replica shard should be relocating or started", replicaShard.started() | replicaShard.relocating());
            }, 60, TimeUnit.SECONDS);

            // Verify that the shard eventually relocates to warm node
            assertBusy(() -> {
                ensureGreen(STUCK_INDEX);
                assertIndexShardLocation(STUCK_INDEX, true);
            }, 60, TimeUnit.SECONDS);

            // Verify document count remains the same after migration
            assertHitCount(client().prepareSearch(STUCK_INDEX).setSize(0).get(), stuckIndexDocs);

            // Verify no data loss occurred during node bounce and migration
            assertBusy(() -> {
                SearchResponse response = client().prepareSearch(STUCK_INDEX)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .setSize((int) stuckIndexDocs)
                    .get();
                assertEquals("Document count should match original", stuckIndexDocs, response.getHits().getTotalHits().value());
            }, 30, TimeUnit.SECONDS);
        } finally {
            client().admin().indices().delete(new DeleteIndexRequest(STUCK_INDEX)).actionGet();
        }
    }

    @AwaitsFix(bugUrl = "") // TODO: Need to fix
    public void testHotToWarmMigrationWithUnassignedReplica() throws Exception {
        logger.info("--> Starting test for hot-to-warm migration with unassigned replica");
        // Start minimal cluster - one master, one hot node, one warm node
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNodes(1);
        internalCluster().startWarmOnlyNodes(1);

        final String INDEX_NAME = "test-index";
        Map<String, Long> indexDocCounts = new HashMap<>();

        try {
            // Create index with 1 primary and 1 replica
            createTestIndex(INDEX_NAME, 1, 1);
            Map<String, Long> stats = indexData(1, false, INDEX_NAME);
            indexDocCounts.put(INDEX_NAME, stats.get(TOTAL_OPERATIONS));
            logger.info("--> Created index {} with {} documents and 1 replica",
                INDEX_NAME, stats.get(TOTAL_OPERATIONS));

            // Ensure yellow - replica won't be assigned as we only have one data node
            ensureYellow(INDEX_NAME);

            // Start migration
            logger.info("--> Starting migration for index with unassigned replica");
            final IndexTieringRequest request = new IndexTieringRequest("warm", INDEX_NAME);
            AcknowledgedResponse response = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, request)
                .actionGet();
            assertTrue(response.isAcknowledged());

            // Verify migration completes despite unassigned replica
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting primaryShard = state.routingTable().index(INDEX_NAME).shard(0).primaryShard();
                List<ShardRouting> replicaShards = state.routingTable().index(INDEX_NAME).shard(0).replicaShards();

                // Verify primary is on warm node
                assertTrue("Primary should be assigned", primaryShard.assignedToNode());
                DiscoveryNode primaryNode = state.nodes().get(primaryShard.currentNodeId());
                assertTrue("Primary should be on warm node",
                    primaryNode.getRoles().contains(DiscoveryNodeRole.WARM_ROLE));

                // Verify replica remains unassigned
                assertTrue("Replica should remain unassigned", replicaShards.get(0).unassigned());

                // Verify index settings
                assertIndexShardLocation(INDEX_NAME, true);
                verifyWarmIndexSettings(INDEX_NAME);
            }, 30, TimeUnit.SECONDS);

            // Verify data consistency
            refresh(INDEX_NAME);
            long expectedCount = indexDocCounts.get(INDEX_NAME);
            long actualCount = client().prepareSearch(INDEX_NAME)
                .setSize(0).get().getHits().getTotalHits().value();

            logger.info("--> Index {} document count - expected: {}, actual: {}",
                INDEX_NAME, expectedCount, actualCount);
            assertEquals("Document count mismatch", expectedCount, actualCount);

        } finally {
            client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
        }
    }

    public void testHotToWarmMigrationWithMasterNodeFailure() throws Exception {
        logger.info("--> Starting test for hot-to-warm migration with master node failed");

        // Setup cluster with disabled allocation initially to control the process
        Settings.Builder settingsBuilder = Settings.builder()
            .put(buildDisabledAllocationSettings());

        // Start cluster nodes with 3 master nodes for quorum
        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startDataOnlyNodes(2, settingsBuilder.build());
        internalCluster().startWarmOnlyNodes(2, settingsBuilder.build());

        final String[] indices = new String[]{"test-1", "test-2", "test-3"};
        Map<String, Long> indexDocCounts = new HashMap<>();

        try {
            // Create indices and add data
            for (String index : indices) {
                createTestIndex(index, 1, 1);
                Map<String, Long> stats = indexData(1, false, index);
                indexDocCounts.put(index, stats.get(TOTAL_OPERATIONS));
                logger.info("--> Created index {} with {} documents", index, stats.get(TOTAL_OPERATIONS));
            }
            ensureGreen(indices);

            // Verify initial state
            for (String index : indices) {
                assertIndexShardLocation(index, false);
            }
            refresh(indices);

            // Start migration for all indices
            logger.info("--> Starting migration for all indices");
            for (String index : indices) {
                final IndexTieringRequest request = new IndexTieringRequest("warm", index);
                AcknowledgedResponse response = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, request)
                    .actionGet();
                assertTrue(response.isAcknowledged());
            }

            // Sleep to ensure migration requests are processed
            Thread.sleep(5000);

            // Verify migrations are stuck due to disabled allocation
            for (String index : indices) {
                assertBusy(() -> {
                    ClusterState state = client().admin().cluster().prepareState().get().getState();
                    ShardRouting shardRouting = state.routingTable().index(index).shard(0).primaryShard();
                    assertFalse("Shard should not be relocating", shardRouting.relocating());
                    assertIndexShardLocation(index, false);
                }, 30, TimeUnit.SECONDS);
            }

            // Stop current master node
            logger.info("--> Stopping current master node");
            internalCluster().stopCurrentClusterManagerNode();

            // Wait for cluster to stabilize with new master
            ensureStableCluster(6); // Remaining nodes (2 master + 2 data + 2 warm)
            logger.info("--> Cluster stabilized after master node failure");

            // Enable allocation to allow migrations to proceed
            logger.info("--> Enabling allocation to allow migrations to complete");
            client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(buildEnabledAllocationSettings(2))
                .get();

            // Verify migrations complete on remaining master
            assertBusy(() -> {
                for (String index : indices) {
                    assertIndexShardLocation(index, true);
                    verifyWarmIndexSettings(index);
                }
            }, 60, TimeUnit.SECONDS);

            // Verify data consistency
            for (String index : indices) {
                refresh(index);
                long expectedCount = indexDocCounts.get(index);
                long actualCount = client().prepareSearch(index)
                    .setSize(0).get().getHits().getTotalHits().value();

                logger.info("--> Index {} document count - expected: {}, actual: {}",
                    index, expectedCount, actualCount);
                assertEquals("Document count mismatch for index " + index,
                    expectedCount, actualCount);
            }

        } finally {
            // Cleanup
            for (String index : indices) {
                client().admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
            }
        }
    }

    public void testWarmMigrationWithTieringQueueSizeBreached() throws Exception {

        Settings.Builder settingsBuilder = Settings.builder()
                .put(buildDisabledAllocationSettings())
                .put(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS_KEY, 2);

        setupCluster(1, settingsBuilder.build());

        createTestIndex(INDEX_NAME, 1, 1);
        createTestIndex("test-index-2", 1, 1);
        createTestIndex("test-index-3", 1, 1);

        refreshClusterInfo();

        List<String> inFlightTieringIndices = List.of(INDEX_NAME,"test-index-2");
        for (String index : inFlightTieringIndices) {
            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", index);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                    .actionGet();
            assertTrue(stuckResponse.isAcknowledged());
            Thread.sleep(4000);

            // Verify index hasn't started relocation
            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(index).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(index, false);
            }, 30, TimeUnit.SECONDS);

        }
        IndexTieringRequest tieringRequest = new IndexTieringRequest("WARM","test-index-3");
        // Try migrating again and verify error
        assertThrows(OpenSearchRejectedExecutionException.class,
                ()-> client().admin().indices()
                        .execute(HotToWarmTierAction.INSTANCE, tieringRequest)
                        .actionGet());

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
        client().admin().indices().delete(new DeleteIndexRequest("test-index-2")).actionGet();
        client().admin().indices().delete(new DeleteIndexRequest("test-index-3")).actionGet();
    }

    public void testWarmMigrationWithAllNodesWaterMarkBreached() {
        setupCluster(1, "20b", "1");

        createTestIndex(INDEX_NAME, 1, 1);

        refreshClusterInfo();
        // Try migrating again and verify error
        verifyMigrationError(INDEX_NAME,
                "since we don't have enough space on all warm nodes");

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testWarmMigrationWithLargeTieringIndicesQueue() throws InterruptedException {
        // Setup and create index
        setupCluster(1, "50b", "1");
        createTestIndex(INDEX_NAME, 1, 1);
        createTestIndex("test-index-2", 1, 1);
        createTestIndex("test-index-3", 1, 1);
        refreshClusterInfo();

        Set<String> tieringIndicesInProgress = new HashSet<>();
        tieringIndicesInProgress.add(INDEX_NAME);
        tieringIndicesInProgress.add("test-index-2");

        // Pausing Relocation to test the consideration of memory for in-progress tiering indices
        try {
            client().admin().cluster().prepareUpdateSettings()
                    .setTransientSettings(buildDisabledAllocationSettings())
                    .get();
            // Trigger first migration
            logger.info("--> Starting migration for stuck index: {}", INDEX_NAME);

            final IndexTieringRequest stuckRequest = new IndexTieringRequest("warm", INDEX_NAME);
            AcknowledgedResponse stuckResponse = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, stuckRequest)
                    .actionGet();
            assertTrue(stuckResponse.isAcknowledged());

            assertBusy(() -> {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                ShardRouting shardRouting = state.routingTable().index(INDEX_NAME).shard(0).primaryShard();
                assertFalse("Shard should not be relocating", shardRouting.relocating());
                assertIndexShardLocation(INDEX_NAME, false);
            }, 30, TimeUnit.SECONDS);

            // Trigger second migration
            logger.info("--> Starting migration for second index while first is stuck");
            final IndexTieringRequest secondRequest = new IndexTieringRequest("warm", "test-index-2");
            AcknowledgedResponse secondResponse = client().admin().indices()
                    .execute(HotToWarmTierAction.INSTANCE, secondRequest)
                    .actionGet();
            assertTrue(secondResponse.isAcknowledged());


            // Try migrating again and verify error
            verifyMigrationError("test-index-3",
                    "since we don't have enough space on all warm nodes");

            // Cleanup

            client().admin().cluster().prepareUpdateSettings()
                    .setTransientSettings(buildEnabledAllocationSettings(2))
                    .get();
            client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
            client().admin().indices().delete(new DeleteIndexRequest("test-index-2")).actionGet();
            client().admin().indices().delete(new DeleteIndexRequest("test-index-3")).actionGet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void testWarmMigrationWithAllNodesWaterMarkBreachedWithExistingTieredIndices() throws Exception {
        // Setup cluster with allocation disabled
        Settings.Builder settingsBuilder = Settings.builder()
                .put(CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "150b")
                .put(CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(),"100b")
                .put(CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "50b")
                .put(CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey(), "0ms")
                .put(DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING.getKey(), 2.0);


        internalCluster().startClusterManagerOnlyNode(settingsBuilder.build());
        internalCluster().startDataOnlyNodes(2,settingsBuilder.build());
        internalCluster().startWarmOnlyNodes(1,settingsBuilder.build());

        createTestIndex(INDEX_NAME, 1, 0);
        createTestIndex("test-index-2", 1, 0);

        // Migrate index to warm
        final IndexTieringRequest tieringRequest = new IndexTieringRequest("warm", INDEX_NAME);
        AcknowledgedResponse stuckResponse = client().admin().indices()
                .execute(HotToWarmTierAction.INSTANCE, tieringRequest)
                .actionGet();
        assertTrue(stuckResponse.isAcknowledged());

        // Verify migration completion
        assertBusy(() -> {
            ClusterState state = client().admin().cluster().prepareState().get().getState();
            ShardRouting shardRouting = state.routingTable().index(INDEX_NAME).shard(0).primaryShard();
            assertIndexShardLocation(INDEX_NAME, true);
        }, 30, TimeUnit.SECONDS);

        MockInternalClusterInfoService clusterInfoService = getMockInternalClusterInfoService();
        // Modify shard sizes to report as 600 bytes
        clusterInfoService.setShardSizeFunctionAndRefresh(shardRouting -> 600L);
        // Free size excluding low watermark = 850 - 150*2 = 550 < 600 (incoming shards size)
        clusterInfoService.setDiskUsageFunctionAndRefresh((discoveryNode,fsInfoPath) -> setDiskUsage(fsInfoPath, TOTAL_SPACE_BYTES, 850L));

        // Verify migration fails with the risk of breaching low-warm-disk-watermark
        verifyMigrationError("test-index-2",
                "since we don't have enough space on all warm nodes");

        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
        client().admin().indices().delete(new DeleteIndexRequest("test-index-2")).actionGet();

    }

    public void testWarmMigrationWithoutClusterInfoMockForJVMUtilization() throws Exception {

        // ResourceUsageCollector requires atleast below stats to be ready within a window to capture stats
        Settings.Builder settingsBuilder = Settings.builder()
                .put(ResourceTrackerSettings.GLOBAL_JVM_USAGE_AC_WINDOW_DURATION_SETTING.getKey(),
                        TimeValue.timeValueMillis(500))
                .put(ResourceTrackerSettings.GLOBAL_IO_USAGE_AC_WINDOW_DURATION_SETTING.getKey(),
                        TimeValue.timeValueMillis(5000))
                .put(ResourceTrackerSettings.GLOBAL_CPU_USAGE_AC_WINDOW_DURATION_SETTING.getKey(),
                        TimeValue.timeValueMillis(500));

        setupCluster(1,settingsBuilder.build());
        createTestIndex(INDEX_NAME, 1, 1);

        // Adding a delay to let the JVM and CPU stats get collected
        Thread.sleep(6000);
        refreshClusterInfo();
        // Try migrating
        migrateToWarm(INDEX_NAME);

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    @AwaitsFix(bugUrl = "") // TODO: Need to fix
    public void testWarmMigrationWithFileCacheActiveUsageBreached() throws Exception {

        // ResourceUsageCollector requires atleast below stats to be ready within a window to capture stats
        Settings.Builder settingsBuilder = Settings.builder()
                .put(FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_KEY,0);

        setupCluster(1,settingsBuilder.build());
        createTestIndex(INDEX_NAME, 1, 1);
        refreshClusterInfo();
        // Try migrating
        verifyMigrationError(INDEX_NAME,
                "Rejecting tiering request as FileCache Active Usage is high on all Warm nodes");

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testWarmMigrationWithAllNodesJVMUtilizationBreached()  {
        setupCluster(1, "10b", "1");

        createTestIndex(INDEX_NAME, 1, 1);

        MockInternalClusterInfoService clusterInfoService = getMockInternalClusterInfoService();
        clusterInfoService.setDiskUsageFunctionAndRefresh((discoveryNode,fsInfoPath) -> setDiskUsage(fsInfoPath, TOTAL_SPACE_BYTES, TOTAL_SPACE_BYTES));
        clusterInfoService.setShardSizeFunctionAndRefresh(shardRouting -> 230L);
        clusterInfoService.setNodeResourceUsageFunctionAndRefresh(
                (nodeId-> new NodeResourceUsageStats(nodeId,System.currentTimeMillis(),99,20,null)));

        // Try migrating again and verify error
        verifyMigrationError(INDEX_NAME,
                "Rejecting tiering request as JVM Utilization is high on all [WARM] nodes");

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).actionGet();
    }

    public void testWarmMigrationReplicaCountFrom2To1() throws Exception {
        setupCluster(2, null, null);

        // Create index with 2 replicas
        createTestIndex("test-replica-2to1", 1, 2);

        // Verify initial replica count
        String initialSettings = client().admin().indices()
            .prepareGetSettings("test-replica-2to1")
            .get().getSetting("test-replica-2to1", IndexMetadata.SETTING_NUMBER_OF_REPLICAS);
        assertEquals("2", initialSettings);

        // Migrate to warm
        migrateToWarm("test-replica-2to1");

        // Verify replica count is now 1
        String finalSettings = client().admin().indices()
            .prepareGetSettings("test-replica-2to1")
            .get().getSetting("test-replica-2to1", IndexMetadata.SETTING_NUMBER_OF_REPLICAS);
        assertEquals("1", finalSettings);

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest("test-replica-2to1")).actionGet();
    }

    public void testWarmMigrationReplicaCountFrom0To1() throws Exception {
        setupCluster(1, null, null);

        // Create index with 0 replicas
        createTestIndex("test-replica-0to1", 1, 0);

        // Verify initial replica count
        String initialSettings = client().admin().indices()
            .prepareGetSettings("test-replica-0to1")
            .get().getSetting("test-replica-0to1", IndexMetadata.SETTING_NUMBER_OF_REPLICAS);
        assertEquals("0", initialSettings);

        // Migrate to warm
        migrateToWarm("test-replica-0to1");

        // Verify replica count is now 1
        String finalSettings = client().admin().indices()
            .prepareGetSettings("test-replica-0to1")
            .get().getSetting("test-replica-0to1", IndexMetadata.SETTING_NUMBER_OF_REPLICAS);
        assertEquals("1", finalSettings);

        // Cleanup
        client().admin().indices().delete(new DeleteIndexRequest("test-replica-0to1")).actionGet();
    }

    // This test is not directly related to hot to warm migration feature.
    public void testBulkOnWarmIndexWithFileCacheEvictionHappeningAlways() throws Exception {
        setupCluster(1,null,null);
        createTestIndex(INDEX_NAME_2, 1, 1);

        migrateToWarm(INDEX_NAME_2);

        // Ingesting docs again before force merge
        for (int i=0; i < 5; i++) {
            indexBulk(INDEX_NAME_2, 5000);
            flushAndRefresh(INDEX_NAME_2);
            Thread.sleep(1000);
        }
        // ensuring cluster is green
        ensureGreen();
        // Ingesting docs again before force merge
        indexBulk(INDEX_NAME_2, 5000);
        flushAndRefresh(INDEX_NAME_2);
        // Deleting the index (so that ref count drops to zero for all the files) and then pruning the cache to clear it to avoid any file
        // leaks
        ensureGreen();
        assertAcked(client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME_2)).get());
    }

    protected MockInternalClusterInfoService getMockInternalClusterInfoService() {
        return (MockInternalClusterInfoService) internalCluster().getCurrentClusterManagerNodeInstance(ClusterInfoService.class);
    }

    protected static FsInfo.Path setDiskUsage(FsInfo.Path original, long totalBytes, long freeBytes) {
        return new FsInfo.Path(original.getPath(), original.getMount(), totalBytes, freeBytes, freeBytes);
    }

    protected void refreshClusterInfo() {
        InternalClusterInfoService infoService = (InternalClusterInfoService) internalCluster().getInstance(
                ClusterInfoService.class,
                internalCluster().getClusterManagerName()
        );
        infoService.refresh();
    }
}
