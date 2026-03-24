/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.storage.directory;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.common.logging.Loggers;
import org.opensearch.common.lucene.store.InputStreamIndexInput;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;
import org.opensearch.storage.common.BlockTransferManager;
import org.opensearch.storage.indexinput.BlockFetchRequest;
import org.opensearch.storage.indexinput.BlockIndexInput;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A directory implementation that stores files as blocks.
 * This directory manages operations on block files and exposes an interface to read
 * these block files in non block manner.
 * If a file is present as is, in a non block manner, this directory can manage such a file as well.
 * Non block files can get created if the files are created without the provided interfaces.
 */
public class OSBlockHotDirectory extends FilterDirectory {
    private Logger logger;
    private final RemoteSegmentStoreDirectory remoteSegmentStoreDirectory;
    private final IndexSettings indexSettings;
    private final Path directoryPath;
    private final BlockTransferManager blockTransferManager;
    private final int blockSizeShift;
    /**
     * This map stores file length for logical files which are backed by block files.
     * Writes can happen to this directory outside the block context using createOutput or directly
     * on the FSDirectory path. Those file names will not be present in this map and will not be
     * treated as block files.
     */
    protected final Map<String, Long> logicalFileLengthMap = new ConcurrentHashMap<>();


    /**
     * Creates a new OSBlockHotDirectory instance with default block transfer manager.
     *
     * @param delegate The underlying directory to delegate operations to
     * @param remoteDirectory The remote directory for segment files
     * @param indexSettings Index-specific settings
     * @throws IllegalArgumentException if no FSDirectory is found in delegate chain
     */
    public OSBlockHotDirectory(Directory delegate, Directory remoteDirectory, IndexSettings indexSettings, Supplier<ThreadPool> threadPoolSupplier) throws IOException {
        this(delegate, remoteDirectory, indexSettings,
                new BlockTransferManager(
                        (name, position, length) -> new InputStreamIndexInput(
                                ((RemoteSegmentStoreDirectory) remoteDirectory).openBlockInput(name, position, length, IOContext.DEFAULT),
                                length
                        ),
                        indexSettings,
                        threadPoolSupplier
                )
        );
    }

    /**
     * Creates a new OSBlockHotDirectory instance with a custom block transfer manager.
     *
     * @param delegate The underlying directory to delegate operations to
     * @param remoteDirectory The remote directory for storing segments
     * @param indexSettings Index-specific settings
     * @param blockTransferManager Custom block transfer manager implementation
     * @throws IllegalArgumentException if validation fails
     */
    public OSBlockHotDirectory(Directory delegate,
                               Directory remoteDirectory,
                               IndexSettings indexSettings,
                               BlockTransferManager blockTransferManager) throws IOException {
        super(delegate);
        validateArguments(delegate, remoteDirectory, indexSettings, blockTransferManager);

        logger = Loggers.getLogger(getClass(), indexSettings.getIndex());
        this.directoryPath = resolveFSDirectoryPath(delegate);
        this.blockTransferManager = blockTransferManager;
        this.indexSettings = indexSettings;
        this.remoteSegmentStoreDirectory = (RemoteSegmentStoreDirectory) remoteDirectory;
        this.blockSizeShift = BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT;
        initializeMetadata();
        logger.debug("OSBlockHotDirectory created with path: {}",
            directoryPath);
    }

    /**
     * Initializes the file length map by reading all files in the directory.
     *
     * @throws IOException if initialization fails
     */
    public void initializeMetadata() throws IOException {
        for (String blockFileName : this.in.listAll()) {
            if (!BlockIndexInput.isBlockFilename(blockFileName)) continue;
            String fileName = BlockIndexInput.getFileNameFromBlockFileName(blockFileName);
            Long currentValue = logicalFileLengthMap.getOrDefault(fileName, 0L);
            logicalFileLengthMap.put(fileName, currentValue + this.in.fileLength(blockFileName));
        }
    }

    /**
     * Lists all the logical files in the directory.
     * Abstracts the block file details.
     * @return Array of unique file names
     * @throws IOException if listing files fails
     */
    @Override
    public String[] listAll() throws IOException {
        ensureOpen();
        String[] localFiles = this.in.listAll();
        return Arrays.stream(localFiles)
            .map(BlockIndexInput::getFileNameFromBlockFileName)
            .distinct()
            .toArray(String[]::new);
    }

    /**
     * Deletes a file by deleting all its associated block files.
     *
     * @param name The name of the file to delete
     * @throws IOException if deletion fails
     * @throws NoSuchFileException if file is not found
     */
    @Override
    public void deleteFile(String name) throws IOException {
        ensureOpen();
        if (logicalFileLengthMap.containsKey(name)) {
            for (String f : getBlockFiles(name)) {
                this.in.deleteFile(f);
            }
            logicalFileLengthMap.remove(name);
        } else {
            this.in.deleteFile(name);
        }
    }

    /**
     * Renames a file by renaming all its associated block files.
     *
     * @param source The source file name
     * @param dest The destination file name
     * @throws IOException if renaming fails
     * @throws NoSuchFileException if source file is not found
     */
    @Override
    public void rename(String source, String dest) throws IOException {
        ensureOpen();
        Long existingFileLength = logicalFileLengthMap.get(source);
        if (existingFileLength != null) {
            for (String f : getBlockFiles(source)) {
                String updatedFileName = f.replace(source, dest);
                this.in.rename(f, updatedFileName);
            }
            logicalFileLengthMap.put(dest, existingFileLength);
            logicalFileLengthMap.remove(source);
        } else {
            this.in.rename(source, dest);
        }
    }

    /**
     * Opens an input stream for reading a file.
     *
     * @param name The name of the file to open
     * @param context The I/O context
     * @return An IndexInput instance for reading the file
     * @throws IOException if opening the input fails
     * @throws NoSuchFileException if file is not found
     */
    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        ensureOpen();
        if (!logicalFileLengthMap.containsKey(name)) {
            return this.in.openInput(name, context);
        }
        return BlockIndexInput.builder()
                .localDirectory((FSDirectory) this.in)
                .resourceDescription("Blocked IndexInput for " + name)
                .name(name)
                .fileSize(fileLength(name))
                .length(fileLength(name))
                .context(context)
                .blockSizeShift(BlockIndexInput.Builder.DEFAULT_BLOCK_SIZE_SHIFT)
                .build();
    }

    /**
     * Copies a file from src to this directory as multiple blocks.
     *
     * @param from Source directory
     * @param src Source file name
     * @param dest Destination file name
     * @param context The I/O context
     * @throws IOException if copying fails
     */
    @Override
    public void copyFrom(Directory from, String src, String dest, IOContext context) throws IOException {
        ensureOpen();
        logger.debug("Copying file from {} to {}", src, dest);
        if (validateRemoteDirectoryPresentInDelegateChain(from)) {
            copyFileAsBlocks(from, src, dest, context);
        } else {
            this.in.copyFrom(from, src, dest, context);
        }
    }

    /**
     * Calculates the total length of a file including all its blocks.
     *
     * @param name The name of the file
     * @return The total length of the file
     * @throws IOException if calculating length fails
     * @throws NoSuchFileException if file is not found
     */
    @Override
    public long fileLength(String name) throws IOException {
        ensureOpen();
        Long existingFileLength = logicalFileLengthMap.get(name);
        return existingFileLength != null ? existingFileLength
                : this.in.fileLength(name);
    }

    /**
     * Syncs the specified files to disk.
     *
     * @param names Collection of file names to sync
     * @throws IOException if syncing fails
     * @throws NoSuchFileException if any file is not found
     */
    @Override
    public void sync(Collection<String> names) throws IOException {
        Set<String> fileNamesToSync = new HashSet<>();
        for (String name : names) {
            if (logicalFileLengthMap.containsKey(name)) {
                fileNamesToSync.addAll(getBlockFiles(name));
            } else {
                fileNamesToSync.add(name);
            }
        }
        logger.debug("Syncing files: {}", fileNamesToSync);
        this.in.sync(fileNamesToSync);
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        ensureOpen();
        return this.in.getPendingDeletions()
                .stream().map(BlockIndexInput::getFileNameFromBlockFileName)
                .collect(Collectors.toSet());
    }

    // Private helper methods
    private void copyFileAsBlocks(Directory from, String src, String dest, IOContext context) throws IOException {
        long fileLength = from.fileLength(src);
        List<BlockFetchRequest> requests = createBlockFetchRequests(dest, fileLength, blockSizeShift);
        try {
            blockTransferManager.fetchBlocksAsync(requests);
            this.logicalFileLengthMap.put(dest, fileLength);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Block transfer interrupted", e);
        }
    }

    private Path resolveFSDirectoryPath(Directory delegate) {
        Directory current = delegate;
        while (current != null) {
            if (current instanceof FSDirectory) {
                return ((FSDirectory) current).getDirectory();
            } else if (current instanceof FilterDirectory) {
                current = ((FilterDirectory) current).getDelegate();
            } else {
                throw new IllegalArgumentException(
                        "No FSDirectory found in delegate chain. Found " + current.getClass().getName()
                );
            }
        }
        throw new IllegalArgumentException("No FSDirectory found in delegate chain");
    }

    private boolean validateRemoteDirectoryPresentInDelegateChain(Directory delegate) {
        Directory current = delegate;
        while (current != null) {
            if (current == this.remoteSegmentStoreDirectory) {
                return true;
            } else if (current instanceof FilterDirectory) {
                current = ((FilterDirectory) current).getDelegate();
            } else {
                return false;
            }
        }
        return false;
    }

    private List<BlockFetchRequest> createBlockFetchRequests(String filename, long fileLength, int blockSizeShift) {
        List<Integer> blockIds = BlockIndexInput.getAllBlockIdsForFile(fileLength, blockSizeShift);
        List<BlockFetchRequest> requests = new ArrayList<>();

        blockIds.forEach(blockId -> {
            String blockFileName = BlockIndexInput.getBlockFileName(filename, blockId);
            long blockSize = BlockIndexInput.getActualBlockSize(blockId, blockSizeShift, fileLength);
            long blockStart = BlockIndexInput.getBlockStart(blockId, blockSizeShift);

            requests.add(BlockFetchRequest.builder()
                    .blockStart(blockStart)
                    .blockSize(blockSize)
                    .directory((FSDirectory) this.in)
                    .blockFileName(blockFileName)
                    .fileName(filename)
                    .build());
        });

        return requests;
    }

    /**
     * Validates constructor arguments.
     *
     * @param delegate The underlying directory
     * @param remoteDirectory The remote directory
     * @param indexSettings Index settings
     * @param blockTransferManager Block transfer manager
     * @throws IllegalArgumentException if any argument is invalid
     */
    private void validateArguments(Directory delegate, Directory remoteDirectory,
                                   IndexSettings indexSettings, BlockTransferManager blockTransferManager) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate directory cannot be null");
        }
        if (remoteDirectory == null) {
            throw new IllegalArgumentException("Remote directory cannot be null");
        }
        if (!(remoteDirectory instanceof RemoteSegmentStoreDirectory)) {
            throw new IllegalArgumentException("Remote directory must be an instance of RemoteSegmentStoreDirectory");
        }
        if (indexSettings == null) {
            throw new IllegalArgumentException("Index settings cannot be null");
        }
        if (blockTransferManager == null) {
            throw new IllegalArgumentException("BlockTransferManager cannot be null");
        }
    }

    private List<String> getBlockFiles(String fileName) {
        return IntStream.rangeClosed(0, BlockIndexInput.getNumberOfBlocks(logicalFileLengthMap.get(fileName), blockSizeShift) - 1)
                .boxed()
                .map(i -> BlockIndexInput.getBlockFileName(fileName, i))
                .collect(Collectors.toList());
    }
}
