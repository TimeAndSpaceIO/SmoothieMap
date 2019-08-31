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

package io.timeandspace.collect.map;

/**
 * Defines a simple key-value pair. The difference between this interface and {@link
 * java.util.Map.Entry} is that this interface doesn't specify {@link #equals} and {@link #hashCode}
 * and whether they should depend on key's and value's equivalence and hash codes at all.
 * KeyValue also doesn't have an equivalent of {@link java.util.Map.Entry}'s {@code setValue()}
 * method.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface KeyValue<K, V> {
    /**
     * Gets the key of the pair.
     * @return the key
     */
    K getKey();

    /**
     * Gets the value of the pair.
     * @return the value
     */
    V getValue();
}
