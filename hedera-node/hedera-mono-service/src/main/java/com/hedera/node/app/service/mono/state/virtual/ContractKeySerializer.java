/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.mono.state.virtual;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/** KeySerializer for ContractKeys */
public class ContractKeySerializer implements KeySerializer<ContractKey> {

    static final long CLASS_ID = 0xfb12270526c45317L;

    static final int CURRENT_VERSION = 1;

    static final long DATA_VERSION = 1;

    // Serializer info

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CURRENT_VERSION;
    }

    // Data version

    @Override
    public long getCurrentDataVersion() {
        return DATA_VERSION;
    }

    // Key serialization

    @Override
    public int getSerializedSize() {
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(final ContractKey key) {
        return key.getSerializedSizeInBytes();
    }

    @Override
    public int getTypicalSerializedSize() {
        return ContractKey.ESTIMATED_AVERAGE_SIZE;
    }

    @Override
    public void serialize(@NonNull final ContractKey key, @NonNull final WritableSequentialData out) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(out);
        key.serialize(out);
    }

    // Key deserialization

    @Override
    public ContractKey deserialize(@NonNull final ReadableSequentialData in) {
        Objects.requireNonNull(in);
        ContractKey key = new ContractKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(@NonNull final BufferedData buf, @NonNull final ContractKey contractKey) {
        return contractKey.equalsTo(buf);
    }
}
