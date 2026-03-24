package org.opensearch.storage.common.tiering;

import org.opensearch.Version;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.index.IndexModule;
import org.opensearch.storage.action.tiering.IndexTieringRequest;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.Before;

import java.util.HashMap;
import java.util.Map;

import static org.opensearch.storage.common.tiering.TieringUtils.TIERING_CUSTOM_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TieringUtilsTests extends OpenSearchTestCase {

    private IndexNameExpressionResolver indexNameExpressionResolver;
    private IndexMetadata indexMetadata;
    private Metadata metadata;
    private ClusterState clusterState;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        indexNameExpressionResolver = mock(IndexNameExpressionResolver.class);
        clusterState = mock(ClusterState.class);
        metadata = mock(Metadata.class);
    }


    public void testIsMigrationAllowed_AllowlistedIndices() {
        assertTrue("Data stream indices should be allowed",
            TieringUtils.isMigrationAllowed(".ds-my-data-stream-2023"));
        assertTrue("Regular indices should be allowed",
            TieringUtils.isMigrationAllowed("my-index"));
    }


    public void testIsMigrationAllowed_BlocklistedIndices() {
        assertFalse("System indices should not be allowed",
            TieringUtils.isMigrationAllowed(".system-index"));
        assertFalse("Kibana indices should not be allowed",
            TieringUtils.isMigrationAllowed(".kibana"));
    }


    public void testIsMigrationAllowed_InvalidInputs() {
        IllegalArgumentException e1 = expectThrows(IllegalArgumentException.class,
            () -> TieringUtils.isMigrationAllowed(null));
        assertEquals("index name cannot be null", e1.getMessage());

        IllegalArgumentException e2 = expectThrows(IllegalArgumentException.class,
            () -> TieringUtils.isMigrationAllowed(""));
        assertEquals("Index name cannot be empty", e2.getMessage());

        IllegalArgumentException e3 = expectThrows(IllegalArgumentException.class,
            () -> TieringUtils.isMigrationAllowed("  "));
        assertEquals("Index name cannot be empty", e3.getMessage());
    }


    public void testResolveRequestIndex_Success() throws Exception {
        Index expectedIndex = new Index("test-index", "uuid");
        when(indexNameExpressionResolver.concreteIndices(
            eq(clusterState),
            eq(IndicesOptions.STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED),
            eq("test-index")
        )).thenReturn(new Index[]{expectedIndex});

        Index resolvedIndex = TieringUtils.resolveRequestIndex(
            indexNameExpressionResolver,
            "test-index",
            clusterState
        );

        assertEquals(expectedIndex, resolvedIndex);
    }


    public void testResolveRequestIndex_MultipleIndices() {
        Index[] indices = new Index[]{
            new Index("index1", "uuid1"),
            new Index("index2", "uuid2")
        };

        when(indexNameExpressionResolver.concreteIndices(
            eq(clusterState),
            eq(IndicesOptions.STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED),
            eq("test*")
        )).thenReturn(indices);

        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> TieringUtils.resolveRequestIndex(
                indexNameExpressionResolver,
                "test*",
                clusterState
            )
        );

        assertTrue(e.getMessage().contains("Failed to resolve index: test*"));

        // If you want to verify the cause
        assertTrue(e.getCause().getMessage().contains("Expected single index but got 2 indices"));
    }


    public void testResolveRequestIndex_IndexNotFound() {
        when(indexNameExpressionResolver.concreteIndices(
            eq(clusterState),
            eq(IndicesOptions.STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED),
            eq("non-existent-index")
        )).thenThrow(new IllegalArgumentException("Index not found"));

        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> TieringUtils.resolveRequestIndex(
                indexNameExpressionResolver,
                "non-existent-index",
                clusterState
            )
        );

        assertTrue(e.getMessage().contains("Failed to resolve index"));
    }


    public void testGetTieringSource_HotToWarm() {
        IndexTieringRequest request = new IndexTieringRequest("WARM", "test-index");
        assertEquals(TieringUtils.H2W_TIERING_TYPE_KEY, TieringUtils.getTieringSourceType(request));
    }


    public void testGetTieringSource_WarmToHot() {
        IndexTieringRequest request = new IndexTieringRequest("HOT", "test-index");
        assertEquals(TieringUtils.W2H_TIERING_TYPE_KEY, TieringUtils.getTieringSourceType(request));
    }


    public void testGetTieringSource_NullRequest() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> TieringUtils.getTieringSourceType((IndexTieringRequest) null)
        );
        assertEquals("Tiering request cannot be null", e.getMessage());
    }

    public void testGetTierPairForTargetTier_HotToWarm() {
        String[] result = TieringUtils.getTierPairForTargetTier(IndexModule.TieringState.WARM);

        assertEquals(2, result.length);
        assertEquals("HOT", result[0]);
        assertEquals("WARM", result[1]);
    }

    public void testGetTierPairForTargetTier_WarmToHot() {
        String[] result = TieringUtils.getTierPairForTargetTier(IndexModule.TieringState.HOT);

        assertEquals(2, result.length);
        assertEquals("WARM", result[0]);
        assertEquals("HOT", result[1]);
    }

    public void testGetTieringStatefromIndexSettings() {
        // Test HOT_TO_WARM state
        Settings hotToWarmSettings = Settings.builder()
                .put(IndexModule.INDEX_TIERING_STATE.getKey(), "HOT_TO_WARM")
                .build();
        assertEquals(
                IndexModule.TieringState.HOT_TO_WARM,
                TieringUtils.getTieringStatefromIndexSettings(hotToWarmSettings)
        );

        // Test WARM state
        Settings warmSettings = Settings.builder()
                .put(IndexModule.INDEX_TIERING_STATE.getKey(), "WARM")
                .build();
        assertEquals(
                IndexModule.TieringState.HOT_TO_WARM,
                TieringUtils.getTieringStatefromIndexSettings(warmSettings)
        );

        // Test HOT state
        Settings hotSettings = Settings.builder()
                .put(IndexModule.INDEX_TIERING_STATE.getKey(), "HOT")
                .build();
        assertEquals(
                IndexModule.TieringState.WARM_TO_HOT,
                TieringUtils.getTieringStatefromIndexSettings(hotSettings)
        );
    }

    public void testGetTieringStartTime_NormalCase() {

        Index testIndex = new Index("test-index","_na_");
        long startTime = System.currentTimeMillis();
        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(TieringUtils.H2W_TIERING_START_TIME_KEY, startTime)
                .put(TieringUtils.W2H_TIERING_START_TIME_KEY, startTime)
                .put(IndexMetadata.SETTING_INDEX_UUID, testIndex.getUUID())
                .build();

        // 3. Create tiering custom data
        Map<String, String> tieringCustomData = new HashMap<>();
        tieringCustomData.put(TieringUtils.H2W_TIERING_START_TIME_KEY, String.valueOf(startTime));
        tieringCustomData.put(TieringUtils.W2H_TIERING_START_TIME_KEY, String.valueOf(startTime));

        indexMetadata = IndexMetadata.builder(testIndex.getName())
                .settings(indexSettings)
                .putCustom(TIERING_CUSTOM_KEY, tieringCustomData)
                .numberOfShards(1)
                .numberOfReplicas(1)
                .build();

        when(clusterState.getMetadata()).thenReturn(metadata);
        when(metadata.getIndexSafe(any())).thenReturn(indexMetadata);

        assertEquals(startTime,TieringUtils.getTieringStartTime(clusterState, testIndex, TieringUtils.H2W_TIERING_START_TIME_KEY));
        assertEquals(startTime,TieringUtils.getTieringStartTime(clusterState, testIndex, TieringUtils.W2H_TIERING_START_TIME_KEY));

    }
}
