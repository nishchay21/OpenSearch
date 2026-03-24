package org.opensearch.storage.action.tiering.status;

import org.opensearch.action.ActionType;
import org.opensearch.storage.action.tiering.status.model.ListTieringStatusResponse;

/** Action type for retrieving tiering status of all indices in the cluster. */
public class ListTieringStatusAction extends ActionType<ListTieringStatusResponse> {

    public static final ListTieringStatusAction INSTANCE = new ListTieringStatusAction();

    public static final String NAME = "cluster:admin/_tier/all";

    private ListTieringStatusAction() { super(NAME, ListTieringStatusResponse::new); }
}
