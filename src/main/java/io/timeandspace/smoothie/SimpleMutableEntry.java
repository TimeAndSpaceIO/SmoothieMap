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

import com.google.errorprone.annotations.DoNotCall;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

import static io.timeandspace.smoothie.Utils.nonNullOrThrowCme;

final class SimpleMutableEntry<K, V> implements Map.Entry<K, V> {

    private final SmoothieMap<K, V> map;
    /**
     * {@link #withKeyAndValue} must always be called before the entry is given to the user of the
     * SmoothieMap library. Observing null key or value means that the entry object itself is
     * accessed concurrently in the user's code. That is why {@link Utils#nonNullOrThrowCme} is used
     * in this class.
     */
    private @Nullable K key;
    private @Nullable V value;

    SimpleMutableEntry(SmoothieMap<K, V> map) {
        this.map = map;
    }

    SimpleMutableEntry<K, V> withKeyAndValue(K key, V value) {
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

    @DoNotCall
    @Override
    public V setValue(V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
        long keyHashCode = map.keyHashCode(nonNullOrThrowCme(key));
        return SmoothieMap.longKeyHashCodeToIntHashCode(keyHashCode) ^
                map.valueHashCode(nonNullOrThrowCme(value));
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Map.Entry)) {
            return false;
        }
        Map.Entry otherEntry = (Map.Entry) obj;
        return map.keysEqual(otherEntry.getKey(), nonNullOrThrowCme(key)) &&
                map.valuesEqual(otherEntry.getValue(), nonNullOrThrowCme(value));
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
