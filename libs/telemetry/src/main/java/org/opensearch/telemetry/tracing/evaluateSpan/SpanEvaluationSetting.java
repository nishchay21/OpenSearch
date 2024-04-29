/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.evaluateSpan;

/**
 * Span Evaluation setting*
 */
public class SpanEvaluationSetting {

    private String spanName;

    private String evaluationMetricName;

    private String evaluationType;

    private String value;

    /**
     * Constructor *
     * @param setting span evaluation setting
     */
    public SpanEvaluationSetting(String setting) {
        String[] settingArray = setting.split(".");
        int settingLength = settingArray.length;

        // Set the required fields
        this.setEvaluationType(settingArray[settingLength -1]);
        this.setEvaluationMetricName(settingArray[settingLength -2]);
    }

    /**
     * To get metric on which we need to evaluate*
     * @return String
     */
    public String getEvaluationMetricName() {
        return evaluationMetricName;
    }

    /**
     * To set metric on which we need to evaluate*
     * @param evaluationMetricName metric name to be evaluated*
     */
    public void setEvaluationMetricName(String evaluationMetricName) {
        this.evaluationMetricName = evaluationMetricName;
    }

    /**
     * To get type of evaluation*
     * @return String
     */
    public String getEvaluationType() {
        return evaluationType;
    }

    /**
     * To set type of setting added*
     * @param evaluationType *
     */
    public void setEvaluationType(String evaluationType) {
        this.evaluationType = evaluationType;
    }

    /**
     * To get setting value*
     * @return String
     */
    public String getValue() {
        return value;
    }

    /**
     * To get setting value*
     * @param value *
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * To get span name*
     * @return String
     */
    public String getSpanName() {
        return spanName;
    }

    /**
     * To set span name*
     * @param spanName *
     */
    public void setSpanName(String spanName) {
        this.spanName = spanName;
    }
}
