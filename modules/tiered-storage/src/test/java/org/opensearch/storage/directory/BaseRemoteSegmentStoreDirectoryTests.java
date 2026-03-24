/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.junit.After;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.UUIDs;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.index.engine.NRTReplicationEngineFactory;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardTestCase;
import org.opensearch.index.store.RemoteDirectory;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;
import org.opensearch.index.store.Store;
import org.opensearch.index.store.lockmanager.RemoteStoreMetadataLockManager;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static java.lang.Math.min;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.index.store.RemoteSegmentStoreDirectory.METADATA_FILES_TO_FETCH;
import static org.opensearch.test.RemoteStoreTestUtils.createMetadataFileBytes;
import static org.opensearch.test.RemoteStoreTestUtils.getDummyMetadata;

/**
 * Base class for setting up a dummy RemoteSegmentStoreDirectory to be used in unit tests
 * Already there in OpenSearch - https://github.com/opensearch-project/OpenSearch/blob/main/server/src/test/java/org/opensearch/index/store/BaseRemoteSegmentStoreDirectoryTests.java
 * But cannot import this file directly as it is in test folder inside server, to be able to import should be inside the root test folder
 * Added some more functions on top of the above for writable warm specific UTs
 */
public class BaseRemoteSegmentStoreDirectoryTests extends IndexShardTestCase {

    protected RemoteDirectory remoteDataDirectory;
    protected RemoteDirectory remoteMetadataDirectory;
    protected RemoteStoreMetadataLockManager mdLockManager;
    protected RemoteSegmentStoreDirectory remoteSegmentStoreDirectory;
    protected IndexShard indexShard;
    protected SegmentInfos segmentInfos;
    protected ThreadPool threadPool;
    protected static final int EIGHT_MB = 1024 * 1024 * 8;
    protected final static int FILE_CACHE_CAPACITY = 10000000;

    protected String metadataFilename = RemoteSegmentStoreDirectory.MetadataFilenameUtils.getMetadataFilename(12, 23, 34, 1, 1, "node-1");
    protected String metadataFilename2 = RemoteSegmentStoreDirectory.MetadataFilenameUtils.getMetadataFilename(12, 13, 34, 1, 1, "node-1");
    protected String metadataFilename3 = RemoteSegmentStoreDirectory.MetadataFilenameUtils.getMetadataFilename(10, 38, 34, 1, 1, "node-1");

    public void setupRemoteSegmentStoreDirectory() throws IOException {
        remoteDataDirectory = mock(RemoteDirectory.class);
        remoteMetadataDirectory = mock(RemoteDirectory.class);
        mdLockManager = mock(RemoteStoreMetadataLockManager.class);
        threadPool = mock(ThreadPool.class);

        Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, org.opensearch.Version.CURRENT)
                .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
                .build();
        ExecutorService executorService = OpenSearchExecutors.newDirectExecutorService();

        indexShard = newStartedShard(false, indexSettings, new NRTReplicationEngineFactory());
        remoteSegmentStoreDirectory = new RemoteSegmentStoreDirectory(
                remoteDataDirectory,
                remoteMetadataDirectory,
                mdLockManager,
                threadPool,
                indexShard.shardId()
        );
        try (Store store = indexShard.store()) {
            segmentInfos = store.readLastCommittedSegmentsInfo();
        }

        when(threadPool.executor(ThreadPool.Names.REMOTE_PURGE)).thenReturn(executorService);
        when(threadPool.executor(ThreadPool.Names.REMOTE_RECOVERY)).thenReturn(executorService);
        when(threadPool.executor(ThreadPool.Names.SAME)).thenReturn(executorService);
    }

    protected Map<String, Map<String, String>> populateMetadata() throws IOException {
        List<String> metadataFiles = new ArrayList<>();

        metadataFiles.add(metadataFilename);
        metadataFiles.add(metadataFilename2);
        metadataFiles.add(metadataFilename3);

        when(
                remoteMetadataDirectory.listFilesByPrefixInLexicographicOrder(
                        RemoteSegmentStoreDirectory.MetadataFilenameUtils.METADATA_PREFIX,
                        METADATA_FILES_TO_FETCH
                )
        ).thenReturn(List.of(metadataFilename));
        when(
                remoteMetadataDirectory.listFilesByPrefixInLexicographicOrder(
                        RemoteSegmentStoreDirectory.MetadataFilenameUtils.METADATA_PREFIX,
                        Integer.MAX_VALUE
                )
        ).thenReturn(metadataFiles);

        Map<String, Map<String, String>> metadataFilenameContentMapping = Map.of(
                metadataFilename,
                getDummyMetadata("_0", 1),
                metadataFilename2,
                getDummyMetadata("_0", 1),
                metadataFilename3,
                getDummyMetadata("_0", 1)
        );

        when(remoteMetadataDirectory.getBlobStream(metadataFilename)).thenAnswer(
                I -> createMetadataFileBytes(
                        metadataFilenameContentMapping.get(metadataFilename),
                        indexShard.getLatestReplicationCheckpoint(),
                        segmentInfos
                )
        );
        when(remoteMetadataDirectory.getBlobStream(metadataFilename2)).thenAnswer(
                I -> createMetadataFileBytes(
                        metadataFilenameContentMapping.get(metadataFilename2),
                        indexShard.getLatestReplicationCheckpoint(),
                        segmentInfos
                )
        );
        when(remoteMetadataDirectory.getBlobStream(metadataFilename3)).thenAnswer(
                I -> createMetadataFileBytes(
                        metadataFilenameContentMapping.get(metadataFilename3),
                        indexShard.getLatestReplicationCheckpoint(),
                        segmentInfos
                )
        );

        return metadataFilenameContentMapping;
    }

    /**
     * Updates metadata to include a specific file with given size.
     */
    protected void updateMetadataForFile(String fileName, long fileSize) throws IOException {
        Map<String, String> fileMetadata = getDummyMetadata(fileName, 10);
        fileMetadata.put(fileName, fileName + "::" + fileName + "__" + UUIDs.base64UUID() + "::" + OpenSearchTestCase.randomIntBetween(1000, 5000) + "::" + fileSize + "::10");

        when(remoteMetadataDirectory.getBlobStream(metadataFilename)).thenAnswer(
                I -> createMetadataFileBytes(
                        fileMetadata,
                        indexShard.getLatestReplicationCheckpoint(),
                        segmentInfos
                )
        );
    }

    @After
    public void tearDown() throws Exception {
        indexShard.close("test tearDown", true, false);
        super.tearDown();
    }

    protected void populateData() throws IOException {
        long fileLength = remoteSegmentStoreDirectory.fileLength("_0.si");
        IndexInput fullIndexInput = new ByteArrayIndexInput("full", new byte[(int) fileLength]);
        IndexInput blockIndexInput = fullIndexInput.slice("slice", 0, min(fileLength,EIGHT_MB));
        when(remoteDataDirectory.openBlockInput(anyString(), anyLong(), anyLong(), anyLong(), any())).thenReturn(blockIndexInput);
        when(remoteDataDirectory.openInput(anyString(), anyLong(), any())).thenReturn(fullIndexInput);
    }

    protected void syncLocalAndRemoteForFile(FSDirectory localDirectory, String fileName) throws IOException {
        try(
                IndexInput input = remoteSegmentStoreDirectory.openInput(fileName, IOContext.DEFAULT);
                IndexOutput output = localDirectory.createOutput(fileName, IOContext.DEFAULT)
        ) {
            byte[] buffer = new byte[8192]; // 8KB buffer size
            long len = input.length();
            long pos = 0;

            while (pos < len) {
                int size = (int)Math.min(buffer.length, len - pos);
                input.readBytes(buffer, 0, size);
                output.writeBytes(buffer, 0, size);
                pos += size;
            }
        }
    }

    public static byte[] createData() {
        final byte[] data = new byte[EIGHT_MB];
        data[0] = data[EIGHT_MB - 1] = 7;
        return data;
    }

}
