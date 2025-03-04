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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Basic implementation support for a {@link CallTranslator} that returns a translated
 * call when the {@link AbstractCallAttempt} matches and null otherwise.
 */
public abstract class AbstractCallTranslator<T extends AbstractCallAttempt> implements CallTranslator<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Call translateCallAttempt(@NonNull final T attempt) {
        requireNonNull(attempt);
        if (matches(attempt)) {
            return callFrom(attempt);
        }
        return null;
    }
}
