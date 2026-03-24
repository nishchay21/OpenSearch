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
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.index.store.remote.filecache.FileCacheFactory;
import org.opensearch.index.store.remote.utils.FileTypeUtils;
import org.opensearch.storage.indexinput.SwitchableIndexInput;
import org.opensearch.storage.indexinput.SwitchableIndexInputWrapper;
import org.opensearch.storage.prefetch.TieredStoragePrefetchSettings;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
import static org.opensearch.storage.utils.DirectoryUtils.getFilePath;
import static org.opensearch.storage.utils.DirectoryUtils.getFilePathSwitchable;

@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class TieredDirectoryTests extends BaseRemoteSegmentStoreDirectoryTests {

    private FileCache fileCache;
    private FSDirectory localDirectory;
    private TieredDirectory TieredDirectory;
    private TieredStoragePrefetchSettings tieredStoragePrefetchSettings;

    private final static String[] LOCAL_FILES = new String[] {
            "_1.cfe",
            "_1.cfe_block_0",
            "_1.cfe_block_1",
            "_2.cfe",
            "_0.cfe_block_7",
            "_0.cfs_block_7",
            "_x.abc_block_0",
            "temp_file.tmp"
    };
    private final static String FILE_PRESENT_LOCALLY = "_1.cfe";
    private final static String FILE_RENAMED = "_1_new.cfe";
    private final static String FILE_PRESENT_IN_REMOTE_ONLY = "_0.si";
    private final static String NON_EXISTENT_FILE = "non_existent_file";
    private final static String TEMP_FILE = "temp_file.tmp";
    private final static String CORRUPTED_FILE = "corrupted_uuid";

    @Before
    public void setup() throws IOException {
        setupRemoteSegmentStoreDirectory();
        populateMetadata();
        remoteSegmentStoreDirectory.init();
        localDirectory = FSDirectory.open(createTempDir());
        removeExtraFSFiles();
        // Concurrency level which dictates how many Segmented Cache will be created in the FileCache
        int concurrencyLevel = randomIntBetween(1,2);
        fileCache = FileCacheFactory.createConcurrentLRUFileCache(FILE_CACHE_CAPACITY, concurrencyLevel);
        Set<Setting<?>> clusterSettingsToAdd = new HashSet<>(BUILT_IN_CLUSTER_SETTINGS);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.READ_AHEAD_BLOCK_COUNT);
        clusterSettingsToAdd.add(TieredStoragePrefetchSettings.STORED_FIELDS_PREFETCH_ENABLED_SETTING);
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, clusterSettingsToAdd);
        this.tieredStoragePrefetchSettings = new TieredStoragePrefetchSettings(clusterSettings);
        TieredDirectory = new TieredDirectory(localDirectory, remoteSegmentStoreDirectory, fileCache, threadPool, getPrefetchSettingsSupplier());
        addFilesToDirectory(LOCAL_FILES);
    }

    public Supplier<TieredStoragePrefetchSettings> getPrefetchSettingsSupplier() {
        return () -> this.tieredStoragePrefetchSettings;
    }

    public void testListAll() throws IOException {
        // even though only block file of _x.abc is present it should appear in listAll() of directory
        String[] actualFileNames = TieredDirectory.listAll();
        String[] expectedFileNames = new String[] { "_0.cfe", "_0.cfs", "_0.si", "_1.cfe", "_2.cfe", "_x.abc", "segments_1", "temp_file.tmp" };
        assertArrayEquals(expectedFileNames, actualFileNames);
    }

    public void testDeleteFile() throws IOException {
        // deletion of temp files
        assertTrue(existsInTieredDirectory(TEMP_FILE));
        TieredDirectory.deleteFile(TEMP_FILE);
        assertFalse(existsInTieredDirectory(TEMP_FILE));


        // deletion on non-existent file should fail silently without throwing any error
        assertFalse(existsInTieredDirectory(NON_EXISTENT_FILE));
        Exception exception = null;
        try {
            TieredDirectory.deleteFile(NON_EXISTENT_FILE);
        } catch (Exception e) {
            exception = e;
        }
        assertNull(exception);

        // deletion of file present in directory
        assertTrue(existsInTieredDirectory(FILE_PRESENT_LOCALLY));
        assertTrue(existsInLocalDirectory(FILE_PRESENT_LOCALLY));
        assertTrue(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY)));
        assertTrue(existsInFileCache(getFilePath(localDirectory,FILE_PRESENT_LOCALLY)));
        TieredDirectory.deleteFile(FILE_PRESENT_LOCALLY);
        assertFalse(existsInTieredDirectory(FILE_PRESENT_LOCALLY));
        assertFalse(existsInLocalDirectory(FILE_PRESENT_LOCALLY));
        assertFalse(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY)));
        assertFalse(existsInFileCache(getFilePath(localDirectory,FILE_PRESENT_LOCALLY)));

        // deletion of file present in remote
        // Current limitation of Composite Directory does not allow files present in remote to be deleted
        assertTrue(existsInTieredDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertTrue(existsInRemoteDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        TieredDirectory.deleteFile(FILE_PRESENT_IN_REMOTE_ONLY);
        assertTrue(existsInTieredDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertTrue(existsInRemoteDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
    }

    public void testRename() throws IOException {

        // renaming of non-existent file
        assertThrows(NoSuchFileException.class, () -> TieredDirectory.rename(NON_EXISTENT_FILE, FILE_RENAMED));

        // Rename should work as expected for file present in directory
        assertTrue(existsInTieredDirectory(FILE_PRESENT_LOCALLY));
        assertTrue(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY)));
        assertFalse(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_RENAMED)));
        TieredDirectory.rename(FILE_PRESENT_LOCALLY, FILE_RENAMED);
        assertFalse(existsInTieredDirectory(FILE_PRESENT_LOCALLY));
        assertFalse(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY)));
        assertTrue(existsInFileCache(getFilePathSwitchable(localDirectory, FILE_RENAMED)));
    }

    public void testOpenInput() throws IOException {
        populateData();

        // File not present in Directory
        assertFalse(existsInTieredDirectory(NON_EXISTENT_FILE));
        assertThrows(NoSuchFileException.class, () -> TieredDirectory.openInput(NON_EXISTENT_FILE, IOContext.DEFAULT));

        // Temp file, read directly form local directory
        assertTrue(existsInLocalDirectory(TEMP_FILE) && FileTypeUtils.isTempFile(TEMP_FILE));
        assertFalse(existsInLocalDirectory(CORRUPTED_FILE));

        assertEquals(
                TieredDirectory.openInput(TEMP_FILE, IOContext.DEFAULT).toString(),
                localDirectory.openInput(TEMP_FILE, IOContext.DEFAULT).toString()
        );

        // File present in file cache, cached locally
        assertNotNull(fileCache.get(getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY)));
        IndexInput indexInput = TieredDirectory.openInput(FILE_PRESENT_LOCALLY, IOContext.DEFAULT);
        assertNotNull(indexInput);
        assertTrue(indexInput instanceof SwitchableIndexInputWrapper);
        assertFalse(getSwitchableIndexInputFromWrapper(indexInput).isCachedFromRemote());

        // File not present in file cache, to be cached from Remote
        assertNull(fileCache.get(getFilePathSwitchable(localDirectory, FILE_PRESENT_IN_REMOTE_ONLY)));
        indexInput = TieredDirectory.openInput(FILE_PRESENT_IN_REMOTE_ONLY, IOContext.DEFAULT);
        assertNotNull(indexInput);
        assertTrue(indexInput instanceof SwitchableIndexInputWrapper);
        assertTrue(getSwitchableIndexInputFromWrapper(indexInput).isCachedFromRemote());
    }

    public void testIndexInputCleanup() throws IOException {
        populateData();
        Path path = getFilePathSwitchable(localDirectory, FILE_PRESENT_LOCALLY);
        // file was already added to cache, hence refCount will be 1
        assertEquals(1, (int) fileCache.getRef(path));
        IndexInput indexInput = TieredDirectory.openInput(FILE_PRESENT_LOCALLY, IOContext.DEFAULT);
        // refCount will increase to 2 due to openInput call
        assertEquals(2, (int) fileCache.getRef(path));
        // decrementing refCount to zero explicitly
        decRefToZero(path);
        // create clones that are not closed
        createUnclosedClones(indexInput, path);
        // trigger GC to check unclosed clones are cleaned up and refCount is adjusted
        triggerGarbageCollectionAndAssertClonesClosed(path);
    }

    public void testAfterSyncToRemote() throws IOException {
        populateData();
        String fileName = "_0.si";
        String fileNameBlock = "_0.si_block_0";
        addFilesToDirectory(new String[]{fileName});

        // File will be present locally until uploaded to Remote
        assertTrue(existsInLocalDirectory(fileName));
        // RefCount of switchable entry should be 1 as file hasn't uploaded to remote yet
        assertEquals(1, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of full file entry should be 1 as it is reference by the switchable entry
        assertEquals(1, (int) fileCache.getRef(getFilePath(localDirectory, fileName)));
        // RefCount of block file will return null indicating it is not present in file cache
        assertNull(fileCache.getRef(getFilePath(localDirectory, fileNameBlock)));

        // Get file from directory
        IndexInput indexInput = TieredDirectory.openInput(fileName, IOContext.DEFAULT);

        assertTrue(indexInput instanceof SwitchableIndexInputWrapper);
        // RefCount of switchable entry increase to 2 due to openInput call
        assertEquals(2, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of full file entry will also increase to 2 as switchable input will increase its ref count since underlying index input is local
        assertEquals(2, (int) fileCache.getRef(getFilePath(localDirectory, fileName)));
        // assert that index input hasn't switched yet
        assertFalse(getSwitchableIndexInputFromWrapper(indexInput).isCachedFromRemote());

        indexInput.close();

        // RefCount of switchable entry decreases to 1 again as index input was closed
        assertEquals(1, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of full file entry will also decrease to 1 to 2 as switchable input will increase its ref count since underlying index input is local
        assertEquals(1, (int) fileCache.getRef(getFilePath(localDirectory, fileName)));

        // Call afterSyncToRemote in which we switch the file from full to block based
        TieredDirectory.afterSyncToRemote(fileName);

        // File will be not be present locally but will be present in remote as it has been uploaded successfully
        assertFalse(existsInLocalDirectory(fileName));
        assertTrue(existsInRemoteDirectory(fileName));
        // RefCount of switchable entry should be 0 now as file has uploaded to remote now
        assertEquals(0, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of full file entry will return null indicating it is not present in cache
        assertNull(fileCache.getRef(getFilePath(localDirectory, fileName)));
        // RefCount of block file will return null indicating it is not present in cache
        // This is because the block file won't be fetched until and unless we get a read request
        assertNull(fileCache.getRef(getFilePath(localDirectory, fileNameBlock)));

        // Get file from directory again and read
        indexInput = TieredDirectory.openInput(fileName, IOContext.DEFAULT);

        // RefCount of switchable entry will increase to 1 now due to openInput call
        assertEquals(1, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of block file will also increase to 1 as switchable entry will incRef it while cloning during openInput
        assertEquals(1, (int) fileCache.getRef(getFilePath(localDirectory, fileNameBlock)));

        indexInput.close();

        // Now that the index input has closed, refCount of switchable input will be 0
        assertEquals(0, (int) fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // RefCount of block will also drop to 0
        assertEquals(0, (int) fileCache.getRef(getFilePath(localDirectory, fileNameBlock)));

        fileCache.prune();
        // When the fileCache is pruned the switchable entry will be evicted as its refCount was zero
        assertNull(fileCache.getRef(getFilePathSwitchable(localDirectory, fileName)));
        // The refCount of block entry will become zero as well
        assertNull(fileCache.getRef(getFilePath(localDirectory, fileNameBlock)));
    }

    private void removeExtraFSFiles() throws IOException {
        HashSet<String> allFiles = new HashSet<>(Arrays.asList(localDirectory.listAll()));
        allFiles.stream().filter(FileTypeUtils::isExtraFSFile).forEach(file -> {
            try {
                localDirectory.deleteFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void addFilesToDirectory(String[] files) throws IOException {
        for (String file : files) {
            IndexOutput indexOutput = TieredDirectory.createOutput(file, IOContext.DEFAULT);
            indexOutput.close();
        }
    }

    private SwitchableIndexInput getSwitchableIndexInputFromWrapper(IndexInput indexInput) {
        assertTrue(indexInput instanceof SwitchableIndexInputWrapper);
        return (SwitchableIndexInput)(((FilterIndexInput)indexInput).getDelegate());
    }

    private void decRefToZero(Path path) {
        int refCount = fileCache.getRef(path);
        for (int i=0; i<refCount; i++)
            fileCache.decRef(path);
    }

    private void createUnclosedClones(IndexInput indexInput, Path path) throws IOException {
        IndexInput clone = indexInput.clone();
        IndexInput cloneOfClone = clone.clone();
        // refCount of entry should have increased due to cloning/slicing operations
        assertEquals(2, (int)fileCache.getRef(path));
    }

    private void triggerGarbageCollectionAndAssertClonesClosed(Path path) {
        try {
            // Clones/Slices will be phantom reachable now, triggering gc should call close on them
            assertBusy(() -> {
                System.gc(); // Do not rely on GC to be deterministic, hence the polling
                assertEquals(
                    "Expected refCount to drop to original count as all clones/slices should have closed",
                    (int) fileCache.getRef(path),
                    0
                );
            }, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Exception thrown while triggering gc", e);
            fail();
        }
    }

    private boolean existsInLocalDirectory(String name) throws IOException {
        return Arrays.asList(localDirectory.listAll()).contains(name);
    }

    private boolean existsInRemoteDirectory(String name) throws IOException {
        return Arrays.asList(remoteSegmentStoreDirectory.listAll()).contains(name);
    }

    private boolean existsInTieredDirectory(String name) throws IOException {
        return Arrays.asList(TieredDirectory.listAll()).contains(name);
    }

    private boolean existsInFileCache(Path path) throws IOException {
        return (fileCache.get(path) != null);
    }
}
