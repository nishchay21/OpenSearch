/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

/**
 * TraceSampleDecision CLASS
 */
public class TraceSampleDecision {

    private String TraceID;

    private boolean SamplingDecision;

    /**
     * Constructor.
     *
     * @param traceID trace
     * @param samplingDecision sample
     */
    public TraceSampleDecision(String traceID, boolean samplingDecision) {
        TraceID = traceID;
        SamplingDecision = samplingDecision;
    }

    /**
     * GET TRACE ID
     */
    public Boolean getSamplingDecision() {
        return SamplingDecision;
    }

    /**
     * GET TRACE ID
     * @param samplingDecision sample
     */
    public void setSamplingDecision(boolean samplingDecision) {
        SamplingDecision = samplingDecision;
    }

    /**
     * GET TRACE ID
     */
    public String getTraceID() {
        return TraceID;
    }

    /**
     * SET TRACE ID
     * @param traceID trace
     */
    public void setTraceID(String traceID) {
        TraceID = traceID;
    }
}
