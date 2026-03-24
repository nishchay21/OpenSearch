package org.opensearch.storage.action.tiering;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.support.clustermanager.AcknowledgedRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Objects;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Request to cancel an ongoing tiering operation for an index.
 * This request contains the index name and necessary parameters to safely
 * cancel migrations that may be stuck in RUNNING_SHARD_RELOCATION state.
 */
public class CancelTieringRequest extends AcknowledgedRequest<CancelTieringRequest> {

    private String index;

    /**
     * Default constructor for stream deserialization.
     */
    public CancelTieringRequest() {
        super();
    }

    /**
     * Constructs a request to cancel tiering for the specified index.
     *
     * @param index the name of the index for which to cancel tiering
     */
    public CancelTieringRequest(final String index) {
        super();
        this.index = index;
    }

    /**
     * Stream constructor for deserialization.
     *
     * @param in the stream input to read from
     * @throws IOException if an error occurs during deserialization
     */
    public CancelTieringRequest(StreamInput in) throws IOException {
        super(in);
        this.index = in.readString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(index);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (index == null || index.isBlank()) {
            validationException = addValidationError("Index name cannot be null or empty", validationException);
        }
        return validationException;
    }

    /**
     * Gets the index name for which to cancel tiering.
     *
     * @return the index name
     */
    public String getIndex() {
        return index;
    }

    /**
     * Sets the index name for which to cancel tiering.
     *
     * @param index the index name
     */
    public void setIndex(String index) {
        this.index = index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CancelTieringRequest that = (CancelTieringRequest) o;
        return Objects.equals(index, that.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index);
    }

    @Override
    public String toString() {
        return "CancelTieringRequest{" +
            "index='" + index + '\'' +
            '}';
    }
}