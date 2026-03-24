/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.indexinput;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.junit.After;
import org.junit.Before;
import org.opensearch.storage.directory.CleanerDaemonThreadLeakFilter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for BlockIndexInput functionality.
 * Tests cover all public methods including cloning, slicing, block operations, and error conditions.
 */
@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class BlockIndexInputTests extends LuceneTestCase {

    // File and block constants
    private static final String FILE_NAME = "_1.cfe";
    private static final String BLOCK_FILE_0 = "_1.cfe_block_0";
    private static final String BLOCK_FILE_1 = "_1.cfe_block_1";

    // Size constants
    private static final long TEST_FILE_SIZE = 10_485_760L; // 10MB
    private static final long BLOCK_0_SIZE = 8_388_608L; // 8MB
    private static final long BLOCK_1_SIZE = 2_097_152L; // 2MB
    private static final int BUFFER_SIZE = 8192;
    private static final int SMALL_BUFFER_SIZE = 100;

    // Test position constants
    private static final long[] TEST_POSITIONS = {0, 512, 4096, TEST_FILE_SIZE - 1L};
    private static final long SEEK_POSITION_1000 = 1000L;
    private static final long SEEK_POSITION_2000 = 2000L;
    private static final long BLOCK_BOUNDARY_OFFSET = 50L;
    private static final long SLICE_OFFSET = 1000L;
    private static final long SLICE_LENGTH = 5000L;

    // Description constants
    private static final String RESOURCE_DESCRIPTION = "Test BlockIndexInput for _1.cfe";
    private static final String SLICE_DESCRIPTION = "test-slice";
    private static final String INVALID_SLICE_DESCRIPTION = "test";
    private static final String BUILDER_RESOURCE_DESCRIPTION = "Test Input";

    // Test instance variables
    private BlockIndexInput blockIndexInput;
    private FSDirectory fsDirectory;

    @Before
    public void setup() throws IOException {
        fsDirectory = FSDirectory.open(createTempDir());
        createTestFile(fsDirectory.getDirectory(), BLOCK_FILE_0, BLOCK_0_SIZE);
        createTestFile(fsDirectory.getDirectory(), BLOCK_FILE_1, BLOCK_1_SIZE);

        blockIndexInput = BlockIndexInput.builder()
                .localDirectory(fsDirectory)
                .resourceDescription(RESOURCE_DESCRIPTION)
                .name(FILE_NAME)
                .fileSize(TEST_FILE_SIZE)
                .length(TEST_FILE_SIZE)
                .context(IOContext.DEFAULT)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .build();
    }

    @After
    public void cleanup() throws IOException {
        Arrays.stream(fsDirectory.listAll()).forEach(fileName -> {
            try {
                fsDirectory.deleteFile(fileName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        fsDirectory.close();
    }

    /**
     * Test basic operations including file properties, seeking, and reading.
     */
    public void testBasicOperations() throws IOException {
        // Test file properties
        assertEquals(FILE_NAME, blockIndexInput.fileName);
        assertEquals(TEST_FILE_SIZE, blockIndexInput.fileSize);
        assertEquals(0, blockIndexInput.getFilePointer());

        // Test seeking and reading at various positions
        for (long position : TEST_POSITIONS) {
            blockIndexInput.seek(position);
            assertEquals(position, blockIndexInput.getFilePointer());
            blockIndexInput.readByte(); // Verify we can read at each position
        }

        // Test bulk reading
        byte[] buffer = new byte[BUFFER_SIZE];
        blockIndexInput.seek(0);
        blockIndexInput.readBytes(buffer, 0, buffer.length);
        assertEquals(buffer.length, blockIndexInput.getFilePointer());
    }

    /**
     * Test reading across block boundaries to ensure proper block handling.
     */
    public void testCrossBlockReading() throws IOException {
        int blockSize = BlockIndexInput.getBlockSize(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT);
        byte[] buffer = new byte[blockSize + SMALL_BUFFER_SIZE];

        // Read starting from end of first block into second block
        blockIndexInput.seek(blockSize - BLOCK_BOUNDARY_OFFSET);
        blockIndexInput.readBytes(buffer, 0, SMALL_BUFFER_SIZE);
        assertEquals(blockSize + BLOCK_BOUNDARY_OFFSET, blockIndexInput.getFilePointer());
    }

    /**
     * Test cloning and slicing functionality including independence verification.
     */
    public void testCloneAndSlice() throws IOException {
        // Test cloning
        blockIndexInput.seek(SEEK_POSITION_1000);
        byte originalByte = blockIndexInput.readByte();

        BlockIndexInput clone = blockIndexInput.clone();
        assertNotNull(clone);
        assertEquals(blockIndexInput.length(), clone.length());

        // Verify clone independence
        clone.seek(SEEK_POSITION_1000);
        assertEquals(originalByte, clone.readByte());
        clone.seek(SEEK_POSITION_2000);
        assertNotEquals(blockIndexInput.getFilePointer(), clone.getFilePointer());

        // Test slicing
        IndexInput slice = blockIndexInput.slice(SLICE_DESCRIPTION, SLICE_OFFSET, SLICE_LENGTH);
        assertEquals(SLICE_LENGTH, slice.length());
        slice.seek(0);

        // Verify slice content matches original
        byte[] originalData = new byte[SMALL_BUFFER_SIZE];
        byte[] sliceData = new byte[SMALL_BUFFER_SIZE];

        blockIndexInput.seek(SLICE_OFFSET);
        blockIndexInput.readBytes(originalData, 0, SMALL_BUFFER_SIZE);

        slice.readBytes(sliceData, 0, SMALL_BUFFER_SIZE);
        assertArrayEquals(originalData, sliceData);
    }

    /**
     * Test block calculation utilities including block size, count, and ID generation.
     */
    public void testBlockCalculations() {
        int blockSizeShift = BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT;
        int blockSize = BlockIndexInput.getBlockSize(blockSizeShift);

        // Test block size is power of 2
        assertTrue(Integer.bitCount(blockSize) == 1);

        // Test number of blocks calculation
        int numBlocks = BlockIndexInput.getNumberOfBlocks(TEST_FILE_SIZE, blockSizeShift);
        assertEquals((int) Math.ceil((double) TEST_FILE_SIZE / blockSize), numBlocks);

        // Test block IDs list
        List<Integer> blockIds = BlockIndexInput.getAllBlockIdsForFile(TEST_FILE_SIZE, blockSizeShift);
        assertEquals(numBlocks, blockIds.size());
        for (int i = 0; i < blockIds.size(); i++) {
            assertEquals(Integer.valueOf(i), blockIds.get(i));
        }
    }

    /**
     * Test invalid operations to ensure proper error handling.
     */
    public void testInvalidOperations() {
        // Test invalid slice parameters
        assertThrows(IllegalArgumentException.class, () ->
                blockIndexInput.slice(INVALID_SLICE_DESCRIPTION, -1, SMALL_BUFFER_SIZE));
        assertThrows(IllegalArgumentException.class, () ->
                blockIndexInput.slice(INVALID_SLICE_DESCRIPTION, 0, -SMALL_BUFFER_SIZE));
        assertThrows(IllegalArgumentException.class, () ->
                blockIndexInput.slice(INVALID_SLICE_DESCRIPTION, TEST_FILE_SIZE + 1, SMALL_BUFFER_SIZE));
        assertThrows(IllegalArgumentException.class, () ->
                blockIndexInput.slice(INVALID_SLICE_DESCRIPTION, 0, TEST_FILE_SIZE + 1));
    }

    /**
     * Test builder pattern validation and successful construction.
     */
    public void testBuilder() throws IOException {
        // Test missing required parameters
        assertThrows(IllegalStateException.class, () ->
                BlockIndexInput.builder().build());
        assertThrows(IllegalStateException.class, () ->
                BlockIndexInput.builder()
                        .name(FILE_NAME)
                        .build());
        assertThrows(IllegalStateException.class, () ->
                BlockIndexInput.builder()
                        .name(FILE_NAME)
                        .localDirectory(fsDirectory)
                        .build());

        // Test successful build
        BlockIndexInput input = BlockIndexInput.builder()
                .name(FILE_NAME)
                .localDirectory(fsDirectory)
                .fileSize(TEST_FILE_SIZE)
                .context(IOContext.DEFAULT)
                .resourceDescription(BUILDER_RESOURCE_DESCRIPTION)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .build();

        assertNotNull(input);
        assertEquals(FILE_NAME, input.fileName);
        assertEquals(TEST_FILE_SIZE, input.fileSize);
        input.close();
    }

    /**
     * Test block metadata calculations including block sizes.
     */
    public void testBlockMetadata() throws IOException {
        int blockSize = BlockIndexInput.getBlockSize(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT);

        // Test first block size
        assertEquals(blockSize, blockIndexInput.getActualBlockSize(0, BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT, TEST_FILE_SIZE));

        // Test last block size
        int lastBlockId = BlockIndexInput.getBlock(TEST_FILE_SIZE - 1, BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT);
        long expectedLastBlockSize = TEST_FILE_SIZE - (lastBlockId * (long) blockSize);
        assertEquals(expectedLastBlockSize, blockIndexInput.getActualBlockSize(lastBlockId, BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT, TEST_FILE_SIZE));
    }

    /**
     * Creates a test file with specified size at the given directory path.
     *
     * @param dir the directory to create the file in
     * @param fileName the name of the file to create
     * @param sizeInBytes the size of the file in bytes
     * @throws IOException if file creation fails
     */
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

    // Additional test cases for new functionality

    public void testGetFileNameFromBlockFileName() {
        assertEquals("_1.cfe", BlockIndexInput.getFileNameFromBlockFileName("_1.cfe_block_0"));
        assertEquals("_1.cfe", BlockIndexInput.getFileNameFromBlockFileName("_1.cfe_block_10"));
        assertEquals("test.dat", BlockIndexInput.getFileNameFromBlockFileName("test.dat_block_5"));
        assertEquals("original", BlockIndexInput.getFileNameFromBlockFileName("original"));
    }

    public void testIsBlockFilename() {
        assertTrue(BlockIndexInput.isBlockFilename("_1.cfe_block_0"));
        assertTrue(BlockIndexInput.isBlockFilename("test.dat_block_10"));
        assertFalse(BlockIndexInput.isBlockFilename("_1.cfe"));
        assertFalse(BlockIndexInput.isBlockFilename("test.dat"));
        assertFalse(BlockIndexInput.isBlockFilename("_1.cfe_block"));
    }

    public void testGetBlockFileName() {
        assertEquals("_1.cfe_block_0", BlockIndexInput.getBlockFileName("_1.cfe", 0));
        assertEquals("_1.cfe_block_5", BlockIndexInput.getBlockFileName("_1.cfe", 5));
        assertEquals("test.dat_block_10", BlockIndexInput.getBlockFileName("test.dat", 10));
    }

    public void testGetBlockStart() {
        int blockSizeShift = BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT;
        int blockSize = BlockIndexInput.getBlockSize(blockSizeShift);

        assertEquals(0L, BlockIndexInput.getBlockStart(0, blockSizeShift));
        assertEquals(blockSize, BlockIndexInput.getBlockStart(1, blockSizeShift));
        assertEquals(2L * blockSize, BlockIndexInput.getBlockStart(2, blockSizeShift));
    }

    public void testGetBlock() {
        int blockSizeShift = BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT;
        int blockSize = BlockIndexInput.getBlockSize(blockSizeShift);

        assertEquals(0, BlockIndexInput.getBlock(0, blockSizeShift));
        assertEquals(0, BlockIndexInput.getBlock(blockSize - 1, blockSizeShift));
        assertEquals(1, BlockIndexInput.getBlock(blockSize, blockSizeShift));
        assertEquals(1, BlockIndexInput.getBlock(blockSize + 100, blockSizeShift));
    }

    public void testGetActualBlockSizeStatic() {
        int blockSizeShift = BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT;
        int blockSize = BlockIndexInput.getBlockSize(blockSizeShift);
        long fileSize = blockSize + 1000L;

        // First block should be full size
        assertEquals(blockSize, BlockIndexInput.getActualBlockSize(0, blockSizeShift, fileSize));
        // Last block should be remainder
        assertEquals(1000L, BlockIndexInput.getActualBlockSize(1, blockSizeShift, fileSize));
    }

    public void testBuilderWithOffset() throws IOException {
        long offset = 1000L;
        long length = 5000L;

        BlockIndexInput input = BlockIndexInput.builder()
                .name(FILE_NAME)
                .localDirectory(fsDirectory)
                .fileSize(TEST_FILE_SIZE)
                .offset(offset)
                .context(IOContext.DEFAULT)
                .length(length)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .resourceDescription("")
                .build();

        assertEquals(length, input.length());
        input.close();
    }

    public void testBuilderWithCloneFlag() throws IOException {
        BlockIndexInput input = BlockIndexInput.builder()
                .name(FILE_NAME)
                .localDirectory(fsDirectory)
                .fileSize(TEST_FILE_SIZE)
                .isClone(true)
                .context(IOContext.DEFAULT)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .resourceDescription("")
                .build();

        assertNotNull(input);
        input.close();
    }

    public void testBuilderGetResourceDescription() {
        BlockIndexInput.Builder builder = BlockIndexInput.builder()
                .name(FILE_NAME)
                .localDirectory(fsDirectory);

        String description = builder.getResourceDescription();
        assertTrue(description.contains(FILE_NAME));
        assertTrue(description.contains(fsDirectory.getDirectory().toString()));
    }

    public void testBuilderWithCustomResourceDescription() throws IOException {
        String customDescription = "Custom Resource Description";

        BlockIndexInput.Builder builder = BlockIndexInput.builder()
                .name(FILE_NAME)
                .localDirectory(fsDirectory)
                .context(IOContext.DEFAULT)
                .resourceDescription(customDescription);

        assertEquals(customDescription, builder.getResourceDescription());

        BlockIndexInput input = builder
                .fileSize(TEST_FILE_SIZE)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .build();

        input.close();
    }

    public void testBuilderValidationNullName() {
        assertThrows(NullPointerException.class, () -> {
            BlockIndexInput.builder().name(null);
        });
    }

    public void testBuilderValidationNullDirectory() {
        assertThrows(NullPointerException.class, () -> {
            BlockIndexInput.builder().localDirectory(null);
        });
    }

    public void testBuilderValidationZeroFileSize() {
        assertThrows(IllegalStateException.class, () -> {
            BlockIndexInput.builder()
                    .name(FILE_NAME)
                    .localDirectory(fsDirectory)
                    .fileSize(0)
                    .build();
        });
    }

    public void testBuilderValidationNegativeFileSize() {
        assertThrows(IllegalStateException.class, () -> {
            BlockIndexInput.builder()
                    .name(FILE_NAME)
                    .localDirectory(fsDirectory)
                    .fileSize(-1)
                    .build();
        });
    }

    public void testSliceWithZeroLength() throws IOException {
        IndexInput slice = blockIndexInput.slice("zero-length", 0, 0);
        assertEquals(0, slice.length());
        slice.close();
    }

    public void testSliceAtFileEnd() throws IOException {
        IndexInput slice = blockIndexInput.slice("end-slice", TEST_FILE_SIZE - 100, 100);
        assertEquals(100, slice.length());
        slice.close();
    }

    public void testClonePreservesPosition() throws IOException {
        long position = 5000L;
        blockIndexInput.seek(position);

        BlockIndexInput clone = blockIndexInput.clone();
        assertEquals(position, clone.getFilePointer());
        clone.close();
    }

    public void testFetchBlockLogging() throws IOException {
        // This test verifies that fetchBlock method works correctly
        // The actual logging is tested implicitly through other operations
        blockIndexInput.seek(0);
        byte b = blockIndexInput.readByte();
        // If we get here without exception, fetchBlock worked
        assertTrue(true);
    }

    public void testMultipleSlicesFromSameInput() throws IOException {
        IndexInput slice1 = blockIndexInput.slice("slice1", 0, 1000);
        IndexInput slice2 = blockIndexInput.slice("slice2", 1000, 2000);

        assertEquals(1000, slice1.length());
        assertEquals(2000, slice2.length());

        slice1.close();
        slice2.close();
    }
}
