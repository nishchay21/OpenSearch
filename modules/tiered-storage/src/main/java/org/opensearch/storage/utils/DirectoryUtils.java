/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.utils;

import org.apache.lucene.store.FSDirectory;

import java.nio.file.Path;

public class DirectoryUtils {

    public static final String SWITCHABLE_PREFIX = "_switchable";

    public static Path getFilePath(FSDirectory localDirectory, String fileName) {
        return localDirectory.getDirectory().resolve(fileName);
    }

    public static Path getFilePathSwitchable(FSDirectory localDirectory, String fileName) {
        return localDirectory.getDirectory().resolve(fileName + SWITCHABLE_PREFIX);
    }
}
