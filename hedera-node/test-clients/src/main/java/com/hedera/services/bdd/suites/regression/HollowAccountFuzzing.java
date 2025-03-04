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

package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountFuzzingFactory.hollowAccountFuzzingTest;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountFuzzingFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class HollowAccountFuzzing {
    private static final String PROPERTIES = "hollow-account-fuzzing.properties";

    @HapiTest
    final Stream<DynamicTest> hollowAccountFuzzing() {
        return defaultHapiSpec("HollowAccountFuzzing")
                .given(initOperations())
                .when()
                .then(runWithProvider(hollowAccountFuzzingTest(PROPERTIES))
                        .maxOpsPerSec(10)
                        .lasting(10L, TimeUnit.SECONDS));
    }
}
