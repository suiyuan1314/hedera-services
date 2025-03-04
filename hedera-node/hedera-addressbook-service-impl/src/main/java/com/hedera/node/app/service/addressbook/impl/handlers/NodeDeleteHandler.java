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

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NODE_DELETED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_DELETE}.
 */
@Singleton
public class NodeDeleteHandler implements TransactionHandler {
    @Inject
    public NodeDeleteHandler() {
        // Exists for injection
    }

    @Override
    public void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        final NodeDeleteTransactionBody transactionBody = txn.nodeDeleteOrThrow();
        final long nodeId = transactionBody.nodeId();

        validateFalsePreCheck(nodeId < 0, INVALID_NODE_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Empty method
    }

    /**
     * Given the appropriate context, deletes a node.
     *
     * @param context the {@link HandleContext} of the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);

        final NodeDeleteTransactionBody transactionBody = context.body().nodeDeleteOrThrow();
        var nodeId = transactionBody.nodeId();

        final var nodeStore = context.writableStore(WritableNodeStore.class);

        Node node = nodeStore.get(nodeId);

        validateFalse(node == null, INVALID_NODE_ID);

        validateFalse(node.deleted(), NODE_DELETED);

        /* Copy all the fields from existing, and mark deleted flag  */
        final var nodeBuilder = node.copyBuilder().deleted(true);

        /* --- Put the modified node. It will be in underlying state's modifications map.
        It will not be committed to state until commit is called on the state.--- */
        nodeStore.put(nodeBuilder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        return feeContext.feeCalculator(SubType.DEFAULT).calculate();
    }
}
