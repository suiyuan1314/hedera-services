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

package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.suites.TargetNetworkType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;

/**
 * A network of Hedera nodes.
 */
public interface HederaNetwork {
    /**
     * Sends the given query to the network node with the given account id as if it
     * was the given functionality. Blocks until the response is available.
     *
     * <p>For valid queries, the functionality can be inferred; but for invalid queries,
     * the functionality must be provided.
     *
     * @param query the query
     * @param functionality the functionality to use
     * @param nodeAccountId the account id of the node to send the query to
     * @return the network's response
     */
    @NonNull
    Response send(@NonNull Query query, @NonNull HederaFunctionality functionality, @NonNull AccountID nodeAccountId);

    /**
     * Submits the given transaction to the network node with the given account id as if it
     * was the given functionality. Blocks until the response is available.
     *
     * <p>For valid transactions, the functionality can be inferred; but for invalid transactions,
     * the functionality must be provided.
     *
     * @param transaction the transaction
     * @param functionality the functionality to use
     * @param target the target to use, given a system functionality
     * @param nodeAccountId the account id of the node to submit the transaction to
     * @return the network's response
     */
    TransactionResponse submit(
            @NonNull Transaction transaction,
            @NonNull HederaFunctionality functionality,
            @NonNull SystemFunctionalityTarget target,
            @NonNull AccountID nodeAccountId);

    /**
     * Returns the network type; for now this is always
     * {@link TargetNetworkType#SHARED_HAPI_TEST_NETWORK}.
     *
     * @return the network type
     */
    TargetNetworkType type();

    /**
     * Returns the nodes of the network.
     *
     * @return the nodes of the network
     */
    List<HederaNode> nodes();

    /**
     * Returns the nodes of the network that match the given selector.
     *
     * @param selector the selector
     * @return the nodes that match the selector
     */
    default List<HederaNode> nodesFor(@NonNull final NodeSelector selector) {
        requireNonNull(selector);
        return nodes().stream().filter(selector).toList();
    }

    /**
     * Returns the node of the network that matches the given selector.
     *
     * @param selector the selector
     * @return the nodes that match the selector
     */
    default HederaNode getRequiredNode(@NonNull final NodeSelector selector) {
        requireNonNull(selector);
        return nodes().stream().filter(selector).findAny().orElseThrow();
    }

    /**
     * Returns the name of the network.
     *
     * @return the name of the network
     */
    String name();

    /**
     * Starts all nodes in the network.
     */
    void start();

    /**
     * Forcibly stops all nodes in the network.
     */
    void terminate();

    /**
     * Waits for all nodes in the network to be ready within the given timeout.
     */
    void awaitReady(@NonNull Duration timeout);
}
