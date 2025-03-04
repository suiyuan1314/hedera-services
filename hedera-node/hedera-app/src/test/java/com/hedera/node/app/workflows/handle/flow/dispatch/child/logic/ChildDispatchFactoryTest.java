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

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hedera.node.app.workflows.handle.flow.dispatch.child.logic.ChildRecordBuilderFactoryTest.asTxn;
import static com.hedera.node.app.workflows.handle.flow.dispatch.child.logic.ChildTxnInfoFactoryTest.consensusTime;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.PRE_HANDLE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.ComputeDispatchFeesAsTopLevel;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.record.ExternalizedRecordCustomizer;
import com.hedera.node.app.state.WrappedHederaState;
import com.hedera.node.app.workflows.dispatcher.ReadableStoreFactory;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.flow.dispatch.Dispatch;
import com.hedera.node.app.workflows.handle.flow.dispatch.child.ChildDispatchComponent;
import com.hedera.node.app.workflows.handle.record.RecordListBuilder;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilderImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.Collections;
import java.util.function.Predicate;
import javax.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChildDispatchFactoryTest {
    @Mock
    private TransactionDispatcher dispatcher;

    @Mock
    private HandleContext handleContext;

    @Mock
    private Dispatch parentDispatch;

    @Mock
    private Provider<ChildDispatchComponent.Factory> childDispatchFactoryProvider;

    @Mock
    private ChildDispatchComponent.Factory childDispatchFactory;

    @Mock
    private ReadableStoreFactory readableStoreFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private SavepointStackImpl savepointStack;

    private ChildDispatchFactory subject;

    private static final AccountID payerId =
            AccountID.newBuilder().accountNum(1_234L).build();
    private static final CryptoTransferTransactionBody transferBody = CryptoTransferTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(TokenID.DEFAULT)
                    .nftTransfers(NftTransfer.newBuilder()
                            .receiverAccountID(AccountID.DEFAULT)
                            .senderAccountID(AccountID.DEFAULT)
                            .serialNumber(1)
                            .build())
                    .build())
            .build();
    private static final TransactionBody txBody = asTxn(transferBody, payerId, consensusTime);
    private final Configuration configuration = HederaTestConfigBuilder.createConfig();
    private final RecordListBuilder recordListBuilder = new RecordListBuilder(consensusTime);

    private final ChildTxnInfoFactory childTxnInfoFactory = new ChildTxnInfoFactory();
    private final ChildRecordBuilderFactory childRecordBuilderFactory = new ChildRecordBuilderFactory();

    private final Predicate<Key> callback = key -> true;
    private final HandleContext.TransactionCategory category = HandleContext.TransactionCategory.CHILD;
    private final ExternalizedRecordCustomizer customizer = recordBuilder -> recordBuilder;
    private final SingleTransactionRecordBuilderImpl.ReversingBehavior reversingBehavior =
            SingleTransactionRecordBuilderImpl.ReversingBehavior.REMOVABLE;

    @BeforeEach
    public void setUp() {
        subject = new ChildDispatchFactory(childTxnInfoFactory, dispatcher, childRecordBuilderFactory);

        given(parentDispatch.handleContext()).willReturn(handleContext);
        given(handleContext.configuration()).willReturn(configuration);
        given(parentDispatch.readableStoreFactory()).willReturn(readableStoreFactory);
        given(readableStoreFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(payerId))
                .willReturn(Account.newBuilder().key(Key.DEFAULT).build());
        given(parentDispatch.recordListBuilder()).willReturn(recordListBuilder);
        given(parentDispatch.stack()).willReturn(savepointStack);
        given(savepointStack.peek()).willReturn(new WrappedHederaState(savepointStack));
        given(childDispatchFactoryProvider.get()).willReturn(childDispatchFactory);
    }

    @Test
    void testCreateChildDispatch() throws PreCheckException {
        // Create the child dispatch
        subject.createChildDispatch(
                parentDispatch,
                txBody,
                callback,
                payerId,
                category,
                childDispatchFactoryProvider,
                customizer,
                reversingBehavior);
        final var expectedPreHandleResult = new PreHandleResult(
                null,
                null,
                SO_FAR_SO_GOOD,
                OK,
                null,
                Collections.emptySet(),
                null,
                Collections.emptySet(),
                null,
                null,
                0);

        verify(dispatcher).dispatchPureChecks(txBody);
        verify(dispatcher).dispatchPreHandle(any());

        verify(childDispatchFactory)
                .create(
                        any(),
                        any(),
                        eq(ComputeDispatchFeesAsTopLevel.NO),
                        eq(payerId),
                        eq(category),
                        any(),
                        eq(expectedPreHandleResult),
                        any());
    }

    @Test
    void failsToCreateDispatchIfPreHandleException() throws PreCheckException {
        willThrow(new PreCheckException(PAYER_ACCOUNT_DELETED))
                .given(dispatcher)
                .dispatchPreHandle(any());
        subject.createChildDispatch(
                parentDispatch,
                txBody,
                callback,
                payerId,
                category,
                childDispatchFactoryProvider,
                customizer,
                reversingBehavior);
        final var expectedPreHandleResult = new PreHandleResult(
                null,
                null,
                PRE_HANDLE_FAILURE,
                PAYER_ACCOUNT_DELETED,
                null,
                Collections.emptySet(),
                null,
                Collections.emptySet(),
                null,
                null,
                0);
        verify(dispatcher).dispatchPureChecks(txBody);
        verify(dispatcher).dispatchPreHandle(any());

        verify(childDispatchFactory)
                .create(
                        any(),
                        any(),
                        eq(ComputeDispatchFeesAsTopLevel.NO),
                        eq(payerId),
                        eq(category),
                        any(),
                        eq(expectedPreHandleResult),
                        any());
    }
}
