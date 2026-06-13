/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.action.tiering;

import org.opensearch.core.index.shard.ShardId;

import java.io.IOException;

/**
 * Exception thrown when a shard's merge drain times out during tiering preparation.
 * Carries structured data about the merge state for diagnostic purposes.
 * <p>
 * This exception type can be detected via {@code instanceof} on the coordinator
 * to produce targeted user-facing messages without string parsing.
 *
 * @opensearch.internal
 */
public class MergeDrainTimeoutException extends IOException {

    private final ShardId shardId;
    private final int activeMerges;
    private final int pendingMerges;
    private final String timeoutValue;

    public MergeDrainTimeoutException(ShardId shardId, int activeMerges, int pendingMerges, String timeoutValue) {
        super(buildMessage(shardId, activeMerges, pendingMerges, timeoutValue));
        this.shardId = shardId;
        this.activeMerges = activeMerges;
        this.pendingMerges = pendingMerges;
        this.timeoutValue = timeoutValue;
    }

    public ShardId getShardId() {
        return shardId;
    }

    public int getActiveMerges() {
        return activeMerges;
    }

    public int getPendingMerges() {
        return pendingMerges;
    }

    public String getTimeoutValue() {
        return timeoutValue;
    }

    private static String buildMessage(ShardId shardId, int activeMerges, int pendingMerges, String timeoutValue) {
        return "Shard ["
            + shardId
            + "] timed out waiting for merges to drain. "
            + "Active merges: "
            + activeMerges
            + ", pending merges: "
            + pendingMerges
            + ". "
            + "Consider increasing cluster.tiering.prepare_timeout (current: "
            + timeoutValue
            + ") "
            + "or wait for merges to complete before retrying.";
    }

    /**
     * Builds a user-facing summary from a MergeDrainTimeoutException.
     */
    public static String userFacingSummary(int totalFailedShards, MergeDrainTimeoutException sample) {
        return "Tiering preparation timed out: "
            + totalFailedShards
            + " shard(s) still have active merges. "
            + "Example: shard ["
            + sample.getShardId()
            + "] has "
            + sample.getActiveMerges()
            + " active and "
            + sample.getPendingMerges()
            + " pending merges. "
            + "Increase cluster.tiering.prepare_timeout (current: "
            + sample.getTimeoutValue()
            + ") or retry later.";
    }
}
