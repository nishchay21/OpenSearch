/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.opensearch.telemetry.TelemetryStorageService;

import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of {@link Span} using Otel span. It keeps a reference of OpenTelemetry Span and handles span
 * lifecycle management by delegating calls to it.
 */
class OTelSpan extends AbstractSpan {

    private final Span delegateSpan;

    private final TracerContextStorage<String, TraceSampleDecision> sampledTracerContextStorage;

    private final Map<String, Object> metadata;

    /**
     * Constructor
     * @param spanName span name
     * @param span the delegate span
     * @param parentSpan the parent span
     * @param sampledTracerContextStorage the context storage
     */
    public OTelSpan(String spanName, Span span, org.opensearch.telemetry.tracing.Span parentSpan, TracerContextStorage<String, TraceSampleDecision> sampledTracerContextStorage) {
        super(spanName, parentSpan);
        this.delegateSpan = span;
        this.sampledTracerContextStorage = sampledTracerContextStorage;
        this.metadata = new HashMap<>();
    }

    @Override
    public void endSpan() {
//      if (this.getSpanName().equals("dispatchedShardOperationOnReplica")) {
//        if (this.getSpanName().equals("bulkShardAction")) {
//        }
//        if (this.sampledTracerContextStorage != null ) {
//            TraceSampleDecision decision =  this.sampledTracerContextStorage.get(TracerContextStorage.SAMPLED);
//            if (this.getSpanName().equals("dispatchedShardOperationOnReplica")) {
////            if (this.getSpanName().equals("bulkShardAction")) {
//                if (decision != null) {
////                    System.out.println("DECISION ENDSPAN: " + decision.getSamplingDecision());
////                    System.out.println("TRACEID ENDSPAN: " + decision.getTraceID());
//                    decision.setTraceID(getTraceId());
//                    decision.setSamplingDecision(true);
//                    this.sampledTracerContextStorage.put(TracerContextStorage.SAMPLED, decision);
//                    this.addAttribute(TracerContextStorage.SAMPLED, true);
//                }
//            } else if (decision != null && this.getTraceId().equals(decision.getTraceID())) {
////                System.out.println("ENDSPAN Thread Name: "  + Thread.currentThread().getName());
////                System.out.println("ENDSPAN decision: " + decision.getSamplingDecision());
//                if (TelemetryStorageService.traceSampleStorage.containsKey(getTraceId()) && TelemetryStorageService.traceSampleStorage.get(getTraceId())) {
//                    this.addAttribute(TracerContextStorage.SAMPLED, true);
//                    TelemetryStorageService.traceSampleStorage.remove(getTraceId());
//                    decision.setSamplingDecision(true);
//                } else {
//                    this.addAttribute(TracerContextStorage.SAMPLED, decision.getSamplingDecision());
//                }
//            }
//        }
        System.out.println("Otel Span: " + Thread.currentThread().getName());
        System.out.println("Span Attributes: "  + this.getAttributes().toString());
        if(this.sampledTracerContextStorage != null) {
            TraceSampleDecision decision = this.sampledTracerContextStorage.get(TracerContextStorage.SAMPLED);
            System.out.println("otel decision: " + decision);
        }
         if (this.getSpanName().equals("dispatchedShardOperationOnReplica")) {
//       if (this.getSpanName().equals("bulkShardAction")) {
             addAttributeAndMarkParent();
         } else if (!this.getAttributes().containsKey(TracerContextStorage.SAMPLED)) {
                    if(this.sampledTracerContextStorage != null) {
                        TraceSampleDecision decision =  this.sampledTracerContextStorage.get(TracerContextStorage.SAMPLED);
                        System.out.println("otel: "  + decision);
                        if (decision != null)
                         System.out.println("OtelSpan: "  + decision.getTraceID() + " - "+ decision.getSamplingDecision());
                        if (decision != null && decision.getTraceID().equals(getTraceId()) && decision.getSamplingDecision()) {
                            System.out.println("OTEL SPAN: " + decision.getSamplingDecision() + "  " +decision.getTraceID());
                            addAttributeAndMarkParent();
                        }
                    }
//                    TelemetryStorageService.traceSampleStorage.remove(getTraceId());
        }

        assert sampledTracerContextStorage != null;
        System.out.println(" TraceId:" + this.getTraceId() + " Span ID: " + this.getSpanId() + " Span Name: " + this.getSpanName() + " Sampled Attribute: " + sampledTracerContextStorage.get(TracerContextStorage.SAMPLED) + " Attributes " + this.getAttributes().toString());
        delegateSpan.end();
    }

    private void addAttributeAndMarkParent() {
        this.addAttribute(TracerContextStorage.SAMPLED, true);
        org.opensearch.telemetry.tracing.Span current_parent = getParentSpan();
        while (current_parent != null) {
            System.out.println(current_parent.getSpanName() + " " + current_parent.getSpanId());
            current_parent.addAttribute(TracerContextStorage.SAMPLED, true);
            current_parent = current_parent.getParentSpan();
        }
    }

    /**
     * Ends the span
     *
     * @param Sampled Sampled state of the span
     */
    @Override
    public void endSpan(Boolean Sampled) {
    }

    @Override
    public void addAttribute(String key, String value) {
        metadata.put(key, value);
        delegateSpan.setAttribute(key, value);
    }

    @Override
    public void addAttribute(String key, Long value) {
        metadata.put(key, value);
        delegateSpan.setAttribute(key, value);
    }

    @Override
    public void addAttribute(String key, Double value) {
        metadata.put(key, value);
        delegateSpan.setAttribute(key, value);
    }

    @Override
    public void addAttribute(String key, Boolean value) {
        metadata.put(key, value);
        delegateSpan.setAttribute(key, value);
    }

    @Override
    public void setError(Exception exception) {
        if (exception != null) {
            delegateSpan.setStatus(StatusCode.ERROR, exception.getMessage());
        }
    }

    @Override
    public void addEvent(String event) {
        delegateSpan.addEvent(event);
    }

    @Override
    public String getTraceId() {
        return delegateSpan.getSpanContext().getTraceId();
    }

    @Override
    public String getSpanId() {
        return delegateSpan.getSpanContext().getSpanId();
    }

    io.opentelemetry.api.trace.Span getDelegateSpan() {
        return delegateSpan;
    }

    /**
     * Returns attribute.
     * @param key key
     * @return value
     */
    public Object getAttribute(String key) {
        return metadata.get(key);
    }

    /**
     * Returns the attributes as map.
     * @return returns the attributes map.
     */
    public Map<String, Object> getAttributes() {
        return metadata;
    }

}
