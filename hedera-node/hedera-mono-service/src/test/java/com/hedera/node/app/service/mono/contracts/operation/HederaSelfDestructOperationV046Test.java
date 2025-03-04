/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.mono.contracts.operation;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.evm.contracts.operations.HederaExceptionalHaltReason;
import com.hedera.node.app.service.mono.context.TransactionContext;
import com.hedera.node.app.service.mono.context.properties.GlobalDynamicProperties;
import com.hedera.node.app.service.mono.contracts.sources.EvmSigsVerifier;
import com.hedera.node.app.service.mono.store.contracts.HederaStackedWorldStateUpdater;
import com.hedera.node.app.service.mono.utils.EntityNum;
import java.util.function.BiPredicate;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaSelfDestructOperationV046Test {
    private static final EntityNum beneficiary = EntityNum.fromLong(2_345);
    private static final String ethAddress = "0xc257274276a4e539741ca11b590b9447b26a8051";
    private static final Address eip1014Address = Address.fromHexString(ethAddress);

    @Mock
    private HederaStackedWorldStateUpdater worldUpdater;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private TransactionContext txnCtx;

    @Mock
    private MessageFrame frame;

    @Mock
    private EVM evm;

    @Mock
    private MutableAccount account;

    @Mock
    private BiPredicate<Address, MessageFrame> addressValidator;

    @Mock
    private EvmSigsVerifier evmSigsVerifier;

    @Mock
    private GlobalDynamicProperties globalDynamicProperties;

    private HederaSelfDestructOperationV046 subject;

    @BeforeEach
    void setUp() {
        subject = new HederaSelfDestructOperationV046(
                gasCalculator, txnCtx, addressValidator, evmSigsVerifier, a -> false, globalDynamicProperties);

        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(gasCalculator.selfDestructOperationGasCost(any(), eq(Wei.ONE))).willReturn(2L);
    }

    @Test
    void reversionForStaticCallWithIllegalStateChange() {
        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(frame.isStatic()).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE, opResult.getHaltReason());
    }

    @Test
    void rejectsSelfDestructToSelf() {
        givenRubberstampValidator();

        given(frame.getStackItem(0)).willReturn(eip1014Address);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.SELF_DESTRUCT_TO_SELF, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void rejectsSelfDestructIfTreasury() {
        givenRubberstampValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(worldUpdater.contractIsTokenTreasury(eip1014Address)).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.CONTRACT_IS_TREASURY, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void rejectsSelfDestructIfContractHasAnyTokenBalance() {
        givenRubberstampValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(worldUpdater.contractHasAnyBalance(eip1014Address)).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void rejectsSelfDestructIfContractHasAnyNfts() {
        givenRubberstampValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(worldUpdater.contractOwnsNfts(eip1014Address)).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void rejectsSelfDestructIfBeneficiaryHasNoActiveSig() {
        givenRubberstampValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(frame.getContractAddress()).willReturn(eip1014Address);
        given(worldUpdater.contractOwnsNfts(eip1014Address)).willReturn(false);
        given(evmSigsVerifier.hasActiveKeyOrNoReceiverSigReq(
                        false, beneficiaryMirror, eip1014Address, null, ContractCall))
                .willReturn(false);
        given(worldUpdater.get(beneficiaryMirror)).willReturn(account);
        given(account.getAddress()).willReturn(beneficiaryMirror);
        given(globalDynamicProperties.callsToNonExistingEntitiesEnabled(any())).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.INVALID_SIGNATURE, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void rejectsDelegateCallSelfDestructIfBeneficiaryHasNoActiveSig() {
        givenRubberstampValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        given(frame.getContractAddress()).willReturn(Address.ALTBN128_MUL);
        given(worldUpdater.contractOwnsNfts(eip1014Address)).willReturn(false);
        given(evmSigsVerifier.hasActiveKeyOrNoReceiverSigReq(
                        true, beneficiaryMirror, eip1014Address, null, ContractCall))
                .willReturn(false);
        given(worldUpdater.get(beneficiaryMirror)).willReturn(account);
        given(account.getAddress()).willReturn(beneficiaryMirror);
        given(globalDynamicProperties.callsToNonExistingEntitiesEnabled(any())).willReturn(true);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.INVALID_SIGNATURE, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void executeInvalidSolidityAddress() {
        givenRejectingValidator();

        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);

        final var opResult = subject.execute(frame, evm);

        assertEquals(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS, opResult.getHaltReason());
        assertEquals(2L, opResult.getGasCost());
    }

    @Test
    void haltsWhenBeneficiaryIsSystemAccount() {
        // given
        subject = new HederaSelfDestructOperationV046(
                gasCalculator, txnCtx, addressValidator, evmSigsVerifier, a -> true, globalDynamicProperties);
        final var beneficiaryMirror = beneficiary.toEvmAddress();
        given(frame.getStackItem(0)).willReturn(beneficiaryMirror);
        given(frame.getRecipientAddress()).willReturn(eip1014Address);
        // when
        final var result = subject.execute(frame, evm);
        // then
        assertEquals(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS, result.getHaltReason());
        assertEquals(2L, result.getGasCost());
    }

    private void givenRubberstampValidator() {
        given(addressValidator.test(any(), any())).willReturn(true);
    }

    private void givenRejectingValidator() {
        given(addressValidator.test(any(), any())).willReturn(false);
    }
}
