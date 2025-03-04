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

package com.swirlds.platform.system.events;

import static com.swirlds.common.io.streams.SerializableDataOutputStream.getSerializedLength;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.config.singleton.ConfigurationHolder;
import com.swirlds.common.crypto.AbstractSerializableHashable;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.config.TransactionConfig;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.StaticSoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.transaction.ConsensusTransactionImpl;
import com.swirlds.platform.system.transaction.StateSignatureTransaction;
import com.swirlds.platform.system.transaction.SwirldTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A class used to store base event data that is used to create the hash of that event.
 * <p>
 * A base event is a set of data describing an event at the point when it is created, before it is added to the
 * hashgraph and before its consensus can be determined. Some of this data is used to create a hash of an event and some
 * data is additional and does not affect the hash.
 */
public class BaseEventHashedData extends AbstractSerializableHashable implements SelfSerializable {
    public static final int TO_STRING_BYTE_ARRAY_LENGTH = 5;
    private static final long CLASS_ID = 0x21c2620e9b6a2243L;

    public static class ClassVersion {
        /**
         * Event descriptors replace the hashes and generation of the parents in the event. Multiple otherParents are
         * supported. birthRound is added for lookup of the effective roster at the time of event creation.
         *
         * @since 0.46.0
         */
        public static final int BIRTH_ROUND = 4;
    }

    ///////////////////////////////////////
    // immutable, sent during normal syncs, affects the hash that is signed:
    ///////////////////////////////////////

    /**
     * the software version of the node that created this event.
     */
    private SoftwareVersion softwareVersion;

    /**
     * ID of this event's creator (translate before sending)
     */
    private NodeId creatorId;

    /**
     * the round number in which this event was created, used to look up the effective roster at that time.
     */
    private long birthRound;

    /**
     * the self parent event descriptor
     */
    private EventDescriptor selfParent;

    /**
     * the other parents' event descriptors
     */
    private List<EventDescriptor> otherParents;

    /** a combined list of all parents, selfParent + otherParents */
    private List<EventDescriptor> allParents;

    /**
     * creation time, as claimed by its creator
     */
    private Instant timeCreated;

    /**
     * the payload: an array of transactions
     */
    private ConsensusTransactionImpl[] transactions;

    /**
     * The event descriptor for this event. Is not itself hashed.
     */
    private EventDescriptor descriptor;

    /**
     * The actual birth round to return. May not be the original birth round if this event was created in the software
     * version right before the birth round migration.
     */
    private long birthRoundOverride;

    /**
     * Class IDs of permitted transaction types.
     */
    private static final Set<Long> TRANSACTION_TYPES =
            Set.of(StateSignatureTransaction.CLASS_ID, SwirldTransaction.CLASS_ID);

    public BaseEventHashedData() {}

    /**
     * Create a BaseEventHashedData object
     *
     * @param softwareVersion the software version of the node that created this event.
     * @param creatorId       ID of this event's creator
     * @param selfParent      self parent event descriptor
     * @param otherParents    other parent event descriptors
     * @param birthRound      the round in which this event was created.
     * @param timeCreated     creation time, as claimed by its creator
     * @param transactions    the payload: an array of transactions included in this event instance
     */
    public BaseEventHashedData(
            @NonNull SoftwareVersion softwareVersion,
            @NonNull final NodeId creatorId,
            @Nullable final EventDescriptor selfParent,
            @NonNull final List<EventDescriptor> otherParents,
            final long birthRound,
            @NonNull final Instant timeCreated,
            @Nullable final ConsensusTransactionImpl[] transactions) {
        this.softwareVersion = Objects.requireNonNull(softwareVersion, "The softwareVersion must not be null");
        this.creatorId = Objects.requireNonNull(creatorId, "The creatorId must not be null");
        this.selfParent = selfParent;
        Objects.requireNonNull(otherParents, "The otherParents must not be null");
        otherParents.forEach(Objects::requireNonNull);
        this.otherParents = otherParents;
        this.allParents = createAllParentsList();
        this.birthRound = birthRound;
        this.birthRoundOverride = birthRound;
        this.timeCreated = Objects.requireNonNull(timeCreated, "The timeCreated must not be null");
        this.transactions = transactions;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.BIRTH_ROUND;
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(softwareVersion, true);
        out.writeSerializable(creatorId, false);
        out.writeSerializable(selfParent, false);
        out.writeSerializableList(otherParents, false, true);
        out.writeLong(birthRound);
        out.writeInstant(timeCreated);

        // write serialized length of transaction array first, so during the deserialization proces
        // it is possible to skip transaction array and move on to the next object
        out.writeInt(getSerializedLength(transactions, true, false));
        // transactions may include both system transactions and application transactions
        // so writeClassId set to true and allSameClass set to false
        out.writeSerializableArray(transactions, true, false);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        final TransactionConfig transactionConfig = ConfigurationHolder.getConfigData(TransactionConfig.class);
        deserialize(in, version, transactionConfig.maxTransactionCountPerEvent());
    }

    private void deserialize(
            @NonNull final SerializableDataInputStream in, final int version, final int maxTransactionCount)
            throws IOException {
        Objects.requireNonNull(in, "The input stream must not be null");
        softwareVersion = in.readSerializable(StaticSoftwareVersion.getSoftwareVersionClassIdSet());

        creatorId = in.readSerializable(false, NodeId::new);
        if (creatorId == null) {
            throw new IOException("creatorId is null");
        }
        selfParent = in.readSerializable(false, EventDescriptor::new);
        otherParents = in.readSerializableList(AddressBook.MAX_ADDRESSES, false, EventDescriptor::new);
        allParents = createAllParentsList();
        birthRound = in.readLong();
        birthRoundOverride = birthRound;

        timeCreated = in.readInstant();
        in.readInt(); // read serialized length
        transactions =
                in.readSerializableArray(ConsensusTransactionImpl[]::new, maxTransactionCount, true, TRANSACTION_TYPES);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final BaseEventHashedData that = (BaseEventHashedData) o;

        return (Objects.equals(creatorId, that.creatorId))
                && Objects.equals(selfParent, that.selfParent)
                && Objects.equals(otherParents, that.otherParents)
                && birthRound == that.birthRound
                && Objects.equals(timeCreated, that.timeCreated)
                && Arrays.equals(transactions, that.transactions)
                && (softwareVersion.compareTo(that.softwareVersion) == 0);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(softwareVersion, creatorId, selfParent, otherParents, birthRound, timeCreated);
        result = 31 * result + Arrays.hashCode(transactions);
        return result;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("softwareVersion", softwareVersion)
                .append("creatorId", creatorId)
                .append("selfParent", selfParent)
                .append("otherParents", otherParents)
                .append("birthRound", birthRound)
                .append("timeCreated", timeCreated)
                .append("transactions size", transactions == null ? "null" : transactions.length)
                .append("hash", getHash() == null ? "null" : getHash().toHex(TO_STRING_BYTE_ARRAY_LENGTH))
                .toString();
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.BIRTH_ROUND;
    }

    /**
     * Returns the software version of the node that created this event.
     *
     * @return the software version of the node that created this event
     */
    @Nullable
    public SoftwareVersion getSoftwareVersion() {
        return softwareVersion;
    }

    /**
     * The ID of the node that created this event.
     *
     * @return the ID of the node that created this event
     */
    @NonNull
    public NodeId getCreatorId() {
        return creatorId;
    }

    /**
     * Override the birth round for this event. This will only be called for events created in the software version
     * right before the birth round migration.
     *
     * @param birthRoundOverride the birth round that has been assigned to this event
     */
    public void setBirthRoundOverride(final long birthRoundOverride) {
        this.birthRoundOverride = birthRoundOverride;
    }

    /**
     * Get the birth round of the event.
     *
     * @return the birth round of the event
     */
    public long getBirthRound() {
        return birthRoundOverride;
    }

    /**
     * Get the event descriptor for the self parent.
     *
     * @return the event descriptor for the self parent
     */
    @Nullable
    public EventDescriptor getSelfParent() {
        return selfParent;
    }

    /**
     * Get the event descriptors for the other parents.
     *
     * @return the event descriptors for the other parents
     */
    @NonNull
    public List<EventDescriptor> getOtherParents() {
        return otherParents;
    }

    /** @return a list of all parents, self parent (if any), + all other parents */
    @NonNull
    public List<EventDescriptor> getAllParents() {
        return allParents;
    }

    @NonNull
    private List<EventDescriptor> createAllParentsList() {
        return !hasSelfParent()
                ? otherParents
                : Stream.concat(Stream.of(selfParent), otherParents.stream()).toList();
    }

    /**
     * Get the self parent generation
     *
     * @return the self parent generation
     */
    public long getSelfParentGen() {
        if (selfParent == null) {
            return EventConstants.GENERATION_UNDEFINED;
        }
        return selfParent.getGeneration();
    }

    /**
     * Get the maximum generation of the other parents.
     *
     * @return the maximum generation of the other parents
     * @deprecated this method should be replaced since there can be multiple other parents.
     */
    public long getOtherParentGen() {
        if (otherParents == null || otherParents.isEmpty()) {
            return EventConstants.GENERATION_UNDEFINED;
        }
        if (otherParents.size() == 1) {
            return otherParents.get(0).getGeneration();
        }
        // 0.46.0 adds support for multiple other parents in the serialization scheme, but not yet in the
        // implementation. This exception should never be reached unless we have multiple parents and need to
        // update the implementation.
        throw new UnsupportedOperationException("Multiple other parents is not supported yet");
    }

    /**
     * Get the hash of the self parent.
     *
     * @return the hash of the self parent
     */
    @Nullable
    public Hash getSelfParentHash() {
        if (selfParent == null) {
            return null;
        }
        return selfParent.getHash();
    }

    /**
     * Get the hash of the other parent with the maximum generation.
     *
     * @return the hash of the other parent with the maximum generation
     * @deprecated
     */
    @Nullable
    public Hash getOtherParentHash() {
        if (otherParents == null || otherParents.isEmpty()) {
            return null;
        }
        if (otherParents.size() == 1) {
            return otherParents.get(0).getHash();
        }
        // 0.46.0 adds support for multiple other parents in the serialization scheme, but not yet in the
        // implementation. This exception should never be reached unless we have multiple parents and need to
        // update the implementation.
        throw new UnsupportedOperationException("Multiple other parents is not supported yet");
    }

    /**
     * Check if the event has a self parent.
     *
     * @return true if the event has a self parent
     */
    public boolean hasSelfParent() {
        return selfParent != null;
    }

    /**
     * Check if the event has other parents.
     *
     * @return true if the event has other parents
     */
    public boolean hasOtherParent() {
        return otherParents != null && !otherParents.isEmpty();
    }

    @NonNull
    public Instant getTimeCreated() {
        return timeCreated;
    }

    /**
     * @return array of transactions inside this event instance
     */
    @Nullable
    public ConsensusTransactionImpl[] getTransactions() {
        return transactions;
    }

    public long getGeneration() {
        return calculateGeneration(getSelfParentGen(), getOtherParentGen());
    }

    /**
     * Calculates the generation of an event based on its parents generations
     *
     * @param selfParentGeneration  the generation of the self parent
     * @param otherParentGeneration the generation of the other parent
     * @return the generation of the event
     */
    public static long calculateGeneration(final long selfParentGeneration, final long otherParentGeneration) {
        return 1 + Math.max(selfParentGeneration, otherParentGeneration);
    }

    /**
     * Get the event descriptor for this event, creating one if it hasn't yet been created. If called more than once
     * then return the same instance.
     *
     * @return an event descriptor for this event
     * @throws IllegalStateException if called prior to this event being hashed
     */
    @NonNull
    public EventDescriptor getDescriptor() {
        if (descriptor == null) {
            if (getHash() == null) {
                throw new IllegalStateException("The hash of the event must be set before creating the descriptor");
            }

            descriptor = new EventDescriptor(getHash(), getCreatorId(), getGeneration(), getBirthRound());
        }

        return descriptor;
    }
}
