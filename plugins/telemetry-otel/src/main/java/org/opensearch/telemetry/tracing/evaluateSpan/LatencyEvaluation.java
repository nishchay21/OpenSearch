/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.evaluateSpan;

import org.opensearch.telemetry.TelemetrySettings;
import org.opensearch.telemetry.tracing.Span;

/**
 * Class used for evaluation of span latency*
 */
public class LatencyEvaluation implements Evaluation {

    private final TelemetrySettings telemetrySettings;

    public LatencyEvaluation(TelemetrySettings telemetrySettings) {
        this.telemetrySettings = telemetrySettings;
    }

    /**
     * Method where evaluation logic resides*
     *
     * @param span span that needs to be evaluated
     */
    @Override
    public void evaluate(Span span) {
        long SpanLatency = getSpanLatency(span.getStartEpochMillis());
    }

    private long getSpanLatency(long startEpochNanos) {
        return System.currentTimeMillis() - startEpochNanos;
    }
}
