/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.state.addressbook.Node;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableNodeStore {

    /**
     * Returns the node needed. If the node doesn't exist returns failureReason. If the
     * node exists , the failure reason will be null.
     *
     * @param nodeId node id being looked up
     * @return node's metadata
     */
    @Nullable
    Node get(final long nodeId);

    /**
     * Returns the number of nodes in the state.
     * @return the number of nodes in the state.
     */
    long sizeOfState();

    /**
     * Warms the system by preloading a node into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param nodeId the node id
     */
    default void warm(final long nodeId) {}
}
