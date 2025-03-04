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

package com.swirlds.platform.event.report;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static com.swirlds.common.utility.CompareTo.isGreaterThan;

import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.platform.recovery.internal.EventStreamLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamMultiFileIterator;
import com.swirlds.platform.recovery.internal.MultiFileRunningHashIterator;
import com.swirlds.platform.system.events.DetailedConsensusEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scans an event stream and generates a report.
 */
public class EventStreamScanner {

    private static final int PROGRESS_INTERVAL = 10_000;

    private long eventCount = 0;
    private long transactionCount = 0;
    private long systemTransactionCount = 0;
    private long applicationTransactionCount = 0;

    // These variables store data for the higher granularity reports
    private final List<EventStreamInfo> granularInfo = new ArrayList<>();
    private DetailedConsensusEvent granularFirstEvent;
    private long granularStartingFileCount = 0;
    private long previousFileCount = 0;
    private long previousDamagedFileCount = 0;
    private long previousByteCount = 0;
    private long granularStartingDamagedFileCount = 0;
    private long granularEventCount = 0;
    private long granularTransactionCount = 0;
    private long granularSystemTransactionCount = 0;
    private long granularApplicationTransactionCount = 0;

    private final Duration reportPeriod;
    private final boolean enableProgressReport;

    private final EventStreamMultiFileIterator fileIterator;
    private final IOIterator<DetailedConsensusEvent> eventIterator;

    public EventStreamScanner(
            @NonNull final Path eventStreamDirectory,
            @NonNull final EventStreamLowerBound lowerBound,
            @NonNull final Duration reportPeriod,
            final boolean enableProgressReport)
            throws IOException {
        Objects.requireNonNull(eventStreamDirectory, "the event stream directory must not be null");
        Objects.requireNonNull(lowerBound, "the lower bound must not be null");
        this.reportPeriod = Objects.requireNonNull(reportPeriod, "the report period must not be null");
        this.enableProgressReport = enableProgressReport;

        fileIterator = new EventStreamMultiFileIterator(eventStreamDirectory, lowerBound);
        eventIterator = new MultiFileRunningHashIterator(fileIterator);
    }

    /**
     * Time is split into "chunks". For each chunk of time we generate a mini-report. When this method is called
     * we gather all the data from a single chunk of time.
     */
    private void reportGranularData(final DetailedConsensusEvent lastEventInPeriod) {
        final long granularRoundCount = lastEventInPeriod.getRoundReceived() - granularFirstEvent.getRoundReceived();

        granularInfo.add(new EventStreamInfo(
                granularFirstEvent.getGossipEvent().getConsensusTimestamp(),
                lastEventInPeriod.getGossipEvent().getConsensusTimestamp(),
                granularRoundCount,
                granularEventCount,
                granularTransactionCount,
                granularSystemTransactionCount,
                granularApplicationTransactionCount,
                previousFileCount - granularStartingFileCount,
                fileIterator.getBytesRead() - previousByteCount,
                granularFirstEvent,
                lastEventInPeriod,
                previousDamagedFileCount - granularStartingDamagedFileCount));
    }

    /**
     * This should be called between each "chunk" of time for which we collect granular data.
     */
    private void resetGranularData(final DetailedConsensusEvent mostRecentEvent) {
        granularFirstEvent = mostRecentEvent;
        granularEventCount = 0;
        granularTransactionCount = 0;
        granularSystemTransactionCount = 0;
        granularApplicationTransactionCount = 0;
        granularStartingFileCount = fileIterator.getFileCount();
        granularStartingDamagedFileCount = fileIterator.getDamagedFileCount();
        previousByteCount = fileIterator.getBytesRead();
    }

    /**
     * Collect data from an event.
     */
    private void collectEventData(final DetailedConsensusEvent mostRecentEvent) {
        eventCount++;
        granularEventCount++;
        mostRecentEvent.getGossipEvent().transactionIterator().forEachRemaining(transaction -> {
            transactionCount++;
            granularTransactionCount++;
            if (transaction.isSystem()) {
                systemTransactionCount++;
                granularSystemTransactionCount++;
            } else {
                applicationTransactionCount++;
                granularApplicationTransactionCount++;
            }
        });
    }

    /**
     * Write information to the console that let's a human know of the progress of the scan.
     */
    private void writeConsoleSummary(
            final DetailedConsensusEvent firstEvent, final DetailedConsensusEvent mostRecentEvent) {

        if (enableProgressReport && eventCount % PROGRESS_INTERVAL == 0) {
            // This is intended to be used in a terminal with a human in the loop, intentionally not logged.
            final Duration consensusTimeProcessed = Duration.between(
                    firstEvent.getGossipEvent().getConsensusTimestamp(),
                    mostRecentEvent.getGossipEvent().getConsensusTimestamp());

            final UnitFormatter formatter = TimeUnit.UNIT_MILLISECONDS
                    .buildFormatter()
                    .setQuantity(consensusTimeProcessed.toMillis())
                    .setDecimalPlaces(2)
                    .setAbbreviate(false);

            System.out.print(" > " + BRIGHT_RED.apply(commaSeparatedNumber(eventCount))
                    + " events have been parsed from a consensus timespan of "
                    + BRIGHT_YELLOW.apply(formatter.render())
                    + "             \r");
            System.out.flush();
        }
    }

    public EventStreamReport createReport() throws IOException {

        if (!eventIterator.hasNext()) {
            throw new IllegalStateException("No events found in the event stream");
        }

        final DetailedConsensusEvent firstEvent = eventIterator.peek();
        granularFirstEvent = firstEvent;

        DetailedConsensusEvent mostRecentEvent = null;
        DetailedConsensusEvent previousEvent;
        while (eventIterator.hasNext()) {
            previousEvent = mostRecentEvent;
            mostRecentEvent = eventIterator.next();

            final Duration elapsedGranularTime = Duration.between(
                    granularFirstEvent.getGossipEvent().getConsensusTimestamp(),
                    mostRecentEvent.getGossipEvent().getConsensusTimestamp());
            if (previousEvent != null && isGreaterThan(elapsedGranularTime, reportPeriod)) {
                // The previous granular period has ended. Start a new period.
                reportGranularData(previousEvent);
                resetGranularData(mostRecentEvent);
            }

            collectEventData(mostRecentEvent);

            previousFileCount = fileIterator.getFileCount();
            previousDamagedFileCount = fileIterator.getDamagedFileCount();

            if (!eventIterator.hasNext() && granularEventCount > 0) {
                reportGranularData(mostRecentEvent);
            }

            writeConsoleSummary(firstEvent, mostRecentEvent);
        }

        final long rounds = mostRecentEvent.getRoundReceived() - firstEvent.getRoundReceived();

        return new EventStreamReport(
                granularInfo,
                new EventStreamInfo(
                        firstEvent.getGossipEvent().getConsensusTimestamp(),
                        mostRecentEvent.getGossipEvent().getConsensusTimestamp(),
                        rounds,
                        eventCount,
                        transactionCount,
                        systemTransactionCount,
                        applicationTransactionCount,
                        fileIterator.getFileCount(),
                        fileIterator.getBytesRead(),
                        firstEvent,
                        mostRecentEvent,
                        fileIterator.getDamagedFileCount()));
    }
}
