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

import java.util.LinkedList;
import java.util.List;

/**
 * Evaluation framework used by inferred sampler to evaluate a span*
 */
public class SpanEvaluation {

    private final List<Evaluation> evaluationList;
    private final TelemetrySettings telemetrySettings;

    /**
     * Constructor for class*
     */
    public SpanEvaluation(TelemetrySettings telemetrySettings) {
        evaluationList = new LinkedList<>();
        this.telemetrySettings = telemetrySettings;
        /*
         As of now we will have this setting as static. In later use cases we can see if we need
         this dynamic.
         */
        initializeEvaluators();
    }

    private void initializeEvaluators() {
        // registering latency evaluation
        LatencyEvaluation latencyEvaluation = new LatencyEvaluation(telemetrySettings);
        registerEvaluation(latencyEvaluation);
    }

    /**
     * The main method that calls evaluation of all registered evaluation*
     * @param span span that needs to be evaluated
     */
    public void performEvaluation(Span span) {
        for (Evaluation evaluation : evaluationList) {
            evaluation.evaluate(span);
        }
    }

    /**
     * Method used for registering an evaluation to the framework*
     * @param evaluation evaluation that needs to be registered
     */
    public void registerEvaluation(Evaluation evaluation) {
        evaluationList.add(evaluation);
    }
}
