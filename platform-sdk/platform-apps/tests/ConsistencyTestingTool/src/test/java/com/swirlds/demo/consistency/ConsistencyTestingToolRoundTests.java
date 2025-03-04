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

package com.swirlds.demo.consistency;

import static com.swirlds.common.utility.ByteUtils.longToByteArray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.platform.event.EventPayload.PayloadOneOfType;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.consensus.GraphGenerations;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.platform.system.transaction.ConsensusTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ConsistencyTestingToolRound}
 */
class ConsistencyTestingToolRoundTests {
    private static final long ROUND_NUMBER = 22L;
    private static final long STATE_VALUE = 33L;
    private static final List<Long> TRANSACTION_CONTENTS = List.of(4L, 55L, 667L);

    private final ConsistencyTestingToolRound testRound =
            new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE, TRANSACTION_CONTENTS);

    /**
     * Build a mock round with specified event contents and round received
     *
     * @param eventContents a list of lists, where each individual list represents the transactions in an event in the
     *                      output round
     * @param roundReceived the round received of the mock round
     * @return a mock round with the specified event contents and round received
     */
    private static Round buildMockRound(final List<List<Long>> eventContents, final long roundReceived) {
        final List<EventImpl> mockEvents = new ArrayList<>();

        eventContents.forEach(event -> {
            final List<ConsensusTransaction> mockTransactions = new ArrayList<>();

            event.forEach(content -> {
                final ConsensusTransactionImpl transaction = mock(ConsensusTransactionImpl.class);
                final Bytes bytes = Bytes.wrap(longToByteArray(content));
                final OneOf<PayloadOneOfType> payload = new OneOf<>(PayloadOneOfType.APPLICATION_PAYLOAD, bytes);
                Mockito.when(transaction.getPayload()).thenReturn(payload);
                Mockito.when(transaction.getApplicationPayload()).thenReturn(bytes);
                Mockito.when(transaction.isSystem()).thenReturn(false);
                mockTransactions.add(transaction);
            });

            final ConsensusTransaction[] eventTransactionArray = new ConsensusTransactionImpl[eventContents.size()];
            mockTransactions.toArray(eventTransactionArray);
            final EventImpl mockEvent = mock(EventImpl.class);
            Mockito.when(mockEvent.getRoundReceived()).thenReturn(roundReceived);
            Mockito.when(mockEvent.getBaseEvent()).thenReturn(mock(GossipEvent.class));
            Mockito.when(mockEvent.consensusTransactionIterator()).thenReturn(mockTransactions.iterator());
            Mockito.when(mockEvent.getTransactions()).thenReturn((ConsensusTransactionImpl[]) eventTransactionArray);

            mockEvents.add(mockEvent);
        });
        final ConsensusSnapshot mockSnapshot = mock(ConsensusSnapshot.class);
        Mockito.when(mockSnapshot.round()).thenReturn(roundReceived);

        return new ConsensusRound(
                mock(AddressBook.class),
                mockEvents,
                mock(EventImpl.class),
                mock(GraphGenerations.class),
                mock(EventWindow.class),
                mockSnapshot,
                false);
    }

    @Test
    @DisplayName("toString test")
    void toStringTest() {
        final String exampleRoundString = "Round Number: " + ROUND_NUMBER + "; Current State: " + STATE_VALUE
                + "; Transactions: " + TRANSACTION_CONTENTS + "\n";

        assertEquals(exampleRoundString, testRound.toString());
    }

    @Test
    @DisplayName("toString followed by fromString yields equivalent object")
    void toStringRoundTrip() {
        assertEquals(testRound, ConsistencyTestingToolRound.fromString(testRound.toString()));
    }

    @Test
    @DisplayName("fromRound")
    void fromRound() {
        // build a mock round that should equal our test round when converted
        // the transactions from the test round are split across 2 events in this mock round
        final Round equivalentRound = buildMockRound(
                List.of(TRANSACTION_CONTENTS.subList(0, 2), TRANSACTION_CONTENTS.subList(2, 3)), ROUND_NUMBER);

        assertEquals(testRound, ConsistencyTestingToolRound.fromRound(equivalentRound, STATE_VALUE));
    }

    @Test
    @DisplayName("equals")
    void equalsTests() {
        assertFalse(testRound.equals(null), "Round should not equal null");
        assertNotEquals(testRound, new Object(), "Round shouldn't equal an object of a different type");
        assertEquals(testRound, testRound, "Round should equal itself");

        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER - 1, STATE_VALUE, testRound.transactionsContents()),
                "Different round number should cause inequality");

        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE - 1, testRound.transactionsContents()),
                "Different state value should cause inequality");

        final List<Long> differentTransactions = new ArrayList<>(testRound.transactionsContents());
        differentTransactions.add(123L);
        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE, differentTransactions),
                "Different transactions should cause inequality");
    }

    @Test
    @DisplayName("compareTo")
    void compareToTests() {
        final ConsistencyTestingToolRound otherRound =
                new ConsistencyTestingToolRound(ROUND_NUMBER + 1, STATE_VALUE, TRANSACTION_CONTENTS);

        assertEquals(0, testRound.compareTo(testRound));
        assertEquals(-1, testRound.compareTo(otherRound));
        assertEquals(1, otherRound.compareTo(testRound));
    }
}
