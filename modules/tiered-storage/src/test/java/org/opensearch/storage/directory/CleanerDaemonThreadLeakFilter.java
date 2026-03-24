/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import com.carrotsearch.randomizedtesting.ThreadFilter;

import org.opensearch.index.store.remote.file.OnDemandBlockSnapshotIndexInput;

/**
 * The {@link java.lang.ref.Cleaner} instance used by {@link OnDemandBlockSnapshotIndexInput} creates
 * a daemon thread which is never stopped, nor do we have a handle to stop it. This filter
 * excludes that thread from the leak detection logic.
 *
 * Already there in OpenSearch - https://github.com/opensearch-project/OpenSearch/blob/main/server/src/test/java/org/opensearch/index/store/remote/file/CleanerDaemonThreadLeakFilter.java
 * But cannot import this file directly as it is in test folder inside server, to be able to import should be inside the root test folder
 */
public final class CleanerDaemonThreadLeakFilter implements ThreadFilter {
    @Override
    public boolean reject(Thread t) {
        return t.getName().startsWith(OnDemandBlockSnapshotIndexInput.CLEANER_THREAD_NAME_PREFIX);
    }
}
