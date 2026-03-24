package org.opensearch.storage.action.tiering.status.model;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.support.clustermanager.ClusterManagerNodeReadRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Migration status request for a single index.
 */
public class GetTieringStatusRequest extends ClusterManagerNodeReadRequest<GetTieringStatusRequest> {

    private String index;

    private Boolean isDetailedFlagEnabled;

    public Boolean getDetailedFlag() {
        return isDetailedFlagEnabled;
    }

    public void setDetailedFlagEnabled(Boolean detailedFlagEnabled) {
        isDetailedFlagEnabled = detailedFlagEnabled;
    }

    public GetTieringStatusRequest() {
    }

    public GetTieringStatusRequest(String index) {
        this.index = index;
        this.isDetailedFlagEnabled = false;
    }

    public GetTieringStatusRequest(String index,Boolean isDetailedFlagEnabled) {
        this.index = index;
        this.isDetailedFlagEnabled = isDetailedFlagEnabled;
    }

    public GetTieringStatusRequest(StreamInput in) throws IOException {
        super(in);
        index = in.readString();
        isDetailedFlagEnabled = in.readBoolean();
    }


    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (index == null) {
            validationException = addValidationError("index is missing", validationException);
        }
        return validationException;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(index);
        out.writeBoolean(isDetailedFlagEnabled);
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public String getIndex() {
        return index;
    }
}
