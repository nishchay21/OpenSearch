/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import org.opensearch.common.annotation.ExperimentalApi;

import java.io.Closeable;

/**
 * Interface for tracing telemetry providers
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface TracingTelemetry extends Closeable {

    /**
     * Creates span with provided arguments
     *
     * @param spanCreationContext span creation context.
     * @param parentSpan parent span.
     * @param tracerContextStorage trace storage
     * @return span instance
     */
    Span createSpan(
        SpanCreationContext spanCreationContext,
        Span parentSpan,
        TracerContextStorage<String, TraceSampleDecision> tracerContextStorage
    );

    /**
     * provides tracing context propagator
     * @return tracing context propagator instance
     */
    TracingContextPropagator getContextPropagator();

}
