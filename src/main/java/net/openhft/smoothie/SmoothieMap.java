/*
 *    Copyright 2015, 2016 Chronicle Software
 *    Copyright 2016, 2018 Roman Leventov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.openhft.smoothie;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.function.*;

import static net.openhft.smoothie.UnsafeAccess.U;
import static sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;

/**
 * Unordered {@code Map} with worst {@link #put(Object, Object) put} latencies more than 100 times
 * smaller than in ordinary hash table implementations like {@link HashMap}.
 *
 * <p>{@code SmoothieMap} supports pluggable keys' and values' equivalences, via {@link
 * #keysEqual(Object, Object)}, {@link #keyHashCode(Object)} and {@link #valuesEqual(Object, Object)
 * }, that could be overridden.
 *
 * <p>Functional additions to the {@code Map} interface, implemented in this class: {@link
 * #sizeAsLong()} ({@code SmoothieMap} maximum size is not limited with Java array maximum size,
 * that is {@code int}-indexed), {@link #containsEntry(Object, Object)}, {@link
 * #forEachWhile(BiPredicate)}, {@link #removeIf(BiPredicate)}.
 *
 * <p><b>Note that this implementation is not synchronized.</b> If multiple threads access a
 * {@code SmoothieMap} concurrently, and at least one of the threads modifies the map structurally,
 * it <i>must</i> be synchronized externally. (A structural modification is any operation that adds
 * or deletes one or more mappings; merely changing the value associated with a key that an instance
 * already contains is not a structural modification.) This is typically accomplished by
 * synchronizing on some object that naturally encapsulates the map.
 *
 * <p>If no such object exists, the map should be "wrapped" using the {@link
 * Collections#synchronizedMap Collections.synchronizedMap} method. This is best done at creation
 * time, to prevent accidental unsynchronized access to the map:<pre>
 *   Map m = Collections.synchronizedMap(new SmoothieMap(...));</pre>
 *
 * <p>{@code SmoothieMap} aims to be as close to {@link HashMap} behaviour and contract as possible,
 * to give an ability to be used as a drop-in replacement of {@link HashMap}. In particular:
 * <ul>
 *     <li>Supports {@code null} keys and values.</li>
 *     <li>Implements {@link Serializable} and {@link Cloneable}.</li>
 *     <li>The iterators returned by all of this class's "collection view methods" are <i>fail-fast
 *     </i>: if the map is structurally modified at any time after the iterator is created, in any
 *     way except through the iterator's own {@code remove} method, the iterator will throw a {@link
 *     ConcurrentModificationException}. Thus, in the face of concurrent modification, the iterator
 *     fails quickly and cleanly, rather than risking arbitrary, non-deterministic behavior at an
 *     undetermined time in the future.
 *
 *     <p>Note that the fail-fast behavior of an iterator cannot be guaranteed as it is, generally
 *     speaking, impossible to make any hard guarantees in the presence of unsynchronized concurrent
 *     modification. Fail-fast iterators (and all bulk operations like {@link #forEach(BiConsumer)},
 *     {@link #clear()}, {@link #replaceAll(BiFunction)} etc.) throw {@code
 *     ConcurrentModificationException} on a best-effort basis. Therefore, it would be wrong to
 *     write a program that depended on this exception for its correctness: <i>the fail-fast
 *     behavior of iterators should be used only to detect bugs.</i>
 *     </li>
 * </ul>
 *
 * <p>In terms of performance, favor calling bulk methods like {@link #forEach(BiConsumer)} to
 * iterating the {@code SmoothieMap} via {@code Iterator}, including for-each style iterations on
 * map's collections views. Especially if you need to remove entries during iteration (i. e. call
 * {@link Iterator#remove()}) and hash code for key objects is not cached on their side (like it is
 * cached, for example, in {@link String} class) - try to express your logic using {@link
 * #removeIf(BiPredicate)} method in this case.
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 *
 * @author Roman Leventov
 */
public class SmoothieMap<K, V> extends AbstractMap<K, V> implements Cloneable, Serializable {

    private static final long serialVersionUID = 0L;

    /**
     * SmoothieMap implementation discussion
     *
     *
     * In three lines
     * ==============
     *
     * Segmented, N segments - power of 2, each segment has hash table area and allocation area,
     * hash table slot: part of key's hash code -> index in alloc area, segment hash table size -
     * constantly 128, alloc area - 35-63 places for entries, but same for segments within a map
     *
     *
     * Algorithm Details (computer science POV)
     * ========================================
     *
     * {@link #segments} array is typically larger than number of actual distinct segment objects.
     * Normally, on Map construction it is twice larger (see {@link #chooseUpFrontScale}), e. g:
     * [seg#0, seg#1, seg#2, seg#3, seg#0, seg#1, seg#2, seg#3]
     *
     * ~~~
     *
     * When alloc area of the particular segment is exhausted, on {@link #put} or similar op,
     *
     * 1) if there are several (2, 4.. ) references to the segment in the {@link #segments} array -
     * half of them are replaced with refs to a newly created segment; entries from overflowed
     * segment are spread to this new segment.
     *
     * E. g. if segments array was
     * [seg#0, seg#1, seg#0, seg#2, seg#0, seg#1, seg#0, seg#2]
     *
     * And seg#0 alloc area, is exhausted, a new seg#3 is allocated, and occurrences at indexes
     * 2 and 6 of seg#0 are replaced with seg#3:
     *
     * [seg#0, seg#1, seg#3, seg#2, seg#0, seg#1, seg#3, seg#2]
     *
     * [call it minor hiccup],
     * see {@link Segment#splitSegmentAndPut}
     *
     * 2) if there is already only 1 ref to the segment in the array, first the whole
     * {@link #segments} array is doubled, then point 1) performed.
     *
     * E. g. is the array was
     * [seg#0, seg#1, seg#0, seg#2]
     *
     * And seg#1 alloc area is exhausted, since there is only one occurrence of seg#1 in the array,
     * it is doubled:
     *
     * [seg#0, seg#1, seg#0, seg#2, seg#0, seg#1, seg#0, seg#2]
     *
     * Then a new seg#3 is allocated, occurrence of seg#1 is replaced with seg#3 at index 5:
     *
     * [seg#0, seg#1, seg#0, seg#2, seg#0, seg#3, seg#0, seg#2]
     *
     * [call it major hiccup]
     * see {@link #doubleSegments()}
     *
     * Minor hiccup is new Segment allocation + some operations with at most 63 entries (incl.
     * hash code computation, though), so this is O(1)
     *
     * Major hiccup is allocation a new array +
     * System.arrayCopy of ~ ([map size] / 32-63 [average entries per segment] * 2-4 [array scale])
     * + Minor hiccup,
     * so this is O(N), but with very low constant, (~200 times smaller constant that O(N) of
     * ordinary rehash in hash tables)
     *
     * ~~~
     *
     * Lowest log({@code segments.length}) bits of keys' hash code are used to choose segment,
     * see {@link #segmentIndex(long)}
     *
     * Segment's hash table is open-addressing, linear hashing. "Keys" are 10-bit, "values"
     * are 6-bit indices in alloc area, hash table slot size - 16 bits (2 bytes)
     *
     * Bits from 53rd to 62nd (10 bits) of keys' hash code used as "keys" in segment's hash table
     * area (see {@link Segment#storedHash(long)}), hence this is meaningful for hash code to be
     * well-distributed in high bits too, see {@link #keyHashCode}
     *
     * Bits from 57th to 63th of keys' hash code used to locate the slot in segment's hash table.
     * I. e. they overlap by 6 bits (57th to 62nd) with bits, stored in this hash table themselves.
     *
     * Storing 4 "free" bits (53rd to 56th) of key's hash codes in hash table reduce probability
     * of:
     * - actual comparison on non-equal keys
     * - actual touch of alloc area when querying absent key
     *
     * by factor of 16, though already not very probably with 32-63 entries per segment on average.
     *
     * ~~~
     *
     * [Some theory just for ref]
     * Linear hash table has the following probs, if load factor is alpha:
     * average number of slots checked on successful search =
     *   1/2 + 1/(2 * (1 - alpha))
     * average number of slots checked on unsuccessful search =
     *   1/2 + 1/(2 * (1 - alpha)^2)
     *
     * For our ranges of load factor:
     *  average entries/segment: - load factor - av. success checks - av. unsuccess. checks
     * 32 - 0.25 - 1.17 - 1.39
     * 63 - 0.49 - 1.48 - 2.43
     *
     * ~~~
     *
     * Why that 32 - 63 average entries per segment?
     *
     * When average entries per segment is N, actual distribution of entries per segment is
     * Poisson(N). Allocation capacity of segment is chosen to minimize expected footprint,
     * accounting probability the segment to oversize capacity -> need to split in two segments. See
     * See MathDecisions#chooseOptimalCap().
     *
     * We need to support a range of average entries/segment [x, y] where x = y/2 because
     * segment's array sizes are granular to powers of 2.
     *
     * [32, 63] is the optimal [roundUp(y/2), y] frame in terms of average footprint, when
     * allocation capacity is limited by 63.
     *
     * ~~~
     *
     * Why want allocation capacity to be at most 63? To keep the whole allocation state in a single
     * {@code long} and find free places with cheap bitwise ops. This also explains why segments
     * are generally not larger than just a few dozens of entries. Also, at most 63 entries in
     * a segment allow to stored hash to overlap with hash bits, used to compute initial slot
     * index, only by 6 lower bits (57th - 62nd bits), still without need to recompute hash code
     * during {@link Segment#shiftRemove(long)}, see {@link Segment#shiftDistance(long, long)}.
     *
     * ~~~
     *
     * Why not 64 or 32 hash table slots, and accordingly less average entries per segment?
     * - Poisson variance higher
     * - Segment object header + segments array overhead is spread between lesser entries -> higher
     * - The only advantage of smaller segments is smaller minor hiccups, but when map become really
     * large major hiccup (doubling the segments array) dominates minor hiccup cost.
     *
     * So we want to make a segment as large as possible. But with limits:
     * -- allocation addressing bits + stored hash bits <= 16, to make segment hash slot of 2 bytes
     * -- the whole segment goes to a single 4K page to avoid several page faults per query
     * -- handle allocations with simple operations with a single {@code long}
     * -- well, still want minor hiccup to be not very large
     *
     * 256 slots / up to 127 allocation capacity is considerable, but 512+ is too much.
     * My tests don't show clear winner between 128 and 256 slots, but implementing 128 is simpler,
     * and minor hiccups are smaller
     *
     *
     * Hardware and runtime details
     * ============================
     *
     * Access {@link #segments} array via Unsafe in order not to touch segments array header
     * cache line/page, see {@link #segment(long)}
     *
     * ~~~
     *
     * Segment is a single object with hash table area (see {@link Segment#t0}) as long fields
     * and variable sized (object array like) allocation area, this requires to generate Segment
     * subclasses for all all distinct capacities, see {@link SegmentClasses}. There might be some
     * negative consequences. Class cache pollution?
     *
     * ~~~
     *
     * All primitives (hash codes, indices) are widened to {@code long} as early as possible,
     * proceed and transmitted in long type, and shortened as late as possible. There is an
     * observation that Hotspot (at least 8u60) generates clearer assembly from this, than from
     * code when primitives are proceed in {@code int} type.
     *
     * ~~~
     *
     * Some ritual techniques are used after classes from {@link java.util} without analysis,
     * examples include:
     *  - assignment-with-use instead of separate assignment and first use
     *  - inlined Objects.requireNonNull in {@link #compute} and similar methods
     *  - the specific layout of key(value) comparison, namely k == key || key != null && key.eq(k)
     *
     * ~~~
     *
     * Iteration is done over allocation bits, not hash table slots (how e. g. in ChronicleMap).
     * Advantages are:
     *  - if iterator.remove() is not called, hash table area (it's cache lines) is not touched
     *    at all
     *  - access allocation area sequentially (with iteration over hash table slots, it jumps over
     *    allocation area randomly)
     *  - don't need to account shift deletion corner cases like repetitive visit of some entries,
     *  that is probably not a big performance cost, but head ache and extra code. See e. g.
     *  how this problem is addressed in {@link Segment#removeIf(SmoothieMap, BiPredicate, int)}
     *
     * On the other hand, when iterating over allocation area, iterator.remove(), requires
     * re-computation of the key's hash code, that might be expensive, that is why {@link
     * #removeIf(BiPredicate)}, {@link net.openhft.smoothie.SmoothieMap.KeySet#removeAll(Collection)
     * } and similar operations on collections, that are going to remove entries during iteration
     * with good probability, are implemented via more traditional hash table slot traversal.
     */

    static final int GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY = 1 << 30;

    /**
     * @return a power of 2 number of segments
     */
    static int chooseSegments(long expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("Expected size should be positive, " +
                    expectedSize + " given ");
        }
        long segments = 1L;
        // overflow-aware
        while (expectedSize - segments * MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT > 0) {
            segments *= 2L;
        }
        if (segments > GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY) {
            throw new IllegalArgumentException("SmoothieMap is not able to hold " +
                    expectedSize + " entries");
        }
        return (int) segments;
    }

    // See MathDecisions in test/
    static final int MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT = 32;
    static final int MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT = 63;

    // See MathDecisions in test/
    static final long[] SEGMENTS_QUADRUPLING_FROM_REF_SIZE_4 = {
            17237966, 20085926, 23461869, 27467051, 32222765, 101478192, 118705641, 139126526,
            163353618, 192120413, 226305341, 266960817, 825529841, 971366784, 1144556172,
            1350385115, 1595184608, 1886539115, 2233536926L, 2647074163L, 1244014982, 598555262,
            294588684, 148182403, 76120369, 39902677, 21329967, 11619067, 6445637, 3639219,
            2089996, 1220217,
    };

    static final long[] SEGMENTS_QUADRUPLING_FROM_REF_SIZE_8 = {
            6333006, 7437876, 8753429, 10321069, 12190537, 37874373, 44596145, 52597103, 62128040,
            73490002, 87044486, 266960817, 315348276, 372980187, 441670897, 523597560, 621373710,
            738137712, 2233536926L, 2647074163L, 1244014982, 598555262, 294588684, 148182403,
            76120369, 39902677, 21329967, 11619067, 6445637, 3639219, 2089996, 1220217,
    };

    static final long[] SEGMENTS_QUADRUPLING_FROM = ARRAY_OBJECT_INDEX_SCALE == 4 ?
            SEGMENTS_QUADRUPLING_FROM_REF_SIZE_4 : SEGMENTS_QUADRUPLING_FROM_REF_SIZE_8;

    /**
     * @return 0 - default, 1 - doubling, 2 - quadrupling
     */
    static int chooseUpFrontScale(long expectedSize, int segments) {
        // if only one segment, no possibility to "skew" assuming given expectedSize is precise
        if (segments == 1)
            return 0;
        int roundedUpAverageEntriesPerSegment =
                Math.max((int) roundedUpDivide(expectedSize, segments),
                        MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT);
        assert roundedUpAverageEntriesPerSegment <= MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        int indexInSegmentsQuadruplingFromArray =
                roundedUpAverageEntriesPerSegment - MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        if (segments * 4L <= GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY &&
                indexInSegmentsQuadruplingFromArray < SEGMENTS_QUADRUPLING_FROM.length &&
                segments >= SEGMENTS_QUADRUPLING_FROM[indexInSegmentsQuadruplingFromArray]) {
            return 2; // quadrupling
        } else {
            if (segments * 2L <= GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY) {
                return 1; // doubling
            } else {
                return 0;
            }
        }
    }

    static long roundedUpDivide(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    // See MathDecisions in test/
    static final byte[] ALLOC_CAPACITIES_REF_SIZE_4 = {
            42, 43, 44, 45, 46, 48, 49, 50, 51, 52, 53, 54, 56, 57, 58, 59, 60, 61, 62, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63,
    };

    static final byte[] ALLOC_CAPACITIES_REF_SIZE_8 = {
            41, 42, 43, 44, 45, 47, 48, 49, 50, 51, 52, 54, 55, 56, 57, 58, 59, 60, 62, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63,
    };

    static final byte[] ALLOC_CAPACITIES = ARRAY_OBJECT_INDEX_SCALE == 4 ?
            ALLOC_CAPACITIES_REF_SIZE_4 : ALLOC_CAPACITIES_REF_SIZE_8;

    static final int MAX_ALLOC_CAPACITY = ALLOC_CAPACITIES[ALLOC_CAPACITIES.length - 1];
    static final int MIN_ALLOC_CAPACITY = ALLOC_CAPACITIES[0];

    static int chooseAllocCapacity(long expectedSize, int segments) {
        int averageEntriesPerSegment = Math.max((int) roundedUpDivide(expectedSize, segments),
                MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT);
        return ALLOC_CAPACITIES[
                averageEntriesPerSegment - MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT];
    }

    static int segmentsTier(int segments) {
        return Integer.numberOfTrailingZeros(segments);
    }

    /**
     * The value for spreading low bits of hash code to high. Approximately equal to {@code
     * round(2 ^ 64 * (sqrt(5) - 1))}, Java form of 11400714819323198485.
     *
     * @see #keyHashCode(Object)
     */
    protected static final long LONG_PHI_MAGIC = -7046029254386353131L;

    final int allocCapacity;
    transient MethodHandle segmentClassConstructor;
    int segmentsMask;
    int segmentsTier;
    transient Segment[] segments;
    transient long size;
    transient int modCount;

    private transient Set<K> keySet;
    private transient Collection<V> values;
    private transient Set<Entry<K, V>> entrySet;

    /**
     * Creates a new, empty {@code SmoothieMap}.
     */
    public SmoothieMap() {
        this(MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT);
    }

    /**
     * Creates a new, empty {@code SmoothieMap} accommodating the specified number of elements
     * without the need to dynamically resize.
     *
     * @param expectedSize The implementation performs internal
     * sizing to accommodate this many elements.
     * @throws IllegalArgumentException if the given {@code expectedSize} is negative or too large
     */
    public SmoothieMap(long expectedSize) {
        int segments = chooseSegments(expectedSize);
        int segmentsTier = segmentsTier(segments);
        int upFrontScale = chooseUpFrontScale(expectedSize, segments);
        allocCapacity = chooseAllocCapacity(expectedSize, segments);
        segmentClassConstructor = SegmentClasses.acquireClassConstructor(allocCapacity);

        initSegments(segments, segmentsTier, upFrontScale);
        updateSegmentsMask();
    }

    /**
     * Creates a new {@code SmoothieMap} with the same mappings as the given map.
     *
     * @param m the map
     */
    public SmoothieMap(Map<? extends K, ? extends V> m) {
        this(m.size());
        putAll(m);
    }

    private void initSegments(int segments, int segmentsTier, int upFrontScale) {
        this.segments = new Segment[segments << upFrontScale];
        for (int i = 0; i < segments; i++) {
            this.segments[i] = makeSegment(segmentsTier);
        }
        for (int scale = 0; scale < upFrontScale; scale++) {
            duplicateHalfSegments(segments << scale);
        }
    }

    /**
     * Returns {@code true} if the two given key objects should be considered equal for this {@code
     * SmoothieMap}.
     *
     * <p>This method should obey general equivalence relation rules (see {@link
     * Object#equals(Object)} documentation for details), and also should be consistent with {@link
     * #keyHashCode(Object)} method in this class (i. e. these methods should overridden together).
     *
     * <p>It is guaranteed that the first specified key is non-null and the arguments are not
     * identical (!=), in particular this means that to get {@link IdentityHashMap} behaviour it's
     * OK to override this method just returning {@code false}: <pre><code>
     * class IdentitySmoothieMap&lt;K, V&gt; extends SmoothieMap&lt;K, V&gt; {
     *
     *     &#064;Override
     *     protected boolean keysEqual(&#064;NotNull Object queriedKey, &#064;Nullable K keyInMap) {
     *         return false;
     *     }
     *
     *     &#064;Override
     *     protected long keyHashCode(&#064;Nullable Object key) {
     *         return System.identityHashCode(key) * LONG_PHI_MAGIC;
     *     }
     * }</code></pre>
     *
     * <p>This method accepts raw {@code Object} argument, because {@link Map} interface allows
     * to check presence of raw key, e. g. {@link #get(Object)} without {@link ClassCastException}.
     * If you want to subclass parameterized {@code SmoothieMap}, you should cast the arguments to
     * the key parameter class yourself, e. g.: <pre><code>
     * class DomainToIpMap extends SmoothieMap&lt;String, Integer&gt; {
     *
     *     &#064;Override
     *     protected boolean keysEqual(&#064;NotNull Object queriedDomain,
     *             &#064;Nullable String domainInMap) {
     *         return ((String) queriedDomain).equalsIgnoreCase(domainInMap));
     *     }
     *
     *     &#064;Override
     *     protected long keyHashCode(&#064;Nullable Object domain) {
     *         return LongHashFunction.xx_r39().hashChars(((String) domain).toLowerCase());
     *     }
     * }</code></pre>
     *
     * <p>Default implementation is {@code queriedKey.equals(keyInMap)}.
     *
     * @param queriedKey the first key to compare, that is passed to queries like {@link #get},
     * but might also be a key, that is already stored in the map
     * @param keyInMap the second key to compare, guaranteed that this key is already stored
     * in the map
     * @return {@code true} if the given keys should be considered equal for this map, {@code false}
     * otherwise
     * @see #keyHashCode(Object)
     */
    protected boolean keysEqual(@NotNull Object queriedKey, @Nullable K keyInMap) {
        return queriedKey.equals(keyInMap);
    }

    /**
     * Returns hash code for the given key.
     *
     * <p>This method should obey general hash code contract (see {@link Object#hashCode()}
     * documentation for details), also should be consistent with {@link #keysEqual(Object, Object)}
     * method in this class (i. e. these methods should overridden together).
     *
     * <p><b>The returned hash codes MUST be distributed well in the whole {@code long} range</b>,
     * because {@code SmoothieMap} implementation uses high bits of the returned value. When
     * overriding this method, if you are not sure that you hash codes are distributed well it is
     * recommended to multiply by {@link #LONG_PHI_MAGIC} finally, to spread low bits of the values
     * to the high.
     *
     * <p>The default implementation is {@code key != null ? key.hashCode() * LONG_PHI_MAGIC : 0L}.
     *
     * <p>Note that unlike {@code keysEqual()} method this one receives a {@code Nullable} argument.
     * If you want to protect yourself from putting {@code null} into {@code SmoothieMap}
     * occasionally, you could skip {@code null} checks in this method: <pre><code>
     * class NonNullKeysSmoothieMap&lt;K, V&gt; extends SmoothieMap&lt;K, V&gt; {
     *
     *     &#064;Override
     *     protected long keyHashCode(Object key) {
     *         // throws NullPointerException if the key is null
     *         return key.hashCode() * LONG_PHI_MAGIC;
     *     }
     * }</code></pre>
     *
     * <p>See other examples of this method override in the documentation to {@link
     * #keysEqual(Object, Object)} method.
     *
     * @param key the key (queried or already stored in the map) to compute hash code for
     * @return the hash code for the given key
     * @see #keysEqual(Object, Object)
     */
    protected long keyHashCode(@Nullable Object key) {
        return key != null ? key.hashCode() * LONG_PHI_MAGIC : 0L;
    }

    /**
     * Returns {@code true} if the two given value objects should be considered equal for this map.
     * This equivalence is used in the implementations of {@link #containsValue(Object)}, {@link
     * #containsEntry(Object, Object)}, {@link #remove(Object, Object)}, {@link #replace(Object,
     * Object, Object)} methods, and the methods of the {@linkplain #values() values} collection.
     *
     * <p>This method should obey general equivalence relation rules (see {@link
     * Object#equals(Object)} documentation for details).
     *
     * <p>It is guaranteed that the first specified value is non-null and the arguments are not
     * identical (!=).
     *
     * <p>This method accepts raw {@code Object} argument, because {@link Map} interface allows
     * to check presence of raw value, e. g. {@link #containsValue(Object)} without {@link
     * ClassCastException}. If you want to subclass parameterized {@code SmoothieMap}, you should
     * cast the arguments to the value parameter class yourself, e. g.: <pre><code>
     * class IpToDomainMap extends SmoothieMap&lt;Integer, String&gt; {
     *
     *     &#064;Override
     *     protected boolean valuesEqual(&#064;NotNull Object d1, &#064;Nullable String d2) {
     *         return ((String) d1).equalsIgnoreCase(d2);
     *     }
     * }</code></pre>
     *
     * <p>Default implementation is {@code queriedValue.equals(valueInMap)}.
     *
     * @param queriedValue the first value to compare, that is passed to queries like {@link
     * #containsValue(Object)}
     * @param valueInMap the second value to compare, this value is already stored in the map
     * @return {@code true} if the given values should be considered equal for this map
     */
    protected boolean valuesEqual(@NotNull Object queriedValue, @Nullable V valueInMap) {
        return queriedValue.equals(valueInMap);
    }

    private void updateSegmentsMask() {
        segmentsMask = segments.length - 1;
        segmentsTier = segmentsTier(segments());
    }

    final Segment<K, V> makeSegment(int tier) {
        try {
            @SuppressWarnings("unchecked")
            Segment<K, V> segment = (Segment<K, V>) segmentClassConstructor.invoke();
            segment.tier = tier;
            return segment;
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new AssertionError(t);
            }
        }
    }

    private void duplicateHalfSegments(int segments) {
        System.arraycopy(this.segments, 0, this.segments, segments, segments);
    }

    final void doubleSegments() {
        int oldSegments = segments();
        this.segments = Arrays.copyOf(this.segments, oldSegments * 2);
        duplicateHalfSegments(oldSegments);
        updateSegmentsMask();
    }

    final int segments() {
        return segmentsMask + 1;
    }

    final long segmentIndex(long hash) {
        return hash & segmentsMask;
    }

    final Segment<K, V> segment(long segmentIndex) {
        //noinspection unchecked
        return (Segment<K, V>) U.getObject(segments, ARRAY_OBJECT_BASE_OFFSET +
                (segmentIndex * ARRAY_OBJECT_INDEX_SCALE));
    }

    /**
     * Returns the number of key-value mappings in this map. If the map contains more than {@code
     * Integer.MAX_VALUE} elements, returns {@code Integer.MAX_VALUE}.
     *
     * @return the number of key-value mappings in this map
     */
    @Override
    public final int size() {
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    /**
     * Returns the number of entries in the map, as a {@code long} value (not truncated to {@code
     * Integer.MAX_VALUE}, if the map size exceeds it, as returned by {@link #size()} method).
     *
     * @return the number of key-value mappings in this map
     * @see #size()
     */
    @SuppressWarnings("unused")
    public final long sizeAsLong() {
        return size;
    }

    /**
     * Returns {@code true} if this map contains no key-value mappings.
     *
     * @return {@code true} if this map contains no key-value mappings
     */
    @Override
    public final boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns {@code true} if this map contains a mapping for the specified key. More formally,
     * returns {@code true} if and only if this map contains a mapping for a key {@code k} such that
     * {@link #keysEqual(Object, Object) keysEqual(key, k)}{@code == true}. (There can be at most
     * one such mapping.)
     *
     * @param key key whose presence in this map is to be tested
     * @return {@code true} if this map contains a mapping for the specified key
     */
    @Override
    public final boolean containsKey(Object key) {
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key))).find(this, hash, key) > 0;
    }

    /**
     * Returns {@code true} if this map contains a mapping of the given key and value. More
     * formally, this map should contain a mapping from a key {@code k} to a value {@code v} such
     * that {@link #keysEqual(Object, Object) keysEqual(key, k)}{@code == true} and {@link
     * #valuesEqual(Object, Object) valuesEqual(value, v)}{@code == true}. (There can be at most one
     * such mapping.)
     *
     * @param key the key of the mapping to check presence of
     * @param value the value of the mapping to check presence of
     * @return {@code true} if this map contains the specified mapping, {@code false} otherwise
     */
    public final boolean containsEntry(Object key, Object value) {
        long hash, allocIndex;
        Segment<K, V> segment;
        V v;
        return (allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .find(this, hash, key)) > 0 &&
                ((v = segment.readValue(allocIndex)) == value ||
                        (value != null && valuesEqual(value, v)));
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if this map contains
     * no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key {@code k} to a value {@code v}
     * such that {@link #keysEqual(Object, Object) keysEqual(key, k)}{@code == true}, then this
     * method returns {@code v}; otherwise it returns {@code null}. (There can be at most one such
     * mapping.)
     *
     * <p>A return value of {@code null} does not <i>necessarily</i> indicate that the map contains
     * no mapping for the key; it's also possible that the map explicitly maps the key to {@code
     * null}. The {@link #containsKey containsKey} operation may be used to distinguish these two
     * cases.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or
     *         {@code null} if this map contains no mapping for the key
     */
    @Override
    public final V get(Object key) {
        long hash, allocIndex;
        Segment<K, V> segment;
        return (allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .find(this, hash, key)) > 0 ?
                segment.readValue(allocIndex) : null;
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code defaultValue} if this map
     * contains no mapping for the key.
     *
     * @param key the key whose associated value is to be returned
     * @param defaultValue the default mapping of the key
     * @return the value to which the specified key is mapped, or {@code defaultValue} if this map
     * contains no mapping for the key
     */
    @Override
    public final V getOrDefault(Object key, V defaultValue) {
        long hash, allocIndex;
        Segment<K, V> segment;
        return (allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .find(this, hash, key)) > 0 ?
                segment.readValue(allocIndex) : defaultValue;
    }

    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value.
     *
     * @param key key with which the specified value is associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or {@code null} if there was no
     * mapping for the key. (A {@code null} return can also indicate that the map previously
     * associated {@code null} with the key.)
     */
    @Override
    public final V replace(K key, V value) {
        long hash, allocIndex;
        Segment<K, V> segment;
        V oldValue;
        if ((allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .find(this, hash, key)) > 0) {
            oldValue = segment.readValue(allocIndex);
            segment.writeValue(allocIndex, value);
            return oldValue;
        }
        return null;
    }

    /**
     * Replaces the entry for the specified key only if currently mapped to the specified value.
     * Values are compared using {@link #valuesEqual(Object, Object)} method.
     *
     * @param key key with which the specified value is associated
     * @param oldValue value expected to be associated with the specified key
     * @param newValue value to be associated with the specified key
     * @return {@code true} if the value was replaced
     */
    @Override
    public final boolean replace(K key, V oldValue, V newValue) {
        long hash, allocIndex;
        Segment<K, V> segment;
        V v;
        if ((allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .find(this, hash, key)) > 0 &&
                ((v = segment.readValue(allocIndex)) == oldValue ||
                        (oldValue != null && valuesEqual(oldValue, v)))) {
            segment.writeValue(allocIndex, newValue);
            return true;
        }
        return false;
    }

    /**
     * Associates the specified value with the specified key in this map. If the map previously
     * contained a mapping for the key, the old value is replaced by the specified value. (A map
     * {@code m} is said to contain a mapping for a key {@code k} if and only if {@link
     * #containsKey(Object) m.containsKey(k)} would return {@code true}.)
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     * mapping for {@code key}. (A {@code null} return can also indicate that the map previously
     * associated {@code null} with {@code key}.)
     */
    @Override
    public final V put(K key, V value) {
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key))).put(this, hash, key, value, false);
    }

    /**
     * If the specified key is not already associated with a value (or is mapped to {@code null})
     * associates it with the given value and returns {@code null}, else returns the current value.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or {@code null} if there was no
     * mapping for the key. (A {@code null} return can also indicate that the map previously
     * associated {@code null} with the key.)
     */
    @Override
    public final V putIfAbsent(K key, V value) {
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key))).put(this, hash, key, value, true);
    }

    /**
     * Removes the mapping for a key from this map if it is present. More formally, if this map
     * contains a mapping from key {@code k} to value {@code v} such that {@link #keysEqual(Object,
     * Object) keysEqual(key, k)}{@code == true}, that mapping is removed. (The map can contain at
     * most one such mapping.)
     *
     * <p>Returns the value to which this map previously associated the key, or {@code null} if the
     * map contained no mapping for the key.
     *
     * <p>A return value of {@code null} does not <i>necessarily</i> indicate that the map contained
     * no mapping for the key; it's also possible that the map explicitly mapped the key to
     * {@code null}.
     *
     * <p>The map will not contain a mapping for the specified key once the
     * call returns.
     *
     * @param key key whose mapping is to be removed from the map
     * @return the previous value associated with {@code key}, or
     *         {@code null} if there was no mapping for {@code key}.
     */
    @Override
    public final V remove(Object key) {
        long hash, allocIndex;
        Segment<K, V> segment;
        V oldValue;
        if ((allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .remove(this, hash, key, null, false)) > 0) {
            oldValue = segment.readValue(allocIndex);
            segment.eraseAlloc(allocIndex);
            return oldValue;
        }
        return null;
    }

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified
     * value. Values are compared using {@link #valuesEqual(Object, Object)} method.
     *
     * @param key key with which the specified value is associated
     * @param value value expected to be associated with the specified key
     * @return {@code true} if the value was removed
     */
    @Override
    public final boolean remove(Object key, Object value) {
        long hash, allocIndex;
        Segment<K, V> segment;
        if ((allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                .remove(this, hash, key, value, true)) > 0) {
            segment.eraseAlloc(allocIndex);
            return true;
        }
        return false;
    }

    /**
     * If the specified key is not already associated with a value (or is mapped to {@code null}),
     * attempts to compute its value using the given mapping function and enters it into this map
     * unless {@code null}.
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
     * @throws NullPointerException if the mappingFunction is null
     */
    @Override
    public final V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null)
            throw new NullPointerException();
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key)))
                .computeIfAbsent(this, hash, key, mappingFunction);
    }

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
     * @throws NullPointerException if the remappingFunction is null
     */
    @Override
    public final V computeIfPresent(
            K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key)))
                .computeIfPresent(this, hash, key, remappingFunction);
    }

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
     * @throws NullPointerException if the remappingFunction is null
     */
    @Override
    public final V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key)))
                .compute(this, hash, key, remappingFunction);
    }

    /**
     * If the specified key is not already associated with a value or is associated with null,
     * associates it with the given non-null value. Otherwise, replaces the associated value with
     * the results of the given remapping function, or removes if the result is {@code null}. This
     * method may be of use when combining multiple mapped values for a key. For example, to either
     * create or append a {@code String msg} to a value mapping:
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
     * @param value the non-null value to be merged with the existing value
     *        associated with the key or, if no existing value or a null value
     *        is associated with the key, to be associated with the key
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the specified key, or null if no
     *         value is associated with the key
     * @throws NullPointerException if remappingFunction is null
     */
    @Override
    public final V merge(
            K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null)
            throw new NullPointerException();
        if (remappingFunction == null)
            throw new NullPointerException();
        long hash;
        return segment(segmentIndex(hash = keyHashCode(key)))
                .merge(this, hash, key, value, remappingFunction);
    }

    /**
     * Extension rules make order of iteration of segments a little weird, avoiding visiting
     * the same segment twice
     *
     * <p>Geven segmentsMask = 0b1111, iterates segments in order:
     * 0000
     * 1000
     * 0100
     * 1100
     * 0010
     * .. etc, i. e. grows "from left to right"
     *
     * <p>In all lines above - +1 increment, that might be +2, +4 or higher, depending on how much
     * segment's tier is smaller than current map tier.
     */
    private long nextSegmentIndex(long segmentIndex, Segment segment) {
        long segmentsTier = this.segmentsTier;
        segmentIndex <<= -segmentsTier;
        segmentIndex = Long.reverse(segmentIndex);
        int numberOfArrayIndexesWithThisSegment = 1 << (segmentsTier - segment.tier);
        segmentIndex += numberOfArrayIndexesWithThisSegment;
        if (segmentIndex > segmentsMask)
            return -1;
        segmentIndex = Long.reverse(segmentIndex);
        segmentIndex >>>= -segmentsTier;
        return segmentIndex;
    }

    private long incrementSegmentIndex(long segmentIndex, long segmentsTier) {
        segmentIndex <<= -segmentsTier;
        segmentIndex = Long.reverse(segmentIndex);
        segmentIndex++;
        segmentIndex = Long.reverse(segmentIndex);
        segmentIndex >>>= -segmentsTier;
        return segmentIndex;
    }

    /**
     * Performs the given action for each entry in this map until all entries have been processed or
     * the action throws an exception. Actions are performed in the order of {@linkplain #entrySet()
     * entry set} iteration. Exceptions thrown by the action are relayed to the caller.
     *
     * @param action The action to be performed for each entry
     * @throws NullPointerException if the specified action is null
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     * @see #forEachWhile(BiPredicate)
     */
    @Override
    public final void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int mc = this.modCount;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            (segment = segment(segmentIndex)).forEach(action);
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
    }

    /**
     * Checks the given {@code predicate} on each entry in this map until all entries have been
     * processed or the predicate returns false for some entry, or throws an Exception. Exceptions
     * thrown by the predicate are relayed to the caller.
     *
     * <p>The entries will be processed in the same order as the entry set iterator, and {@link
     * #forEach(BiConsumer)} order.
     *
     * <p>If the map is empty, this method returns {@code true} immediately.
     *
     * @param predicate the predicate to be checked for each entry
     * @return {@code true} if the predicate returned {@code true} for all entries of the map,
     * {@code false} if it returned {@code false} for some entry
     * @throws NullPointerException if the given {@code predicate} is {@code null}
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     * @see #forEach(BiConsumer)
     */
    @SuppressWarnings("unused")
    public final boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
        Objects.requireNonNull(predicate);
        boolean interrupted = false;
        int mc = this.modCount;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            if (!(segment = segment(segmentIndex)).forEachWhile(predicate)) {
                interrupted = true;
                break;
            }
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
        return !interrupted;
    }

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
    public final void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int mc = this.modCount;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            (segment = segment(segmentIndex)).replaceAll(function);
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
    }

    /**
     * Returns {@code true} if this map maps one or more keys to the specified value. More formally,
     * returns {@code true} if and only if this map contains at least one mapping to a value {@code
     * v} such that {@link #valuesEqual valuesEqual(value, v)}{@code == true}. This operation
     * requires time linear in the map size.
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the specified value
     */
    @Override
    public final boolean containsValue(Object value) {
        int mc = this.modCount;
        @SuppressWarnings("unchecked")
        V v = (V) value;
        boolean found = false;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            if ((segment = segment(segmentIndex)).containsValue(this, v)) {
                found = true;
                break;
            }
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
        return found;
    }

    /**
     * Returns a shallow copy of this {@code SmoothieMap} instance: the keys and values themselves
     * are not cloned.
     *
     * @return a shallow copy of this map
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during operation
     */
    @Override
    public final SmoothieMap<K, V> clone() {
        int mc = this.modCount;
        SmoothieMap<K, V> result;
        try {
            //noinspection unchecked
            result = (SmoothieMap<K, V>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
        result.keySet = null;
        result.values = null;
        result.entrySet = null;
        result.segments = segments.clone();
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            Segment<K, V> segmentClone = (segment = segment(segmentIndex)).clone();
            result.segments[(int) segmentIndex] = segmentClone;
            int numberOfArrayIndexesWithThisSegment = 1 << (segmentsTier - segment.tier);
            long si = segmentIndex;
            for (int i = 1; i < numberOfArrayIndexesWithThisSegment; i++) {
                si = incrementSegmentIndex(si, segmentsTier);
                result.segments[(int) si] = segmentClone;
            }
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
        return result;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
        s.writeLong(size);
        writeAllEntries(s);
    }

    private void writeAllEntries(ObjectOutputStream s) throws IOException {
        int mc = this.modCount;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            (segment = segment(segmentIndex)).writeAllEntries(s);
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        segmentClassConstructor = SegmentClasses.acquireClassConstructor(allocCapacity);
        long size = s.readLong();
        if (size < 0)
            throw new InvalidObjectException("Invalid size: " + size);
        int segments = chooseSegments(size);
        int segmentsTier = segmentsTier(segments);
        int actualTier = this.segmentsTier;
        if (segmentsTier > actualTier) {
            throw new InvalidObjectException("Either size: " + size +
                    " or segmentsMask: " + segmentsMask + " corrupted");
        }
        int upFrontScale = actualTier - segmentsTier;
        initSegments(segments, segmentsTier, upFrontScale);
        for (int i = 0; i < size; i++) {
            //noinspection unchecked
            put((K) s.readObject(), (V) s.readObject());
        }
    }

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
    public final void putAll(Map<? extends K, ? extends V> m) {
        m.forEach(this::put);
    }

    /**
     * Removes all of the mappings from this map. The map will be empty after this call returns.
     *
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during operation
     */
    @Override
    public final void clear() {
        int mc = this.modCount;
        Segment<K, V> segment;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            if (!(segment = segment(segmentIndex)).isEmpty()) {
                segment.clear(this);
                mc++;
                modCount++;
            }
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
    }

    /**
     * Removes all of the entries of this map that satisfy the given predicate. Errors or runtime
     * exceptions thrown during iteration or by the predicate are relayed to the caller.
     *
     * <p>Note the order in which this method visits entries is <i>different</i> from the iteration
     * and {@link #forEach(BiConsumer)} order.
     *
     * @param filter a predicate which returns {@code true} for entries to be removed
     * @return {@code true} if any entries were removed
     * @throws NullPointerException if the specified {@code filter} is {@code null}
     * @throws ConcurrentModificationException if any structural modification of the map (new entry
     * insertion or an entry removal) is detected during iteration
     */
    public final boolean removeIf(BiPredicate<? super K, ? super V> filter) {
        Objects.requireNonNull(filter);
        if (isEmpty())
            return false;
        Segment<K, V> segment;
        int mc = this.modCount;
        int initialModCount = mc;
        for (long segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
            mc = (segment = segment(segmentIndex)).removeIf(this, filter, mc);
        }
        if (mc != modCount)
            throw new ConcurrentModificationException();
        return mc != initialModCount;
    }

    /**
     * Returns a {@link Set} view of the keys contained in this map. The set is backed by the map,
     * so changes to the map are reflected in the set, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the set is in progress (except through the iterator's own
     * {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The set supports element removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove}, {@code removeAll}, {@code retainAll},
     * and {@code clear} operations. These operations and queries ({@code contains()}, {@code
     * containsAll()}) respect key equivalence possibly overridden by {@link #keyHashCode(Object)}
     * and {@link #keysEqual(Object, Object)} methods, but set's own {@code hashCode()} use built-in
     * Java object hash code for the key objects.
     *
     * <p>The key set does not support the {@code add} or {@code addAll} operations.
     *
     * <p>The set is created the first time this method is called, and returned in response to all
     * subsequent calls. No synchronization is performed, so there is a slight chance that multiple
     * calls to this method will not all return the same set.
     *
     * @return a set view of the keys contained in this map
     */
    @NotNull
    @Override
    public final Set<K> keySet() {
        Set<K> ks;
        return (ks = keySet) != null ? ks : (keySet = new KeySet());
    }

    final class KeySet extends AbstractSet<K> {
        public final int size() {
            return SmoothieMap.this.size();
        }

        public final void clear() {
            SmoothieMap.this.clear();
        }

        @NotNull
        public final Iterator<K> iterator() {
            return new KeyIterator();
        }

        public final boolean contains(Object o) {
            return containsKey(o);
        }

        public final boolean remove(Object key) {
            long hash, allocIndex;
            Segment<K, V> segment;
            if ((allocIndex = (segment = segment(segmentIndex(hash = keyHashCode(key))))
                    .remove(SmoothieMap.this, hash, key, null, false)) > 0) {
                segment.eraseAlloc(allocIndex);
                return true;
            }
            return false;
        }

        @Override
        public final void forEach(Consumer<? super K> action) {
            Objects.requireNonNull(action);
            int mc = SmoothieMap.this.modCount;
            Segment<K, V> segment;
            for (long segmentIndex = 0; segmentIndex >= 0;
                 segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
                segment = segment(segmentIndex);
                segment.forEachKey(action);
            }
            if (mc != modCount)
                throw new ConcurrentModificationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            if (size() > c.size() &&
                    // This condition ensures keyHashCode() is not overridden.
                    // Otherwise this optimization might make the removeAll() impl violating
                    // the contract, "remove all elements from this, containing in the given
                    // collection".
                    SmoothieMap.this.getClass() == SmoothieMap.class) {
                for (Iterator<?> it = c.iterator(); it.hasNext();) {
                    if (remove(it.next())) {
                        it.forEachRemaining(this::remove);
                        return true;
                    }
                }
                return false;
            } else {
                return SmoothieMap.this.removeIf((k, v) -> c.contains(k));
            }
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> !c.contains(k));
        }
    }

    /**
     * Returns a {@link Collection} view of the values contained in this map. The collection is
     * backed by the map, so changes to the map are reflected in the collection, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the collection is in progress (except through the iterator's
     * own {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The collection supports element removal, which removes the corresponding mapping from the
     * map, via the {@code Iterator.remove}, {@code Collection.remove}, {@code removeAll}, {@code
     * retainAll} and {@code clear} operations. These operations and queries ({@code contains()},
     * {@code containsAll()}) respect value equivalence possibly overridden by {@link
     * #valuesEqual(Object, Object)} method.
     *
     * <p>The values collection does not support the {@code add} or {@code addAll} operations.
     *
     * <p>The collection is created the first time this method is called, and returned in response
     * to all subsequent calls. No synchronization is performed, so there is a slight chance that
     * multiple calls to this method will not all return the same collection.
     *
     * @return a collection view of the values contained in this map
     */
    @NotNull
    @Override
    public final Collection<V> values() {
        Collection<V> vs;
        return (vs = values) != null ? vs : (values = new Values());
    }

    final class Values extends AbstractCollection<V> {
        @Override
        public int size() {
            return SmoothieMap.this.size();
        }

        @Override
        public void clear() {
            SmoothieMap.this.clear();
        }

        @NotNull
        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(Object o) {
            return containsValue(o);
        }

        @Override
        public void forEach(Consumer<? super V> action) {
            Objects.requireNonNull(action);
            int mc = SmoothieMap.this.modCount;
            Segment<K, V> segment;
            for (long segmentIndex = 0; segmentIndex >= 0;
                 segmentIndex = nextSegmentIndex(segmentIndex, segment)) {
                segment = segment(segmentIndex);
                segment.forEachValue(action);
            }
            if (mc != modCount)
                throw new ConcurrentModificationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> c.contains(v));
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> !c.contains(v));
        }
    }

    /**
     * Returns a {@link Set} view of the mappings contained in this map. The set is backed by the
     * map, so changes to the map are reflected in the set, and vice-versa.
     *
     * <p>If a structural modification of the map (new entry insertion or an entry removal) is
     * detected while an iteration over the set is in progress (except through the iterator's own
     * {@code remove} operation), {@link ConcurrentModificationException} is thrown.
     *
     * <p>The set supports element removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove}, {@code removeAll}, {@code retainAll} and
     * {@code clear} operations. These operations respect custom key and value equivalences possibly
     * overridden by {@link #keyHashCode(Object)}, {@link #keysEqual(Object, Object)} and {@link
     * #valuesEqual(Object, Object)} methods, but set's own {@code hashCode()} and {@code hashCode()
     * } and {@code equals()} implementations for the entries, returned by the entry set, use
     * built-in Java {@code equals()} and {@code hashCode()} implementations for the map's keys and
     * values.
     *
     * <p>The entry set does not support the {@code add} or {@code addAll} operations.
     *
     * <p>The set is created the first time this method is called, and returned in response to all
     * subsequent calls. No synchronization is performed, so there is a slight chance that multiple
     * calls to this method will not all return the same set.
     *
     * @return a set view of the mappings contained in this map
     */
    @NotNull
    @Override
    public final Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> es;
        return (es = entrySet) != null ? es : (entrySet = new EntrySet());
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public int size() {
            return SmoothieMap.this.size();
        }

        @Override
        public void clear() {
            SmoothieMap.this.clear();
        }

        @NotNull
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Entry))
                return false;
            Entry<?, ?> e = (Entry<?, ?>) o;
            return containsEntry(e.getKey(), e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Entry) {
                Entry<?, ?> e = (Entry<?, ?>) o;
                return SmoothieMap.this.remove(e.getKey(), e.getValue());
            }
            return false;
        }
    }


    abstract class SmoothieIterator<E> implements Iterator<E> {
        private int expectedModCount = modCount;
        private long segmentIndex;
        Segment<K, V> segment;
        long allocations;
        long allocIndex;
        private long nextSegmentIndex;

        SmoothieIterator() {
            initSegmentIndex(0);
        }

        @Override
        public final boolean hasNext() {
            return allocations != 0 || hasNextSegment();
        }

        private boolean hasNextSegment() {
            Segment<K, V> nextSegment = this.segment;
            for (long nextSegmentIndex = segmentIndex;
                 (nextSegmentIndex = nextSegmentIndex(nextSegmentIndex, nextSegment)) >= 0; ) {
                if (!(nextSegment = segment(nextSegmentIndex)).isEmpty()) {
                    this.nextSegmentIndex = nextSegmentIndex;
                    return true;
                }
            }
            this.nextSegmentIndex = 0;
            return false;
        }

        final void advance() {
            if (expectedModCount != modCount)
                throw new ConcurrentModificationException();
            for (long allocations = this.allocations, allocIndex = this.allocIndex;
                 allocations != 0; allocations <<= 1) {
                allocIndex--;
                if (allocations < 0) {
                    this.allocations = allocations << 1;
                    this.allocIndex = allocIndex;
                    return;
                }
            }
            advanceSegment();
            advance(); // recursion for brevity
        }

        private void advanceSegment() {
            if (nextSegmentIndex > 0) {
                initSegmentIndex(nextSegmentIndex);
            } else if (nextSegmentIndex < 0) {
                hasNextSegment();
                advanceSegment(); // recursion for brevity
            } else {
                assert nextSegmentIndex == 0;
                throw new NoSuchElementException();
            }
        }

        private void initSegmentIndex(long nextSegmentIndex) {
            this.nextSegmentIndex = -1;
            long a, tail;
            allocations = (a = ~(segment = segment(segmentIndex = nextSegmentIndex)).bitSet) <<
                    (tail = Long.numberOfLeadingZeros(a));
            allocIndex = 65 - tail;
        }

        @Override
        public final void remove() {
            long allocIndex;
            Segment<K, V> segment;
            if ((segment = this.segment).isFree((allocIndex = this.allocIndex)))
                throw new IllegalStateException("Element already removed on this iteration");
            segment.iterationRemove(SmoothieMap.this, segment.readKey(allocIndex), allocIndex);
            expectedModCount++;
        }
    }

    final class KeyIterator extends SmoothieIterator<K> {
        @Override
        public K next() {
            advance();
            return segment.readKey(allocIndex);
        }
    }

    final class ValueIterator extends SmoothieIterator<V> {
        @Override
        public V next() {
            advance();
            return segment.readValue(allocIndex);
        }
    }

    final class SmoothieEntry extends SimpleEntry<K, V> {
        final Segment<K, V> segment;
        final long allocIndex;
        final int mc = modCount;

        public SmoothieEntry(Segment<K, V> segment, long allocIndex) {
            super(segment.readKey(allocIndex), segment.readValue(allocIndex));
            this.segment = segment;
            this.allocIndex = allocIndex;
        }

        @Override
        public V setValue(V value) {
            if (mc != modCount)
                throw new ConcurrentModificationException();
            segment.writeValue(allocIndex, value);
            return super.setValue(value);
        }
    }

    final class EntryIterator extends SmoothieIterator<Entry<K, V>> {
        @Override
        public Entry<K, V> next() {
            advance();
            return new SmoothieEntry(segment, allocIndex);
        }
    }
}
