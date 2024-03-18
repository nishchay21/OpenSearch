/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import org.opensearch.common.annotation.InternalApi;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.util.concurrent.ThreadContextStatePropagator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Core's ThreadContext based TracerContextStorage implementation
 *
 * @opensearch.internal
 */
@InternalApi
public class SampledContextTracerStorage implements TracerContextStorage<String, TraceSampleDecision>, ThreadContextStatePropagator {

    private final ThreadContext threadContext;

    private final TracingTelemetry tracingTelemetry;

    public SampledContextTracerStorage(ThreadContext threadContext, TracingTelemetry tracingTelemetry) {
        this.threadContext = Objects.requireNonNull(threadContext);
        this.tracingTelemetry = Objects.requireNonNull(tracingTelemetry);
        this.threadContext.registerThreadContextStatePropagator(this);
    }

    @Override
    public TraceSampleDecision get(String key) {
        return getCurrentDecision(key);
    }

    @Override
    public void put(String key, TraceSampleDecision traceSampleDecision) {
        TraceSampleDecision currenRef = threadContext.getTransient(key);
        if (currenRef == null) {
            threadContext.putTransient(key, new TraceSampleDecision(traceSampleDecision.getTraceID(), traceSampleDecision.getSamplingDecision()));
        } else {
            currenRef.setTraceID(traceSampleDecision.getTraceID());
            currenRef.setSamplingDecision(traceSampleDecision.getSamplingDecision());
        }
    }

    @Override
    @SuppressWarnings("removal")
    public Map<String, Object> transients(Map<String, Object> source) {
        final Map<String, Object> transients = new HashMap<>();
        System.out.println("Transient Thread Name: " + Thread.currentThread().getName());
        System.out.println(source.toString());
        if (source.containsKey(SAMPLED)) {
            System.out.println("INSIDE TRANSIENT");
            transients.put(SAMPLED, source.get(SAMPLED));
        }
        System.out.println("Transient Final: " + transients.toString());
        return transients;
    }

    @Override
    public Map<String, Object> transients(Map<String, Object> source, boolean isSystemContext) {
        if (isSystemContext == true) {
            return Collections.emptyMap();
        } else {
            return transients(source);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public Map<String, String> headers(Map<String, Object> source) {
        final Map<String, String> headers = new HashMap<>();
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> headers(Map<String, Object> source, boolean isSystemContext) {
        return headers(source);
    }

    TraceSampleDecision getCurrentDecision(String key) {
        return threadContext.getTransient(key);
    }
}
