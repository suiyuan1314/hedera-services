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

package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A strategy interface to allow a dispatcher to optionally set the verification status of a
 * "simple" {@link Key.KeyOneOfType#CONTRACT_ID},
 * {@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}, or
 * even cryptographic key.
 *
 * <p>The strategy has the option to delegate back to the cryptographic verifications
 * already computed by the app in pre-handle and/or handle workflows by returning
 * {@link Decision#DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION}.
 *
 * <p>Because it possible for the {@code tokenTransfer()} system contract to need to amend
 * its dispatched transaction based on the results of signature verifications, the strategy
 * also has the option to return an amended transaction body when this occurs.
 */
public interface VerificationStrategy {
    enum Decision {
        VALID,
        INVALID,
        DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION
    }

    /**
     * Returns a decision on whether to verify the signature of a primitive key. Note
     * this signature may be implicit in the case of a {@link Key.KeyOneOfType#CONTRACT_ID}
     * or {@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}; such keys have active
     * signatures based on the sender address of the EVM message frame.
     *
     * <p>Recall a <i>primitive</i> key is one of the below key types:
     * <ul>
     *     <li>{@link Key.KeyOneOfType#CONTRACT_ID}</li>
     *     <li>{@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}</li>
     *     <li>{@link Key.KeyOneOfType#ED25519}</li>
     *     <li>{@link Key.KeyOneOfType#ECDSA_SECP256K1}</li>
     * </ul>
     * C.f. {@link #isPrimitive(Key)}.
     *
     * @param key the key whose signature is to be verified
     * @return a decision on whether to verify the signature, or delegate back to the crypto engine results
     */
    Decision decideForPrimitive(@NonNull Key key);

    /**
     * Returns a predicate that tests whether a given key is a valid signature for a given key
     * given this strategy within the given {@link HandleContext}.
     *
     * @param context the context in which this strategy will be used
     * @param maybeEthSenderKey the key that is the sender of the EVM message, if known
     * @return a predicate that tests whether a given key is a valid signature for a given key
     */
    default Predicate<Key> asSignatureTestIn(
            @NonNull final HandleContext context, @Nullable final Key maybeEthSenderKey) {
        requireNonNull(context);
        return new Predicate<>() {
            @Override
            public boolean test(@NonNull final Key key) {
                requireNonNull(key);
                return switch (key.key().kind()) {
                    case KEY_LIST -> {
                        final var keys = key.keyListOrThrow().keys();
                        for (final var childKey : keys) {
                            if (!test(childKey)) {
                                yield false;
                            }
                        }
                        yield !keys.isEmpty();
                    }
                    case THRESHOLD_KEY -> {
                        final var thresholdKey = key.thresholdKeyOrThrow();
                        final var keyList = thresholdKey.keysOrElse(KeyList.DEFAULT);
                        final var keys = keyList.keys();
                        final var threshold = thresholdKey.threshold();
                        final var clampedThreshold = Math.max(1, Math.min(threshold, keys.size()));
                        var passed = 0;
                        for (final var childKey : keys) {
                            if (test(childKey)) {
                                passed++;
                            }
                            if (passed >= clampedThreshold) {
                                yield true;
                            }
                        }
                        yield false;
                    }
                    default -> {
                        if (isPrimitive(key)) {
                            yield switch (decideForPrimitive(key)) {
                                case VALID -> true;
                                case INVALID -> false;
                                    // Note the EthereumTransaction sender's key has necessarily signed
                                case DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION -> Objects.equals(key, maybeEthSenderKey)
                                        || context.verificationFor(key).passed();
                            };
                        }
                        yield false;
                    }
                };
            }
        };
    }

    /**
     * Returns whether the given key is a primitive key.
     *
     * @param key the key to test
     * @return whether the given key is a primitive key
     */
    static boolean isPrimitive(@NonNull final Key key) {
        requireNonNull(key);
        return switch (key.key().kind()) {
            case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, ED25519, ECDSA_SECP256K1 -> true;
            default -> false;
        };
    }
}
