/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.mono.store.contracts.precompile.impl;

import static com.hedera.node.app.service.evm.utils.ValidationUtils.validateTrue;
import static com.hedera.node.app.service.mono.store.contracts.precompile.utils.PrecompilePricingUtils.GasCostType.ASSOCIATE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.node.app.service.mono.context.SideEffectsTracker;
import com.hedera.node.app.service.mono.contracts.sources.EvmSigsVerifier;
import com.hedera.node.app.service.mono.fees.FeeCalculator;
import com.hedera.node.app.service.mono.ledger.accounts.ContractAliases;
import com.hedera.node.app.service.mono.store.contracts.WorldLedgers;
import com.hedera.node.app.service.mono.store.contracts.precompile.InfrastructureFactory;
import com.hedera.node.app.service.mono.store.contracts.precompile.Precompile;
import com.hedera.node.app.service.mono.store.contracts.precompile.SyntheticTxnFactory;
import com.hedera.node.app.service.mono.store.contracts.precompile.codec.Association;
import com.hedera.node.app.service.mono.store.contracts.precompile.utils.KeyActivationUtils;
import com.hedera.node.app.service.mono.store.contracts.precompile.utils.PrecompilePricingUtils;
import com.hedera.node.app.service.mono.store.models.Id;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Objects;
import javax.inject.Provider;
import org.hyperledger.besu.evm.frame.MessageFrame;

/* --- Constructor functional interfaces for mocking --- */
public abstract class AbstractAssociatePrecompile implements Precompile {
    private static final String ASSOCIATE_FAILURE_MESSAGE = "Invalid full prefix for associate precompile!";
    private final WorldLedgers ledgers;
    private final ContractAliases aliases;
    private final EvmSigsVerifier sigsVerifier;
    private final SideEffectsTracker sideEffects;
    private final InfrastructureFactory infrastructureFactory;
    protected final PrecompilePricingUtils pricingUtils;
    protected TransactionBody.Builder transactionBody;
    protected Association associateOp;
    protected final SyntheticTxnFactory syntheticTxnFactory;
    protected final Provider<FeeCalculator> feeCalculator;

    protected AbstractAssociatePrecompile(
            final WorldLedgers ledgers,
            final ContractAliases aliases,
            final EvmSigsVerifier sigsVerifier,
            final SideEffectsTracker sideEffects,
            final SyntheticTxnFactory syntheticTxnFactory,
            final InfrastructureFactory infrastructureFactory,
            final PrecompilePricingUtils pricingUtils,
            final Provider<FeeCalculator> feeCalculator) {
        this.ledgers = ledgers;
        this.aliases = aliases;
        this.sigsVerifier = sigsVerifier;
        this.sideEffects = sideEffects;
        this.pricingUtils = pricingUtils;
        this.syntheticTxnFactory = syntheticTxnFactory;
        this.infrastructureFactory = infrastructureFactory;
        this.feeCalculator = feeCalculator;
    }

    @Override
    public void run(final MessageFrame frame) {
        // --- Check required signatures ---
        final var accountId =
                Id.fromGrpcAccount(Objects.requireNonNull(associateOp).accountId());
        final var hasRequiredSigs = KeyActivationUtils.validateKey(
                frame, accountId.asEvmAddress(), sigsVerifier::hasActiveKey, ledgers, aliases, TokenAssociateToAccount);
        validateTrue(hasRequiredSigs, INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, ASSOCIATE_FAILURE_MESSAGE);

        // --- Build the necessary infrastructure to execute the transaction ---
        final var accountStore = infrastructureFactory.newAccountStore(ledgers.accounts());
        final var tokenStore = infrastructureFactory.newTokenStore(
                accountStore, sideEffects, ledgers.tokens(), ledgers.nfts(), ledgers.tokenRels());

        // --- Execute the transaction and capture its results ---
        final var associateLogic = infrastructureFactory.newAssociateLogic(accountStore, tokenStore);
        final var validity = associateLogic.validateSyntax(transactionBody.build());
        validateTrue(validity == OK, validity);
        associateLogic.associate(accountId, associateOp.tokenIds());
    }

    @Override
    public long getMinimumFeeInTinybars(final Timestamp consensusTime) {
        return pricingUtils.getMinimumPriceInTinybars(ASSOCIATE, consensusTime);
    }
}
