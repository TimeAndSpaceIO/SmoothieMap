/*
 * Copyright (C) The SmoothieMap Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.timeandspace.smoothie;

import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

import static io.timeandspace.smoothie.Utils.verifyNonNull;

/**
 * Based on https://github.com/google/guava/blob/838560034dfaa1afdf51a126afe6b8b8e6cce3dd/
 * guava/src/com/google/common/collect/AbstractIterator.java
 *
 * Copyright (C) 2007 The Guava Authors
 */
class FilteringIterator<T> implements Iterator<T> {

    private enum State {
        /** We have computed the next element and haven't returned it yet. */
        READY,

        /** We haven't yet computed or have already returned the element. */
        NOT_READY,

        /** We have reached the end of the data and are finished. */
        DONE,

        /** We've suffered an exception and are kaput. */
        FAILED,
    }

    private final Iterator<? extends T> delegate;
    private final Predicate<T> filter;
    private State state = State.NOT_READY;
    private boolean couldRemovePreviousElement = false;
    private @Nullable T next;

    FilteringIterator(Iterator<? extends T> delegate, Predicate<T> filter) {
        this.delegate = delegate;
        this.filter = filter;
    }

    @Override
    public boolean hasNext() {
        if (state == State.FAILED) {
            throw new IllegalStateException();
        }
        switch (state) {
            case DONE:
                return false;
            case READY:
                return true;
            default:
        }
        return tryToComputeNext();
    }

    private boolean tryToComputeNext() {
        state = State.FAILED; // temporary pessimism
        couldRemovePreviousElement = false;
        T next;
        do {
            if (!delegate.hasNext()) {
                state = State.DONE;
                return false;
            }
            next = verifyNonNull(delegate.next());
        } while (!filter.test(next));
        state = State.READY;
        this.next = next;
        return true;
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        T result = verifyNonNull(next);
        state = State.NOT_READY;
        couldRemovePreviousElement = true;
        next = null;
        return result;
    }

    @Override
    public void remove() {
        if (couldRemovePreviousElement) {
            couldRemovePreviousElement = false;
            delegate.remove();
        } else {
            if (state != State.NOT_READY) {
                // JDK interface method names: Iterator.next() and hasNext()
                //noinspection SSBasedInspection:
                throw new IllegalStateException("This iterator doesn't support remove after " +
                        "hasNext() has been called since the last call to next()");
            }
            // JDK interface method names: Iterator.next() and hasNext()
            //noinspection SSBasedInspection
            throw new IllegalStateException(
                    "Could not remove because next() has not been yet called on this iterator or " +
                            "remove() has already been called after the last call to next()");
        }
    }
}
