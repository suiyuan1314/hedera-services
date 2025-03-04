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

package com.hedera.node.app.spi.state;

import com.swirlds.platform.state.spi.WritableQueueStateBase;
import com.swirlds.state.spi.WritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link WritableQueueState} that delegates to another {@link WritableQueueState} as
 * though it were the backend data source. Modifications to this {@link WrappedWritableKVState} are
 * buffered, along with reads, allowing code to rollback by simply throwing away the wrapper.
 *
 * @param <E> The type of element in the queue.
 */
public class WrappedWritableQueueState<E> extends WritableQueueStateBase<E> {

    private final WritableQueueState<E> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     */
    public WrappedWritableQueueState(@NonNull final WritableQueueState<E> delegate) {
        super(delegate.getStateKey());
        this.delegate = delegate;
    }

    @Override
    protected void addToDataSource(@NonNull final E element) {
        delegate.add(element);
    }

    @Override
    protected void removeFromDataSource() {
        delegate.poll();
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return delegate.iterator();
    }
}
