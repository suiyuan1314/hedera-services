/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.queries.meta.HapiGetReceipt;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * ! WARNING - Requires a RecordCache TTL of 3s to pass !
 *
 * <p>Even with a 3s TTL, a number of these tests fail. FUTURE: revisit
 * */
@Tag(CRYPTO)
public class TxnRecordRegression {
    @HapiTest
    final Stream<DynamicTest> recordsStillQueryableWithDeletedPayerId() {
        return defaultHapiSpec("DeletedAccountRecordsUnavailableAfterTtl")
                .given(
                        cryptoCreate("toBeDeletedPayer"),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                                .payingWith("toBeDeletedPayer")
                                .via("recordTxn"))
                .when(cryptoDelete("toBeDeletedPayer"))
                .then(getTxnRecord("recordTxn"));
    }

    @HapiTest
    final Stream<DynamicTest> returnsInvalidForUnspecifiedTxnId() {
        return defaultHapiSpec("ReturnsInvalidForUnspecifiedTxnId")
                .given()
                .when()
                .then(getTxnRecord("").useDefaultTxnId().hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> recordNotFoundIfNotInPayerState() {
        return defaultHapiSpec("RecordNotFoundIfNotInPayerState")
                .given(
                        cryptoCreate("misc").via("success"),
                        usableTxnIdNamed("rightAccountWrongId").payerId("misc"))
                .when()
                .then(getTxnRecord("rightAccountWrongId").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> recordUnavailableBeforeConsensus() {
        return defaultHapiSpec("RecordUnavailableBeforeConsensus")
                .given()
                .when()
                .then(
                        cryptoCreate("misc").via("success").balance(1_000L).deferStatusResolution(),
                        getTxnRecord("success").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> recordUnavailableIfRejectedInPrecheck() {
        return defaultHapiSpec("RecordUnavailableIfRejectedInPrecheck")
                .given(
                        cryptoCreate("misc").balance(1000L),
                        usableTxnIdNamed("failingTxn").payerId("misc"))
                .when(cryptoCreate("nope")
                        .payingWith("misc")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)
                        .txnId("failingTxn"))
                .then(getTxnRecord("failingTxn").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> getReceiptReturnsInvalidForUnspecifiedTxnId() {
        return hapiTest(getReceipt("").useDefaultTxnId().hasAnswerOnlyPrecheck(INVALID_TRANSACTION_ID));
    }

    @HapiTest
    final Stream<DynamicTest> returnsNotSupportedForMissingOp() {
        return defaultHapiSpec("ReturnsNotSupportedForMissingOp")
                .given(cryptoCreate("misc").via("success").balance(1_000L))
                .when()
                .then(getReceipt("success").forgetOp().hasAnswerOnlyPrecheck(NOT_SUPPORTED));
    }

    // (FUTURE) Re-enable once we have a way to manipulate time in the test network
    final Stream<DynamicTest> receiptUnavailableAfterCacheTtl() {
        return defaultHapiSpec("ReceiptUnavailableAfterCacheTtl")
                .given()
                .when()
                .then(
                        // This extra three minutes isn't worth adding to mono-service checks, but
                        // especially as it fails now against mod-service, is worthwhile as HapiTest
                        ifHapiTest(
                                cryptoCreate("misc").via("success").balance(1_000L),
                                sleepFor(181_000L),
                                // Run a transaction to give receipt expiration a chance to occur
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                                getReceipt("success").hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND)));
    }

    @HapiTest
    final Stream<DynamicTest> receiptUnknownBeforeConsensus() {
        return defaultHapiSpec("ReceiptUnknownBeforeConsensus")
                .given()
                .when()
                .then(
                        cryptoCreate("misc").via("success").balance(1_000L).deferStatusResolution(),
                        getReceipt("success").hasPriorityStatus(UNKNOWN));
    }

    @HapiTest
    final Stream<DynamicTest> receiptAvailableWithinCacheTtl() {
        return defaultHapiSpec("ReceiptAvailableWithinCacheTtl")
                .given(cryptoCreate("misc").via("success").balance(1_000L))
                .when()
                .then(getReceipt("success").hasPriorityStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> receiptUnavailableIfRejectedInPrecheck() {
        return defaultHapiSpec("ReceiptUnavailableIfRejectedInPrecheck")
                .given(cryptoCreate("misc").balance(1_000L))
                .when(cryptoCreate("nope")
                        .payingWith("misc")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)
                        .via("failingTxn"))
                .then(getReceipt("failingTxn").hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> receiptNotFoundOnUnknownTransactionID() {
        return defaultHapiSpec("receiptNotFoundOnUnknownTransactionID")
                .given()
                .when()
                .then(withOpContext((spec, ctxLog) -> {
                    final HapiGetReceipt op =
                            getReceipt(spec.txns().defaultTransactionID()).hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND);
                    CustomSpecAssert.allRunFor(spec, op);
                }));
    }
}
