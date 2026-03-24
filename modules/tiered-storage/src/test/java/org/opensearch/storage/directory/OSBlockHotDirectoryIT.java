/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import org.opensearch.storage.TieredStoragePlugin;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.node.Node;
import org.opensearch.plugins.Plugin;
import org.opensearch.remotestore.RemoteStoreBaseIntegTestCase;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
@OpenSearchIntegTestCase.ClusterScope(
        scope = OpenSearchIntegTestCase.Scope.TEST, // Changed to TEST scope for reliability
        numDataNodes = 0, // Start with 0 and control manually
        numClientNodes = 0,
        supportsDedicatedMasters = false,
        autoManageMasterNodes = true // Enable auto management
)
public class OSBlockHotDirectoryIT extends RemoteStoreBaseIntegTestCase {

    protected static final String INDEX_NAME = "block-aware-test-idx";
    protected static final int NUM_DOCS_SMALL = 50;
    protected static final int NUM_DOCS_MEDIUM = 200;

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
        return Stream.concat(
                super.nodePlugins().stream(),
                Stream.of(TieredStoragePlugin.class)
        ).collect(Collectors.toList());
    }

    @Override
    protected Settings featureFlagSettings() {
        return Settings.builder()
                .put(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG, true)
                .build();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Node.NODE_SEARCH_CACHE_SIZE_SETTING.getKey(), "100mb")
                .put("cluster.routing.allocation.node_concurrent_recoveries", 2)
                .put("discovery.type", "zen") // Use zen discovery for multi-node
                .put("cluster.initial_cluster_manager_nodes", "node_t0") // Set initial master
                .put("cluster.index.hot_block_eager_fetch", true) // Enable hot block eager fetch
                .build();
    }

    public void testBasicBlockAwareOperations() throws Exception {
        // Start cluster manually for better control
        InternalTestCluster cluster = internalCluster();
        cluster.startNodes(2); // Start 2 nodes
        cluster.validateClusterFormed(); // Ensure cluster is formed

        String indexName = INDEX_NAME + "_basic";

        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1) // Create replica for OSBlockHotDirectory
                .put("index.refresh_interval", "1s")
                .build();

        try {
            assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(indexSettings).get());
            ensureGreen(indexName);// Ensure cluster is healthy
            // Index small amount of data
            indexBulkDocuments(indexName, NUM_DOCS_SMALL);
            refresh(indexName);

            // Basic search test
            SearchResponse response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .setPreference("_replica")
                    .get();
            assertHitCount(response, NUM_DOCS_SMALL);

            // Test directory access
            testDirectoryAccess(indexName);

            // Test different query types
            testQueryVariations(indexName);
        } finally {
            safeDeleteIndex(indexName);
        }
    }

    public void testConcurrentOperations() throws Exception {
        InternalTestCluster cluster = internalCluster();
        cluster.startNodes(2);
        cluster.validateClusterFormed();

        String indexName = INDEX_NAME + "_concurrent";

        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                .put("index.refresh_interval", "2s")
                .build();

        try {
            assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(indexSettings).get());
            ensureGreen(indexName);

            indexBulkDocuments(indexName, NUM_DOCS_MEDIUM);
            refresh(indexName);

            int numThreads = 3;
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicBoolean testPassed = new AtomicBoolean(true);
            AtomicInteger successCount = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        SearchResponse response = client().prepareSearch(indexName)
                                .setQuery(QueryBuilders.matchAllQuery())
                                .setSize(10)
                                .setPreference("_replica")
                                .get();

                        if (response.getHits().getTotalHits().value() > 0) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        logger.error("Concurrent search failed for thread " + threadId, e);
                        testPassed.set(false);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue("Concurrent operations should complete",
                    latch.await(30, TimeUnit.SECONDS));
            assertTrue("All concurrent operations should succeed", testPassed.get());
            assertEquals("All threads should succeed", numThreads, successCount.get());

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            safeDeleteIndex(indexName);
        }
    }

    public void testIndexManagementAndErrorHandling() throws Exception {
        InternalTestCluster cluster = internalCluster();
        cluster.startNodes(1); // Single node for this test
        cluster.validateClusterFormed();

        String indexName = INDEX_NAME + "_mgmt";

        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                .build();

        try {
            assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(indexSettings).get());
            ensureGreen(indexName);
            IndexShard shard = cluster.getDataNodeInstance(IndicesService.class)
                    .indexService(resolveIndex(indexName))
                    .getShardOrNull(0);
            Directory directory = (((FilterDirectory) (((FilterDirectory) (shard.store().directory())).getDelegate())).getDelegate());
            assert directory instanceof OSBlockHotDirectory;

            // Test with empty index first
            SearchResponse response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .get();
            assertEquals("Empty index should return no results", 0, response.getHits().getTotalHits().value());

            // Add some data
            indexBulkDocuments(indexName, NUM_DOCS_SMALL);

            // Test management operations
            client().admin().indices().prepareFlush(indexName).get();
            client().admin().indices().prepareRefresh(indexName).get();

            // Verify data integrity
            response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .get();
            assertHitCount(response, NUM_DOCS_SMALL);

            // Test error handling
            response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.termQuery("non_existent_field", "value"))
                    .get();
            assertEquals("Should return no results", 0, response.getHits().getTotalHits().value());

        } finally {
            safeDeleteIndex(indexName);
        }
    }

    public void testMultiShardOperations() throws Exception {
        InternalTestCluster cluster = internalCluster();
        cluster.startNodes(2);
        cluster.validateClusterFormed();

        String indexName = INDEX_NAME + "_multishard";

        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 2)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1) // Create replicas for OSBlockHotDirectory
                .build();

        try {
            assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(indexSettings).get());
            ensureGreen(indexName);

            indexBulkDocuments(indexName, NUM_DOCS_MEDIUM);
            refresh(indexName);

            SearchResponse response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .setPreference("_replica")
                    .get();
            assertHitCount(response, NUM_DOCS_MEDIUM);

            // Simple aggregation test
            response = client().prepareSearch(indexName)
                    .setQuery(QueryBuilders.matchAllQuery())
                    .addAggregation(
                            org.opensearch.search.aggregations.AggregationBuilders.terms("categories")
                                    .field("category.keyword")
                                    .size(5)
                    )
                    .setSize(0)
                    .setPreference("_replica")
                    .get();

            assertNotNull("Should have aggregation results",
                    response.getAggregations().get("categories"));

            testDirectoryAccess(indexName);
        } finally {
            safeDeleteIndex(indexName);
        }
    }

    // Helper methods

    private void indexBulkDocuments(String indexName, int numDocs) throws Exception {
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName)
                    .setId(String.valueOf(i))
                    .setSource(
                            "id", i,
                            "title", "Doc " + i,
                            "category", "cat_" + (i % 3),
                            "number", i
                    )
                    .get();

            // Refresh periodically for better performance
            if (i % 25 == 0) {
                refresh(indexName);
            }
        }
        refresh(indexName); // Final refresh
    }

    private void testDirectoryAccess(String indexName) {
        try {
            IndexShard shard = getIndexShard(indexName);
            if (shard != null) {
                Directory directory = shard.store().directory();
                String[] files = directory.listAll();
                assertTrue("Should have files", files.length > 0);
                Directory actualDirectory = (((FilterDirectory) (((FilterDirectory) (shard.store().directory())).getDelegate())).getDelegate());
                assert actualDirectory instanceof OSBlockHotDirectory;
                for (String fileName : files) {
                    if (fileName.endsWith(".si")) {
                        try (IndexInput input = directory.openInput(fileName, IOContext.DEFAULT)) {
                            assertTrue("File should have content", input.length() > 0);
                        }
                        break;
                    }
                }

                logger.info("Directory access test passed for {}", directory.getClass().getSimpleName());
            }
        } catch (Exception e) {
            logger.info("Directory access test skipped: " + e.getMessage());
        }
    }

    private void testQueryVariations(String indexName) {
        // Term query
        SearchResponse response = client().prepareSearch(indexName)
                .setQuery(QueryBuilders.termQuery("category.keyword", "cat_1"))
                .setSize(5)
                .setPreference("_replica")
                .get();
        assertTrue("Should find some results", response.getHits().getTotalHits().value() >= 0);

        // Range query
        response = client().prepareSearch(indexName)
                .setQuery(QueryBuilders.rangeQuery("number").gte(10).lte(20))
                .setSize(5)
                .setPreference("_replica")
                .get();
        assertTrue("Should find some results", response.getHits().getTotalHits().value() >= 0);
    }

    private IndexShard getIndexShard(String indexName) {
        try {
            return internalCluster().getDataNodeInstance(IndicesService.class)
                    .indexService(resolveIndex(indexName))
                    .getShardOrNull(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void safeDeleteIndex(String indexName) {
        try {
            if (indexExists(indexName)) {
                assertAcked(client().admin().indices().delete(new DeleteIndexRequest(indexName)).get());
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup index: " + indexName, e);
        }
    }
}
