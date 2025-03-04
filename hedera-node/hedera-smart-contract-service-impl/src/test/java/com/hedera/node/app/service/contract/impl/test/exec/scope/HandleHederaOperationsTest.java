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

package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationFromHapi;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationFromParent;
import static com.hedera.node.app.spi.workflows.record.ExternalizedRecordCustomizer.SUPPRESSING_EXTERNALIZED_RECORD_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractCreateRecordBuilder;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.app.spi.workflows.record.ExternalizedRecordCustomizer;
import com.hedera.node.app.spi.workflows.record.RecordListCheckPoint;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleHederaOperationsTest {
    @Mock
    private HandleContext.SavepointStack savepointStack;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private HandleContext context;

    @Mock
    private WritableContractStateStore stateStore;

    @Mock
    private ContractCreateRecordBuilder contractCreateRecordBuilder;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private PendingCreationMetadataRef pendingCreationMetadataRef;

    private HandleHederaOperations subject;

    @BeforeEach
    void setUp() {
        subject = new HandleHederaOperations(
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_CONTRACTS_CONFIG,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                HederaFunctionality.CONTRACT_CALL,
                pendingCreationMetadataRef);
    }

    @Test
    void returnsContextualStore() {
        given(context.writableStore(WritableContractStateStore.class)).willReturn(stateStore);

        assertSame(stateStore, subject.getStore());
    }

    @Test
    void validatesShard() {
        assertSame(
                HederaOperations.MISSING_CONTRACT_ID,
                subject.shardAndRealmValidated(
                        ContractID.newBuilder().shardNum(1).contractNum(2L).build()));
    }

    @Test
    void validatesRealm() {
        assertSame(
                HederaOperations.MISSING_CONTRACT_ID,
                subject.shardAndRealmValidated(
                        ContractID.newBuilder().realmNum(1).contractNum(2L).build()));
    }

    @Test
    void returnsUnchangedWithMatchingShardRealm() {
        final var plausibleId = ContractID.newBuilder()
                .shardNum(0)
                .realmNum(0)
                .contractNum(3456L)
                .build();
        assertSame(plausibleId, subject.shardAndRealmValidated(plausibleId));
    }

    @Test
    void usesExpectedLimit() {
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxNumber(), subject.contractCreationLimit());
    }

    @Test
    void delegatesEntropyToBlockRecordInfo() {
        final var pretendEntropy = Bytes.fromHex("0123456789");
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.getNMinus3RunningHash()).willReturn(pretendEntropy);
        assertSame(pretendEntropy, subject.entropy());
    }

    @Test
    void returnsZeroEntropyIfNMinus3HashMissing() {
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        assertSame(HandleHederaOperations.ZERO_ENTROPY, subject.entropy());
    }

    @Test
    void createsNewSavepointWhenBeginningScope() {
        given(context.savepointStack()).willReturn(savepointStack);

        final var nestedScope = subject.begin();

        assertSame(subject, nestedScope);
        verify(savepointStack).createSavepoint();
    }

    @Test
    void rollsBackSavepointWhenReverting() {
        given(context.savepointStack()).willReturn(savepointStack);

        subject.revert();

        verify(savepointStack).rollback();
    }

    @Test
    void peekNumberUsesContext() {
        given(context.peekAtNewEntityNum()).willReturn(123L);
        assertEquals(123L, subject.peekNextEntityNumber());
    }

    @Test
    void useNumberUsesContext() {
        given(context.newEntityNum()).willReturn(123L);
        assertEquals(123L, subject.useNextEntityNumber());
    }

    @Test
    void createRecordListCheckPointUsesContext() {
        var recordListCheckPoint = new RecordListCheckPoint(null, null);
        given(context.createRecordListCheckPoint()).willReturn(recordListCheckPoint);
        assertEquals(recordListCheckPoint, subject.createRecordListCheckPoint());
    }

    @Test
    void revertRecordsFromUsesContext() {
        var recordListCheckPoint = new RecordListCheckPoint(null, null);
        subject.revertRecordsFrom(recordListCheckPoint);
        verify(context).revertRecordsFrom(recordListCheckPoint);
    }

    @Test
    void commitIsNoopUntilSavepointExposesIt() {
        given(context.savepointStack()).willReturn(savepointStack);

        subject.commit();

        verify(savepointStack).commit();
    }

    @Test
    void lazyCreationCostInGasTest() {
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(gasCalculator.canonicalPriceInTinybars(any(), eq(A_NEW_ACCOUNT_ID)))
                .willReturn(6L)
                .willReturn(5L);
        given(gasCalculator.topLevelGasPrice()).willReturn(1L);
        assertEquals(11L, subject.lazyCreationCostInGas(NON_SYSTEM_LONG_ZERO_ADDRESS));
    }

    @Test
    void gasPriceInTinybarsDelegates() {
        given(tinybarValues.topLevelTinybarGasPrice()).willReturn(1234L);
        assertEquals(1234L, subject.gasPriceInTinybars());
    }

    @Test
    void valueInTinybarsDelegates() {
        given(tinybarValues.asTinybars(1L)).willReturn(2L);
        assertEquals(2L, subject.valueInTinybars(1L));
    }

    @Test
    void collectFeeStillTransfersAllToNetworkFunding() {
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.collectFee(TestHelpers.NON_SYSTEM_ACCOUNT_ID, 123L);

        verify(tokenServiceApi)
                .transferFromTo(
                        TestHelpers.NON_SYSTEM_ACCOUNT_ID,
                        AccountID.newBuilder()
                                .accountNum(DEFAULT_LEDGER_CONFIG.fundingAccount())
                                .build(),
                        123L);
    }

    @Test
    void refundFeeStillTransfersAllFromNetworkFunding() {
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.refundFee(TestHelpers.NON_SYSTEM_ACCOUNT_ID, 123L);

        verify(tokenServiceApi)
                .transferFromTo(
                        AccountID.newBuilder()
                                .accountNum(DEFAULT_LEDGER_CONFIG.fundingAccount())
                                .build(),
                        TestHelpers.NON_SYSTEM_ACCOUNT_ID,
                        123L);
    }

    @Test
    void chargeStorageRentIsNoop() {
        assertDoesNotThrow(() -> subject.chargeStorageRent(
                ContractID.newBuilder().contractNum(1L).build(), 2L, true));
    }

    @Test
    void updateStorageMetadataUsesApi() {
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.updateStorageMetadata(NON_SYSTEM_CONTRACT_ID, Bytes.EMPTY, 2);

        verify(tokenServiceApi).updateStorageMetadata(NON_SYSTEM_CONTRACT_ID, Bytes.EMPTY, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createContractWithNonSelfAdminParentDispatchesAsExpectedThenMarksCreated() throws ParseException {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(124L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthContractCreation = synthContractCreationFromParent(pendingId, parent);
        final var synthAccountCreation =
                synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, synthContractCreation);
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreation)
                .build();
        final var captor = ArgumentCaptor.forClass(ExternalizedRecordCustomizer.class);
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        captor.capture()))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        assertInternalFinisherAsExpected(captor.getValue(), synthContractCreation);
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void translatesCreateContractHandleException() throws IOException {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(124L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthContractCreation = synthContractCreationFromParent(pendingId, parent);
        final var synthAccountCreation =
                synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, synthContractCreation);
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreation)
                .build();
        final var captor = ArgumentCaptor.forClass(ExternalizedRecordCustomizer.class);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        captor.capture()))
                .willThrow(new HandleException(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED));
        given(context.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        final var e = Assertions.assertThrows(
                ResourceExhaustedException.class,
                () -> subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS));
        assertEquals(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED, e.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createContractWithSelfAdminParentDispatchesAsExpectedThenMarksCreated() throws ParseException {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(123L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthContractCreation = synthContractCreationFromParent(pendingId, parent)
                .copyBuilder()
                .adminKey((Key) null)
                .build();
        final var synthAccountCreation =
                synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, synthContractCreation);
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreation)
                .build();
        final var captor = ArgumentCaptor.forClass(ExternalizedRecordCustomizer.class);
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        captor.capture()))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        assertInternalFinisherAsExpected(captor.getValue(), synthContractCreation);
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    private void assertInternalFinisherAsExpected(
            @NonNull final UnaryOperator<Transaction> internalFinisher,
            @NonNull final ContractCreateTransactionBody expectedOp)
            throws ParseException {
        Objects.requireNonNull(internalFinisher);

        // The finisher should swap the crypto create body with the contract create body
        final var cryptoCreateBody = TransactionBody.newBuilder()
                .cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT)
                .build();
        final var cryptoCreateInput = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(cryptoCreateBody))
                        .build()))
                .build();
        final var cryptoCreateOutput = internalFinisher.apply(cryptoCreateInput);
        final var finishedBody = TransactionBody.PROTOBUF.parseStrict(SignedTransaction.PROTOBUF
                .parseStrict(cryptoCreateOutput.signedTransactionBytes().toReadableSequentialData())
                .bodyBytes()
                .toReadableSequentialData());
        assertEquals(expectedOp, finishedBody.contractCreateInstanceOrThrow());

        // The finisher should reject transforming anything byt a crypto create
        final var nonCryptoCreateBody = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        final var nonCryptoCreateInput = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(nonCryptoCreateBody))
                        .build()))
                .build();
        assertThrows(IllegalArgumentException.class, () -> internalFinisher.apply(nonCryptoCreateInput));

        // The finisher should propagate any IOExceptions (which should never happen, as only HandleContext is client)
        final var nonsenseInput = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap("NONSENSE"))
                .build();
        assertThrows(UncheckedParseException.class, () -> internalFinisher.apply(nonsenseInput));
    }

    @Test
    void createContractWithBodyDispatchesThenMarksAsContract() {
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, someBody))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        any(ExternalizedRecordCustomizer.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, someBody, CANONICAL_ALIAS);

        verify(context)
                .dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        any(ExternalizedRecordCustomizer.class));
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    void createContractInsideEthereumTransactionWithBodyDispatchesThenMarksAsContract() {
        subject = new HandleHederaOperations(
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_CONTRACTS_CONFIG,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                ETHEREUM_TRANSACTION,
                pendingCreationMetadataRef);
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, someBody))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        any(ExternalizedRecordCustomizer.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, someBody, CANONICAL_ALIAS);

        final var captor = ArgumentCaptor.forClass(ExternalizedRecordCustomizer.class);
        verify(context)
                .dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        captor.capture());
        assertNotSame(SUPPRESSING_EXTERNALIZED_RECORD_CUSTOMIZER, captor.getValue());
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    void createContractWithFailedDispatchNotImplemented() {
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, someBody))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.dispatchRemovableChildTransaction(
                        eq(synthTxn),
                        eq(ContractCreateRecordBuilder.class),
                        eq(null),
                        eq(A_NEW_ACCOUNT_ID),
                        any(ExternalizedRecordCustomizer.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        assertThrows(IllegalStateException.class, () -> subject.createContract(666L, someBody, CANONICAL_ALIAS));
    }

    @Test
    void deleteUnaliasedContractUsesApi() {
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow());
        verify(tokenServiceApi).deleteContract(CALLED_CONTRACT_ID);
    }

    @Test
    void deleteAliasedContractUsesApi() {
        var txnBody = TransactionBody.newBuilder()
                .ethereumTransaction(EthereumTransactionBody.DEFAULT)
                .build();
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        subject.deleteAliasedContract(CANONICAL_ALIAS);
        verify(tokenServiceApi)
                .deleteContract(
                        ContractID.newBuilder().evmAddress(CANONICAL_ALIAS).build());
    }

    @Test
    void getModifiedAccountNumbersIsNotActuallyNeeded() {
        assertSame(Collections.emptyList(), subject.getModifiedAccountNumbers());
    }

    @Test
    void getOriginalSlotsUsedDelegatesToApi() {
        given(context.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(tokenServiceApi.originalKvUsageFor(A_NEW_CONTRACT_ID)).willReturn(123L);
        assertEquals(123L, subject.getOriginalSlotsUsed(A_NEW_CONTRACT_ID));
    }

    @Test
    void externalizeHollowAccountMerge() {
        // given
        var parentAccount = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1001).build())
                .key(Key.DEFAULT)
                .build();
        var contractId = ContractID.newBuilder().contractNum(1001).build();
        given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(ContractID.DEFAULT)).willReturn(parentAccount);
        given(context.addRemovableChildRecordBuilder(eq(ContractCreateRecordBuilder.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractID(eq(contractId))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.transaction(any(Transaction.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);

        // when
        subject.externalizeHollowAccountMerge(contractId, ContractID.DEFAULT, VALID_CONTRACT_ADDRESS.evmAddress());

        // then
        verify(contractCreateRecordBuilder).contractID(contractId);
        verify(contractCreateRecordBuilder).contractCreateResult(any(ContractFunctionResult.class));
    }
}
