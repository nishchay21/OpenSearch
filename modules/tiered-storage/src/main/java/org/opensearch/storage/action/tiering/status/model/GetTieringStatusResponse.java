package org.opensearch.storage.action.tiering.status.model;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

public class GetTieringStatusResponse extends ActionResponse implements ToXContentObject {

    public TieringStatus getTieringStatus() {
        return tieringStatus;
    }

    private TieringStatus tieringStatus;

    public GetTieringStatusResponse(TieringStatus tieringStatus) {
        this.tieringStatus = tieringStatus;
    }

    public GetTieringStatusResponse(StreamInput in) throws IOException {
        tieringStatus = TieringStatus.readFrom(in);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        tieringStatus.toXContent(builder, params);
        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        tieringStatus.writeTo(out);
    }


}
