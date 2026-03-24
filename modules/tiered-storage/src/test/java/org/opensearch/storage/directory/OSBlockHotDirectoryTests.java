/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import org.opensearch.storage.common.BlockTransferManager;
import org.opensearch.storage.indexinput.BlockFetchRequest;
import org.opensearch.storage.indexinput.BlockIndexInput;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.UUIDs;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;
import org.opensearch.node.Node;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OSBlockHotDirectory functionality.
 */
@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class OSBlockHotDirectoryTests extends BaseRemoteSegmentStoreDirectoryTests {

    // Test file constants
    private static final String FILE_WITH_BLOCKS = "_1.cfe";
    private static final String FILE_BLOCK_0 = "_1.cfe_block_0";
    private static final String FILE_BLOCK_1 = "_1.cfe_block_1";
    private static final String REGULAR_FILE = "_2.cfe";
    private static final String RENAMED_FILE = "_3.cfe";
    private static final String RENAMED_BLOCK_0 = "_3.cfe_block_0";
    private static final String RENAMED_BLOCK_1 = "_3.cfe_block_1";
    private static final String SECOND_RENAMED_FILE = "_4.cfe";

    // File size constants
    private static final long BLOCK_0_SIZE = 8_388_608L;
    private static final long BLOCK_1_SIZE = 2_097_152L;
    private static final long REGULAR_FILE_SIZE = 2048L;
    private static final long LARGE_FILE_SIZE = 10_485_760L; // 10MB
    private static final int BUFFER_SIZE = 8192; // 8KB

    // Block position constants
    private static final long FIRST_BLOCK_START = 0L;
    private static final long SECOND_BLOCK_START = 8_388_608L;
    private static final long FIRST_BLOCK_SIZE = 8_388_608L;
    private static final long SECOND_BLOCK_SIZE = 2_097_152L;

    // Node configuration constants
    private static final String TEST_NODE_NAME = "test-node";
    private static final String TEST_INDEX_NAME = "test-index";
    private static final int TEST_NODE_PROCESSORS = 2;
    private static final int TEST_SHARD_COUNT = 1;
    private static final int TEST_REPLICA_COUNT = 0;

    private BlockTransferManager blockTransferManager;
    private OSBlockHotDirectory blockAwareDirectory;
    private IndexSettings indexSettings;
    private FSDirectory fsDirectory;

    @Before
    public void setup() throws IOException {
        setupRemoteSegmentStoreDirectory();
        populateMetadata();
        updateMetadataForFile(FILE_WITH_BLOCKS, LARGE_FILE_SIZE);
        remoteSegmentStoreDirectory.init();

        fsDirectory = FSDirectory.open(createTempDir());
        cleanDirectory(fsDirectory);

        Settings settings = Settings.builder()
                .put(Node.NODE_NAME_SETTING.getKey(), TEST_NODE_NAME)
                .put("path.home", createTempDir().toString())
                .put(OpenSearchExecutors.NODE_PROCESSORS_SETTING.getKey(), TEST_NODE_PROCESSORS)
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, TEST_SHARD_COUNT)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, TEST_REPLICA_COUNT)
                .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                .build();

        IndexMetadata indexMetadata = IndexMetadata.builder(TEST_INDEX_NAME)
                .settings(settings)
                .build();
        blockTransferManager = mock(BlockTransferManager.class);
        indexSettings = new IndexSettings(indexMetadata, settings);
        blockAwareDirectory = new OSBlockHotDirectory(
                fsDirectory,
                remoteSegmentStoreDirectory,
                indexSettings,
                blockTransferManager
        );
    }

    @After
    public void cleanup() throws Exception {
        if (fsDirectory != null) {
            cleanDirectory(fsDirectory);
            fsDirectory.close();
        }
        super.tearDown();
    }


    public void testListAllWithMixOfBlockFilesAndNonBlockFiles() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        fsDirectory.sync(List.of(FILE_BLOCK_0, REGULAR_FILE, FILE_BLOCK_1));

        String[] actualFileNames = blockAwareDirectory.listAll();
        String[] expectedFileNames = new String[] { FILE_WITH_BLOCKS, REGULAR_FILE };
        Arrays.sort(expectedFileNames);
        Arrays.sort(actualFileNames);
        assertArrayEquals(expectedFileNames, actualFileNames);
    }

    public void testFileLength() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);

        assertEquals(BLOCK_0_SIZE + BLOCK_1_SIZE, blockAwareDirectory.fileLength(FILE_WITH_BLOCKS));
        assertEquals(REGULAR_FILE_SIZE, blockAwareDirectory.fileLength(REGULAR_FILE));
    }

    public void testDeleteFile() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);


        // Test deleting blocked file
        assertTrue(fileExists(FILE_WITH_BLOCKS));
        verifyFileList(new String[] { FILE_BLOCK_0, FILE_BLOCK_1, REGULAR_FILE });
        blockAwareDirectory.deleteFile(FILE_WITH_BLOCKS);
        assertFalse(fileExists(FILE_WITH_BLOCKS));
        verifyFileList(new String[] { REGULAR_FILE });

        // Test deleting regular file
        assertTrue(fileExists(REGULAR_FILE));
        blockAwareDirectory.deleteFile(REGULAR_FILE);
        assertFalse(fileExists(REGULAR_FILE));
        verifyFileList(new String[] {});
        //TODO file is deleted from map

        assertThrows(NoSuchFileException.class, () -> {
            blockAwareDirectory.deleteFile("non_existent_file");
        });
    }

    public void testRename() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);

        // Test renaming blocked file
        assertTrue(fileExists(FILE_WITH_BLOCKS));
        verifyFileList(new String[] { FILE_BLOCK_0, FILE_BLOCK_1, REGULAR_FILE });
        blockAwareDirectory.rename(FILE_WITH_BLOCKS, RENAMED_FILE);
        assertFalse(fileExists(FILE_WITH_BLOCKS));
        assertTrue(fileExists(RENAMED_FILE));
        verifyFileList(new String[] { RENAMED_BLOCK_0, RENAMED_BLOCK_1, REGULAR_FILE });

        // Test renaming regular file
        assertTrue(fileExists(REGULAR_FILE));
        blockAwareDirectory.rename(REGULAR_FILE, SECOND_RENAMED_FILE);
        assertFalse(fileExists(REGULAR_FILE));
        assertTrue(fileExists(SECOND_RENAMED_FILE));
        verifyFileList(new String[] { RENAMED_BLOCK_0, RENAMED_BLOCK_1, SECOND_RENAMED_FILE });

        assertThrows(NoSuchFileException.class, () -> {
            blockAwareDirectory.rename("non_existent_file", "new_name");
        });
    }

    public void testCopyFileAsBlocksNotCalledForDifferentRemoteDirectoryInstance() throws IOException, InterruptedException {
        // Create a different RemoteSegmentStoreDirectory instance
        RemoteSegmentStoreDirectory differentRemoteDirectory = new RemoteSegmentStoreDirectory(
                remoteDataDirectory,
                remoteMetadataDirectory,
                mdLockManager,
                threadPool,
                indexShard.shardId()
        );
        IndexInput fullIndexInput = new ByteArrayIndexInput("full", new byte[(int) LARGE_FILE_SIZE]);
        when(remoteDataDirectory.openInput(anyString(), anyLong(), any())).thenReturn(fullIndexInput);
        blockAwareDirectory.copyFrom(differentRemoteDirectory, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);
        verify(blockTransferManager, never()).fetchBlocksAsync(any());
        assertFalse(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
    }


    public void testOpenInput() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);


        // Test regular file
        try (IndexInput input = blockAwareDirectory.openInput(REGULAR_FILE, IOContext.DEFAULT)) {
            assertNotNull(input);
            assertFalse(input instanceof BlockIndexInput);
        }

        // Test blocked file
        try (IndexInput input = blockAwareDirectory.openInput(FILE_WITH_BLOCKS, IOContext.DEFAULT)) {
            assertNotNull(input);
            assertTrue(input instanceof BlockIndexInput);
        }
    }

    public void testCopyFrom() throws IOException, InterruptedException {
        FSDirectory tmpDirectory = FSDirectory.open(createTempDir());
        createTestFile(tmpDirectory.getDirectory(), FILE_WITH_BLOCKS, LARGE_FILE_SIZE);

        blockAwareDirectory.copyFrom(tmpDirectory, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);

        ArgumentCaptor<List<BlockFetchRequest>> requestCaptor = ArgumentCaptor.forClass(List.class);
        verify(blockTransferManager, times(0)).fetchBlocksAsync(any());
        cleanDirectory(tmpDirectory);
    }

    public void testCopyFromWithRemoteSegmentDirectory() throws IOException, InterruptedException {
        FSDirectory tmpDirectory = FSDirectory.open(createTempDir());
        createTestFile(tmpDirectory.getDirectory(), FILE_WITH_BLOCKS, LARGE_FILE_SIZE);
        updateMetadataForFile(FILE_WITH_BLOCKS, LARGE_FILE_SIZE);
        blockAwareDirectory.copyFrom(new FilterDirectory(remoteSegmentStoreDirectory) {
        }, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);

        ArgumentCaptor<List<BlockFetchRequest>> requestCaptor = ArgumentCaptor.forClass(List.class);
        verify(blockTransferManager, times(1)).fetchBlocksAsync(requestCaptor.capture());

        List<BlockFetchRequest> requests = requestCaptor.getValue();
        assertEquals(2, requests.size());

        verifyBlockFetchRequest(requests.get(0), FIRST_BLOCK_START, FIRST_BLOCK_SIZE, FILE_BLOCK_0);
        verifyBlockFetchRequest(requests.get(1), SECOND_BLOCK_START, SECOND_BLOCK_SIZE, FILE_BLOCK_1);

        cleanDirectory(tmpDirectory);
    }

    private void verifyBlockFetchRequest(BlockFetchRequest request, long expectedStart,
                                         long expectedSize, String expectedBlockFileName) {
        assertEquals(expectedStart, request.getBlockStart());
        assertEquals(expectedSize, request.getBlockSize());
        assertEquals(expectedBlockFileName, request.getBlockFileName());
        assertEquals(expectedBlockFileName, request.getFilePath().getFileName().toString());
        assertEquals(FILE_WITH_BLOCKS, request.getFileName());
    }

    private void cleanDirectory(FSDirectory directory) throws IOException {
        Arrays.stream(directory.listAll()).forEach(fileName -> {
            try {
                directory.deleteFile(fileName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void verifyFileList(String[] expectedFiles) throws IOException {
        String[] actualFiles = fsDirectory.listAll();
        Arrays.sort(expectedFiles);
        Arrays.sort(actualFiles);
        assertArrayEquals(expectedFiles, actualFiles);
    }

    private boolean fileExists(String fileName) throws IOException {
        return Arrays.asList(blockAwareDirectory.listAll()).contains(fileName);
    }

    private void createTestFile(Path dir, String fileName, long sizeInBytes) throws IOException {
        Path filePath = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long written = 0;
            while (written < sizeInBytes) {
                int toWrite = (int) Math.min(buffer.length, sizeInBytes - written);
                out.write(buffer, 0, toWrite);
                written += toWrite;
            }
        }
    }

    // Additional test cases for comprehensive coverage

    public void testInitializeMetadata() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);

        OSBlockHotDirectory newDirectory = new OSBlockHotDirectory(
                fsDirectory,
                remoteSegmentStoreDirectory,
                indexSettings,
                blockTransferManager
        );

        assertTrue(newDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
        assertEquals(BLOCK_0_SIZE + BLOCK_1_SIZE, newDirectory.logicalFileLengthMap.get(FILE_WITH_BLOCKS).longValue());
        assertFalse(newDirectory.logicalFileLengthMap.containsKey(REGULAR_FILE));
    }

    public void testDeleteFileRemovesFromMap() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);

        assertTrue(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
        blockAwareDirectory.deleteFile(FILE_WITH_BLOCKS);
        assertFalse(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
    }

    public void testRenameUpdatesMap() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        long totalSize = BLOCK_0_SIZE + BLOCK_1_SIZE;
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, totalSize);

        blockAwareDirectory.rename(FILE_WITH_BLOCKS, RENAMED_FILE);

        assertFalse(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
        assertTrue(blockAwareDirectory.logicalFileLengthMap.containsKey(RENAMED_FILE));
        assertEquals(totalSize, blockAwareDirectory.logicalFileLengthMap.get(RENAMED_FILE).longValue());
    }

    public void testSyncWithBlockFiles() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);

        blockAwareDirectory.sync(Arrays.asList(FILE_WITH_BLOCKS, REGULAR_FILE));
        // Verify no exceptions thrown and method completes successfully
        assertTrue(true);

        // Should not throw exception for non-existent files in sync
        assertThrows(NoSuchFileException.class, () -> {
            blockAwareDirectory.sync(Arrays.asList(FILE_WITH_BLOCKS, REGULAR_FILE, "non_existent"));
        });
    }

    public void testGetPendingDeletions() throws IOException {
        Set<String> pendingDeletions = blockAwareDirectory.getPendingDeletions();
        assertNotNull(pendingDeletions);
    }

    public void testToString() {
        String result = blockAwareDirectory.toString();
        assertTrue(result.contains("OSBlockHotDirectory"));
    }

    public void testCopyFromNonRemoteDirectory() throws IOException, InterruptedException {
        FSDirectory tmpDirectory = FSDirectory.open(createTempDir());
        createTestFile(tmpDirectory.getDirectory(), FILE_WITH_BLOCKS, LARGE_FILE_SIZE);

        blockAwareDirectory.copyFrom(tmpDirectory, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);

        // Should delegate to underlying directory, not create blocks
        verify(blockTransferManager, never()).fetchBlocksAsync(any());
        assertFalse(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));

        cleanDirectory(tmpDirectory);
        tmpDirectory.close();
    }

    public void testCopyFromAddsToMap() throws IOException {
        updateMetadataForFile(FILE_WITH_BLOCKS, LARGE_FILE_SIZE);

        blockAwareDirectory.copyFrom(remoteSegmentStoreDirectory, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);

        assertTrue(blockAwareDirectory.logicalFileLengthMap.containsKey(FILE_WITH_BLOCKS));
        assertEquals(LARGE_FILE_SIZE, blockAwareDirectory.logicalFileLengthMap.get(FILE_WITH_BLOCKS).longValue());
    }

    public void testFileLengthFromMap() throws IOException {
        long expectedLength = BLOCK_0_SIZE + BLOCK_1_SIZE;
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, expectedLength);

        assertEquals(expectedLength, blockAwareDirectory.fileLength(FILE_WITH_BLOCKS));
    }

    public void testFileLengthFromUnderlying() throws IOException {
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);

        assertEquals(REGULAR_FILE_SIZE, blockAwareDirectory.fileLength(REGULAR_FILE));
    }

    public void testOpenInputBlockedFile() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);
        blockAwareDirectory.logicalFileLengthMap.put(FILE_WITH_BLOCKS, BLOCK_0_SIZE + BLOCK_1_SIZE);

        try (IndexInput input = blockAwareDirectory.openInput(FILE_WITH_BLOCKS, IOContext.DEFAULT)) {
            assertNotNull(input);
            assertTrue(input instanceof BlockIndexInput);
        }
    }

    public void testOpenInputRegularFile() throws IOException {
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);

        try (IndexInput input = blockAwareDirectory.openInput(REGULAR_FILE, IOContext.DEFAULT)) {
            assertNotNull(input);
            assertFalse(input instanceof BlockIndexInput);
        }
    }

    public void testConstructorWithNullBlockTransferManager() {
        assertThrows(IllegalArgumentException.class, () -> {
            new OSBlockHotDirectory(
                    fsDirectory,
                    remoteSegmentStoreDirectory,
                    indexSettings,
                    (BlockTransferManager) null
            );
        });
    }

    public void testConstructorWithInvalidDelegate() {
        Directory invalidDelegate = mock(Directory.class);
        assertThrows(IllegalArgumentException.class, () -> {
            new OSBlockHotDirectory(
                    invalidDelegate,
                    remoteSegmentStoreDirectory,
                    indexSettings,
                    blockTransferManager
            );
        });
    }

    public void testListAllWithOnlyRegularFiles() throws IOException {
        createTestFile(fsDirectory.getDirectory(), REGULAR_FILE, REGULAR_FILE_SIZE);
        createTestFile(fsDirectory.getDirectory(), SECOND_RENAMED_FILE, REGULAR_FILE_SIZE);

        String[] files = blockAwareDirectory.listAll();
        Arrays.sort(files);

        String[] expected = {REGULAR_FILE, SECOND_RENAMED_FILE};
        Arrays.sort(expected);

        assertArrayEquals(expected, files);
    }

    public void testListAllWithOnlyBlockFiles() throws IOException {
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), FILE_BLOCK_1, BLOCK_1_SIZE);

        String[] files = blockAwareDirectory.listAll();
        assertEquals(1, files.length);
        assertEquals(FILE_WITH_BLOCKS, files[0]);
    }


    public void testOpenInputNonExistentFile() {
        assertThrows(NoSuchFileException.class, () -> {
            blockAwareDirectory.openInput("non_existent_file", IOContext.DEFAULT);
        });
    }

    public void testCopyFromInterrupted() throws IOException, InterruptedException {
        updateMetadataForFile(FILE_WITH_BLOCKS, LARGE_FILE_SIZE);

        doThrow(new InterruptedException("Test interruption"))
                .when(blockTransferManager).fetchBlocksAsync(any());

        assertThrows(IOException.class, () -> {
            blockAwareDirectory.copyFrom(remoteSegmentStoreDirectory, FILE_WITH_BLOCKS, FILE_WITH_BLOCKS, IOContext.DEFAULT);
        });

        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted(); // Clear interrupt flag
    }

    public void testLogicalFileLengthMapConcurrency() throws IOException, InterruptedException {
        int numThreads = 10;
        int numOperations = 100;

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < numOperations; j++) {
                    String fileName = "file_" + threadId + "_" + j;
                    blockAwareDirectory.logicalFileLengthMap.put(fileName, (long) (threadId * numOperations + j));
                    blockAwareDirectory.logicalFileLengthMap.get(fileName);
                    blockAwareDirectory.logicalFileLengthMap.remove(fileName);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Verify map is in consistent state
        assertTrue(blockAwareDirectory.logicalFileLengthMap.isEmpty());
    }
}
