package org.opensearch.storage.action.tiering.status.model;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.support.clustermanager.ClusterManagerNodeReadRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Locale;

/**
 * Tiering status request for all indices .
 * OR
 * Tiering status request for indices for specific Tiering State :
 * eg. "_tier/all" - for all tiering indices
 * eg. "_tier/all?target=_warm" - for Hot to Warm Tiering
 * eg. "_tier/all?target=_hot" - for Warm to Hot Tiering
 */

public class ListTieringStatusRequest extends ClusterManagerNodeReadRequest<ListTieringStatusRequest> {

    private static final Logger log = LogManager.getLogger(ListTieringStatusRequest.class);
    private String targetTier;

    public String getTargetTier() {
        return targetTier;
    }

    public ListTieringStatusRequest(){
        this.targetTier = null;
    }

    public ListTieringStatusRequest(String targetTier) {
        if (targetTier != null) {
            this.targetTier = targetTier.toUpperCase(Locale.ROOT);
        } else {
            this.targetTier = null;
        }
    }

    public ListTieringStatusRequest(StreamInput in) throws IOException {
        super(in);
        targetTier = in.readOptionalString();
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(targetTier != null ? targetTier.toString() : null);
    }
}
