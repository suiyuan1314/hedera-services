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

package com.hedera.node.app.throttle.schemas;

import static com.hedera.node.app.service.mono.pbj.PbjConverter.toPbj;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.service.mono.pbj.PbjConverter;
import com.swirlds.state.spi.MigrationContext;
import com.swirlds.state.spi.Schema;
import com.swirlds.state.spi.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0490CongestionThrottleSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V0490CongestionThrottleSchema.class);

    public static final String THROTTLE_USAGE_SNAPSHOTS_STATE_KEY = "THROTTLE_USAGE_SNAPSHOTS";
    public static final String CONGESTION_LEVEL_STARTS_STATE_KEY = "CONGESTION_LEVEL_STARTS";
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    /**
     * These fields hold usage snapshots when migrating from a mono-service state.
     */
    private static DeterministicThrottle.UsageSnapshot[] usageSnapshots;

    private static DeterministicThrottle.UsageSnapshot gasThrottleUsageSnapshot;

    public V0490CongestionThrottleSchema() {
        super(VERSION);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY, ThrottleUsageSnapshots.PROTOBUF),
                StateDefinition.singleton(CONGESTION_LEVEL_STARTS_STATE_KEY, CongestionLevelStarts.PROTOBUF));
    }

    /** {@inheritDoc} */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (usageSnapshots != null && gasThrottleUsageSnapshot != null) {
            log.info("Migrating throttle usage snapshots");
            // For diff testing we need to initialize the throttle snapshots from the saved state
            final var throttleSnapshots = ctx.newStates().getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
            throttleSnapshots.put(new ThrottleUsageSnapshots(
                    Arrays.stream(usageSnapshots).map(PbjConverter::toPbj).toList(), toPbj(gasThrottleUsageSnapshot)));

            // Unless we find diff testing requires, for now don't bother migrating congestion level starts
            final var congestionLevelStarts = ctx.newStates().getSingleton(CONGESTION_LEVEL_STARTS_STATE_KEY);
            congestionLevelStarts.put(CongestionLevelStarts.DEFAULT);

            log.info("BBM: finished migrating congestion throttle service");
        } else if (ctx.previousVersion() == null) {
            log.info("Creating genesis throttle snapshots and congestion level starts");
            // At genesis we put empty throttle usage snapshots and
            // congestion level starts into their respective singleton
            // states just to ensure they exist
            final var throttleSnapshots = ctx.newStates().getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
            throttleSnapshots.put(ThrottleUsageSnapshots.DEFAULT);
            final var congestionLevelStarts = ctx.newStates().getSingleton(CONGESTION_LEVEL_STARTS_STATE_KEY);
            congestionLevelStarts.put(CongestionLevelStarts.DEFAULT);
        }
    }

    public static void setUsageSnapshots(DeterministicThrottle.UsageSnapshot[] usageSnapshots) {
        V0490CongestionThrottleSchema.usageSnapshots = usageSnapshots;
    }

    public static void setGasThrottleUsageSnapshot(DeterministicThrottle.UsageSnapshot gasThrottleUsageSnapshot) {
        V0490CongestionThrottleSchema.gasThrottleUsageSnapshot = gasThrottleUsageSnapshot;
    }
}
