/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.evaluateSpan;

import org.opensearch.telemetry.TelemetrySettings;

import java.util.LinkedList;
import java.util.List;

/**
 * Builder for creation of SpanEvaluation*
 */
public class SpanEvaluationBuilder {

    private final List<Evaluation> evaluationList;
    private TelemetrySettings telemetrySettings;

    /**
     * Constructor*
     */
    public SpanEvaluationBuilder() {
        evaluationList = new LinkedList<>();
    }

    /**
     * Method used for registering an evaluation to the framework*
     * @param evaluation evaluation that needs to be registered
     * @return SpanEvaluationBuilder
     */
    public SpanEvaluationBuilder registerEvaluation(Evaluation evaluation) {
        this.evaluationList.add(evaluation);
        return this;
    }

    /**
     * *
     * @param telemetrySettings settings required for evaluation
     * @return SpanEvaluationBuilder
     */
    public SpanEvaluationBuilder withSettings(TelemetrySettings telemetrySettings) {
        this.telemetrySettings = telemetrySettings;
        return this;
    }

    /**
     * *
     * @return SpanEvaluation
     */
    public SpanEvaluation build() {
        return new SpanEvaluation(telemetrySettings, evaluationList);
    }
}
