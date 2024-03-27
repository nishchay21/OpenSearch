/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import org.opensearch.common.concurrent.RefCountedReleasable;
import org.opensearch.telemetry.Telemetry;
import org.opensearch.telemetry.metrics.MetricsTelemetry;
import org.opensearch.telemetry.metrics.OTelMetricsTelemetry;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.opensearch.telemetry.tracing.evaluateSpan.SpanEvaluation;

/**
 * Otel implementation of Telemetry
 */
public class OTelTelemetry implements Telemetry {

    private final RefCountedReleasable<OpenTelemetrySdk> refCountedOpenTelemetry;
    private final SpanEvaluation spanEvaluation;

    /**
     * Creates Telemetry instance

     */
    /**
     * Creates Telemetry instance
     * @param refCountedOpenTelemetry open telemetry.
     */
    public OTelTelemetry(RefCountedReleasable<OpenTelemetrySdk> refCountedOpenTelemetry, SpanEvaluation spanEvaluation) {
        this.refCountedOpenTelemetry = refCountedOpenTelemetry;
        this.spanEvaluation = spanEvaluation;
    }

    @Override
    public TracingTelemetry getTracingTelemetry() {
        return new OTelTracingTelemetry<>(refCountedOpenTelemetry, refCountedOpenTelemetry.get().getSdkTracerProvider(), spanEvaluation);
    }

    @Override
    public MetricsTelemetry getMetricsTelemetry() {
        return new OTelMetricsTelemetry<>(refCountedOpenTelemetry, refCountedOpenTelemetry.get().getSdkMeterProvider());
    }
}
