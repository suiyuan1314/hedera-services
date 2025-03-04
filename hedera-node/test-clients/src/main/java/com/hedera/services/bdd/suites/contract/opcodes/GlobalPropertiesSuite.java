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

package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.*;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PROPS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class GlobalPropertiesSuite {

    private static final Logger LOG = LogManager.getLogger(GlobalPropertiesSuite.class);
    private static final String CONTRACT = "GlobalProperties";
    private static final String GET_CHAIN_ID = "getChainID";
    private static final String GET_BASE_FEE = "getBaseFee";
    private static final String GET_GAS_LIMIT = "getGasLimit";

    @HapiTest
    final Stream<DynamicTest> chainIdWorks() {
        final var defaultChainId = BigInteger.valueOf(295L);
        final var devChainId = BigInteger.valueOf(298L);
        final Set<Object> acceptableChainIds = Set.of(devChainId, defaultChainId);
        return defaultHapiSpec("chainIdWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, GET_CHAIN_ID).via("chainId"))
                .then(
                        getTxnRecord("chainId")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, GET_CHAIN_ID, CONTRACT),
                                                        isOneOfLiteral(acceptableChainIds)))),
                        contractCallLocal(CONTRACT, GET_CHAIN_ID)
                                .nodePayment(1_234_567)
                                .has(ContractFnResultAsserts.resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_CHAIN_ID, CONTRACT),
                                                isOneOfLiteral(acceptableChainIds))));
    }

    @HapiTest
    final Stream<DynamicTest> baseFeeWorks() {
        final var expectedBaseFee = BigInteger.valueOf(0);
        return defaultHapiSpec("baseFeeWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, GET_BASE_FEE).via("baseFee"))
                .then(
                        getTxnRecord("baseFee")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, GET_BASE_FEE, CONTRACT),
                                                        isLiteralResult(new Object[] {BigInteger.valueOf(0)})))),
                        contractCallLocal(CONTRACT, GET_BASE_FEE)
                                .nodePayment(1_234_567)
                                .has(ContractFnResultAsserts.resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_BASE_FEE, CONTRACT),
                                                ContractFnResultAsserts.isLiteralResult(
                                                        new Object[] {expectedBaseFee}))));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> coinbaseWorks() {
        return defaultHapiSpec("coinbaseWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, "getCoinbase").via("coinbase"))
                .then(withOpContext((spec, opLog) -> {
                    final var expectedCoinbase =
                            parsedToByteString(DEFAULT_PROPS.fundingAccount().getAccountNum());

                    final var callLocal = contractCallLocal(CONTRACT, "getCoinbase")
                            .nodePayment(1_234_567)
                            .saveResultTo("callLocalCoinbase");
                    final var callRecord = getTxnRecord("coinbase");

                    allRunFor(spec, callRecord, callLocal);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var callLocalResult = spec.registry().getBytes("callLocalCoinbase");
                    Assertions.assertEquals(recordResult.getContractCallResult(), expectedCoinbase);
                    Assertions.assertArrayEquals(callLocalResult, expectedCoinbase.toByteArray());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> gasLimitWorks() {
        final var gasLimit = Long.parseLong(HapiSpecSetup.getDefaultNodeProps().get("contracts.maxGasPerSec"));
        return defaultHapiSpec("gasLimitWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, GET_GAS_LIMIT).via("gasLimit").gas(gasLimit))
                .then(
                        getTxnRecord("gasLimit")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                        isLiteralResult(new Object[] {BigInteger.valueOf(gasLimit)})))),
                        contractCallLocal(CONTRACT, GET_GAS_LIMIT)
                                .gas(gasLimit)
                                .nodePayment(1_234_567)
                                .has(ContractFnResultAsserts.resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                ContractFnResultAsserts.isLiteralResult(
                                                        new Object[] {BigInteger.valueOf(gasLimit)}))));
    }
}
