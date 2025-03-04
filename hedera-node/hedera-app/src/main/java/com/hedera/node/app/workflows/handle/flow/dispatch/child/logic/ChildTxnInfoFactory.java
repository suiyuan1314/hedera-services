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

package com.hedera.node.app.workflows.handle.flow.dispatch.child.logic;

import static com.hedera.hapi.util.HapiUtils.functionOf;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.workflows.TransactionInfo;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Factory for providing all needed information for a child transaction.
 */
@Singleton
public class ChildTxnInfoFactory {
    /**
     * Constructs the {@link ChildTxnInfoFactory} instance.
     */
    @Inject
    public ChildTxnInfoFactory() {}

    /**
     * Provides the transaction information for the given dispatched transaction body.
     * @param txBody the transaction body
     * @return the transaction information
     */
    public TransactionInfo getTxnInfoFrom(TransactionBody txBody) {
        final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txBody);
        final var signedTransaction =
                SignedTransaction.newBuilder().bodyBytes(bodyBytes).build();
        final var signedTransactionBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);
        final var transaction = Transaction.newBuilder()
                .signedTransactionBytes(signedTransactionBytes)
                .build();
        // Since in the current systems the synthetic transactions need not have a transaction ID
        // Payer will be injected as synthetic payer in dagger subcomponent, since the payer could be different
        // for schedule dispatches. Also, there will not be signature verifications for synthetic transactions.
        // So these fields are set to default values and will not be used.
        return new TransactionInfo(
                transaction,
                txBody,
                TransactionID.DEFAULT,
                AccountID.DEFAULT,
                SignatureMap.DEFAULT,
                signedTransactionBytes,
                functionOfTxn(txBody));
    }

    /**
     * Provides the functionality of the transaction body.
     * @param txBody the transaction body
     * @return the functionality
     */
    private static HederaFunctionality functionOfTxn(final TransactionBody txBody) {
        try {
            return functionOf(txBody);
        } catch (final UnknownHederaFunctionality e) {
            throw new IllegalArgumentException("Unknown Hedera Functionality", e);
        }
    }
}
