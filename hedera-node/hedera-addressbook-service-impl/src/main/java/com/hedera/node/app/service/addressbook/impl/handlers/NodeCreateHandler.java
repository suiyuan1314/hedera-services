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

package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CAE_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_NODES_CREATED;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateRecordBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_CREATE}.
 * This is a privileged(Needs signatures from 2-50 )
 */
@Singleton
public class NodeCreateHandler implements TransactionHandler {
    private final AddressBookValidator addressBookValidator;

    /**
     * Constructs a {@link NodeCreateHandler} with the given {@link AddressBookValidator}.
     * @param addressBookValidator the validator for the crypto create transaction
     */
    @Inject
    public NodeCreateHandler(@NonNull final AddressBookValidator addressBookValidator) {
        this.addressBookValidator =
                requireNonNull(addressBookValidator, "The supplied argument 'addressBookValidator' must not be null");
    }

    @Override
    public void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        final var op = txn.nodeCreate();
        final var accountId = validateAccountID(op.accountIdOrElse(AccountID.DEFAULT), INVALID_NODE_ACCOUNT_ID);
        validateFalsePreCheck(!accountId.hasAccountNum() && accountId.hasAlias(), INVALID_NODE_ACCOUNT_ID);
        validateFalsePreCheck(op.gossipEndpoint().isEmpty(), INVALID_GOSSIP_ENDPOINT);
        validateFalsePreCheck(op.serviceEndpoint().isEmpty(), INVALID_SERVICE_ENDPOINT);
        validateFalsePreCheck(
                op.gossipCaCertificate().length() == 0
                        || op.gossipCaCertificate().equals(Bytes.EMPTY),
                INVALID_GOSSIP_CAE_CERTIFICATE);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        requireNonNull(context);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);

        final var op = handleContext.body().nodeCreate();

        final var configuration = handleContext.configuration();
        final var nodeConfig = configuration.getConfigData(NodesConfig.class);
        final var nodeStore = handleContext.writableStore(WritableNodeStore.class);
        final var accountStore = handleContext.readableStore(ReadableAccountStore.class);
        final var accountId = op.accountIdOrElse(AccountID.DEFAULT);

        validateFalse(nodeStore.sizeOfState() >= nodeConfig.maxNumber(), MAX_NODES_CREATED);
        validateTrue(accountStore.contains(accountId), INVALID_NODE_ACCOUNT_ID);
        addressBookValidator.validateDescription(op.description(), nodeConfig);
        addressBookValidator.validateGossipEndpoint(op.gossipEndpoint(), nodeConfig);
        addressBookValidator.validateServiceEndpoint(op.serviceEndpoint(), nodeConfig);

        final var nodeBuilder = new Node.Builder()
                .accountId(op.accountId())
                .description(op.description())
                .gossipEndpoint(op.gossipEndpoint())
                .serviceEndpoint(op.serviceEndpoint())
                .gossipEndpoint(op.gossipEndpoint())
                .serviceEndpoint(op.serviceEndpoint())
                .gossipCaCertificate(op.gossipCaCertificate())
                .grpcCertificateHash(op.grpcCertificateHash());
        final var node = nodeBuilder.nodeId(getNextNodeID(nodeStore)).build();

        nodeStore.put(node);

        final var recordBuilder = handleContext.recordBuilder(NodeCreateRecordBuilder.class);

        recordBuilder.nodeID(node.nodeId());
    }

    private long getNextNodeID(@NonNull final WritableNodeStore nodeStore) {
        requireNonNull(nodeStore);
        final var nodeIds = nodeStore.keys();
        AtomicLong max = new AtomicLong(-1);
        nodeIds.forEachRemaining(nodeId -> max.set(Math.max(max.get(), nodeId.number())));
        return max.get() + 1;
    }
}
