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

import io.timeandspace.collect.Equivalence;
import io.timeandspace.collect.ObjSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * An extension of the {@link Map} interface. Notable additions and differences:
 * <ul>
 *     <li>{@code null} keys and values are explicitly prohibited. Attempt to query nulls via
 *     methods like {@link #get(Object)} or {@link #containsValue(Object)} are specified to throw
 *     a {@link NullPointerException} as well. In the {@link Map} interface, both aspects (rejecting
 *     nulls for storage and rejecting null queries) are optional.</li>
 *     <li>The notion of non-standard key and value equivalences is formalized in {@link
 *     #keyEquivalence()} and {@link #valueEquivalence()} methods.</li>
 *     <li>{@link #sizeAsLong()} returns the exact size of the map if it exceeds {@link
 *     Integer#MAX_VALUE}.</li>
 *     <li>{@link #containsEntry(Object, Object)}</li>
 *     <li>{@link #getInternalKey} allows to use the map as the vehicle for key interning.</li>
 *     <li>{@link #forEachWhile(BiPredicate)}</li>
 *     <li>{@link #removeIf(BiPredicate)}</li>
 * </ul>
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
public interface ObjObjMap<K, V> extends Map<K, V> {

    /**
     * Returns the equivalence strategy for keys for this map. All methods in the {@link Map}
     * interface which are defined in terms of {@link Object#equals(Object)} equality of key
     * objects use this Equivalence instead (including {@link #equals}, {@link #hashCode()}, and
     * {@link #keySet}'s and {@link #entrySet}'s {@code equals()} and {@code hashCode()}, but
     * excluding {@code equals()} and {@code hashCode()} of {@link Map.Entry} objects which can be
     * obtained from the {@link #entrySet}).
     *
     * @return the equivalence strategy for keys for this map
     */
    Equivalence<K> keyEquivalence();

    /**
     * Returns the equivalence strategy for values for this map. All methods in the {@link Map}
     * interface which defined in terms of {@link Object#equals(Object)} equality of value objects,
     * such as {@link #containsValue(Object)} and {@link #remove(Object, Object)} use this
     * Equivalence instead (including {@link #equals}, {@link #hashCode()}, and {@link #entrySet}'s
     * {@code equals()} and {@code hashCode()}, but excluding {@code equals()} and {@code
     * hashCode()} of {@link Map.Entry} objects which can be obtained from the {@link #entrySet}).
     *
     * @return the equivalence strategy for values for this map
     */
    Equivalence<V> valueEquivalence();

    /**
     * Returns the number of entries in the map as a {@code long} value (not truncated to {@code
     * Integer.MAX_VALUE}, if the map size exceeds it, as returned by the {@link #size()} method).
     *
     * @return the number of key-value mappings in this map
     * @see #size
     * @see #mappingCount
     */
    long sizeAsLong();

    /**
     * This method is an alias to {@link #sizeAsLong()}. It is defined for compatibility with {@link
     * ConcurrentHashMap#mappingCount()}.
     *
     * @implSpec
     * The default implementation delegates to {@link #sizeAsLong()}.
     *
     * @return the number of key-value mappings in this map
     * @see #size
     * @see #sizeAsLong
     */
    @SuppressWarnings("unused")
    default long mappingCount() {
        return sizeAsLong();
    }

    /**
     * Returns {@code true} if this map contains a mapping for the specified key. More formally,
     * returns {@code true} if and only if this map contains a mapping for a key {@code k} such that
     * the specified {@code key} and {@code k} are {@linkplain #keyEquivalence() equivalent}. (There
     * can be at most one such mapping.)
     *
     * @param key key whose presence in this map is to be tested
     * @return {@code true} if this map contains a mapping for the specified key
     * @throws NullPointerException if the given key is null
     */
    @Override
    boolean containsKey(Object key);

    /**
     * Returns {@code true} if this map contains a mapping of the given key and value. More
     * formally, this map should contain a mapping from a key {@code k} to a value {@code v} such
     * that the specified {@code key} and {@code k} are equivalent with regard to {@link
     * #keyEquivalence()}, and the specified {@code value} and {@code v} are equivalent with regard
     * to {@link #valueEquivalence()}. (There can be at most one such mapping.)
     *
     * @param key the key of the mapping to check presence of
     * @param value the value of the mapping to check presence of
     * @return {@code true} if this map contains the specified mapping, {@code false} otherwise
     * @throws NullPointerException if the given key or value is null
     */
    boolean containsEntry(Object key, Object value);

    /**
     * Returns the key object held by this map internally and equivalent to the specified key, if
     * there is one, or {@code null} if this map contains no mapping for the key.
     *
     * <p>This method could be used to deduplicate objects in the application, to reduce the memory
     * footprint and make the application to conform to the "most objects die young" hypothesis that
     * most GC algorithms are optimized for. This method is functionally similar to {@link
     * String#intern()} and Guava's <a
     * href="https://guava.dev/releases/28.0-jre/api/docs/com/google/common/collect/Interner.html">
     * Interner</a>, but allows to piggy-back a map data structure which may already exist in an
     * application.
     *
     * <p>{@link #keySet()}.{@link ObjSet#getInternal(Object) getInternal(key)} delegates to this
     * method.
     *
     * @param key the key whose equivalent held by this map internally is to be returned
     * @return the map-internal equivalent of the specified key, or {@code null} if the map contains
     * no mapping for the specified key
     */
    @Nullable K getInternalKey(K key);

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if this map contains
     * no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key {@code k} to a value {@code v}
     * such that the specified {@code key} and {@code k} are {@linkplain #keyEquivalence()
     * equivalent}, then this method returns {@code v}; otherwise it returns {@code null}. (There
     * can be at most one such mapping.)
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or
     *         {@code null} if this map contains no mapping for the key
     * @throws NullPointerException if the given key is null
     */
    @Override
    @Nullable V get(Object key);

    /**
     * Returns the value to which the specified key is mapped, or {@code defaultValue} if this map
     * contains no mapping for the key.
     *
     * @param key the key whose associated value is to be returned
     * @param defaultValue the default mapping of the key
     * @return the value to which the specified key is mapped, or {@code defaultValue} if this map
     * contains no mapping for the key
     * @throws NullPointerException if the given key is null
     */
    @Override
    V getOrDefault(Object key, V defaultValue);

    /**
     * Associates the specified value with the specified key in this map. If the map previously
     * contained a mapping for the key, the old value is replaced by the specified value. (A map
     * {@code m} is said to contain a mapping for a key {@code k} if and only if {@link
     * #containsKey(Object) m.containsKey(k)} would return {@code true}.)
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     * mapping for {@code key}
     * @throws NullPointerException if the given key or value is null
     */
    @Override
    @Nullable V put(K key, V value);

    /**
     * If the specified key is not already associated with a value, associates it with the given
     * value and returns {@code null}, else returns the current value.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or {@code null} if there was no
     * mapping for the key
     * @throws NullPointerException if the given key or value is null
     */
    @Override
    @Nullable V putIfAbsent(K key, V value);

    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value.
     *
     * @param key key with which the specified value is associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or {@code null} if there was no
     * mapping for the key.
     * @throws NullPointerException if the given key or value is null
     */
    @Override
    @Nullable V replace(K key, V value);

    /**
     * Replaces the entry for the specified key only if currently mapped to the specified value.
     * Values are compared using {@link #valueEquivalence()}.
     *
     * @param key key with which the specified value is associated
     * @param oldValue value expected to be associated with the specified key
     * @param newValue value to be associated with the specified key
     * @return {@code true} if the value was replaced
     * @throws NullPointerException if the given key, oldValue or newValue is null
     */
    @Override
    boolean replace(K key, V oldValue, V newValue);

    /**
     * If the value for the specified key is present and non-null, attempts to compute a new mapping
     * given the key and its current mapped value.
     *
     * <p>If the function returns {@code null}, the mapping is removed. If the function itself
     * throws an (unchecked) exception, the exception is rethrown, and the current mapping is left
     * unchanged.
     *
     * @param key key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the given key or remappingFunction is null
     */
    @Override
    @Nullable V computeIfPresent(K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction);

    /**
     * Removes the mapping for a key from this map if it is present. More formally, if this map
     * contains a mapping from key {@code k} to value {@code v} such that the specified {@code key}
     * and {@code k} are {@linkplain #keyEquivalence() equivalent}, that mapping is removed. (The
     * map can contain at most one such mapping.)
     *
     * <p>Returns the value to which this map previously associated the key, or {@code null} if the
     * map contained no mapping for the key.
     *
     * <p>The map will not contain a mapping for the specified key once the call returns.
     *
     * @param key key whose mapping is to be removed from the map
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     * mapping for {@code key}
     * @throws NullPointerException if the given key is null
     */
    @Override
    @Nullable V remove(Object key);

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified
     * value. Values are compared using {@link #valueEquivalence()}.
     *
     * @param key key with which the specified value is associated
     * @param value value expected to be associated with the specified key
     * @return {@code true} if the value was removed
     * @throws NullPointerException if the given key or value is null
     */
    @Override
    boolean remove(Object key, Object value);

    /**
     * If the specified key is not already associated with a value, attempts to compute its value
     * using the given mapping function and enters it into this map unless {@code null}.
     *
     * <p>If the function returns {@code null} no mapping is recorded. If the function itself throws
     * an (unchecked) exception, the exception is rethrown, and no mapping is recorded. The most
     * common usage is to construct a new object serving as an initial mapped value or memoized
     * result, as in:
     *
     * <pre> {@code
     * map.computeIfAbsent(key, k -> new Value(f(k)));
     * }</pre>
     *
     * <p>Or to implement a multi-value map, {@code Map<K,Collection<V>>},
     * supporting multiple values per key:
     *
     * <pre> {@code
     * map.computeIfAbsent(key, k -> new HashSet<V>()).add(v);
     * }</pre>
     *
     * @param key key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with
     *         the specified key, or null if the computed value is null
     * @throws NullPointerException if the given key or mappingFunction is null
     */
    @Override
    @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

    /**
     * Attempts to compute a mapping for the specified key and its current mapped value (or {@code
     * null} if there is no current mapping). For example, to either create or append a {@code
     * String} msg to a value mapping:
     *
     * <pre> {@code
     * map.compute(key, (k, v) -> (v == null) ? msg : v.concat(msg))}</pre>
     * (Method {@link #merge merge()} is often simpler to use for such purposes.)
     *
     * <p>If the function returns {@code null}, the mapping is removed (or remains absent if
     * initially absent). If the function itself throws an (unchecked) exception, the exception is
     * rethrown, and the current mapping is left unchanged.
     *
     * @param key key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the given key or remappingFunction is null
     */
    @Override
    @Nullable V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction);

    /**
     * If the specified key is not already associated with a value or is associated with null,
     * associates it with the given value. Otherwise, replaces the associated value with the results
     * of the given remapping function, or removes if the result is {@code null}. This method may be
     * of use when combining multiple mapped values for a key. For example, to either create or
     * append a {@code String msg} to a value mapping:
     *
     * <pre> {@code
     * map.merge(key, msg, String::concat)
     * }</pre>
     *
     * <p>If the function returns {@code null} the mapping is removed. If the function itself throws
     * an (unchecked) exception, the exception is rethrown, and the current mapping is left
     * unchanged.
     *
     * @param key key with which the resulting value is to be associated
     * @param value the value to be merged with the existing value associated with the key or, if no
     * existing value is associated with the key, to be associated with the key
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the specified key, or null if no
     *         value is associated with the key
     * @throws NullPointerException if the given key, value or remappingFunction is null
     */
    @Override
    @Nullable V merge(
            K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction);

    /**
     * Performs the given action for each entry in this map until all entries have been processed or
     * the action throws an exception. Actions are performed in the order of {@linkplain #entrySet()
     * entry set} iteration. Exceptions thrown by the action are relayed to the caller.
     *
     * <p>The entries will be processed in the same order as they appear the entry set's iterator
     * and {@link #forEachWhile(BiPredicate)}.
     *
     * @param action The action to be performed for each entry
     * @throws NullPointerException if the specified action is null
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     * @see #forEachWhile(BiPredicate)
     */
    @Override
    void forEach(BiConsumer<? super K, ? super V> action);

    /**
     * Checks the given {@code predicate} on each entry in this map until all entries have been
     * processed or the predicate returns false for some entry, or throws an Exception. Exceptions
     * thrown by the predicate are relayed to the caller.
     *
     * <p>The entries will be processed in the same order as they appear in the entry set's iterator
     * and {@link #forEach(BiConsumer)}.
     *
     * <p>If the map is empty, this method returns {@code true} immediately.
     *
     * @param predicate the predicate to be checked for each entry
     * @return {@code true} if the map is empty, or if the predicate returned {@code true} for all
     * entries of the map, {@code false} if the predicate returned {@code false} for some entry
     * @throws NullPointerException if the specified predicate is null
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     * @see #forEach(BiConsumer)
     */
    boolean forEachWhile(BiPredicate<? super K, ? super V> predicate);

    /**
     * Replaces each entry's value with the result of invoking the given function on that entry
     * until all entries have been processed or the function throws an exception. Exceptions thrown
     * by the function are relayed to the caller.
     *
     * @param function the function to apply to each entry
     * @throws NullPointerException if the specified function is null
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     */
    @Override
    void replaceAll(BiFunction<? super K, ? super V, ? extends V> function);

    /**
     * Returns {@code true} if this map has one or more keys associated with the specified value.
     * More formally, returns {@code true} if and only if this map contains at least one mapping to
     * a value {@code v} such that {@link #valueEquivalence()}{@code .equivalent(value, v) == true}.
     * This operation requires time linear in the map size.
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the specified value
     */
    @Override
    boolean containsValue(Object value);

    /**
     * Copies all of the mappings from the specified map to this map. The effect of this call is
     * equivalent to that of calling {@link #put(Object,Object) put(k, v)} on this map once for each
     * mapping from key {@code k} to value {@code v} in the specified map. The behavior of this
     * operation is undefined if the specified map is modified while the operation is in progress.
     *
     * @param m mappings to be stored in this map
     * @throws NullPointerException if the specified map is null
     */
    @Override
    void putAll(Map<? extends K, ? extends V> m);

    /**
     * Removes all of the mappings from this map. The map will be empty after this call returns.
     *
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     *         insertion or an entry removal) is detected during operation
     */
    @Override
    void clear();

    /**
     * Removes all of the entries of this map that satisfy the given predicate. Errors or runtime
     * exceptions thrown during iteration or by the predicate are relayed to the caller.
     *
     * <p>Note the order in which this method visits entries may be different from the iteration and
     * {@link #forEach(BiConsumer)} order.
     *
     * @param filter a predicate which returns {@code true} for entries to be removed
     * @return {@code true} if any entries were removed
     * @throws NullPointerException if the specified filter is null
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     *         insertion or an entry removal) is detected during iteration
     */
    boolean removeIf(BiPredicate<? super K, ? super V> filter);

    /**
     * Returns a {@link Set} view of the keys contained in this map. The set is backed by the map,
     * so changes to the map are reflected in the set, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the set is in progress (except through the iterator's own
     * {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The set supports element removal, which removes the corresponding mapping from the map,
     * via the {@link Iterator#remove} (optionally), {@link Set#remove}, {@link Set#removeAll},
     * {@link Set#retainAll}, and {@link Set#clear} operations.
     *
     * <p>{@link Set#remove}, {@link Set#contains}, and {@link Set#containsAll} operations on the
     * returned set as well as key set's own {@code equals()} and {@code hashCode()} respect the
     * map's {@link #keyEquivalence()}. When this map has a custom {@link #keyEquivalence()}, {@code
     * equals()} on the key set works as follows: another object is considered equal to the key set
     * if it is a {@link Set} which has the same size as this map and for all elements in another
     * set, {@code keySet.contains(elementOfAnotherSet)} returns true. {@code hashCode()} on the key
     * set returns a sum of results of calls to {@link Equivalence#hash} on all elements in the key
     * set.
     *
     * <p>The key set does not support the {@link Set#add} and {@link Set#addAll} operations.
     *
     * <p>The set is created the first time this method is called, and returned in response to all
     * subsequent calls. No synchronization is performed, so there is a slight chance that multiple
     * calls to this method will not all return the same set.
     *
     * @return a set view of the keys contained in this map
     */
    @Override
    ObjSet<K> keySet();

    /**
     * Returns a {@link Collection} view of the values contained in this map. The collection is
     * backed by the map, so changes to the map are reflected in the collection, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the collection is in progress (except through the iterator's
     * own {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The collection supports element removal, which removes the corresponding mapping from the
     * map, via the {@link Iterator#remove} (optionally), {@link Collection#remove}, {@link
     * Collection#removeAll}, {@link Collection#retainAll} and {@link Collection#clear} operations.
     *
     * <p>{@link Collection#remove}, {@link Collection#contains}, and {@link Collection#containsAll}
     * on the returned collection respect the map's {@link #valueEquivalence()}.
     *
     * <p>The values collection does not support the {@code add} or {@code addAll} operations.
     *
     * <p>The collection is created the first time this method is called, and returned in response
     * to all subsequent calls. No synchronization is performed, so there is a slight chance that
     * multiple calls to this method will not all return the same collection.
     *
     * @return a collection view of the values contained in this map
     */
    @Override
    Collection<V> values();

    /**
     * Returns a {@link Set} view of the mappings contained in this map. The set is backed by the
     * map, so changes to the map are reflected in the set, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the set is in progress (except through the iterator's own
     * {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The set supports element removal, which removes the corresponding mapping from the map,
     * via the {@link Iterator#remove} (optionally), {@link Set#remove}, {@link Set#removeAll},
     * {@link Set#retainAll}, and {@link Set#clear} operations.
     *
     * <p>{@link Set#remove}, {@link Set#contains}, and {@link Set#containsAll} operations on the
     * returned set as well as entry set's own {@code equals()} and {@code hashCode()} respect the
     * map's {@link #keyEquivalence()} and {@link #valueEquivalence()}, but the implementations of
     * {@code equals()} and {@code hashCode()} for the {@link Map.Entry} objects which can be
     * obtained from the set use built-in Java object equality and hash code for the map's keys and
     * values.
     *
     * <p>When this map has a custom {@link #keyEquivalence()} or {@link #valueEquivalence()},
     * {@code equals()} on the entry set works as follows: another object is considered equal to the
     * entry set if it is a {@link Set} which has the same size as this map; all elements in another
     * set are {@link Map.Entry} objects; and for all the entries in another set, {@code
     * thisMap.containsEntry(entryFromAnotherSet.getKey(), entryFromAnotherSet.getValue())} returns
     * true. {@code hashCode()} on the entry set is the same as on the map itself, see {@link
     * #hashCode()}.
     *
     * <p>The entry set does not support the {@code Set#add} and {@link Set#addAll} operations.
     *
     * <p>The set is created the first time this method is called, and returned in response to all
     * subsequent calls. No synchronization is performed, so there is a slight chance that multiple
     * calls to this method will not all return the same set.
     *
     * @return a set view of the mappings contained in this map
     */
    @Override
    ObjSet<Entry<K, V>> entrySet();

    /**
     * Returns true if the other object is a {@link Map}; this map and the given map have the same
     * {@linkplain #sizeAsLong size}; and for all entries in the other map, {@link #containsEntry
     * containsEntry(e.getKey(), e.getValue())} called on this map returns true.
     *
     * <p>Note that the specification above means that {@code equals()} depends on {@link
     * #keyEquivalence()} and {@link #valueEquivalence()} of this map, if they are custom. This may
     * be inconsistent with the general {@link Map#equals} contract.
     */
    @Override
    boolean equals(Object o);

    /**
     * Returns a sum of the following expressions: {@code keyEquivalence().hash(key) ^
     * valueEquivalence().hash(value)} applied to all entries in this map. Note that if this map has
     * a custom {@link #keyEquivalence()} or {@link #valueEquivalence()}, the resulting hash code
     * may be inconsistent with the general {@link Map#hashCode()} contract.
     */
    @Override
    int hashCode();
}
