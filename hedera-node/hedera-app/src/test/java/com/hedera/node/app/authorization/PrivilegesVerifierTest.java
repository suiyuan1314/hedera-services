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

package com.hedera.node.app.authorization;

import static com.hedera.node.app.service.mono.txns.auth.SystemOpAuthorization.AUTHORIZED;
import static com.hedera.node.app.service.mono.txns.auth.SystemOpAuthorization.IMPERMISSIBLE;
import static com.hedera.node.app.service.mono.txns.auth.SystemOpAuthorization.UNAUTHORIZED;
import static com.hedera.node.app.service.mono.txns.auth.SystemOpAuthorization.UNNECESSARY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.mono.pbj.PbjConverter;
import com.hedera.node.app.service.mono.txns.auth.SystemOpAuthorization;
import com.hedera.node.app.service.mono.utils.accessors.SignedTxnAccessor;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// This test may look a little weird without context. The original test in mono is very extensive. To ensure that
// we don't break anything, I copied the test from mono and hacked it a little to run it with the new code.
// (It was a good thing. I discovered two bugs...) :)
class PrivilegesVerifierTest {

    private Wrapper subject;

    private static class Wrapper {
        private final PrivilegesVerifier delegate;

        Wrapper(final ConfigProvider configProvider) {
            delegate = new PrivilegesVerifier(configProvider);
        }

        SystemOpAuthorization checkAccessor(final SignedTxnAccessor accessor) {
            com.hedera.hapi.node.base.AccountID accountID = PbjConverter.toPbj(accessor.getPayer());
            com.hedera.hapi.node.base.HederaFunctionality functionality = PbjConverter.toPbj(accessor.getFunction());
            com.hedera.hapi.node.transaction.TransactionBody txBody = PbjConverter.toPbj(accessor.getTxn());
            final var pbjResult = delegate.hasPrivileges(accountID, functionality, txBody);
            return SystemOpAuthorization.valueOf(pbjResult.name());
        }

        boolean canPerformNonCryptoUpdate(final long accountNum, final long fileNum) {
            final var accountID = com.hedera.hapi.node.base.AccountID.newBuilder()
                    .accountNum(accountNum)
                    .build();
            final var fileID = com.hedera.hapi.node.base.FileID.newBuilder()
                    .fileNum(fileNum)
                    .build();
            final var fileUpdateTxBody = com.hedera.hapi.node.transaction.TransactionBody.newBuilder()
                    .fileUpdate(com.hedera.hapi.node.file.FileUpdateTransactionBody.newBuilder()
                            .fileID(fileID)
                            .build())
                    .build();
            final var fileAppendTxBody = com.hedera.hapi.node.transaction.TransactionBody.newBuilder()
                    .fileAppend(com.hedera.hapi.node.file.FileAppendTransactionBody.newBuilder()
                            .fileID(fileID)
                            .build())
                    .build();
            return delegate.hasPrivileges(accountID, HederaFunctionality.FILE_UPDATE, fileUpdateTxBody)
                            == SystemPrivilege.AUTHORIZED
                    && delegate.hasPrivileges(accountID, HederaFunctionality.FILE_APPEND, fileAppendTxBody)
                            == SystemPrivilege.AUTHORIZED;
        }
    }

    @BeforeEach
    void setUp() {
        final var configuration = HederaTestConfigBuilder.createConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(configuration, 1L);

        subject = new Wrapper(configProvider);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new PrivilegesVerifier(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void treasuryCanUpdateAllNonAccountEntities() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(2, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 111));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 112));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 123));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(2, num));
        }
    }

    @Test
    void sysAdminCanUpdateKnownSystemFiles() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(50, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 111));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 112));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 123));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(50, num));
        }
    }

    @Test
    void softwareUpdateAdminCanUpdateExpected() {
        // expect:
        assertFalse(subject.canPerformNonCryptoUpdate(54, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 112));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(54, num));
        }
    }

    @Test
    void addressBookAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(55, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(55, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(55, 112));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(55, num));
        }
    }

    @Test
    void feeSchedulesAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(56, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 112));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(56, num));
        }
    }

    @Test
    void exchangeRatesAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(57, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 123));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 112));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 150));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(57, num));
        }
    }

    @Test
    void freezeAdminCanUpdateExpected() {
        // expect:
        assertFalse(subject.canPerformNonCryptoUpdate(58, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 112));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 102));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(58, num));
        }
    }

    @Test
    void uncheckedSubmitRejectsUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void sysAdminCanSubmitUnchecked() throws InvalidProtocolBufferException {
        // given:
        var txn = sysAdminTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void treasuryCanSubmitUnchecked() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(75)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForSystem() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(75)));
        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForNonSystem() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(1001)));
        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorizedForTreasury() throws InvalidProtocolBufferException {
        // given:
        var selfUpdateTxn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var otherUpdateTxn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(50)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(selfUpdateTxn)));
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(otherUpdateTxn)));
    }

    @Test
    void cryptoUpdateRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var civilianTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var sysAdminTxn = sysAdminTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(civilianTxn)));
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(sysAdminTxn)));
    }

    @Test
    void fileUpdateRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(111)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(111)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(112)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void treasuryCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void sysAdminCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = sysAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void freezeAdminCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = freezeAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void randomAdminCannotFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(123)));
        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(123)));
        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysUndeleteTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysUndeleteTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(123)));
        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(123)));
        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysDeleteTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(UNAUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysDeleteTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(1122)));
        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void contractUpdateRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setContractUpdateInstance(
                        ContractUpdateTransactionBody.newBuilder().setContractID(contract(1233)));
        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(112)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void softwareUpdateAdminCanUpdateZipFile() throws InvalidProtocolBufferException {
        // given:
        var txn = softwareUpdateAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(150)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(1122)));
        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemFilesCannotBeDeleted() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setFileDelete(FileDeleteTransactionBody.newBuilder().setFileID(file(100)));

        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void civilianFilesAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setFileDelete(FileDeleteTransactionBody.newBuilder().setFileID(file(1001)));

        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void civilianContractsAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setContractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().setContractID(contract(1001)));

        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void systemAccountsCannotBeDeleted() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(account(100)));

        // expect:
        assertEquals(IMPERMISSIBLE, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void civilianAccountsAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(account(1001)));

        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void createAccountAlwaysOk() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn().setCryptoCreateAccount(CryptoCreateTransactionBody.getDefaultInstance());

        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void ethereumTxnAlwaysOk() throws InvalidProtocolBufferException {
        // given:
        var txn = ethereumTxn().setEthereumTransaction(EthereumTransactionBody.getDefaultInstance());

        // expect:
        assertEquals(UNNECESSARY, subject.checkAccessor(accessor(txn)));
    }

    @Test
    void handlesDifferentPayer() throws InvalidProtocolBufferException {
        // given:
        var selfUpdateTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var otherUpdateTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(50)));
        // expect:
        assertEquals(AUTHORIZED, subject.checkAccessor(accessorWithPayer(selfUpdateTxn, account(2))));
        assertEquals(AUTHORIZED, subject.checkAccessor(accessorWithPayer(otherUpdateTxn, account(2))));
    }

    private SignedTxnAccessor accessor(TransactionBody.Builder transaction) throws InvalidProtocolBufferException {
        var txn = TransactionBody.newBuilder()
                .mergeFrom(transaction.build())
                .clearTransactionID()
                .build();
        var accessor = SignedTxnAccessor.from(Transaction.newBuilder()
                .setBodyBytes(txn.toByteString())
                .build()
                .toByteArray());
        accessor.setPayer(transaction.getTransactionID().getAccountID());
        return accessor;
    }

    private SignedTxnAccessor accessorWithPayer(TransactionBody.Builder txn, AccountID payer)
            throws InvalidProtocolBufferException {
        var accessor = SignedTxnAccessor.from(Transaction.newBuilder()
                .setBodyBytes(txn.build().toByteString())
                .build()
                .toByteArray());
        accessor.setPayer(payer);
        return accessor;
    }

    private TransactionBody.Builder ethereumTxn() {
        return txnWithPayer(123);
    }

    private TransactionBody.Builder civilianTxn() {
        return txnWithPayer(75231);
    }

    private TransactionBody.Builder treasuryTxn() {
        return txnWithPayer(2);
    }

    private TransactionBody.Builder softwareUpdateAdminTxn() {
        return txnWithPayer(54);
    }

    private TransactionBody.Builder freezeAdminTxn() {
        return txnWithPayer(58);
    }

    private TransactionBody.Builder sysAdminTxn() {
        return txnWithPayer(50);
    }

    private TransactionBody.Builder sysDeleteTxn() {
        return txnWithPayer(59);
    }

    private TransactionBody.Builder sysUndeleteTxn() {
        return txnWithPayer(60);
    }

    private TransactionBody.Builder exchangeRatesAdminTxn() {
        return txnWithPayer(57);
    }

    private TransactionBody.Builder txnWithPayer(long num) {
        return TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder().setAccountID(account(num)));
    }

    private ContractID contract(long num) {
        return ContractID.newBuilder().setContractNum(num).build();
    }

    private FileID file(long num) {
        return FileID.newBuilder().setFileNum(num).build();
    }

    private AccountID account(long num) {
        return AccountID.newBuilder().setAccountNum(num).build();
    }
}
