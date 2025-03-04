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

package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl.NODES_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.MigrationContext;
import com.swirlds.state.spi.Schema;
import com.swirlds.state.spi.StateDefinition;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * General schema for the addressbook service
 * {@code V052AddressBookSchema} is used for migrating the address book on Version 0.52.0
 */
public class V052AddressBookSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V052AddressBookSchema.class);

    private static final long MAX_NODES = 100L;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(52).patch(0).build();

    public V052AddressBookSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(NODES_KEY, EntityNumber.PROTOBUF, Node.PROTOBUF, MAX_NODES));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        final WritableKVState<EntityNumber, Node> writableNodes =
                ctx.newStates().get(NODES_KEY);
        final var networkInfo = ctx.networkInfo();
        final var addressBook = networkInfo.addressBook();
        log.info("Started migrating nodes from address book");

        addressBook.forEach(nodeInfo -> {
            final var node = Node.newBuilder()
                    .nodeId(nodeInfo.nodeId())
                    .accountId(nodeInfo.accountId())
                    .description(nodeInfo.memo())
                    .gossipEndpoint(List.of(
                            ServiceEndpoint.newBuilder()
                                    .ipAddressV4(Bytes.wrap(nodeInfo.internalHostName()))
                                    .port(nodeInfo.internalPort())
                                    .build(),
                            ServiceEndpoint.newBuilder()
                                    .ipAddressV4(Bytes.wrap(nodeInfo.externalHostName()))
                                    .port(nodeInfo.externalPort())
                                    .build()))
                    .gossipCaCertificate(nodeInfo.sigCertBytes())
                    .weight(nodeInfo.stake())
                    .build();
            writableNodes.put(
                    EntityNumber.newBuilder().number(nodeInfo.nodeId()).build(), node);
        });

        if (writableNodes.isModified()) {
            ((WritableKVStateBase) writableNodes).commit();
        }
        log.info("Migrated {} nodes from address book", addressBook.size());
    }
}
