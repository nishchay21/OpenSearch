/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.common.lucene.store.InputStreamIndexInput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;
import org.opensearch.index.store.remote.filecache.CachedIndexInput;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.index.store.remote.filecache.FileCacheFactory;
import org.opensearch.index.store.remote.filecache.FullFileCachedIndexInput;
import org.opensearch.index.store.remote.utils.TransferManager;
import org.opensearch.storage.indexinput.OnDemandPrefetchBlockSnapshotIndexInput;
import org.opensearch.storage.indexinput.SwitchableIndexInput;
import org.opensearch.storage.prefetch.TieredStoragePrefetchSettings;
import org.opensearch.threadpool.ThreadPool;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.opensearch.index.store.remote.utils.FileTypeUtils.BLOCK_FILE_IDENTIFIER;
import static org.opensearch.storage.utils.DirectoryUtils.getFilePath;
import static org.opensearch.storage.utils.DirectoryUtils.getFilePathSwitchable;

@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class SwitchableIndexInputTests extends BaseRemoteSegmentStoreDirectoryTests {

    FSDirectory localDirectory;
    FileCache fileCache;
    TransferManager transferManager;
    TieredStoragePrefetchSettings tieringServicePrefetchSettings;
    private static final String FILE_NAME = "_0.si";
    private static final String FILE_NAME_BLOCK = "_0.si_block_0";

    @Before
    public void setup() throws IOException {
        setupRemoteSegmentStoreDirectory();
        populateMetadata();
        remoteSegmentStoreDirectory.init();
        populateData();
        localDirectory = FSDirectory.open(createTempDir());
        syncLocalAndRemoteForFile(localDirectory, FILE_NAME);
        // Concurrency level which dictates how many Segmented Cache will be created in the FileCache
        int concurrencyLevel = randomIntBetween(1,2);
        fileCache = FileCacheFactory.createConcurrentLRUFileCache(FILE_CACHE_CAPACITY, concurrencyLevel);
        transferManager = new TransferManager(
                (name, position, length) ->
                        new InputStreamIndexInput(this.remoteSegmentStoreDirectory.openBlockInput(name, position, length, IOContext.DEFAULT), length),
                fileCache,
            threadPool
        );
        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.READ_AHEAD_BLOCK_COUNT);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.STORED_FIELDS_PREFETCH_ENABLED_SETTING);
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, clusterSettingsToAdd);
        this.tieringServicePrefetchSettings = new TieredStoragePrefetchSettings(clusterSettings);
    }

    public Supplier<TieredStoragePrefetchSettings> getPrefetchSettingsSupplier() {
        return () -> this.tieringServicePrefetchSettings;
    }

    public void testSwitchableIndexInputLocal() throws IOException {

        // initially no file should be present in file cache
        assertNull(getFileCacheEntry(FILE_NAME));

        SwitchableIndexInput switchableIndexInput = new SwitchableIndexInput(
                "switchable",
                FILE_NAME,
                getFilePath(localDirectory, FILE_NAME),
                getFilePathSwitchable(localDirectory, FILE_NAME),
                fileCache,
                localDirectory,
                remoteSegmentStoreDirectory,
                transferManager,
                false,
                threadPool,
                getPrefetchSettingsSupplier()
        );

        // once switchable index input(locally cached) is initialized entry gets added to file cache
        CachedIndexInput cachedIndexInput = getFileCacheEntry(FILE_NAME);
        assertNotNull(cachedIndexInput);

        IndexInput indexInput = cachedIndexInput.getIndexInput();
        assertTrue(indexInput instanceof FullFileCachedIndexInput);
        // assert that switchable index input is cached from local directory not remote
        assertFalse(switchableIndexInput.isCachedFromRemote());

        // assert that switchable index input has local based index input as it's underlying index input
        IndexInput localIndexInput = localDirectory.openInput(FILE_NAME, IOContext.DEFAULT);
        assertEquals(switchableIndexInput.length(), localIndexInput.length());
        assertEquals(switchableIndexInput.getFilePointer(), localIndexInput.getFilePointer());
        assertEquals(switchableIndexInput.readByte(), localIndexInput.readByte());

        // test cloning and slicing of index input
        testCloneSliceRefCounting(switchableIndexInput, FILE_NAME);
    }

    public void testSwitchableIndexInputRemote() throws IOException {
        populateData();

        // initially no block files should be present in file cache
        assertNull(getFileCacheEntry(FILE_NAME_BLOCK));

        SwitchableIndexInput switchableIndexInput = new SwitchableIndexInput(
                "switchable",
                FILE_NAME,
                getFilePath(localDirectory, FILE_NAME),
                getFilePathSwitchable(localDirectory, FILE_NAME),
                fileCache,
                localDirectory,
                remoteSegmentStoreDirectory,
                transferManager,
                true,
                threadPool,
                getPrefetchSettingsSupplier()
        );

        CachedIndexInput cachedIndexInput = getFileCacheEntry(FILE_NAME_BLOCK);
        // still no block files as block file won't be fetched until a read request comes in
        assertNull(cachedIndexInput);

        SwitchableIndexInput switchableIndexInput1 = switchableIndexInput.clone();
        byte b0 = switchableIndexInput1.readByte();

        cachedIndexInput = getFileCacheEntry(FILE_NAME_BLOCK);
        // block file cached in FileCache now
        assertNotNull(cachedIndexInput);

        // assert that switchable index input is cached from remote
        assertTrue(switchableIndexInput.isCachedFromRemote());

        // assert that switchable index input has remote based index input as it's underlying index input
        IndexInput remoteIndexInput = remoteSegmentStoreDirectory.openInput(FILE_NAME, IOContext.DEFAULT);
        byte b1 = remoteIndexInput.readByte();
        assertEquals(switchableIndexInput1.length(), remoteIndexInput.length());
        assertEquals(switchableIndexInput1.getFilePointer(), remoteIndexInput.getFilePointer());
        assertEquals(b0, b1);

        switchableIndexInput1.close();

        // test cloning and slicing of index input
        testCloneSliceRefCounting(switchableIndexInput, FILE_NAME_BLOCK);
    }

    public void testSwitchToRemote() throws IOException {
        SwitchableIndexInput switchableIndexInput = new SwitchableIndexInput(
                "switchable",
                FILE_NAME,
                getFilePath(localDirectory, FILE_NAME),
                getFilePathSwitchable(localDirectory, FILE_NAME),
                fileCache,
                localDirectory,
                remoteSegmentStoreDirectory,
                transferManager,
                false,
                threadPool,
                getPrefetchSettingsSupplier()
        );

        SwitchableIndexInput clonedIndexInput = switchableIndexInput.clone();
        SwitchableIndexInput slicedIndexInput = switchableIndexInput.slice("slice", 0, switchableIndexInput.length());

        // assert that the index input and it's clones haven't switched and FileCache has full file entries not blocks
        assertFalse(switchableIndexInput.hasSwitchedToRemote());
        assertFalse(clonedIndexInput.hasSwitchedToRemote());
        assertFalse(slicedIndexInput.hasSwitchedToRemote());
        assertNotNull(getFileCacheEntry(FILE_NAME));
        assertNull(getFileCacheEntry(FILE_NAME_BLOCK));

        long filePointerBeforeSwitching = switchableIndexInput.getFilePointer();
        switchableIndexInput.switchToRemote();
        long filePointerAfterSwitching = switchableIndexInput.getFilePointer();

        switchableIndexInput.readByte();
        // assert that the index input and it's clones have switched and FileCache has block entries only and not full files
        assertTrue(switchableIndexInput.hasSwitchedToRemote());
        assertTrue(clonedIndexInput.hasSwitchedToRemote());
        assertTrue(slicedIndexInput.hasSwitchedToRemote());
        assertNull(getFileCacheEntry(FILE_NAME));
        assertNotNull(getFileCacheEntry(FILE_NAME_BLOCK));

        // assert that the file pointer is at the same position even after switching
        assertEquals(filePointerAfterSwitching, filePointerBeforeSwitching);
    }

    public void testPrefetch() throws IOException {
        SwitchableIndexInput switchableIndexInput = new SwitchableIndexInput(
            "switchable",
            FILE_NAME,
            getFilePath(localDirectory, FILE_NAME),
            getFilePathSwitchable(localDirectory, FILE_NAME),
            fileCache,
            localDirectory,
            remoteSegmentStoreDirectory,
            transferManager,
            false,
            threadPool,
            getPrefetchSettingsSupplier()
        );

        switchableIndexInput.prefetch(0, 10);
        IndexInput indexInput = switchableIndexInput.getUnderlyingIndexInput();
        assertTrue(indexInput instanceof FullFileCachedIndexInput);
        switchableIndexInput.switchToRemote();
        assertTrue(switchableIndexInput.hasSwitchedToRemote());
        switchableIndexInput.prefetch(0, 10);
        indexInput = switchableIndexInput.getUnderlyingIndexInput();
        assertTrue(indexInput instanceof OnDemandPrefetchBlockSnapshotIndexInput);
    }

    // test to perform concurrent operations on a single index input
    public void testConcurrencySingleIndexInput() throws IOException, InterruptedException {
        MockSwitchableIndexInput switchableIndexInput = getMockSwitchableIndexInput();
        List<SwitchableIndexInput> indexInputs = new ArrayList<>();
        indexInputs.add(switchableIndexInput);
        final ExecutorService testRunner = Executors.newFixedThreadPool(8);
        try {
            InjectableLock objectLock = switchableIndexInput.getObjectLock();
            // list of operations to perform on the single index input
            List<Consumer<SwitchableIndexInput>> operations = getOperationsToExecute();
            // concurrently executing all operations multiple times - no timeout expected, operations should complete
            runOperationsConcurrently(testRunner, operations, indexInputs, 10, true);
            // once a thread acquires the object lock it will be blocked until we explicitly set delayEnabled to false
            objectLock.setDelayEnabled(true);
            // concurrently executing all operations multiple times - timeout expected as one of the threads will acquire the lock and wait, causing other threads to wait as well
            runOperationsConcurrently(testRunner, operations, indexInputs, 10, false);
            // set delayEnabled to false in order to unblock thread holding the lock
            objectLock.setDelayEnabled(false);
        } finally {
            assertTrue(terminate(testRunner));
        }
    }

    // test to perform concurrent operations on a multiple index inputs(original and clones)
    public void testConcurrencyMultipleIndexInput() throws IOException, InterruptedException {
        MockSwitchableIndexInput switchableIndexInput = getMockSwitchableIndexInput();
        SwitchableIndexInput clone1 = switchableIndexInput.clone();
        SwitchableIndexInput clone2 = clone1.clone();
        List<SwitchableIndexInput> indexInputs = new ArrayList<>();
        indexInputs.add(clone1);
        indexInputs.add(clone2);
        final ExecutorService testRunner = Executors.newFixedThreadPool(8);
        try {
            InjectableReadWriteLock sharedLock = switchableIndexInput.getSharedLock();
            // list of operations to perform on each of the index inputs (original and clones both)
            List<Consumer<SwitchableIndexInput>> operations = getOperationsToExecute();
            // concurrently executing all operations multiple times - no timeout expected
            runOperationsConcurrently(testRunner, operations, indexInputs, 10, true);
            // once a thread acquires the shared write lock it will be blocked until we explicitly set delayEnabled to false
            sharedLock.setWriteDelayEnabled(true);
            // concurrently executing all operations multiple times - timeout expected as one of the threads will acquire write lock and wait, causing other threads to wait as well
            runOperationsConcurrently(testRunner, operations, indexInputs, 10, false);
            // set delayEnabled to false in order to unblock thread holding the lock
            sharedLock.setWriteDelayEnabled(false);
        } finally {
            assertTrue(terminate(testRunner));
        }
    }

    private void testCloneSliceRefCounting(SwitchableIndexInput switchableIndexInput, String fileName) throws IOException {
        IndexInput clonedIndexInput = switchableIndexInput.clone();
        IndexInput slicedIndexInput = switchableIndexInput.slice("slice", 0, switchableIndexInput.length());
        fileCache.prune();
        long parentPointer = switchableIndexInput.getFilePointer();

        // check that clones and slices behave independently and do not change file pointers for parent

        assertEquals(switchableIndexInput.getFilePointer(), clonedIndexInput.getFilePointer());
        assertEquals(slicedIndexInput.getFilePointer(), 0);

        clonedIndexInput.seek(clonedIndexInput.getFilePointer() + 1);
        slicedIndexInput.seek(slicedIndexInput.getFilePointer() + 2);

        assertNotEquals(switchableIndexInput.getFilePointer(), clonedIndexInput.getFilePointer());
        assertNotEquals(switchableIndexInput.getFilePointer(), slicedIndexInput.getFilePointer());
        assertEquals(switchableIndexInput.getFilePointer(), parentPointer);

        // asserting that the file cache has the file entry (refCount > 0 due to clones and slices)
        CachedIndexInput cachedIndexInput = getFileCacheEntry(fileName);
        assertNotNull(cachedIndexInput);

        clonedIndexInput.close();
        slicedIndexInput.close();

        // asserting that the file cache has the file entry with refCount 0
        cachedIndexInput = getFileCacheEntry(fileName);
        assertNotNull(cachedIndexInput);

        if (fileName.contains(BLOCK_FILE_IDENTIFIER) == false) {
            uploadToRemote(fileName);
        }

        fileCache.prune();

        // asserting that the file cache doesn't have the entry now since index input is closed
        cachedIndexInput = getFileCacheEntry(fileName);
        assertNull(cachedIndexInput);
    }

    private CachedIndexInput getFileCacheEntry(String name) {
        Path path = getFilePath(localDirectory, name);
        CachedIndexInput cachedIndexInput = fileCache.get(path);
        fileCache.decRef(path);
        return cachedIndexInput;
    }

    // For mocking behaviour when file is uploaded to remote
    private void uploadToRemote(String file) {
        fileCache.decRef(getFilePath(localDirectory, file));
        fileCache.decRef(getFilePathSwitchable(localDirectory, file));
    }

    private MockSwitchableIndexInput getMockSwitchableIndexInput() throws IOException {
        return new MockSwitchableIndexInput(
            "switchable",
            FILE_NAME,
            fileCache,
            localDirectory,
            remoteSegmentStoreDirectory,
            transferManager,
            false,
            threadPool,
            getPrefetchSettingsSupplier()
        );
    }

    private Runnable createOperationRunner(CountDownLatch startTogether,
                                           Consumer<SwitchableIndexInput> operation,
                                           SwitchableIndexInput switchableIndexInput,
                                           CountDownLatch latch
    ) {
        return () -> {
            try {
                startTogether.await();
                operation.accept(switchableIndexInput);
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                latch.countDown();
            }
        };
    }

    private List<Consumer<SwitchableIndexInput>> getOperationsToExecute() {
        List<Consumer<SwitchableIndexInput>> operations = new ArrayList<>();
        operations.add(SwitchableIndexInput::getFilePointer);
        operations.add(SwitchableIndexInput::clone);
        operations.add(SwitchableIndexInput::length);
        operations.add(indexInput -> {
            try {
                indexInput.switchToRemote();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        operations.add(indexInput -> {
            try {
                indexInput.readByte();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return operations;
    }

    private void runOperationsConcurrently(ExecutorService testRunner,
                                           List<Consumer<SwitchableIndexInput>> operations,
                                           List<SwitchableIndexInput> indexInputs,
                                           int numTimesToExecute,
                                           boolean shouldComplete) throws InterruptedException {
        for (int i = 0; i <= numTimesToExecute; i++) {
            CountDownLatch latch = new CountDownLatch(indexInputs.size() * operations.size());
            CountDownLatch startTogether = new CountDownLatch(1);
            for (Consumer<SwitchableIndexInput> operation : operations) {
                for (SwitchableIndexInput indexInput : indexInputs) {
                    testRunner.submit(createOperationRunner(startTogether, operation, indexInput, latch));
                }
            }
            startTogether.countDown();
            // operations shouldn't time out
            assertEquals(shouldComplete, latch.await(5, TimeUnit.SECONDS));
        }
    }

    private static class MockSwitchableIndexInput extends SwitchableIndexInput {

        public MockSwitchableIndexInput(
            String resourceDescription,
            String fileName, FileCache fileCache,
            FSDirectory localDirectory, RemoteSegmentStoreDirectory remoteDirectory,
            TransferManager transferManager,
            boolean cacheFromRemote,
            ThreadPool threadPool,
            Supplier<TieredStoragePrefetchSettings> tieredStoragePrefetchSettingsSupplier
        ) throws IOException {
            super(resourceDescription, fileName, getFilePath(localDirectory, FILE_NAME), getFilePathSwitchable(localDirectory, FILE_NAME), fileCache, localDirectory, remoteDirectory, transferManager, cacheFromRemote, threadPool, tieredStoragePrefetchSettingsSupplier);
            sharedLock = new InjectableReadWriteLock(sharedLock);
            objectLock = new InjectableLock(objectLock);
        }

        InjectableReadWriteLock getSharedLock() {
            return (InjectableReadWriteLock)sharedLock;
        }

        InjectableLock getObjectLock() {
            return (InjectableLock)objectLock;
        }
    }

    private static class InjectableLock implements Lock {
        private final Lock delegate;
        private final Object delayMonitor = new Object();
        private volatile boolean delayEnabled = false;

        public InjectableLock(Lock delegate) {
            this.delegate = delegate;
        }

        public void setDelayEnabled(boolean enabled) {
            synchronized (delayMonitor) {
                delayEnabled = enabled;
                if (!enabled) {
                    delayMonitor.notifyAll();
                }
            }
        }

        private void maybeDelay() {
            synchronized (delayMonitor) {
                while (delayEnabled) {
                    try {
                        delayMonitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during delay", e);
                    }
                }
            }
        }

        @Override
        public void lock() {
            delegate.lock();
            maybeDelay();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            delegate.lockInterruptibly();
        }

        @Override
        public boolean tryLock() {
            return delegate.tryLock();
        }

        @Override
        public boolean tryLock(long time, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return delegate.tryLock(time, unit);
        }

        @Override
        public void unlock() {
            delegate.unlock();
        }

        @Override
        public Condition newCondition() {
            return delegate.newCondition();
        }
    }

    private static class InjectableReadWriteLock implements ReadWriteLock {
        private final ReadWriteLock delegate;
        private final InjectableLock readLockWrapper;
        private final InjectableLock writeLockWrapper;

        public InjectableReadWriteLock(ReadWriteLock delegate) {
            this.delegate = delegate;
            this.readLockWrapper = new InjectableLock(delegate.readLock());
            this.writeLockWrapper = new InjectableLock(delegate.writeLock());
        }

        public void setReadDelayEnabled(boolean enabled) {
            readLockWrapper.setDelayEnabled(enabled);
        }

        public void setWriteDelayEnabled(boolean enabled) {
            writeLockWrapper.setDelayEnabled(enabled);
        }

        @Override
        public Lock readLock() {
            return readLockWrapper;
        }

        @Override
        public Lock writeLock() {
            return writeLockWrapper;
        }
    }

}
