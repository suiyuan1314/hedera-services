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

package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CAE_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateRecordBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeAccumulator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeUpdateHandlerTest extends AddressBookTestBase {

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock
    private NodeCreateRecordBuilder recordBuilder;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private FeeAccumulator feeAccumulator;

    @Mock
    private StoreMetricsService storeMetricsService;

    private TransactionBody txn;
    private NodeUpdateHandler subject;

    private AddressBookValidator addressBookValidator;

    @BeforeEach
    void setUp() {
        addressBookValidator = new AddressBookValidator();
        subject = new NodeUpdateHandler(addressBookValidator);
    }

    @Test
    @DisplayName("pureChecks fail when nodeId is negagive")
    void nodeIdCannotNegative() {
        txn = new NodeUpdateBuilder().build();
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(txn));
        assertThat(INVALID_NODE_ID).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks fail when accountId not set")
    void accountIdNeedSet() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(AccountID.DEFAULT)
                .build();
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(txn));
        assertThat(INVALID_NODE_ACCOUNT_ID).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks fail when accountId is alias")
    void accountIdCannotAlias() {
        txn = new NodeUpdateBuilder().withNodeId(1).withAccountId(alias).build();
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(txn));
        assertThat(INVALID_NODE_ACCOUNT_ID).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks fail when gossipCaCertificate empty")
    void gossipCaCertificateCannotEmpty() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withGossipCaCertificate(Bytes.EMPTY)
                .build();
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(txn));
        assertThat(INVALID_GOSSIP_CAE_CERTIFICATE).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks succeeds when expected attributes are specified")
    void pureCheckPass() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withGossipCaCertificate(Bytes.wrap("cert"))
                .build();
        assertDoesNotThrow(() -> subject.pureChecks(txn));
    }

    @Test
    void nodetIdMustInState() {
        txn = new NodeUpdateBuilder().withNodeId(2L).build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(handleContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_ID, msg.getStatus());
    }

    @Test
    void accountIdMustInState() {
        txn = new NodeUpdateBuilder().withNodeId(1L).withAccountId(accountId).build();
        given(accountStore.contains(accountId)).willReturn(false);
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(handleContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID, msg.getStatus());
    }

    @Test
    void failsWhenDescriptionTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Description")
                .build();
        setupHandle();

        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_DESCRIPTION, msg.getStatus());
    }

    @Test
    void failsWhenDescriptionContainZeroByte() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Des\0cription")
                .build();
        setupHandle();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 12)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_DESCRIPTION, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointTooSmall() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointHaveIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint4))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveEmptyIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint5))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveZeroIp() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint6))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenServiceEndpointTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build();
        setupHandle();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_SERVICE_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint4))
                .build();
        setupHandle();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.IP_FQDN_CANNOT_BE_SET_FOR_SAME_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointFQDNTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .build();
        setupHandle();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .withValue("nodes.maxFqdnSize", 4)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.FQDN_SIZE_TOO_LARGE, msg.getStatus());
    }

    @Test
    void hanldeWorkAsExpected() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Description")
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .withGossipCaCertificate(Bytes.wrap("cert"))
                .withGrpcCertificateHash(Bytes.wrap("hash"))
                .build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithMoreNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 12)
                .withValue("nodes.maxGossipEndpoint", 4)
                .withValue("nodes.maxServiceEndpoint", 3)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(accountStore.contains(accountId)).willReturn(true);
        given(handleContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        final var updatedNode = writableStore.get(1L);
        assertNotNull(updatedNode);
        assertEquals(1, updatedNode.nodeId());
        assertEquals("Description", updatedNode.description());
        assertArrayEquals(
                (List.of(endpoint1, endpoint2)).toArray(),
                updatedNode.gossipEndpoint().toArray());
        assertArrayEquals(
                (List.of(endpoint1, endpoint3)).toArray(),
                updatedNode.serviceEndpoint().toArray());
        assertArrayEquals("cert".getBytes(), updatedNode.gossipCaCertificate().toByteArray());
        assertArrayEquals("hash".getBytes(), updatedNode.grpcCertificateHash().toByteArray());
    }

    @Test
    void nothingHappensIfUpdateHasNoop() {
        txn = new NodeUpdateBuilder().withNodeId(1L).build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        final var updatedNode = writableStore.get(1L);
        assertEquals(node, updatedNode);
    }

    @Test
    void preHandleDoesNothing() {
        assertDoesNotThrow(() -> subject.preHandle(mock(PreHandleContext.class)));
    }

    private void setupHandle() {
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(accountStore.contains(accountId)).willReturn(true);
        given(handleContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
    }

    private class NodeUpdateBuilder {
        private long nodeId = -1L;
        private AccountID accountId = null;
        private String description = null;
        private List<ServiceEndpoint> gossipEndpoint = null;

        private List<ServiceEndpoint> serviceEndpoint = null;

        private Bytes gossipCaCertificate = null;

        private Bytes grpcCertificateHash = null;

        private NodeUpdateBuilder() {}

        public TransactionBody build() {
            final var txnId = TransactionID.newBuilder().accountID(payerId).transactionValidStart(consensusTimestamp);
            final var txnBody = NodeUpdateTransactionBody.newBuilder();
            txnBody.nodeId(nodeId);
            if (accountId != null) {
                txnBody.accountId(accountId);
            }
            if (description != null) {
                txnBody.description(description);
            }
            if (gossipEndpoint != null) {
                txnBody.gossipEndpoint(gossipEndpoint);
            }
            if (serviceEndpoint != null) {
                txnBody.serviceEndpoint(serviceEndpoint);
            }
            if (gossipCaCertificate != null) {
                txnBody.gossipCaCertificate(gossipCaCertificate);
            }
            if (grpcCertificateHash != null) {
                txnBody.grpcCertificateHash(grpcCertificateHash);
            }

            return TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .nodeUpdate(txnBody.build())
                    .build();
        }

        public NodeUpdateBuilder withNodeId(final long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public NodeUpdateBuilder withAccountId(final AccountID accountId) {
            this.accountId = accountId;
            return this;
        }

        public NodeUpdateBuilder withDescription(final String description) {
            this.description = description;
            return this;
        }

        public NodeUpdateBuilder withGossipEndpoint(final List<ServiceEndpoint> gossipEndpoint) {
            this.gossipEndpoint = gossipEndpoint;
            return this;
        }

        public NodeUpdateBuilder withServiceEndpoint(final List<ServiceEndpoint> serviceEndpoint) {
            this.serviceEndpoint = serviceEndpoint;
            return this;
        }

        public NodeUpdateBuilder withGossipCaCertificate(final Bytes gossipCaCertificate) {
            this.gossipCaCertificate = gossipCaCertificate;
            return this;
        }

        public NodeUpdateBuilder withGrpcCertificateHash(final Bytes grpcCertificateHash) {
            this.grpcCertificateHash = grpcCertificateHash;
            return this;
        }
    }
}
