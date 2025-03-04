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

package com.hedera.node.app.service.mono.statedumpers.scheduledtransactions;

import com.hedera.node.app.service.mono.state.adapters.MerkleMapLike;
import com.hedera.node.app.service.mono.state.merkle.MerkleScheduledTransactions;
import com.hedera.node.app.service.mono.state.virtual.EntityNumVirtualKey;
import com.hedera.node.app.service.mono.state.virtual.schedule.ScheduleEqualityVirtualKey;
import com.hedera.node.app.service.mono.state.virtual.schedule.ScheduleEqualityVirtualValue;
import com.hedera.node.app.service.mono.state.virtual.schedule.ScheduleSecondVirtualValue;
import com.hedera.node.app.service.mono.state.virtual.schedule.ScheduleVirtualValue;
import com.hedera.node.app.service.mono.state.virtual.temporal.SecondSinceEpocVirtualKey;
import com.hedera.node.app.service.mono.statedumpers.DumpCheckpoint;
import com.hedera.node.app.service.mono.statedumpers.utils.FieldBuilder;
import com.hedera.node.app.service.mono.statedumpers.utils.ThingsToStrings;
import com.hedera.node.app.service.mono.statedumpers.utils.Writer;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ScheduledTransactionsDumpUtils {
    public static void dumpMonoScheduledTransactions(
            @NonNull final Path path,
            @NonNull final MerkleScheduledTransactions scheduledTransactions,
            @NonNull final DumpCheckpoint checkpoint) {
        try (@NonNull final var writer = new Writer(path)) {
            final var byId = scheduledTransactions.byId();
            final var byEquality = scheduledTransactions.byEquality();
            final var byExpirationSecond = scheduledTransactions.byExpirationSecond();

            System.out.printf("=== Dumping schedule transactions %n ======");

            final var byIdDump = gatherMonoScheduledTransactionsByID(byId);
            reportOnScheduledTransactionsById(writer, byIdDump);
            System.out.println("Size of byId in State : " + byId.size() + " and gathered : " + byIdDump.size());

            final var byEqualityDump = gatherMonoScheduledTransactionsByEquality(byEquality);
            reportOnScheduledTransactionsByEquality(writer, byEqualityDump);
            System.out.println(
                    "Size of byEquality in State : " + byEquality.size() + " and gathered : " + byEqualityDump.size());

            final var byExpiryDump = gatherMonoScheduledTransactionsByExpiry(byExpirationSecond);
            reportOnScheduledTransactionsByExpiry(writer, byExpiryDump);
            System.out.println("Size of byExpiry in State : " + byExpirationSecond.size() + " and gathered : "
                    + byExpiryDump.size());
        }
    }

    public static void reportOnScheduledTransactionsByEquality(
            final Writer writer, final List<BBMScheduledEqualityValue> source) {
        writer.writeln("=== Scheduled Transactions by Equality ===");
        source.stream().forEach(e -> writer.writeln(e.toString()));
        writer.writeln("");
    }

    private static List<BBMScheduledEqualityValue> gatherMonoScheduledTransactionsByEquality(
            final MerkleMapLike<ScheduleEqualityVirtualKey, ScheduleEqualityVirtualValue> source) {
        final List<BBMScheduledEqualityValue> r = new ArrayList<>();
        source.forEach((k, v) -> r.add(BBMScheduledEqualityValue.fromMono(v)));
        return r;
    }

    @NonNull
    private static Map<BBMScheduledId, BBMScheduledTransaction> gatherMonoScheduledTransactionsByID(
            MerkleMapLike<EntityNumVirtualKey, ScheduleVirtualValue> source) {
        final var r = new HashMap<BBMScheduledId, BBMScheduledTransaction>();
        source.forEach((k, v) -> r.put(BBMScheduledId.fromMono(k), BBMScheduledTransaction.fromMono(v)));
        return r;
    }

    @NonNull
    private static Map<BBMScheduledId, BBMScheduledSecondValue> gatherMonoScheduledTransactionsByExpiry(
            MerkleMapLike<SecondSinceEpocVirtualKey, ScheduleSecondVirtualValue> source) {
        final var r = new HashMap<BBMScheduledId, BBMScheduledSecondValue>();
        source.forEach((k, v) -> r.put(BBMScheduledId.fromMono(k), BBMScheduledSecondValue.fromMono(v)));
        return r;
    }

    public static void reportOnScheduledTransactionsById(
            @NonNull final Writer writer,
            @NonNull final Map<BBMScheduledId, BBMScheduledTransaction> scheduledTransactions) {
        writer.writeln("=== Scheduled Transactions by ID ===");
        writer.writeln(formatHeader());
        scheduledTransactions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> formatScheduledTransaction(writer, e.getValue()));
        writer.writeln("");
    }

    public static void reportOnScheduledTransactionsByExpiry(
            @NonNull final Writer writer,
            @NonNull final Map<BBMScheduledId, BBMScheduledSecondValue> scheduledTransactions) {
        writer.writeln("=== Scheduled Transactions by Expiry ===");
        scheduledTransactions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.writeln(e.getValue().toString()));
        writer.writeln("");
    }

    @NonNull
    private static String formatHeader() {
        return fieldFormattersForScheduleById.stream().map(Pair::left).collect(Collectors.joining(FIELD_SEPARATOR));
    }

    static final String FIELD_SEPARATOR = ";";
    static final String SUBFIELD_SEPARATOR = ",";
    static Function<Boolean, String> booleanFormatter = b -> b ? "T" : "";
    static Function<Object, String> csvQuote =
            s -> ThingsToStrings.quoteForCsv(FIELD_SEPARATOR, (s == null) ? "" : s.toString());

    static <T> Function<Optional<T>, String> getOptionalFormatter(@NonNull final Function<T, String> formatter) {
        return ot -> ot.isPresent() ? formatter.apply(ot.get()) : "";
    }

    static <T> Function<T, String> getNullableFormatter(@NonNull final Function<T, String> formatter) {
        return t -> null != t ? formatter.apply(t) : "";
    }

    static <T> Function<List<T>, String> getListFormatter(
            @NonNull final Function<T, String> formatter, @NonNull final String subfieldSeparator) {
        return lt -> {
            if (!lt.isEmpty()) {
                final var sb = new StringBuilder();
                for (@NonNull final var e : lt) {
                    final var v = formatter.apply(e);
                    sb.append(v);
                    sb.append(subfieldSeparator);
                }
                // Remove last subfield separator
                if (sb.length() >= subfieldSeparator.length()) sb.setLength(sb.length() - subfieldSeparator.length());
                return sb.toString();
            } else return "";
        };
    }

    // spotless:off
    @NonNull
    private static final List<Pair<String, BiConsumer<FieldBuilder, BBMScheduledTransaction>>>
            fieldFormattersForScheduleById = List.of(
                    Pair.of(
                            "number",
                            getFieldFormatterForScheduledTxn(BBMScheduledTransaction::number, Object::toString)),
                    Pair.of(
                            "adminKey",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::adminKey,
                                    getOptionalFormatter(ThingsToStrings::toStringOfJKey))),
                    Pair.of("memo", getFieldFormatterForScheduledTxn(BBMScheduledTransaction::memo, csvQuote)),
                    Pair.of(
                            "isDeleted",
                            getFieldFormatterForScheduledTxn(BBMScheduledTransaction::deleted, booleanFormatter)),
                    Pair.of(
                            "isExecuted",
                            getFieldFormatterForScheduledTxn(BBMScheduledTransaction::executed, booleanFormatter)),
                    Pair.of(
                            "calculatedWaitForExpiry",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::calculatedWaitForExpiry, booleanFormatter)),
                    Pair.of(
                            "waitForExpiryProvided",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::waitForExpiryProvided, booleanFormatter)),
                    Pair.of(
                            "payer",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::payer, ThingsToStrings::toStringOfEntityId)),
                    Pair.of(
                            "schedulingAccount",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::schedulingAccount, ThingsToStrings::toStringOfEntityId)),
                    Pair.of(
                            "schedulingTXValidStart",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::schedulingTXValidStart,
                                    ThingsToStrings::toStringOfRichInstant)),
                    Pair.of(
                            "expirationTimeProvided",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::expirationTimeProvided,
                                    getNullableFormatter(ThingsToStrings::toStringOfRichInstant))),
                    Pair.of(
                            "calculatedExpirationTime",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::calculatedExpirationTime,
                                    getNullableFormatter(ThingsToStrings::toStringOfRichInstant))),
                    Pair.of(
                            "resolutionTime",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::resolutionTime,
                                    getNullableFormatter(ThingsToStrings::toStringOfRichInstant))),
                    Pair.of(
                            "bodyBytes",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::bodyBytes, ThingsToStrings::toStringOfByteArray)),
                    Pair.of(
                            "ordinaryScheduledTxn",
                            getFieldFormatterForScheduledTxn(BBMScheduledTransaction::ordinaryScheduledTxn, csvQuote)),
                    Pair.of(
                            "scheduledTxn",
                            getFieldFormatterForScheduledTxn(BBMScheduledTransaction::scheduledTxn, csvQuote)),
                    Pair.of(
                            "signatories",
                            getFieldFormatterForScheduledTxn(
                                    BBMScheduledTransaction::signatories,
                                    getListFormatter(ThingsToStrings::toStringOfByteArray, SUBFIELD_SEPARATOR))));

    @NonNull
    static <T> BiConsumer<FieldBuilder, BBMScheduledTransaction> getFieldFormatterForScheduledTxn(
            @NonNull final Function<BBMScheduledTransaction, T> fun, @NonNull final Function<T, String> formatter) {
        return (fb, u) -> fb.append(formatter.apply(fun.apply(u)));
    }

    private static void formatScheduledTransaction(
            @NonNull final Writer writer, @NonNull final BBMScheduledTransaction scheduledTransaction) {
        final var fb = new FieldBuilder(FIELD_SEPARATOR);
        fieldFormattersForScheduleById.stream().map(Pair::right).forEach(ff -> ff.accept(fb, scheduledTransaction));
        writer.writeln(fb);
    }
}
