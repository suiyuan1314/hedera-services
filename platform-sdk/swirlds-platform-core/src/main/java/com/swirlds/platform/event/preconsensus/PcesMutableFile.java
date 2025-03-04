/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Represents a preconsensus event file that can be written to.
 */
public class PcesMutableFile {
    /** the file version to write at the beginning of the file. atm, this is just a placeholder for future changes */
    public static final int FILE_VERSION = 1;

    /**
     * Describes the file that is being written to.
     */
    private final PcesFile descriptor;

    /**
     * Counts the bytes written to the file.
     */
    private final CountingStreamExtension counter;

    /**
     * The highest ancient indicator of all events written to the file.
     */
    private long highestAncientIdentifierInFile;

    /**
     * The output stream to write to.
     */
    private final SerializableDataOutputStream out;

    /**
     * Create a new preconsensus event file that can be written to.
     *
     * @param descriptor a description of the file
     */
    PcesMutableFile(@NonNull final PcesFile descriptor) throws IOException {
        if (Files.exists(descriptor.getPath())) {
            throw new IOException("File " + descriptor.getPath() + " already exists");
        }

        Files.createDirectories(descriptor.getPath().getParent());

        this.descriptor = descriptor;
        counter = new CountingStreamExtension(false);
        out = new SerializableDataOutputStream(new ExtendableOutputStream(
                new BufferedOutputStream(
                        new FileOutputStream(descriptor.getPath().toFile())),
                counter));
        out.writeInt(FILE_VERSION);
        highestAncientIdentifierInFile = descriptor.getLowerBound();
    }

    /**
     * Check if this file is eligible to contain an event based on bounds.
     *
     * @param ancientIdentifier the ancient indicator of the event in question
     * @return true if this file is eligible to contain the event
     */
    public boolean canContain(final long ancientIdentifier) {
        return descriptor.canContain(ancientIdentifier);
    }

    /**
     * Write an event to the file.
     *
     * @param event the event to write
     */
    public void writeEvent(final GossipEvent event) throws IOException {
        if (!descriptor.canContain(event.getAncientIndicator(descriptor.getFileType()))) {
            throw new IllegalStateException("Cannot write event " + event.getHash() + " with ancient indicator "
                    + event.getAncientIndicator(descriptor.getFileType()) + " to file " + descriptor);
        }
        out.writeSerializable(event, false);
        highestAncientIdentifierInFile =
                Math.max(highestAncientIdentifierInFile, event.getAncientIndicator(descriptor.getFileType()));
    }

    /**
     * Atomically rename this file so that its un-utilized span is 0.
     *
     * @param upperBoundInPreviousFile the previous file's upper bound. Even if we are not utilizing the
     *                                        entire span of this file, we cannot reduce the upper bound so that
     *                                        it is smaller than the previous file's highest upper bound.
     * @return the new span compressed file
     */
    public PcesFile compressSpan(final long upperBoundInPreviousFile) {
        if (highestAncientIdentifierInFile == descriptor.getUpperBound()) {
            // No need to compress, we used the entire span.
            return descriptor;
        }

        final PcesFile newDescriptor = descriptor.buildFileWithCompressedSpan(
                Math.max(highestAncientIdentifierInFile, upperBoundInPreviousFile));

        try {
            Files.move(descriptor.getPath(), newDescriptor.getPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        return newDescriptor;
    }

    /**
     * Flush the file.
     */
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Close the file.
     */
    public void close() throws IOException {
        out.close();
    }

    /**
     * Get the current size of the file, in bytes.
     *
     * @return the size of the file in bytes
     */
    public long fileSize() {
        return counter.getCount();
    }

    /**
     * Get the difference between the highest ancient indicator written to the file and the lowest legal ancient indicator for this
     * file. Higher values mean that the upper bound was chosen well.
     */
    public long getUtilizedSpan() {
        return highestAncientIdentifierInFile - descriptor.getLowerBound();
    }

    /**
     * Get the span that is unused in this file. Low values mean that the upperBound was chosen
     * well, resulting in less overlap between files. A value of 0 represents a "perfect" choice.
     */
    public long getUnUtilizedSpan() {
        return descriptor.getUpperBound() - highestAncientIdentifierInFile;
    }

    /**
     * Get the span of ancient indicators that this file can legally contain.
     */
    public long getSpan() {
        return descriptor.getUpperBound() - descriptor.getLowerBound();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return descriptor.toString();
    }
}
