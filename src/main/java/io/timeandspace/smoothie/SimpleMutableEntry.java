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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import static io.timeandspace.smoothie.Utils.nonNullOrThrowCme;

final class SimpleMutableEntry<K, V> extends AbstractEntry<K, V> {
    private @MonotonicNonNull K key;
    private @MonotonicNonNull V value;

    @CanIgnoreReturnValue
    SimpleMutableEntry<K, V> with(K key, V value) {
        this.key = key;
        this.value = value;
        return this;
    }

    @Override
    public K getKey() {
        return nonNullOrThrowCme(key);
    }

    @Override
    public V getValue() {
        return nonNullOrThrowCme(value);
    }

    @Override
    public V setValue(V value) {
        // The object is designed for reuse, setting value doesn't make sense as in entries in
        // entry-object-based  map implementations such as HashMap.
        throw new UnsupportedOperationException("Use SmoothieMap.");
    }
}
