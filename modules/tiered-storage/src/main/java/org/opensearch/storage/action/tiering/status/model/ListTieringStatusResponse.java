package org.opensearch.storage.action.tiering.status.model;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListTieringStatusResponse extends ActionResponse {

    private List<TieringStatus> tieringStatusList;

    public List<TieringStatus> getTieringStatusList() {
        return tieringStatusList;
    }

    public ListTieringStatusResponse(List<TieringStatus> tieringStatusList) {
        this.tieringStatusList = tieringStatusList;
    }

    public ListTieringStatusResponse(StreamInput in) throws IOException {

        int size = in.readVInt();
        List<TieringStatus> builder = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            builder.add(TieringStatus.readFrom(in));
        }
        tieringStatusList = Collections.unmodifiableList(builder);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(tieringStatusList.size());
        for (TieringStatus tieringStatus : tieringStatusList) {
            tieringStatus.writeTo(out);
        }
    }


}
