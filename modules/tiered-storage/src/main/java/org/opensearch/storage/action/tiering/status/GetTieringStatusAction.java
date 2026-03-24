package org.opensearch.storage.action.tiering.status;

import org.opensearch.action.ActionType;
import org.opensearch.storage.action.tiering.status.model.GetTieringStatusResponse;

public class GetTieringStatusAction extends ActionType<GetTieringStatusResponse> {

    public static final GetTieringStatusAction INSTANCE = new GetTieringStatusAction();
    public static final String NAME = "indices:admin/_tier/get";

    public GetTieringStatusAction() { super(NAME, GetTieringStatusResponse::new); }
}
