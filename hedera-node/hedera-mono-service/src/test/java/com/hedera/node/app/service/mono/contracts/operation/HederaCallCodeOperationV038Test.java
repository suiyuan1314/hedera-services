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

import static com.hedera.node.app.service.mono.contracts.operation.CommonCallSetup.commonSetup;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.evm.contracts.operations.HederaExceptionalHaltReason;
import com.hedera.node.app.service.mono.context.properties.GlobalDynamicProperties;
import com.hedera.node.app.service.mono.contracts.sources.EvmSigsVerifier;
import com.hedera.node.app.service.mono.store.contracts.HederaStackedWorldStateUpdater;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaCallCodeOperationV038Test {
    private final String EVM_VERSION_0_38 = "v0.38";

    @Mock
    private GasCalculator calc;

    @Mock
    private MessageFrame evmMsgFrame;

    @Mock
    private EVM evm;

    @Mock
    private HederaStackedWorldStateUpdater worldUpdater;

    @Mock
    private Account acc;

    @Mock
    private EvmSigsVerifier sigsVerifier;

    @Mock
    private BiPredicate<Address, MessageFrame> addressValidator;

    @Mock
    private Predicate<Address> systemAccountDetector;

    @Mock
    private GlobalDynamicProperties globalDynamicProperties;

    private final long cost = 100L;

    private HederaCallCodeOperationV038 subject;

    @BeforeEach
    void setup() {
        subject = new HederaCallCodeOperationV038(
                sigsVerifier, calc, addressValidator, systemAccountDetector, globalDynamicProperties);
        commonSetup(evmMsgFrame, worldUpdater, acc);
    }

    @Test
    void haltWithInvalidAddr() {
        given(worldUpdater.get(any())).willReturn(null);
        given(calc.callOperationGasCost(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        anyBoolean()))
                .willReturn(cost);
        given(evmMsgFrame.getStackItem(0)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(1)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(2)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(3)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(4)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(5)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(6)).willReturn(Bytes.EMPTY);
        given(addressValidator.test(any(), any())).willReturn(false);
        given(globalDynamicProperties.callsToNonExistingEntitiesEnabled(any())).willReturn(false);

        var opRes = subject.execute(evmMsgFrame, evm);

        assertEquals(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS, opRes.getHaltReason());
        assertEquals(cost, opRes.getGasCost());
    }

    @Test
    void executesAsExpected() {
        given(calc.callOperationGasCost(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        anyBoolean()))
                .willReturn(cost);
        // and:
        given(evmMsgFrame.getStackItem(0)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(1)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(2)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(3)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(4)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(5)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(6)).willReturn(Bytes.EMPTY);
        // and:
        given(evmMsgFrame.stackSize()).willReturn(20);
        given(evmMsgFrame.getRemainingGas()).willReturn(cost);
        given(evmMsgFrame.getDepth()).willReturn(1025);
        given(evmMsgFrame.getContractAddress()).willReturn(Address.ALTBN128_ADD);
        given(evmMsgFrame.getRecipientAddress()).willReturn(Address.ALTBN128_ADD);
        given(worldUpdater.get(any())).willReturn(acc);
        given(acc.getBalance()).willReturn(Wei.of(100));
        given(calc.gasAvailableForChildCall(any(), anyLong(), anyBoolean())).willReturn(10L);
        given(acc.getAddress()).willReturn(Address.ZERO);
        given(sigsVerifier.hasActiveKeyOrNoReceiverSigReq(Mockito.anyBoolean(), any(), any(), any(), eq(ContractCall)))
                .willReturn(true);
        given(addressValidator.test(any(), any())).willReturn(true);
        given(globalDynamicProperties.callsToNonExistingEntitiesEnabled(any())).willReturn(false);

        var opRes = subject.execute(evmMsgFrame, evm);
        assertNull(opRes.getHaltReason());
        assertEquals(opRes.getGasCost(), cost);
    }

    @Test
    void executeHaltsWithInvalidSignature() {
        given(calc.callOperationGasCost(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        anyBoolean()))
                .willReturn(cost);
        given(calc.callOperationGasCost(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        anyBoolean()))
                .willReturn(cost);
        // and:
        given(evmMsgFrame.getStackItem(0)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(1)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(2)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(3)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(4)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(5)).willReturn(Bytes.EMPTY);
        given(evmMsgFrame.getStackItem(6)).willReturn(Bytes.EMPTY);
        // and:
        given(worldUpdater.get(any())).willReturn(acc);
        given(acc.getAddress()).willReturn(Address.ZERO);
        given(sigsVerifier.hasActiveKeyOrNoReceiverSigReq(Mockito.anyBoolean(), any(), any(), any(), eq(ContractCall)))
                .willReturn(false);
        given(addressValidator.test(any(), any())).willReturn(true);

        given(evmMsgFrame.getContractAddress()).willReturn(Address.ALTBN128_ADD);
        given(evmMsgFrame.getRecipientAddress()).willReturn(Address.ALTBN128_ADD);
        given(globalDynamicProperties.callsToNonExistingEntitiesEnabled(any())).willReturn(false);

        var opRes = subject.execute(evmMsgFrame, evm);
        assertEquals(HederaExceptionalHaltReason.INVALID_SIGNATURE, opRes.getHaltReason());
        assertEquals(opRes.getGasCost(), cost);
    }
}
