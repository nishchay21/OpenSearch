/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.action.tiering;

import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexService;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardState;
import org.opensearch.index.translog.TranslogStats;
import org.opensearch.indices.IndicesService;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransportPrepareTieringAction} shard-level operations.
 * <p>
 * These tests verify the shard operation logic:
 * - Correct ordering of sync, flush, refresh, and remote store sync
 * - Primary permit acquisition before operations
 * - Timeout handling on permit acquisition
 * - Uncommitted ops verification after flush
 * - Permit release on failure
 * - Primary-only shard targeting
 */
@SuppressWarnings("unchecked")
public class TransportPrepareTieringActionTests extends OpenSearchTestCase {

    private IndexShard mockIndexShard;
    private IndicesService mockIndicesService;
    private IndexService mockIndexService;
    private Releasable mockPermit;
    private ShardRouting primaryShardRouting;
    private ShardId shardId;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockIndexShard = mock(IndexShard.class);
        mockIndicesService = mock(IndicesService.class);
        mockIndexService = mock(IndexService.class);
        mockPermit = mock(Releasable.class);

        shardId = new ShardId(new Index("test-index", "test-uuid"), 0);
        primaryShardRouting = TestShardRouting.newShardRouting(shardId, "node1", true, ShardRoutingState.STARTED);

        when(mockIndicesService.indexServiceSafe(shardId.getIndex())).thenReturn(mockIndexService);
        when(mockIndexService.getShard(0)).thenReturn(mockIndexShard);

        TranslogStats translogStats = mock(TranslogStats.class);
        when(translogStats.getUncommittedOperations()).thenReturn(0);
        when(mockIndexShard.translogStats()).thenReturn(translogStats);
    }

    /**
     * Helper to mock acquireAllPrimaryOperationsPermits to immediately call the listener with a permit.
     */
    private void mockPermitAcquisitionSuccess() {
        doAnswer(invocation -> {
            ActionListener<Releasable> listener = invocation.getArgument(0);
            listener.onResponse(mockPermit);
            return null;
        }).when(mockIndexShard).acquireAllPrimaryOperationsPermits(any(ActionListener.class), any(TimeValue.class));
    }

    /**
     * Helper to mock acquireAllPrimaryOperationsPermits to call the listener with an exception.
     */
    private void mockPermitAcquisitionFailure(Exception exception) {
        doAnswer(invocation -> {
            ActionListener<Releasable> listener = invocation.getArgument(0);
            listener.onFailure(exception);
            return null;
        }).when(mockIndexShard).acquireAllPrimaryOperationsPermits(any(ActionListener.class), any(TimeValue.class));
    }

    /**
     * Simulates the shard operation logic from TransportPrepareTieringAction.shardOperation.
     * We replicate the logic here since the actual method requires full transport infrastructure.
     */
    private void executeShardOperation(IndexShard indexShard, ShardRouting shardRouting) throws IOException {
        PlainActionFuture<Releasable> permitFuture = new PlainActionFuture<>();
        indexShard.acquireAllPrimaryOperationsPermits(permitFuture, TimeValue.timeValueSeconds(30));
        Releasable permit;
        try {
            permit = permitFuture.actionGet();
        } catch (Exception e) {
            throw new IOException("Failed to acquire primary operation permits for shard [" + shardRouting.shardId() + "]", e);
        }
        try {
            indexShard.sync();
            indexShard.flush(new FlushRequest().force(true).waitIfOngoing(true));
            indexShard.refresh("prepare_tiering");
            indexShard.waitForRemoteStoreSync();

            int uncommitted = indexShard.translogStats().getUncommittedOperations();
            if (uncommitted > 0) {
                throw new IOException(
                    "Shard [" + shardRouting.shardId() + "] still has " + uncommitted + " uncommitted translog ops after flush"
                );
            }
        } finally {
            permit.close();
        }
    }

    /**
     * Verifies that the shard operation calls sync, flush, refresh, and waitForRemoteStoreSync in order.
     */
    public void testShardOperation_SyncFlushRefreshAndWaitForRemoteSync() throws IOException {
        mockPermitAcquisitionSuccess();

        executeShardOperation(mockIndexShard, primaryShardRouting);

        InOrder inOrder = inOrder(mockIndexShard);
        inOrder.verify(mockIndexShard).sync();
        inOrder.verify(mockIndexShard).flush(any(FlushRequest.class));
        inOrder.verify(mockIndexShard).refresh("prepare_tiering");
        inOrder.verify(mockIndexShard).waitForRemoteStoreSync();
    }

    /**
     * Verifies that primary permits are acquired before sync/flush operations.
     */
    public void testShardOperation_AcquiresPrimaryPermitsBeforeOperations() throws IOException {
        mockPermitAcquisitionSuccess();

        executeShardOperation(mockIndexShard, primaryShardRouting);

        InOrder inOrder = inOrder(mockIndexShard);
        inOrder.verify(mockIndexShard).acquireAllPrimaryOperationsPermits(any(ActionListener.class), any(TimeValue.class));
        inOrder.verify(mockIndexShard).sync();
        inOrder.verify(mockIndexShard).flush(any(FlushRequest.class));
    }

    /**
     * Verifies that timeout on permit acquisition throws IOException.
     */
    public void testShardOperation_PermitTimeout_ThrowsIOException() {
        mockPermitAcquisitionFailure(new TimeoutException("Timed out waiting for permits"));

        IOException thrown = expectThrows(IOException.class, () -> executeShardOperation(mockIndexShard, primaryShardRouting));
        assertTrue(
            "Exception message should mention permit acquisition failure",
            thrown.getMessage().contains("Failed to acquire primary operation permits")
        );
        assertNotNull("Should have a cause", thrown.getCause());
    }

    /**
     * Verifies that uncommitted ops > 0 after flush throws IOException.
     */
    public void testShardOperation_UncommittedOpsAfterFlush_ThrowsIOException() throws IOException {
        mockPermitAcquisitionSuccess();

        TranslogStats translogStats = mock(TranslogStats.class);
        when(translogStats.getUncommittedOperations()).thenReturn(5);
        when(mockIndexShard.translogStats()).thenReturn(translogStats);

        IOException thrown = expectThrows(IOException.class, () -> executeShardOperation(mockIndexShard, primaryShardRouting));
        assertTrue(
            "Exception message should mention uncommitted ops",
            thrown.getMessage().contains("uncommitted translog ops after flush")
        );
        assertTrue("Exception message should include the count", thrown.getMessage().contains("5"));
        // Permit should still be released via finally block
        verify(mockPermit).close();
    }

    /**
     * Verifies that the permit is released even if flush/sync throws an exception.
     */
    public void testShardOperation_ReleasesPermitOnFailure() throws IOException {
        mockPermitAcquisitionSuccess();

        doThrow(new IOException("sync failed")).when(mockIndexShard).sync();

        expectThrows(IOException.class, () -> executeShardOperation(mockIndexShard, primaryShardRouting));

        // Verify permit was released despite the exception
        verify(mockPermit).close();
    }

    /**
     * Verifies that the shards() method only returns primary shards.
     * We test this by building a routing table with both primary and replica shards
     * and verifying the predicate filters correctly.
     */
    public void testShards_TargetsPrimariesOnly() {
        ShardId sid = new ShardId(new Index("test-index", "test-uuid"), 0);
        ShardRouting primary = TestShardRouting.newShardRouting(sid, "node1", true, ShardRoutingState.STARTED);
        ShardRouting replica = TestShardRouting.newShardRouting(sid, "node2", false, ShardRoutingState.STARTED);

        IndexShardRoutingTable shardRoutingTable = new IndexShardRoutingTable.Builder(sid).addShard(primary).addShard(replica).build();

        IndexRoutingTable indexRoutingTable = new IndexRoutingTable.Builder(sid.getIndex()).addIndexShard(shardRoutingTable).build();

        RoutingTable routingTable = RoutingTable.builder().add(indexRoutingTable).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).routingTable(routingTable).build();

        // Use the same predicate as TransportPrepareTieringAction.shards()
        var shardsIterator = clusterState.routingTable().allShardsSatisfyingPredicate(new String[] { "test-index" }, ShardRouting::primary);

        int count = 0;
        for (ShardRouting shard : shardsIterator) {
            assertTrue("Only primary shards should be returned", shard.primary());
            count++;
        }
        assertEquals("Should have exactly 1 primary shard", 1, count);
    }

    // ── Wire serde tests ──────────────────────────────────────────────────────

    /**
     * Verifies that PrepareTieringRequest round-trips correctly over the wire (writeTo → StreamInput).
     * This ensures the request is not corrupted when sent from cluster-manager to shard nodes.
     */
    public void testPrepareTieringRequest_SerializationRoundTrip() throws IOException {
        PrepareTieringRequest original = new PrepareTieringRequest("my-index");

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        PrepareTieringRequest deserialized = new PrepareTieringRequest(out.bytes().streamInput());

        // BroadcastRequest only wire-serializes indices; timeout is a local routing hint not sent over the wire.
        assertArrayEquals(original.indices(), deserialized.indices());
    }

    /**
     * Verifies round-trip with no indices (broadcast to all).
     */
    public void testPrepareTieringRequest_SerializationRoundTrip_NoIndices() throws IOException {
        PrepareTieringRequest original = new PrepareTieringRequest();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        PrepareTieringRequest deserialized = new PrepareTieringRequest(out.bytes().streamInput());

        assertArrayEquals(original.indices(), deserialized.indices());
    }

    // ── Async shard operation tests (deadlock fix) ────────────────────────────

    /**
     * Helper that replicates the async shard operation logic from
     * {@link TransportPrepareTieringAction#shardOperationAsync} for unit testing
     * without requiring full transport infrastructure.
     *
     * @param indexShard the (mock) index shard
     * @param shardRouting the shard routing
     * @param listener the action listener to complete
     * @param threadPool the thread pool for scheduling timeouts
     * @param mergeTimeout the timeout to wait for merges to drain
     */
    private void executeShardOperationAsync(
        IndexShard indexShard,
        ShardRouting shardRouting,
        ActionListener<Void> listener,
        ThreadPool threadPool,
        TimeValue mergeTimeout
    ) {
        // Fail fast if shard is not fully started
        if (indexShard.state() != IndexShardState.STARTED) {
            listener.onFailure(
                new IOException(
                    "Shard ["
                        + shardRouting.shardId()
                        + "] is not in STARTED state (current: "
                        + indexShard.state()
                        + "). Cannot prepare for tiering — will retry."
                )
            );
            return;
        }

        // Acquire permits (blocking via PlainActionFuture to match production code)
        Releasable permit;
        try {
            PlainActionFuture<Releasable> permitFuture = new PlainActionFuture<>();
            indexShard.acquireAllPrimaryOperationsPermits(permitFuture, TimeValue.timeValueSeconds(30));
            permit = permitFuture.actionGet();
        } catch (Exception e) {
            listener.onFailure(
                new IOException("Failed to acquire primary operation permits for shard [" + shardRouting.shardId() + "]", e)
            );
            return;
        }

        indexShard.freezeForTiering();

        long mergeTimeoutMillis = (long) (mergeTimeout.millis() * 0.8);
        TimeValue effectiveTimeout = TimeValue.timeValueMillis(mergeTimeoutMillis);
        AtomicBoolean completed = new AtomicBoolean(false);

        // Schedule timeout
        Scheduler.ScheduledCancellable timeout = threadPool.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                int activeMerges = indexShard.getActiveMergeCount();
                int pendingMerges = indexShard.getPendingMergeCount();
                permit.close();
                listener.onFailure(
                    new MergeDrainTimeoutException(shardRouting.shardId(), activeMerges, pendingMerges, mergeTimeout.toString())
                );
            }
        }, effectiveTimeout, ThreadPool.Names.GENERIC);

        // Non-blocking merge wait
        boolean alreadyDrained = indexShard.onMergesDrained(() -> {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel();
                try {
                    completeSyncAndFlushForTest(indexShard, shardRouting);
                    permit.close();
                    listener.onResponse(null);
                } catch (Exception e) {
                    permit.close();
                    listener.onFailure(e);
                }
            }
        });

        if (alreadyDrained && completed.compareAndSet(false, true)) {
            timeout.cancel();
            try {
                completeSyncAndFlushForTest(indexShard, shardRouting);
                permit.close();
                listener.onResponse(null);
            } catch (Exception e) {
                permit.close();
                listener.onFailure(e);
            }
        }
    }

    /**
     * Helper that replicates completeSyncAndFlush from TransportPrepareTieringAction.
     */
    private void completeSyncAndFlushForTest(IndexShard indexShard, ShardRouting shardRouting) throws IOException {
        indexShard.sync();
        indexShard.flush(new FlushRequest().force(true).waitIfOngoing(true));
        indexShard.refresh("prepare_tiering");
        indexShard.waitForRemoteStoreSync();

        int uncommitted = indexShard.translogStats().getUncommittedOperations();
        if (uncommitted > 0) {
            throw new IOException(
                "Shard [" + shardRouting.shardId() + "] still has " + uncommitted + " uncommitted translog ops after flush"
            );
        }
    }

    /**
     * Tests that when merges are already drained (onMergesDrained returns true),
     * the listener fires immediately with a successful response, and no timeout fires.
     */
    public void testShardOperationAsync_AlreadyDrained_CompletesImmediately() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(true);

            AtomicReference<Void> responseRef = new AtomicReference<>();
            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    responseRef.set(null);
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should have been called", latch.await(5, TimeUnit.SECONDS));
            assertNull("Should not have failed", failureRef.get());

            // Verify sync/flush/refresh/waitForRemoteStoreSync were all called (completeSyncAndFlush)
            verify(mockIndexShard).sync();
            verify(mockIndexShard).flush(any(FlushRequest.class));
            verify(mockIndexShard).refresh("prepare_tiering");
            verify(mockIndexShard).waitForRemoteStoreSync();

            // Permit should be released
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that when merges are not yet drained (onMergesDrained returns false),
     * the listener is registered and fires later when the drain callback is invoked.
     * Verifies that completeSyncAndFlush operations are performed after drain.
     */
    public void testShardOperationAsync_MergesDrainLater_ListenerFires() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            // Capture the Runnable passed to onMergesDrained
            ArgumentCaptor<Runnable> drainCallbackCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockIndexShard.onMergesDrained(drainCallbackCaptor.capture())).thenReturn(false);

            AtomicReference<Void> responseRef = new AtomicReference<>();
            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    responseRef.set(null);
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            // Listener should NOT have fired yet (merges not drained)
            assertFalse("Listener should not fire before drain callback", latch.await(100, TimeUnit.MILLISECONDS));

            // Now simulate merges completing — invoke the captured drain callback
            Runnable drainCallback = drainCallbackCaptor.getValue();
            assertNotNull("Drain callback should have been captured", drainCallback);
            drainCallback.run();

            // Listener should fire after drain callback
            assertTrue("Listener should fire after drain callback", latch.await(5, TimeUnit.SECONDS));
            assertNull("Should not have failed", failureRef.get());

            // Verify completeSyncAndFlush operations were called
            verify(mockIndexShard).sync();
            verify(mockIndexShard).flush(any(FlushRequest.class));
            verify(mockIndexShard).refresh("prepare_tiering");
            verify(mockIndexShard).waitForRemoteStoreSync();

            // Permit should be released
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests the timeout path: when merges never drain, the scheduled timeout fires
     * and the listener receives a MergeDrainTimeoutException with correct merge counts.
     */
    public void testShardOperationAsync_Timeout_FiresMergeDrainTimeoutException() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            // onMergesDrained returns false and the callback is never invoked (simulates stuck merges)
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(false);

            // Mock merge counts for the timeout diagnostic message
            when(mockIndexShard.getActiveMergeCount()).thenReturn(3);
            when(mockIndexShard.getPendingMergeCount()).thenReturn(2);

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            // Use a very short timeout so the test doesn't wait long
            TimeValue shortTimeout = TimeValue.timeValueMillis(100);

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, shortTimeout);

            // Wait for the timeout to fire (effective timeout = 80ms for 100ms configured)
            assertTrue("Listener should fire after timeout", latch.await(5, TimeUnit.SECONDS));

            // Verify the failure is a MergeDrainTimeoutException
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue(
                "Should be MergeDrainTimeoutException, got: " + failureRef.get().getClass().getName(),
                failureRef.get() instanceof MergeDrainTimeoutException
            );

            MergeDrainTimeoutException timeoutEx = (MergeDrainTimeoutException) failureRef.get();
            assertEquals(3, timeoutEx.getActiveMerges());
            assertEquals(2, timeoutEx.getPendingMerges());
            assertEquals(shardId, timeoutEx.getShardId());
            assertTrue("Timeout message should contain timeout value", timeoutEx.getMessage().contains("100ms"));

            // Permit should be released on timeout path
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that if the shard is not in STARTED state, the listener immediately
     * receives an IOException mentioning the state issue.
     */
    public void testShardOperationAsync_ShardNotStarted_FailsFast() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            when(mockIndexShard.state()).thenReturn(IndexShardState.RECOVERING);

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire immediately", latch.await(5, TimeUnit.SECONDS));
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue("Should be IOException", failureRef.get() instanceof IOException);
            assertTrue("Message should mention not in STARTED state", failureRef.get().getMessage().contains("not in STARTED state"));

            // No permit acquisition should occur, no merge operations
            verify(mockIndexShard, never()).acquireAllPrimaryOperationsPermits(any(ActionListener.class), any(TimeValue.class));
            verify(mockIndexShard, never()).onMergesDrained(any(Runnable.class));
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that if permit acquisition fails, the listener immediately receives
     * an IOException wrapping the permit failure cause.
     */
    public void testShardOperationAsync_PermitAcquisitionFails() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            mockPermitAcquisitionFailure(new TimeoutException("Timed out waiting for permits"));

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire immediately on permit failure", latch.await(5, TimeUnit.SECONDS));
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue("Should be IOException", failureRef.get() instanceof IOException);
            assertTrue(
                "Message should mention permit acquisition failure",
                failureRef.get().getMessage().contains("Failed to acquire primary operation permits")
            );

            // Should not proceed to merge drain
            verify(mockIndexShard, never()).onMergesDrained(any(Runnable.class));
            verify(mockIndexShard, never()).freezeForTiering();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that the AtomicBoolean guard prevents double completion when both
     * the drain callback and timeout fire concurrently. Only one should succeed
     * in calling the listener.
     */
    public void testShardOperationAsync_AtomicBooleanPreventsDoubleCompletion() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            // Capture the drain callback so we can invoke it manually
            ArgumentCaptor<Runnable> drainCallbackCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockIndexShard.onMergesDrained(drainCallbackCaptor.capture())).thenReturn(false);

            // Mock merge counts for potential timeout path
            when(mockIndexShard.getActiveMergeCount()).thenReturn(1);
            when(mockIndexShard.getPendingMergeCount()).thenReturn(0);

            AtomicInteger completionCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    completionCount.incrementAndGet();
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    completionCount.incrementAndGet();
                    latch.countDown();
                }
            };

            // Use a very short timeout
            TimeValue shortTimeout = TimeValue.timeValueMillis(100);

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, shortTimeout);

            // Wait for the timeout to fire (effective = 80ms)
            assertTrue("Timeout should fire", latch.await(5, TimeUnit.SECONDS));

            // Now also invoke the drain callback — this simulates a race condition
            Runnable drainCallback = drainCallbackCaptor.getValue();
            assertNotNull("Drain callback should have been captured", drainCallback);
            drainCallback.run();

            // Give a small window for any potential second invocation
            Thread.sleep(50);

            // Only ONE invocation of the listener should have occurred
            assertEquals("Listener should be called exactly once despite race", 1, completionCount.get());
        } finally {
            terminate(testThreadPool);
        }
    }

    // ── Additional async shard operation tests ──────────────────────────────────

    /**
     * Tests that the permit is always closed on the success path (already drained).
     * Verifies the permit.close() call occurs after completeSyncAndFlush succeeds.
     */
    public void testShardOperationAsync_PermitClosedOnSuccessPath() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(true);

            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire", latch.await(5, TimeUnit.SECONDS));
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that the permit is always closed on the timeout path.
     * Verifies permit.close() is called even when a MergeDrainTimeoutException is thrown.
     */
    public void testShardOperationAsync_PermitClosedOnTimeoutPath() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(false);
            when(mockIndexShard.getActiveMergeCount()).thenReturn(1);
            when(mockIndexShard.getPendingMergeCount()).thenReturn(0);

            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueMillis(100));

            assertTrue("Listener should fire after timeout", latch.await(5, TimeUnit.SECONDS));
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that the permit is always closed on the drain callback path
     * when completeSyncAndFlush throws an exception.
     */
    public void testShardOperationAsync_PermitClosedOnDrainCallbackFailure() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            // Capture the drain callback
            ArgumentCaptor<Runnable> drainCallbackCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockIndexShard.onMergesDrained(drainCallbackCaptor.capture())).thenReturn(false);

            // Make sync() throw to exercise the exception path in the drain callback
            doThrow(new IOException("sync failed in drain callback")).when(mockIndexShard).sync();

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            // Invoke the drain callback
            Runnable drainCallback = drainCallbackCaptor.getValue();
            assertNotNull(drainCallback);
            drainCallback.run();

            assertTrue("Listener should fire", latch.await(5, TimeUnit.SECONDS));
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue("Should be IOException", failureRef.get() instanceof IOException);

            // Most importantly: permit must still be closed
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that the AtomicBoolean guard correctly prevents double-firing when
     * the drain callback arrives after the timeout has already fired.
     * Verifies the listener is called exactly once and the permit is closed exactly once.
     */
    public void testShardOperationAsync_DrainAfterTimeout_OnlyTimeoutFires() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            ArgumentCaptor<Runnable> drainCallbackCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockIndexShard.onMergesDrained(drainCallbackCaptor.capture())).thenReturn(false);
            when(mockIndexShard.getActiveMergeCount()).thenReturn(2);
            when(mockIndexShard.getPendingMergeCount()).thenReturn(1);

            AtomicInteger responseCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    responseCount.incrementAndGet();
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureCount.incrementAndGet();
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueMillis(100));

            // Wait for timeout to fire
            assertTrue("Timeout should fire", latch.await(5, TimeUnit.SECONDS));
            assertEquals("Should have exactly 1 failure (timeout)", 1, failureCount.get());
            assertEquals("Should have 0 responses", 0, responseCount.get());

            // Now invoke drain callback after timeout already completed — should be a no-op
            Runnable drainCallback = drainCallbackCaptor.getValue();
            assertNotNull(drainCallback);
            drainCallback.run();

            // Counts should not have changed
            assertEquals("Failure count should still be 1", 1, failureCount.get());
            assertEquals("Response count should still be 0", 0, responseCount.get());
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that when the shard state is CLOSED (not STARTED), the operation fails
     * immediately with an appropriate IOException and no further actions are taken.
     */
    public void testShardOperationAsync_ShardClosed_FailsFast() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            when(mockIndexShard.state()).thenReturn(IndexShardState.CLOSED);

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire immediately", latch.await(5, TimeUnit.SECONDS));
            assertNotNull("Should have a failure", failureRef.get());
            assertTrue("Should be IOException", failureRef.get() instanceof IOException);
            assertTrue("Message should mention not STARTED", failureRef.get().getMessage().contains("not in STARTED state"));
            assertTrue("Message should mention CLOSED", failureRef.get().getMessage().contains("CLOSED"));

            // No permit acquisition or merge drain should occur
            verify(mockIndexShard, never()).acquireAllPrimaryOperationsPermits(any(ActionListener.class), any(TimeValue.class));
            verify(mockIndexShard, never()).onMergesDrained(any(Runnable.class));
            verify(mockIndexShard, never()).freezeForTiering();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that when timeout fires, the exception passed to onFailure is specifically a
     * {@link MergeDrainTimeoutException} and that {@code getActiveMerges()}, {@code getPendingMerges()},
     * and {@code getTimeoutValue()} carry the correct values that were set on the mock.
     */
    public void testShardOperationAsync_TimeoutProduces_MergeDrainTimeoutException_WithCorrectFields() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);

            // onMergesDrained returns false and the callback is never invoked (simulates stuck merges)
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(false);

            // Set specific merge counts for verification
            when(mockIndexShard.getActiveMergeCount()).thenReturn(5);
            when(mockIndexShard.getPendingMergeCount()).thenReturn(3);

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            // Use a very short timeout so the test doesn't wait long
            TimeValue shortTimeout = TimeValue.timeValueMillis(50);

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, shortTimeout);

            // Wait for the timeout to fire
            assertTrue("Listener should fire after timeout", latch.await(5, TimeUnit.SECONDS));

            // Verify the failure is specifically a MergeDrainTimeoutException
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue(
                "Should be MergeDrainTimeoutException, got: " + failureRef.get().getClass().getName(),
                failureRef.get() instanceof MergeDrainTimeoutException
            );

            MergeDrainTimeoutException timeoutEx = (MergeDrainTimeoutException) failureRef.get();
            assertEquals("Active merges should be 5", 5, timeoutEx.getActiveMerges());
            assertEquals("Pending merges should be 3", 3, timeoutEx.getPendingMerges());
            assertEquals("Timeout value should match the configured timeout", "50ms", timeoutEx.getTimeoutValue());
            assertEquals("ShardId should match", shardId, timeoutEx.getShardId());

            // Permit should be released on timeout path
            verify(mockPermit).close();
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that when {@code acquireAllPrimaryOperationsPermits} calls listener.onFailure(exception),
     * the async {@code shardOperationAsync} calls its listener.onFailure with an IOException wrapping
     * that cause. No permit.close() should be called since permit was never acquired.
     */
    public void testShardOperationAsync_PermitAcquisitionFailure_AsyncPath() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            mockPermitAcquisitionFailure(new RuntimeException("permit denied"));

            AtomicReference<Exception> failureRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    failureRef.set(e);
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire immediately on permit failure", latch.await(5, TimeUnit.SECONDS));
            assertNotNull("Should have received a failure", failureRef.get());
            assertTrue("Should be IOException", failureRef.get() instanceof IOException);
            assertTrue(
                "Message should mention permit acquisition failure",
                failureRef.get().getMessage().contains("Failed to acquire primary operation permits")
            );
            assertNotNull("Should have a cause", failureRef.get().getCause());
            assertTrue("Cause should be the original RuntimeException", failureRef.get().getCause() instanceof RuntimeException);
            assertEquals("permit denied", failureRef.get().getCause().getMessage());

            // Permit was never acquired, so close() should never be called
            verify(mockPermit, never()).close();

            // Should not proceed to freeze or merge drain
            verify(mockIndexShard, never()).freezeForTiering();
            verify(mockIndexShard, never()).onMergesDrained(any(Runnable.class));
        } finally {
            terminate(testThreadPool);
        }
    }

    /**
     * Tests that {@code indexShard.freezeForTiering()} is called before
     * {@code indexShard.onMergesDrained()} is registered. Uses InOrder verification.
     */
    public void testShardOperationAsync_FreezeForTieringCalledBeforeDrain() throws Exception {
        TestThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            mockPermitAcquisitionSuccess();
            when(mockIndexShard.state()).thenReturn(IndexShardState.STARTED);
            when(mockIndexShard.onMergesDrained(any(Runnable.class))).thenReturn(true);

            CountDownLatch latch = new CountDownLatch(1);

            ActionListener<Void> listener = new ActionListener<Void>() {
                @Override
                public void onResponse(Void unused) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    latch.countDown();
                }
            };

            executeShardOperationAsync(mockIndexShard, primaryShardRouting, listener, testThreadPool, TimeValue.timeValueSeconds(30));

            assertTrue("Listener should fire", latch.await(5, TimeUnit.SECONDS));

            // Verify ordering: freezeForTiering MUST be called before onMergesDrained
            InOrder inOrder = inOrder(mockIndexShard);
            inOrder.verify(mockIndexShard).freezeForTiering();
            inOrder.verify(mockIndexShard).onMergesDrained(any(Runnable.class));
        } finally {
            terminate(testThreadPool);
        }
    }
}
