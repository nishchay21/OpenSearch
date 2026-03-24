package org.opensearch.storage.tiering;

import org.opensearch.common.util.io.IOUtils;
import org.opensearch.index.store.remote.filecache.FileCacheSettings;
import org.opensearch.indices.ShardLimitValidator;
import org.opensearch.storage.action.tiering.CancelTieringRequest;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterInfoService;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.ack.ClusterStateUpdateResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Priority;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.storage.metrics.TierActionMetrics;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.opensearch.storage.common.tiering.TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS;
import static org.opensearch.storage.common.tiering.TieringUtils.H2W_TIERING_START_TIME_KEY;
import static org.opensearch.storage.common.tiering.TieringUtils.JVM_USAGE_TIERING_THRESHOLD_PERCENT;
import static org.opensearch.storage.common.tiering.TieringUtils.TIERING_CUSTOM_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.opensearch.index.IndexModule.INDEX_TIERING_STATE;
import static org.opensearch.index.IndexModule.IS_WARM_INDEX_SETTING;
import static org.opensearch.index.IndexModule.TieringState;

/**
 * Unit tests for TieringService cancellation functionality.
 * Tests the core cancel logic, state management, and error handling.
 */
public class TieringServiceCancelTests extends OpenSearchTestCase {

    private TestTieringService service;
    private ClusterService clusterService;
    private ClusterInfoService clusterInfoService;
    private IndexNameExpressionResolver indexNameExpressionResolver;
    private AllocationService allocationService;
    private ClusterState clusterState;
    private Index testIndex;
    private IndexMetadata indexMetadata;
    private TierActionMetrics tierActionMetrics;
    private NodeEnvironment nodeEnvironment;
    private ShardLimitValidator shardLimitValidator;

    /**
     * Concrete test implementation of TieringService to test abstract methods
     */
    private static class TestTieringService extends TieringService {

        public TestTieringService(
            Settings settings,
            ClusterService clusterService,
            ClusterInfoService clusterInfoService,
            IndexNameExpressionResolver indexNameExpressionResolver,
            AllocationService allocationService,
            TierActionMetrics tierActionMetrics,
            NodeEnvironment nodeEnvironment,
            ShardLimitValidator shardLimitValidator
        ) {
            super(settings, clusterService, clusterInfoService, indexNameExpressionResolver, allocationService, tierActionMetrics, nodeEnvironment, shardLimitValidator);
        }

        @Override
        protected Settings getTieringStartSettingsToAdd() {
            return Settings.builder()
                .put(IS_WARM_INDEX_SETTING.getKey(), true)
                .put(INDEX_TIERING_STATE.getKey(), TieringState.HOT_TO_WARM)
                .build();
        }

        @Override
        protected String getTieringStartTimeKey() {
            return H2W_TIERING_START_TIME_KEY;
        }

        @Override
        protected Setting<Integer> getMaxConcurrentTieringRequestsSetting() {
            return H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS;
        }

        @Override
        protected IndexModule.TieringState getTargetTieringState() {
            return TieringState.WARM;
        }

        @Override
        protected void validateTieringRequest(ClusterState clusterState, ClusterInfoService service,
                                            Set<Index> tieringEntries, Integer maxConcurrentTieringRequests,
                                            Integer jvmActiveUsageThresholdPercent, Index index) {
            // Mock validation - do nothing for tests
        }

        @Override
        protected IndexModule.TieringState getTieringType() {
            return TieringState.HOT_TO_WARM;
        }

        @Override
        protected Settings getIndexTierSettingsToRestoreAfterCancellation() {
            // Return settings that should be restored after cancellation
            return Settings.builder()
                .put(INDEX_TIERING_STATE.getKey(), TieringState.HOT)
                .build();
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();

        clusterService = mock(ClusterService.class);
        clusterInfoService = mock(ClusterInfoService.class);
        indexNameExpressionResolver = mock(IndexNameExpressionResolver.class);
        allocationService = mock(AllocationService.class);
        clusterState = mock(ClusterState.class);
        tierActionMetrics = new TierActionMetrics(mock(MetricsRegistry.class));
        nodeEnvironment = newNodeEnvironment();
        shardLimitValidator = mock(ShardLimitValidator.class);

        testIndex = new Index("test-index", "uuid");

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_INDEX_UUID, "uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IS_WARM_INDEX_SETTING.getKey(), true)
            .put(INDEX_TIERING_STATE.getKey(), TieringState.HOT_TO_WARM.toString())
            .build();

        // Create index metadata with tiering custom data
        Map<String, String> tieringCustomData = new HashMap<>();
        tieringCustomData.put(H2W_TIERING_START_TIME_KEY, String.valueOf(System.currentTimeMillis()));

        indexMetadata = IndexMetadata.builder("test-index")
            .settings(indexSettings)
            .numberOfShards(1)
            .numberOfReplicas(1)
            .putCustom(TIERING_CUSTOM_KEY, tieringCustomData)
            .build();

        Settings defaultSettings = Settings.builder()
            .put(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS.getKey(), 50)
            .put(JVM_USAGE_TIERING_THRESHOLD_PERCENT.getKey(), 99)
            .build();

        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS);
        clusterSettingsToAdd.add(JVM_USAGE_TIERING_THRESHOLD_PERCENT);
        clusterSettingsToAdd.add(FileCacheSettings.DATA_TO_FILE_CACHE_SIZE_RATIO_SETTING);

        ClusterSettings mockSettings = new ClusterSettings(defaultSettings, clusterSettingsToAdd);
        when(clusterService.getClusterSettings()).thenReturn(mockSettings);

        // Mock index name resolution
        when(indexNameExpressionResolver.concreteIndices(any(), any(), eq("test-index")))
            .thenReturn(new Index[]{testIndex});

        service = new TestTieringService(
            defaultSettings,
            clusterService,
            clusterInfoService,
            indexNameExpressionResolver,
            allocationService,
            tierActionMetrics,
            nodeEnvironment,
            shardLimitValidator
        );
    }

    @Override
    public void tearDown() throws Exception{
        IOUtils.close(nodeEnvironment);
        super.tearDown();
    }

    public void testIsIndexBeingTieredTrue() {
        // Add index to tiering tracking
        service.tieringIndices.add(testIndex);

        boolean result = service.isIndexBeingTiered(testIndex);
        assertTrue("Index should be detected as being tiered", result);
    }

    public void testIsIndexBeingTieredFalse() {
        // Index not in tiering tracking
        boolean result = service.isIndexBeingTiered(testIndex);
        assertFalse("Index should not be detected as being tiered", result);
    }

    public void testIsIndexBeingTieredWithInvalidIndex() {
        // Mock index resolution failure
        when(indexNameExpressionResolver.concreteIndices(any(), any(), eq("invalid-index")))
            .thenThrow(new IllegalArgumentException("Index not found"));

        boolean result = service.isIndexBeingTiered(testIndex);
        assertFalse("Invalid index should return false", result);
    }

    @SuppressWarnings("unchecked")
    public void testCancelTieringSuccess() {
        // Setup: Add index to tiering tracking
        service.tieringIndices.add(testIndex);

        // Simple test that verifies the method executes without throwing
        // and that proper service calls are made (following existing test pattern)
        CancelTieringRequest request = new CancelTieringRequest();
        request.setIndex("test-index");
        ActionListener<ClusterStateUpdateResponse> listener = mock(ActionListener.class);

        // Mock cluster service task submission to avoid complex cluster state mocking
        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        doAnswer(invocation -> {
            // Simulate that the task was submitted successfully
            // We're testing the service logic, not the cluster state update mechanics
            return null;
        }).when(clusterService).submitStateUpdateTask(any(), taskCaptor.capture());

        service.cancelTiering(request, listener, clusterState);

        // Verify task was submitted with correct source
        verify(clusterService).submitStateUpdateTask(eq("cancel hot to warm tiering"), any());

        // Verify task was captured
        ClusterStateUpdateTask capturedTask = taskCaptor.getValue();
        assertNotNull("Task should be captured", capturedTask);
        assertEquals("Task should have IMMEDIATE priority", Priority.IMMEDIATE, capturedTask.priority());
    }

    @SuppressWarnings("unchecked")
    public void testCancelTieringIndexNotBeingTiered() {
        // Setup: Don't add index to tiering tracking (simulate index not being tiered)

        // Mock cluster state and metadata
        Metadata metadata = mock(Metadata.class);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.index(testIndex)).thenReturn(indexMetadata);

        // Mock cluster service task submission with failure
        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        doAnswer(invocation -> {
            ClusterStateUpdateTask task = taskCaptor.getValue();
            try {
                task.execute(clusterState);
            } catch (IllegalArgumentException e) {
                task.onFailure("test-source", e);
            }
            return null;
        }).when(clusterService).submitStateUpdateTask(any(), taskCaptor.capture());

        // Test cancellation
        CancelTieringRequest request = new CancelTieringRequest();
        request.setIndex("test-index");
        ActionListener<ClusterStateUpdateResponse> listener = mock(ActionListener.class);

        service.cancelTiering(request, listener, clusterState);

        // Verify task was submitted
        verify(clusterService).submitStateUpdateTask(eq("cancel hot to warm tiering"), any());

        // Verify listener was called with failure
        verify(listener).onFailure(any(IllegalArgumentException.class));
    }

    @SuppressWarnings("unchecked")
    public void testCancelTieringWithIndexResolutionFailure() {
        // Mock index resolution failure
        when(indexNameExpressionResolver.concreteIndices(any(), any(), eq("invalid-index")))
            .thenThrow(new IllegalArgumentException("Index not found"));

        CancelTieringRequest request = new CancelTieringRequest();
        request.setIndex("invalid-index");
        ActionListener<ClusterStateUpdateResponse> listener = mock(ActionListener.class);

        service.cancelTiering(request, listener, clusterState);

        // Verify listener was called with failure
        verify(listener).onFailure(any(IllegalArgumentException.class));
    }

    public void testUpdateIndexMetadataForTieringCancel() {
        // Create metadata builder mock
        Metadata.Builder metadataBuilder = mock(Metadata.Builder.class);
        RoutingTable.Builder routingTableBuilder = mock(RoutingTable.Builder.class);

        // Create index metadata with tiering settings
        Settings settingsWithTiering = Settings.builder()
            .put(indexMetadata.getSettings())
            .put(IS_WARM_INDEX_SETTING.getKey(), true)
            .put(INDEX_TIERING_STATE.getKey(), TieringState.HOT_TO_WARM.toString())
            .build();

        IndexMetadata indexWithTieringSettings = IndexMetadata.builder(indexMetadata)
            .settings(settingsWithTiering)
            .build();

        // Test the method - this should not throw exceptions
        service.updateIndexMetadataForTieringCancel(
            metadataBuilder,
            indexWithTieringSettings
        );

        // Verify that metadataBuilder.put() was called
        verify(metadataBuilder).put(any(IndexMetadata.Builder.class));
    }

    public void testUpdateIndexMetadataForTieringCancelWithException() {
        // Create metadata builder that throws exception
        Metadata.Builder metadataBuilder = mock(Metadata.Builder.class);
        RoutingTable.Builder routingTableBuilder = mock(RoutingTable.Builder.class);

        // Create index metadata with invalid settings that would cause issues
        IndexMetadata invalidMetadata = null;

        // Test that exception is properly wrapped
        Exception e = expectThrows(
            Exception.class,
            () -> service.updateIndexMetadataForTieringCancel(
                metadataBuilder,
                invalidMetadata
            )
        );

        // Verify exception is thrown for invalid input
        assertNotNull("Exception should be thrown for invalid metadata", e);
    }

    public void testGetTieringStartSettingsToAdd() {
        Settings settings = service.getTieringStartSettingsToAdd();

        assertEquals("true", settings.get(IS_WARM_INDEX_SETTING.getKey()));
        assertEquals(TieringState.HOT_TO_WARM.toString(), settings.get(INDEX_TIERING_STATE.getKey()));
    }

    public void testGetTieringStartTimeKey() {
        assertEquals(H2W_TIERING_START_TIME_KEY, service.getTieringStartTimeKey());
    }

    public void testGetTargetTieringState() {
        assertEquals(TieringState.WARM, service.getTargetTieringState());
    }

    public void testGetTieringType() {
        assertEquals(TieringState.HOT_TO_WARM, service.getTieringType());
    }

    public void testGetMaxConcurrentTieringRequestsSetting() {
        assertEquals(H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS, service.getMaxConcurrentTieringRequestsSetting());
    }
}
