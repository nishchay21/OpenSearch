/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.dataformat;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.store.FormatChecksumStrategy;

/**
 * Describes the static capabilities of a data format, including its default checksum
 * strategy, format name, and whether it requires native store for warm reads.
 * Provided by {@link DataFormatPlugin} implementations and consumed by
 * DataFormatAwareStoreDirectory, DataFormatAwareRemoteDirectory, and
 * TieredDataFormatAwareStoreDirectoryFactory.
 *
 * <p>The checksum strategy here is the <em>default fallback</em> — a full-file scan.
 * At runtime, the {@link IndexingExecutionEngine} may override this with a more
 * efficient strategy (e.g., {@link org.opensearch.index.store.PrecomputedChecksumStrategy})
 * via {@link org.opensearch.index.store.DataFormatAwareStoreDirectory#registerChecksumStrategy}.
 *
 * <p>All formats use the segment store ({@code RemoteSegmentStoreDirectory}) for uploads.
 * Formats that return {@code true} from {@link #nativeStoreSupported()} get a
 * {@code NativeStoreRepository}-backed TieredDirectory for warm reads. The native store
 * is obtained from the repository that's already wired to the shard (via
 * {@code Repository.getNativeStore()}) — the descriptor only signals the intent,
 * it doesn't provide the store itself.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class DataFormatDescriptor {

    private final String formatName;
    private final FormatChecksumStrategy checksumStrategy;
    private final boolean nativeStoreSupported;

    /**
     * Creates a new DataFormatDescriptor without native store.
     * The format uses the standard segment store for both uploads and warm reads.
     *
     * @param formatName        the format name (e.g., "parquet")
     * @param checksumStrategy  the default checksum strategy for this format
     */
    public DataFormatDescriptor(String formatName, FormatChecksumStrategy checksumStrategy) {
        this(formatName, checksumStrategy, false);
    }

    /**
     * Creates a new DataFormatDescriptor.
     *
     * @param formatName          the format name (e.g., "parquet")
     * @param checksumStrategy    the default checksum strategy for this format
     * @param nativeStoreSupported if {@code true}, warm reads for this format use the
     *                            {@code NativeStoreRepository} from the shard's repository
     *                            instead of the standard block-based remote reads
     */
    public DataFormatDescriptor(String formatName, FormatChecksumStrategy checksumStrategy, boolean nativeStoreSupported) {
        this.formatName = formatName;
        this.checksumStrategy = checksumStrategy;
        this.nativeStoreSupported = nativeStoreSupported;
    }

    /**
     * Returns the format name.
     *
     * @return the format name
     */
    public String getFormatName() {
        return formatName;
    }

    /**
     * Returns the default checksum strategy for this format.
     *
     * @return the checksum strategy
     */
    public FormatChecksumStrategy getChecksumStrategy() {
        return checksumStrategy;
    }

    /**
     * Returns whether this format needs native store for warm reads.
     *
     * <p>When {@code true}, the tiered directory factory obtains the
     * {@code NativeStoreRepository} from the shard's repository (via
     * {@code Repository.getNativeStore()}) and wires it into this format's
     * TieredDirectory for Rust-native I/O optimized for sequential
     * column-chunk reads (e.g., Parquet via native S3/GCS/Azure/FS).
     *
     * <p>When {@code false} (the default), warm reads go through the standard
     * {@code RemoteSegmentStoreDirectory} with block-based on-demand fetching.
     *
     * @return true if native store is needed for warm reads
     */
    public boolean nativeStoreSupported() {
        return nativeStoreSupported;
    }
}
