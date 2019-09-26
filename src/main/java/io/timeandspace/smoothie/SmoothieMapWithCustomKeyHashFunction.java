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

import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

class SmoothieMapWithCustomKeyHashFunction<K, V> extends SmoothieMap<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyHashFunction.class);

    private final ToLongFunction<K> keyHashFunction;

    SmoothieMapWithCustomKeyHashFunction(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        keyHashFunction = builder.keyHashFunction();
    }

    @Override
    long keyHashCode(Object key) {
        //noinspection unchecked
        return keyHashFunction.applyAsLong((K) key);
    }

    @Override
    ToLongFunction<K> getKeyHashFunction() {
        return keyHashFunction;
    }

    /** Doesn't account for {@link #keyHashFunction}. */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
