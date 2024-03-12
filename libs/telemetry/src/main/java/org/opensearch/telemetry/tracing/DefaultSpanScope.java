/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import org.opensearch.common.annotation.InternalApi;

import java.util.Objects;

/**
 * Default implementation for {@link SpanScope}
 *
 * @opensearch.internal
 */
@InternalApi
class DefaultSpanScope implements SpanScope {
    private final Span span;
    private final SpanScope previousSpanScope;
    private final Span beforeSpan;
    private static final ThreadLocal<SpanScope> spanScopeThreadLocal = new ThreadLocal<>();
    private final TracerContextStorage<String, Span> tracerContextStorage;
    private final TracerContextStorage<String, TraceSampleDecision> sampledTracerContextStorage;
    private TraceSampleDecision previousDecision;

    /**
     * Constructor
     * @param span span
     * @param previousSpanScope before attached span scope.
     */
    private DefaultSpanScope(
        Span span,
        final Span beforeSpan,
        SpanScope previousSpanScope,
        TracerContextStorage<String, Span> tracerContextStorage,
        TracerContextStorage<String, TraceSampleDecision> sampledTracerContextStorage
//        TraceSampleDecision previousDecision
    ) {
        this.span = Objects.requireNonNull(span);
        this.beforeSpan = beforeSpan;
        this.previousSpanScope = previousSpanScope;
        this.tracerContextStorage = tracerContextStorage;
        this.sampledTracerContextStorage = sampledTracerContextStorage;
//        this.previousDecision = previousDecision;
    }

    /**
     * Creates the SpanScope object.
     * @param span span.
     * @param tracerContextStorage tracer context storage.
     * @return SpanScope spanScope
     */
    public static SpanScope create(Span span, TracerContextStorage<String, Span> tracerContextStorage, TracerContextStorage<String, TraceSampleDecision> sampledTracerContextStorage) {
        final SpanScope beforeSpanScope = spanScopeThreadLocal.get();
        final Span beforeSpan = tracerContextStorage.get(TracerContextStorage.CURRENT_SPAN);
//        final TraceSampleDecision previousDecision = sampledTracerContextStorage.get(TracerContextStorage.SAMPLED);
        return new DefaultSpanScope(span, beforeSpan, beforeSpanScope, tracerContextStorage, sampledTracerContextStorage);
//        return new DefaultSpanScope(span, beforeSpan, beforeSpanScope, tracerContextStorage, sampledTracerContextStorage, previousDecision);
    }

    @Override
    public void close() {
        detach();
    }

    @Override
    public SpanScope attach() {
        spanScopeThreadLocal.set(this);
        tracerContextStorage.put(TracerContextStorage.CURRENT_SPAN, this.span);
        if (previousDecision == null || !this.span.getTraceId().equals(previousDecision.getTraceID())) {
            previousDecision = new TraceSampleDecision(this.span.getTraceId(), false);
        }
//        sampledTracerContextStorage.put(TracerContextStorage.SAMPLED, previousDecision);
        return this;
    }

    private void detach() {
        spanScopeThreadLocal.set(previousSpanScope);
        tracerContextStorage.put(TracerContextStorage.CURRENT_SPAN, beforeSpan);
//        sampledTracerContextStorage.put(TracerContextStorage.SAMPLED, previousDecision);
    }

    @Override
    public Span getSpan() {
        return span;
    }

    static SpanScope getCurrentSpanScope() {
        return spanScopeThreadLocal.get();
    }

}
