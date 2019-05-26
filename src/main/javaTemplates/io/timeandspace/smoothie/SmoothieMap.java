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
import com.google.errorprone.annotations.DoNotCall;
import io.timeandspace.collect.Equivalence;
import io.timeandspace.collect.map.KeyValue;
import io.timeandspace.collect.map.ObjObjMap;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;
import org.checkerframework.common.value.qual.IntVal;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.InflatedSegmentQueryContext.COMPUTE_IF_PRESENT_ENTRY_REMOVED;
import static io.timeandspace.smoothie.InflatedSegmentQueryContext.Node;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.DELETED_SLOT_COUNT_UNIT;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.GROUP_SLOTS;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.SEGMENT_ORDER_UNIT;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.allocCapacity;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.clearAllocBit;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.deletedSlotCount;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.extractBitSetForIteration;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.getBitSetAndState;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.incrementSegmentOrderAndZeroDeletedSlotCount;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.isInflatedBitSetAndState;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.lowestFreeAllocIndex;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.makeBitSetAndStateForPrivatelyPopulatedSegment;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.makeBitSetAndStateWithNewAllocCapacity;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.makeBitSetAndStateWithNewDeletedSlotCount;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.readControlsGroup;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.readData;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.segmentOrder;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.segmentSize;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.setBitSetAndState;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.setBitSetAndStateAfterBulkOperation;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.setLowestAllocBit;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.updateDeletedSlotCountAndSetLowestAllocBit;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.GROUP_BITS;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.GROUP_SLOTS_DIVISION_SHIFT;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.HASH_TABLE_SLOTS;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.groupIndexesEqualModuloHashTableGroups;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.readControlByte;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.replaceData;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.writeControlByte;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.writeControlByteWithConditionalCloning;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.writeData;
import static io.timeandspace.smoothie.SmoothieMap.Segment.DELETED_CONTROL;
import static io.timeandspace.smoothie.SmoothieMap.Segment.EMPTY_CONTROL;
import static io.timeandspace.smoothie.SmoothieMap.Segment.HASH_CONTROL_BITS;
import static io.timeandspace.smoothie.SmoothieMap.Segment.HASH_DATA_BITS;
import static io.timeandspace.smoothie.SmoothieMap.Segment.INFLATED_SEGMENT_MARKER_CONTROLS_GROUP;
import static io.timeandspace.smoothie.SmoothieMap.Segment.LOG_HASH_TABLE_SIZE;
import static io.timeandspace.smoothie.SmoothieMap.Segment.MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING;
import static io.timeandspace.smoothie.SmoothieMap.Segment.SEGMENT_MAX_NON_EMPTY_SLOTS;
import static io.timeandspace.smoothie.SmoothieMap.Segment.SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES;
import static io.timeandspace.smoothie.SmoothieMap.Segment.addSlotIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.allocIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.branchlessDeletedOrEmpty;
import static io.timeandspace.smoothie.SmoothieMap.Segment.checkAllocIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.clearLowestSetBit;
import static io.timeandspace.smoothie.SmoothieMap.Segment.covertAllDeletedToEmptyAndFullToDeletedControlSlots;
import static io.timeandspace.smoothie.SmoothieMap.Segment.eraseKeyAndValue;
import static io.timeandspace.smoothie.SmoothieMap.Segment.extractMatchingEmpty;
import static io.timeandspace.smoothie.SmoothieMap.Segment.hashControlBits;
import static io.timeandspace.smoothie.SmoothieMap.Segment.isMatchingEmpty;
import static io.timeandspace.smoothie.SmoothieMap.Segment.lowestMatchIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.lowestMatchIndexFromTrailingZeros;
import static io.timeandspace.smoothie.SmoothieMap.Segment.makeData;
import static io.timeandspace.smoothie.SmoothieMap.Segment.match;
import static io.timeandspace.smoothie.SmoothieMap.Segment.matchEmpty;
import static io.timeandspace.smoothie.SmoothieMap.Segment.matchEmptyOrDeleted;
import static io.timeandspace.smoothie.SmoothieMap.Segment.matchHashDataBits;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readKey;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readKeyChecked;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readValue;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readValueChecked;
import static io.timeandspace.smoothie.SmoothieMap.Segment.slotIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.swapHashTablesAndAllocAreasDuringSplit;
import static io.timeandspace.smoothie.SmoothieMap.Segment.writeEntry;
import static io.timeandspace.smoothie.SmoothieMap.Segment.writeKey;
import static io.timeandspace.smoothie.SmoothieMap.Segment.writeValue;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SHIFT;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.UnsafeUtils.minInstanceFieldOffset;
import static io.timeandspace.smoothie.Utils.BYTE_SIZE_DIVISION_SHIFT;
import static io.timeandspace.smoothie.Utils.assertEqual;
import static io.timeandspace.smoothie.Utils.assertIsPowerOfTwo;
import static io.timeandspace.smoothie.Utils.assertNonNull;
import static io.timeandspace.smoothie.Utils.assertThat;
import static java.lang.Math.max;
import static java.lang.Math.min;
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
 * TODO don't extend AbstractMap
 */
public class SmoothieMap<K, V> extends AbstractMap<K, V>
        implements ObjObjMap<K, V>, Cloneable, Serializable {

    static final double MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB = 0.2;
    static final double MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB = 0.00001;

    public static <K, V> SmoothieMapBuilder<K, V> newBuilder() {
        return SmoothieMapBuilder.newBuilder();
    }

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
     * {@link #segmentsArray} array is typically larger than number of actual distinct segment objects.
     * Normally, on Map construction it is twice larger (see {@link #chooseUpFrontScale}), e. g:
     * [seg#0, seg#1, seg#2, seg#3, seg#0, seg#1, seg#2, seg#3]
     *
     * ~~~
     *
     * When alloc area of the particular segment is exhausted, on {@link #put} or similar op,
     *
     * 1) if there are several (2, 4.. ) references to the segment in the {@link #segmentsArray} array -
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
     * {@link #segmentsArray} array is doubled, then point 1) performed.
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
     * see {@link #tryDoubleSegmentsArray()}
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
     * Storing 4 "freeEntrySlot" bits (53rd to 56th) of key's hash codes in hash table reduce probability
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
     * Access {@link #segmentsArray} array via Unsafe in order not to touch segments array header
     * cache line/page, see {@link #segmentByHash(long)}
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
     *  - inlined Utils.requireNonNull in {@link #compute} and similar methods
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
     * #removeIf(BiPredicate)}, {@link io.timeandspace.smoothie.SmoothieMap.KeySet#removeAll
     * } and similar operations on collections, that are going to remove entries during iteration
     * with good probability, are implemented via more traditional hash table slot traversal.
     */

    /**
     * {@link #segmentsArray} is always power of two sized. An array in Java couldn't have length
     * 2^31 because it's greater than {@link Integer#MAX_VALUE}, so 30 is the maximum.
     */
    static final int MAX_SEGMENTS_ARRAY_ORDER = 30;
    private static final int MAX_SEGMENTS_ARRAY_LENGTH = 1 << MAX_SEGMENTS_ARRAY_ORDER;

    private static final long
            MAP_AVERAGE_SEGMENTS_SATURATION_SEGMENT_CAPACITY_POWER_OF_TWO_COMPONENTS =
            ((long) SEGMENT_MAX_ALLOC_CAPACITY / MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE) *
                    MAX_SEGMENTS_ARRAY_LENGTH;

    /**
     * Returns the minimum of {@link #MAX_SEGMENTS_ARRAY_ORDER} and
     * log2(ceilingPowerOfTwo(divideCeiling(size, {@link Segment#SEGMENT_MAX_ALLOC_CAPACITY}))).
     * The given size must be positive.
     */
    static int doComputeAverageSegmentOrder(long size) {
        assert size > 0;
        // The implementation of this method aims to avoid integral division and branches. The idea
        // is that instead of dividing the size by SEGMENT_MAX_ALLOC_CAPACITY = 48, the size is
        // first divided by 16 (that is replaced with right shift) and then additionally by 3, that
        // is replaceable with multiplication and right shift too (a bit twiddling hack).

        // saturatedSize is needed for proper ceiling division by SEGMENT_MAX_ALLOC_CAPACITY.
        long saturatedSize = size + SEGMENT_MAX_ALLOC_CAPACITY - 1;
        long segmentCapacityPowerOfTwoComponents = min(
                // [Replacing division with shift]
                saturatedSize >>> MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT,
                MAP_AVERAGE_SEGMENTS_SATURATION_SEGMENT_CAPACITY_POWER_OF_TWO_COMPONENTS
        );
        // The following line is an obscure form of
        // `int averageSegments = (int) (segmentCapacityPowerOfTwoComponents / 3);`, where 3 is
        // the constant equal to SEGMENT_MAX_ALLOC_CAPACITY /
        // MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE.
        // The following magic constants work only if the argument is between 0 and 2^31 + 2^30,
        // i. e. assuming that
        // MAP_AVERAGE_SEGMENTS_SATURATION_SEGMENT_CAPACITY_POWER_OF_TWO_COMPONENTS = 3 * 2^30 =
        // 2^31 + 2^30.
        int averageSegments = (int) ((segmentCapacityPowerOfTwoComponents * 2863311531L) >>> 33);
        return Integer.SIZE - Integer.numberOfLeadingZeros(averageSegments - 1);
    }

    /**
     * The order of any segment must not become more than {@link #computeAverageSegmentOrder(long)}
     * plus this value, and more than {@link #segmentsArray}'s order. If a segment has the maximum
     * allowed order and its size exceeds {@link Segment#SEGMENT_MAX_ALLOC_CAPACITY}, it is inflated
     * instead of being split (see {@link #makeSpaceAndInsert}).
     *
     * This constant's value of 1 formalizes the "{@link #segmentsArray} may be doubled, but not
     * quadrupled above the average segments" principle, described in the Javadoc comment for {@link
     * InflatedSegmentQueryContext}.
     */
    static final int MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE = 1;

    static int maxSplittableSegmentOrder(int averageSegmentOrder) {
        // Since MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE = 1, this statement is currently
        // logically equivalent to `return averageSegmentOrder`.
        return averageSegmentOrder + MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE - 1;
    }

    /** TODO refresh optimal value */
    private static final int SEGMENT_INTERMEDIATE_ALLOC_CAPACITY = 30;

    private static class SegmentsArrayLengthAndNumSegments {
        final int segmentsArrayLength;
        final int numSegments;

        private SegmentsArrayLengthAndNumSegments(int segmentsArrayLength, int numSegments) {
            this.segmentsArrayLength = segmentsArrayLength;
            this.numSegments = numSegments;
        }
    }
    /**
     * @return a power of 2 number of segments
     */
    private static SegmentsArrayLengthAndNumSegments chooseInitialSegmentsArrayLength(
            SmoothieMapBuilder<?, ?> builder) {
        long minExpectedSize = builder.minExpectedSize();
        if (minExpectedSize == SmoothieMapBuilder.UNKNOWN_SIZE) {
            return new SegmentsArrayLengthAndNumSegments(1, 1);
        }
        assertThat(minExpectedSize >= 0);
        if (minExpectedSize <= SEGMENT_MAX_ALLOC_CAPACITY) {
            return new SegmentsArrayLengthAndNumSegments(1, 1);
        }
        if (minExpectedSize <= 2 * SEGMENT_MAX_ALLOC_CAPACITY) {
            return new SegmentsArrayLengthAndNumSegments(2, 2);
        }
        // TODO something more smart. For example, when minExpectedSize / SEGMENT_MAX_ALLOC_CAPACITY
        //  is just over a power of two N, it's better to choose initialNumSegments = N / 2,
        //  initialSegmentsArrayLength = N * 2.
        int initialNumSegments = (int) Math.min(MAX_SEGMENTS_ARRAY_LENGTH,
                LongMath.floorPowerOfTwo(minExpectedSize / SEGMENT_MAX_ALLOC_CAPACITY));
        int initialSegmentsArrayLength = (int) Math.min(MAX_SEGMENTS_ARRAY_LENGTH,
                ((long) initialNumSegments) * 2L);
        return new SegmentsArrayLengthAndNumSegments(
                initialSegmentsArrayLength, initialNumSegments);
    }

    // See MathDecisions in test/
    static final int MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT = 32;
    static final int MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT = 63;

    // See MathDecisions in test/
    private static final long[] SEGMENTS_QUADRUPLING_FROM_REF_SIZE_4 = {
            17237966, 20085926, 23461869, 27467051, 32222765, 101478192, 118705641, 139126526,
            163353618, 192120413, 226305341, 266960817, 825529841, 971366784, 1144556172,
            1350385115, 1595184608, 1886539115, 2233536926L, 2647074163L, 1244014982, 598555262,
            294588684, 148182403, 76120369, 39902677, 21329967, 11619067, 6445637, 3639219,
            2089996, 1220217,
    };

    private static final long[] SEGMENTS_QUADRUPLING_FROM_REF_SIZE_8 = {
            6333006, 7437876, 8753429, 10321069, 12190537, 37874373, 44596145, 52597103, 62128040,
            73490002, 87044486, 266960817, 315348276, 372980187, 441670897, 523597560, 621373710,
            738137712, 2233536926L, 2647074163L, 1244014982, 598555262, 294588684, 148182403,
            76120369, 39902677, 21329967, 11619067, 6445637, 3639219, 2089996, 1220217,
    };

    private static final long[] SEGMENTS_QUADRUPLING_FROM = ARRAY_OBJECT_INDEX_SCALE == 4 ?
            SEGMENTS_QUADRUPLING_FROM_REF_SIZE_4 : SEGMENTS_QUADRUPLING_FROM_REF_SIZE_8;

    /**
     * @return 0 - default, 1 - doubling, 2 - quadrupling
     */
    private static int chooseUpFrontScale(long expectedSize, int segments) {
        // if only one segment, no possibility to "skew" assuming given expectedSize is precise
        if (segments == 1)
            return 0;
        int roundedUpAverageEntriesPerSegment =
                max((int) roundedUpDivide(expectedSize, segments),
                        MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT);
        assert roundedUpAverageEntriesPerSegment <= MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        int indexInSegmentsQuadruplingFromArray =
                roundedUpAverageEntriesPerSegment - MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        if (segments * 4L <= MAX_SEGMENTS_ARRAY_LENGTH &&
                indexInSegmentsQuadruplingFromArray < SEGMENTS_QUADRUPLING_FROM.length &&
                segments >= SEGMENTS_QUADRUPLING_FROM[indexInSegmentsQuadruplingFromArray]) {
            return 2; // quadrupling
        } else {
            if (segments * 2L <= MAX_SEGMENTS_ARRAY_LENGTH) {
                return 1; // doubling
            } else {
                return 0;
            }
        }
    }

    private static long roundedUpDivide(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    // See MathDecisions in test/
    private static final byte[] ALLOC_CAPACITIES_REF_SIZE_4 = {
            42, 43, 44, 45, 46, 48, 49, 50, 51, 52, 53, 54, 56, 57, 58, 59, 60, 61, 62, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63,
    };

    private static final byte[] ALLOC_CAPACITIES_REF_SIZE_8 = {
            41, 42, 43, 44, 45, 47, 48, 49, 50, 51, 52, 54, 55, 56, 57, 58, 59, 60, 62, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63,
    };

    private static final byte[] ALLOC_CAPACITIES = ARRAY_OBJECT_INDEX_SCALE == 4 ?
            ALLOC_CAPACITIES_REF_SIZE_4 : ALLOC_CAPACITIES_REF_SIZE_8;

    private static int chooseAllocCapacity(long expectedSize, int segments) {
        int averageEntriesPerSegment = max((int) roundedUpDivide(expectedSize, segments),
                MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT);
        return ALLOC_CAPACITIES[
                averageEntriesPerSegment - MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT];
    }

    private static int order(int numSegments) {
        assertIsPowerOfTwo(numSegments, "num segments");
        return Integer.numberOfTrailingZeros(numSegments);
    }

    /**
     * The number of the lowest bit in hash codes that is used (along with all the higher bits) to
     * locate a segment (in {@link #segmentsArray}) for a key (see {@link #segmentByHash}).
     *
     * The lowest {@link Segment#HASH_CONTROL_BITS} + {@link Segment#HASH_DATA_BITS} bits are stored
     * in hash tables of the segments, the following {@link Segment#LOG_HASH_TABLE_SIZE} bits are
     * used to locate the first lookup slot in hash tables of the segments.
     */
    static final int SEGMENT_LOOKUP_HASH_SHIFT =
            LOG_HASH_TABLE_SIZE + HASH_CONTROL_BITS + HASH_DATA_BITS;

    /**
     * The number of bytes to shift (masked) hash to the right to obtain the offset in {@link
     * #segmentsArray}.
     */
    private static final int SEGMENT_ARRAY_OFFSET_HASH_SHIFT =
            SEGMENT_LOOKUP_HASH_SHIFT - ARRAY_OBJECT_INDEX_SHIFT;

    private static final AtomicIntegerFieldUpdater<SmoothieMap> GROW_SEGMENTS_ARRAY_LOCK_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SmoothieMap.class, "growSegmentsArrayLock");
    private static final int GROW_SEGMENTS_ARRAY_LOCK_UNLOCKED = 0;
    private static final int GROW_SEGMENTS_ARRAY_LOCK_LOCKED = 1;

    private static final long MOD_COUNT_FIELD_OFFSET;

    static {
        try {
            Field modCountField = SmoothieMap.class.getDeclaredField("modCount");
            MOD_COUNT_FIELD_OFFSET = U.objectFieldOffset(modCountField);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    protected static final long LONG_PHI_MAGIC = -7046029254386353131L;

    /**
     * This field is used as a lock via {@link #GROW_SEGMENTS_ARRAY_LOCK_UPDATER} in {@link
     * #growSegmentsArray}.
     */
    private volatile int growSegmentsArrayLock = GROW_SEGMENTS_ARRAY_LOCK_UNLOCKED;

    private volatile long segmentLookupMask;
    @Nullable Object segmentsArray;
    private long size;
    private int modCount;

    @MonotonicNonNull InflatedSegmentQueryContext<K, V> inflatedSegmentQueryContext;

    /**
     * The last value returned from a {@link #doComputeAverageSegmentOrder} call. Updated
     * transparently in {@link #computeAverageSegmentOrder}. The average segment order is computed
     * and used only in the contexts related to SmoothieMap's growth, namely {@link #split}
     * (triggered when a segment grows too large) and {@link #splitInflated} (triggered when the
     * average segment order grows large enough for an inflated segment to not be considered outlier
     * anymore). It means that if no entries are inserted into a SmoothieMap or more entries are
     * deleted from a SmoothieMap than inserted the value stored in lastComputedAverageSegmentOrder
     * could become stale, much larger than the actual average segment order. It's updated when a
     * SmoothieMap starts to grow again (in the next {@link #split} call), so there shouldn't be any
     * "high watermark" effects, unless entries are inserted into a SmoothieMap in an artificial
     * order, for example, making all insertions to fall into already inflated segments, while
     * removals happen from ordinary segments. See also the comment for {@link
     * InflatedSegment#shouldBeSplit}, and the comments inside that method.
     */
    byte lastComputedAverageSegmentOrder;

    /**
     *
     */
    private boolean allocateIntermediateSegments;

    /* if Flag doShrink */
    private boolean doShrink;
    /* endif */

    /* if Tracking hashCodeDistribution */
    @Nullable HashCodeDistribution<K, V> hashCodeDistribution;
    /* endif */

    /* if Tracking segmentOrderStats */
    /**
     * Each byte of this long value contains the count of segments with the order corresponding to
     * the number of the byte. Once a segment with the order equal to {@link Long#BYTES} emerges in
     * the SmoothieMap, {@link #segmentCountsByOrder} is allocated, the counts of segments with
     * small orders are moved there (both is done in {@link #inflateSegmentCountsByOrderAndAdd}) and
     * this field is not used anymore.
     *
     * The purpose of this field is optimization of the memory footprint of small SmoothieMaps. The
     * alternative is allocating small {@link #segmentCountsByOrder} array initially and then
     * reallocating it on demand, but that would require reading the length of the array in {@link
     * #addToSegmentCountWithOrder}.
     */
    private long segmentCountsBySmallOrder;
    /**
     * If null, there haven't been segments with order greater than or equal to {@link Long#BYTES}
     * in the SmoothieMap yet, and the counts of segments with smaller orders are stored in {@link
     * #segmentCountsBySmallOrder}. Once a segment with the order equal to {@link Long#BYTES}
     * emerges in the SmoothieMap, {@link #inflateSegmentCountsByOrderAndAdd} is called and an array
     * of {@link #MAX_SEGMENTS_ARRAY_ORDER} + 1 ints is stored in this field.
     *
     * The type of this field is Object rather than int[] to avoid class checks when
     * [Avoid normal array access].
     */
    private @Nullable Object segmentCountsByOrder;
    private byte maxSegmentOrder;
    /* endif */

    private @Nullable Set<K> keySet;
    private @Nullable Collection<V> values;
    private @Nullable Set<Entry<K, V>> entrySet;

    /**
     * Creates a new, empty {@code SmoothieMap}.
     */
    SmoothieMap(SmoothieMapBuilder<K, V> builder) {
        SegmentsArrayLengthAndNumSegments initialSegmentsArrayLengthAndNumSegments =
                chooseInitialSegmentsArrayLength(builder);

        Object[] segmentsArray = initSegmentsArray(initialSegmentsArrayLengthAndNumSegments);
        updateSegmentLookupMask(segmentsArray.length);
        // Ensure that no thread sees null in the segmentsArray field and nulls as segmentsArray's
        // elements. The latter could lead to a segfault.
        U.storeFence();
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
     *     protected boolean keysEqual(Object queriedKey, K internalKey) {
     *         return false;
     *     }
     *
     *     &#064;Override
     *     protected long keyHashCode(Object key) {
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
     *     protected boolean keysEqual(Object queriedDomain, String domainInMap) {
     *         return ((String) queriedDomain).equalsIgnoreCase(domainInMap));
     *     }
     *
     *     &#064;Override
     *     protected long keyHashCode(Object domain) {
     *         return LongHashFunction.xx_r39().hashChars(((String) domain).toLowerCase());
     *     }
     * }</code></pre>
     *
     * <p>Default implementation is {@code queriedKey.equals(internalKey)}.
     *
     * @param queriedKey the first key to compare, that is passed to queries like {@link #get},
     * but might also be a key, that is already stored in the map
     * @param internalKey the second key to compare, guaranteed that this key is already stored
     * in the map
     * @return {@code true} if the given keys should be considered equal for this map, {@code false}
     * otherwise
     * @see #keyHashCode(Object)
     */
    boolean keysEqual(Object queriedKey, K internalKey) {
        return queriedKey.equals(internalKey);
    }

    @Override
    public Equivalence<K> keyEquivalence() {
        return Equivalence.defaultEquality();
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
     * TODO update javadoc
     *
     * Ensures that the lowest {@link #SEGMENT_LOOKUP_HASH_SHIFT} of the result are affected by all
     * input hash bits, i. e. partial avalanche effect. To achieve full avalanche effect (all bits
     * of the result are affected by all input hash bits) considerably more steps are required,
     * e. g. see the finalization procedure of xxHash. Having the lowest {@link
     * #SEGMENT_LOOKUP_HASH_SHIFT} bits of the result distributed well is critical because those
     * bits are responsible for SmoothieMap's efficiency (number of collisions and unnecessary key
     * comparisons) within (ordinary) segments, that is not reported to a callback provided to
     * {@link SmoothieMapBuilder#reportPoorHashCodeDistribution}, because there are checks that only
     * catch higher level, inter-segment anomalies (see {@link
     * #segmentShouldBeReported}, (TODO link to num inflated segments method) for
     * more details).
     *  because of two types of intra-segment distribution problems - slot concentration and stored
     *  hash collisions
     *  TODO detect slot concentration?
     *
     * <p>See other examples of this method override in the documentation to {@link
     * #keysEqual(Object, Object)} method.
     *
     * @param key the key (queried or already stored in the map) to compute hash code for
     * @return the hash code for the given key
     * @see #keysEqual(Object, Object)
     */
    protected long keyHashCode(Object key) {
        long x = ((long) key.hashCode()) * LONG_PHI_MAGIC;
        return x ^ (x >>> (Long.SIZE - SEGMENT_LOOKUP_HASH_SHIFT));
    }

    /**
     * The standard procedure to convert from SmoothieMap's internally used 64-bit hash code to a
     * 32-bit hash code to be used for methods such as {@link #hashCode()}, implement {@link
     * Entry#hashCode}, etc.
     */
    static int longKeyHashCodeToIntHashCode(long keyHashCode) {
        return Long.hashCode(keyHashCode);
    }

    ToLongFunction<K> getKeyHashFunction() {
        return new ToLongFunction<K>() {
            @Override
            public long applyAsLong(K key) {
                return keyHashCode(key);
            }

            @Override
            public String toString() {
                return "default key hash function";
            }
        };
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
     *     protected boolean valuesEqual(Object d1, String d2) {
     *         return ((String) d1).equalsIgnoreCase(d2);
     *     }
     * }</code></pre>
     *
     * <p>Default implementation is {@code queriedValue.equals(internalValue)}.
     *
     * @param queriedValue the first value to compare, that is passed to queries like {@link
     * #containsValue(Object)}
     * @param internalValue the second value to compare, this value is already stored in the map
     * @return {@code true} if the given values should be considered equal for this map
     */
    boolean valuesEqual(Object queriedValue, V internalValue) {
        return queriedValue.equals(internalValue);
    }

    @Override
    public Equivalence<V> valueEquivalence() {
        return Equivalence.defaultEquality();
    }

    int valueHashCode(V value) {
        return value.hashCode();
    }

    private int getInitialSegmentAllocCapacity(int segmentOrder) {
        if (allocateIntermediateSegments) {
            return SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
        } else {
            return SEGMENT_MAX_ALLOC_CAPACITY;
        }
    }

    //region segmentOrderStats-related methods
    /* if Tracking segmentOrderStats */

    private void addToSegmentCountWithOrder(int segmentOrder, @Positive int segments) {
        /* if Enabled extraChecks */
        if (segments <= 0) {
            throw new IllegalArgumentException();
        }
        /* endif */
        // Always-on check protecting against memory corruption: always checking that segmentOrder
        // argument is within the expected bounds (rather only when extraChecks are enabled) because
        // we cannot risk memory corruption because of [Avoid normal array access] below with
        // segmentOrder as the index.
        if (segmentOrder < 0 || segmentOrder > MAX_SEGMENTS_ARRAY_ORDER) {
            throwIseSegmentOrder(segmentOrder);
        }
        int oldSegmentCount;
        int newSegmentCount;
        @Nullable Object segmentCountsByOrder = this.segmentCountsByOrder;
        if (segmentCountsByOrder != null) { // [Positive likely branch]
            // [Avoid normal array access]
            long offset = ARRAY_INT_BASE_OFFSET_AS_LONG +
                    ARRAY_INT_INDEX_SCALE_AS_LONG * (long) segmentOrder;
            oldSegmentCount = U.getInt(segmentCountsByOrder, offset);
            newSegmentCount = oldSegmentCount + segments;
            U.putInt(segmentCountsByOrder, offset, newSegmentCount);
        } else if (segmentOrder < Long.BYTES) { // [Positive likely branch]
            long mask = (1 << Byte.SIZE) - 1;
            int shift = segmentOrder * Byte.SIZE;
            long segmentCountsBySmallOrder = this.segmentCountsBySmallOrder;
            oldSegmentCount = (int) ((segmentCountsBySmallOrder >>> shift) & mask);
            newSegmentCount = oldSegmentCount + segments;
            long negativeShiftedMask = ~(mask << shift);
            this.segmentCountsBySmallOrder =
                    (segmentCountsBySmallOrder & negativeShiftedMask) |
                            (((long) newSegmentCount) << shift);
        } else {
            inflateSegmentCountsByOrderAndAdd(segmentOrder, segments);
            return;
        }
        if (newSegmentCount <= 1 << segmentOrder) {
            if (oldSegmentCount == 0) {
                maxSegmentOrder = (byte) max(segmentOrder, (int) maxSegmentOrder);
            }
        } else {
            throwIseSegmentCountOverflow(segmentOrder);
        }
    }

    /** See {@link #segmentCountsBySmallOrder} and {@link #segmentCountsByOrder}. */
    private void inflateSegmentCountsByOrderAndAdd(int segmentOrder, int segments) {
        int[] segmentCountsByOrder = new int[MAX_SEGMENTS_ARRAY_ORDER + 1];
        copySegmentCountsBySmallOrderIntoArray(segmentCountsByOrder);
        this.segmentCountsByOrder = segmentCountsByOrder;
        addToSegmentCountWithOrder(segmentOrder, segments);
    }

    int[] debugSegmentCountsByOrder() {
        if (segmentCountsByOrder != null) {
            return (int[]) segmentCountsByOrder;
        }
        int[] debugSegmentCountsByOrder = new int[MAX_SEGMENTS_ARRAY_ORDER + 1];
        copySegmentCountsBySmallOrderIntoArray(debugSegmentCountsByOrder);
        return debugSegmentCountsByOrder;
    }

    private void copySegmentCountsBySmallOrderIntoArray(int[] segmentCountsByOrder) {
        long segmentCountsBySmallOrder = this.segmentCountsBySmallOrder;
        int mask = (1 << Byte.SIZE) - 1;
        for (int smallOrder = 0; smallOrder < Long.BYTES; smallOrder++) {
            segmentCountsByOrder[smallOrder] = ((int) segmentCountsBySmallOrder) & mask;
            segmentCountsBySmallOrder >>>= Byte.SIZE;
        }
    }

    /** [Reducing bytecode size of a hot method] */
    @Contract("_ -> fail")
    private static void throwIseSegmentCountOverflow(int segmentOrder) {
        throw new IllegalStateException(
                "Overflow of the count of segments of the order " + segmentOrder);
    }

    /** [Reducing bytecode size of a hot method] */
    @Contract("_ -> fail")
    private static void throwIseSegmentOrder(int segmentOrder) {
        throw new IllegalStateException("Segment order: " + segmentOrder);
    }

    /** @param segments number of segments to substract. Must be positive. */
    @AmortizedPerSegment
    private void subtractFromSegmentCountWithOrder(int segmentOrder, int segments) {
        /* if Enabled extraChecks */
        if (segments <= 0) {
            throw new IllegalArgumentException();
        }
        /* endif */
        // [Always-on check protecting against memory corruption]
        if (segmentOrder < 0 || segmentOrder > MAX_SEGMENTS_ARRAY_ORDER) {
            throwIseSegmentOrder(segmentOrder);
        }
        int oldSegmentCount;
        int newSegmentCount;
        @Nullable Object segmentCountsByOrder = this.segmentCountsByOrder;
        if (segmentCountsByOrder != null) { // [Positive likely branch]
            // [Avoid normal array access]
            long offset = ARRAY_INT_BASE_OFFSET_AS_LONG +
                    ARRAY_INT_INDEX_SCALE_AS_LONG * (long) segmentOrder;
            oldSegmentCount = U.getInt(segmentCountsByOrder, offset);
            newSegmentCount = oldSegmentCount - segments;
            U.putInt(segmentCountsByOrder, offset, newSegmentCount);
        } else if (segmentOrder < Long.BYTES) {
            long mask = (1 << Byte.SIZE) - 1;
            int shift = segmentOrder * Byte.SIZE;
            long segmentCountsBySmallOrder = this.segmentCountsBySmallOrder;
            oldSegmentCount = (int) ((segmentCountsBySmallOrder >>> shift) & mask);
            newSegmentCount = oldSegmentCount - segments;
            long negativeShiftedMask = ~(mask << shift);
            this.segmentCountsBySmallOrder =
                    (segmentCountsBySmallOrder & negativeShiftedMask) |
                            (((long) newSegmentCount) << shift);
        } else {
            throwIseSegmentOrder(segmentOrder);
            return;
        }
        if (newSegmentCount == 0) {
            updateMaxSegmentOrder(segmentOrder);
        } else if (newSegmentCount < 0) {
            throwIseSegmentCountUnderflow(segmentOrder);
        }
    }

    @AmortizedPerOrder
    private void updateMaxSegmentOrder(int orderWhoseSegmentCountBecameZero) {
        int maxSegmentOrder = (int) this.maxSegmentOrder;
        if (orderWhoseSegmentCountBecameZero < maxSegmentOrder) { // [Positive likely branch]
            return;
        }
        if (orderWhoseSegmentCountBecameZero > maxSegmentOrder) {
            throw new IllegalStateException("Segment order " + orderWhoseSegmentCountBecameZero +
                    " is greater than the max segment order " + maxSegmentOrder);
        }
        // assert orderWhoseSegmentCountBecameZero == maxSegmentOrder;
        boolean updated = false;
        @Nullable Object segmentCountsByOrderObject = this.segmentCountsByOrder;
        if (segmentCountsByOrder != null) {
            int[] segmentCountsByOrder = (int[]) segmentCountsByOrderObject;
            for (int order = maxSegmentOrder - 1; order >= 0; order--) {
                if (segmentCountsByOrder[order] > 0) {
                    this.maxSegmentOrder = (byte) order;
                    updated = true;
                    break;
                }
            }
        } else {
            long segmentCountsBySmallOrder = this.segmentCountsBySmallOrder;
            for (int order = maxSegmentOrder - 1; order >= 0; order--) {
                long mask = (1 << Byte.SIZE) - 1;
                int shift = order * Byte.SIZE;
                int segmentCount = (int) ((segmentCountsBySmallOrder >>> shift) & mask);
                if (segmentCount > 0) {
                    this.maxSegmentOrder = (byte) order;
                    updated = true;
                    break;
                }
            }
        }
        if (!updated) {
            throw new IllegalStateException("Counts of segments of all orders are zero");
        }
    }

    /** [Reducing bytecode size of a hot method] */
    @Contract("_ -> fail")
    private static void throwIseSegmentCountUnderflow(int segmentOrder) {
        throw new IllegalStateException(
                "Underflow of the count of segments of the order " + segmentOrder);
    }

    /* endif */ /* comment // Tracking segmentOrderStats //*/
    //endregion


    private Object[] initSegmentsArray(
            SegmentsArrayLengthAndNumSegments segmentsArrayLengthAndNumSegments) {
        Object[] segmentsArray = new Object[segmentsArrayLengthAndNumSegments.segmentsArrayLength];
        int numCreatedSegments = segmentsArrayLengthAndNumSegments.numSegments;
        int segmentsOrder = order(numCreatedSegments);
        int segmentAllocCapacity = getInitialSegmentAllocCapacity(segmentsOrder);
        for (int i = 0; i < numCreatedSegments; i++) {
            segmentsArray[i] = Segment.createNewSegment(segmentAllocCapacity, segmentsOrder);
        }
        duplicateSegments(segmentsArray, numCreatedSegments);
        this.segmentsArray = segmentsArray;
        /* if Tracking segmentOrderStats */
        addToSegmentCountWithOrder(segmentsOrder, numCreatedSegments);
        /* endif */
        return segmentsArray;
    }

    private void updateSegmentLookupMask(int segmentsArrayLength) {
        assertIsPowerOfTwo(segmentsArrayLength, "segments array length");
        segmentLookupMask = ((long) segmentsArrayLength - 1) << SEGMENT_LOOKUP_HASH_SHIFT;
    }

    private static void duplicateSegments(Object[] segmentsArray, int filledLowPartLength) {
        int segmentsArrayLength = segmentsArray.length;
        for (; filledLowPartLength < segmentsArrayLength; filledLowPartLength *= 2) {
            System.arraycopy(segmentsArray, 0, segmentsArray, filledLowPartLength,
                    filledLowPartLength);
        }
    }

    /**
     * Returns a non-negative number if upon returning from this method {@link #segmentsArray} has
     * sufficient capacity to hold segments of order equal to priorSegmentOrder + 1, or a negative
     * number if the maximum capacity is reached (in other words, if priorSegmentOrder is equal to
     * {@link #MAX_SEGMENTS_ARRAY_ORDER}.
     *
     * If returns a non-negative number, it's 0 or 1 depending on if modCount increment has
     * happened inside during the method call.
     *
     * Negative integer return contract: this method documents to return a negative value rather
     * than exactly -1 to enforce clients to use comparision with zero (< 0, or >= 0) rather than
     * exact comparision with -1 (== -1, != -1), because the former requires less machine
     * instructions (see jz/je/jne).
     */
    @AmortizedPerSegment
    final int tryEnsureSegmentsArrayCapacityForSplit(int priorSegmentOrder) {
        // Computing the current segmentsArray length from segmentLookupMask (a volatile variable)
        // to ensure that if this method returns early in [The current capacity is sufficient]
        // branch below, later reads of segmentsArray will observe a segmentsArray of at least the
        // ensured capacity. This is not strictly required to avoid memory corruption, because in
        // replaceInSegmentsArray() (the only method where writes to segmentsArray happen after
        // calling to tryEnsureSegmentsArrayCapacityForSplit()) the length of the array is used as
        // the loop bound anyway, but provides a little more confidence. Also reading the length of
        // segmentsArray directly is not guaranteed to be faster than computing it from
        // segmentLookupMask, because the former incurs an extra data dependency. While reading
        // segmentsArray field just once and passing it into both
        // tryEnsureSegmentsArrayCapacityForSplit() and (through a chain of methods) to
        // replaceInSegmentsArray() is possible, it means adding a parameter to a number of methods,
        // that is cumbersome (for a method annotated @AmortizedPerSegment, i. e. shouldn't be
        // optimized _that_ hard) and has it's cost too, which is not guaranteed to be lower than
        // the cost of reading from the segmentsArray field twice.
        long visibleSegmentsArrayLength =
                (this.segmentLookupMask >>> SEGMENT_LOOKUP_HASH_SHIFT) + 1;
        // Needs to be a long, because if priorSegmentOrder = MAX_SEGMENTS_ARRAY_LENGTH == 30,
        // requiredSegmentsArrayLength = 2^31 will overflow as an int.
        long requiredSegmentsArrayLength = 1L << (priorSegmentOrder + 1);
        if (visibleSegmentsArrayLength >= requiredSegmentsArrayLength) { // [Positive likely branch]
            // The current capacity is sufficient.
            return 0; // Didn't increment modCount in the course of this method call.
        } else {
            // [Positive likely branch]
            if (requiredSegmentsArrayLength <= MAX_SEGMENTS_ARRAY_LENGTH) {
                // Code in a rarely taken branch is extracted as a method, see
                // [Reducing bytecode size of a hot method]
                // TODO check whether this is actually a good trick
                growSegmentsArray((int) requiredSegmentsArrayLength);
                return 1; // Incremented modCount in growSegmentsArray().
            } else {
                // Not succeeded to ensure capacity because MAX_SEGMENTS_ARRAY_LENGTH capacity is
                // reached.
                return -1;
            }
        }
    }

    @AmortizedPerOrder
    private void growSegmentsArray(int requiredSegmentsArrayLength) {
        // Protecting growSegmentsArray() with a lock not only to detect concurrent modifications,
        // but also because it's very hard to prove that no race between concurrent
        // tryEnsureSegmentsArrayCapacityForSplit() calls (which could lead to accessing array
        // elements beyond the array's length in segmentByHash()) is possible.
        if (!GROW_SEGMENTS_ARRAY_LOCK_UPDATER.compareAndSet(this,
                GROW_SEGMENTS_ARRAY_LOCK_UNLOCKED, GROW_SEGMENTS_ARRAY_LOCK_LOCKED)) {
            throw new ConcurrentModificationException("Concurrent map update is in progress");
        }
        try {
            Object[] oldSegments = getNonNullSegmentsArrayOrThrowCme();
            // Check the length again, after an equivalent check in
            // tryEnsureSegmentsArrayCapacityForSplit(). Sort of double-checked locking.
            if (oldSegments.length < requiredSegmentsArrayLength) {
                modCount++;
                Object[] newSegments = Arrays.copyOf(oldSegments, requiredSegmentsArrayLength);
                duplicateSegments(newSegments, oldSegments.length);
                // Ensures that no thread could see nulls as segmentsArray's elements.
                U.storeFence();
                this.segmentsArray = newSegments;
                // It's critical to update segmentLookupMask after assigning the new
                // segments array into the segmentsArray field (the previous statement) to
                // provide a happens-before (segmentLookupMask is volatile) with the code
                // in segmentByHash() and thus guarantee impossibility of an illegal out of
                // bounds access to an array. See the corresponding comment in
                // segmentByHash().
                updateSegmentLookupMask(newSegments.length);
            } else {
                throw new ConcurrentModificationException();
            }
        }
        finally {
            growSegmentsArrayLock = GROW_SEGMENTS_ARRAY_LOCK_UNLOCKED;
        }
    }

    @HotPath
    final Object segmentByHash(long hash) {
        // It's critical that the segmentsArray field is read (<- 2) after segmentLookupMask (<- 1)
        // to provide a happens-before edge (segmentLookupMask is volatile) with the code in
        // growSegmentsArray() and thus guarantee impossibility of an illegal (and crash- and memory
        // corruption-prone, because of Unsafe) out of bounds access to an array if some thread
        // accesses a SmoothieMap concurrently with another thread growing it in
        // growSegmentsArray().
        long segmentArrayOffset = (hash & segmentLookupMask /* <- 1 */) >>
                SEGMENT_ARRAY_OFFSET_HASH_SHIFT;
        @Nullable Object segmentsArray = this.segmentsArray; // <- 2
        /* if Enabled moveToMapWithShrunkArray */
        // [segmentsArray non-null checks]
        if (segmentsArray == null) {
            throwIseSegmentsArrayNull();
        }
        /* endif */
        // Avoid normal array access: normal array access incurs
        // - an extra data dependency between reading the array reference and a reference to a
        //   specific Segment;
        // - the array header cache line reading and refreshing it in L1;
        // - bound checks;
        // - a class check that `segmentsArray` is indeed an array of objects.
        return U.getObject(segmentsArray,
                ARRAY_OBJECT_BASE_OFFSET_AS_LONG + segmentArrayOffset);
    }

    final int debugSegmentsArrayLength() {
        //noinspection ConstantConditions: suppress nullability warnings during debug
        return ((Object[]) segmentsArray).length;
    }

    final Segment<K, V> debugSegmentByIndex(int segmentIndex) {
        //noinspection unchecked,ConstantConditions: suppress nullability warnings during debug
        return (Segment<K, V>) ((Object[]) segmentsArray)[segmentIndex];
    }

    /**
     * TODO annotate segmentsArray with @Nullable when
     *  https://youtrack.jetbrains.com/issue/IDEA-210087 is fixed.
     */
    private static <K, V> Segment<K, V> segmentByIndexDuringBulkOperations(
            Object[] segmentsArray, int segmentIndex) {
        // [Not avoiding normal array access]
        @Nullable Object segment = segmentsArray[segmentIndex];
        if (segment == null) {
            throw new ConcurrentModificationException();
        }
        //noinspection unchecked
        return (Segment<K, V>) segment;
    }

    static int firstSegmentIndexByHashAndOrder(long hash, int segmentOrder) {
        // In native implementation, BEXTR instruction (see en.wikipedia.org/wiki/
        // Bit_Manipulation_Instruction_Sets#BMI1_(Bit_Manipulation_Instruction_Set_1)) can be used.
        return ((int) (hash >>> SEGMENT_LOOKUP_HASH_SHIFT)) & ((1 << segmentOrder) - 1);
    }

    static int firstSegmentIndexByIndexAndOrder(@NonNegative int segmentIndex,
            @IntRange(from = 0, to = MAX_SEGMENTS_ARRAY_ORDER) int segmentOrder) {
        return segmentIndex & ((1 << segmentOrder) - 1);
    }
    
    private static int siblingSegmentIndex(int segmentIndex, int segmentOrder) {
        return segmentIndex ^ (1 << (segmentOrder - 1));
    }

    /**
     * Specifically to be called from {@link #split}.
     * @param firstSiblingsSegmentIndex the first (smallest) index in {@link #segmentsArray} where
     *        where yet unsplit segment (with the order equal to newSegmentOrder - 1) is stored.
     * @param newSegmentOrder the order of the two new sibling segments
     * @param chooseLower should be equal to 1 if this method should return the first index for the
     *       lower segment among two siblings, value 0 means that the first index for the higher
     *       segment among two siblings should be returned.
     * @return the first index for the lower or the higher segment among the two new siblings, as
     * chosen.
     */
    private static int chooseFirstSiblingSegmentIndex(
            int firstSiblingsSegmentIndex, int newSegmentOrder, int chooseLower) {
        int n = newSegmentOrder - 1;
        // Using the last algorithm from this answer: https://stackoverflow.com/a/47990 because it's
        // simpler than the first algorithm, but the data dependency chain is equally long in our
        // case because we have to convert chooseLower to x by `1 - chooseLower` operation.
        int x = 1 - chooseLower;
        //noinspection UnnecessaryLocalVariable - using the same variable name as in the source
        int number = firstSiblingsSegmentIndex;
        return (number & ~(1 << n)) | (x << n);
    }

    /**
     * @param firstReplacedSegmentIndex the first (smallest) index in {@link #segmentsArray} where
     *        the replacement segment should be stored instead of the replaced segment.
     * @param replacementSegmentOrder the order of the replacement segment. Must be equal to or
     *        greater than the order of the replaced segment.
     */
    @AmortizedPerSegment
    private void replaceInSegmentsArray(Object[] segmentsArray,
            int firstReplacedSegmentIndex, int replacementSegmentOrder, Object replacementSegment) {
        modCount++;
        int step = 1 << replacementSegmentOrder;
        for (int segmentIndex = firstReplacedSegmentIndex; segmentIndex < segmentsArray.length;
             segmentIndex += step) {
            // Not avoiding normal array access: couldn't [Avoid normal array access], because
            // unless segmentsArray is read just once across all paths in put(), remove() etc.
            // methods and passed all the way down as local parameter to replaceInSegmentsArray()
            // (which is called in split(), tryShrink2(), and methods), that is likely not practical
            // because it contributes to bytecode size and machine operations on the hot paths of
            // methods like put() and remove(), a memory corrupting race is possible, because
            // segmentsArray is not volatile and the second read of this field (e. g. the one
            // performed in getNonNullSegmentsArrayOrThrowCme() to obtain an array version to be
            // passed into this method) might see an _earlier_ version of the array with smaller
            // length. See
            // https://shipilev.net/blog/2016/close-encounters-of-jmm-kind/#wishful-hb-actual
            // explaining how that is possible.
            segmentsArray[segmentIndex] = replacementSegment;
        }
    }

    /**
     * Should be called in point access and segment transformation methods, except in {@link
     * #segmentByHash} (see the comment for {@link #throwIseSegmentsArrayNull}).
     *
     * Must annotate with @Nullable when https://youtrack.jetbrains.com/issue/IDEA-210087 is fixed.
     */
    private Object[] getNonNullSegmentsArrayOrThrowCme() {
        @Nullable Object[] segmentsArray =
                (@Nullable Object[]) this.segmentsArray;
        /* if Enabled moveToMapWithShrunkArray */
        // [segmentsArray non-null checks]
        if (segmentsArray == null) {
            throwCmeSegmentsArrayNull();
        }
        /* endif */
        return segmentsArray;
    }

    /**
     * Should be called in the beginning of bulk iteration methods.
     *
     * Must annotate with @Nullable when https://youtrack.jetbrains.com/issue/IDEA-210087 is fixed.
     */
    private Object[] getNonNullSegmentsArrayOrThrowIse() {
        @Nullable Object[] segmentsArray =
                (@Nullable Object[]) this.segmentsArray;
        /* if Enabled moveToMapWithShrunkArray */
        // segmentsArray non-null checks: are needed only when moveToMapWithShrunkArray() operation
        // is possible because there is no other way that segmentsArray can be observed to be null
        // after SmoothieMap's construction.
        if (segmentsArray == null) {
            throwIseSegmentsArrayNull();
        }
        /* endif */
        return segmentsArray;
    }

    /**
     * Reducing bytecode size of a hot method: extracting exception construction and throwing as
     * a method in order to reduce the bytecode size of a hot method ({@link #segmentByHash} here),
     * ultimately making SmoothieMap friendlier for inlining, because inlining thresholds and limits
     * are defined in terms of the numbers of bytecodes in Hotspot JVM.
     *
     * When {@link #segmentsArray} is found to be null, this method should be called only from
     * {@link #segmentByHash} (among point access and segment transformation methods) because
     * segmentByHash() is first called on all map query paths, so that if a SmoothieMap is
     * mistakenly accessed after calling {@link #moveToMapWithShrunkArray()}, an
     * IllegalStateException is thrown. In other methods {@link #getNonNullSegmentsArrayOrThrowCme()
     * } should be called instead to throw a ConcurrentModificationException, because {@link
     * #segmentsArray} might be found to be null in other point access and segment transformation
     * methods only if the map is accessed concurrently.
     */
    @Contract(" -> fail")
    private static void throwIseSegmentsArrayNull() {
        throw new IllegalStateException(
                "Old map object shouldn't be accessed after explicit shrinking"
        );
    }

    /** [Reducing bytecode size of a hot method] */
    @Contract(" -> fail")
    private static void throwCmeSegmentsArrayNull() {
        throw new ConcurrentModificationException(
                "Explicit shrinking is done concurrently with other some " +
                        "modification operations on a map"
        );
    }

    final void incrementSize() {
        modCount++;
        size++;
    }

    final void decrementSize() {
        modCount++;
        size--;
    }

    /**
     * Makes at least Opaque-level read to allow making modCount checks more robust in the face of
     * operation reorderings performed by the JVM.
     */
    final int getModCountOpaque() {
        // It should be VarHandle's Opaque mode. In the absence of that in sun.misc.Unsafe API,
        // using volatile, that costs almost as little on x86.
        return U.getIntVolatile(this, MOD_COUNT_FIELD_OFFSET);
    }

    final void checkModCountOrThrowCme(int expectedModCount) {
        // Intentionally makes Opaque read of modCount rather than plain read (that is, accessing
        // modCount field directly) to make the modCount check more robust in the face of operation
        // reorderings performed by the JVM.
        int actualModCount = getModCountOpaque();
        if (expectedModCount != actualModCount) {
            throw new ConcurrentModificationException();
        }
    }

    private InflatedSegmentQueryContext<K, V> getInflatedSegmentQueryContext() {
        @MonotonicNonNull InflatedSegmentQueryContext<K, V> context = inflatedSegmentQueryContext;
        if (context == null) {
            context = new InflatedSegmentQueryContext<>(this);
            inflatedSegmentQueryContext = context;
        }
        return context;
    }

    /** @see #lastComputedAverageSegmentOrder */
    @AmortizedPerSegment
    final int computeAverageSegmentOrder(long size) {
        int previouslyComputedAverageSegmentOrder = (int) lastComputedAverageSegmentOrder;
        int averageSegmentOrder = doComputeAverageSegmentOrder(size);
        // Guarding unlikely write: it's unlikely that lastComputedAverageSegmentOrder actually
        // needs to be updated. Guarding the write (which is done in updateAverageSegmentOrder())
        // should be preferable when a GC algorithm with expensive write barriers is used.
        // [Positive likely branch]
        if (averageSegmentOrder == previouslyComputedAverageSegmentOrder) {
            return averageSegmentOrder;
        } else {
            // [Rarely taken branch is extracted as a method]
            updateAverageSegmentOrder(previouslyComputedAverageSegmentOrder, averageSegmentOrder);
            return averageSegmentOrder;
        }
    }

    @AmortizedPerOrder
    private void updateAverageSegmentOrder(
            int previouslyComputedAverageSegmentOrder, int newAverageSegmentOrder) {
        lastComputedAverageSegmentOrder = (byte) newAverageSegmentOrder;
        /* if Tracking hashCodeDistribution */
        // TODO [hashCodeDistribution null check]
        @Nullable HashCodeDistribution<K, V> hashCodeDistribution = this.hashCodeDistribution;
        if (hashCodeDistribution != null) {
            hashCodeDistribution.averageSegmentOrderUpdated(
                    previouslyComputedAverageSegmentOrder, newAverageSegmentOrder);
        }
        /* endif */
    }

    @Override
    public final int size() {
        return (int) min(size, Integer.MAX_VALUE);
    }

    @Override
    public final long sizeAsLong() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return size == 0;
    }

    //region Map API point access methods

    @Override
    public final boolean containsKey(Object key) {
        return getInternalKey(key) != null;
    }

    @Override
    public final boolean containsEntry(Object key, Object value) {
        Utils.requireNonNull(value);
        @Nullable V internalVal = get(key);
        if (internalVal == null) {
            return false;
        }
        //noinspection ObjectEquality: identity comparision is intended
        boolean valuesIdentical = internalVal == value;
        return valuesIdentical || valuesEqual(value, internalVal);
    }

    @Override
    public final V getOrDefault(Object key, V defaultValue) {
        @Nullable V internalVal = get(key);
        return internalVal != null ? internalVal : defaultValue;
    }

    @CanIgnoreReturnValue
    @Override
    public final @Nullable V remove(Object key) {
        Utils.requireNonNull(key);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return replace(segment, key, hash, null, null);
    }

    @Override
    public final boolean remove(Object key, Object value) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(value);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return replace(segment, key, hash, value, null) != null;
    }

    @Override
    public final V replace(K key, V value) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(value);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return replace(segment, key, hash, null, value);
    }

    @Override
    public final boolean replace(K key, V oldValue, V newValue) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(oldValue);
        Utils.requireNonNull(newValue);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return replace(segment, key, hash, oldValue, newValue) != null;
    }

    @CanIgnoreReturnValue
    @Override
    public final @Nullable V put(K key, V value) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(value);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return put(segment, key, hash, value, false);
    }

    @Override
    public final @Nullable V putIfAbsent(K key, V value) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(value);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        return put(segment, key, hash, value, true);
    }

    //endregion

    //region Implementations of point access methods

    private static int findFirstEmptySlotForHash(Object segment, long hash) {
        return findFirstEmptySlotForSlotIndexBase(segment, slotIndex(hash));
    }

    private static int findFirstEmptySlotForSlotIndexBase(Object segment, int slotIndexBase) {
        int groupFirstSlotIndex = slotIndexBase;
        // TODO Unbounded search loop: check whether it's better to make this loop bounded and
        //  avoiding a safepoint poll in exchange
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) { // [Positive likely branch]
                return lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
            }
            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
        }
    }

    @Override
    public final @Nullable V get(Object key) {
        Utils.requireNonNull(key);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int groupFirstSlotIndex = slotIndex(hash);
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                // TODO check whether it's better to read a data word in advance and extract the
                //  byte upon matching, similar to what is done in a loop in split()
                int data = readData(segment, (long) matchSlotIndex);
                // TODO check: is it really worthwhile to store and check these bits?
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        return readValue(segment, allocIndex);
                    }
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                return null;
            }
            // InflatedSegment checking after unsuccessful key search: the key search above was
            // destined to be unsuccessful in an inflated segment, but we don't check whether the
            // segment is inflated or not in the beginning to declutter the hot path as much as
            // possible. This is enabled by the fact that InflatedSegment inherits Segment and thus
            // has a hash table area, see the Javadoc for InflatedSegment for details.
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                // Quadratic probing:
                // TODO try simple linear probing, also allows to relax the condition in
                //  removeAtSlot()
                slotIndexStep += GROUP_SLOTS;
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return getInflated(segment, key, hash);
            }
        }
    }

    /**
     * Shallow xxxInflated() methods: this method (and other xxxInflated() methods) just casts the
     * segment object to {@link InflatedSegment} and delegates to it's method. It's done to reduce
     * the bytecode size of {@link #get(Object)} as much as possible, see
     * [Reducing bytecode size of a hot method].
     */
    private @Nullable V getInflated(Object segment, Object key, long hash) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.get(this, key, hash);
    }

    @Override
    public final @Nullable K getInternalKey(Object key) {
        Utils.requireNonNull(key);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int groupFirstSlotIndex = slotIndex(hash);
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        return internalKey;
                    }
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return getInternalKeyInflated(segment, key, hash);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable K getInternalKeyInflated(Object segment, Object key, long hash) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.getInternalKey(this, key, hash);
    }

    @Override
    public final @Nullable V computeIfPresent(
            K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(remappingFunction);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int groupFirstSlotIndex = slotIndex(hash);
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        V internalVal = readValue(segment, allocIndex);
                        V newValue = remappingFunction.apply(key, internalVal);
                        if (newValue != null) {
                            writeValue(segment, allocIndex, internalVal);
                        } else {
                            removeAtSlot(hash, segment, matchSlotIndex, allocIndex);
                        }
                        return newValue;
                    }
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return computeIfPresentInflated(segment, key, hash, remappingFunction);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeIfPresentInflated(Object segment,
            K key, long hash, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.computeIfPresent(this, key, hash, remappingFunction);
    }

    /**
     * The common implementation for
     *  {@link #remove(Object)}: matchValue == null, replacementValue == null
     *  {@link #remove(Object, Object)}: matchValue != null, replacementValue == null
     *  {@link #replace(Object, Object)}: matchValue == null, replacementValue != null
     *  {@link #replace(Object, Object, Object)}: matchValue != null, replacementValue != null
     *
     * @return a value removed or replaced in the map, or null if no change was made. If matchValue
     * is null, the returned value is the internal value removed or replaced in the map. If
     * matchValue is non-null, the returned value could be the internal value _or_ the matchValue
     * itself.
     */
    private @Nullable V replace(Object segment, Object key, long hash, @Nullable Object matchValue,
            @Nullable V replacementValue) {
        int groupFirstSlotIndex = slotIndex(hash);
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        V internalVal = readValue(segment, allocIndex);
                        //noinspection ObjectEquality: identity comparision is intended
                        boolean valuesIdentical = internalVal == matchValue;
                        if (matchValue == null || valuesIdentical ||
                                valuesEqual(matchValue, internalVal)) {

                            if (replacementValue == null) {
                                removeAtSlot(hash, segment, matchSlotIndex, allocIndex);
                            } else {
                                writeValue(segment, allocIndex, replacementValue);
                            }
                            return internalVal;
                        } else {
                            return null;
                        }
                    }
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return replaceInflated(segment, key, hash, matchValue, replacementValue);
            }
        }
    }

    /**
     * [Shallow xxxInflated() methods]
     *
     * @return a value removed or replaced in the map, or null if no change was made. If matchValue
     * is null, the returned value is the internal value removed or replaced in the map. If
     * matchValue is non-null, the returned value could be the internal value _or_ the matchValue
     * itself.
     */
    @SuppressWarnings("unchecked")
    private @Nullable V replaceInflated(Object segment, Object key, long hash,
            @Nullable Object matchValue, @Nullable V replacementValue) {
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.replace(this, (K) key, hash, (V) matchValue, replacementValue);
    }

    @SuppressWarnings("unused") // will be used in removing iterators which are not implemented yet
    private void removeDuringIterationFromOrdinarySegment(Object segment,
            long hash, int allocIndexToRemove) {
        // Reading bitSetAndState in advance, outside of the loop where it's solely used to avoid a
        // data dependency stall before the call to removeAtSlotNoShrink(): the loop doesn't have
        // any normal outcome other than removing an entry, so this read of bitSetAndState must be
        // always useful.
        long bitSetAndState = getBitSetAndState(segment);
        /* if Enabled extraChecks */
        assertThat(!isInflatedBitSetAndState(bitSetAndState));
        /* endif */
        int groupFirstSlotIndex = slotIndex(hash);
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    if (allocIndex == allocIndexToRemove) {
                        // It's possible to implement shrinking during iteration, but it would be
                        // more complex than shrinking during ordinary removeAtSlot(), involving a
                        // procedure similar to compactEntriesDuringSplit().
                        // TODO implement shrinking during iteration
                        bitSetAndState = removeAtSlotNoShrink(
                                bitSetAndState, segment, matchSlotIndex, allocIndex);
                        setBitSetAndState(segment, bitSetAndState);
                        return;
                    }
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                // There is no entry at allocIndexToRemove with the same hash control bits as for
                // the provided hash, so, perhaps, it has been removed concurrently.
                throw new ConcurrentModificationException();
            }
            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
        }
    }

    private @Nullable V put(Object segment, K key, long hash, V value, boolean onlyIfAbsent) {
        int initialGroupFirstSlotIndex = slotIndex(hash);
        int groupFirstSlotIndex = initialGroupFirstSlotIndex;
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits); ;) {
                // Positive likely branch: the following condition is in a separate if block rather
                // than the loop condition (as in all other operations: find(), compute(), etc.) in
                // order to make it positive and so it's more likely that JIT compiles the code with
                // an assumption that this branch is taken (i. e. that bitMask is 0), that is what
                // we really expect during Map.put() or putIfAbsent().
                // TODO check bytecode output of javac
                // TODO check if this even makes sense
                // TODO check a different approach, with a distinguished check and then do-while
                if (bitMask == 0) {
                    break;
                }
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        V internalVal = readValue(segment, allocIndex);
                        if (!onlyIfAbsent) {
                            writeValue(segment, allocIndex, value);
                        }
                        return internalVal;
                    }
                }
                bitMask = clearLowestSetBit(bitMask);
            }
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) {
                long bitSetAndState = getBitSetAndState(segment);
                int deletedSlotCount = deletedSlotCount(bitSetAndState);
                int insertionSlotIndex;
                // boolean as int: enables branchless operations in insert(). 0 = false, 1 = true
                int replacingEmptySlot;
                // Zero deleted slots fast path: an always-taken branch if entries are never removed
                // from the SmoothieMap.
                if (deletedSlotCount == 0) {
                    insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
                    replacingEmptySlot = 1;
                } else if (slotIndexStep == 0) {
                    long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                    // Inlined lowestMatchIndex: computing the number of trailing zeros directly
                    // and then dividing by Byte.SIZE is inlined lowestMatchIndex(). It's inlined
                    // because the trailingZeros value is also needed for extractMatchingEmpty().
                    int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                    insertionSlotIndex =
                            lowestMatchIndexFromTrailingZeros(groupFirstSlotIndex, trailingZeros);
                    replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                } else {
                    // Deleted slot search heuristic:
                    // TODO instead of making a full-blown search, we can just save and check the
                    //  initial controls group, and if it contains no deleted controls, use the
                    //  same code as in `slotIndexStep == 0` branch above. This is very unlikely
                    //  that the probe chain is longer than 2 control groups, where the simplified
                    //  approach can be any worse because it might miss the opportunity to insert
                    //  the entry into a deleted slot in one of the middle (neither first nor last)
                    //  control groups in the probe chain

                    // Reusing local variables: it is better here to reuse existing local variables
                    // than introducing new variables, because of the risk of using a wrong
                    // variable. The old variables are not needed at this point because the outer
                    // `if` block ends with `return` unconditionally.
                    groupFirstSlotIndex = initialGroupFirstSlotIndex;
                    slotIndexStep = 0;
                    while (true) {
                        // Reusing another local variable.
                        controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
                        long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                        if (emptyOrDeletedBitMask != 0) {
                            // [Inlined lowestMatchIndex]
                            int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                            insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                    groupFirstSlotIndex, trailingZeros);
                            replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                            break;
                        }
                        slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                        groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                    }
                }
                insert(segment, key, hash, value, insertionSlotIndex, bitSetAndState,
                        deletedSlotCount, replacingEmptySlot);
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return putInflated(segment, key, hash, value, onlyIfAbsent);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V putInflated(Object segment, K key, long hash, V value,
            boolean onlyIfAbsent) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.put(this, key, hash, value, onlyIfAbsent);
    }

    @Override
    public final @Nullable V computeIfAbsent(
            K key, Function<? super K, ? extends V> mappingFunction) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(mappingFunction);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int initialGroupFirstSlotIndex = slotIndex(hash);
        int groupFirstSlotIndex = initialGroupFirstSlotIndex;
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        return readValue(segment, allocIndex);
                    }
                }
                bitMask = clearLowestSetBit(bitMask);
            }
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) {
                V value = mappingFunction.apply(key);
                if (value != null) {
                    long bitSetAndState = getBitSetAndState(segment);
                    int deletedSlotCount = deletedSlotCount(bitSetAndState);
                    int insertionSlotIndex;
                    int replacingEmptySlot; // [boolean as int]
                    if (deletedSlotCount == 0) { // [Zero deleted slots fast path]
                        insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
                        replacingEmptySlot = 1;
                    } else if (slotIndexStep == 0) {
                        long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                        // [Inlined lowestMatchIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                        insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                groupFirstSlotIndex, trailingZeros);
                        replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                    } else {
                        // TODO [Deleted slot search heuristic]
                        // [Reusing local variables]
                        groupFirstSlotIndex = initialGroupFirstSlotIndex;
                        slotIndexStep = 0;
                        while (true) {
                            // Reusing another local variable.
                            controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
                            long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                            if (emptyOrDeletedBitMask != 0) {
                                // [Inlined lowestMatchIndex]
                                int trailingZeros =
                                        Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                                insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                        groupFirstSlotIndex, trailingZeros);
                                replacingEmptySlot =
                                        extractMatchingEmpty(controlsGroup, trailingZeros);
                                break;
                            }
                            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                        }
                    }
                    insert(segment, key, hash, value, insertionSlotIndex,
                            bitSetAndState, deletedSlotCount, replacingEmptySlot);
                }
                return value;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return computeIfAbsentInflated(segment, key, hash, mappingFunction);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeIfAbsentInflated(Object segment,
            K key, long hash, Function<? super K, ? extends V> mappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.computeIfAbsent(this, key, hash, mappingFunction);
    }

    @Override
    public final @Nullable V compute(
            K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(remappingFunction);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int initialGroupFirstSlotIndex = slotIndex(hash);
        int groupFirstSlotIndex = initialGroupFirstSlotIndex;
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        V oldValue = readValue(segment, allocIndex);
                        V newValue = remappingFunction.apply(key, oldValue);
                        if (newValue != null) {
                            writeValue(segment, allocIndex, newValue);
                        } else {
                            removeAtSlot(hash, segment, matchSlotIndex, allocIndex);
                        }
                        return newValue;
                    }
                }
                bitMask = clearLowestSetBit(bitMask);
            }
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) {
                V value = remappingFunction.apply(key, null);
                if (value != null) {
                    long bitSetAndState = getBitSetAndState(segment);
                    int deletedSlotCount = deletedSlotCount(bitSetAndState);
                    int insertionSlotIndex;
                    int replacingEmptySlot; // [boolean as int]
                    if (deletedSlotCount == 0) { // [Zero deleted slots fast path]
                        insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
                        replacingEmptySlot = 1;
                    } else if (slotIndexStep == 0) {
                        long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                        // [Inlined lowestMatchIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                        insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                groupFirstSlotIndex, trailingZeros);
                        replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                    } else {
                        // TODO [Deleted slot search heuristic]
                        // [Reusing local variables]
                        groupFirstSlotIndex = initialGroupFirstSlotIndex;
                        slotIndexStep = 0;
                        while (true) {
                            // Reusing another local variable.
                            controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
                            long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                            if (emptyOrDeletedBitMask != 0) {
                                // [Inlined lowestMatchIndex]
                                int trailingZeros =
                                        Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                                insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                        groupFirstSlotIndex, trailingZeros);
                                replacingEmptySlot =
                                        extractMatchingEmpty(controlsGroup, trailingZeros);
                                break;
                            }
                            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                        }
                    }
                    insert(segment, key, hash, value, insertionSlotIndex,
                            bitSetAndState, deletedSlotCount, replacingEmptySlot);
                }
                return value;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return computeInflated(segment, key, hash, remappingFunction);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeInflated(Object segment,
            K key, long hash, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.compute(this, key, hash, remappingFunction);
    }

    @Override
    public final @Nullable V merge(
            K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Utils.requireNonNull(key);
        Utils.requireNonNull(value);
        Utils.requireNonNull(remappingFunction);
        long hash = keyHashCode(key);
        Object segment = segmentByHash(hash);
        int initialGroupFirstSlotIndex = slotIndex(hash);
        int groupFirstSlotIndex = initialGroupFirstSlotIndex;
        long hashControlBits = hashControlBits(hash);
        for (int slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                int data = readData(segment, (long) matchSlotIndex);
                if (matchHashDataBits(data, hash)) {
                    int allocIndex = allocIndex(data);
                    K internalKey = readKey(segment, allocIndex);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean keysIdentical = internalKey == key;
                    if (keysIdentical || keysEqual(key, internalKey)) {
                        V internalVal = readValue(segment, allocIndex);
                        V newValue = remappingFunction.apply(internalVal, value);
                        if (newValue != null) {
                            writeValue(segment, allocIndex, newValue);
                        } else {
                            removeAtSlot(hash, segment, matchSlotIndex, allocIndex);
                        }
                        return newValue;
                    }
                }
                bitMask = clearLowestSetBit(bitMask);
            }
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) {
                long bitSetAndState = getBitSetAndState(segment);
                int deletedSlotCount = deletedSlotCount(bitSetAndState);
                int insertionSlotIndex;
                int replacingEmptySlot; // [boolean as int]
                if (deletedSlotCount == 0) { // [Zero deleted slots fast path]
                    insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
                    replacingEmptySlot = 1;
                } else if (slotIndexStep == 0) {
                    long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                    // [Inlined lowestMatchIndex]
                    int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                    insertionSlotIndex =
                            lowestMatchIndexFromTrailingZeros(groupFirstSlotIndex, trailingZeros);
                    replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                } else {
                    // TODO [Deleted slot search heuristic]
                    // [Reusing local variables]
                    groupFirstSlotIndex = initialGroupFirstSlotIndex;
                    slotIndexStep = 0;
                    while (true) {
                        // Reusing another local variable.
                        controlsGroup = readControlsGroup(segment, (long) groupFirstSlotIndex);
                        long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                        if (emptyOrDeletedBitMask != 0) {
                            // [Inlined lowestMatchIndex]
                            int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                            insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                    groupFirstSlotIndex, trailingZeros);
                            replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                            break;
                        }
                        slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                        groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                    }
                }
                insert(segment, key, hash, value, insertionSlotIndex,
                        bitSetAndState, deletedSlotCount, replacingEmptySlot);
                return value;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (controlsGroup != INFLATED_SEGMENT_MARKER_CONTROLS_GROUP) {//[Positive likely branch]
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
            } else {
                return mergeInflated(segment, key, hash, value, remappingFunction);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V mergeInflated(Object segment, K key, long hash, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.merge(this, key, hash, value, remappingFunction);
    }

    //endregion

    //region insert() and the family of makeSpaceAndInsert() methods called from it

    /**
     * @param replacingEmptySlot must be 1 if the slot being filled had {@link
     * Segment#EMPTY_CONTROL} value before, 0 if {@link Segment#DELETED_CONTROL}.
     *
     * @apiNote deletedSlotCount can be re-extracted inside this method from bitSetAndState instead
     * of being passed. TODO compare the approaches
     */
    @HotPath
    private void insert(Object segment, K key, long hash, V value, int insertionSlotIndex,
            long bitSetAndState, int deletedSlotCount, int replacingEmptySlot) {
        int allocIndex = lowestFreeAllocIndex(bitSetAndState);
        int allocCapacity = allocCapacity(bitSetAndState);
        // The first part `deletedSlotCount == 0` of the following condition is merely a short
        // circuit, the second part
        // `segmentSize(bitSetAndState) + deletedSlotCount + replacingEmptySlot <=
        //         SEGMENT_MAX_NON_EMPTY_SLOTS` alone is definitive.
        // TODO check whether having the first part really improves the performance
        // If SEGMENT_MAX_NON_EMPTY_SLOTS is made equal to SEGMENT_MAX_ALLOC_CAPACITY (which is
        // quite possible, for tuning and experimentation), this first short-circuit would make
        // the variable enoughNonEmptySlots to not hold true *always if and only if* there are
        // enough non-empty slots, but only in cases enabled by additional conditions:
        // `allocIndex < allocCapacity` in this method and
        // `allocCapacity == SEGMENT_INTERMEDIATE_ALLOC_CAPACITY` in makeSpaceAndInsert().
        boolean enoughNonEmptySlots = deletedSlotCount == 0 ||
                segmentSize(bitSetAndState) + deletedSlotCount + replacingEmptySlot <=
                        SEGMENT_MAX_NON_EMPTY_SLOTS;
        if (allocIndex < allocCapacity) { // [Positive likely branch]
            if (enoughNonEmptySlots) { // [Positive likely branch]
                doInsert(segment, key, hash, value, insertionSlotIndex, bitSetAndState,
                        replacingEmptySlot, allocIndex);
                return;
            }
        }
        makeSpaceAndInsert(allocCapacity, segment, key, hash, value, insertionSlotIndex, allocIndex,
                bitSetAndState, deletedSlotCount, replacingEmptySlot, enoughNonEmptySlots);
    }

    private void doInsert(Object segment, K key, long hash, V value, int insertionSlotIndex,
            long bitSetAndState, int replacingEmptySlot, int allocIndex) {
        bitSetAndState = updateDeletedSlotCountAndSetLowestAllocBit(
                bitSetAndState, replacingEmptySlot);
        setBitSetAndState(segment, bitSetAndState);
        writeEntry(segment, key, hash, value, insertionSlotIndex, allocIndex);
        size++;
        modCount++;
    }

    /**
     * Makes space for an extra entry by means of either {@link
     * #growCapacityAndDropDeletesIfNeededAndInsert}, {@link #splitAndInsert}, {@link
     * #inflateAndInsert} or {@link #dropDeletesAndInsert}.
     *
     * @apiNote
     * Some of the parameters of this method can be recomputed inside it, that would reduce the
     * bytecode size of {@link #insert} (that is good: see [Reducing bytecode size of a hot method])
     * and might be even cheaper than putting variables that are in registers onto stack and then
     * popping them from the stack (that happens when so many arguments are passed to the method).
     * Namely, allocCapacity and deletedSlotCount can be re-extracted from bitSetAndState, and
     * enoughNonEmptySlots can be recomputed in a similar manner as it is computed in {@link
     * #insert}. This is the same tradeoff as in {@link #insert} itself, where deletedSlotCount
     * argument can be re-extracted inside the method, see the comment for that method.
     * TODO compare the approaches
     */
    @AmortizedPerSegment
    final void makeSpaceAndInsert(int allocCapacity, Object segment, K key, long hash, V value,
            int insertionSlotIndex, int allocIndex, long bitSetAndState, int deletedSlotCount,
            int replacingEmptySlot, boolean enoughNonEmptySlots) {
        if (bitSetAndState == BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) {
            throw new ConcurrentModificationException();
        }

        // ### First route: check if the segment is intermediate-sized and should be grown to full
        // ### size.
        // This branch could more naturally be placed in insert() (and then allocCapacity shouldn't
        // be passed into makeSpaceAndInsert()) but there is an objective to make bytecode size of
        // insert() as small as possible, see [Reducing bytecode size of a hot method]. TODO compare
        boolean segmentIsShortOfAllocSpace = allocIndex >= allocCapacity;
        // TODO check whether & instead of && helps
        if (segmentIsShortOfAllocSpace & allocCapacity == SEGMENT_INTERMEDIATE_ALLOC_CAPACITY) {
            // Dropping deleted slots from a non-full-capacity segment: it's theoretically possible
            // that a segment hits SEGMENT_INTERMEDIATE_ALLOC_CAPACITY and
            // SEGMENT_MAX_NON_EMPTY_SLOTS thresholds at the same time (theoretically it's enabled
            // by the fact that SEGMENT_INTERMEDIATE_ALLOC_CAPACITY (30) > GROUP_SLOTS (8), that
            // allows to slowly fill the hash table with deleted slots, see the condition for
            // writing DELETED_CONTROL into a slot in removeAtSlot()). However, that requires a very
            // specialized, likely manually crafted sequence of insertions and deletions into a
            // segment. But still, growing the alloc capacity and cleaning up deleted slots are
            // independent actions in concept. Because of that, enoughNonEmptySlots should be passed
            // into growCapacityAndDropDeletesIfNeededAndInsert(), where we drop deletes if
            // enoughNonEmptySlots is false as well as growing the capacity of the segment.
            growCapacityAndDropDeletesIfNeededAndInsert(segment, key, hash, value,
                    insertionSlotIndex, bitSetAndState, replacingEmptySlot, enoughNonEmptySlots);
            return;
        }

        int newDeletedSlotCount = deletedSlotCount - 1 + replacingEmptySlot;

        // Need to read modCount here rather than inside methods splitAndInsert(),
        // inflateAndInsert(), and dropDeletesAndInsert() so that it is done before calling to
        // tryEnsureSegmentsArrayCapacityForSplit() that may update the modCount field (and is a
        // bulky method that needs to be surrounded with modCount read and check).
        int modCount = getModCountOpaque();

        // ### Second route: consider splitting or inflation.
        // If the previous route is not taken and growCapacityAndDropDeletesIfNeededAndInsert() is
        // not called, but the alloc capacity is SEGMENT_INTERMEDIATE_ALLOC_CAPACITY, the deleted
        // slot count should be at least SEGMENT_MAX_NON_EMPTY_SLOTS -
        // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY (= 20) > SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES
        // (= 5), so the following branch won't be taken either. It means that a segment with the
        // intermediate alloc capacity can't be split or inflated without growing or dropping
        // deleted slots first.
        if (newDeletedSlotCount < SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES) {
            int segmentOrder = segmentOrder(bitSetAndState);
            // InflatedSegment.shouldBeSplit() refers to and depends on the following call to
            // computeAverageSegmentOrder().
            int averageSegmentOrder = computeAverageSegmentOrder(size);
            boolean acceptableOrderAfterSplitting =
                    segmentOrder < averageSegmentOrder + MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE;
            int modCountIncrement;
            // In principle, we can still split to-become-outlier segments or segments that are
            // already outliers as long as their order is less than segmentsArray's order (which
            // might happen to be greater than averageSegmentOrder +
            // MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE if the SmoothieMap used to be larger and
            // has shrunk in size since). But it's not done because we don't want to disturb the
            // poor hash code distribution detection (see HashCodeDistribution), as well as
            // the functionality of moveToMapWithShrunkArray().
            if (acceptableOrderAfterSplitting &&
                    (modCountIncrement =
                            tryEnsureSegmentsArrayCapacityForSplit(segmentOrder)) >= 0) {
                modCount += modCountIncrement;
                splitAndInsert(modCount, segment, key, hash, value, bitSetAndState, segmentOrder);
                return;
            } else {
                // If a segment should otherwise be inflated, try to get around with
                // dropDeletesAndInsert() even if there are less deleted slots than
                // SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES as long as the segment is not short
                // of alloc space.
                if (segmentIsShortOfAllocSpace) {
                    inflateAndInsert(
                            modCount, segmentOrder, segment, bitSetAndState, key, hash, value);
                    return;
                }
                // Intentional fall-through to dropDeletesAndInsert()
            }
        }

        // ### Third route: drop deletes.
        dropDeletesAndInsert(modCount, segment, bitSetAndState, key, hash, value, allocIndex);
    }

    @AmortizedPerSegment
    private void growCapacityAndDropDeletesIfNeededAndInsert(Object oldSegment, K key, long hash,
            V value, int insertionSlotIndex, long bitSetAndState, int replacingEmptySlot,
            boolean enoughNonEmptySlots) {
        int modCount = getModCountOpaque();

        // The old segment's bitSetAndState is never reset back to an operational value after this
        // statement.
        setBitSetAndState(oldSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        // ### Create a new segment.
        int oldAllocCapacity = SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
        int newAllocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
        Segment<K, V> newSegment = Segment.allocateSegment(newAllocCapacity);
        newSegment.copyHashTableFrom(oldSegment);
        newSegment.copyAllocAreaFrom(oldSegment, oldAllocCapacity);
        bitSetAndState = makeBitSetAndStateWithNewAllocCapacity(bitSetAndState, newAllocCapacity);
        newSegment.bitSetAndState = bitSetAndState;
        U.storeFence(); // [Safe segment publication]

        // ### Replace references from oldSegment to newSegment in segmentsArray.
        int segmentOrder = segmentOrder(bitSetAndState);
        int firstSegmentIndex = firstSegmentIndexByHashAndOrder(hash, segmentOrder);
        replaceInSegmentsArray(
                getNonNullSegmentsArrayOrThrowCme(), firstSegmentIndex, segmentOrder, newSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        // ### Drop deleted slots if needed and insert the new entry.
        // In a segment with alloc capacity smaller than SEGMENT_MAX_ALLOC_CAPACITY that was full
        // the next free alloc index could only be equal to the old alloc capacity.
        //noinspection UnnecessaryLocalVariable
        int allocIndex = oldAllocCapacity;
        if (enoughNonEmptySlots) { // [Positive likely branch]
            doInsert(newSegment, key, hash, value, insertionSlotIndex, bitSetAndState,
                    replacingEmptySlot, allocIndex);
            modCount++; // Matches the modCount field increment performed in doInsert().

            checkModCountOrThrowCme(modCount);
        } else {
            // Since the alloc capacity is just increased and SEGMENT_MAX_ALLOC_CAPACITY -
            // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY (= 18) >
            // SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES (= 5) it's not possible that
            // splitAndInsert() should be called here instead of dropDeletesAndInsert(). And it
            // wouldn't make sense because then splitAndInsert() should have been called instead of
            // growCapacityAndDropDeletesIfNeededAndInsert() in the first place.
            dropDeletesAndInsert(
                    modCount, newSegment, bitSetAndState, key, hash, value, allocIndex);
            // modCount is checked inside dropDeletesAndInsert(), so it doesn't need to be checked
            // again here.
        }
    }

    @AmortizedPerSegment
    private void splitAndInsert(int modCount, Object fromSegment, K key, long hash, V value,
            long fromSegment_bitSetAndState, int priorSegmentOrder) {
        // Increment modCount because splitting is a modification itself.
        modCount++;
        // Parallel modCount field increment: increment modCount field on itself rather than
        // assigning the local variable to still be able to capture the discrepancy and throw a
        // ConcurrentModificationException in the end of this method.
        this.modCount++;

        // The bitSetAndState of fromSegment is reset back to an operational value inside split(),
        // closer to the end of the method.
        setBitSetAndState(fromSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        int siblingSegmentsOrder = priorSegmentOrder + 1;

        Segment.@Nullable DebugHashTableSlot[] debugHashTableSlots =
                ((Segment) fromSegment).debugHashTable(this);
        int firstSiblingSegmentsIndex =
                firstSegmentIndexByHashAndOrder(hash, priorSegmentOrder);
        if (firstSiblingSegmentsIndex == 52680 && priorSegmentOrder == 16) {
            int x = 0;
        }

        // ### Create a new segment and split entries between fromSegment and the new segment.
        int intoSegmentAllocCapacity = getInitialSegmentAllocCapacity(siblingSegmentsOrder);
        // intoSegment's bitSetAndState is written and [Safe segment publication] is ensured inside
        // split(), closer to the end of the method.
        Segment<K, V> intoSegment =
                Segment.allocateNewSegmentAndSetEmptyControls(intoSegmentAllocCapacity);
        int siblingSegmentsQualificationBitIndex =
                SEGMENT_LOOKUP_HASH_SHIFT + siblingSegmentsOrder - 1;

        long fromSegmentIsHigher = split(fromSegment,
                fromSegment_bitSetAndState, intoSegment, intoSegmentAllocCapacity,
                siblingSegmentsOrder, siblingSegmentsQualificationBitIndex);
        // storeFence() is called inside split() to make publishing of intoSegment safe.

        // ### Publish intoSegment (the new segment) to segmentsArray.
        int intoSegmentIsLower = (int) (fromSegmentIsHigher >>>
                siblingSegmentsQualificationBitIndex);
//        int firstSiblingSegmentsIndex =
//                firstSegmentIndexByHashAndOrder(hash, priorSegmentOrder);
        int firstIntoSegmentIndex = chooseFirstSiblingSegmentIndex(
                firstSiblingSegmentsIndex, siblingSegmentsOrder, intoSegmentIsLower);
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(), firstIntoSegmentIndex,
                siblingSegmentsOrder, intoSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        /* if Tracking segmentOrderStats */
        addToSegmentCountWithOrder(siblingSegmentsOrder, 2);
        subtractFromSegmentCountWithOrder(priorSegmentOrder, 1);
        /* endif */

        // Unlike all other similar methods which may be called from makeSpaceAndInsert(), namely
        // growCapacityAndDropDeletesIfNeededAndInsert(), inflateAndInsert(), and
        // dropDeletesAndInsert() we check the modCount here before the "insert" part of the method
        // because the insertion might cause fromSegment or intoSegment to be inflated or split
        // itself if during the splitting all or almost all entries went to one of the segments.
        // There is no cheap way to detect if that have happened to additionally increment the local
        // copy of modCount. (Compare with the similar problem in splitInflated().) After all, the
        // main point is making a modCount check after bulky operations: split() and
        // replaceInSegmentsArray() which are called above. Including the last point update (the
        // put() call below) in the scope of the modCount check is not necessary.
        checkModCountOrThrowCme(modCount);

        // ### Insert the new entry into fromSegment or intoSegment.
        long siblingSegmentsQualificationBit = 1L << siblingSegmentsQualificationBitIndex;
        boolean newEntryShouldGoInFromSegment =
                (hash & siblingSegmentsQualificationBit) == fromSegmentIsHigher;
        Object segmentToInsertNewEntryInto =
                newEntryShouldGoInFromSegment ? fromSegment : intoSegment;
        if (put(segmentToInsertNewEntryInto, key, hash, value, true /* onlyIfAbsent */) != null) {
            throw new ConcurrentModificationException();
        }
    }

    /**
     * Distributes entries between fromSegment and intoSegment.
     *
     * fromSegment_bitSetAndState includes the prior segment order, that is equal to newSegmentOrder
     * - 1. It's incremented inside this method and is written into fromSegment's {@link
     * Segment#bitSetAndState}. intoSegment's bitSetAndState is also valid after this method
     * returns.
     *
     * @return [boolean as long], 0 if fromSegment is the lower-index one of the two sibling
     * segments after the split, or `1 << siblingSegmentsQualificationBitIndex` if fromSegment is
     * the higher-index one. See the definition of swappedSegmentsInsideLoopAndFromSegmentIsHigher
     * variable inside the method.
     *
     * @apiNote siblingSegmentsQualificationBitIndex can be re-computed inside the method from
     * newSegmentOrder instead of being passed as a parameter. There is the same "pass-or-recompute"
     * tradeoff as in {@link #insert} and {@link #makeSpaceAndInsert}. However, in split() an
     * additional factor for passing siblingSegmentsQualificationBitIndex is making it being
     * computed only once in {@link #splitAndInsert}, hence less implicit dependency and probability
     * of making mistakes.
     */
    @SuppressWarnings({"UnnecessaryLabelOnBreakStatement", "UnnecessaryLabelOnContinueStatement"})
    @AmortizedPerSegment
    final long split(Object fromSegment, long fromSegment_bitSetAndState,
            Object intoSegment, int intoSegmentAllocCapacity, int newSegmentOrder,
            int siblingSegmentsQualificationBitIndex) {
        // ### Convert full control slots in fromSegment's hash table to deleted:
        // split() has a side-effect of purging deleted slots from the hash table of fromSegment
        // (see the first call in this method) and "squeezing out" empty slots that were written in
        // removeAtSlot() instead of deleted slots and may have been keeping probe chains longer
        // than they needed to be for some keys that are present in fromSegment at the moment (see
        // displacingLoop below in this method).
        //
        // Conversion of full slots to deleted and then, essentially, back is required even if there
        // were no deletions from fromSegment because splitting of a segment (that is, moving
        // approximately half of the entries to intoSegment) has the same effect as deletions. To
        // prevent breaking chain invariants (of always less than GROUP_SLOTS consecutive empty
        // slots on any chain between the base slot and the actual insertion slot; see how this
        // invariant is ensured in removeAtSlot()) either a sort of "shift deletion" procedure
        // (classical for linear probing) would be needed (but shift deletion would be very costly
        // in SwissTable with its high load factors and where entries shifted by at least some slots
        // is a norm rather than an exception, so this algorithm doesn't worth serious
        // consideration), or at least two separate walks over the hash table of fromSegment would
        // be required: the first one to move entries that need to go to intoSegment and to shift
        // other slots, the second one walk should start from any empty slot (rather than from any
        // slot, e. g. slot #0) and re-shift slots again, restoring the chain invariants. This
        // second algorithm with walking the hash table twice is very likely to be slower than
        // simply always converting full slots to deleted and having a displacing loop (what is
        // implemented currently), and this alternative algorithm is definitely more complex.
        // TODO compare the conversion + displacingLoop algorithm with the double walk algorithm
        //  (low priority because unlikely to be fruitful)
        covertAllDeletedToEmptyAndFullToDeletedControlSlots(fromSegment);

        // ### Defining variables that will be used in and after [fromSegment iteration].
        /* if Tracking hashCodeDistribution */
        int initialFromSegmentSize = segmentSize(fromSegment_bitSetAndState);
        // This variable is used to count how many hashes of keys in fromSegment have 1 in
        // LOG_HASH_TABLE_SIZE-th bit. See how it used in the end of this method.
        // Not counting numKeysForHalfOne or numKeysForHalfTwo directly to make the computation
        // inside [fromSegment iteration] loop cheaper.
        int hashTableHalfBits = 0;
        /* endif */
        int intoSegmentAllocIndex = 0;
        // boolean as long: see [boolean as int]. Reusing a variable for two purposes, as a sanity
        // boolean flag ("swappedSegmentsInsideLoop") and as a bit indicating that fromSegment is
        // the higher index segment ("fromSegmentIsHigher") to minimize the number of variables in
        // this giant method and thus use registers more efficiently. This variable is of long type
        // rather than int to avoid conversion inside [fromSegment iteration] loop, where
        // entryShouldRemainInFromSegment is computed. Unlike [boolean as int], "true" value is
        // equal to siblingSegmentsQualificationBit (1 << siblingSegmentsQualificationBitIndex)
        // rather than 1.
        long swappedSegmentsInsideLoopAndFromSegmentIsHigher = 0;
        long siblingSegmentsQualificationBit = 1L << siblingSegmentsQualificationBitIndex;

        // ### fromSegment iteration: restoring values in control slots, moving entries to
        // intoSegment. If this bit in a hash of some key is "fromSegmentIsHigher", then it should
        // stay in fromSegment rather than be split into intoSegment.
        //
        // Note: this loop is an extended version of
        // [Restoring values in control slots and optimizing the hash table] loop in dropDeletes(),
        // enriched with collecting statistics and moving entries to intoSegment. Whenever possible,
        // comments are put in dropDeletes() and referenced from split() to reduce the size of
        // split().
        // [Byte-by-byte hash table iteration]
        // [Int-indexed loop to avoid a safepoint poll]
        for (int slotIndex = 0; slotIndex < HASH_TABLE_SLOTS; slotIndex++) {
            // [Visiting only deleted slots]
            boolean shouldVisitEntry =
                    readControlByte(fromSegment, (long) slotIndex) == DELETED_CONTROL;
            if (!shouldVisitEntry) {
                continue;
            }
            int dataByteToDisplace = readData(fromSegment, (long) slotIndex);

            byte controlByteToWriteAtSlotIndex;

            // See [displacingLoop] in dropDeletes().
            displacingLoop:
            for (boolean isFirstDisplacingLoopIteration = true;
                // There is no condition in this loop. We either break from it (see
                // `break displacingLoop`) or continue explicitly (see
                // `continue displacingLoop`)
                    ;
                 isFirstDisplacingLoopIteration = false
            ) {
                int allocIndex = allocIndex(dataByteToDisplace);
                K key = readKey(fromSegment, allocIndex);
                long hash = keyHashCode(key);

                /* if Tracking hashCodeDistribution */
                int halfBit = 1 << (LOG_HASH_TABLE_SIZE - 1);
                // Counting hashTableHalfBits regardless of whether hashCodeDistribution is null
                // (see [hashCodeDistribution null check]), because adding a null check is
                // likely more expensive than doing this small branchless computation itself.
                hashTableHalfBits += ((int) hash) & halfBit;
                /* endif */

                int slotIndexBase = slotIndex(hash);
                // slotIndex is used at the second and later iterations of displacingLoop because
                // before proceeding to these iterations
                // [Virtual swap of data slot at slotIndex and newSlotIndex] is done.
                int quadraticProbingChainGroupIndex =
                        // [Replacing division with shift]
                        (slotIndex - slotIndexBase) >>> GROUP_SLOTS_DIVISION_SHIFT;
                byte restoredFullControlByte = (byte) hashControlBits(hash);

                // Using "fromSegmentIsHigher" meaning of the
                // swappedSegmentsInsideLoopAndFromSegmentIsHigher variable in this expression.
                final boolean entryShouldRemainInFromSegment =
                        (hash & siblingSegmentsQualificationBit) ==
                                swappedSegmentsInsideLoopAndFromSegmentIsHigher;

                if (entryShouldRemainInFromSegment) { // 50-50 unpredictable branch
                    // ### The entry remains in fromSegment

                    // [Non-zero quadraticProbingChainGroupIndex]
                    if (quadraticProbingChainGroupIndex != 0) { // Unlikely branch
                        // [Find first non-full slot]
                        int newSlotIndex;
                        long searchControlsGroup;
                        int trailingZeros;
                        {
                            int groupFirstSlotIndex = slotIndexBase;
                            // TODO [Unbounded search loop]
                            for (int slotIndexStep = 0; ; ) {
                                searchControlsGroup =
                                        readControlsGroup(fromSegment, (long) groupFirstSlotIndex);
                                long emptyOrDeletedBitMask =
                                        matchEmptyOrDeleted(searchControlsGroup);
                                // [Positive likely branch]
                                if (emptyOrDeletedBitMask != 0) {
                                    // [Inlined lowestMatchIndex]
                                    trailingZeros =
                                            Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                                    newSlotIndex = lowestMatchIndexFromTrailingZeros(
                                            groupFirstSlotIndex, trailingZeros);
                                    break;
                                }
                                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                                groupFirstSlotIndex =
                                        addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                            }
                        }
                        int newQuadraticProbingChainGroupIndex =
                                // [Replacing division with shift]
                                (newSlotIndex - slotIndexBase) >>> GROUP_SLOTS_DIVISION_SHIFT;
                        // [Likely "different newQuadraticProbingChainGroupIndex" branch]
                        // [Positive likely branch]
                        if (!groupIndexesEqualModuloHashTableGroups(
                                newQuadraticProbingChainGroupIndex,
                                quadraticProbingChainGroupIndex)) {
                            // [Conditional control byte cloning]
                            writeControlByteWithConditionalCloning(
                                    fromSegment, (long) newSlotIndex, restoredFullControlByte);
                            boolean newSlotWasEmpty =
                                    isMatchingEmpty(searchControlsGroup, trailingZeros);
                            if (newSlotWasEmpty) {
                                // Finish displacingLoop.
                                writeData(fromSegment, (long) newSlotIndex,
                                        (byte) dataByteToDisplace);

                                // Empty the slot at slotIndex.
                                controlByteToWriteAtSlotIndex = EMPTY_CONTROL;
                                // Not calling writeData() for slotIndex, i. e. leaving
                                // "garbage" in that data slot.

                                break displacingLoop;
                            } else {
                                // If [Branchless hash table iteration] was used in split() then
                                // controlsGroup (or, rather, the iterated bitMask directly) and
                                // dataGroup would need to be updated here if
                                // newSlotIndex - slotIndex < GROUP_SLOTS.

                                // [Virtual swap of data slot at slotIndex and newSlotIndex]
                                dataByteToDisplace = replaceData(fromSegment,
                                        (long) newSlotIndex, (byte) dataByteToDisplace);
                                continue displacingLoop;
                            }
                        }
                        // [Quadratic probing chain group indexes are equal]
                    }
                    // Restore full control byte at slotIndex:
                    controlByteToWriteAtSlotIndex = restoredFullControlByte;
                    // This branch is unlikely because it could only be taken if
                    // [Non-zero quadraticProbingChainGroupIndex] branch was taken which is
                    // unlikely.
                    if (!isFirstDisplacingLoopIteration) { // Unlikely branch
                        // Delayed write of a swapped data byte at slotIndex:
                        writeData(fromSegment, (long) slotIndex, (byte) dataByteToDisplace);
                    }
                    break displacingLoop;
                }
                else {
                    // ### The entry is moved to intoSegment

                    // #### Swap fromSegment's and intoSegment's contents if the intoSegment is
                    // #### full.
                    // The probability of taking the following branch once during the
                    // [fromSegment iteration], that is, when more than
                    // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY go into an intermediate-sized
                    // intoSegment, equals to 1 -
                    // CDF[BinomialDistribution[SEGMENT_MAX_ALLOC_CAPACITY(48), 0.5], 30] ~=
                    // 3% (except for the cases when the distribution is skewed so that the majority
                    // of segments at some order split with much more keys going to higher-index
                    // siblings than to lower-index ones). There is no doubling of probability like
                    // for HashCodeDistribution's
                    // OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS
                    // values because if the distribution is skewed towards fromSegment the
                    // following branch is not taken. If initialFromSegmentSize <
                    // SEGMENT_MAX_ALLOC_CAPACITY (48), in other words, if there are some deleted
                    // control slots the probability is even lower.
                    //
                    // Therefore the following branch is very unlikely on each individual iteration.
                    //
                    // Doesn't seem practical to use [Positive likely branch] principle
                    // because it would require to add a dummy loop and organize code in a
                    // very unnatural way.
                    if (intoSegmentAllocIndex == intoSegmentAllocCapacity) {
                        // ### Swapping segments if intoSegment is overflowed.
                        // Alternative to swapping segments inline the [fromSegment iteration] and
                        // swapping fromSegment and intoSegment variables is finishing iteration in
                        // a separate method like "finishSplitAfterSwap()". The advantage of this
                        // approach is that entryShouldRemainInFromSegment's computation is cheaper
                        // (just check == 0 instead of comparing with a local), and that fromSegment
                        // and intoSegment variable would be effectively final that may allow
                        // compiler to generate more efficient machine code. The disadvantages is
                        // higher overall complexity, having a separate method that is called rarely
                        // (hence pretty cold and may kick JIT (re) compilation well into
                        // SmoothieMap's operation), more methods to compile, poorer utilization of
                        // instruction cache. The disadvantages seem to outweigh the advantages.

                        // Using "swappedSegmentsInsideLoop" meaning of the
                        // swappedSegmentsInsideLoopAndFromSegmentIsHigher variable in this
                        // expression.
                        final boolean notSwappedSegmentsInsideLoop =
                                swappedSegmentsInsideLoopAndFromSegmentIsHigher == 0;
                        if (notSwappedSegmentsInsideLoop) {
                            swappedSegmentsInsideLoopAndFromSegmentIsHigher =
                                    siblingSegmentsQualificationBit;
                            // makeSpaceAndInsert[Second route] guarantees that only
                            // full-capacity segments are split:
                            /* if Enabled extraChecks */
                            assertEqual(allocCapacity(fromSegment_bitSetAndState),
                                    SEGMENT_MAX_ALLOC_CAPACITY);
                            /* endif */
                            int fromSegmentAllocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
                            fromSegment_bitSetAndState = swapHashTablesAndAllocAreasDuringSplit(
                                    fromSegment, fromSegmentAllocCapacity,
                                    fromSegment_bitSetAndState,
                                    intoSegment, intoSegmentAllocCapacity);
                            // Swap fromSegment and intoSegment variables.
                            {
                                Object tmpSegment = intoSegment;
                                intoSegment = fromSegment;
                                fromSegment = tmpSegment;

                                intoSegmentAllocCapacity = fromSegmentAllocCapacity;
                            }

                            // If [Branchless hash table iteration] was used in split() then
                            // dataGroup would need to be re-read here because it might have been
                            // updated in a swapHashTablesAndAllocAreasDuringSplit() call above
                            // if compactEntriesDuringSplit() was called. (The re-read could have
                            // been conditional on the fact that fromSegment_bitSetAndState returned
                            // from swapHashTablesAndAllocAreasDuringSplit() is different from the
                            // former value, but the approaches would need to be compared.)
                            // controlsGroup wouldn't need to be re-read because control slots are
                            // not updated in swapHashTablesAndAllocAreasDuringSplit() or
                            // compactEntriesDuringSplit().

                        } else {
                            // Already swapped segments inside the splitting loop once. This
                            // might only happen if entries are inserted into fromSegment
                            // concurrently with the splitting loop.
                            throw new ConcurrentModificationException();
                        }
                    }

                    V value = readValue(fromSegment, allocIndex);

                    // #### Put the entry into intoSegment.
                    {
                        // Optimization
                        // if (relativeGroupIndex == 0) { insertionSlotIndex = slotIndex; }
                        // is impossible here because slotIndex may be already taken by some
                        // entry that was previously moved to intoSegment into an
                        // insertionSlotIndex that was greater than the slotIndex of that entry
                        // in fromSegment because it was shifted and wrapped around the hash
                        // table.
                        // TODO check if an optimization is still possible in some other way,
                        //  perhaps if linear probing is used instead of quadratic
                        int insertionSlotIndex =
                                findFirstEmptySlotForSlotIndexBase(intoSegment, slotIndexBase);
                        // [Conditional control byte cloning]
                        writeControlByteWithConditionalCloning(intoSegment,
                                (long) insertionSlotIndex, restoredFullControlByte);
                        writeData(intoSegment, (long) insertionSlotIndex,
                                makeData(intoSegmentAllocIndex, hash));
                        writeKey(intoSegment, intoSegmentAllocIndex, key);
                        writeValue(intoSegment, intoSegmentAllocIndex, value);
                        intoSegmentAllocIndex++;
                    }

                    // #### Purge the entry from fromSegment.
                    {
                        eraseKeyAndValue(fromSegment, allocIndex);
                        fromSegment_bitSetAndState =
                                clearAllocBit(fromSegment_bitSetAndState, allocIndex);
                        controlByteToWriteAtSlotIndex = EMPTY_CONTROL;
                        // Not calling writeData() for slotIndex, i. e. leaving "garbage" in
                        // that data slot.
                    }
                    break displacingLoop;
                }
                // Must break from or continue the displacingLoop across all paths above.
                // Uncommenting the following statement should make the compiler complain about
                // "unreachable statement".
                /* comment */
                throw new AssertionError();
                /**/
            } // end of displacingLoop

            // [Conditional control byte cloning]
            writeControlByteWithConditionalCloning(
                    fromSegment, (long) slotIndex, controlByteToWriteAtSlotIndex);
        }

        // ### Update fromSegment's bitSetAndState.
        // Zeroing deleted slot count because we purged all deleted slots in the beginning of this
        // method.
        fromSegment_bitSetAndState =
                incrementSegmentOrderAndZeroDeletedSlotCount(fromSegment_bitSetAndState);
        setBitSetAndStateAfterBulkOperation(fromSegment, fromSegment_bitSetAndState);

        // ### Write out intoSegment's bitSetAndState and publish it to segmentsArray.
        long intoSegment_bitSetAndState = makeBitSetAndStateForPrivatelyPopulatedSegment(
                intoSegmentAllocCapacity, newSegmentOrder,
                intoSegmentAllocIndex);
        setBitSetAndState(intoSegment, intoSegment_bitSetAndState);
        U.storeFence(); // [Safe segment publication]

        /* if Tracking hashCodeDistribution */
        // TODO [hashCodeDistribution null check]
        @Nullable HashCodeDistribution<K, V> hashCodeDistribution = this.hashCodeDistribution;
        if (hashCodeDistribution != null) {
            int numKeysForHalfOne = hashTableHalfBits >>> (LOG_HASH_TABLE_SIZE - 1);
            hashCodeDistribution.accountSegmentSplit(
                    this, newSegmentOrder - 1, numKeysForHalfOne, initialFromSegmentSize);
        }
        /* endif */

        return swappedSegmentsInsideLoopAndFromSegmentIsHigher;
    }

    /** Invariant before calling this method: oldSegment's size is equal to the capacity. */
    private void inflateAndInsert(int modCount, int segmentOrder, Object oldSegment,
            long bitSetAndState, K key, long hash, V value) {

        // The old segment's bitSetAndState is never reset back to an operational value after this
        // statement.
        setBitSetAndState(oldSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        /* if Enabled extraChecks */
        int allocCapacity = allocCapacity(bitSetAndState);
        assertEqual(allocCapacity, SEGMENT_MAX_ALLOC_CAPACITY);
        assertEqual(allocCapacity, segmentSize(bitSetAndState));
        /* endif */
        InflatedSegment<K, V> inflatedSegment = new InflatedSegment<>(segmentOrder, size);
        copyEntriesFromOrdinaryDuringInflate(oldSegment, inflatedSegment);

        int firstSegmentIndex = firstSegmentIndexByHashAndOrder(hash, segmentOrder);
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(),
                firstSegmentIndex, segmentOrder, inflatedSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        if (inflatedSegment.put(this, key, hash, value, true /* onlyIfAbsent */) != null) {
            throw new ConcurrentModificationException();
        }
        // Matches the modCount field increment performed in InflatedSegment.put(). We don't expect
        // deflateSmall() or splitInflated() to be triggered during this put (they would mean that
        // some updates to the map have been happening concurrently with this inflateAndInsert()
        // call), so if they are called and cause an extra modCount increment we expectedly throw
        // a ConcurrentModificationException in the subsequent check.
        modCount++;

        checkModCountOrThrowCme(modCount);
    }

    private void copyEntriesFromOrdinaryDuringInflate(
            Object oldSegment, InflatedSegment<K, V> intoSegment) {
        // Just iterating all alloc indexes until constant SEGMENT_MAX_ALLOC_CAPACITY because it
        // must be true in inflateAndInsert() from where this method is called that the segment's
        // capacity is equal to SEGMENT_MAX_ALLOC_CAPACITY and that the size is equal to capacity.
        for (int allocIndex = 0; allocIndex < SEGMENT_MAX_ALLOC_CAPACITY; allocIndex++) {
            K key = readKey(oldSegment, allocIndex);
            V value = readValue(oldSegment, allocIndex);
            intoSegment.putDuringInflation(this, key, keyHashCode(key), value);
        }
    }

    private void dropDeletesAndInsert(int modCount, Object segment, long bitSetAndState, K key,
            long hash, V value, int allocIndex) {
        // Increment modCount because dropping deleted slots from a segment is a modification
        // itself.
        modCount++;
        // [Parallel modCount field increment]
        this.modCount++;

        setBitSetAndState(segment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        // ### Drop deleted slots from the hash table of the segment.
        dropDeletes(segment);
        bitSetAndState = makeBitSetAndStateWithNewDeletedSlotCount(bitSetAndState, 0);

        // ### Insert the new entry.
        int insertionSlotIndex = findFirstEmptySlotForHash(segment, hash);

        // Inlined version of doInsert() that calls setLowestAllocBit() instead of
        // updateDeletedSlotCountAndSetLowestAllocBit() (because we must not replace an empty slot
        // just after dropping them) and setBitSetAndStateAfterBulkOperation() instead
        // of simple setBitSetAndState().
        {
            bitSetAndState = setLowestAllocBit(bitSetAndState);
            writeEntry(segment, key, hash, value, insertionSlotIndex, allocIndex);
            size++;
            this.modCount++;
            modCount++;
            setBitSetAndStateAfterBulkOperation(segment, bitSetAndState);
        }

        checkModCountOrThrowCme(modCount);
    }

    @SuppressWarnings({"UnnecessaryLabelOnBreakStatement", "UnnecessaryLabelOnContinueStatement"})
    private void dropDeletes(Object segment) {
        // TODO comment about this action
        covertAllDeletedToEmptyAndFullToDeletedControlSlots(segment);

        // ### Restoring values in control slots and optimizing the hash table:
        // Note: this loop is a simplified version of [fromSegment iteration] loop in split(). These
        // loops must have the same structure and be changed in parallel. Whenever possible,
        // comments about various aspects of this loop are put in dropDeletes() and referenced from
        // split() to reduce the size of the latter.
        //
        // Byte-by-byte hash table iteration: iterating the hash table one slot at a time with the
        // following branch (which is fairly unpredictable; only at most 60-70% of slots can be
        // matched. More specifically, not more than SEGMENT_MAX_NON_EMPTY_SLOTS -
        // SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES = 45 out of HASH_TABLE_SLOTS = 64. Also note
        // that because of displacingLoop wrapping around the hash table (or going ahead of the
        // current slotIndex) not all slots that are deleted at the beginning of this hash table
        // iteration might be matched) instead of reading controlGroups and dataGroups at a time and
        // having branchless iteration (like in Segment.removeIf() and compactEntriesDuringSplit())
        // because displacingLoop high bytes of controlsGroup and dataGroup may be modified in
        // displacingLoop. It is possible to check for that in
        // [Likely "different newQuadraticProbingChainGroupIndex" branch] and update controlsGroup
        // and dataGroup accordingly, but combined with other factors against
        // [Branchless hash table iteration] it makes less likely that
        // [Branchless hash table iteration] would be preferable in dropDeletes().
        // TODO compare the approaches, separately from Segment.removeIf() and
        //  compactEntriesDuringSplit()
        //
        // [Int-indexed loop to avoid a safepoint poll]
        for (int slotIndex = 0; slotIndex < HASH_TABLE_SLOTS; slotIndex++) {
            // Visiting only deleted slots: full entries (which may appear as the result of wrap
            // around and forward replacement in displacingLoop) are skipped.
            boolean shouldVisitEntry =
                    readControlByte(segment, (long) slotIndex) == DELETED_CONTROL;
            if (!shouldVisitEntry) {
                continue;
            }
            int dataByteToDisplace = readData(segment, (long) slotIndex);

            byte controlByteToWriteAtSlotIndex;

            // This is an entry displacing loop in the spirit of Cuckoo or Robin Hood hashing.
            //
            // There is no equivalent to this loop in raw_hash_set.h, where the logic of this
            // loop is fused with the outer loop by stepping back in the iteration:
            // github.com/abseil/abseil-cpp/blob/256be563447a315f2a7993ec669460ba475fa86a/
            // absl/container/internal/raw_hash_set.h#L1561. However, an inner loop which is a
            // close equivalent to this loop is present in hashbrown:
            // github.com/Amanieu/hashbrown/blob/00ba2f911577af79460be2d85ad2474428eda4bc/
            // src/raw/mod.rs#L637
            //
            // The separate displacing loop allows avoiding repetition of a few operations in the
            // beginning of the outer byte-by-byte loop: `boolean shouldVisitEntry = ...`
            // and `if (!shouldVisitEntry)` which are unnecessary on the second and later iterations
            // of the displacing loop. If [Branchless hash table iteration] was used instead of
            // [Byte-by-byte hash table iteration] then the separate displacing loop would be
            // mandatory.
            //
            // Effective variable of the displacing loop is dataByteToDisplace rather than
            // isFirstDisplacingLoopIteration, but the former is controlled fully manually inside
            // the loop.
            displacingLoop:
            for (boolean isFirstDisplacingLoopIteration = true;
                // There is no condition in this loop. We either break from it (see
                // `break displacingLoop`) or continue explicitly (see
                // `continue displacingLoop`)
                    ;
                 isFirstDisplacingLoopIteration = false
            ) {
                int allocIndex = allocIndex(dataByteToDisplace);
                K key = readKey(segment, allocIndex);
                long hash = keyHashCode(key);

                int slotIndexBase = slotIndex(hash);
                // slotIndex is used at the second and later iterations of displacingLoop because
                // before proceeding to these iterations
                // [Virtual swap of data slot at slotIndex and newSlotIndex] is done.
                int quadraticProbingChainGroupIndex =
                        // [Replacing division with shift]
                        (slotIndex - slotIndexBase) >>> GROUP_SLOTS_DIVISION_SHIFT;
                byte restoredFullControlByte = (byte) hashControlBits(hash);

                // Non-zero quadraticProbingChainGroupIndex:
                // If quadraticProbingChainGroupIndex == 0 then the condition of
                // [Likely "different newQuadraticProbingChainGroupIndex" branch] below is
                // guaranteed to be false because quadraticProbingChainGroupIndex = 0 is the first
                // checked word in the [Quadratic probing] chain, and
                // newQuadraticProbingChainGroupIndex should point to the same or an earlier word in
                // the [Quadratic probing] chain. This branch should be rarely taken with the
                // maximum load factor of
                // SEGMENT_MAX_NON_EMPTY_SLOTS / HASH_TABLE_SLOTS ~= 0.78 and therefore CPU
                // should predict this branch well. This branch doesn't follow the
                // [Positive likely branch] principle to enable fall-through logic below.
                // TODO evaluate exactly the probability of this branch
                if (quadraticProbingChainGroupIndex != 0) { // Unlikely branch
                    // [Find first non-full slot]
                    int newSlotIndex;
                    long searchControlsGroup;
                    int trailingZeros;
                    {
                        int groupFirstSlotIndex = slotIndexBase;
                        // TODO [Unbounded search loop]
                        for (int slotIndexStep = 0; ; ) {
                            searchControlsGroup =
                                    readControlsGroup(segment, (long) groupFirstSlotIndex);
                            long emptyOrDeletedBitMask = matchEmptyOrDeleted(searchControlsGroup);
                            // [Positive likely branch]
                            if (emptyOrDeletedBitMask != 0) {
                                // [Inlined lowestMatchIndex]
                                trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                                newSlotIndex = lowestMatchIndexFromTrailingZeros(
                                        groupFirstSlotIndex, trailingZeros);
                                break;
                            }
                            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                        }
                    }
                    int newQuadraticProbingChainGroupIndex =
                            // [Replacing division with shift]
                            (newSlotIndex - slotIndexBase) >>> GROUP_SLOTS_DIVISION_SHIFT;
                    // Likely "different newQuadraticProbingChainGroupIndex" branch:
                    // quadraticProbingChainGroupIndex is greater than zero but
                    // newQuadraticProbingChainGroupIndex is still expected to be zero, hence
                    // it's likely that this branch is taken. Note: counterintuitively,
                    // newQuadraticProbingChainGroupIndex can be greater than
                    // quadraticProbingChainGroupIndex and it means the same thing as if it was
                    // smaller, because quadratic probing chain group indexes are non-monotonic
                    // (modulo HASH_TABLE_GROUPS).
                    //
                    // We have to compare probing chain group indexes modulo HASH_TABLE_GROUPS
                    // because canonicalization of them via (& SLOT_MASK) is avoided when
                    // quadraticProbingChainGroupIndex is computed to reduce the number of
                    // operations to be performed outside of unlikely
                    // [Non-zero quadraticProbingChainGroupIndex] branch.
                    // [Positive likely branch]
                    if (!groupIndexesEqualModuloHashTableGroups(
                            newQuadraticProbingChainGroupIndex, quadraticProbingChainGroupIndex)) {
                        // [Conditional control byte cloning]
                        writeControlByteWithConditionalCloning(
                                segment, (long) newSlotIndex, restoredFullControlByte);
                        boolean newSlotWasEmpty =
                                isMatchingEmpty(searchControlsGroup, trailingZeros);
                        if (newSlotWasEmpty) {
                            // Finish displacingLoop.
                            writeData(segment, (long) newSlotIndex, (byte) dataByteToDisplace);

                            // Empty the slot at slotIndex.
                            controlByteToWriteAtSlotIndex = EMPTY_CONTROL;
                            // Not calling writeData() for slotIndex, i. e. leaving "garbage" in
                            // that data slot.

                            break displacingLoop;
                        } else {
                            // Virtual swap of data slot at slotIndex and newSlotIndex:
                            // virtually, we should swap data bytes between slots at slotIndex
                            // and newSlotIndex, but we delay writing the data from newSlotIndex
                            // to slotIndex until
                            // [Delayed write of a swapped data byte at slotIndex]. There are
                            // two reasons why this should be beneficial: 1) the data is just
                            // a byte, so it may be stored in a register (although it's not
                            // guaranteed because there are so many variables in play in this
                            // method so that there are likely not enough registers for each of
                            // them); 2) even if dataByteToDisplace is stored on a stack,
                            // writing to a local on the stack should be cheaper than writing to
                            // managed memory when there is a GC with expensive write barriers.
                            // Compare with hashbrown (in Rust), which 1) doesn't know the size
                            // of the swapped data, and the size is likely to not fit a
                            // register, and 2) doesn't have write barriers.
                            dataByteToDisplace = replaceData(
                                    segment, (long) newSlotIndex, (byte) dataByteToDisplace);
                            continue displacingLoop;
                        }
                    }
                    // Quadratic probing chain group indexes are equal: if
                    // newQuadraticProbingChainGroupIndex is equal to
                    // quadraticProbingChainGroupIndex then fall through to
                    // [Restore full control byte at slotIndex]. Note that at non-first iteration
                    // of displacingLoop it may appear like a full cycle around the hash table has
                    // been completed, and the quadraticProbingChainGroupIndex value (modulo
                    // HASH_TABLE_GROUPS) is closer to the beginning of the chain (0) than it used
                    // to be on the first or any other previous iteration of displacingLoop. This
                    // may feel "wrong" but it doesn't seem that it breaks any of the invariants of
                    // the hash table.
                }
                // Restore full control byte at slotIndex:
                controlByteToWriteAtSlotIndex = restoredFullControlByte;
                // This branch is unlikely because it could only be taken if
                // [Non-zero quadraticProbingChainGroupIndex] branch was taken which is unlikely.
                if (!isFirstDisplacingLoopIteration) { // Unlikely branch
                    // Delayed write of a swapped data byte at slotIndex:
                    writeData(segment, (long) slotIndex, (byte) dataByteToDisplace);
                }
                break displacingLoop;
            } // end of displacingLoop

            // Conditional control byte cloning: Unlike random entry writes in insert() or
            // removeAtSlot() methods, whether it's needed to clone a control byte during
            // dropDeletes() should usually change monotonically: yes for the first few
            // iterations (GROUP_SLOTS *
            // (SEGMENT_MAX_NON_EMPTY_SLOTS - SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES) /
            // HASH_TABLE_SLOTS ~= 5..6 on average, usually, that is, not considering exotic
            // cases like [Dropping deleted slots from a non-full-capacity segment]), no for the
            // rest of the iterations (SEGMENT_MAX_NON_EMPTY_SLOTS -
            // SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES - 5..6 ~= 40 on average). Hopefully,
            // branch predictors in modern CPUs  can pick up this pattern, so that on average
            // there should be little (1-2) mispredicted branches (TODO verify) in
            // writeControlByteWithConditionalCloning() over the course of dropDeletes()
            // compared to what would there be (6) if the branch taking was random. This should
            // justify using conditional control byte cloning over branchless approach as in
            // writeControlByte() (that has its own drawbacks, see a comment inside that
            // method).
            writeControlByteWithConditionalCloning(
                    segment, (long) slotIndex, controlByteToWriteAtSlotIndex);
        }
    }

    //endregion

    //region removeAtSlot() and shrinking methods called from it

    /**
     * Returns the updated bitSetAndState, but doesn't update {@link Segment#bitSetAndState} field.
     * Callers should call {@link Segment#setBitSetAndState} themselves with the value returned from
     * this method.
     */
    private long removeAtSlotNoShrink(
            long bitSetAndState, Object segment, int slotIndex, int allocIndex) {
        size--;
        modCount++;

        // Clearing the alloc bit before updating the deleted slot count probably makes data
        // dependency chains shorter than if these updates were applied in the opposite order.
        bitSetAndState = clearAllocBit(bitSetAndState, allocIndex);

        int prevGroupFirstSlotIndex = addSlotIndex(slotIndex, -GROUP_SLOTS);
        long emptyBitMaskAfter = matchEmpty(readControlsGroup(segment, (long) slotIndex));
        long emptyBitMaskBefore =
                matchEmpty(readControlsGroup(segment, (long) prevGroupFirstSlotIndex));
        // We count how many consecutive non empties we have to the right and to the left of
        // slotIndex. If the sum is greater or equal to GROUP_SLOTS then there is at least one probe
        // window that might have seen a full group.
        //
        // Note: if linear probing is used instead of [Quadratic probing], the last condition could
        // be changed to `> GROUP_BITS` instead of `>= GROUP_BITS`.
        //
        // TODO check: is it worthwhile to have the two == 0 checks upfront or they could be dropped
        boolean needToInsertDeleted = (emptyBitMaskBefore == 0) || (emptyBitMaskAfter == 0) ||
                (Long.numberOfTrailingZeros(emptyBitMaskAfter) +
                        Long.numberOfLeadingZeros(emptyBitMaskBefore)) >= GROUP_BITS;
        // Enables branchless operations.
        // TODO check if the conversion from boolean to int here is branchless itself, on the
        //  assembly level
        int insertDeleted = needToInsertDeleted ? 1 : 0;
        writeControlByte(segment, (long) slotIndex, (byte) branchlessDeletedOrEmpty(insertDeleted));
        bitSetAndState += DELETED_SLOT_COUNT_UNIT * (long) insertDeleted;

        eraseKeyAndValue(segment, allocIndex);
        return bitSetAndState;
    }

    @HotPath
    private void removeAtSlot(long hash, Object segment, int slotIndex, int allocIndex) {
        long bitSetAndState = getBitSetAndState(segment);
        bitSetAndState = removeAtSlotNoShrink(bitSetAndState, segment, slotIndex, allocIndex);
        setBitSetAndState(segment, bitSetAndState);
        /* if Flag|Always doShrink */
        /* if Flag doShrink */if (doShrink) {/* endif */
            tryShrink1(hash, segment, bitSetAndState);
        /* if Flag doShrink */}/* endif */
        /* endif */
    }

    /* if Flag|Always doShrink */
    /**
     * tryShrink1() makes one guard check and calls {@link #tryShrink2}. This is not just a part of
     * the if block in {@link #removeAtSlot}, because if {@link #doShrink} is false (that is the
     * default), tryShrink1() is never called and not compiled, that makes {@link #removeAtSlot}'s
     * bytecode and compiled code size smaller, that is better for JIT, makes more likely that
     * {@link #removeAtSlot} is inlined itself, and better for instruction cache.
     */
    @HotPath
    private void tryShrink1(long hash, Object segment, long bitSetAndState) {
        int segmentSize = segmentSize(bitSetAndState);
        // [Positive likely branch]
        if (segmentSize >
                // Warning: this formula is valid only as long as both SEGMENT_MAX_ALLOC_CAPACITY
                // and MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING are even.
                (SEGMENT_MAX_ALLOC_CAPACITY - MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING) / 2) {
            return;
        } else {
            // This branch is taken when segmentSize <=
            // (SEGMENT_MAX_ALLOC_CAPACITY - MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING) / 2 = 22.
            // When this condition is met symmetrically both sibling segments it's guaranteed they
            // can shrink into one of them with capacity SEGMENT_MAX_ALLOC_CAPACITY with
            // MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING = 4 extra leftover capacity after
            // shrinking (see the tryShrink2[1] condition).

            // Assumption that one of the siblings has the capacity of SEGMENT_MAX_ALLOC_CAPACITY:
            // In a pair of two sibling segments at least one should have the capacity of
            // SEGMENT_MAX_ALLOC_CAPACITY. There are two exceptions when this may not be the case:
            //  (1) splitInflated() may produce two intermediate-sized segments. This should be very
            //  rare because inflated segments themselves should be rare.
            //  (2) After shrinkAndTrimToSize(), the sibling segments can be arbitrarily sized. This
            //  is not a target case for optimization because after shrinkAndTrimToSize()
            //  SmoothieMap is assumed to be used as an immutable map, which is the point of
            //  shrinkAndTrimToSize().
            // Correctness is preserved in both of these cases in tryShrink2[1] condition. It's just
            // that tryShrink2() would be entered unnecessarily and relatively expensive
            // computations (including reading from the sibling segment) is done until tryShrink2[1]
            // condition. But that's OK because it is either happen rarely (1) or on unconventional
            // use of SmoothieMap (2). The alternative to comparing segmentSize with a constant
            // in tryShrink1() that could probably cover the cases (1) and (2) is extracting
            // segment's alloc capacity from bitSetAndState and comparing segmentSize with
            // (allocCapacity - MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING) / 2, or something like
            // that. However, this approach has its own complications (we cannot simply apply the
            // formula above to intermediate alloc capacity, that would lead to inadequately low
            // threshold for entering tryShrink2()) and is more computationally expensive. So it's
            // not worthwhile.

            // When the current segment is the sole segment in the SmoothieMap, we may enter
            // tryShrink2() needlessly which is a deliberate choice: see
            // [Not shrinking the sole segment].

            // The probability of taking this branch is low, so tryShrink2() should not be inlined.
            tryShrink2(segment, bitSetAndState, hash);
        }
    }

    /**
     * SegmentOne/SegmentTwo naming: in this method, "segmentOne" and "segmentTwo" are being shrunk.
     * This unusual naming is chosen instead of calling segments "first" and "second" to avoid a
     * confusing perception of first/second segments, as determined by which one is associated with
     * lower ranges of hash codes, and the concept of "first segment index" which is the smallest
     * index in {@link #segmentsArray} some segment is stored at.
     */
    @AmortizedPerSegment
    private void tryShrink2(Object segmentOne, long segmentOne_bitSetAndState, long hash) {
        int segmentOneOrder = segmentOrder(segmentOne_bitSetAndState);
        // Guard in a non-HotPath method: the following branch is unlikely, but it is made
        // positive to reduce nesting in the rest of the method, contrary to the
        // [Positive likely branch] principle, since performance is not that critically important in
        // tryShrink2(), because it is called only @AmortizedPerSegment, not @HotPath.
        if (segmentOneOrder == 0) { // Unlikely branch
            if (segmentOne_bitSetAndState != BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) {
                // Not shrinking the sole segment: if the segment has the order 0, it's the sole
                // segment in the SmoothieMap, so it doesn't have a sibling to be merged with.
                // Making this check in tryShrink2() is better for the case when it is false (that
                // is, there are more than one segment in a SmoothieMap), because the hot
                // tryShrink1() method therefore contains less code, and worse when this check is
                // actually true, i. e. there is just one segment in a SmoothieMap, because
                // tryShrink2() could then potentially be called frequently (unless inlined into
                // tryShrink2()). It is chosen to favor the first case, because SmoothieMap's target
                // optimization case is when it has more than one segment.
                return;
            } else {
                // This segment is already being shrunk from a racing thread.
                throw new ConcurrentModificationException();
            }
        }

        int modCount = getModCountOpaque();
        // Segment index re-computation: it's possible to pass segmentIndex downstream from
        // SmoothieMap's methods instead of recomputing it here, but it doesn't probably worth that
        // to store an extra value on the stack, because tryShrink2() is called rarely even if
        // shrinking is enabled.
        int firstSegmentOneIndex = firstSegmentIndexByHashAndOrder(hash, segmentOneOrder);
        int firstSegmentTwoIndex = siblingSegmentIndex(firstSegmentOneIndex, segmentOneOrder);
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowCme();
        // [Not avoiding normal array access]
        Object segmentTwo = segmentsArray[firstSegmentTwoIndex];
        long segmentTwo_bitSetAndState = getBitSetAndState(segmentTwo);
        int segmentTwoOrder = segmentOrder(segmentTwo_bitSetAndState);
        if (segmentTwoOrder == segmentOneOrder) { // [Positive likely branch]
            int segmentOneSize = segmentSize(segmentOne_bitSetAndState);
            int segmentTwoSize = segmentSize(segmentTwo_bitSetAndState);
            int sizeAfterShrinking = segmentOneSize + segmentTwoSize;
            int segmentOne_allocCapacity = allocCapacity(segmentOne_bitSetAndState);
            int segmentTwo_allocCapacity = allocCapacity(segmentTwo_bitSetAndState);
            int maxAllocCapacity = max(segmentOne_allocCapacity, segmentTwo_allocCapacity);
            // This branch is not guaranteed to be taken by the condition in tryShrink1() because
            // tryShrink1() concerns only a single sibling segment. Upon entering tryShrink2() it
            // may discover that the sibling is still too large for shrinking. Another reason why
            // this condition might not be taken is that the
            // [Assumption that one of the siblings has the capacity of SEGMENT_MAX_ALLOC_CAPACITY]
            // is false.
            if (sizeAfterShrinking + MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING <=
                    maxAllocCapacity) { // (1)
                int fromSegment_firstIndex;
                Object fromSegment;
                long fromSegment_bitSetAndState;
                int fromSegmentSize;
                Object intoSegment;
                long intoSegment_bitSetAndState;
                // Unless entries are removed in an ad hoc order, tryShrink2() should appear to be
                // called while performing a removal from the lower-index or the higher-index (0 or
                // 1 in (order - 1)th bit) segment randomly. Therefore always shrinking segments of
                // the same alloc capacity into `segmentOne` (rather than randomizing
                // segmentOne/segmentTwo choice when segmentOne_allocCapacity ==
                // segmentTwo_allocCapacity) shouldn't cause any skew.
                if (segmentOne_allocCapacity < segmentTwo_allocCapacity) {
                    fromSegment_firstIndex = firstSegmentOneIndex;
                    fromSegment = segmentOne;
                    fromSegment_bitSetAndState = segmentOne_bitSetAndState;
                    fromSegmentSize = segmentOneSize;
                    intoSegment = segmentTwo;
                    intoSegment_bitSetAndState = segmentTwo_bitSetAndState;
                } else {
                    fromSegment_firstIndex = firstSegmentTwoIndex;
                    fromSegment = segmentTwo;
                    fromSegment_bitSetAndState = segmentTwo_bitSetAndState;
                    fromSegmentSize = segmentTwoSize;
                    intoSegment = segmentOne;
                    intoSegment_bitSetAndState = segmentOne_bitSetAndState;
                }
                // The bitSetAndState is reset back to an operational value in the epilogue of the
                // doShrinkInto() method.
                setBitSetAndState(intoSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);
                replaceInSegmentsArray(
                        segmentsArray, fromSegment_firstIndex, segmentOneOrder, intoSegment);
                // Matches the modCount field increment performed in replaceInSegmentsArray().
                modCount++;
                doShrinkInto(fromSegment, fromSegment_bitSetAndState, intoSegment,
                        intoSegment_bitSetAndState);
                // fromSegmentSize is the number of modCount increments that should have been done
                // in doShrinkInto().
                modCount += fromSegmentSize;

                // Check the modCount after both bulky operations performed above:
                // replaceInSegmentsArray() and doShrinkInto().
                checkModCountOrThrowCme(modCount);

                /* if Tracking segmentOrderStats */
                subtractFromSegmentCountWithOrder(segmentOneOrder, 2);
                addToSegmentCountWithOrder(segmentOneOrder - 1, 1);
                /* endif */
            } else {
                // sizeAfterShrinking is not small enough yet.
                //noinspection UnnecessaryReturnStatement
                return;
            }
        } else if (segmentTwoOrder > segmentOneOrder) {
            // The ranges of hash codes sibling to the ranges associated with segmentOne are
            // associated with multiple segments, i. e. they are "split deeper" than segmentOne.
            // Those multiple segments should be shrunk themselves first before it's possible to
            // merge them with segmentOne.
            //noinspection UnnecessaryReturnStatement
            return;
        } else {
            // If the order of segmentTwo is observed to be lower than the order of segmentOne,
            // it should be already shrinking in a racing thread. During shrinking, intoSegment's
            // bitSetAndState is set to BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE that includes
            // segment order of 0, i. e. less than segmentOne's order. When shrinking is complete,
            // intoSegment's order is decremented, making it less than segmentOne's order too.
            throw new ConcurrentModificationException();
        }
    }

    /**
     * fromSegment's bitSetAndState is updated to fully valid value inside this method, including
     * decrementing the segment order.
     */
    private void doShrinkInto(Object fromSegment, final long fromSegment_bitSetAndState,
            Object intoSegment, long intoSegment_initialBitSetAndState) {
        int intoSegment_initialSize = segmentSize(intoSegment_initialBitSetAndState);
        int intoSegment_initialNonEmptySlots =
                intoSegment_initialSize + deletedSlotCount(intoSegment_initialBitSetAndState);
        int intoSegment_remainingEmptySlotsQuota =
                SEGMENT_MAX_NON_EMPTY_SLOTS - intoSegment_initialNonEmptySlots;
        if (intoSegment_remainingEmptySlotsQuota < 0) {
            // Might be due to delete slot count overflow or underflow.
            throw new ConcurrentModificationException();
        }
        long intoSegment_modifiedBitSetAndState = intoSegment_initialBitSetAndState;

        long fromSegment_bitSet = extractBitSetForIteration(fromSegment_bitSetAndState);
        // Branchless entries iteration: another option is checking every bit of the bitSet,
        // avoiding a relatively expensive call to numberOfLeadingZeros() and extra arithmetic
        // operations. But in doShrinkInto() there are expected to be many empty alloc indexes
        // (since we are able to shrink two segments into one) that would make a branch with a bit
        // checking unpredictable. This is a tradeoff similar to [Branchless hash table iteration].
        // TODO compare the approaches
        // Backward entries iteration: should be a little cheaper than forward iteration because
        // the loop condition compares iterAllocIndex with 0 (which compiles into less machine μops
        // when a flag is checked just after a subtraction) and because it uses numberOfLeadingZeros
        // rather than numberOfTrailingZeros: on AMD chips at least up to Ryzen, LZCNT is cheaper
        // than TZCNT, see https://www.agner.org/optimize/instruction_tables.pdf.
        // [Int-indexed loop to avoid a safepoint poll]. A safepoint poll might be still inserted by
        // some JVMs because this is not a conventional counted loop.
        for (int iterAllocIndexStep = Long.numberOfLeadingZeros(fromSegment_bitSet) + 1,
             iterAllocIndex = Long.SIZE;
             (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

            K key = readKey(fromSegment, iterAllocIndex);
            V value = readValue(fromSegment, iterAllocIndex);

            // TODO check what is better - these two statements before or after
            //  the internal put operation, or one before and one after, or both after?
            fromSegment_bitSet = fromSegment_bitSet << iterAllocIndexStep;
            iterAllocIndexStep = Long.numberOfLeadingZeros(fromSegment_bitSet) + 1;

            // ### Internal put:
            long hash = keyHashCode(key);
            int groupFirstSlotIndex = slotIndex(hash);
            internalPutLoop:
            for (int slotIndexStep = 0; ;) {
                long controlsGroup = readControlsGroup(intoSegment, (long) groupFirstSlotIndex);
                long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                if (emptyOrDeletedBitMask != 0) { // [Positive likely branch]
                    // [Inlined lowestMatchIndex]
                    int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                    int insertionSlotIndex =
                            lowestMatchIndexFromTrailingZeros(groupFirstSlotIndex, trailingZeros);
                    // [boolean as int]
                    int replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                    int allocIndex = lowestFreeAllocIndex(intoSegment_modifiedBitSetAndState);
                    // No need to check that allocIndex is less than segment's alloc
                    // capacity because it's guaranteed that there is enough capacity in
                    // tryShrink2[1] and the bitSetAndState field isn't re-read in between.
                    intoSegment_remainingEmptySlotsQuota -= replacingEmptySlot;
                    if (intoSegment_remainingEmptySlotsQuota >= 0) {//[Positive likely branch]
                        intoSegment_modifiedBitSetAndState =
                                setLowestAllocBit(intoSegment_modifiedBitSetAndState);
                        writeEntry(intoSegment, key, hash, value, insertionSlotIndex, allocIndex);
                        modCount++;
                    } else {
                        // TODO dropDeletes
                        throw new UnsupportedOperationException("TODO");
                    }
                    //noinspection UnnecessaryLabelOnBreakStatement
                    break internalPutLoop;
                }
                slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                if (slotIndexStep < HASH_TABLE_SLOTS) {
                    groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                } else {
                    // Hash table overflow is possible if there are put operations
                    // concurrent with this doShrinkInto() operation, or there is a racing
                    // doShrinkInto() operation.
                    throw new ConcurrentModificationException();
                }
            }
            // End of internal put
        }

        // ### Update intoSegment's bitSetAndState.
        int intoSegment_newNonEmptySlots =
                SEGMENT_MAX_NON_EMPTY_SLOTS - intoSegment_remainingEmptySlotsQuota;
        int intoSegment_newDeletedSlotCount =
                intoSegment_newNonEmptySlots - segmentSize(intoSegment_modifiedBitSetAndState);
        long intoSegment_newBitSetAndState = makeBitSetAndStateWithNewDeletedSlotCount(
                intoSegment_modifiedBitSetAndState, intoSegment_newDeletedSlotCount);
        // Decrement intoSegment's order.
        intoSegment_newBitSetAndState -= SEGMENT_ORDER_UNIT;
        setBitSetAndStateAfterBulkOperation(intoSegment, intoSegment_newBitSetAndState);
    }
    /* endif */ //comment*/ end if Flag|Always doShrink //*/

    //endregion

    //region Methods that replace inflated segments: deflate or split them

    /**
     * This method is called to deflate an inflated segment that became small and now an ordinary
     * segment can hold all it's entries.
     * @param hash a hash code of some entry belonging to the given segment. It allows to determine
     * the index(es) of the given segment in {@link #segmentsArray}.
     */
    private void deflateSmall(long hash, InflatedSegment<K, V> inflatedSegment) {
        int modCount = getModCountOpaque();

        // The inflated segment's bitSetAndState is never reset back to an operational value after
        // this statement.
        long inflatedSegmentBitSetAndState =
                replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(inflatedSegment);
        int segmentOrder = segmentOrder(inflatedSegmentBitSetAndState);
        int deflatedSegmentAllocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
        Segment<Object, Object> deflatedSegment =
                Segment.allocateNewSegmentAndSetEmptyControls(deflatedSegmentAllocCapacity);

        doDeflateSmall(
                segmentOrder, inflatedSegment, deflatedSegment, deflatedSegmentAllocCapacity);
        // storeFence() is called inside doDeflateSmall() to make publishing of deflatedSegment
        // safe.

        int firstSegmentIndex = firstSegmentIndexByHashAndOrder(hash, segmentOrder);
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(), firstSegmentIndex, segmentOrder,
                deflatedSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        checkModCountOrThrowCme(modCount);
    }

    private void replaceInflatedWithEmptyOrdinary(
            int segmentIndex, InflatedSegment<K, V> inflatedSegment) {
        // The inflated segment's bitSetAndState is never reset back to an operational value after
        // this statement.
        long inflatedSegmentBitSetAndState =
                replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(inflatedSegment);
        int segmentOrder = segmentOrder(inflatedSegmentBitSetAndState);
        int ordinarySegmentAllocCapacity = getInitialSegmentAllocCapacity(segmentOrder);
        Segment<Object, Object> ordinarySegment =
                Segment.createNewSegment(ordinarySegmentAllocCapacity, segmentOrder);
        int firstSegmentIndex = firstSegmentIndexByIndexAndOrder(segmentIndex, segmentOrder);
        // replaceInSegmentsArray() increments modCount.
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(),
                firstSegmentIndex, segmentOrder, ordinarySegment);
    }

    /**
     * Returns the number of entries moved from the inflated segment into an ordinary segment.
     *
     * intoSegmentAllocCapacity could have not been passed and {@link
     * Segment#SEGMENT_MAX_ALLOC_CAPACITY} used instead, but doing this to make the choice of
     * intoSegmentAllocCapacity the sole responsibility of {@link #deflateSmall}.
     */
    private void doDeflateSmall(int segmentOrder, InflatedSegment<K, V> inflatedSegment,
            Object intoSegment, int intoSegmentAllocCapacity) {
        int segmentSize = 0;
        for (Node<K, V> node : inflatedSegment.delegate.keySet()) {
            if (segmentSize >= intoSegmentAllocCapacity) {
                // This is possible if entries are added to the inflated segment concurrently with
                // the deflation.
                throw new ConcurrentModificationException();
            }
            K key = node.getKey();
            V value = node.getValue();
            // Possibly wrong hash from InflatedSegment's node: if there are concurrent
            // modifications, this might be a hash not corresponding to the read key. We are
            // tolerating that because it doesn't make sense to recompute keyHashCode(key) and
            // compare it with the stored hash, then it's easier just to use keyHashCode(key)
            // directly. Using a wrong hash might make the entry undiscoverable during subsequent
            // operations with the SmoothieMap (i. e. effectively could lead to a memory leak), but
            // nothing worse than that.
            long hash = node.hash;
            int insertionSlotIndex = findFirstEmptySlotForHash(intoSegment, hash);
            writeEntry(intoSegment, key, hash, value, insertionSlotIndex, segmentSize);
            segmentSize++;
            // Unlike in other similar procedures, don't increment modCount here because
            // intoSegment is not yet published to segmentsArray.
        }

        // Update intoSegment's bitSetAndState.
        long intoSegmentBitSetAndState = makeBitSetAndStateForPrivatelyPopulatedSegment(
                intoSegmentAllocCapacity, segmentOrder, segmentSize);
        setBitSetAndState(intoSegment, intoSegmentBitSetAndState);
        U.storeFence(); // [Safe segment publication]
    }

    /**
     * Splits an inflated segment whose order is not an outlier anymore.
     * @param hash a hash code of some entry belonging to the given segment. It allows to determine
     * indexes at which the given segment is stored in {@link #segmentsArray}.
     */
    private void splitInflated(long hash, InflatedSegment<K, V> inflatedSegment) {
        int modCount = getModCountOpaque();

        // The inflated segment's bitSetAndState is never reset back to an operational value after
        // this statement.
        long inflatedSegmentBitSetAndState =
                replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(inflatedSegment);
        int inflatedSegmentOrder = segmentOrder(inflatedSegmentBitSetAndState);
        int modCountIncrement = tryEnsureSegmentsArrayCapacityForSplit(inflatedSegmentOrder);
        if (modCountIncrement < 0) { // Unlikely branch
            // splitInflated() callers ensure that segmentsArray is not yet of
            // MAX_SEGMENTS_ARRAY_LENGTH by guarding a call to splitInflated() with
            // InflatedSegment.shouldBeSplit(). Ensuring segmentsArray's capacity might fail only
            // because it's already of MAX_SEGMENTS_ARRAY_LENGTH. So if this is the case, there
            // should be some concurrent modifications ongoing.
            throw new ConcurrentModificationException();
        }
        modCount += modCountIncrement;

        // ### Creating two result segments and replacing references in segmentsArray to the
        // ### inflated segment with references to the the result segments.
        int resultSegmentsOrder = inflatedSegmentOrder + 1;
        int resultSegmentsAllocCapacity = getInitialSegmentAllocCapacity(resultSegmentsOrder);
        // Publishing result segments before population in splitInflated: result segments are first
        // published to segmentsArray and then populated using the "public" put() procedure (see
        // doSplitInflated()) rather than populated privately as in doShrinkInto() and
        // doDeflateSmall() because the inflated segment which is being split in this method may
        // contain more than SEGMENT_MAX_ALLOC_CAPACITY entries, therefore one of the result
        // segments might need to be inflated too while the entries are moved from the inflated
        // segment. Or, if allocateIntermediateSegments is true, initially result segments have the
        // intermediate capacity and it's quite likely that they need to be replaced with
        // full-capacity segment(s) while the entries are moved from the inflated segment.
        //
        // Finally, if inflatedSegmentOrder is less than the current lastComputedAverageSegmentOrder
        // + MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE - 1 (which shouldn't normally happen, but
        // is possible if an artificial sequence of key insertions is constructed so that the
        // inflated segment is not accessed while the rest of the map has become more than four
        // times bigger) then the result segments can even be split during doSplitInflated().
        //
        // Accounting for these possibilities would make the private insertion procedure in this
        // method too complex, especially considering that methods handling inflated segments don't
        // need to be optimized very hard.

        // [SegmentOne/SegmentTwo naming]
        int firstIndexOfResultSegmentOne =
                firstSegmentIndexByHashAndOrder(hash, resultSegmentsOrder);
        Segment<K, V> resultSegmentOne =
                Segment.createNewSegment(resultSegmentsAllocCapacity, resultSegmentsOrder);
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowCme();
        replaceInSegmentsArray(
                segmentsArray, firstIndexOfResultSegmentOne, resultSegmentsOrder, resultSegmentOne);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        int firstIndexOfResultSegmentTwo =
                siblingSegmentIndex(firstIndexOfResultSegmentOne, resultSegmentsOrder);
        Segment<K, V> resultSegmentTwo =
                Segment.createNewSegment(resultSegmentsAllocCapacity, resultSegmentsOrder);
        replaceInSegmentsArray(
                segmentsArray, firstIndexOfResultSegmentTwo, resultSegmentsOrder, resultSegmentTwo);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        /* if Tracking segmentOrderStats */
        addToSegmentCountWithOrder(resultSegmentsOrder, 2);
        subtractFromSegmentCountWithOrder(inflatedSegmentOrder, 1);
        /* endif */

        // Checking modCount just after tryEnsureSegmentsArrayCapacityForSplit() and
        // replaceInSegmentsArray(), but before doSplitInflated() because it's very hard or
        // impossible to track the extra increments to modCount field if one (or both) of the result
        // segments are inflated, or grown in capacity, or split. See explanations in
        // [Publishing result segments before population in splitInflated] comment above.
        checkModCountOrThrowCme(modCount);

        doSplitInflated(inflatedSegment);
    }

    private void doSplitInflated(InflatedSegment<K, V> inflatedSegment) {
        int numMovedEntries = 0;
        for (Node<K, V> node : inflatedSegment.delegate.keySet()) {
            K key = node.getKey();
            V value = node.getValue();
            // [Possibly wrong hash from InflatedSegment's node]
            long hash = node.hash;
            // [Publishing result segments before population in splitInflated] explains why we are
            // using "public" put() method here. (1)
            if (put(segmentByHash(hash), key, hash, value, true /* onlyIfAbsent */) != null) {
                throw new ConcurrentModificationException();
            }
            numMovedEntries++;
        }
        // Restoring the correct size after calling put() with entries that are already in the map
        // in the loop above.
        size = size - (long) numMovedEntries;
    }

    // endregion

    //region shrinkAndTrimToSize() and moveToMapWithShrunkArray()

    private void shrinkAndTrimToSize() {
        throw new UnsupportedOperationException("TODO");
    }

    /* if Enabled moveToMapWithShrunkArray */
    private SmoothieMap<K, V> moveToMapWithShrunkArray() {
        throw new UnsupportedOperationException("TODO");
    }
    /* endif */

    //endregion

    //region Bulk operations

    /**
     * Extension rules make order of iteration of segments a little weird, avoiding visiting
     * the same segment twice
     *
     * <p>Given {@link #segmentLookupMask} = 0b1111, iterates segments in order:
     * 0000
     * 1000
     * 0100
     * 1100
     * 0010
     * .. etc, i. e. grows "from left to right"
     *
     * <p>In all lines above - +1 increment, that might be +2, +4 or higher, depending on how much
     * segment's tier is smaller than current map tier.
     *
     * Returns a negative value if there are no more segments in the SmoothieMap beyond the given
     * segment (following the [Negative integer return contract] principle).
     */
    private static int nextSegmentIndex(int segmentsArrayLength, int segmentsArrayOrder,
            int segmentIndex, Segment<?, ?> segment) {
        // Removes 32 - segmentsArrayOrder rightmost zeros from segmentIndex, so that after the
        // following Integer.reverse() call there are no always-zero lower bits and
        // numberOfArrayIndexesWithThisSegment can be added to segmentIndex directly without further
        // shifting. If segmentsArrayOrder equals to 0, this operation doesn't actually "remove all
        // 32 zeros", but rather doesn't change segmentIndex. This doesn't matter because
        // segmentIndex must be zero in this case anyway.
        segmentIndex <<= -segmentsArrayOrder;
        segmentIndex = Integer.reverse(segmentIndex);
        int segmentOrder = segmentOrder(getBitSetAndState(segment));
        assertThat(segmentOrder <= segmentsArrayOrder);
        int numberOfArrayIndexesWithThisSegment = 1 << (segmentsArrayOrder - segmentOrder);
        segmentIndex += numberOfArrayIndexesWithThisSegment;
        if (segmentIndex >= segmentsArrayLength)
            return -1;
        segmentIndex = Integer.reverse(segmentIndex);
        // Restores 32 - segmentsArrayOrder rightmost zeros in segmentIndex.
        segmentIndex >>>= -segmentsArrayOrder;
        return segmentIndex;
    }

    @Override
    public final int hashCode() {
        int h = 0;
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            h += segment.hashCode(this);
        }
        checkModCountOrThrowCme(modCount);
        return h;
    }

    @Override
    public final void forEach(BiConsumer<? super K, ? super V> action) {
        Utils.requireNonNull(action);
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            segment.forEach(action);
        }
        checkModCountOrThrowCme(modCount);
    }

    @Override
    public final boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
        Utils.requireNonNull(predicate);
        boolean interrupted = false;
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            if (!segment.forEachWhile(predicate)) {
                interrupted = true;
                break;
            }
        }
        checkModCountOrThrowCme(modCount);
        return !interrupted;
    }

    @Override
    public final void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Utils.requireNonNull(function);
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            segment.replaceAll(function);
        }
        checkModCountOrThrowCme(modCount);
    }

    @Override
    public final boolean containsValue(Object value) {
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        @SuppressWarnings("unchecked")
        V v = (V) value;
        boolean found = false;
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            if (segment.containsValue(this, v)) {
                found = true;
                break;
            }
        }
        checkModCountOrThrowCme(modCount);
        return found;
    }

    @Override
    public final SmoothieMap<K, V> clone() {
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
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
        // TODO review concurrency of this method
        Object[] resultSegmentsArray = segmentsArray.clone();
        result.segmentsArray = resultSegmentsArray;
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            int segmentOrder = segmentOrder(getBitSetAndState(segment));
            Segment<K, V> segmentClone = segment.clone();
            // TODO check if segmentIndex is really the first index as required by
            //  replaceInSegmentsArray()?
            result.replaceInSegmentsArray(
                    resultSegmentsArray, segmentIndex, segmentOrder, segmentClone);
        }
        checkModCountOrThrowCme(modCount);
        // Safe publication of result.
        U.storeFence();
        return result;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
        throw new UnsupportedOperationException("TODO");
    }

    /** To be called from {@link #writeObject}. */
    @SuppressWarnings("unused")
    private void writeAllEntries(ObjectOutputStream s) throws IOException {
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            segment.writeAllEntries(s);
        }
        checkModCountOrThrowCme(modCount);
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    // Not interested in this.put() results because putAll() returns void.
    @SuppressWarnings("CheckReturnValue")
    public final void putAll(Map<? extends K, ? extends V> m) {
        m.forEach(this::put);
    }

    @Override
    public final void clear() {
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            if (segment.clear(segmentIndex, this)) {
                modCount++;
            }
        }
        checkModCountOrThrowCme(modCount);
    }

    @Override
    public final boolean removeIf(BiPredicate<? super K, ? super V> filter) {
        Utils.requireNonNull(filter);
        if (isEmpty())
            return false;
        // Updating this variable in a loop rather than having a variable "initialModCount" outside
        // of the loop and returning `modCount != initialModCount` because it may result in a wrong
        // return if exactly 2^32 entries are removed from the SmoothieMap during this removeIf().
        boolean removedSomeEntries = false;

        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            int newModCount = segment.removeIf(this, filter, modCount);
            removedSomeEntries |= modCount != newModCount;
            modCount = newModCount;
        }
        checkModCountOrThrowCme(modCount);

        return removedSomeEntries;
    }

    final void aggregateStats(SmoothieMapStats stats) {
        stats.incrementAggregatedMaps();
        int modCount = getModCountOpaque();
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
        int segmentArrayLength = segmentsArray.length;
        int segmentsArrayOrder = order(segmentArrayLength);
        Segment<K, V> segment;
        for (int segmentIndex = 0; segmentIndex >= 0;
             segmentIndex = nextSegmentIndex(
                     segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
            segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
            stats.aggregateSegment(this, segment);
        }
        checkModCountOrThrowCme(modCount);
    }

    //endregion

    //region Collection views: keySet(), values(), entrySet()

    @Override
    public final Set<K> keySet() {
        @Nullable Set<K> ks = keySet;
        return ks != null ? ks : (keySet = new KeySet());
    }

    /** TODO don't extend AbstractSet */
    final class KeySet extends AbstractSet<K> {
        @Override
        public int size() {
            return SmoothieMap.this.size();
        }

        @Override
        public void clear() {
            SmoothieMap.this.clear();
        }

        @Override
        public Iterator<K> iterator() {
            return new ImmutableKeyIterator<>(SmoothieMap.this);
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object key) {
            return SmoothieMap.this.remove(key) != null;
        }

        @Override
        public final void forEach(Consumer<? super K> action) {
            Utils.requireNonNull(action);
            int modCount = getModCountOpaque();
            Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
            int segmentArrayLength = segmentsArray.length;
            int segmentsArrayOrder = order(segmentArrayLength);
            Segment<K, V> segment;
            for (int segmentIndex = 0; segmentIndex >= 0;
                 segmentIndex = nextSegmentIndex(
                         segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
                segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
                segment.forEachKey(action);
            }
            checkModCountOrThrowCme(modCount);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            if (size() > c.size() &&
                    // This condition ensures keyHashCode() is not overridden.
                    // Otherwise this optimization might make the removeAll() impl violating
                    // the contract, "remove all elements from this, containing in the given
                    // collection".
                    // TODO review with the new SmoothieMap extension model.
                    SmoothieMap.this.getClass() == SmoothieMap.class) {
                for (Iterator<?> it = c.iterator(); it.hasNext();) {
                    if (remove(it.next())) {
                        // Employing streaming method forEachRemaining() which may be optimized
                        // better than element-by-element explicit iteration.
                        it.forEachRemaining(key -> {
                            // We already know that we removed something from the map because of
                            // the if block outside, so not interested in remove() results here.
                            @SuppressWarnings({"CheckReturnValue", "unused"})
                            boolean removed = remove(key);
                        });
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
            Utils.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> !c.contains(k));
        }
    }

    @Override
    public final Collection<V> values() {
        @Nullable Collection<V> vs = values;
        return vs != null ? vs : (values = new Values());
    }

    /** TODO don't extend AbstractCollection */
    final class Values extends AbstractCollection<V> {
        @Override
        public int size() {
            return SmoothieMap.this.size();
        }

        @Override
        public void clear() {
            SmoothieMap.this.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ImmutableValueIterator<>(SmoothieMap.this);
        }

        @Override
        public boolean contains(Object o) {
            return containsValue(o);
        }

        @Override
        public void forEach(Consumer<? super V> action) {
            Utils.requireNonNull(action);
            int modCount = getModCountOpaque();
            Object[] segmentsArray = getNonNullSegmentsArrayOrThrowIse();
            int segmentArrayLength = segmentsArray.length;
            int segmentsArrayOrder = order(segmentArrayLength);
            Segment<K, V> segment;
            for (int segmentIndex = 0; segmentIndex >= 0;
                 segmentIndex = nextSegmentIndex(
                         segmentArrayLength, segmentsArrayOrder, segmentIndex, segment)) {
                segment = segmentByIndexDuringBulkOperations(segmentsArray, segmentIndex);
                segment.forEachValue(action);
            }
            checkModCountOrThrowCme(modCount);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Utils.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> c.contains(v));
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            Utils.requireNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> !c.contains(v));
        }
    }

    @Override
    public final Set<Entry<K, V>> entrySet() {
        @Nullable Set<Entry<K, V>> es = entrySet;
        return es != null ? es : (entrySet = new EntrySet());
    }

    /** TODO don't extend AbstractSet */
    final class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public int size() {
            return SmoothieMap.this.size();
        }

        @Override
        public void clear() {
            SmoothieMap.this.clear();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new ImmutableEntryIterator<>(SmoothieMap.this);
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

    //endregion

    //region Iterators

    abstract static class ImmutableSmoothieIterator<K, V, E> implements Iterator<E> {
        /**
         * ImmutableSmoothieIterator has this explicit field rather than just uses inner class
         * functionality to avoid unintended access to the SmoothieMap's state, for example, using
         * {@link SmoothieMap#modCount} instead of {@link #expectedModCount}.
         */
        private final SmoothieMap<K, V> smoothie;
        private final int expectedModCount;
        private final Object[] segmentsArray;
        private final int segmentsArrayOrder;
        Segment<K, V> currentSegment;
        @Nullable Iterator<Node<K, V>> inflatedSegmentIterator;

        /**
         * The next segment to be iterated is stored in {@link #segmentsArray} at this index if the
         * index is positive. A negative value means there are no more segments to iterate.
         */
        private int nextSegmentIndex;
        private @Nullable Segment<K, V> nextSegment;

        long bitSet;
        int iterAllocIndex;

        ImmutableSmoothieIterator(SmoothieMap<K, V> smoothie) {
            this.smoothie = smoothie;
            this.expectedModCount = smoothie.modCount;
            Object[] segmentsArray = smoothie.getNonNullSegmentsArrayOrThrowIse();
            this.segmentsArray = segmentsArray;
            this.segmentsArrayOrder = order(segmentsArray.length);
            Segment<K, V> firstSegment = segmentByIndexDuringBulkOperations(segmentsArray, 0);
            initCurrentSegment(0, firstSegment);
        }

        @EnsuresNonNull("currentSegment")
        private void initCurrentSegment(int currentSegmentIndex, Segment<K, V> currentSegment) {
            this.currentSegment = currentSegment;
            long currentSegment_bitSetAndState = getBitSetAndState(currentSegment);

            // findNextSegment() must be called before advancements
            // (advanceOrdinarySegmentIteration() and advanceInflatedSegmentIteration() calls below)
            // because nextSegmentIndex and nextSegment are assigned in findNextSegment() and are
            // used in advanceSegment() which is called from the advancement methods.
            findNextSegment(currentSegmentIndex, currentSegment);

            boolean currentSegmentIsOrdinary =
                    !isInflatedBitSetAndState(currentSegment_bitSetAndState);
            // [Positive likely branch]
            if (currentSegmentIsOrdinary) {
                long bitSet = extractBitSetForIteration(currentSegment_bitSetAndState);
                advanceOrdinarySegmentIteration(bitSet, Long.SIZE);
            } else {
                // Current segment is inflated:
                this.iterAllocIndex = -1;
                Iterator<Node<K, V>> inflatedSegmentIterator =
                        ((InflatedSegment<K, V>) currentSegment).delegate.keySet().iterator();
                this.inflatedSegmentIterator = inflatedSegmentIterator;
                advanceInflatedSegmentIteration(inflatedSegmentIterator);
            }
        }

        @AmortizedPerSegment
        private void findNextSegment(int currentSegmentIndex, Segment<K, V> currentSegment) {
            int nextSegmentIndex = nextSegmentIndex(
                    segmentsArray.length, segmentsArrayOrder, currentSegmentIndex, currentSegment);
            // Writing nextSegmentIndex to the field immediately because "not found" contract of
            // nextSegmentIndex() method (returning a negative value) corresponds to the "no more
            // segments" contract of the field.
            this.nextSegmentIndex = nextSegmentIndex;
            if (nextSegmentIndex >= 0) {
                nextSegment = segmentByIndexDuringBulkOperations(segmentsArray, nextSegmentIndex);
            } else {
                nextSegment = null;
            }
        }

        /**
         * @param prevAllocIndex the previously iterated index in this segment, or {@link Long#SIZE}
         * if the iteration of the ordinary segment is just starting.
         */
        @HotPath
        final void advanceOrdinarySegmentIteration(long bitSet, int prevAllocIndex) {
            if (bitSet != 0) { // [Positive likely branch]
                // Branchless entries iteration in iterators: using [Branchless entries iteration],
                // because segments' fullness may vary from approximately half full to full, 75%
                // full on average, so the approach with checking every bitSet's bit would still
                // have a fairly unpredictable branch. However, the expected branch profile would be
                // different from the expected profile in doShrinkInto(), and it's more likely that
                // checking every bitSet's bit is a better strategy in iterators than in
                // doShrinkInto().
                // TODO compare the approaches for iterators
                // [Backward entries iteration]
                int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;
                this.iterAllocIndex = prevAllocIndex - iterAllocIndexStep;
                this.bitSet = bitSet << iterAllocIndexStep;
            } else {
                // Logically, there should be `this.iterAllocIndex = -1;` statement here before calling
                // to advanceSegment(), but instead it is pushed to advanceSegment() and
                // initCurrentSegment() to avoid unnecessary writes: see a comment in
                // advanceSegment().
                advanceSegment();
            }
        }

        final void advanceInflatedSegmentIteration(
                Iterator<Node<K, V>> inflatedSegmentIterator) {
            if (inflatedSegmentIterator.hasNext()) { // [Positive likely branch]
                return;
            }
            // Cleaning up inflatedSegmentIterator proactively so that initCurrentSegment() doesn't
            // need to set this field to null each time an ordinary segment is encountered (that
            // is, almost every time, because almost all segments should be ordinary).
            this.inflatedSegmentIterator = null;
            advanceSegment();
        }

        @AmortizedPerSegment
        private void advanceSegment() {
            int nextSegmentIndex = this.nextSegmentIndex;
            if (nextSegmentIndex < 0) {
                // Logically, writing iterAllocIndex to -1 belongs to advanceOrdinarySegmentIteration(),
                // but instead doing it here and in initCurrentSegment(), in the
                // [Current segment is inflated] branch to avoid unnecessary field writes when an
                // ordinary segment is followed by another ordinary segment (almost every time) in
                // the expense of a single unnecessary field write (here) in the end of the
                // iteration if the last iterated segment was inflated (which should almost never
                // happen).
                this.iterAllocIndex = -1;
                // Don't advance, the iteration is complete. iterAllocIndex stores -1 and
                // inflatedSegmentIterator stores null at this point.
                return;
            }
            @Nullable Segment<K, V> nextSegment = this.nextSegment;
            /* if Enabled extraChecks */assertNonNull(nextSegment);/* endif */
            initCurrentSegment(nextSegmentIndex, nextSegment);
        }

        final void checkModCount() {
            if (expectedModCount != smoothie.modCount) {
                throw new ConcurrentModificationException();
            }
        }

        final Node<K, V> nextInflatedSegmentEntry(
                Iterator<Node<K, V>> inflatedSegmentIterator) {
            try {
                return inflatedSegmentIterator.next();
            } catch (NoSuchElementException e) {
                // advanceInflatedSegmentIteration() must ensure there is a next entry in the
                // iteration, if there is no entry there should be some concurrent modification of
                // the inflated segment happening.
                throw new ConcurrentModificationException(e);
            }
        }

        @Override
        public final boolean hasNext() {
            if (iterAllocIndex >= 0) { // [Positive likely branch]
                return true;
            }
            return inflatedSegmentIterator != null;
        }

        @Override
        public final void remove() {
            //noinspection SSBasedInspection
            throw new UnsupportedOperationException(
                    "remove() operation is not supported by this Iterator");
        }
    }

    static final class ImmutableKeyIterator<K, V> extends ImmutableSmoothieIterator<K, V, K> {
        ImmutableKeyIterator(SmoothieMap<K, V> smoothie) {
            super(smoothie);
        }

        @Override
        public K next() {
            checkModCount();
            int allocIndex = this.iterAllocIndex;
            if (allocIndex >= 0) { // [Positive likely branch]
                return nextKeyInOrdinarySegment(allocIndex);
            } else {
                return nextKeyInInflatedSegment();
            }
        }

        private K nextKeyInOrdinarySegment(int allocIndex) {
            K key = readKeyChecked(currentSegment, allocIndex);
            advanceOrdinarySegmentIteration(this.bitSet, allocIndex);
            return key;
        }

        private K nextKeyInInflatedSegment() {
            @Nullable Iterator<Node<K, V>> inflatedSegmentIterator =
                    this.inflatedSegmentIterator;
            if (inflatedSegmentIterator == null) {
                // NoSuchElementException or concurrent modification: this condition may also be
                // caused by a concurrent modification, if a concurrent call to Iterator.next()
                // advanced to an ordinary segment, updating iterAllocIndex to non-negative value
                // and setting inflatedSegmentIterator to null.
                throw new NoSuchElementException();
            }
            Node<K, V> inflatedSegmentNode = nextInflatedSegmentEntry(inflatedSegmentIterator);
            K key = inflatedSegmentNode.getKey();
            advanceInflatedSegmentIteration(inflatedSegmentIterator);
            return key;
        }
    }

    static final class ImmutableValueIterator<K, V> extends ImmutableSmoothieIterator<K, V, V> {
        ImmutableValueIterator(SmoothieMap<K, V> smoothie) {
            super(smoothie);
        }

        @Override
        public V next() {
            checkModCount();
            int allocIndex = this.iterAllocIndex;
            if (allocIndex >= 0) { // [Positive likely branch]
                return nextValueInOrdinarySegment(allocIndex);
            } else {
                return nextValueInInflatedSegment();
            }
        }

        private V nextValueInOrdinarySegment(int allocIndex) {
            V value = readValueChecked(currentSegment, allocIndex);
            advanceOrdinarySegmentIteration(this.bitSet, allocIndex);
            return value;
        }

        private V nextValueInInflatedSegment() {
            @Nullable Iterator<Node<K, V>> inflatedSegmentIterator =
                    this.inflatedSegmentIterator;
            if (inflatedSegmentIterator == null) {
                // [NoSuchElementException or concurrent modification]
                throw new NoSuchElementException();
            }
            Node<K, V> inflatedSegmentNode = nextInflatedSegmentEntry(inflatedSegmentIterator);
            V value = inflatedSegmentNode.getValue();
            advanceInflatedSegmentIteration(inflatedSegmentIterator);
            return value;
        }
    }

    static final class ImmutableEntryIterator<K, V>
            extends ImmutableSmoothieIterator<K, V, Entry<K, V>> {
        private final SimpleMutableEntry<K, V> entry;

        ImmutableEntryIterator(SmoothieMap<K, V> smoothie) {
            super(smoothie);
            entry = new SimpleMutableEntry<>(smoothie);
        }

        @Override
        public Entry<K, V> next() {
            checkModCount();
            int allocIndex = this.iterAllocIndex;
            if (allocIndex >= 0) { // [Positive likely branch]
                return nextEntryInOrdinarySegment(allocIndex);
            } else {
                return nextValueInInflatedSegment();
            }
        }

        private Entry<K, V> nextEntryInOrdinarySegment(int allocIndex) {
            Segment<K, V> currentSegment = this.currentSegment;
            checkAllocIndex(currentSegment, allocIndex);
            K key = readKey(currentSegment, allocIndex);
            V value = readValue(currentSegment, allocIndex);
            advanceOrdinarySegmentIteration(this.bitSet, allocIndex);
            return entry.withKeyAndValue(key, value);
        }

        private Entry<K, V> nextValueInInflatedSegment() {
            @Nullable Iterator<Node<K, V>> inflatedSegmentIterator =
                    this.inflatedSegmentIterator;
            if (inflatedSegmentIterator == null) {
                // [NoSuchElementException or concurrent modification]
                throw new NoSuchElementException();
            }
            Node<K, V> inflatedSegmentNode = nextInflatedSegmentEntry(inflatedSegmentIterator);
            K key = inflatedSegmentNode.getKey();
            V value = inflatedSegmentNode.getValue();
            advanceInflatedSegmentIteration(inflatedSegmentIterator);
            return entry.withKeyAndValue(key, value);
        }
    }

    @SuppressWarnings("unused") // To be used in non-Immutable iterators
    final class SmoothieEntry extends SimpleEntry<K, V> {
        final Object segment;
        final int allocIndex;
        final int mc = modCount;

        SmoothieEntry(Object segment, int allocIndex) {
            super(readKey(segment, allocIndex), readValue(segment, allocIndex));
            this.segment = segment;
            this.allocIndex = allocIndex;
        }

        @Override
        public V setValue(V value) {
            if (mc != modCount)
                throw new ConcurrentModificationException();
            writeValue(segment, allocIndex, value);
            return super.setValue(value);
        }
    }

    //endregion

    //region HashTableArea
    /**
     * HashTableArea contains an open-addressing hash table of 64 slots with linear probing, 1-byte
     * keys and values stored separately, i. e. 64 bytes of keys are contiguous (see {@link #c0}),
     * and then there are 64 contiguous bytes of values (see {@link #d0}).
     *
     * HashTableArea (together with {@link Segment}) reimplements SwissTable, specifically this
     * version: https://github.com/abseil/abseil-cpp/blob/3088e76c597e068479e82508b1770a7ad0c806b6/
     * absl/container/internal/raw_hash_set.h, with some improvements adopted from hashbrown,
     * specifically this version:
     * https://github.com/Amanieu/hashbrown/tree/6b9cc4e01090553c5928ccc0ee4568319ee0ed33/src.
     * Naming of variables and methods is kept close to raw_hash_set.h.
     *
     * HashTableArea is made a superclass of {@link Segment} to ensure that {@link Segment#k0} and
     * {@link Segment#v0} are laid before the primitive fields by the JVM and therefore are followed
     * directly by {@link Segment17#k1}, etc. This is guaranteed because JVMs don't mix fields
     * declared in superclasses and subclasses.
     *
     * All instance fields of HashTableArea are private and must not be accessed using normal
     * .fieldName Java syntax, because JVMs are free to lay the fields in an arbitrary order, while
     * we need a specific, stable layout. This layout corresponds to the order in which the fields
     * are declared. The data is accessed using {@link #CONTROLS_OFFSET} and {@link
     * #DATA_SLOTS_OFFSET}.
     */
    static class HashTableArea {
        /**
         * 64 1-byte control slots. The lower 7 bits of a used slot are taken from {@link
         * SmoothieMap}'s key hash code. The highest bit of 1 denotes a special control, either
         * {@link Segment#EMPTY_CONTROL} or {@link Segment#DELETED_CONTROL}.
         *
         * {@link Segment#INFLATED_SEGMENT_MARKER_CONTROL} value indicates that the segment is an
         * {@link InflatedSegment}.
         */
        @SuppressWarnings("unused")
        private long c0, c1, c2, c3, c4, c5, c6, c7;

        static final int HASH_TABLE_SLOTS = 64;
        static final int SLOT_MASK = HASH_TABLE_SLOTS - 1;
        static final int GROUP_SLOTS = Long.BYTES;
        /** [Replacing division with shift] */
        static final int GROUP_SLOTS_DIVISION_SHIFT = 3; // = numberOfTrailingZeros(GROUP_SLOTS)
        @IntVal(8)
        static final int HASH_TABLE_GROUPS = HASH_TABLE_SLOTS / GROUP_SLOTS;
        static final int GROUP_BITS = Long.SIZE;

        static {
            assertThat(Integer.numberOfTrailingZeros(GROUP_SLOTS) == GROUP_SLOTS_DIVISION_SHIFT);
            assertThat(HASH_TABLE_SLOTS == HASH_TABLE_GROUPS * GROUP_SLOTS);
        }

        /** The cloned {@link #c0} enables branchless operations. */
        @SuppressWarnings("unused")
        private long c0Clone;
        static final int CLONED_CONTROL_SLOTS = GROUP_SLOTS;

        /**
         * 1-byte data slots, corresponding to the control slots. The lower 6 bits of a used slot
         * contain an entry allocation index, e. g. 0 means that {@link SmoothieMap}'s entry is
         * stored in {@link Segment#k0} and {@link Segment#v0}. The highest 2 bits are taken from
         * {@link SmoothieMap}'s key hash code and are compared with the bits of the hash code of
         * the key being looked up to reduce the probability of calling equals() on different keys
         * by extra 75%.
         *
         * The tradeoff here is that it's possible to make HashTableArea 16 bytes smaller if only
         * 6-bit entry allocated indexes are stored. However, to read and write 6-bit slots an
         * offset and a shift should be computed using either integral division by 6 or a lookup
         * table, and extra shifting and bit masking is involved. Another approach is the layout of
         * 64 4-bit slots followed by 64 2-bit slots which doesn't require neither integral division
         * nor a lookup table, but on the other hand it requires two reads and two writes and even
         * more shifting and bit masking operations.
         *
         * In any case, 6-bit slot management is estimated to add at least 3-5 cycles to the latency
         * of reading operations with a Segment and double of that, 6-10 cycles for writing
         * operations (however I haven't actually tried to measure that. - leventov), not
         * considering the effect of the reduced probability of calling equals() on different keys,
         * described above, for 1-byte slots. On the other hand, 16 bytes that could have been saved
         * is about 1.7% (not compressed Oops) to 3.1% (compressed Oops) of the size of a Segment.
         *
         * Data slots that correspond to empty control slots are not required to have specific
         * values, in particular, zeros. They can have "garbage" values.
         *
         * TODO consider using
         *  https://en.wikipedia.org/wiki/Bit_Manipulation_Instruction_Sets#Parallel_bit_deposit_and_extract
         *  in native code
         *
         * TODO compare with interleaved controls group - data group layout
         */
        @SuppressWarnings("unused")
        private long d0, d1, d2, d3, d4, d5, d6, d7;
        static final int DATA_BYTES = 64;

        static final int TOTAL_HASH_TABLE_BYTES =
                HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS + DATA_BYTES;

        private static final boolean LITTLE_ENDIAN =
                ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        static final long CONTROLS_OFFSET;
        static final long CLONED_CONTROLS_OFFSET;
        /**
         * Pre-casted constant: since {@link #CONTROLS_OFFSET} is not a compile-time constant, it
         * might be better to use a pre-casted int constant rather than casting from long to int
         * each time at some usage sites.
         */
        static final int CONTROLS_OFFSET_AS_INT;
        private static final long DATA_SLOTS_OFFSET;

        static {
            CONTROLS_OFFSET = minInstanceFieldOffset(HashTableArea.class);
            CLONED_CONTROLS_OFFSET = CONTROLS_OFFSET + HASH_TABLE_SLOTS;
            CONTROLS_OFFSET_AS_INT = (int) CONTROLS_OFFSET;
            DATA_SLOTS_OFFSET = CONTROLS_OFFSET + HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS;
            assertThat((TOTAL_HASH_TABLE_BYTES % Long.BYTES) == 0);
        }

        static boolean groupIndexesEqualModuloHashTableGroups(int groupIndex1, int groupIndex2) {
            return ((groupIndex1 - groupIndex2) & (HASH_TABLE_GROUPS - 1)) == 0;
        }

        static void assertNonNullSegment(Object segment) {
            // TODO link to ticket in YouTrack when created
            // TODO inline when issue in IntelliJ is fixed
            //noinspection ResultOfMethodCallIgnored
            assertNonNull(segment);
        }

        static long readControlsGroup(Object segment, long firstSlotIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            long controlsGroup = U.getLong(segment, CONTROLS_OFFSET + firstSlotIndex);
            if (LITTLE_ENDIAN) {
                return controlsGroup;
            } else {
                return Long.reverseBytes(controlsGroup);
            }
        }

        static int readControlByte(Object segment, long slotIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            return (int) U.getByte(segment, CONTROLS_OFFSET + slotIndex);
        }

        static void writeControlByte(Object segment, long slotIndex, byte controlByte) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putByte(segment, CONTROLS_OFFSET + slotIndex, controlByte);
            // Sets the cloned control. For slot indexes between 8th and 63th, i. e. 87.5% of the
            // time this computation results in the same index as the original, and the same byte in
            // memory is set twice. The alternative is a conditional block as in
            // writeControlByteWithConditionalCloning(), but that is a branch.
            // TODO compare the approaches, importantly with Shenandoah GC that emits primitive
            //  write barriers
            long clonedSlotIndex = ((slotIndex - GROUP_SLOTS) & SLOT_MASK) + GROUP_SLOTS;
            U.putByte(segment, CONTROLS_OFFSET + clonedSlotIndex, controlByte);
        }

        static void writeControlByteWithConditionalCloning(Object segment, long slotIndex,
                byte controlByte) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putByte(segment, CONTROLS_OFFSET + slotIndex, controlByte);
            if (slotIndex < GROUP_SLOTS) {
                long clonedSlotIndex = slotIndex + HASH_TABLE_SLOTS;
                U.putByte(segment, CONTROLS_OFFSET + clonedSlotIndex, controlByte);
            }
        }

        static int readData(Object segment, long slotIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            // Another option is `DATA_SLOTS_OFFSET + 63 - slotIndex`, that makes more control byte
            // and data byte accesses to fall in a single cache line (and on the order hand, more
            // accesses are two cache lines apart, compared to `DATA_SLOTS_OFFSET + slotIndex`).
            // Warning: it would require to add a Long.reverseBytes() call in
            // readDataGroupByGroupIndex(). Other data-access methods also would need to be updated.
            // TODO compare the approaches
            return (int) U.getByte(segment, DATA_SLOTS_OFFSET + slotIndex);
        }

        static long readDataGroup(Object segment, long firstSlotIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            long dataGroup = U.getLong(segment, DATA_SLOTS_OFFSET + firstSlotIndex);
            if (LITTLE_ENDIAN) {
                return dataGroup;
            } else {
                return Long.reverseBytes(dataGroup);
            }
        }

        static void writeData(Object segment, long slotIndex, byte data) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putByte(segment, DATA_SLOTS_OFFSET + slotIndex, data);
        }

        static int replaceData(Object segment, long slotIndex, byte newData) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            long offset = DATA_SLOTS_OFFSET + slotIndex;
            int oldData = (int) U.getByte(segment, offset);
            U.putByte(segment, offset, newData);
            return oldData;
        }
    }
    //endregion

    static class BitSetAndStateArea extends HashTableArea {
        /**
         * The lower 48 bits of bitSetAndState is a bit set denoting free and occupied
         * allocation indexes.
         *
         * The next 5 bits (from 48th to 52nd) represent the segment order. See {@link
         * SmoothieMap#lastComputedAverageSegmentOrder} and {@link SmoothieMap#order} for more info
         * about this concept.
         *
         * The next 5 bits (from 53rd to 57th) are used to codify the allocation capacity of this
         * segment. The value of 0 in these bits (i. e, all bits from 59th to 63rd are zeros) is
         * assigned for {@link Segment17}, 1 - for {@link Segment18}, etc through 31 for {@link
         * Segment48}. This information is not extracted through a polymorphic method on {@link
         * Segment} because it would require to access object headers of Segments, and maybe even
         * virtual method table, i. e. would actually be much slower. See {@link Segment} javadoc
         * for more info about the approach to the method dispatch in this class.
         *
         * The highest 6 bits (from 58th to 63rd) represent the number of control slots with {@link
         * Segment#DELETED_CONTROL}, i. e. the number of deleted slots in the hash table. This value is
         * updated in a branchless manner using {@link #DELETED_SLOT_COUNT_UNIT}, and if the segment is
         * updated concurrently, the deleted slot count could inadvertently overflow or underflow. To
         * avoid illegal memory access, it's critical to ensure that no race could lead to corruption of
         * the previous value in bitSetAndState, {@link #allocCapacity}, that must not be modified
         * through the entire lifetime of a segment. That is why the deleted slot count value is placed
         * in the highest-order bits of bitSetAndState, so that no overflow or underflow of this value
         * could corrupt other values.
         *
         * All four values are packed into a single long rather than having their own fields in order to
         * save memory. The saving is 8 bytes if UseCompressedOops is false and
         * UseCompressedClassPointers is false, because ObjectAlignmentInBytes is at least 8 bytes, the
         * object header size is 16 bytes, all fields before bitSetAndState are longs, and the next
         * field {@link Segment#k0} requires 8-byte alignment too. In addition, storing four values in
         * a single long field allows to reduce the number of memory reads and writes required during
         * insert and delete operations, when several of those values are read and/or updated.
         */
        long bitSetAndState;

        private static final int BIT_SET_BITS = 48;
        static final int SEGMENT_MAX_ALLOC_CAPACITY = BIT_SET_BITS;
        /** {@link #SEGMENT_MAX_ALLOC_CAPACITY} = 48 = 16 * 3 */
        static final int MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE = 16;
        static final int MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT = 4;
        static final long BIT_SET_MASK = (1L << BIT_SET_BITS) - 1;

        static {
            if (Integer.numberOfTrailingZeros(MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE)
                    != MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT) {
                throw new AssertionError();
            }
        }

        /**
         * Free allocation slots correspond to set bits, and vice versa, occupied slots correspond
         * to clear bits in {@link #bitSetAndState}. This is done to avoid extra bitwise inversions
         * for the typical operation of finding the lowest free slot, because there are only {@link
         * Long#numberOfTrailingZeros(long)} and {@link Long#numberOfLeadingZeros(long)} methods in
         * {@code Long} class, no methods for leading/trailing "ones".
         */
        static final long EMPTY_BIT_SET = BIT_SET_MASK;

        private static final int SEGMENT_ORDER_SHIFT = BIT_SET_BITS;
        static final long SEGMENT_ORDER_UNIT = 1L << SEGMENT_ORDER_SHIFT;
        /**
         * 5 bits is enough to store values from 0 to {@link #MAX_SEGMENTS_ARRAY_ORDER} = 30.
         */
        private static final int SEGMENT_ORDER_BITS = 5;
        private static final int SEGMENT_ORDER_MASK = (1 << SEGMENT_ORDER_BITS) - 1;

        /**
         * There are not enough bits to store the alloc capacity directly, because it requires 6 bits
         * (numbers up to {@link #SEGMENT_MAX_ALLOC_CAPACITY}). Therefore it's not possible to create a segment
         * of any alloc capacity between 0 and {@link #SEGMENT_MAX_ALLOC_CAPACITY}. The minimum supported alloc
         * capacity is {@link #BASE_ALLOC_CAPACITY}
         */
        private static final int EXTRA_ALLOC_CAPACITY_SHIFT =
                SEGMENT_ORDER_SHIFT + SEGMENT_ORDER_BITS;
        private static final int EXTRA_ALLOC_CAPACITY_BITS = 5;
        private static final int EXTRA_ALLOC_CAPACITY_MASK = (1 << EXTRA_ALLOC_CAPACITY_BITS) - 1;
        private static final int MAX_EXTRA_ALLOC_CAPACITY = (1 << EXTRA_ALLOC_CAPACITY_BITS) - 1;
        /**
         * Equals to 17.
         * Warning: {@link #allocCapacityMinusOneHalved} relies on this value to be odd. {@link
         * Segment#tryShrink1} relies on this value to be greater than 2.
         */
        private static final int BASE_ALLOC_CAPACITY =
                SEGMENT_MAX_ALLOC_CAPACITY - MAX_EXTRA_ALLOC_CAPACITY;

        private static final int DELETED_SLOT_COUNT_SHIFT =
                EXTRA_ALLOC_CAPACITY_SHIFT + EXTRA_ALLOC_CAPACITY_BITS;
        static final long DELETED_SLOT_COUNT_UNIT = 1L << DELETED_SLOT_COUNT_SHIFT;
        private static final int DELETED_SLOT_COUNT_BITS = 6;
        private static final int DELETED_SLOT_COUNT_MASK = (1 << DELETED_SLOT_COUNT_BITS) - 1;
        private static final long NEGATIVE_DELETED_SLOT_COUNT_MASK =
                ~(((long) DELETED_SLOT_COUNT_MASK) << DELETED_SLOT_COUNT_SHIFT);

        /**
         * A special deleted slot count value, impossible for an ordinary segment, used for {@link
         * InflatedSegment} to make it possible to identify it before checking the class of a
         * segment object.
         *
         * It's important that the deleted slot count in an inflated segment's bitSetAndState is
         * different from {@link #BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE}'s deleted slot count
         * value that is {@link #DELETED_SLOT_COUNT_MASK}, so that it is possible to distinguish
         * between an inflated bitSetAndState with the segment order of 0 and a bulk operation
         * placeholder bitSetAndState.
         */
        private static final int INFLATED_SEGMENT_DELETED_SLOT_COUNT = DELETED_SLOT_COUNT_MASK - 1;

        /**
         * This value is assigned into {@link #bitSetAndState} in the beginning of bulk operations
         * (like {@link Segment#tryShrink2} and {@link #splitAndInsert})) so that concurrent
         * operations could catch this condition and throw {@link ConcurrentModificationException}
         * more likely.
         *
         * This value is comprised of a full bit set (see {@link #EMPTY_BIT_SET}), an extra alloc
         * capacity of 0, a segment order of 0, and a deleted slot count equal to {@link
         * #DELETED_SLOT_COUNT_MASK}. Any entry insertion or deletion operation triggers either
         * shrinking or growing if encounters this bitSetAndState, where it is identified that this
         * bitSetAndState is a placeholder, and then a {@link ConcurrentModificationException} is
         * thrown.
         */
        static final long BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE =
                ((long) DELETED_SLOT_COUNT_MASK) << DELETED_SLOT_COUNT_SHIFT;

        private static final long BIT_SET_AND_STATE_OFFSET;

        static {
            assertIsPowerOfTwo(MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE, "");
            // Checking that all bitSetAndState's areas are set up correctly
            long fullBitSetAndState = BIT_SET_MASK |
                    ((long) SEGMENT_ORDER_MASK << SEGMENT_ORDER_SHIFT) |
                    ((long) EXTRA_ALLOC_CAPACITY_MASK << EXTRA_ALLOC_CAPACITY_SHIFT) |
                    ((long) DELETED_SLOT_COUNT_MASK << DELETED_SLOT_COUNT_SHIFT);
            assertThat(fullBitSetAndState == -1L);
            assertThat(BASE_ALLOC_CAPACITY == 17);
        }

        static {
            try {
                Field bitSetAndStateField =
                        BitSetAndStateArea.class.getDeclaredField("bitSetAndState");
                BIT_SET_AND_STATE_OFFSET = U.objectFieldOffset(bitSetAndStateField);
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }

        static long getBitSetAndState(Object segment) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            return U.getLong(segment, BIT_SET_AND_STATE_OFFSET);
        }

        static void setBitSetAndState(Object segment, long bitSetAndState) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putLong(segment, BIT_SET_AND_STATE_OFFSET, bitSetAndState);
        }

        static long makeNewBitSetAndState(int allocCapacity, int segmentOrder) {
            int extraAllocCapacity = allocCapacity - BASE_ALLOC_CAPACITY;
            return EMPTY_BIT_SET |
                    (((long) segmentOrder) << SEGMENT_ORDER_SHIFT) |
                    (((long) extraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
        }

        /**
         * A privately populated segment has no deleted slots and the first {@code segmentSize}
         * contiguous allocation slots are occupied.
         */
        static long makeBitSetAndStateForPrivatelyPopulatedSegment(int allocCapacity,
                int segmentOrder, int segmentSize) {
            long bitSet = ((EMPTY_BIT_SET << segmentSize) & EMPTY_BIT_SET);
            int extraAllocCapacity = allocCapacity - BASE_ALLOC_CAPACITY;
            return bitSet |
                    (((long) segmentOrder) << SEGMENT_ORDER_SHIFT) |
                    (((long) extraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
        }

        static long incrementSegmentOrderAndZeroDeletedSlotCount(long bitSetAndState) {
            return (bitSetAndState + SEGMENT_ORDER_UNIT) & NEGATIVE_DELETED_SLOT_COUNT_MASK;
        }

        static long makeBitSetAndStateWithNewAllocCapacity(
                long bitSetAndState, int newAllocCapacity) {
            long negativeExtraAllocCapacityMask =
                    ~(((long) EXTRA_ALLOC_CAPACITY_MASK) << EXTRA_ALLOC_CAPACITY_SHIFT);
            int newExtraAllocCapacity = newAllocCapacity - BASE_ALLOC_CAPACITY;
            return (bitSetAndState & negativeExtraAllocCapacityMask) |
                    (((long) newExtraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
        }

        static long makeBitSetAndStateWithNewDeletedSlotCount(
                long bitSetAndState, int newDeletedSlotCount) {
            return (bitSetAndState & NEGATIVE_DELETED_SLOT_COUNT_MASK) |
                    (((long) newDeletedSlotCount) << DELETED_SLOT_COUNT_SHIFT);
        }

        /**
         * Returns a bitSetAndState that includes a full bit set, an extra alloc capacity of 0, the
         * specified segment order and a deleted slot count equal to {@link
         * #INFLATED_SEGMENT_DELETED_SLOT_COUNT}.
         */
        static long makeInflatedBitSetAndState(int segmentOrder) {
            return (((long) INFLATED_SEGMENT_DELETED_SLOT_COUNT) << DELETED_SLOT_COUNT_SHIFT) |
                    (((long) segmentOrder) << SEGMENT_ORDER_SHIFT);
        }

        static boolean isInflatedBitSetAndState(long bitSetAndState) {
            long inflatedSegmentWithZeroOrder_bitSetAndState =
                    ((long) INFLATED_SEGMENT_DELETED_SLOT_COUNT) << DELETED_SLOT_COUNT_SHIFT;
            // Check that the deleted slot counts match.
            return (bitSetAndState & inflatedSegmentWithZeroOrder_bitSetAndState) ==
                    inflatedSegmentWithZeroOrder_bitSetAndState;
        }

        static long clearBitSetAndDeletedSlotCount(long bitSetAndState) {
            bitSetAndState |= EMPTY_BIT_SET;
            bitSetAndState &= ~(((long) DELETED_SLOT_COUNT_MASK) << DELETED_SLOT_COUNT_SHIFT);
            return bitSetAndState;
        }

        /**
         * Inverts the bitSet's bits so that set bits correspond to occupied slots (by default, it's
         * vice versa in bitSetAndState, see the comment for {@link #EMPTY_BIT_SET}) to make it
         * convenient for branchless iteration via {@link Long#numberOfLeadingZeros} or {@link
         * Long#numberOfTrailingZeros}.
         */
        static long extractBitSetForIteration(long bitSetAndState) {
            return (~bitSetAndState) & BIT_SET_MASK;
        }

        /**
         * This method could return a value outside of the allowed range, if the segment is already
         * full. The result must be checked for being less than {@link #allocCapacity}.
         */
        static int lowestFreeAllocIndex(long bitSetAndState) {
            return Long.numberOfTrailingZeros(bitSetAndState);
        }

        static long clearAllocBit(long bitSetAndState, int allocIndex) {
            return bitSetAndState | (1L << allocIndex);
        }

        static long updateDeletedSlotCountAndSetLowestAllocBit(long bitSetAndState,
                int replacingEmptySlot) {
            return (bitSetAndState & (bitSetAndState - 1)) +
                    (DELETED_SLOT_COUNT_UNIT - DELETED_SLOT_COUNT_UNIT * (long) replacingEmptySlot);
        }

        static long setLowestAllocBit(long bitSetAndState) {
            return bitSetAndState & (bitSetAndState - 1);
        }

        static int segmentSize(long bitSetAndState) {
            // Using this form rather than Long.bitCount((~bitSetAndState) & BIT_SET_MASK), because
            // segmentSize() method is typically used in other arithmetic operations and
            // comparisons, so the `BIT_SET_BITS -` part could be applied by the compiler to some
            // earlier computed expression, shortening the longest data dependency chain.
            return BIT_SET_BITS - Long.bitCount(bitSetAndState & BIT_SET_MASK);
        }

        static boolean isEmpty(long bitSetAndState) {
            return (bitSetAndState & BIT_SET_MASK) == EMPTY_BIT_SET;
        }

        static int segmentOrder(long bitSetAndState) {
            return ((int) (bitSetAndState >>> SEGMENT_ORDER_SHIFT)) & SEGMENT_ORDER_MASK;
        }

        static int allocCapacity(long bitSetAndState) {
            int extraAllocCapacity = ((int) (bitSetAndState >>> EXTRA_ALLOC_CAPACITY_SHIFT)) &
                    EXTRA_ALLOC_CAPACITY_MASK;
            return BASE_ALLOC_CAPACITY + extraAllocCapacity;
        }

        /**
         * Computes `(allocCapacity(bitSetAndState) - 1) / 2` with the same complexity as {@link
         * #allocCapacity} itself.
         */
        static int allocCapacityMinusOneHalved(long bitSetAndState) {
            int extraAllocCapacityHalved =
                    ((int) (bitSetAndState >>> (EXTRA_ALLOC_CAPACITY_SHIFT + 1)))
                            & (EXTRA_ALLOC_CAPACITY_MASK >>> 1);
            // For this method to return the correct value of `(allocCapacity(bitSetAndState) - 1) / 2`,
            // BASE_ALLOC_CAPACITY should be odd.
            return ((BASE_ALLOC_CAPACITY - 1) / 2) + extraAllocCapacityHalved;
        }

        static int deletedSlotCount(long bitSetAndState) {
            return (int) (bitSetAndState >>> DELETED_SLOT_COUNT_SHIFT);
        }

        static long replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(Object segment) {
            long bitSetAndState = getBitSetAndState(segment);
            if (bitSetAndState == BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) {
                throw new ConcurrentModificationException();
            }
            setBitSetAndState(segment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);
            return bitSetAndState;
        }

        static void setBitSetAndStateAfterBulkOperation(Object segment, long newBitSetAndState) {
            // This extra check doesn't guarantee anything because the update is not atomic, but it
            // raises the chances of catching concurrent modification.
            if (getBitSetAndState(segment) != BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) {
                throw new ConcurrentModificationException();
            }
            setBitSetAndState(segment, newBitSetAndState);
        }

        @SuppressWarnings("unused")
        final DebugBitSetAndState debugBitSetAndState() {
            return new DebugBitSetAndState(bitSetAndState);
        }

        /** A structure view on {@link #bitSetAndState} for debugging. */
        static class DebugBitSetAndState {
            final long bitSet;
            final int size;
            final int order;
            final int allocCapacity;
            final int deletedSlotCount;

            DebugBitSetAndState(long bitSetAndState) {
                bitSet = extractBitSetForIteration(bitSetAndState);
                size = Long.bitCount(bitSet);
                order = segmentOrder(bitSetAndState);
                allocCapacity = allocCapacity(bitSetAndState);
                deletedSlotCount = deletedSlotCount(bitSetAndState);
            }
        }
    }

    /**
     * TODO describe what's implied by the comment to {@link #bitSetAndState}.
     */
    static class Segment<K, V> extends BitSetAndStateArea implements Cloneable {

        /**
         * If the number of non-empty (full or deleted) slots in a segment is about to exceed this
         * threshold, {@link #dropDeletes} (if the number of deleted slots is greater or equal to
         * {@link #SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES}) or {@link #split} is initiated.
         *
         * There are three conflicting requirements affecting this constant and {@link
         * #SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES}:
         *
         *  - Max non-empty slots shouldn't be much greater than {@link
         *    #SEGMENT_MAX_ALLOC_CAPACITY} (ideally should be equal to it), because the more
         *    non-empty slots could be present in a hash table the longer probe chains could become,
         *    that not only makes search slower for some keys, but could also reduce the quality of
         *    branch prediction (e. g. [Quadratic probing] loops are expected to perform only one
         *    iteration) for all SmoothieMaps, even those that don't have deleted slots at all.
         *
         *  - {@link #SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES} shouldn't be too small,
         *    because in the "insertions + deletions, steady net growth" case it leads to calling
         *    {@link #dropDeletes} soon before a subsequent {@link #split}. The latter drops deleted
         *    slots as a side effect too, repeating the work of the preceding {@link #dropDeletes}.
         *
         *  - SEGMENT_MAX_NON_EMPTY_SLOTS - {@link #SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES}
         *    (this constant means the maximum number of full slots for which segment splitting is
         *    never initiated) shouldn't be much smaller than {@link #SEGMENT_MAX_ALLOC_CAPACITY},
         *    because it leads to splitting a segment with too few entries, that reduces the memory
         *    utilization (efficiency) of a SmoothieMap.
         *
         *    Also, bigger difference between SEGMENT_MAX_NON_EMPTY_SLOTS - {@link
         *    #SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES} and {@link #SEGMENT_MAX_ALLOC_CAPACITY}
         *    reduces the statistical quality of hash code distribution analysis when segments are
         *    split, see the comment for {@link
         *    HashCodeDistribution#OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS
         *    }, [Adding up to SEGMENT_MAX_ALLOC_CAPACITY entries] section.
         *
         * TODO experiment with these constants
         */
        static final int SEGMENT_MAX_NON_EMPTY_SLOTS = SEGMENT_MAX_ALLOC_CAPACITY + 2;

        /** @see #SEGMENT_MAX_NON_EMPTY_SLOTS */
        static final int SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES = 5;

        /** TODO experiment with this constant */
        static final int MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING = 4;

        /**
         * This threshold determines when rare, but expected inflated segments (see {@link
         * InflatedSegmentQueryContext} Javadoc where this case is described) should be deflated.
         *
         * If entries are constantly added and removed in a SmoothieMap, inflated segments should
         * statistically have a tendency to reduce in size, because the total cardinality of hash
         * code ranges, associated with them is 2 to 4 times smaller than that of an average segment
         * (numbers x = 2 and y = 4 appear as x = 2 ^ {@link
         * #MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE} = 2, y = x * 2). Therefore this constant
         * could be less than {@link #MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING}, since freshly
         * deflated segments are less likely to grow back and be inflated again.
         */
        static final int MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_DEFLATION = 1;

        /**
         * EMPTY_CONTROL and {@link #DELETED_CONTROL} values are taken from hashbrown:
         * https://github.com/Amanieu/hashbrown/blob/6b9cc4e01090553c5928ccc0ee4568319ee0ed33/
         * src/raw/mod.rs#L46-L50
         *
         * It differs from raw_hash_set.h, because Segment doesn't need a "sentinel" value (see
         * https://github.com/abseil/abseil-cpp/blob/3088e76c597e068479e82508b1770a7ad0c806b6/
         * absl/container/internal/raw_hash_set.h#L292) for iteration. The sentinel value is only
         * necessary in C++ due to how C++ iterators work, requiring the end() pointer. See
         * https://github.com/Amanieu/hashbrown/issues/35#issuecomment-444684378 for more info.
         */
        static final @IntVal(0b1111_1111) byte EMPTY_CONTROL = -1;
        static final @IntVal(0b1000_0000) byte DELETED_CONTROL = -128;
        /**
         * The value to fill control slots with in {@link InflatedSegment}'s in {@link
         * HashTableArea}. See the Javadoc for {@link InflatedSegment} and
         * [InflatedSegment checking after unsuccessful key search] for details.
         */
        static final int INFLATED_SEGMENT_MARKER_CONTROL = 0b1000_1000;

        static final long MOST_SIGNIFICANT_BIT = 1L << 7;

        /** {@link #EMPTY_CONTROL} repeated 8 times */
        static final long EMPTY_CONTROLS_GROUP = -1L;

        private static final long MOST_SIGNIFICANT_BYTE_BITS = 0x8080808080808080L;
        private static final long LEAST_SIGNIFICANT_BYTE_BITS = 0x0101010101010101L;

        /**
         * A group of {@link #INFLATED_SEGMENT_MARKER_CONTROL} values to fill {@link
         * InflatedSegment}'s control slots with.
         */
        @SuppressWarnings({"NumericOverflow", "ConstantOverflow"})
        static final long INFLATED_SEGMENT_MARKER_CONTROLS_GROUP =
                INFLATED_SEGMENT_MARKER_CONTROL * LEAST_SIGNIFICANT_BYTE_BITS;

        static boolean isFirstSlotFull(long controlsGroup) {
            return (controlsGroup & MOST_SIGNIFICANT_BIT) == 0;
        }

        /**
         * For the technique, see:
         * http://graphics.stanford.edu/~seander/bithacks.html##ValueInWord
         * (Determine if a word has a byte equal to n).
         *
         * Caveat: there are false positives but:
         * - they only occur if there is a real match
         * - they never occur on {@link #EMPTY_CONTROL} or {@link #DELETED_CONTROL}
         * - they will be handled gracefully by subsequent checks in code
         *
         * Example:
         *   controlsGroup = 0x1716151413121110
         *   hashControlBits = 0x12
         *   return (x - LEAST_SIGNIFICANT_BYTE_BITS) & ~x & MOST_SIGNIFICANT_BYTE_BITS
         *     = 0x0000000080800000
         *
         * This method is copied from https://github.com/abseil/abseil-cpp/blob/
         * 3088e76c597e068479e82508b1770a7ad0c806b6/absl/container/internal/raw_hash_set.h#L428-L446
         */
        static long match(long controlsGroup, long hashControlBits) {
            long x = controlsGroup ^ (LEAST_SIGNIFICANT_BYTE_BITS * hashControlBits);
            return (x - LEAST_SIGNIFICANT_BYTE_BITS) & ~x & MOST_SIGNIFICANT_BYTE_BITS;
        }

        static long matchEmpty(long controlsGroup) {
            // Matches when the two highest bits in a control byte are ones.
            return (controlsGroup & (controlsGroup << 1)) & MOST_SIGNIFICANT_BYTE_BITS;
        }

        /**
         * Matches deleted control slots. Doesn't consider the possibility that control slots may
         * be {@link #INFLATED_SEGMENT_MARKER_CONTROL} (if they are, they are going to be matched
         * too).
         */
        static long matchDeleted(long controlsGroup) {
            // Matches when the highest bit is one and the 7th bit is 0.
            // The alternative is (x ^ (~x << 1)) & MSB, but that seems to incur one more step in
            // the longest data dependency chain (1 ~; 2 <<; 3 ^; 4 &) than the version below.
            return (controlsGroup ^ (controlsGroup << 1)) &
                    (controlsGroup & MOST_SIGNIFICANT_BYTE_BITS);
        }

        static long matchFull(long controlsGroup) {
            // Matches when the highest bit in a byte is zero.
            return (~controlsGroup) & MOST_SIGNIFICANT_BYTE_BITS;
        }

        static long matchFullOrDeleted(long controlsGroup) {
            // Matches when there is a zero among two highest bits in a control byte: the highest
            // bit for full slots, the second highest bit for deleted slots.
            return (~controlsGroup | (~controlsGroup << 1)) & MOST_SIGNIFICANT_BYTE_BITS;
        }

        /**
         * Matches empty or deleted control slots. Doesn't consider the possibility that control
         * slots may be {@link #INFLATED_SEGMENT_MARKER_CONTROL} (if they are, they are going to be
         * matched too).
         */
        static long matchEmptyOrDeleted(long controlsGroup) {
            return controlsGroup & MOST_SIGNIFICANT_BYTE_BITS;
        }

        // TODO fix; find out why this method is needed in raw_hash_set.h
        static int countLeadingEmptyOrDeleted(long controlsGroup) {
            long gaps = 0x00FEFEFEFEFEFEFEL;
            return (Long.numberOfTrailingZeros(((~controlsGroup & (controlsGroup >> 7)) | gaps) + 1) + 7)
                    >> 3;
        }

        /**
         * Performs the following transformation on all bytes in the controls group:
         * {@link #DELETED_CONTROL} -> {@link #EMPTY_CONTROL}
         * Neither {@link #EMPTY_CONTROL} nor {@link #DELETED_CONTROL}, i. e. a full slot ->
         *   {@link #DELETED_CONTROL}.
         *
         * Doesn't consider the possibility that control slots may be {@link
         * #INFLATED_SEGMENT_MARKER_CONTROL}. If they are, they are going to be converted to {@link
         * #EMPTY_CONTROL}.
         */
        static long convertDeletedToEmptyAndFullToDeleted(long controlsGroup) {
            // Map high bit = 1 (EMPTY_CONTROL or DELETED_CONTROL) to 1111_1111 (EMPTY_CONTROL)
            // and high bit = 0 (a full slot) to 1000_0000 (DELETED_CONTROL)
            long fullBitMask = (~controlsGroup) & MOST_SIGNIFICANT_BYTE_BITS;
            return (~fullBitMask + (fullBitMask >>> 7));
        }

        static int lowestMatchIndex(int groupFirstSlotIndex, long groupBitMask) {
            return addSlotIndex(groupFirstSlotIndex,
                    // [Replacing division with shift]
                    Long.numberOfTrailingZeros(groupBitMask) >>> BYTE_SIZE_DIVISION_SHIFT);
        }

        /**
         * The second part of inlined {@link #lowestMatchIndex} method, see
         * [Inlined lowestMatchIndex].
         */
        static int lowestMatchIndexFromTrailingZeros(int groupFirstSlotIndex, int trailingZeros) {
            return addSlotIndex(groupFirstSlotIndex,
                    // [Replacing division with shift]
                    trailingZeros >>> BYTE_SIZE_DIVISION_SHIFT);
        }

        /**
         * Determines whether the lowest empty or deleted slot in the controls group is empty (then
         * returns 1), or deleted (then returns 0).
         * @param trailingZeros the number of trailing zeros in a result of
         *        {@link #matchEmptyOrDeleted} call. I. e. this argument must be equal to
         *        Long.numberOfTrailingZeros(matchEmptyOrDeleted(controlsGroup)).
         *
         * @implNote implementation of this method exploits the fact that {@link #EMPTY_CONTROL}'s
         * 7th bit is 1, and {@link #DELETED_CONTROL}'s 7th bit is 0. trailingZeros is the number of
         * bits until the most significant bit of the corresponding matched control byte (see {@link
         * #matchEmptyOrDeleted}), i. e. until the 7th bit.
         */
        static int extractMatchingEmpty(long controlsGroup, int trailingZeros) {
            return (int) ((controlsGroup >>> (trailingZeros - 1)) & 1);
        }

        /**
         * Shifts the data group to the right to make the data byte corresponding to the matched
         * control byte the first byte in the data group.
         * @param trailingZeros the number of trailing zeros in a result of any matching method,
         *        like {@link #match}, {@link #matchDeleted}, etc. I. e. this argument must be equal
         *        to Long.numberOfTrailingZeros(match*(controlsGroup)).
         */
        static long extractDataByte(long dataGroup, int trailingZeros) {
            return dataGroup >>> (trailingZeros - (Byte.SIZE - 1));
        }

        /**
         * Determines whether the lowest empty or deleted slot in the controls group is empty (then
         * returns true), or deleted (then returns false).
         *
         * This method is equivalent to {@link #extractMatchingEmpty}, except that it returns a
         * boolean result instead of 1/0 integer.
         *
         * @param trailingZeros the number of trailing zeros in a result of
         *        {@link #matchEmptyOrDeleted} call. I. e. this argument must be equal to
         *        Long.numberOfTrailingZeros(matchEmptyOrDeleted(controlsGroup)).
         */
        static boolean isMatchingEmpty(long controlsGroup, int trailingZeros) {
            return ((controlsGroup >>> (trailingZeros - 1)) & 1) != 0;
        }

        /**
         * If the given `deleted` is 1 returns {@link #DELETED_CONTROL}, if the given `deleted` is 0
         * returns {@link #EMPTY_CONTROL}.
         */
        static int branchlessDeletedOrEmpty(int deleted) {
            return EMPTY_CONTROL - ((EMPTY_CONTROL - DELETED_CONTROL) * deleted);
        }

        /** = numberOfTrailingZeros({@link #HASH_TABLE_SLOTS}). */
        @CompileTimeConstant
        static final int LOG_HASH_TABLE_SIZE = 6;
        static final int HASH_CONTROL_BITS = 7;
        private static final long HASH_CONTROL_BIT_MASK = (1 << HASH_CONTROL_BITS) - 1;
        private static final int ALLOC_INDEX_DATA_BITS = 6;
        private static final int ALLOC_INDEX_DATA_MASK = (1 << ALLOC_INDEX_DATA_BITS) - 1;
        static final int HASH_DATA_BITS = Byte.SIZE - ALLOC_INDEX_DATA_BITS;
        private static final int HASH_DATA_BITS_MASK =
                ((1 << HASH_DATA_BITS) - 1) << ALLOC_INDEX_DATA_BITS;
        private static final int HASH_DATA_SHIFT_IN_DATA_BYTE = ALLOC_INDEX_DATA_BITS;
        private static final int HASH_DATA_BITS_SHIFT_FOR_KEY_HASH =
                HASH_CONTROL_BITS - ALLOC_INDEX_DATA_BITS;

        static {
            assertThat(LOG_HASH_TABLE_SIZE == Integer.numberOfTrailingZeros(HASH_TABLE_SLOTS));
            assertThat(SLOT_MASK == (1 << LOG_HASH_TABLE_SIZE) - 1);
            assertThat(HASH_DATA_BITS_MASK == 0b1100_0000);
            assertThat(HASH_DATA_BITS_SHIFT_FOR_KEY_HASH == 1);
        }

        static long clearLowestSetBit(long bitMask) {
            return bitMask & (bitMask - 1);
        }

        static int slotIndex(long hash) {
            return ((int) hash) & SLOT_MASK;
        }

        static int addSlotIndex(int slotIndex, int addition) {
            return (slotIndex + addition) & SLOT_MASK;
        }

        static long hashControlBits(long hash) {
            return (hash >> LOG_HASH_TABLE_SIZE) & HASH_CONTROL_BIT_MASK;
        }

        static boolean matchHashDataBits(int data, long hash) {
            return ((data ^ ((int) (hash >> HASH_DATA_BITS_SHIFT_FOR_KEY_HASH))) &
                    HASH_DATA_BITS_MASK) == 0;
        }

        static int debugExtractHashDataBits(int data) {
            return (data & HASH_DATA_BITS_MASK) >> HASH_DATA_SHIFT_IN_DATA_BYTE;
        }

        static byte makeData(int allocIndex, long hash) {
            int hashPart =
                    ((int) (hash >> HASH_DATA_BITS_SHIFT_FOR_KEY_HASH)) & HASH_DATA_BITS_MASK;
            return (byte) (hashPart | allocIndex);
        }

        /**
         * @param data an int value containing the data byte in its lower 8 bits. Higher bits may
         *        contain garbage.
         */
        static byte changeAllocIndexInData(int data, int newAllocIndex) {
            return (byte) ((data & HASH_DATA_BITS_MASK) | newAllocIndex);
        }

        /**
         * @param data an int value containing the data byte in its lower 8 bits. Higher bits may
         *        contain garbage.
         */
        static int allocIndex(int data) {
            return data & ALLOC_INDEX_DATA_MASK;
        }

        static <K, V> Segment<K, V> allocateSegment(int allocCapacity) {
            Class<? extends Segment<K, V>> c = acquireClass(allocCapacity);
            try {
                @SuppressWarnings("unchecked")
                Segment<K, V> segment = (Segment<K, V>) U.allocateInstance(c);
                return segment;
            } catch (InstantiationException e) {
                throw new AssertionError(e);
            }
        }

        static <K, V> Segment<K, V> allocateNewSegmentAndSetEmptyControls(int allocCapacity) {
            Segment<K, V> segment = allocateSegment(allocCapacity);
            segment.setAllControlSlotsFromGroup(EMPTY_CONTROLS_GROUP);
            return segment;
        }

        static <K, V> Segment<K, V> createNewSegment(int allocCapacity, int segmentOrder) {
            Segment<K, V> segment = allocateNewSegmentAndSetEmptyControls(allocCapacity);
            segment.bitSetAndState = makeNewBitSetAndState(allocCapacity, segmentOrder);
            // Safe segment publication: ensure racy readers always see correctly initialized
            // bitSetAndState. It's hard to prove that no races are possible that could lead to
            // a JVM crash or memory corruption due to access to an alloc index beyond the alloc
            // capacity of the segment, especially considering the possibility of non-atomic
            // bitSetAndState access on 32-bit platforms.
            U.storeFence();
            return segment;
        }

        /**
         * This constructor must not be used. {@link #createNewSegment(int, int)} should be used
         * instead to obtain a Segment instance. This constructor is not annotated @Deprecated
         * because that makes all implicit no-arg constructors in classes {@link Segment17} through
         * {@link Segment48} to appear to use a deprecated member, and thus generate compiler
         * warnings.
         */
        Segment() {
        }

        static <K> K readKey(Object segment, @NonNegative int allocIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            //noinspection unchecked
            K key = (K) U.getObject(segment, allocKeyOffset(allocIndex));
            if (key == null) {
                throw new ConcurrentModificationException();
            }
            return key;
        }

        /**
         * This method is called from iterators (either directly, or via {@link #readKeyChecked} or
         * {@link #readValueChecked}) where there is no guarantee that the allocIndex belongs to the
         * provided segment object because of potential racy concurrent method calls on the Iterator
         * object itself. allocIndex may be equal to {@link #SEGMENT_MAX_ALLOC_CAPACITY} - 1, from
         * some segment and the segment provided may be a segment with {@link
         * #SEGMENT_INTERMEDIATE_ALLOC_CAPACITY} which follows the full-capacity to which the
         * allocIndex really belongs.
         *
         * To avoid memory corruption or segmentation faults, we must read the segment's {@link
         * #allocCapacity} and compare to the allocIndex, similar to what JVM does during normal
         * array accesses. We also check that the segment object is not null because if the Iterator
         * object is created in one thread and {@link ImmutableKeyIterator#next()} is called from
         * another thread {@link ImmutableSmoothieIterator#currentSegment} might not be visible and
         * null is read from this field and passed into readKeyAllocCapacityChecked().
         *
         * The alternative to doing the extra checks (which requires extra reading of {@link
         * #bitSetAndState} on every iteration) is to store a "SegmentIteration" object with
         * `final Object segment` and `int allocIndex` fields. When iterator advances to the next
         * segment (e. g. in {@link ImmutableSmoothieIterator#initCurrentSegment} a new
         * SegmentIteration object is created instead of updating the segment field in the previous
         * one and the reference to SegmentIteration is updated in the iterator. However, the
         * "SegmentIteration" approach is unlikely to be faster than the "array-style checks"
         * approach because it also imposes extra indirection, extra read (both on {@link HotPath}),
         * extra writes, and extra allocations (both {@link AmortizedPerSegment}).
         * TODO compare the approaches (low-priority because unlikely to be fruitful)
         */
        static void checkAllocIndex(Object segment, @NonNegative int allocIndex) {
            if (segment == null) {
                throw new ConcurrentModificationException();
            }
            long bitSetAndState = getBitSetAndState(segment);
            int allocCapacity = allocCapacity(bitSetAndState);
            if (allocIndex >= allocCapacity) {
                throw new ConcurrentModificationException();
            }
        }

        static <K> K readKeyChecked(Object segment, @NonNegative int allocIndex) {
            checkAllocIndex(segment, allocIndex);
            //noinspection unchecked
            K key = (K) U.getObject(segment, allocKeyOffset(allocIndex));
            if (key == null) {
                // Concurrent segment modification or an inflated segment: key may be null not only
                // because of concurrent modification (entry deletion) in the segment, but also
                // because the segment provided is in fact an InflatedSegment which has nulls at all
                // alloc indexes. The Javadoc comment for this method explains how a wrong segment
                // object may be passed into this method.
                throw new ConcurrentModificationException();
            }
            return key;
        }

        static <V> V readValue(Object segment, @NonNegative int allocIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            //noinspection unchecked
            V value = (V) U.getObject(segment, allocValueOffset(allocIndex));
            if (value == null) {
                throw new ConcurrentModificationException();
            }
            return value;
        }

        static <V> V readValueChecked(Object segment, @NonNegative int allocIndex) {
            checkAllocIndex(segment, allocIndex);
            //noinspection unchecked
            V value = (V) U.getObject(segment, allocValueOffset(allocIndex));
            if (value == null) {
                // [Concurrent segment modification or an inflated segment]
                throw new ConcurrentModificationException();
            }
            return value;
        }

        static void writeKey(Object segment, int allocIndex, Object key) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putObject(segment, allocKeyOffset(allocIndex), key);
        }

        static void writeValue(Object segment, int allocIndex, Object value) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            U.putObject(segment, allocValueOffset(allocIndex), value);
        }

        static void eraseKeyAndValue(Object segment, int allocIndex) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            long keyOffset = allocKeyOffset(allocIndex);
            long valueOffset = keyOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
            U.putObject(segment, keyOffset, null);
            U.putObject(segment, valueOffset, null);
        }

        static <K, V> void writeEntry(Object segment, K key, long hash, V value, int slotIndex,
                int allocIndex) {
            writeControlByte(segment, (long) slotIndex, (byte) hashControlBits(hash));
            writeData(segment, (long) slotIndex, makeData(allocIndex, hash));
            writeKey(segment, allocIndex, key);
            writeValue(segment, allocIndex, value);
        }

        //region Segment's internal bulk operations

        final void setAllControlSlotsFromGroup(long controlsGroup) {
            // Looping manually instead of using Unsafe.setMemory() because Unsafe prohibits calling
            // setMemory() with non-array objects since Java 9.
            // Int-indexed loop to avoid a safepoint poll:
            // see http://psy-lob-saw.blogspot.com/2016/02/wait-for-it-counteduncounted-loops.html
            for (int offset = CONTROLS_OFFSET_AS_INT,
                 limit = CONTROLS_OFFSET_AS_INT + HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS;
                 offset < limit;
                 offset += Long.BYTES) {
                U.putLong(this, (long) offset, controlsGroup);
            }
        }

        @AmortizedPerSegment
        void copyHashTableFrom(Object oldSegment) {
            /* if Enabled extraChecks */assertNonNullSegment(oldSegment);/* endif */
            // Looping manually instead of using Unsafe.copyMemory() because Unsafe prohibits
            // calling copyMemory() with non-array objects since Java 9.
            // [Int-indexed loop to avoid a safepoint poll]
            for (int offset = CONTROLS_OFFSET_AS_INT,
                 limit = CONTROLS_OFFSET_AS_INT + TOTAL_HASH_TABLE_BYTES;
                 offset < limit;
                 offset += Long.BYTES) {
                long offsetAsLong = (long) offset;
                U.putLong(this, offsetAsLong, U.getLong(oldSegment, offsetAsLong));
            }
        }

        void copyAllocAreaFrom(Object oldSegment, int oldSegmentCapacity) {
            /* if Enabled extraChecks */assertNonNullSegment(oldSegment);/* endif */
            for (int allocIndex = 0; allocIndex < oldSegmentCapacity; allocIndex++) {
                long keyOffset = allocKeyOffset(allocIndex);
                long valueOffset = keyOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
                // Raw key and value objects copy: not using readKey() and readValue() for
                // performance and because they check the objects for not being nulls, however in
                // this loop we may copy nulls in some slots. Not checking for that to make the
                // iteration branchless and streamlined.
                // TODO some GCs may in fact do nullness checks themselves on putObject(). If they
                //  also have write barriers for writing nulls, it might be better to have explicit
                //  nullness checks and not copy nulls to `this` segment. We know that object slots
                //  at `this` segment are all nulls before this loop; the GC might not know that
                //  and prepare for the worse (that the slots may contain non-null objects) or make
                //  such checks before the writes.
                U.putObject(this, keyOffset, U.getObject(oldSegment, keyOffset));
                U.putObject(this, valueOffset, U.getObject(oldSegment, valueOffset));
            }
        }

        @AmortizedPerSegment
        static void swapHashTables(Object segmentOne, Object segmentTwo) {
            /* if Enabled extraChecks */
            assertNonNullSegment(segmentOne);
            assertNonNullSegment(segmentTwo);
            /* endif */
            // [Int-indexed loop to avoid a safepoint poll]
            for (int offset = CONTROLS_OFFSET_AS_INT,
                 limit = CONTROLS_OFFSET_AS_INT + TOTAL_HASH_TABLE_BYTES;
                 offset < limit; offset += Long.BYTES) {
                long offsetAsLong = (long) offset;
                long hashTableWordOne = U.getLong(segmentOne, offsetAsLong);
                long hashTableWordTwo = U.getLong(segmentTwo, offsetAsLong);
                U.putLong(segmentOne, offsetAsLong, hashTableWordTwo);
                U.putLong(segmentTwo, offsetAsLong, hashTableWordOne);
            }
        }

        @AmortizedPerSegment
        static void covertAllDeletedToEmptyAndFullToDeletedControlSlots(Object segment) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            // Alternative to converting the cloned group just like other groups is actually cloning
            // the first group after the conversion. TODO compare the approaches
            // [Int-indexed loop to avoid a safepoint poll]
            for (int offset = CONTROLS_OFFSET_AS_INT,
                 limit = CONTROLS_OFFSET_AS_INT + HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS;
                 offset < limit;
                 offset += Long.BYTES) {
                long controlsGroup = U.getLong(segment, (long) offset);
                long convertedControlsGroup = convertDeletedToEmptyAndFullToDeleted(controlsGroup);
                U.putLong(segment, (long) offset, convertedControlsGroup);
            }
        }

        /**
         * Returns the updated nonFullSegment_bitSet (which is the allocation index mapping part of
         * {@link Segment#bitSetAndState}).
         *
         * This method must only be called in the context of {@link #split} because it may call to
         * {@link #compactEntriesDuringSplit} which should always be called in the context of {@link
         * #split}. See the comment for {@link #compactEntriesDuringSplit}.
         */
        @AmortizedPerSegment
        static long swapHashTablesAndAllocAreasDuringSplit(
                Object nonFullSegment, int nonFullSegment_allocCapacity,
                long nonFullSegment_bitSet, Object fullSegment, int fullSegment_allocCapacity) {
            // Compacting entries of nonFullSegment if needed to be able to swap allocation areas.
            // This must be done before swapping the hash tables, because
            // compactEntriesDuringSplit() updates some data slots of nonFullSegment.
            if (fullSegment_allocCapacity < nonFullSegment_allocCapacity) { // Likelihood - ???
                nonFullSegment_bitSet = compactEntriesDuringSplit(nonFullSegment,
                        nonFullSegment_allocCapacity, nonFullSegment_bitSet,
                        fullSegment_allocCapacity);
            }

            // [Guard in a non-HotPath method]
            if (fullSegment_allocCapacity > nonFullSegment_allocCapacity) { // Unlikely branch
                // Cannot swap; should not happen
                throw new AssertionError();
            }

            swapHashTables(nonFullSegment, fullSegment);

            // Swapping allocation areas.
            for (int allocIndex = 0; allocIndex < fullSegment_allocCapacity; allocIndex++) {
                long keyOffset = allocKeyOffset(allocIndex);
                long valueOffset = keyOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;

                // TODO [Raw key and value objects copy]
                Object k1 = U.getObject(fullSegment, keyOffset);
                Object v1 = U.getObject(fullSegment, valueOffset);

                Object k2 = U.getObject(nonFullSegment, keyOffset);
                Object v2 = U.getObject(nonFullSegment, valueOffset);

                U.putObject(nonFullSegment, keyOffset, k1);
                U.putObject(nonFullSegment, valueOffset, v1);

                U.putObject(fullSegment, keyOffset, k2);
                U.putObject(fullSegment, valueOffset, v2);
            }

            return nonFullSegment_bitSet;
        }

        /**
         * "Defragment" entries so that all entries that are stored at alloc indexes equal to and
         * beyond fitInAllocCapacity are moved to smaller alloc indexes.
         *
         * Must be called only in the context of {@link #split}, because it assumes that deleted
         * control slots are in fact former full, and there are no "true" deleted control slots,
         * i. e. that the segment is in the process of split[fromSegment iteration] after calling
         * to {@link #covertAllDeletedToEmptyAndFullToDeletedControlSlots}.
         *
         * @return an updated bitSet
         */
        @AmortizedPerSegment
        private static long compactEntriesDuringSplit(Object segment, int allocCapacity,
                long bitSet, int fitInAllocCapacity) {
            /* if Enabled extraChecks */assertNonNullSegment(segment);/* endif */
            int segmentSize = segmentSize(bitSet);
            if (segmentSize > fitInAllocCapacity) {
                throw new ConcurrentModificationException();
            }
            if (fitInAllocCapacity >= allocCapacity) {
                // compactEntriesDuringSplit() method must not be called if it's not needed anyway.
                throw new AssertionError();
            }

            // Branchless hash table iteration:
            // Using bitMask-style loop to go over all full or deleted slots in the group.
            // The alternative approach is to iterate over each byte in separation. It's hard to say
            // which approach approach is better without benchmarking. The bitMask approach is
            // branchless, but it incurs more arithmetic operations and a (relatively expensive?)
            // numberOfTrailingZeros() call. Simpler [Byte-by-byte hash table iteration] has an
            // extra branch but it would be relatively well predicated here because 23-28% of slots
            // (more specifically, between SEGMENT_MAX_NON_EMPTY_SLOTS -
            // SEGMENT_MIN_DELETED_SLOTS_FOR_DROP_DELETES - SEGMENT_INTERMEDIATE_ALLOC_CAPACITY = 15
            // and SEGMENT_MAX_ALLOC_CAPACITY - SEGMENT_INTERMEDIATE_ALLOC_CAPACITY = 18 slots out
            // of 64) should be matched.
            // TODO compare the approaches
            // See also [Branchless entries iteration].
            //
            // This two-level hash table iteration loop is the same as in Segment.removeIf().
            // Modifications to these instances of the loop must be done simultaneously.
            // [Int-indexed loop to avoid a safepoint poll]
            for (int iterationGroupFirstSlotIndex = 0;
                 iterationGroupFirstSlotIndex < HASH_TABLE_SLOTS;
                 iterationGroupFirstSlotIndex += GROUP_SLOTS) {

                long controlsGroup =
                        readControlsGroup(segment, (long) iterationGroupFirstSlotIndex);
                long dataGroup = readDataGroup(segment, (long) iterationGroupFirstSlotIndex);

                // matchFullOrDeleted() because the segment is in the process of
                // split[fromSegment iteration] after calling to
                // covertAllDeletedToEmptyAndFullToDeletedControlSlots(). Both full and deleted
                // slots are mapping to full data slots. split[fromSegment iteration] may also be
                // already completed, then there are only full control slots that are matched.
                for (long bitMask = matchFullOrDeleted(controlsGroup);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {

                    int slotIndex;
                    int dataByte;
                    { // [Inlined lowestMatchIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                        // TODO lowestMatchIndexFromTrailingZeros() makes & SLOT_MASK, maybe this is
                        //  unnecessary in the context of this loop?
                        slotIndex = lowestMatchIndexFromTrailingZeros(
                                iterationGroupFirstSlotIndex, trailingZeros);
                        // Pseudo dataByte: `(int) extractDataByte(dataGroup, trailingZeros)`
                        // doesn't actually represent just a data byte because it may contain other
                        // data bytes in bits 8-31, but it doesn't matter because dataByte is only
                        // used as an argument for allocIndex() and changeAllocIndexInData() which
                        // care only about the lowest 8 bits.
                        dataByte = (int) extractDataByte(dataGroup, trailingZeros);
                    }

                    int allocIndex = allocIndex(dataByte);
                    if (allocIndex < fitInAllocCapacity) { // [Positive likely branch]
                        continue;
                    }

                    // ### Moving the entry at allocIndex to a smaller index.
                    int newAllocIndex = lowestFreeAllocIndex(bitSet);
                    // Transitively checks that newAllocIndex < allocCapacity as required by
                    // lowestFreeAllocIndex(), because it's ensured that fitInAllocCapacity is less
                    // than allocCapacity in a guard condition above.
                    if (newAllocIndex >= fitInAllocCapacity) {
                        // Can happen if entries are inserted into the segment concurrently with
                        // split(), including concurrently with this method which is called from
                        // swapHashTablesAndAllocAreasDuringSplit() which is called from split().
                        throw new ConcurrentModificationException();
                    }
                    // First calling setLowestAllocBit() and then clearAllocBit() leads to less
                    // data dependencies than if these methods were called in the reverse order.
                    bitSet = clearAllocBit(setLowestAllocBit(bitSet), allocIndex);

                    Object key = readKey(segment, allocIndex);
                    Object value = readValue(segment, allocIndex);

                    eraseKeyAndValue(segment, allocIndex);

                    writeKey(segment, newAllocIndex, key);
                    writeValue(segment, newAllocIndex, value);

                    // Alternative is to update a long word and write it out after the inner
                    // loop if diverges from originalDataGroup. But that's more complicated.
                    // TODO compare approaches
                    writeData(segment, (long) slotIndex,
                            changeAllocIndexInData(dataByte, newAllocIndex));
                }
            }
            return bitSet;
        }

        //endregion

        //region Segment's bulk operations corresponding to bulk Map API operations

        @Override
        public Segment<K, V> clone() {
            try {
                // Segment objects don't contain any nested arrays or other non-scalar data
                // structures apart from the key and the value objects themselves, so shallow
                // Object.clone() is fine here.
                //noinspection unchecked
                return (Segment<K, V>) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        /**
         * Returns true if some entries were cleared and {@link #modCount} of the map was
         * incremented, false otherwise.
         */
        boolean clear(int segmentIndex, SmoothieMap<K, V> map) {
            long bitSetAndState = this.bitSetAndState;
            int segmentSize = segmentSize(bitSetAndState);
            if (segmentSize > 0) { // [Positive likely branch]
                setAllControlSlotsFromGroup(EMPTY_CONTROLS_GROUP);
                // Leaving garbage in data slots.

                map.size -= (long) segmentSize;
                map.modCount++;
                int allocCapacity = allocCapacity(bitSetAndState);
                for (int allocIndex = 0; allocIndex < allocCapacity; allocIndex++) {
                    long keyOffset = allocKeyOffset(allocIndex);
                    long valueOffset = keyOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
                    U.putObject(this, keyOffset, null);
                    U.putObject(this, valueOffset, null);
                }
                this.bitSetAndState = clearBitSetAndDeletedSlotCount(bitSetAndState);
                return true;
            } else {
                return false;
            }
        }

        /** Use {@link #hashCode(SmoothieMap)} instead. */
        @DoNotCall
        @Deprecated
        @Override
        public final int hashCode() {
            throw new UnsupportedOperationException();
        }

        int hashCode(SmoothieMap<K, V> map) {
            int h = 0;
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // Iteration in bulk segment methods:
            // 1. Using [Branchless entries iteration] here rather than checking every bit of the
            // bitSet because the predictability of the bit checking branch would be the same as for
            // [Branchless entries iteration in iterators]. However, bulk iteration's execution
            // model may appear to be more or less favorable for consuming the cost of
            // numberOfLeadingZeros() in the CPU pipeline, so the tradeoff should be evaluated
            // separately from iterators. TODO compare the approaches
            // 2. [Backward entries iteration]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);
                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the hash code computation, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                h += longKeyHashCodeToIntHashCode(map.keyHashCode(key)) ^ map.valueHashCode(value);
            }
            return h;
        }

        void forEach(BiConsumer<? super K, ? super V> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);
                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(key, value);
            }
        }

        final void forEachKey(Consumer<? super K> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(key);
            }
        }

        final void forEachValue(Consumer<? super V> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(value);
            }
        }

        final boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);
                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                if (!predicate.test(key, value)) {
                    return false;
                }
            }
            return true;
        }

        final void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);
                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                writeValue(this, iterAllocIndex, function.apply(key, value));
            }
        }

        final boolean containsValue(SmoothieMap<K, V> map, V queriedValue) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                V internalVal = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after
                //  the value objects comparison, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                //noinspection ObjectEquality: identity comparision is intended
                boolean valuesIdentical = queriedValue == internalVal;
                if (valuesIdentical || map.valuesEqual(queriedValue, internalVal)) {
                    return true;
                }
            }
            return false;
        }

        final void writeAllEntries(ObjectOutputStream s) throws IOException {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {

                K key = readKey(this, iterAllocIndex);
                V value = readValue(this, iterAllocIndex);

                // TODO check what is better - these two statements before or after writing the
                //  objects to the output stream, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                s.writeObject(key);
                s.writeObject(value);
            }
        }

        final int removeIf(
                SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount) {
            long bitSetAndState = this.bitSetAndState;
            int initialModCount = modCount;
            try {
                // [Branchless hash table iteration]. Unlike [Branchless hash table iteration] in
                // compactEntriesDuringSplit() the branchless iteration here is more likely to
                // be a better choice than [Byte-by-byte hash table iteration] because the segment
                // is expected to be averagely filled during removeIf() (more specifically, expected
                // number of full slots is about (SEGMENT_MAX_ALLOC_CAPACITY +
                // (SEGMENT_MAX_ALLOC_CAPACITY / 2)) / 2 = 36 out of HASH_TABLE_SLOTS = 64, or 56%),
                // so byte-by-byte checking branch would be less predictable here.
                // TODO compare the approaches, separately from compactEntriesDuringSplit() because
                //  the branch probability is different.
                //
                // This two-level hash table iteration loop is the same as in
                // compactEntriesDuringSplit(). Modifications to these instances of the loop must be
                // done simultaneously.
                // [Int-indexed loop to avoid a safepoint poll]
                for (int iterationGroupFirstSlotIndex = 0;
                     iterationGroupFirstSlotIndex < HASH_TABLE_SLOTS;
                     iterationGroupFirstSlotIndex += GROUP_SLOTS) {

                    long controlsGroup =
                            readControlsGroup(this, (long) iterationGroupFirstSlotIndex);
                    long dataGroup = readDataGroup(this, (long) iterationGroupFirstSlotIndex);

                    // [Branchless hash table iteration]. Unlike other instances of
                    // [Branchless hash table iteration] in split(), dropDeletes(), and
                    //
                    innerLoop:
                    for (long bitMask = matchFull(controlsGroup);
                         bitMask != 0L;
                         bitMask = clearLowestSetBit(bitMask)) {

                        int slotIndex;
                        int dataByte;
                        { // [Inlined lowestMatchIndex]
                            int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                            // TODO lowestMatchIndexFromTrailingZeros() makes & SLOT_MASK, maybe
                            //  this is unnecessary in the context of this loop?
                            slotIndex = lowestMatchIndexFromTrailingZeros(
                                    iterationGroupFirstSlotIndex, trailingZeros);
                            // [Pseudo dataByte]
                            dataByte = (int) extractDataByte(dataGroup, trailingZeros);
                        }

                        int allocIndex = allocIndex(dataByte);

                        K key = readKey(this, allocIndex);
                        V value = readValue(this, allocIndex);

                        if (!filter.test(key, value)) {
                            continue innerLoop;
                        }

                        // TODO research if it's possible to do something better than just call
                        //  removeAtSlotNoShrink() in a loop, which may result in calling expensive
                        //  operations a lot of times if nearly all entries are removed from the
                        //  segment during this loop.
                        bitSetAndState = map.removeAtSlotNoShrink(
                                bitSetAndState, this, slotIndex, allocIndex);
                        // Matches the modCount field increment performed in removeAtSlotNoShrink().
                        modCount++;
                    }
                }
            } finally {
                // Writing bitSetAndState in a finally block because we want the segment to remain
                // in a consistent state if an exception was thrown from filter.test(), or in a
                // more up-to-date, debuggable state if a ConcurrentModificationException was thrown
                // from readKey() or readValue().
                if (modCount != initialModCount) {
                    setBitSetAndState(this, bitSetAndState);
                }
            }
            return modCount;
        }

        //endregion

        //region Segment's stats and debug bulk operations

        void aggregateStats(SmoothieMap<K, V> map, OrdinarySegmentStats ordinarySegmentStats) {
            ordinarySegmentStats.incrementAggregatedSegments();
            // [Byte-by-byte hash table iteration]
            for (int slotIndex = 0; slotIndex < HASH_TABLE_SLOTS; slotIndex++) {
                int controlByte = readControlByte(this, (long) slotIndex);
                if (controlByte == EMPTY_CONTROL) {
                    continue;
                }
                if (controlByte == DELETED_CONTROL) {
                    ordinarySegmentStats.aggregateDeletedSlot();
                    continue;
                }
                // Slot is full
                int data = readData(this, (long) slotIndex);
                int allocIndex = allocIndex(data);
                K key = readKey(this, allocIndex);
                long hash = map.keyHashCode(key);
                int slotIndexBase = slotIndex(hash);
                ordinarySegmentStats.aggregateFullSlot(slotIndexBase, slotIndex);
            }
        }

        String debugToString() {
            DebugBitSetAndState bitSetAndState = new DebugBitSetAndState(this.bitSetAndState);
            StringBuilder sb = new StringBuilder();
            sb.append(bitSetAndState).append('\n');
            sb.append("Slots:\n");
            for (int allocIndex = 0; allocIndex < bitSetAndState.allocCapacity; allocIndex++) {
                Object key = U.getObject(this, allocKeyOffset(allocIndex));
                Object value = U.getObject(this, allocValueOffset(allocIndex));
                sb.append(key).append('=').append(value).append('\n');
            }
            return sb.toString();
        }

        @Nullable DebugHashTableSlot<K, V>[] debugHashTable(SmoothieMap<K, V> map) {
            @Nullable DebugHashTableSlot[] debugHashTableSlots =
                    new DebugHashTableSlot[HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS];
            // [Byte-by-byte hash table iteration]
            for (int slotIndex = 0; slotIndex < HASH_TABLE_SLOTS + CLONED_CONTROL_SLOTS;
                 slotIndex++) {
                int controlByte = readControlByte(this, (long) slotIndex);
                if (controlByte == EMPTY_CONTROL) {
                    continue;
                }
                int dataByte = readData(this, (long) (slotIndex & SLOT_MASK));
                debugHashTableSlots[slotIndex] =
                        new DebugHashTableSlot<>(map, this, (byte) controlByte, dataByte);
            }
            //noinspection unchecked
            return debugHashTableSlots;
        }

        static class DebugHashTableSlot<K, V> {
            final byte controlByte;
            final byte dataByte;
            final int allocIndex;
            final int hashDataBits;
            final @Nullable K key;
            final long hash;
            final boolean hashDataBitsMatch;
            final byte restoredFullControlByte;
            final @Nullable V value;

            @SuppressWarnings("unchecked")
            DebugHashTableSlot(SmoothieMap<K, V> map, Segment<K, V> segment,
                    byte controlByte, int dataByte) {
                this.controlByte = controlByte;
                this.dataByte = (byte) dataByte;
                this.allocIndex = allocIndex(dataByte);
                this.hashDataBits = debugExtractHashDataBits(dataByte);
                this.key = (K) U.getObject(segment, allocKeyOffset(allocIndex));
                if (key != null) {
                    hash = map.keyHashCode(key);
                    hashDataBitsMatch = matchHashDataBits(dataByte, hash);
                    restoredFullControlByte = (byte) hashControlBits(hash);
                } else {
                    hash = 0L;
                    hashDataBitsMatch = false;
                    restoredFullControlByte = 0;
                }
                this.value = (V) U.getObject(segment, allocValueOffset(allocIndex));
            }

            @SuppressWarnings("AutoBoxing")
            @Override
            public String toString() {
                return String.format("%8s,%2d,%" + HASH_DATA_BITS + "s,%5s,%8s,%s",
                        Integer.toBinaryString(Byte.toUnsignedInt(controlByte)),
                        allocIndex, Integer.toBinaryString(hashDataBits), hashDataBitsMatch,
                        Integer.toBinaryString(Byte.toUnsignedInt(restoredFullControlByte)), key);
            }
        }

        //endregion

        static final long ALLOC_OFFSET;
        private static final long ALLOC_VALUE_OFFSET;

        /** `* 2` because there are two objects, key and value, that contribute to the scale. */
        private static final int ALLOC_INDEX_SHIFT =
                Integer.numberOfTrailingZeros(ARRAY_OBJECT_INDEX_SCALE * 2);

        static {
            try {
                // TODO Excelsior JET: ensure it works with Excelsior JET's negative object field
                //  offsets, see https://www.excelsiorjet.com/blog/articles/unsafe-harbor/
                ALLOC_OFFSET = min(U.objectFieldOffset(Segment.class.getDeclaredField("k0")),
                        U.objectFieldOffset(Segment.class.getDeclaredField("v0")));
                ALLOC_VALUE_OFFSET = ALLOC_OFFSET + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }

        private static long allocKeyOffset(int allocIndex) {
            return ALLOC_OFFSET + (long) (allocIndex << ALLOC_INDEX_SHIFT);
        }

        private static long allocValueOffset(int allocIndex) {
            return ALLOC_VALUE_OFFSET + (long) (allocIndex << ALLOC_INDEX_SHIFT);
        }

        @SuppressWarnings("unused")
        Object k0, v0;
    }

    /**
     * InflatedSegment inherits {@link Segment} and therefore {@link HashTableArea} which is unused
     * in InflatedSegment. This is done so that on the hot point access path checking whether the
     * segment being queried is inflated or not can be done _after_ unsuccessful key search in the
     * hash table (in the first accessed control group), not before it.
     * See [InflatedSegment checking after unsuccessful key search].
     */
    static class InflatedSegment<K, V> extends Segment<K, V> {
        /**
         * Allows to store {@link #SEGMENT_MAX_ALLOC_CAPACITY} + 1 entries without a rehash,
         * assuming that {@link #INFLATED_SEGMENT_DELEGATE_HASH_MAP_LOAD_FACTOR} is also used.
         */
        private static final int INFLATED_SEGMENT_DELEGATE_HASH_MAP_INITIAL_CAPACITY = 64;
        /**
         * The default HashMap's load factor of 0.75 would trigger a rehash at the insertion of the
         * 49th entry, i. e. right away after the inflation.
         */
        private static final float INFLATED_SEGMENT_DELEGATE_HASH_MAP_LOAD_FACTOR = 0.8f;

        private final HashMap<Node<K, V>, Node<K, V>> delegate;

        /* if Tracking hashCodeDistribution */
        /**
         * TODO implement optimization of not calling into {@link
         *  HashCodeDistribution#checkAndReportTooLargeInflatedSegment} if the SmoothieMap's size
         *  not decreased since the previous check call, but the size of this inflated segment
         *  decreased. This is a likely case because InflatedSegments have a statistical tendency to
         *  reduce in size, see a comment for {@link #MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_DEFLATION}.
         */
        private long checkAndReportIfTooLarge_lastCall_smoothieSize;
        private int checkAndReportIfTooLarge_lastCall_delegateSize;
        /* endif */

        InflatedSegment(int segmentOrder, long smoothieMapSize) {
            bitSetAndState = makeInflatedBitSetAndState(segmentOrder);
            setAllControlSlotsFromGroup(INFLATED_SEGMENT_MARKER_CONTROLS_GROUP);
            delegate = new HashMap<>(INFLATED_SEGMENT_DELEGATE_HASH_MAP_INITIAL_CAPACITY,
                    INFLATED_SEGMENT_DELEGATE_HASH_MAP_LOAD_FACTOR);
            /* if Tracking hashCodeDistribution */
            checkAndReportIfTooLarge_lastCall_smoothieSize = smoothieMapSize;
            checkAndReportIfTooLarge_lastCall_delegateSize = 0;
            /* endif */
            // [Safe segment publication]. Unnecessary on Hotspot because we are writing a final
            // field delegate, see
            // https://shipilev.net/blog/2014/safe-public-construction/#_safe_initialization.
            U.storeFence();
        }

        /** Constructor to be used in {@link #clone}. */
        private InflatedSegment(long bitSetAndState, HashMap<Node<K, V>, Node<K, V>> delegate
                /* if Tracking hashCodeDistribution */,
                long checkAndReportIfTooLarge_lastCall_smoothieSize,
                int checkAndReportIfTooLarge_lastCall_delegateSize/* endif */) {
            this.bitSetAndState = bitSetAndState;
            setAllControlSlotsFromGroup(INFLATED_SEGMENT_MARKER_CONTROLS_GROUP);
            this.delegate = delegate;
            /* if Tracking hashCodeDistribution */
            this.checkAndReportIfTooLarge_lastCall_smoothieSize =
                    checkAndReportIfTooLarge_lastCall_smoothieSize;
            this.checkAndReportIfTooLarge_lastCall_delegateSize =
                    checkAndReportIfTooLarge_lastCall_delegateSize;
            /* endif */
        }

        Iterable<? extends KeyValue<K, V>> getEntries() {
            return delegate.keySet();
        }

        @Nullable V get(SmoothieMap<K, V> smoothie, Object key, long hash) {
            return smoothie.getInflatedSegmentQueryContext().get(delegate, key, hash);
        }

        @Nullable K getInternalKey(SmoothieMap<K, V> smoothie, Object key, long hash) {
            return smoothie.getInflatedSegmentQueryContext().getInternalKey(delegate, key, hash);
        }

        @Nullable V computeIfPresent(SmoothieMap<K, V> smoothie,
                K key, long hash, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            @Nullable Object computeIfPresentResult =
                    context.computeIfPresent(delegate, key, hash, remappingFunction);
            //noinspection ObjectEquality: identity comparison is intended
            boolean entryRemoved = computeIfPresentResult == COMPUTE_IF_PRESENT_ENTRY_REMOVED;
            if (!entryRemoved) { // [Positive likely branch]
                //noinspection unchecked
                return (@Nullable V) computeIfPresentResult;
            } else {
                onEntryRemoval(smoothie, hash, delegate);
                return null;
            }
        }

        private void onEntryRemoval(SmoothieMap<K, V> smoothie, long hash,
                HashMap<Node<K, V>, Node<K, V>> delegate) {
            // Segment structure modification only after entry structure modification: can call
            // deflateSmall() only if there was a structural modification to the delegate map (an
            // entry removal) to adhere to the HashMap's contract on updating modCount only when
            // structural modifications happen to a map, which, unfortunately, permits patterns such
            // as calling to computeIfPresent() within an iteration loop over the same map. See
            // also [Don't increment SmoothieMap's modCount on non-structural modification].
            if (shouldDeflate(delegate.size())) {
                smoothie.deflateSmall(hash, this);
            } else if (shouldBeSplit(smoothie, segmentOrder(bitSetAndState)) < 0) {
                smoothie.splitInflated(hash, this);
            }
        }

        private static boolean shouldDeflate(int delegateSize) {
            return delegateSize <=
                    SEGMENT_MAX_ALLOC_CAPACITY - MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_DEFLATION;
        }

        /**
         * Checks whether this inflated segment should be split (see {@link #splitInflated}) because
         * it's not anymore an outlier in the SmoothieMap. Returns a negative number if this segment
         * should be split, or the latest computed (non-negative) {@link #computeAverageSegmentOrder}
         * value, if this segment shouldn't be split. In the latter case it's guaranteed that the
         * order of this segment is not less than the returned average segment order in the map.
         * (Note that it might be less than the average segment order plus {@link
         * #MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE} if {@link #segmentsArray}'s length is already
         * equal to {@link #MAX_SEGMENTS_ARRAY_LENGTH}.)
         *
         * This invariant is used in {@link #checkAndReportIfTooLarge} which should always be called
         * after shouldBeSplit() and this is also the reason why this method returns an int encoding
         * an average segment order rather than just a boolean.
         *
         * This method follows the [Negative integer return contract] principle.
         */
        private int shouldBeSplit(SmoothieMap<K, V> smoothie, int segmentOrder) {
            int lastComputedAverageSegmentOrder = (int) smoothie.lastComputedAverageSegmentOrder;
            // If this inflated segment's order is equal to MAX_SEGMENTS_ARRAY_ORDER it can't be
            // split anyway, even if the average segment order is equal to
            // MAX_SEGMENTS_ARRAY_ORDER - 1 or MAX_SEGMENTS_ARRAY_ORDER.
            //
            // The alternative to having this check as a separate branch is to compute
            // bound = min(MAX_SEGMENTS_ARRAY_ORDER,
            //     lastComputedAverageSegmentOrder + MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE)
            // and to have `segmentOrder < bound` as the outer check in the branch below. However,
            // since `segmentOrder != MAX_SEGMENTS_ARRAY_ORDER` should be perfectly predicted in all
            // target cases (the maximum optimization of throughput when the average segment order
            // approaches MAX_SEGMENTS_ARRAY_ORDER is not a SmoothieMap's target), having a separate
            // check should be faster. TODO verify
            if (segmentOrder != MAX_SEGMENTS_ARRAY_ORDER) { // [Positive likely branch]
                // The first check `segmentOrder <= maxSplittableSegmentOrder` allows to avoid
                // a relatively expensive computeAverageSegmentOrder() call on each structural
                // mutation access to an inflated segment, the second (nested) check is definitive.
                //
                // Average segment order is computed (and therefore lastComputedAverageSegmentOrder
                // is updated) every time before a segment is split in a SmoothieMap in
                // makeSpaceAndInsert(). Inflated segments normally start to appear only in large
                // SmoothieMaps which have a lot of segments, so when a SmoothieMap grows and the
                // average segment order increases, segments are expected to be split frequently (in
                // comparison to the size of the map and, therefore, the frequency of updates of any
                // single given inflated segment) in the process. This makes unlikely that segments
                // will stay inflated needlessly for long time and are not deflated because of this
                // optimizing pre-check, when lastComputedAverageSegmentOrder becomes stale. If a
                // SmoothieMap is so large that all segments are inflated and no splits occur (and,
                // therefore, lastComputedAverageSegmentOrder is not updated), or is in a shrinking
                // phase after growing that large, shouldBeSplit() and splitInflated() are
                // irrelevant. In the latter case the inflated segment is expected to deflate
                // eventually via deflateSmall().
                //
                // This is an unlikely branch, but can't follow the [Positive likely branch]
                // principle here because of lastComputedAverageSegmentOrder's update and the
                // fall-through logic.
                if (segmentOrder <= maxSplittableSegmentOrder(lastComputedAverageSegmentOrder)) {
                    lastComputedAverageSegmentOrder =
                            smoothie.computeAverageSegmentOrder(smoothie.size);
                    int maxSplittableSegmentOrder =
                            maxSplittableSegmentOrder(lastComputedAverageSegmentOrder);
                    if (segmentOrder <= maxSplittableSegmentOrder) {
                        return -1; // should be split
                    }
                    // Intentional fall-through to `return lastComputedAverageSegmentOrder;`
                }
            }
            return lastComputedAverageSegmentOrder;
        }

        /**
         * @return a value removed or replaced in the map, or null if no change was made. If
         * matchValue is null, the returned value is the internal value removed or replaced in the
         * map. If matchValue is non-null, the returned value could be the internal value _or_ the
         * matchValue itself.
         */
        @Nullable V replace(SmoothieMap<K, V> smoothie, K key, long hash, @Nullable V matchValue,
                @Nullable V replacementValue) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            @Nullable V removedOrReplacedVal;
            if (matchValue == null) {
                if (replacementValue == null) {
                    removedOrReplacedVal = context.remove(delegate, key, hash);
                } else {
                    removedOrReplacedVal = context.replace(delegate, key, hash, replacementValue);
                }
            } else {
                boolean removedOrReplaced = context.removeOrReplaceEntry(
                        delegate, key, hash, matchValue, replacementValue);
                removedOrReplacedVal = removedOrReplaced ? matchValue : null;
            }
            boolean entryRemoved = replacementValue == null && removedOrReplacedVal != null;
            if (entryRemoved) {
                onEntryRemoval(smoothie, hash, delegate);
            }
            return removedOrReplacedVal;
        }

        @Nullable V put(
                SmoothieMap<K, V> smoothie, K key, long hash, V value, boolean onlyIfAbsent) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            @Nullable V replacedOrInternalVal =
                    context.put(delegate, key, hash, value, onlyIfAbsent);
            boolean entryInserted = replacedOrInternalVal == null;
            if (entryInserted) {
                onEntryInsertion(smoothie, hash
                        /* if Tracking hashCodeDistribution */, key, delegate/* endif */);
            }
            return replacedOrInternalVal;
        }

        private void onEntryInsertion(SmoothieMap<K, V> smoothie, long hash
                /* if Tracking hashCodeDistribution */, K key,
                HashMap<Node<K, V>, Node<K, V>> delegate/* endif */) {
            // [Segment structure modification only after entry structure modification]
            int segmentOrder = segmentOrder(bitSetAndState);
            int segmentShouldBeSplitIfNegativeOrAverageSegmentOrder =
                    shouldBeSplit(smoothie, segmentOrder);
            if (segmentShouldBeSplitIfNegativeOrAverageSegmentOrder < 0) {
                smoothie.splitInflated(hash, this);
            }
            /* if Tracking hashCodeDistribution */
            else {
                @Nullable HashCodeDistribution<K, V> hashCodeDistribution =
                        smoothie.hashCodeDistribution;
                // TODO hashCodeDistribution null check: might need to be removed, if
                //  hashCodeDistribution-tracking SmoothieMap is a different implementation
                //  which has it always non-null. (But properly then it should be inlined into
                //  SmoothieMap altogether.)
                if (hashCodeDistribution != null) {
                    checkAndReportIfTooLarge(hashCodeDistribution, segmentOrder, smoothie,
                            delegate.size(), key,
                            segmentShouldBeSplitIfNegativeOrAverageSegmentOrder);
                }
            }
            /* endif */
        }

        /**
         * The main difference from {@link #put} is that {@link #onEntryInsertion}, and therefore
         * {@link #checkAndReportIfTooLarge} is not called from this method. When a segment is
         * inflated, we want to report that it's too large only after the inflation is complete.
         */
        void putDuringInflation(SmoothieMap<K, V> smoothie, K key, long hash, V value) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            context.putDuringInflation(this.delegate, key, hash, value);
        }

        @Nullable V computeIfAbsent(SmoothieMap<K, V> smoothie, K key, long hash,
                Function<? super K, ? extends V> mappingFunction) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            Node<K, V> nodeWithKeyAndHash = context.getNodeForKey(key, hash);
            @Nullable Node<K, V> internalNode =
                    context.computeIfAbsent(delegate, nodeWithKeyAndHash, mappingFunction);
            if (internalNode != null) {
                // If internalNode is not identical to nodeWithKeyAndHash it means that an entry
                // already existed in the delegate HashMap with it. mappingFunction wan't executed,
                // internalNode was just returned from context.computeIfAbsent().
                //noinspection ObjectEquality: identity comparision is intended
                boolean entryInserted = internalNode == nodeWithKeyAndHash;
                if (entryInserted) {
                    onEntryInsertion(smoothie, hash
                            /* if Tracking hashCodeDistribution */, key, delegate/* endif */);
                }
                return internalNode.getValue();
            } else {
                return null;
            }
        }

        @Nullable V compute(SmoothieMap<K, V> smoothie,
                K key, long hash, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            Node<K, V> nodeWithKeyAndHash = context.getNodeForKey(key, hash);
            @Nullable Node<K, V> internalNode =
                    context.compute(delegate, nodeWithKeyAndHash, remappingFunction);
            if (internalNode != null) {
                // See the Javadoc comment for InflatedSegmentQueryContext.compute() for
                // explanations of why the expression below means that an entry was inserted into
                // the delegate map.
                //noinspection ObjectEquality: identity comparision is intended
                boolean entryInserted = internalNode == nodeWithKeyAndHash;
                // [Segment structure modification only after entry structure modification]
                if (entryInserted) {
                    onEntryInsertion(smoothie, hash
                            /* if Tracking hashCodeDistribution */, key, delegate/* endif */);
                }
                return internalNode.getValue();
            } else {
                // See the Javadoc comment for InflatedSegmentQueryContext.compute() for
                // explanations of why the expression below means that an entry was removed from the
                // delegate map.
                boolean entryRemoved = nodeWithKeyAndHash.clearKeyIfNonNull();
                if (entryRemoved) {
                    onEntryRemoval(smoothie, hash, delegate);
                }
                return null;
            }
        }

        @Nullable V merge(SmoothieMap<K, V> smoothie, K key, long hash, V value,
                BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            InflatedSegmentQueryContext<K, V> context = smoothie.getInflatedSegmentQueryContext();
            HashMap<Node<K, V>, Node<K, V>> delegate = this.delegate;
            Node<K, V> nodeWithKeyAndHash = context.getNodeForKey(key, hash);
            @Nullable Node<K, V> internalNode =
                    context.merge(delegate, nodeWithKeyAndHash, value, remappingFunction);
            if (internalNode != null) {
                // If internalNode is not identical to nodeWithKeyAndHash it means that an entry
                // already existed in the delegate HashMap with it, in other words, no entry
                // insertion have happened to the delegate map.
                //noinspection ObjectEquality: identity comparision is intended
                boolean entryInserted = internalNode == nodeWithKeyAndHash;
                if (entryInserted) {
                    onEntryInsertion(smoothie, hash
                            /* if Tracking hashCodeDistribution */, key, delegate/* endif */);
                }
                return internalNode.getValue();
            } else {
                // If internalNode, it means that an entry removal have happened to the delegate
                // map, according to the contract of InflatedSegmentQueryContext.merge().
                onEntryRemoval(smoothie, hash, delegate);
                return null;
            }
        }

        /* if Tracking hashCodeDistribution */
        private void checkAndReportIfTooLarge(HashCodeDistribution<K, V> hashCodeDistribution,
                int segmentOrder, SmoothieMap<K, V> smoothie, int delegateSize, K excludedKey,
                int averageSegmentOrder) {
            if (!hashCodeDistribution.isReportingTooLargeInflatedSegment()) {
                return;
            }
            long smoothieSize = smoothie.size;
            // TODO should use `|` instead of `||`?
            if (smoothieSize < checkAndReportIfTooLarge_lastCall_smoothieSize ||
                    delegateSize > checkAndReportIfTooLarge_lastCall_delegateSize) {
                hashCodeDistribution.checkAndReportTooLargeInflatedSegment(
                        segmentOrder, this, smoothieSize, smoothie, delegateSize, excludedKey);

            }
            checkAndReportIfTooLarge_lastCall_smoothieSize = smoothieSize;
            checkAndReportIfTooLarge_lastCall_delegateSize = delegateSize;
        }
        /* endif */

        @DoNotCall
        @Deprecated
        @Override
        final void copyHashTableFrom(Object oldSegment) {
            throw new UnsupportedOperationException();
        }

        @DoNotCall
        @Deprecated
        @Override
        final void copyAllocAreaFrom(Object oldSegment, int oldSegmentCapacity) {
            throw new UnsupportedOperationException();
        }

        /**
         * Not calling super.clone() in order to be able to keep {@link #delegate} field final.
         * Also, {@link Segment#clone()} would needlessly clone many nulls in this inflated
         * segment's alloc area.
         */
        @SuppressWarnings("MethodDoesntCallSuperMethod")
        @Override
        public InflatedSegment<K, V> clone() {
            @SuppressWarnings("unchecked")
            HashMap<Node<K, V>,
                    Node<K, V>> delegateClone =
                    (HashMap<Node<K, V>,
                            Node<K, V>>) delegate.clone();
            return new InflatedSegment<>(bitSetAndState, delegateClone
                    /* if Tracking hashCodeDistribution */,
                    checkAndReportIfTooLarge_lastCall_smoothieSize,
                    checkAndReportIfTooLarge_lastCall_delegateSize/* endif */);
        }

        @Override
        boolean clear(int segmentIndex, SmoothieMap<K, V> map) {
            int delegateSize = delegate.size();
            if (delegateSize > 0) {
                // Increments modCount, as required by the contract of clear().
                map.replaceInflatedWithEmptyOrdinary(segmentIndex, this);
                map.size -= (long) delegateSize;
                return true;
            } else {
                return false;
            }
        }

        @Override
        int hashCode(SmoothieMap<K, V> map) {
            int h = 0;
            for (Node<K, V> node : delegate.keySet()) {
                // Note: not using `node.hashCode()` here because although the current
                // implementation happens to be the same, Node.hashCode() is not bound to be the
                // same in theory.
                int keyHashCode = longKeyHashCodeToIntHashCode(node.hash);
                h += keyHashCode ^ map.valueHashCode(node.getValue());
            }
            return h;
        }

        @Override
        void forEach(BiConsumer<? super K, ? super V> action) {
            delegate.keySet().forEach(node -> action.accept(node.getKey(), node.getValue()));
        }

        @Override
        void aggregateStats(SmoothieMap<K, V> map, OrdinarySegmentStats ordinarySegmentStats) {
            throw new IllegalStateException("must not be called on an inflated segment");
        }

        @Override
        String debugToString() {
            return "InflatedSegment: " + delegate.toString();
        }

        @Override
        @Nullable DebugHashTableSlot<K, V>[] debugHashTable(SmoothieMap<K, V> map) {
            throw new IllegalStateException("must not be called on an inflated segment");
        }
    }

    //region Segment17..Segment48 classes

    private static final String segmentClassNameBase;
    static {
        String segment17ClassName = Segment17.class.getName();
        segmentClassNameBase = segment17ClassName.substring(0, segment17ClassName.length() - 2);
    }

    /**
     * Lazily initialized cache, containing classes from {@link Segment17} to {@link Segment48},
     * accessible by index. The cache is not initialized eagerly because it's quite likely that
     * only {@link Segment48} and, maybe, another one "intermediate" sized segment class are
     * used.
     */
    private static final Class[] segmentClassCache = new Class[SEGMENT_MAX_ALLOC_CAPACITY + 1];

    @SuppressWarnings("unchecked")
    private static <K, V> Class<? extends Segment<K, V>> acquireClass(int allocCapacity) {
        Class segmentClass = segmentClassCache[allocCapacity];
        if (segmentClass != null)
            return segmentClass;
        return loadAndCacheSegmentClass(allocCapacity);
    }

    private static Class loadAndCacheSegmentClass(int allocCapacity) {
        try {
            String segmentClassName = segmentClassNameBase + allocCapacity;
            Class<?> segmentClass = Class.forName(segmentClassName);
            segmentClassCache[allocCapacity] = segmentClass;
            return segmentClass;
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * See {@link Segment} javadoc for more info about the following classes
     */
    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment17<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16;
    }

    /**
     * Don't create a single long extension chain (e. g. {@link Segment18} extends {@link
     * Segment17}, {@link Segment19} extends {@link Segment20}, etc), because it might cause more
     * classes to be loaded (e. g. if only {@link Segment48} is ever used, that happens if {@link
     * OptimizationObjective#ALLOCATION_RATE} is used and {@link SmoothieMap#shrinkAndTrimToSize()}
     * is never called), and also might be more troublesome for some GC heap traversal
     * implementations (?)
     */
    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment18<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment19<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment20<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
    }


    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment21<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment22<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment23<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment24<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment25<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment26<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment27<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment28<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment29<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment30<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment31<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment32<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment33<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment34<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment35<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment36<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment37<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment38<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment39<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment40<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment41<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment42<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment43<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment44<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42, k43, v43;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment45<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment46<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment47<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45, k46, v46;
    }

    @SuppressWarnings({"unused", "NullableProblems"})
    static class Segment48<K, V> extends Segment<K, V> {
        Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45, k46, v46, k47, v47;
    }

    //endregion
}
