package org.opensearch.storage.action.admin.tiering.status.model;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.index.Index;
import org.opensearch.storage.action.tiering.status.model.TieringStatus;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TieringStatusTests extends OpenSearchTestCase {


    public void testTieringStatusConstructor() {
        String indexName = "test-index";
        String status = "RUNNING";
        String source = "hot";
        String target = "warm";
        long startTime = System.currentTimeMillis();

        TieringStatus tieringStatus = new TieringStatus(indexName, status, source, target, startTime);

        assertEquals(indexName, tieringStatus.getIndexName());
        assertEquals(status, tieringStatus.getStatus());
        assertEquals(source, tieringStatus.getSource());
        assertEquals(target, tieringStatus.getTarget());
        assertEquals(startTime, tieringStatus.getStartTime());
    }


    public void testTieringStatusSerialization() throws IOException {
        TieringStatus original = new TieringStatus("test-index", "RUNNING", "hot", "warm", 123456L);

        // Mock StreamOutput
        StreamOutput streamOutput = mock(StreamOutput.class);
        original.writeTo(streamOutput);

        verify(streamOutput).writeString("test-index");
        verify(streamOutput).writeString("RUNNING");
        verify(streamOutput).writeString("hot");
        verify(streamOutput).writeString("warm");
        verify(streamOutput).writeLong(123456L);
    }


    public void testShardLevelStatusCreation() {
        Map<String, Integer> counters = new HashMap<>();
        counters.put("pending", 2);
        counters.put("succeeded", 3);
        counters.put("running", 1);
        counters.put("total", 6);

        List<TieringStatus.OngoingShard> ongoingShards = Collections.singletonList(
                new TieringStatus.OngoingShard(1, "node2")
        );

        TieringStatus.ShardLevelStatus status = new TieringStatus.ShardLevelStatus(counters, ongoingShards);

        assertEquals(counters, status.getShardLevelCounters());
    }

    public void testFromRoutingTable() {
        // Test for WARM tier
        testForTier(true, true);
        // Test for HOT tier
        testForTier(false, true);

        // Test for detailed Flag as false
        testForTier(false, false);
    }

    private void testForTier(boolean isWarmTier, boolean isDetailedFlagEnabled) {
        // Mock ClusterState and its components
        ClusterState clusterState = mock(ClusterState.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);

        // Create node with appropriate roles
        Set<DiscoveryNodeRole> roles = new HashSet<>();
        if (isWarmTier) {
            roles.add(DiscoveryNodeRole.WARM_ROLE);
        } else {
            roles.add(DiscoveryNodeRole.DATA_ROLE);
        }

        DiscoveryNode node = new DiscoveryNode(
                "node-" + (isWarmTier ? "warm" : "hot"),
                buildNewFakeTransportAddress(),
                emptyMap(),
                roles,
                Version.CURRENT
        );

        RoutingTable.Builder routingTableBuilder = new RoutingTable.Builder()
                .add(createRoutingTable(new Index("test-index", "na")));

        when(clusterState.routingTable()).thenReturn(routingTableBuilder.build());
        when(clusterState.getNodes()).thenReturn(nodes);
        when(nodes.get(any())).thenReturn(node);

        String targetTier = isWarmTier ? "WARM" : "HOT";
        TieringStatus.ShardLevelStatus status = TieringStatus.ShardLevelStatus
                .fromRoutingTable(clusterState, "test-index", false, targetTier);

        assertNotNull(status);
        Map<String, Integer> counters = status.getShardLevelCounters();

        // Verify counters
        assertTrue(counters.containsKey("total"));
        assertTrue(counters.containsKey("pending"));
        assertTrue(counters.containsKey("succeeded"));
        assertTrue(counters.containsKey("running"));
        if(isDetailedFlagEnabled) {
            assertFalse(counters.containsKey("ongoing_shards"));
        }

        // Additional assertions specific to tier if needed
        if (isWarmTier) {
            assertTrue(node.isWarmNode());
        } else {
            assertFalse(node.isWarmNode());
        }
    }

    public void testOngoingShardSerialization() throws IOException {
        TieringStatus.OngoingShard shard = new TieringStatus.OngoingShard(1, "node2");

        StreamOutput streamOutput = mock(StreamOutput.class);
        shard.writeTo(streamOutput);

        verify(streamOutput).writeInt(1);
        verify(streamOutput).writeString("node2");
    }

    private List<ShardRouting> createTestShards() {
        // Create and return test shard routings
        // This would be implementation-specific based on your needs
        return Collections.emptyList();
    }

    private IndexRoutingTable createRoutingTable(Index index) {
        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(index);
        ShardRouting primaryShard = TestShardRouting.newShardRouting(
                index.getName(), 1,
                "node-2",
                "node-3",
                true,
                ShardRoutingState.RELOCATING
        );

        builder.addShard(primaryShard);
        ShardRouting replicaShard = TestShardRouting.newShardRouting(
                index.getName(), 2,
                "node-2",
                "node-3",
                false,
                ShardRoutingState.RELOCATING
        );
        builder.addShard(replicaShard);

        return builder.build();
    }
}
