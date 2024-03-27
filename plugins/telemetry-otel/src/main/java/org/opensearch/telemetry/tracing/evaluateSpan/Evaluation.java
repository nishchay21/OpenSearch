/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.evaluateSpan;

import org.opensearch.telemetry.tracing.Span;

/**
 * Interface for evaluation framework*
 */
public interface Evaluation {

    /**
     * Method where evaluation logic resides*
     * @param span span that needs to be evaluated
     */
    void evaluate(Span span);

}
