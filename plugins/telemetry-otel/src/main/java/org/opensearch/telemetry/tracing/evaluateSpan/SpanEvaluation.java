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
     * *
     * @param telemetrySettings telemetry settings
     * @param evaluationList list of evaluators required on span
     */
    public SpanEvaluation(TelemetrySettings telemetrySettings, List<Evaluation> evaluationList) {
        this.telemetrySettings = telemetrySettings;
        this.evaluationList = new LinkedList<>();
    }

    /**

     * The main method that calls evaluation of all registered evaluation*
     * @param span span that needs to be evaluated
     * @return EvaluationResult
     */
    public EvaluationResult performEvaluation(Span span) {
        for (Evaluation evaluation : evaluationList) {
            EvaluationResult result = evaluation.evaluate(span);
            if (result.equals(EvaluationResult.EVALUATED_TRUE)) {
                return result;
            }
        }
        return EvaluationResult.EVALUATED_FALSE;
    }
}
