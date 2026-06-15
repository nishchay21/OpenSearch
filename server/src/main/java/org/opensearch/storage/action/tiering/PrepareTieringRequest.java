/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.action.tiering;

import org.opensearch.action.support.broadcast.BroadcastRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.rest.action.admin.cluster.ClusterAdminTask;
import org.opensearch.tasks.Task;

import java.io.IOException;
import java.util.Map;

/**
 * Request for pre-tiering sync operations on DFA indices.
 * Targets a specific index to flush, refresh, and sync to remote store
 * before tiering proceeds.
 * <p>
 * Creates a {@link ClusterAdminTask} so the prepare operation is cancellable
 * via the {@code _tasks} API and auto-cancels after the configured timeout.
 *
 * @opensearch.internal
 */
public class PrepareTieringRequest extends BroadcastRequest<PrepareTieringRequest> {

    /**
     * Constructs a new PrepareTieringRequest for the specified indices.
     *
     * @param indices the indices to prepare for tiering
     */
    public PrepareTieringRequest(String... indices) {
        super(indices);
    }

    /**
     * Constructs a new PrepareTieringRequest from a stream.
     *
     * @param in the stream input
     * @throws IOException if an I/O error occurs
     */
    public PrepareTieringRequest(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
        return new ClusterAdminTask(id, type, action, parentTaskId, headers, timeout());
    }
}
