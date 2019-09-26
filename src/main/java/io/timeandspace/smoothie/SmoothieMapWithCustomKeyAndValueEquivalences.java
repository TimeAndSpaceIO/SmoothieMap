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

import io.timeandspace.collect.Equivalence;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

/**
 * The logic of this class is the same as of {@link SmoothieMapWithCustomValueEquivalence}. These
 * classes should be updated in parallel.
 */
final class SmoothieMapWithCustomKeyAndValueEquivalences<K, V>
        extends SmoothieMapWithCustomKeyEquivalence<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyAndValueEquivalences.class);

    private final Equivalence<V> valueEquivalence;

    SmoothieMapWithCustomKeyAndValueEquivalences(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        valueEquivalence = builder.valueEquivalence();
    }

    @Override
    boolean valuesEqual(Object queriedValue, V internalValue) {
        //noinspection unchecked
        return valueEquivalence.equivalent((V) queriedValue, internalValue);
    }

    @Override
    public Equivalence<V> valueEquivalence() {
        return valueEquivalence;
    }

    @Override
    int valueHashCodeForAggregateHashCodes(Object value) {
        //noinspection unchecked
        return valueEquivalence.hash((V) value);
    }

    /**
     * Doesn't account for {@link #keyHashFunction}, {@link #keyEquivalence}, and
     * {@link #valueEquivalence}.
     */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
