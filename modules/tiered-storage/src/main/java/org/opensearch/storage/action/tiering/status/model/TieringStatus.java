package org.opensearch.storage.action.tiering.status.model;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Migration status object
 */
public class TieringStatus implements ToXContentObject, Writeable {

    public static final String TIERING_STATUS = "tiering_status";
    public static final String INDEX = "index";
    public static final String STATE = "state";
    public static final String SOURCE = "source";
    public static final String TARGET = "target";
    public static final String START_TIME = "start_time";
    public static final String SHARD_LEVEL_STATUS = "shard_level_status";
    public static final String SUCCEEDED_SHARDS = "succeeded";
    public static final String RUNNING_SHARDS = "running";
    public static final String PENDING_SHARDS = "pending";
    public static final String TOTAL_SHARDS = "total";
    public static final String SHARD_RELOCATION_STATUS = "shard_relocation_status";
    public static final String RELOCATING_NODE_ID = "relocating_node_id";
    public static final String SOURCE_SHARD_ID = "source_shard_id";

    private ShardLevelStatus shardLevelStatus = null;
    private String indexName;
    private String status;
    private String source;
    private String target;
    private long startTime;

    public String getIndexName() {
        return indexName;
    }

    public ShardLevelStatus getShardLevelStatus() {
        return shardLevelStatus;
    }

    public void setShardLevelStatus(ShardLevelStatus shardLevelStatus) {
        this.shardLevelStatus = shardLevelStatus;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getSource() {
        return this.source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public TieringStatus(final String indexName,final String status,final String source,final String target,final long startTime) {
        this.indexName = indexName;
        this.status = status;
        this.source = source;
        this.target = target;
        this.startTime = startTime;
    }

    public TieringStatus() {
    }

    @Override
    public void writeTo(StreamOutput streamOutput) throws IOException {
        streamOutput.writeString(indexName);
        streamOutput.writeString(status);
        streamOutput.writeString(source);
        streamOutput.writeString(target);
        streamOutput.writeLong(startTime);
        streamOutput.writeOptionalWriteable(shardLevelStatus);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
        xContentBuilder.startObject(TIERING_STATUS);
        xContentBuilder.field(INDEX, this.getIndexName());
        xContentBuilder.field(STATE, this.getStatus());
        xContentBuilder.field(SOURCE, this.getSource());
        xContentBuilder.field(TARGET, this.getTarget());
        xContentBuilder.field(START_TIME, this.getStartTime());
        if (shardLevelStatus != null) {
            shardLevelStatus.toXContent(xContentBuilder, params);
        }
        xContentBuilder.endObject();
        return xContentBuilder;
    }

    public static TieringStatus readFrom(StreamInput in) throws IOException {
        final TieringStatus tieringStatus = new TieringStatus();
        tieringStatus.setIndexName(in.readString());
        tieringStatus.setStatus(in.readString());
        tieringStatus.setSource(in.readString());
        tieringStatus.setTarget(in.readString());
        tieringStatus.setStartTime(in.readLong());
        tieringStatus.setShardLevelStatus(in.readOptionalWriteable(ShardLevelStatus::new));

        return tieringStatus;
    }

    public static class ShardLevelStatus implements ToXContentObject, Writeable  {
        private final Map<String, Integer> shardLevelCounters;

        public List<OngoingShard> getOngoingShards() {
            return ongoingShards;
        }

        private final List<OngoingShard> ongoingShards;

        public Map<String, Integer> getShardLevelCounters() {
            return shardLevelCounters;
        }

        public ShardLevelStatus(StreamInput in) throws IOException {
            shardLevelCounters = new HashMap<>();
            final Map<String, Object> inputMap = in.readMap();
            for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
                shardLevelCounters.put(entry.getKey(), Integer.parseInt(entry.getValue().toString()));
            }
            List<OngoingShard> shards = in.readList(OngoingShard::new);
            ongoingShards = shards != null ? shards : Collections.emptyList();
        }

        public ShardLevelStatus(Map<String, Integer> shardLevelCounters, List<OngoingShard> ongoingShards) {
            this.shardLevelCounters = shardLevelCounters;
            this.ongoingShards = ongoingShards;
        }

        public static ShardLevelStatus fromRoutingTable(ClusterState clusterState, String index,Boolean isDetailedFlagEnabled, String targetTier) {
            final List<ShardRouting> routingTable = clusterState.routingTable().allShards(index);
            final Map<String, Integer> shardLevelCounters = new HashMap<>();
            final List<OngoingShard> ongoingShards = new ArrayList<>();

            int pending = 0;
            int running = 0;
            int succeeded = 0;
            boolean isTargetWarm = "WARM".equalsIgnoreCase(targetTier);

            for (ShardRouting shard : routingTable) {
                // Switch based on shard routing state. Note that INITIALIZING is missing below since we will have a
                // corresponding shard in RELOCATING state.
                switch(shard.state()) {
                    case STARTED:
                        // Only count STARTED shards as done if they're placed on target nodes.
                        final boolean isWarmNode = clusterState.getNodes().get(shard.currentNodeId()).isWarmNode();
                        if (isTargetWarm == isWarmNode) {
                            succeeded++;
                        } else {
                            pending++;
                        }
                        break;
                    case UNASSIGNED:
                        pending++;
                        break;
                    case RELOCATING:
                        running++;
                        if(isDetailedFlagEnabled) {
                            ongoingShards.add(new OngoingShard(
                                    shard.shardId().id(),
                                    shard.relocatingNodeId()
                            ));
                        }

                }
            }
            shardLevelCounters.put(PENDING_SHARDS, pending);
            shardLevelCounters.put(SUCCEEDED_SHARDS, succeeded);
            shardLevelCounters.put(RUNNING_SHARDS, running);
            shardLevelCounters.put(TOTAL_SHARDS, pending + succeeded + running);
            return new ShardLevelStatus(shardLevelCounters,ongoingShards);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeMapWithConsistentOrder(shardLevelCounters);
            if (ongoingShards != null) {
                out.writeList(ongoingShards);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject(SHARD_LEVEL_STATUS);
            for (Map.Entry<String, Integer> counter : shardLevelCounters.entrySet()) {
                builder.field(counter.getKey(), counter.getValue());
            }

            if (ongoingShards != null && !ongoingShards.isEmpty()) {
                builder.startArray(SHARD_RELOCATION_STATUS);
                for (OngoingShard shard : ongoingShards) {
                    if (shard != null) {
                        shard.toXContent(builder, params);
                    }
                }
                builder.endArray();
            }
            builder.endObject();
            return builder;
        }
    }

    public static class OngoingShard implements ToXContentObject, Writeable {
        private final int sourceShardId;

        private final String relocatingNodeId;

        public OngoingShard(int shardId, final String relocatingNodeId) {
            this.sourceShardId = shardId;
            this.relocatingNodeId = relocatingNodeId;
        }

        public OngoingShard(StreamInput in) throws IOException {
            this.sourceShardId = in.readInt();
            this.relocatingNodeId = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeInt(sourceShardId);
            out.writeString(relocatingNodeId != null ? relocatingNodeId : "");
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject()
                    .field(SOURCE_SHARD_ID, sourceShardId)
                    .field(RELOCATING_NODE_ID, relocatingNodeId)
                    .endObject();
            return builder;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TieringStatus that = (TieringStatus) o;
        return Objects.equals(indexName, that.indexName) &&
                Objects.equals(status, that.status) &&
                Objects.equals(source, that.source) &&
                Objects.equals(target, that.target) &&
                (startTime == that.startTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(indexName, status, source, target, startTime);
    }
}
