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
 * If a custom key has function is not specified in the {@link SmoothieMapBuilder}, there is another
 * specialization possible which overrides {@link #keyHashCode} directly without an indirection via
 * a {@link java.util.function.ToLongFunction} hash function object. TODO implement this
 */
class SmoothieMapWithCustomKeyEquivalence<K, V> extends SmoothieMapWithCustomKeyHashFunction<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyEquivalence.class);

    private final Equivalence<K> keyEquivalence;

    SmoothieMapWithCustomKeyEquivalence(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        keyEquivalence = builder.keyEquivalence();
    }

    @Override
    boolean keysEqual(Object queriedKey, K internalKey) {
        //noinspection unchecked
        return keyEquivalence.equivalent((K) queriedKey, internalKey);
    }

    @Override
    public Equivalence<K> keyEquivalence() {
        return keyEquivalence;
    }

    @Override
    int keyHashCodeForAggregateHashCodes(Object key) {
        //noinspection unchecked
        return keyEquivalence.hash((K) key);
    }

    /** Doesn't account for {@link #keyHashFunction} and {@link #keyEquivalence}. */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
