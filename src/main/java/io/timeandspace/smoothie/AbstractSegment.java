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

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The only purpose of this class is that the methods in {@link
 * InterleavedSegments.FullCapacitySegment} and {@link
 * InterleavedSegments.IntermediateCapacitySegment} can have @Override annotations.
 */
abstract class AbstractSegment<K, V> {

    abstract int hashCode(SmoothieMap<K, V> map);

    /**
     * This method acccepts a {@link SmoothieMap.KeySet} object to make it impossible to call it
     * accidentially from the wrong context, e. g. the general {@link SmoothieMap#hashCode()}
     * implementation.
     */
    abstract int keySetHashCode(SmoothieMap.KeySet<K, V> keySet);

    abstract void forEach(BiConsumer<? super K, ? super V> action);

    abstract boolean forEachWhile(BiPredicate<? super K, ? super V> predicate);

    abstract void forEachKey(Consumer<? super K> action);

    abstract boolean forEachKeyWhile(Predicate<? super K> predicate);

    abstract void forEachValue(Consumer<? super V> action);

    abstract boolean forEachValueWhile(Predicate<? super V> predicate);

    abstract void replaceAll(BiFunction<? super K, ? super V, ? extends V> function);

    abstract boolean containsValue(SmoothieMap<K, V> map, V queriedValue);

    abstract int removeIf(
            SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount);
}
