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

package com.swirlds.platform.event.branching;

import static com.swirlds.platform.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static com.swirlds.platform.event.branching.BranchDetectorTests.generateSimpleSequenceOfEvents;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * It's currently difficult to write unit tests to validate metrics and logging. The least we can do is ensure that it
 * doesn't throw an exception.
 */
class BranchReporterTests {

    @Test
    void doesNotThrowSmallAncientWindow() {

        final Randotron randotron = Randotron.create();

        final AddressBook addressBook =
                RandomAddressBookBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, addressBook);

        int ancientThreshold = randotron.nextInt(1, 1000);
        reporter.updateEventWindow(
                new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));

        final List<GossipEvent> events = new ArrayList<>();
        for (final NodeId nodeId : addressBook.getNodeIdSet()) {
            events.addAll(generateSimpleSequenceOfEvents(randotron, nodeId, ancientThreshold, 512));
        }

        for (final GossipEvent event : events) {
            reporter.reportBranch(event);

            if (randotron.nextBoolean(0.1)) {
                ancientThreshold++;
                reporter.updateEventWindow(
                        new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));
            }
            if (randotron.nextBoolean(0.1)) {
                reporter.clear();
                reporter.updateEventWindow(
                        new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));
            }
        }

        // Advance ancient window very far into the future
        ancientThreshold += 1000;
        reporter.updateEventWindow(
                new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));
    }

    @Test
    void doesNotThrowLargeAncientWindow() {
        final Randotron randotron = Randotron.create();

        final AddressBook addressBook =
                RandomAddressBookBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, addressBook);

        int ancientThreshold = randotron.nextInt(1, 1000);
        reporter.updateEventWindow(
                new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));

        final List<GossipEvent> events = new ArrayList<>();
        for (final NodeId nodeId : addressBook.getNodeIdSet()) {
            events.addAll(generateSimpleSequenceOfEvents(randotron, nodeId, ancientThreshold, 512));
        }

        for (final GossipEvent event : events) {
            reporter.reportBranch(event);

            if (randotron.nextBoolean(0.01)) {
                ancientThreshold++;
                reporter.updateEventWindow(
                        new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));
            }
        }

        // Advance ancient window very far into the future
        ancientThreshold += 1000;
        reporter.updateEventWindow(
                new EventWindow(1 /* ignored */, ancientThreshold, 1 /* ignored */, BIRTH_ROUND_THRESHOLD));
    }

    @Test
    void eventWindowMustBeSetTest() {
        final Randotron randotron = Randotron.create();

        final AddressBook addressBook =
                RandomAddressBookBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, addressBook);

        final GossipEvent event = new TestingEventBuilder(randotron)
                .setCreatorId(addressBook.getNodeId(0))
                .build();
        assertThrows(IllegalStateException.class, () -> reporter.reportBranch(event));
    }
}
