/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.noop;

import org.opensearch.common.annotation.InternalApi;
import org.opensearch.telemetry.tracing.ScopedSpan;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanContext;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;

import java.util.Collection;
import java.util.Map;

/**
 * No-op implementation of Tracer
 *
 * @opensearch.internal
 */
@InternalApi
public class NoopTracer implements Tracer {

    /**
     * No-op Tracer instance
     */
    public static final Tracer INSTANCE = new NoopTracer();

    private NoopTracer() {}

    @Override
    public Span startSpan(SpanCreationContext context) {
        return NoopSpan.INSTANCE;
    }

    @Override
    public SpanContext getCurrentSpan() {
        return new SpanContext(NoopSpan.INSTANCE);
    }

    @Override
    public ScopedSpan startScopedSpan(SpanCreationContext spanCreationContext) {
        return ScopedSpan.NO_OP;
    }

    @Override
    public SpanScope withSpanInScope(Span span) {
        return SpanScope.NO_OP;
    }

    /**
     * Creates the Span Scope for a current thread. It's mandatory to scope the span just after creation so that it will
     * automatically manage the attach /detach to the current thread.
     *
     * @param span   span to be scoped
     * @param sample sample
     * @return ScopedSpan
     */
    @Override
    public SpanScope withSpanInScope(Span span, String sample) {
        return null;
    }

    @Override
    public boolean isRecording() {
        return false;
    }

    @Override
    public void close() {

    }

    @Override
    public Span startSpan(SpanCreationContext spanCreationContext, Map<String, Collection<String>> header) {
        return NoopSpan.INSTANCE;
    }
}
