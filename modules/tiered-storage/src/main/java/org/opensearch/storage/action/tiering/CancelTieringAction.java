package org.opensearch.storage.action.tiering;

import org.opensearch.action.ActionType;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;

/**
 * Action for cancelling ongoing tiering operations.
 * This action can be used to cancel both hot-to-warm and warm-to-hot migrations
 * when shards get stuck in RUNNING_SHARD_RELOCATION state.
 */
public class CancelTieringAction extends ActionType<AcknowledgedResponse> {

    public static final CancelTieringAction INSTANCE = new CancelTieringAction();
    public static final String NAME = "indices:admin/_tier/cancel";

    private CancelTieringAction() {
        super(NAME, AcknowledgedResponse::new);
    }
}