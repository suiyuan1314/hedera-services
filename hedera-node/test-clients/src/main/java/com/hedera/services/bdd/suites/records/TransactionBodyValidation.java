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

package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertEventuallyPasses;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;

import com.hedera.services.bdd.junit.support.validators.TransactionBodyValidator;
import com.hedera.services.bdd.suites.HapiSuite;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class TransactionBodyValidation extends HapiSuite {
    private static final Logger log = LogManager.getLogger(TransactionBodyValidation.class);

    public static void main(String... args) {
        new TransactionBodyValidation().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(validateTransactionBodySet());
    }

    final Stream<DynamicTest> validateTransactionBodySet() {
        return defaultHapiSpec("TransactionBodyValidation")
                .given()
                .when()
                .then(sourcing(() -> assertEventuallyPasses(new TransactionBodyValidator(), Duration.ofMillis(2_100))));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
