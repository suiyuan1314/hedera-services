/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.virtualmap.internal.merkle;

import com.swirlds.common.config.singleton.ConfigurationHolder;
import com.swirlds.common.crypto.Hash;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The hashing algorithm in the {@link com.swirlds.virtualmap.internal.hash.VirtualHasher} is setup to
 * hash enormous trees in breadth-first order. As the hasher hashes, it notifies this listener which then stores
 * up the changes into different sorted lists.
 * Then, when the "batch" is completed, it flushes the data in the proper order to the data source. This process
 * completely bypasses the {@link com.swirlds.virtualmap.internal.cache.VirtualNodeCache} and the
 * {@link com.swirlds.virtualmap.internal.pipeline.VirtualPipeline}, which is essential for performance and memory
 * reasons, since during reconnect we may need to process the entire data set, which is too large to fit in memory.
 * <p>
 * Three things are required for this listener to work: the {@code firstLeafPath}, the {@code lastLeafPath}, and
 * the {@link VirtualDataSource}.
 * <p>
 * A tree is broken up into "ranks" where "rank 0" is the on that contains root, "rank 1" is the one that contains
 * the left and right children of root, "rank 2" has the children of the nodes in "rank 1", and so forth. The higher
 * the rank, the deeper in the tree the rank lives.
 * <p>
 * A "batch" is a portion of the tree that is independently hashed. The batch will always be processed from the
 * deepest rank (the leaves) to the lowest rank (nearest the top). When we flush, we flush in the opposite order
 * from the closest to the top of the tree to the deepest rank. Each rank is processed in ascending path order.
 * So we store each rank as a separate array and then stream them out in the proper order to disk.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public abstract class AbstractHashListener<K extends VirtualKey, V extends VirtualValue>
        implements VirtualHashListener<K, V> {

    private final VirtualDataSource<K, V> dataSource;
    private final long firstLeafPath;
    private final long lastLeafPath;
    private List<VirtualLeafRecord<K, V>> leaves;
    private List<VirtualHashRecord> hashes;

    // Flushes are initiated from onNodeHashed(). While a flush is in progress, other nodes
    // are still hashed in parallel, so it may happen that enough nodes are hashed to
    // start a new flush, while the previous flush is not complete yet. This flag is
    // protection from that
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private int reconnectFlushInterval = 0;

    /**
     * Create a new {@link ReconnectHashListener}.
     *
     * @param firstLeafPath
     * 		The first leaf path. Must be a valid path.
     * @param lastLeafPath
     * 		The last leaf path. Must be a valid path.
     * @param dataSource
     * 		The data source. Cannot be null.
     */
    protected AbstractHashListener(
            final long firstLeafPath, final long lastLeafPath, final VirtualDataSource<K, V> dataSource) {

        if (firstLeafPath != Path.INVALID_PATH && !(firstLeafPath > 0 && firstLeafPath <= lastLeafPath)) {
            throw new IllegalArgumentException("The first leaf path is invalid. firstLeafPath=" + firstLeafPath
                    + ", lastLeafPath=" + lastLeafPath);
        }

        if (lastLeafPath != Path.INVALID_PATH && lastLeafPath <= 0) {
            throw new IllegalArgumentException(
                    "The last leaf path is invalid. firstLeafPath=" + firstLeafPath + ", lastLeafPath=" + lastLeafPath);
        }

        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public synchronized void onHashingStarted() {
        assert (hashes == null) && (leaves == null) : "Hashing must not be started yet";
        hashes = new ArrayList<>();
        leaves = new ArrayList<>();
        reconnectFlushInterval =
                ConfigurationHolder.getConfigData(VirtualMapConfig.class).reconnectFlushInterval();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNodeHashed(final long path, final Hash hash) {
        assert hashes != null && leaves != null : "onNodeHashed called without onHashingStarted";
        final List<VirtualHashRecord> dirtyHashesToFlush;
        final List<VirtualLeafRecord<K, V>> dirtyLeavesToFlush;
        synchronized (this) {
            hashes.add(new VirtualHashRecord(path, hash));
            if ((reconnectFlushInterval > 0)
                    && (hashes.size() >= reconnectFlushInterval)
                    && flushInProgress.compareAndSet(false, true)) {
                dirtyHashesToFlush = hashes;
                hashes = new ArrayList<>();
                dirtyLeavesToFlush = leaves;
                leaves = new ArrayList<>();
            } else {
                dirtyHashesToFlush = null;
                dirtyLeavesToFlush = null;
            }
        }
        if ((dirtyHashesToFlush != null) && (dirtyLeavesToFlush != null)) {
            flush(dirtyHashesToFlush, dirtyLeavesToFlush);
        }
    }

    @Override
    public synchronized void onLeafHashed(final VirtualLeafRecord<K, V> leaf) {
        leaves.add(leaf);
    }

    @Override
    public void onHashingCompleted() {
        final List<VirtualHashRecord> finalNodesToFlush;
        final List<VirtualLeafRecord<K, V>> finalLeavesToFlush;
        synchronized (this) {
            finalNodesToFlush = hashes;
            hashes = null;
            finalLeavesToFlush = leaves;
            leaves = null;
        }
        if (!finalNodesToFlush.isEmpty() || !finalLeavesToFlush.isEmpty()) {
            assert !flushInProgress.get() : "Flush must not be in progress when hashing is complete";
            flushInProgress.set(true);
            flush(finalNodesToFlush, finalLeavesToFlush);
        }
    }

    // Since flushes may take quite some time, this method is called outside synchronized blocks,
    // otherwise all hashing tasks would be blocked on listener calls until flush is completed.
    private void flush(
            @NonNull final List<VirtualHashRecord> hashesToFlush,
            @NonNull final List<VirtualLeafRecord<K, V>> leavesToFlush) {
        assert flushInProgress.get() : "Flush in progress flag must be set";
        try {
            // flush it down
            try {
                dataSource.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashesToFlush.stream(),
                        leavesToFlush.stream(),
                        findLeavesToRemove(),
                        true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            flushInProgress.set(false);
        }
    }

    /**
     * Find the leaves that need to be removed from the data source up to this moment.
     *
     * @return a stream of leaves to remove
     */
    protected abstract Stream<VirtualLeafRecord<K, V>> findLeavesToRemove();
}
