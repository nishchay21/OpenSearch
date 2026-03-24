package org.opensearch.storage.metrics;

import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.Histogram;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.metrics.tags.Tags;

/**
 * Migration Metrics to tracks successful migrations, rejections, and latency.
 *
 * @opensearch.internal
 */
public final class TierActionMetrics {

    private static final String LATENCY_METRIC_UNIT_MS = "ms";
    private static final String COUNTER_METRICS_UNIT = "1";

    public static final String NODE_ID = "node_id";
    public static final String INDEX_NAME = "index_name";
    public static final String TIER_TYPE = "tier_type";
    public static final String REJECTION_REASON = "rejection_reason";

    public final Counter successfulMigrations;
    public final Counter rejectionReason;
    public final Histogram migrationLatency;

    public TierActionMetrics(MetricsRegistry metricsRegistry) {
        successfulMigrations = metricsRegistry.createCounter(
            "migration_successful",
            "Counter for successful tier migrations",
            COUNTER_METRICS_UNIT
        );

        rejectionReason = metricsRegistry.createCounter(
            "migration_rejection_reason",
            "Counter for rejected tier migrations with their reasons",
            COUNTER_METRICS_UNIT
        );

        migrationLatency = metricsRegistry.createHistogram(
            "migration_latency",
            "Histogram for tracking end-to-end migration time",
            LATENCY_METRIC_UNIT_MS
        );
    }

    public void recordMigrationLatency(Double value, String nodeId, String indexName, String tierType) {
        Tags tags = createBaseTags(nodeId, indexName, tierType);
        migrationLatency.record(value, tags);
    }

    public void recordSuccessfulMigration(String nodeId, String indexName, String tierType) {
        Tags tags = createBaseTags(nodeId, indexName, tierType);
        successfulMigrations.add(1.0, tags);
    }

    public void recordRejectedMigration(String nodeId, String indexName, String tierType, String reason) {
        Tags tags = createBaseTags(nodeId, indexName, tierType)
            .addTag(REJECTION_REASON, reason);
        rejectionReason.add(1.0, tags);
    }

    private Tags createBaseTags(String nodeId, String indexName, String tierType) {
        return Tags.create()
            .addTag(NODE_ID, nodeId)
            .addTag(INDEX_NAME, indexName)
            .addTag(TIER_TYPE, tierType);
    }
}
