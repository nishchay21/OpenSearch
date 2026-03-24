package org.opensearch.storage.action.admin.tiering.status.transport;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.index.IndexModule;
import org.opensearch.storage.action.tiering.status.model.GetTieringStatusRequest;
import org.opensearch.storage.action.tiering.status.model.GetTieringStatusResponse;
import org.opensearch.storage.action.tiering.status.model.TieringStatus;
import org.opensearch.storage.action.tiering.status.transport.TransportGetTieringStatusAction;
import org.opensearch.storage.tiering.HotToWarmTieringService;
import org.opensearch.storage.tiering.WarmToHotTieringService;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.junit.Before;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class TransportGetTieringStatusTests extends OpenSearchTestCase {

    @Mock
    private TransportService transportService;
    @Mock
    private ClusterService clusterService;
    @Mock
    private HotToWarmTieringService hotToWarmTieringService;
    @Mock
    private WarmToHotTieringService warmToHotTieringService;
    @Mock
    private ThreadPool threadPool;
    @Mock
    private ActionFilters actionFilters;
    @Mock
    private IndexNameExpressionResolver indexNameExpressionResolver;
    @Mock
    private ClusterState clusterState;
    @Mock
    private Metadata metadata;
    @Mock
    private IndexMetadata indexMetadata;
    @Mock
    private ActionListener<GetTieringStatusResponse> listener;

    private TransportGetTieringStatusAction action;
    private GetTieringStatusRequest request;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);
        action = new TransportGetTieringStatusAction(
                transportService,
                clusterService,
                hotToWarmTieringService,
                warmToHotTieringService,
                threadPool,
                actionFilters,
                indexNameExpressionResolver
        );

        request = new GetTieringStatusRequest();
        request.setIndex("test-index");
        when(clusterService.state()).thenReturn(clusterState);
        when(clusterState.getMetadata()).thenReturn(metadata);
    }

    public void testExecutor() {
        assertEquals(ThreadPool.Names.SAME, action.executor());
    }

    public void testClusterManagerOperation_HotToWarmTiering() {
        // Arrange
        Index index = new Index("test-index", "uuid");
        Settings settings = Settings.builder()
                .put(IndexModule.INDEX_TIERING_STATE.getKey(), IndexModule.TieringState.HOT_TO_WARM.toString())
                .build();
        TieringStatus expectedStatus = new TieringStatus();

        when(indexNameExpressionResolver.concreteIndices(any(), any(), any())).thenReturn(new Index[]{index});
        when(metadata.index(index)).thenReturn(indexMetadata);
        when(indexMetadata.getSettings()).thenReturn(settings);
        when(hotToWarmTieringService.getTieringStatus("test-index",false)).thenReturn(expectedStatus);

        // Act
        action.clusterManagerOperation(request, clusterState, listener);

        // Assert
        verify(listener).onResponse(any(GetTieringStatusResponse.class));
    }


    public void testClusterManagerOperation_WarmToHotTiering() {
        // Arrange
        Index index = new Index("test-index", "uuid");
        Settings settings = Settings.builder()
                .put(IndexModule.INDEX_TIERING_STATE.getKey(), IndexModule.TieringState.WARM_TO_HOT.toString())
                .build();
        TieringStatus expectedStatus = new TieringStatus();

        when(indexNameExpressionResolver.concreteIndices(any(), any(), any())).thenReturn(new Index[]{index});
        when(metadata.index(index)).thenReturn(indexMetadata);
        when(indexMetadata.getSettings()).thenReturn(settings);
        when(warmToHotTieringService.getTieringStatus("test-index",false)).thenReturn(expectedStatus);

        // Act
        action.clusterManagerOperation(request, clusterState, listener);

        // Assert
        verify(listener).onResponse(any(GetTieringStatusResponse.class));
    }


    public void testClusterManagerOperation_WithException() {
        // Arrange
        when(indexNameExpressionResolver.concreteIndices(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Test exception"));

        // Act
        action.clusterManagerOperation(request, clusterState, listener);

        // Assert
        verify(listener).onFailure(any(IllegalArgumentException.class));
    }


    public void testClusterManagerOperation_NullTieringState() {
        // Arrange
        Index index = new Index("test-index", "uuid");
        Settings settings = Settings.builder().build(); // No tiering state

        when(indexNameExpressionResolver.concreteIndices(any(), any(), any())).thenReturn(new Index[]{index});
        when(metadata.index(index)).thenReturn(indexMetadata);
        when(indexMetadata.getSettings()).thenReturn(settings);

        // Act
        action.clusterManagerOperation(request, clusterState, listener);
    }
}
