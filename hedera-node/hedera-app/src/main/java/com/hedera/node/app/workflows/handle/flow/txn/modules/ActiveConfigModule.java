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

package com.hedera.node.app.workflows.handle.flow.txn.modules;

import com.hedera.node.app.workflows.handle.flow.txn.UserTxnScope;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The module that provides the active configuration dependencies. This should be the same in UserTxnScope and
 * can be accessed by ChildDispatchScope and UserDispatchScope.
 */
@Module
public interface ActiveConfigModule {
    @Provides
    @UserTxnScope
    static Configuration provideConfiguration(@NonNull ConfigProvider configProvider) {
        return configProvider.getConfiguration();
    }

    @Provides
    @UserTxnScope
    static HederaConfig provideHederaConfig(@NonNull Configuration configuration) {
        return configuration.getConfigData(HederaConfig.class);
    }
}
