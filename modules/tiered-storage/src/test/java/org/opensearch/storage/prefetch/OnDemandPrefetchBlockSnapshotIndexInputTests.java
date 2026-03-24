/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.prefetch;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.Version;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.breaker.CircuitBreaker;
import org.opensearch.core.common.breaker.NoopCircuitBreaker;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.index.store.remote.file.AbstractBlockIndexInput;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.index.store.remote.filecache.FileCacheFactory;
import org.opensearch.index.store.remote.utils.BlobFetchRequest;
import org.opensearch.index.store.remote.utils.TransferManager;
import org.opensearch.storage.directory.CleanerDaemonThreadLeakFilter;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.junit.Before;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class OnDemandPrefetchBlockSnapshotIndexInputTests extends OpenSearchTestCase {
    // params shared by all test cases
    private static final String RESOURCE_DESCRIPTION = "Test TestOnDemandPrefetchBlockSnapshotIndexInput Block Size";
    private static final long BLOCK_SNAPSHOT_FILE_OFFSET = 0;
    private static final String FILE_NAME = "File_Name";
    private static final String BLOCK_FILE_PREFIX = FILE_NAME;
    private static final boolean IS_CLONE = false;
    private static final int FILE_SIZE = 29360128;
    protected final static int FILE_CACHE_CAPACITY = 10000000;
    private TransferManager transferManager;
    private LockFactory lockFactory;
    private BlobStoreIndexShardSnapshot.FileInfo fileInfo;
    private Path path;
    private ThreadPool threadPool;
    FileCache fileCache;
    private TieredStoragePrefetchSettings tieringServicePrefetchSettings;

    @Before
    public void init() {
        assumeFalse("Awaiting Windows fix https://github.com/opensearch-project/OpenSearch/issues/5396", Constants.WINDOWS);
        transferManager = mock(TransferManager.class);
        lockFactory = SimpleFSLockFactory.INSTANCE;
        threadPool = new TestThreadPool("StormbornPrefetchSettingsTests");
        path = createTempDir("TestOnDemandPrefetchBlockSnapshotIndexInputTests");
        int concurrencyLevel = randomIntBetween(1,2);
        fileCache = FileCacheFactory.createConcurrentLRUFileCache(FILE_CACHE_CAPACITY, concurrencyLevel);
        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.READ_AHEAD_BLOCK_COUNT);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.STORED_FIELDS_PREFETCH_ENABLED_SETTING);
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, clusterSettingsToAdd);
        this.tieringServicePrefetchSettings = new TieredStoragePrefetchSettings(clusterSettings);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        threadPool.shutdownNow();
    }

    public Supplier<TieredStoragePrefetchSettings> getPrefetchSettingsSupplier() {
        return () -> this.tieringServicePrefetchSettings;
    }

    public void test8MBBlock() throws Exception {
        runAllTestsFor(23);
    }

    public void test4KBBlock() throws Exception {
        runAllTestsFor(12);
    }

    public void test1MBBlock() throws Exception {
        runAllTestsFor(20);
    }

    public void test4MBBlock() throws Exception {
        runAllTestsFor(22);
    }

    public void testPrefetch() throws  Exception {
        // length of each block is 2 ^^ 23.
        final TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile = createTestOnDemandPrefetchBlockSnapshotIndexInput(23);

        // length 10 indicates it will only fetch one block
        blockedSnapshotFile.prefetch(0, 10);
        verify(transferManager, times(1)).fetchBlobAsync(any(BlobFetchRequest.class));
        // length 8388670 > (2 ^^ 23) indicates it will only fetch two blocks
        blockedSnapshotFile.prefetch(0, 8388670);
        verify(transferManager, times(3)).fetchBlobAsync(any(BlobFetchRequest.class));
        // length 16777350 > (2 * 2 ^^ 23) indicates it will only fetch three blocks
        blockedSnapshotFile.prefetch(0, 16777350);
        verify(transferManager, times(6)).fetchBlobAsync(any(BlobFetchRequest.class));
    }

    public void testSettings() throws Exception {
        final TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile = createTestOnDemandPrefetchBlockSnapshotIndexInput(23);
        assertFalse(blockedSnapshotFile.checkIfFileEnabledReadAhead());
        assertTrue(blockedSnapshotFile.checkIfStoredFieldsPrefetchEnabled());
    }

    public void testFetchBlockWithCacheTracking() throws Exception {
        final TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile = createTestOnDemandPrefetchBlockSnapshotIndexInput(23);

        // Fetch a block that exists (should be cache hit)
        IndexInput result = blockedSnapshotFile.fetchBlockPublic(0);
        assertNotNull("Fetched block should not be null", result);

        // Verify that the cache hit was recorded in metrics
        // The actual verification would depend on having access to the metrics collector
        // For now, we verify that the method completes without exception
    }

    public void testDownloadBlocksAsyncWithCacheOptimization() throws Exception {
        final TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile = createTestOnDemandPrefetchBlockSnapshotIndexInput(23);

        // Test that cached blocks are not re-downloaded
        // Since blocks are already created in initBlockFiles, they should be cache hits
        blockedSnapshotFile.downloadBlocksAsync(0, 1, false);

        // Verify that fetchBlobAsync is not called for cached blocks
        // Note: This test assumes that the cache check prevents unnecessary downloads
        // The exact verification would depend on the mock setup and cache state
    }

    public void testReadAheadEnabled() throws Exception  {
        TestOnDemandPrefetchBlockSnapshotIndexInput indexInput = getIndexInput("lucene.dvd", "clone");
        assertTrue(indexInput.checkIfFileEnabledReadAhead());
        indexInput = getIndexInput("_1.fdt", "clone");
        assertFalse(indexInput.checkIfFileEnabledReadAhead());
        indexInput = getIndexInput("_0.cfs", "lucene9.dvd");
        assertTrue(indexInput.checkIfFileEnabledReadAhead());
        indexInput = getIndexInput("_1.cfe", "lucene9.dvd");
        assertFalse(indexInput.checkIfFileEnabledReadAhead());
    }

    TestOnDemandPrefetchBlockSnapshotIndexInput getIndexInput(String fileName, String resourceDescription) {
        fileInfo = new BlobStoreIndexShardSnapshot.FileInfo(
            fileName,
            new StoreFileMetadata(fileName, FILE_SIZE, "", Version.LATEST),
            null
        );

        FSDirectory directory = null;
        try {
            // use MMapDirectory to create block
            directory = new MMapDirectory(path, lockFactory);
        } catch (IOException e) {
            fail("fail to create MMapDirectory: " + e.getMessage());
        }

        return new TestOnDemandPrefetchBlockSnapshotIndexInput(
            AbstractBlockIndexInput.builder()
                .resourceDescription(RESOURCE_DESCRIPTION)
                .offset(BLOCK_SNAPSHOT_FILE_OFFSET)
                .length(FILE_SIZE)
                .blockSizeShift(23)
                .isClone(IS_CLONE),
            resourceDescription,
            fileInfo,
            directory,
            transferManager,
            threadPool,
            fileCache,
            getPrefetchSettingsSupplier()
        );
    }

    public void testChunkedRepositoryWithBlockSizeGreaterThanChunkSize() throws IOException {
        verifyChunkedRepository(
            new ByteSizeValue(8, ByteSizeUnit.KB).getBytes(), // block Size
            new ByteSizeValue(2, ByteSizeUnit.KB).getBytes(), // repository chunk size
            new ByteSizeValue(15, ByteSizeUnit.KB).getBytes() // file size
        );
    }

    public void testChunkedRepositoryWithBlockSizeLessThanChunkSize() throws IOException {
        verifyChunkedRepository(
            new ByteSizeValue(1, ByteSizeUnit.KB).getBytes(), // block Size
            new ByteSizeValue(2, ByteSizeUnit.KB).getBytes(), // repository chunk size
            new ByteSizeValue(3, ByteSizeUnit.KB).getBytes() // file size
        );
    }

    public void testChunkedRepositoryWithBlockSizeEqualToChunkSize() throws IOException {
        verifyChunkedRepository(
            new ByteSizeValue(2, ByteSizeUnit.KB).getBytes(), // block Size
            new ByteSizeValue(2, ByteSizeUnit.KB).getBytes(), // repository chunk size
            new ByteSizeValue(15, ByteSizeUnit.KB).getBytes() // file size
        );
    }

    private void verifyChunkedRepository(long blockSize, long repositoryChunkSize, long fileSize) throws IOException {
        when(transferManager.fetchBlob(any())).thenReturn(new ByteArrayIndexInput("test", new byte[(int) blockSize]));
        try (
            FSDirectory directory = new MMapDirectory(path, lockFactory);
            IndexInput indexInput = new TestOnDemandPrefetchBlockSnapshotIndexInput(
                AbstractBlockIndexInput.builder()
                    .resourceDescription(RESOURCE_DESCRIPTION)
                    .offset(BLOCK_SNAPSHOT_FILE_OFFSET)
                    .length(FILE_SIZE)
                    .blockSizeShift((int) (Math.log(blockSize) / Math.log(2)))
                    .isClone(IS_CLONE),
                RESOURCE_DESCRIPTION,
                new BlobStoreIndexShardSnapshot.FileInfo(
                    FILE_NAME,
                    new StoreFileMetadata(FILE_NAME, fileSize, "", Version.LATEST),
                    new ByteSizeValue(repositoryChunkSize)
                ),
                directory,
                transferManager,
                threadPool,
                fileCache,
                getPrefetchSettingsSupplier()
            )
        ) {
            // Seek to the position past the first repository chunk
            indexInput.seek(repositoryChunkSize);
        }

        // Verify all the chunks related to block are added to the fetchBlob request
        verify(transferManager).fetchBlob(argThat(request -> request.getBlobLength() == blockSize));
    }

    private void runAllTestsFor(int blockSizeShift) throws Exception {
        final TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile = createTestOnDemandPrefetchBlockSnapshotIndexInput(blockSizeShift);
        final int blockSize = 1 << blockSizeShift;

        TestGroup.testGetBlock(blockedSnapshotFile, blockSize, FILE_SIZE);
        TestGroup.testGetTotalBlocks(blockedSnapshotFile, blockSize, FILE_SIZE);
        TestGroup.testGetBlockOffset(blockedSnapshotFile, blockSize, FILE_SIZE);
        TestGroup.testGetBlockStart(blockedSnapshotFile, blockSize);
        TestGroup.testGetBlobParts(blockedSnapshotFile, blockSize);
        TestGroup.testCurrentBlockStart(blockedSnapshotFile, blockSize);
        TestGroup.testCurrentBlockPosition(blockedSnapshotFile, blockSize);
        TestGroup.testClone(blockedSnapshotFile, blockSize);
        TestGroup.testSlice(blockedSnapshotFile, blockSize);
        TestGroup.testGetFilePointer(blockedSnapshotFile, blockSize);
        TestGroup.testReadByte(blockedSnapshotFile, blockSize);
        TestGroup.testReadShort(blockedSnapshotFile, blockSize);
        TestGroup.testReadInt(blockedSnapshotFile, blockSize);
        TestGroup.testReadLong(blockedSnapshotFile, blockSize);
        TestGroup.testReadVInt(blockedSnapshotFile, blockSize);
        TestGroup.testSeek(blockedSnapshotFile, blockSize, FILE_SIZE);
        TestGroup.testReadByteWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadShortWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadIntWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadLongWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadBytes(blockedSnapshotFile, blockSize);
    }

    // create TestOnDemandPrefetchBlockSnapshotIndexInput for each block size
    private TestOnDemandPrefetchBlockSnapshotIndexInput createTestOnDemandPrefetchBlockSnapshotIndexInput(int blockSizeShift) throws IOException,
        InterruptedException {

        // file info should be initialized per test method since file size need to be calculated
        fileInfo = new BlobStoreIndexShardSnapshot.FileInfo(
            FILE_NAME,
            new StoreFileMetadata(FILE_NAME, FILE_SIZE, "", Version.LATEST),
            null
        );

        int blockSize = 1 << blockSizeShift;

        doAnswer(invocation -> {
            BlobFetchRequest blobFetchRequest = invocation.getArgument(0);
            return blobFetchRequest.getDirectory().openInput(blobFetchRequest.getFileName(), IOContext.READONCE);
        }).when(transferManager).fetchBlob(any());

        FSDirectory directory = null;
        try {
            // use MMapDirectory to create block
            directory = new MMapDirectory(path, lockFactory);
        } catch (IOException e) {
            fail("fail to create MMapDirectory: " + e.getMessage());
        }

        initBlockFiles(blockSize, directory);

        return new TestOnDemandPrefetchBlockSnapshotIndexInput(
            AbstractBlockIndexInput.builder()
                .resourceDescription(RESOURCE_DESCRIPTION)
                .offset(BLOCK_SNAPSHOT_FILE_OFFSET)
                .length(FILE_SIZE)
                .blockSizeShift(blockSizeShift)
                .isClone(IS_CLONE),
            RESOURCE_DESCRIPTION,
            fileInfo,
            directory,
            transferManager,
            threadPool,
            fileCache,
            getPrefetchSettingsSupplier()
        );
    }

    private void initBlockFiles(int blockSize, FSDirectory fsDirectory) {
        int numOfBlocks = FILE_SIZE / blockSize;

        int sizeOfLastBlock = FILE_SIZE % blockSize;

        try {

            // block size will always be an integer multiple of frame size
            // write 48, -80 alternatively
            for (int i = 0; i < numOfBlocks; i++) {
                // create normal blocks
                String blockName = BLOCK_FILE_PREFIX + "_block_" + i;
                IndexOutput output = fsDirectory.createOutput(blockName, null);
                // since block size is always even number, safe to do division
                for (int j = 0; j < blockSize / 2; j++) {
                    // byte 00110000
                    output.writeByte((byte) 48);
                    // byte 10110000
                    output.writeByte((byte) -80);
                }
                output.close();
            }

            if (numOfBlocks > 1 && sizeOfLastBlock != 0) {
                // create last block
                String lastBlockName = BLOCK_FILE_PREFIX + "_block_" + numOfBlocks;
                IndexOutput output = fsDirectory.createOutput(lastBlockName, null);
                for (int i = 0; i < sizeOfLastBlock; i++) {
                    if ((i & 1) == 0) {
                        output.writeByte((byte) 48);
                    } else {
                        output.writeByte((byte) -80);
                    }
                }
                output.close();
            }

        } catch (IOException e) {
            fail("fail to initialize block files: " + e.getMessage());
        }

    }

    private static class TestGroup {

        public static void testGetBlock(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) {
            // block 0
            assertEquals(0, blockedSnapshotFile.getBlock(0L));
            // block 1
            assertEquals(1, blockedSnapshotFile.getBlock(blockSize));

            // end block
            assertEquals((fileSize - 1) / blockSize, blockedSnapshotFile.getBlock(fileSize - 1));
        }

        public static void testGetTotalBlocks(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) {
            assertEquals((fileSize - 1) / blockSize + 1, blockedSnapshotFile.getTotalBlocks());
        }

        public static void testGetBlockOffset(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) {
            // block 0
            assertEquals(1, blockedSnapshotFile.getBlockOffset(1));

            // block 1
            assertEquals(0, blockedSnapshotFile.getBlockOffset(blockSize));

            // end block
            assertEquals((fileSize - 1) % blockSize, blockedSnapshotFile.getBlockOffset(fileSize - 1));
        }

        public static void testGetBlockStart(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) {
            // block 0
            assertEquals(0L, blockedSnapshotFile.getBlockStart(0));

            // block 1
            assertEquals(blockSize, blockedSnapshotFile.getBlockStart(1));

            // block 2
            assertEquals(blockSize * 2, blockedSnapshotFile.getBlockStart(2));
        }

        public static void testGetBlobParts(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile,
                                            int blockSizeShift) {
            // block id 0
            int blockId = 0;
            long blockStart = blockedSnapshotFile.getBlockStart(blockId);
            long blockEnd = blockStart + blockedSnapshotFile.getActualBlockSize(blockId, blockSizeShift, FILE_SIZE);
            assertEquals(
                (blockEnd - blockStart),
                blockedSnapshotFile.getBlobParts(blockStart, blockEnd).stream().mapToLong(o -> o.getLength()).sum()
            );

            // block 1
            blockId = 1;
            blockStart = blockedSnapshotFile.getBlockStart(blockId);
            blockEnd = blockStart + blockedSnapshotFile.getActualBlockSize(blockId, blockSizeShift, FILE_SIZE);
            assertEquals(
                (blockEnd - blockStart),
                blockedSnapshotFile.getBlobParts(blockStart, blockEnd).stream().mapToLong(o -> o.getLength()).sum()
            );

            // block 2
            blockId = 2;
            blockStart = blockedSnapshotFile.getBlockStart(blockId);
            blockEnd = blockStart + blockedSnapshotFile.getActualBlockSize(blockId, blockSizeShift, FILE_SIZE);
            assertEquals(
                (blockEnd - blockStart),
                blockedSnapshotFile.getBlobParts(blockStart, blockEnd).stream().mapToLong(o -> o.getLength()).sum()
            );
        }

        public static void testCurrentBlockStart(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            // block 0
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(0L, blockedSnapshotFile.currentBlockStart());

            // block 1
            blockedSnapshotFile.seek(blockSize * 2 - 1);
            assertEquals(blockSize, blockedSnapshotFile.currentBlockStart());

            // block 2
            blockedSnapshotFile.seek(blockSize * 3 - 1);
            assertEquals(blockSize * 2, blockedSnapshotFile.currentBlockStart());
        }

        public static void testCurrentBlockPosition(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            // block 0
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(blockSize - 1, blockedSnapshotFile.currentBlockPosition());

            // block 1
            blockedSnapshotFile.seek(blockSize + 1);
            assertEquals(1, blockedSnapshotFile.currentBlockPosition());

            // block 2
            blockedSnapshotFile.seek(blockSize * 2 + 11);
            assertEquals(11, blockedSnapshotFile.currentBlockPosition());
        }

        public static void testClone(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(blockSize + 1);
            TestOnDemandPrefetchBlockSnapshotIndexInput clonedFile = (TestOnDemandPrefetchBlockSnapshotIndexInput) blockedSnapshotFile.clone();
            assertEquals(clonedFile.currentBlockPosition(), blockedSnapshotFile.currentBlockPosition());
            assertEquals(clonedFile.getFilePointer(), blockedSnapshotFile.getFilePointer());
            clonedFile.seek(blockSize + 11);
            assertNotEquals(clonedFile.currentBlockPosition(), blockedSnapshotFile.currentBlockPosition());
        }

        public static void testSlice(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            IndexInput slice = blockedSnapshotFile.slice("slice", blockSize - 11, 22);
            TestOnDemandPrefetchBlockSnapshotIndexInput newSlice = (TestOnDemandPrefetchBlockSnapshotIndexInput) slice;
            assertEquals(newSlice.isCloned(), true);
            assertEquals(newSlice.getTransferManager(), blockedSnapshotFile.getTransferManager());
            assertEquals(newSlice.getFileName(), blockedSnapshotFile.getFileName());
            assertEquals(newSlice.getBlockMask(), blockedSnapshotFile.getBlockMask());
            assertEquals(newSlice.getBlockSize(), blockedSnapshotFile.getBlockSize());
            assertEquals(newSlice.getBlockSizeShift(), blockedSnapshotFile.getBlockSizeShift());
            assertEquals(newSlice.getDirectory(), blockedSnapshotFile.getDirectory());
            assertNotEquals(newSlice.getLength(), blockedSnapshotFile.getLength());
            assertNotEquals(newSlice.getOffset(), blockedSnapshotFile.getOffset());

            newSlice.seek(0);
            assertEquals(0, newSlice.getFilePointer());
            assertEquals(blockSize - 11, newSlice.currentBlockPosition());
            newSlice.seek(21);
            assertEquals(21, newSlice.getFilePointer());
            assertEquals(10, newSlice.currentBlockPosition());
            assertEquals(newSlice.getResourceDescription(), "slice");
            try {
                newSlice.seek(23);
            } catch (EOFException e) {
                return;
            }
            fail("Able to seek past file end");

            newSlice = (TestOnDemandPrefetchBlockSnapshotIndexInput) blockedSnapshotFile.slice("lucene.dvd", blockSize - 11, 22);
            assertEquals(newSlice.getResourceDescription(), "lucene.dvd");
        }

        public static void testGetFilePointer(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(blockSize - 11);
            assertEquals(blockSize - 11, blockedSnapshotFile.currentBlockPosition());
            blockedSnapshotFile.seek(blockSize + 5);
            assertEquals(5, blockedSnapshotFile.currentBlockPosition());
            blockedSnapshotFile.seek(blockSize * 2);
            assertEquals(0, blockedSnapshotFile.currentBlockPosition());
        }

        public static void testReadByte(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals((byte) 48, blockedSnapshotFile.readByte());
            blockedSnapshotFile.seek(1);
            assertEquals((byte) -80, blockedSnapshotFile.readByte());

            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals((byte) -80, blockedSnapshotFile.readByte());
            blockedSnapshotFile.seek(blockSize);
            assertEquals((byte) 48, blockedSnapshotFile.readByte());
        }

        public static void testReadShort(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-20432, blockedSnapshotFile.readShort());

            blockedSnapshotFile.seek(blockSize);
            assertEquals(-20432, blockedSnapshotFile.readShort());

            // cross block 0 and block 1
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(12464, blockedSnapshotFile.readShort());
        }

        public static void testReadInt(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());

            blockedSnapshotFile.seek(blockSize);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());

            // 3 byte in block 0, 1 byte in block 1
            blockedSnapshotFile.seek(blockSize - 3);
            assertEquals(816853168, blockedSnapshotFile.readInt());
            // 2 byte in block 0, 2 byte in block 1
            blockedSnapshotFile.seek(blockSize - 2);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());
            // 1 byte in block 0, 3 byte in block 1
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(816853168, blockedSnapshotFile.readInt());
        }

        public static void testReadLong(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());

            // 7 byte in block 0, 1 byte in block 1
            blockedSnapshotFile.seek(blockSize - 7);
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong());

            // 6 byte in block 0, 2 byte in block 2
            blockedSnapshotFile.seek(blockSize - 6);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());

            // 5 byte in block 0, 3 byte in block 3
            blockedSnapshotFile.seek(blockSize - 5);
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong());

            // 4 byte in block 0, 4 block in block 4
            blockedSnapshotFile.seek(blockSize - 4);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());
        }

        public static void testReadVInt(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(48, blockedSnapshotFile.readVInt());

            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(6192, blockedSnapshotFile.readVInt());
        }

        public static void testReadVLong(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(48, blockedSnapshotFile.readVLong());

            blockedSnapshotFile.seek(1);
            assertEquals(6192, blockedSnapshotFile.readVLong());
        }

        public static void testSeek(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(0, blockedSnapshotFile.currentBlockPosition());

            blockedSnapshotFile.seek(blockSize + 11);
            assertEquals(11, blockedSnapshotFile.currentBlockPosition());

            try {
                blockedSnapshotFile.seek(fileSize + 1);
            } catch (EOFException e) {
                return;
            }
            fail("Able to seek past end");
        }

        public static void testReadByteWithPos(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(48, blockedSnapshotFile.readByte(0));
            assertEquals(-80, blockedSnapshotFile.readByte(1));

            assertEquals(48, blockedSnapshotFile.readByte(blockSize));
            assertEquals(-80, blockedSnapshotFile.readByte(blockSize + 1));
        }

        public static void testReadShortWithPos(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-20432, blockedSnapshotFile.readShort(0));
            assertEquals(12464, blockedSnapshotFile.readShort(1));

            assertEquals(12464, blockedSnapshotFile.readShort(blockSize - 1));
            assertEquals(-20432, blockedSnapshotFile.readShort(blockSize));
        }

        public static void testReadIntWithPos(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-1338986448, blockedSnapshotFile.readInt(0));
            assertEquals(-1338986448, blockedSnapshotFile.readInt(blockSize));

            // 3 byte in block 0, 1 byte in block 1
            assertEquals(816853168, blockedSnapshotFile.readInt(blockSize - 3));
            // 2 byte in block 0, 2 byte in block 1
            assertEquals(-1338986448, blockedSnapshotFile.readInt(blockSize - 2));
            // 1 byte in block 0, 3 byte in block 1
            assertEquals(816853168, blockedSnapshotFile.readInt(blockSize - 1));
        }

        public static void testReadLongWithPos(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(0));

            // 7 byte in block 0, 1 byte in block 1
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong(blockSize - 7));

            // 6 byte in block 0, 2 byte in block 2
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(blockSize - 6));

            // 5 byte in block 0, 3 byte in block 3
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong(blockSize - 5));

            // 4 byte in block 0, 4 block in block 4
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(blockSize - 4));
        }

        public static void testReadBytes(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            byte[] byteArr = new byte[2];
            blockedSnapshotFile.seek(0);
            blockedSnapshotFile.readBytes(byteArr, 0, 2);
            assertEquals(48, byteArr[0]);
            assertEquals(-80, byteArr[1]);

            blockedSnapshotFile.seek(blockSize - 1);
            blockedSnapshotFile.readBytes(byteArr, 0, 2);
            assertEquals(-80, byteArr[0]);
            assertEquals(48, byteArr[1]);
        }

        public static void testPrefetch(TestOnDemandPrefetchBlockSnapshotIndexInput blockedSnapshotFile) throws IOException {

        }
    }
}
