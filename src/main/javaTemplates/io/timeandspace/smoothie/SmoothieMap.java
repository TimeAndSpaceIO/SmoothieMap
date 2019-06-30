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
import io.timeandspace.smoothie.InterleavedSegments.FullCapacitySegment;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.BitSetAndState.BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE;
import static io.timeandspace.smoothie.BitSetAndState.SEGMENT_ORDER_UNIT;
import static io.timeandspace.smoothie.BitSetAndState.allocCapacity;
import static io.timeandspace.smoothie.BitSetAndState.clearAllocBit;
import static io.timeandspace.smoothie.BitSetAndState.clearBitSet;
import static io.timeandspace.smoothie.BitSetAndState.extractBitSetForIteration;
import static io.timeandspace.smoothie.BitSetAndState.freeAllocIndexClosestTo;
import static io.timeandspace.smoothie.BitSetAndState.incrementSegmentOrder;
/* if Interleaved segments Supported intermediateSegments */
import static io.timeandspace.smoothie.BitSetAndState.isFullCapacity;
/* endif */
import static io.timeandspace.smoothie.BitSetAndState.isInflatedBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.lowestFreeAllocIndex;
import static io.timeandspace.smoothie.BitSetAndState.makeBitSetAndStateForPrivatelyPopulatedContinuousSegment;
import static io.timeandspace.smoothie.BitSetAndState.makeInflatedBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.makeNewBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.segmentOrder;
import static io.timeandspace.smoothie.BitSetAndState.segmentSize;
import static io.timeandspace.smoothie.BitSetAndState.setAllocBit;
import static io.timeandspace.smoothie.BitSetAndState.setLowestAllocBit;
// comment // [if-elif Continuous|Interleaved segments templating] /**/
/* if Continuous segments */
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.getBitSetAndState;
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme;
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.setBitSetAndState;
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.setBitSetAndStateAfterBulkOperation;
/* elif Interleaved segments //
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.getBitSetAndState;
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme;
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.setBitSetAndState;
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.setBitSetAndStateAfterBulkOperation;
// endif */
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS_MASK;
import static io.timeandspace.smoothie.HashTable.matchFull;
import static io.timeandspace.smoothie.HashTable.setSlotEmpty;
/* if Continuous segments //
import static io.timeandspace.smoothie.ContinuousSegments.allocateNewSegmentWithoutSettingBitSetAndSet;
import static io.timeandspace.smoothie.ContinuousSegments.createNewSegment;
import static io.timeandspace.smoothie.ContinuousSegments.grow;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.readTagGroup;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.readDataGroup;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.writeDataGroup;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.writeTagAndData;
import static io.timeandspace.smoothie.ContinuousSegments.SegmentBase.allocOffset;
// elif Interleaved segments */
import static io.timeandspace.smoothie.InterleavedSegments.allocIndexBoundaryForLocalAllocation;
import static io.timeandspace.smoothie.InterleavedSegments.allocOffset;
import static io.timeandspace.smoothie.InterleavedSegments.allocateNewSegmentWithoutSettingBitSetAndSet;
import static io.timeandspace.smoothie.InterleavedSegments.createNewSegment;
import static io.timeandspace.smoothie.InterleavedSegments.grow;
import static io.timeandspace.smoothie.InterleavedSegments.dataGroupFromTagGroupOffset;
import static io.timeandspace.smoothie.InterleavedSegments.dataGroupOffset;
import static io.timeandspace.smoothie.InterleavedSegments.tagGroupOffset;
import static io.timeandspace.smoothie.InterleavedSegments.readDataGroupAtOffset;
import static io.timeandspace.smoothie.InterleavedSegments.readTagGroupAtOffset;
import static io.timeandspace.smoothie.InterleavedSegments.writeDataGroupAtOffset;
import static io.timeandspace.smoothie.InterleavedSegments.writeTagAndData;
/* endif */
import static io.timeandspace.smoothie.HashTable.INFLATED_SEGMENT__MARKER_DATA_GROUP;
import static io.timeandspace.smoothie.HashTable.matchEmpty;
import static io.timeandspace.smoothie.HashTable.addGroupIndex;
import static io.timeandspace.smoothie.HashTable.extractAllocIndex;
import static io.timeandspace.smoothie.HashTable.firstAllocIndex;
import static io.timeandspace.smoothie.HashTable.lowestMatchingSlotIndex;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_SLOTS;
import static io.timeandspace.smoothie.HashTable.baseGroupIndex;
import static io.timeandspace.smoothie.HashTable.makeData;
import static io.timeandspace.smoothie.HashTable.match;
import static io.timeandspace.smoothie.HashTable.shouldStopProbing;
import static io.timeandspace.smoothie.InflatedSegmentQueryContext.COMPUTE_IF_PRESENT_ENTRY_REMOVED;
import static io.timeandspace.smoothie.InflatedSegmentQueryContext.Node;
import static io.timeandspace.smoothie.LongMath.clearLowestNBits;
import static io.timeandspace.smoothie.LongMath.clearLowestSetBit;
import static io.timeandspace.smoothie.OutboundOverflowCounts.addOutboundOverflowCountsPerGroup;
import static io.timeandspace.smoothie.OutboundOverflowCounts.computeOutboundOverflowCount_perGroupChanges;
import static io.timeandspace.smoothie.OutboundOverflowCounts.decrementOutboundOverflowCountsPerGroup;
import static io.timeandspace.smoothie.OutboundOverflowCounts.incrementOutboundOverflowCountsPerGroup;
import static io.timeandspace.smoothie.OutboundOverflowCounts.outboundOverflowCount_groupForChange;
import static io.timeandspace.smoothie.OutboundOverflowCounts.outboundOverflowCount_markGroupForChange;
import static io.timeandspace.smoothie.OutboundOverflowCounts.subtractOutboundOverflowCountsPerGroupAndUpdateAllGroups;
import static io.timeandspace.smoothie.Segments.valueOffsetFromAllocOffset;
import static io.timeandspace.smoothie.SmoothieMap.Segment.HASH__BASE_GROUP_INDEX_BITS;
import static io.timeandspace.smoothie.SmoothieMap.Segment.TAG_HASH_BITS;
import static io.timeandspace.smoothie.SmoothieMap.Segment.MIN_LEFTOVER_ALLOC_CAPACITY_AFTER_SHRINKING;
import static io.timeandspace.smoothie.SmoothieMap.Segment.checkAllocIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.eraseKeyAndValueAtOffset;
import static io.timeandspace.smoothie.SmoothieMap.Segment.tagBits;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readKeyAtOffset;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readKeyCheckedAtIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readValueAtOffset;
import static io.timeandspace.smoothie.SmoothieMap.Segment.readValueCheckedAtIndex;
import static io.timeandspace.smoothie.SmoothieMap.Segment.writeEntry;
import static io.timeandspace.smoothie.SmoothieMap.Segment.writeValueAtOffset;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SHIFT;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.Utils.checkNonNull;
import static io.timeandspace.smoothie.Utils.rethrowUnchecked;
import static io.timeandspace.smoothie.Utils.verifyEqual;
import static io.timeandspace.smoothie.Utils.verifyIsPowerOfTwo;
import static io.timeandspace.smoothie.Utils.verifyNonNull;
import static io.timeandspace.smoothie.Utils.verifyThat;
import static java.lang.Integer.numberOfTrailingZeros;
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
     *  - inlined Utils.checkNonNull in {@link #compute} and similar methods
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

    static final int SEGMENT_MAX_ALLOC_CAPACITY = 48;
    /** {@link #SEGMENT_MAX_ALLOC_CAPACITY} = 48 = 16 * 3 */
    static final int MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE = 16;
    @CompileTimeConstant
    static final int MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT = 4;

    static {
        verifyIsPowerOfTwo(MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE, "");
        verifyEqual(MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT,
                numberOfTrailingZeros(MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE));
    }

    /**
     * Using 30 for {@link ContinuousSegments} because statistically it leads to lower expected
     * SmoothieMap's memory footprint. Using 32 for {@link InterleavedSegments} because it's only
     * very marginally worse than 30, but {@link InterleavedSegments.IntermediateCapacitySegment}
     * can be fully symmetric with 4 allocation slots surrounding each hash table group. Also,
     * the probability of calling {@link
     * FullCapacitySegment#swapContentsDuringSplit} is lower
     * (see [Swap segments] in {@link #split}) which is good because {@link
     * FullCapacitySegment#swapContentsDuringSplit} is relatively
     * more expensive than {@link
     * ContinuousSegments.SegmentBase#swapContentsDuringSplit} and allocates.
     *
     * TODO recompute and provide here exact expected memory footprint of a SmoothieMap in case of
     *  intermediate capacity = 30 and 32
     */
    static final int SEGMENT_INTERMEDIATE_ALLOC_CAPACITY =
            /* if Continuous segments */30/* elif Interleaved segments //32// endif */;

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
        // TODO simplify and move MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT
        //  inside the division, i. e.
        //  `>>> (33 + MAX_ALLOC_CAPACITY_POWER_OF_TWO_COMPONENT_SIZE_DIVISION_SHIFT)`
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
        //
        // Similar replacement of division is done in InterleavedSegments' allocOffset methods.
        int averageSegments = (int) ((segmentCapacityPowerOfTwoComponents * 2863311531L) >>> 33);
        return Integer.SIZE - Integer.numberOfLeadingZeros(averageSegments - 1);
    }

    /**
     * The order of any segment must not become more than {@link #computeAverageSegmentOrder(long)}
     * plus this value, and more than {@link #segmentsArray}'s order. If a segment has the maximum
     * allowed order and its size exceeds {@link #SEGMENT_MAX_ALLOC_CAPACITY}, it is inflated
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
        verifyThat(minExpectedSize >= 0);
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
        verifyIsPowerOfTwo(numSegments, "num segments");
        return numberOfTrailingZeros(numSegments);
    }

    /**
     * The number of the lowest bit in hash codes that is used (along with all the higher bits) to
     * locate a segment (in {@link #segmentsArray}) for a key (see {@link #segmentByHash}).
     *
     * The lowest {@link Segment#HASH__BASE_GROUP_INDEX_BITS} bits are used to locate the
     * first lookup group within a segment, the following {@link Segment#TAG_HASH_BITS} bits are
     * stored in the tag groups.
     */
    static final int SEGMENT_LOOKUP_HASH_SHIFT = HASH__BASE_GROUP_INDEX_BITS + TAG_HASH_BITS;

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
        MOD_COUNT_FIELD_OFFSET = UnsafeUtils.getFieldOffset(SmoothieMap.class, "modCount");
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

    /** @deprecated in order not to forget to remove calls from production code */
    @Deprecated
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
            segmentsArray[i] = createNewSegment(segmentAllocCapacity, segmentsOrder);
        }
        duplicateSegments(segmentsArray, numCreatedSegments);
        this.segmentsArray = segmentsArray;
        /* if Tracking segmentOrderStats */
        addToSegmentCountWithOrder(segmentsOrder, numCreatedSegments);
        /* endif */
        return segmentsArray;
    }

    private void updateSegmentLookupMask(int segmentsArrayLength) {
        verifyIsPowerOfTwo(segmentsArrayLength, "segments array length");
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
    private long segmentLookupBits(long hash) {
        return hash & segmentLookupMask;
    }

    /** TODO update Javadoc links to this method and remove it because it's not used in real code */
    @HotPath
    final Object segmentByHash(long hash) {
        // Reading segmentLookupMask before segmentsArray: it's critical here that segmentLookupMask
        // field is read (in segmentLookupBits()) before segmentsArray field (in
        // segmentBySegmentLookupBits()). This order provides a happens-before edge
        // (segmentLookupMask is volatile) with the code in growSegmentsArray() and thus guarantees
        // impossibility of an illegal (and crash- and memory corruption-prone because of Unsafe)
        // out of bounds access to an array if some thread accesses a SmoothieMap concurrently with
        // another thread growing it in growSegmentsArray().
        return segmentBySegmentLookupBits(segmentLookupBits(hash));
    }

    @HotPath
    private Object segmentBySegmentLookupBits(long hash_segmentLookupBits) {
        long segmentArrayOffset = hash_segmentLookupBits >> SEGMENT_ARRAY_OFFSET_HASH_SHIFT;
        @Nullable Object segmentsArray = this.segmentsArray;
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

    /* if Interleaved segments Supported intermediateSegments */
    @HotPath
    private int isFullCapacitySegment(long hash_segmentLookupBits, Object segment) {
        // TODO implement a bit set that allows to avoid accessing segment object's header on the
        //  hot path of point accesses.
        return segment instanceof FullCapacitySegment ? 1 : 0;
    }

    @AmortizedPerSegment
    private int isFullCapacitySegmentByIndex(int segmentIndex, Object segment) {
        // TODO implement a bit set that allows to avoid accessing segment object's header on the
        //  hot path of point accesses.
        return segment instanceof FullCapacitySegment ? 1 : 0;
    }
    /* endif */

    /** @deprecated in order not to forget to remove calls from production code */
    @Deprecated
    final int debugSegmentsArrayLength() {
        //noinspection ConstantConditions: suppress nullability warnings during debug
        return ((Object[]) segmentsArray).length;
    }

    /** @deprecated in order not to forget to remove calls from production code */
    @Deprecated
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

    private static int firstSegmentIndexByIndexAndOrder(@NonNegative int segmentIndex,
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
        // TODO specialize to avoid access into value's memory: see [Protecting null comparisons].
        return getInternalKey(key) != null;
    }

    @Override
    public final boolean containsEntry(Object key, Object value) {
        checkNonNull(value);
        @Nullable V internalVal = get(key);
        //noinspection ObjectEquality: identity comparision is intended
        boolean valuesIdentical = internalVal == value;
        // Avoiding `internalVal != null` check before valuesIdentical check for the same reason as
        // [Protecting null comparisons].
        return valuesIdentical || (internalVal != null && valuesEqual(value, internalVal));
    }

    @Override
    public final V getOrDefault(Object key, V defaultValue) {
        @Nullable V internalVal = get(key);
        // TODO specialize or implement get as getOrDefault(null) to avoid access into value's
        //  memory: see [Protecting null comparisons].
        return internalVal != null ? internalVal : defaultValue;
    }

    @CanIgnoreReturnValue
    @Override
    public final @Nullable V remove(Object key) {
        checkNonNull(key);

        long hash = keyHashCode(key);
        long hash_segmentLookupBits = segmentLookupBits(hash);
        Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        return removeImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, null);
    }

    @Override
    public final boolean remove(Object key, Object value) {
        checkNonNull(key);
        checkNonNull(value);

        long hash = keyHashCode(key);
        long hash_segmentLookupBits = segmentLookupBits(hash);
        Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        // TODO `== value` may be better than `!= null` (if the method also returns the
        //  corresponding object) for the same reason as [Protecting null comparisons]. Or, the
        //  method should be specialized.
        return removeImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, value) != null;
    }

    @Override
    public final V replace(K key, V value) {
        checkNonNull(key);
        checkNonNull(value);

        long hash = keyHashCode(key);
        long hash_segmentLookupBits = segmentLookupBits(hash);
        Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        return replaceImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, null, value);
    }

    @Override
    public final boolean replace(K key, V oldValue, V newValue) {
        checkNonNull(key);
        checkNonNull(oldValue);
        checkNonNull(newValue);

        long hash = keyHashCode(key);
        long hash_segmentLookupBits = segmentLookupBits(hash);
        Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        // TODO `== oldValue` may be better than `!= null` (if the method also returns the
        //  corresponding object) for the same reason as [Protecting null comparisons]. Or, the
        //  method should be specialized.
        return replaceImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, oldValue, newValue) != null;
    }

    @CanIgnoreReturnValue
    @Override
    public final @Nullable V put(K key, V value) {
        checkNonNull(key);
        checkNonNull(value);

        long hash = keyHashCode(key);
        long hash_segmentLookupBits = segmentLookupBits(hash);
        Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        return putImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, value, false /* onlyIfAbsent */);
    }

    @Override
    public final @Nullable V putIfAbsent(K key, V value) {
        checkNonNull(key);
        checkNonNull(value);

        long hash = keyHashCode(key);
        return internalPutIfAbsent(key, hash, value);
    }

    @HotPath
    private @Nullable V internalPutIfAbsent(K key, long hash, V value) {
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        return putImpl(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                key, hash, value, true /* onlyIfAbsent */);
    }

    //endregion

    //region Implementations of point access methods

    @Override
    public final @Nullable V get(Object key) {
        checkNonNull(key);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            // bitMask loop:
            // TODO compare with int-indexed loop with bitCount(bitMask) limit
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                long allocOffset = allocOffset(firstAllocIndex(dataGroup, bitMask)
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    return readValueAtOffset(segment, allocOffset);
                }
            }
            // Likelihood of this branch depends on SmoothieMap's use case.
            // TODO provide separate getLikelyPresent() and getLikelyAbsent() methods
            if (shouldStopProbing(dataGroup)) {
                return null;
            }
            // InflatedSegment checking after unsuccessful key search: the key search above was
            // destined to be unsuccessful in an inflated segment, but we don't check whether the
            // segment is inflated or not in the beginning to declutter the hot path as much as
            // possible. This is enabled by the fact that InflatedSegment inherits Segment and thus
            // has a hash table. See also the Javadoc for InflatedSegment.
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                // Quadratic probing:
                // The alternative to quadratic probing is double hashing: we can extract two bits
                // out of the key's hash and use (these_two_bits << 1) + 1 as the step in the
                // probing chain, like it is done in F14: https://github.com/facebook/folly/blob/
                // e988905d/folly/container/detail/F14Table.h#L1337-L1345.
                // Advantages of double hashing:
                //  - Less clustering (TODO evaluate)
                //  - No additional operations with the step on each probing loop iteration (like
                //  the following `groupIndexStep += 1` with quadratic probing).
                // Disadvantages:
                //  - A relatively expensive computation of the step value. This computation is
                //  likely to not be needed at all because the probing will likely finish at the
                //  first group. CPU probably won't be able to smear this computation in its
                //  pipeline during the first iteration because of the high register pressure the
                //  computed step value is pushed to the stack. There are two ways to avoid making
                //  this expensive computation on the first iteration of the probing loop:
                //   1) Unroll the first iteration of the probing loop. But it would increase the
                //   size and the complexity of the methods (both in computational terms, i. e.
                //   icache trashing, quality of compilation into machine code by JVM, branch
                //   predictability) considerably. This doesn't seem like a good deal.
                //   2) Recompute the step from hash value on each iteration of the probing loop.
                //   This approach is potentially viable since the chance that the probing chain
                //   will be longer than two groups (when the repetitive step computations will
                //   start to make the difference) is very low. TODO evaluate this approach
                //  - A simplified, no-action approach to handling missing opportunities of shifting
                //  back unnecessarily overflown entries during split() is not possible with double
                //  hashing because that approach relies on the quadratic probing scheme: see
                //  [fromSegment iteration].
                //
                // The maximum load factor in SmoothieMap is lower than in F14
                // (SEGMENT_MAX_ALLOC_CAPACITY / HASH_TABLE_SLOTS = 0.75 vs. 12/14 ~= 0.86). Also,
                // thanks to the "smoothness" of SmoothieMap there are no sizes when the whole map
                // is highly populated (but rather only a proportion of segments). These factors
                // are in favor of quadratic probing, although it's still not a clear win and proper
                // benchmarking and precise evaluation should be done. TODO compare the approaches.
                //
                // Double hashing can also allow measuring outbound overflow counts corresponding to
                // steps 1 and 3 counts and 5 and 7 in two 4-bit registers instead of measuring the
                // total outbound overflow count for a group in a single 8-bit register (see
                // ContinuousSegment_BitSetAndStateArea.outboundOverflowCountsPerGroup) which would
                // allow more granular breaking from the probing loop. TODO work out this idea
                groupIndexStep += 1;
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // Break from the loop when visited all groups in the hash table: this may happen
                // when every group in the hash table has outbound overflow count greater than 1,
                // yet many of the groups are empty enough (after removals from the segment) so that
                // the total number of them is less than SEGMENT_MAX_ALLOC_CAPACITY. This may
                // eventually happen in a segment after a period of entry insertions and removals
                // since no kind of "shift deletion" is performed upon removals. See also
                // https://github.com/facebook/folly/blob/e988905d/
                // folly/container/detail/F14Table.h#L1399-L1402.
                // TODO remove this condition in a specialized version of a Map that doesn't support
                //  removes
                if (groupIndex == baseGroupIndex) { // Unlikely branch
                    return null;
                }
            } else {
                return getInflated(segment, key, hash);
            }
        }
    }

    final int countCollisionKeyComparisons(Object segment, Object key, long hash) {
        verifyNonNull(segment);
        verifyNonNull(key);

        /* if Interleaved segments Supported intermediateSegments */
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        int numCollisionKeyComparisons = 0;

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            // [bitMask loop]
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                long allocOffset = allocOffset(firstAllocIndex(dataGroup, bitMask)
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    return numCollisionKeyComparisons;
                } else {
                    numCollisionKeyComparisons++;
                }
            }
            if (shouldStopProbing(dataGroup)) {
                throw new IllegalStateException("Expected the key to be in the map");
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [Break from the loop when visited all groups in the hash table]
                if (groupIndex == baseGroupIndex) {
                    throw new IllegalStateException("Expected the key to be in the map");
                }
            } else {
                throw new IllegalStateException("Expected an ordinary segment");
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
        checkNonNull(key);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                long allocOffset = allocOffset(firstAllocIndex(dataGroup, bitMask)
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    return internalKey;
                }
            }
            // Likelihood of this branch depends on SmoothieMap's use case.
            if (shouldStopProbing(dataGroup)) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [Break from the loop when visited all groups in the hash table]
                if (groupIndex == baseGroupIndex) { // Unlikely branch
                    return null;
                }
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
            K key, BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
        checkNonNull(key);
        checkNonNull(remappingFunction);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                // Inlined lowestMatchingSlotIndex: computing the number of trailing zeros directly
                // and then calling lowestMatchingSlotIndexFromTrailingZeros() is inlined
                // lowestMatchingSlotIndex(). It's inlined because the trailingZeros value is also
                // needed for extractAllocIndex().
                int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                long allocOffset = allocOffset(allocIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    V internalVal = readValueAtOffset(segment, allocOffset);
                    @Nullable V newValue = remappingFunction.apply(key, internalVal);
                    if (newValue != null) {
                        writeValueAtOffset(segment, allocOffset, internalVal);
                    } else {
                        // Computing outboundOverflowCount_perGroupDecrements in the end: not
                        // computing outboundOverflowCount_perGroupDecrements along with the key
                        // search loop as in remove() because it's considered generally unlikely
                        // that remappingFunction returns null, so avoiding premature computations
                        // which can turn out to be unnecessary. Since the majority of key search
                        // loops are expected to to have just one iteration, the main cost
                        // contribution of the remove()'s approach is establishing an extra variable
                        // outside of the loop which likely leads to a stack push because of very
                        // high register pressure in the method.
                        // TODO provide separate computeIfPresentLikelyRemove() method
                        long outboundOverflowCount_perGroupDecrements =
                                computeOutboundOverflowCount_perGroupChanges(
                                        baseGroupIndex, groupIndex);
                        // [Reusing local variable]
                        dataGroup = setSlotEmpty(dataGroup, trailingZeros);
                        removeAtSlot(hash, segment,
                                /* if Interleaved segments Supported intermediateSegments */
                                isFullCapacitySegment,/* endif */
                                outboundOverflowCount_perGroupDecrements, dataGroupOffset,
                                dataGroup, allocIndex, allocOffset);
                    }
                    return newValue;
                }
            }
            // Likelihood of this branch depends on SmoothieMap's use case.
            if (shouldStopProbing(dataGroup)) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [Break from the loop when visited all groups in the hash table]
                if (groupIndex == baseGroupIndex) { // Unlikely branch
                    return null;
                }
            } else {
                return computeIfPresentInflated(segment, key, hash, remappingFunction);
            }
        }
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeIfPresentInflated(Object segment,
            K key, long hash,
            BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.computeIfPresent(this, key, hash, remappingFunction);
    }

    /**
     * The common implementation for
     *  {@link #remove(Object)}: matchValue == null
     *  {@link #remove(Object, Object)}: matchValue != null
     *
     * @param matchValue if non-null the entry's value should be equal to matchValue for removal.
     * @return a value removed in the map, or null if no change was made. If matchValue is null the
     * returned value is the internal value removed in the map. If matchValue is non-null the
     * returned value could be either the internal value _or_ the matchValue itself.
     */
    private @Nullable V removeImpl(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            Object key, long hash, @Nullable Object matchValue) {
        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        // Compare with precomputed outboundOverflowCount_perGroupChanges approach:
        // [Precomputed outboundOverflowCount_perGroupChanges] might turn out to be more effective
        // so that [Computing outboundOverflowCount_perGroupDecrements in the end] should be used
        // instead of computing outboundOverflowCount_perGroupDecrements along with the key search
        // loop. TODO compare the approaches
        long outboundOverflowCount_perGroupDecrements = 0;
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                // [Inlined lowestMatchingSlotIndex]
                int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                long allocOffset = allocOffset(allocIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    V internalVal = readValueAtOffset(segment, allocOffset);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean valuesIdentical = internalVal == matchValue;
                    // Avoiding `matchValue == null` check before valuesIdentical check for the same
                    // reason as [Protecting null comparisons].
                    if (valuesIdentical || matchValue == null ||
                            valuesEqual(matchValue, internalVal)) {
                        // [Reusing local variable]
                        dataGroup = setSlotEmpty(dataGroup, trailingZeros);
                        removeAtSlot(hash, segment,
                                /* if Interleaved segments Supported intermediateSegments */
                                isFullCapacitySegment,/* endif */
                                outboundOverflowCount_perGroupDecrements, dataGroupOffset,
                                dataGroup, allocIndex, allocOffset);
                        return internalVal;
                    } else {
                        return null;
                    }
                }
            }
            // Likelihood of this branch depends on SmoothieMap's use case.
            if (shouldStopProbing(dataGroup)) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                outboundOverflowCount_perGroupDecrements = outboundOverflowCount_markGroupForChange(
                        outboundOverflowCount_perGroupDecrements, groupIndex);
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [Break from the loop when visited all groups in the hash table]
                if (groupIndex == baseGroupIndex) { // Unlikely branch
                    return null;
                }
            } else {
                return removeOrReplaceInflated(segment, key, hash, matchValue, null);
            }
        }
    }

    /**
     * The difference of this method from {@link #removeImpl} is that we know that the entry
     * corresponding to the given hash value must be present in the segment's hash table, otherwise
     * there should be some concurrent modification.
     *
     * @param allocIndexToRemove the alloc index of the entry being removed; should be matched
     *        instead of key and matchValue as in {@link #removeImpl}.
     */
    @SuppressWarnings("unused") // will be used in removing iterators which are not implemented yet
    private void removeDuringIterationFromOrdinarySegment(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long hash, long allocIndexToRemove) {
        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        // TODO [Compare with precomputed outboundOverflowCount_perGroupChanges approach]
        long outboundOverflowCount_perGroupDecrements = 0;
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                // [Inlined lowestMatchingSlotIndex]
                int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                if (allocIndex == allocIndexToRemove) {
                    dataGroup = setSlotEmpty(dataGroup, trailingZeros); // [Reusing local variable]
                    // An alternative to reading bitSetAndState here is reading it in advance,
                    // outside of the loop to avoid a data dependency stall before the call to
                    // removeAtSlotNoShrink(): the loop doesn't have any normal outcome other than
                    // removing an entry, so this read of bitSetAndState must be always useful.
                    // However, because of high register pressure the value is likely to be
                    // immediately pushed to stack and then has to be read from the stack. And the
                    // cache line containing segment's bitSetAndState should likely be already in L1
                    // anyway because bitSetAndState is read in the beginning of an iteration over
                    // a segment.
                    long bitSetAndState = getBitSetAndState(segment);
                    /* if Enabled extraChecks */
                    verifyThat(!isInflatedBitSetAndState(bitSetAndState));
                    /* endif */
                    long allocOffset = allocOffset(allocIndex
                            /* if Interleaved segments Supported intermediateSegments */
                            , (long) isFullCapacitySegment/* endif */);
                    // It's possible to implement shrinking during iteration, but it would be
                    // more complex than shrinking during ordinary removeAtSlot(), involving a
                    // procedure similar to compactEntriesDuringSegmentSwap().
                    // TODO implement shrinking during iteration
                    bitSetAndState = removeAtSlotNoShrink(bitSetAndState, segment,
                            /* if Interleaved segments Supported intermediateSegments */
                            isFullCapacitySegment,/* endif */
                            outboundOverflowCount_perGroupDecrements, dataGroupOffset, dataGroup,
                            allocIndex, allocOffset);
                    setBitSetAndState(segment, bitSetAndState);
                    return;
                }
            }
            if (shouldStopProbing(dataGroup)) {
                break; // to throwing ConcurrentModificationException
            }
            /* if Enabled extraChecks */
            verifyThat(dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP);
            /* endif */
            outboundOverflowCount_perGroupDecrements = outboundOverflowCount_markGroupForChange(
                    outboundOverflowCount_perGroupDecrements, groupIndex);
            groupIndexStep += 1; // [Quadratic probing]
            groupIndex = addGroupIndex(groupIndex, groupIndexStep);
            if (groupIndex == baseGroupIndex) {
                break; // to throwing ConcurrentModificationException
            }
        }
        // There is no hash table entry pointing to allocIndexToRemove with the same hash tag bits
        // as for the provided hash. It means some concurrent modification should be happening to
        // the segment.
        throw new ConcurrentModificationException();
    }

    /**
     * The common implementation for
     *  {@link #replace(Object, Object)}: matchValue == null
     *  {@link #replace(Object, Object, Object)}: matchValue != null
     *
     * @param matchValue if non-null the entry's value should be equal to matchValue for
     *        replacement.
     * @return a value replaced in the map, or null if no change was made. If matchValue is null the
     * returned value is the internal value replaced in the map. If matchValue is non-null the
     * returned value could be either the internal value _or_ the matchValue itself.
     */
    private @Nullable V replaceImpl(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            Object key, long hash, @Nullable Object matchValue,
            V replacementValue) {
        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
            long tagGroupOffset = tagGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
            long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                long allocIndex = firstAllocIndex(dataGroup, bitMask);
                long allocOffset = allocOffset(allocIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                K internalKey = readKeyAtOffset(segment, allocOffset);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    V internalVal = readValueAtOffset(segment, allocOffset);
                    //noinspection ObjectEquality: identity comparision is intended
                    boolean valuesIdentical = internalVal == matchValue;
                    // Avoiding `matchValue == null` check before valuesIdentical check for the same
                    // reason as [Protecting null comparisons].
                    if (valuesIdentical || matchValue == null ||
                            valuesEqual(matchValue, internalVal)) {
                        writeValueAtOffset(segment, allocOffset, replacementValue);
                        return internalVal;
                    } else {
                        return null;
                    }
                }
            }
            // Likelihood of this branch depends on SmoothieMap's use case.
            if (shouldStopProbing(dataGroup)) {
                return null;
            }
            // [InflatedSegment checking after unsuccessful key search]
            if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) { // [Positive likely branch]
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [Break from the loop when visited all groups in the hash table]
                if (groupIndex == baseGroupIndex) { // Unlikely branch
                    return null;
                }
            } else {
                return removeOrReplaceInflated(segment, key, hash, matchValue, replacementValue);
            }
        }
    }

    /**
     * [Shallow xxxInflated() methods]
     *
     * If the given replacementValue is null the method removes an entry from the inflated segment,
     * otherwise replaces the value corresponding to the key.
     *
     * @param matchValue if non-null the entry's value should be equal to matchValue for removal or
     *        replacement.
     * @return a value removed or replaced in the map, or null if no change was made. If matchValue
     * is null, the returned value is the internal value removed or replaced in the map. If
     * matchValue is non-null, the returned value could be the internal value _or_ the matchValue
     * itself.
     */
    @SuppressWarnings("unchecked")
    private @Nullable V removeOrReplaceInflated(Object segment, Object key, long hash,
            @Nullable Object matchValue, @Nullable V replacementValue) {
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.removeOrReplace(
                this, (K) key, hash, (V) matchValue, replacementValue);
    }

    @HotPath
    private @Nullable V putImpl(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            K key, long hash, V value, boolean onlyIfAbsent) {
        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        long groupIndex = baseGroupIndex;
        long dataGroup;
        long emptyBitMask;
        // TODO [Compare with precomputed outboundOverflowCount_perGroupChanges approach]
        long outboundOverflowCount_perGroupIncrements;
        toInsertNewEntry:
        {
            long groupIndexStep = 0;
            toFindEmptySlot:
            {
                keySearch:
                while (true) {
                    long tagGroupOffset = tagGroupOffset(groupIndex
                            /* if Interleaved segments Supported intermediateSegments */
                            , (long) isFullCapacitySegment/* endif */);
                    long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
                    long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
                    dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                    for (long bitMask = match(tagGroup, hashTagBits, dataGroup); ; ) {
                        // Positive likely branch: the following condition is in a separate if block
                        // rather than the loop condition (as in all other operations: find(),
                        // compute(), etc.) in order to make it positive and so it's more likely
                        // that JIT compiles the code with an assumption that this branch is taken
                        // (i. e. that bitMask is 0), that is what we really expect during Map.put()
                        // or putIfAbsent().
                        // TODO check bytecode output of javac
                        // TODO check if this even makes sense
                        // TODO check a different approach, with an unrolled check and then a
                        //  do-while
                        if (bitMask == 0) {
                            break;
                        }
                        long allocOffset = allocOffset(firstAllocIndex(dataGroup, bitMask)
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) isFullCapacitySegment/* endif */);
                        K internalKey = readKeyAtOffset(segment, allocOffset);
                        //noinspection ObjectEquality: identity comparision is intended
                        boolean keysIdentical = internalKey == key;
                        if (keysIdentical || keysEqual(key, internalKey)) {
                            V internalVal = readValueAtOffset(segment, allocOffset);
                            if (!onlyIfAbsent) {
                                writeValueAtOffset(segment, allocOffset, value);
                            }
                            return internalVal;
                        }
                        bitMask = clearLowestSetBit(bitMask);
                    }
                    // Likelihood of this branch depends on SmoothieMap's use case.
                    if (shouldStopProbing(dataGroup)) {
                        // Fast-path empty slot search: this is a fast-path condition to avoid
                        // re-reading the dataGroup (even though from L1) in [Find empty slot] loop.
                        // This is a likely branch because during puts the hash table is expected to
                        // be only half-full on average:
                        // SEGMENT_MAX_ALLOC_CAPACITY / 2 / HASH_TABLE_SLOTS = 37.5% full.
                        if (groupIndexStep == 0) { // [Positive likely branch]
                            emptyBitMask = matchEmpty(dataGroup);
                            if (emptyBitMask != 0) { // [Positive likely branch]
                                outboundOverflowCount_perGroupIncrements = 0;
                                break toInsertNewEntry;
                            } else {
                                outboundOverflowCount_perGroupIncrements =
                                        outboundOverflowCount_groupForChange(groupIndex);
                                // The first iteration of [Quadratic probing] inlined
                                groupIndexStep = 1;
                                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                                break toFindEmptySlot;
                            }
                        } else {
                            break keySearch; // to [Reset groupIndex]
                        }
                    }
                    // [InflatedSegment checking after unsuccessful key search]
                    // [Positive likely branch]
                    if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) {
                        groupIndexStep += 1; // [Quadratic probing]
                        groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                        // [Break from the loop when visited all groups in the hash table]
                        if (groupIndex == baseGroupIndex) { // Unlikely branch
                            break keySearch; // to [Reset groupIndex]
                        }
                    } else {
                        return putInflated(segment, key, hash, value, onlyIfAbsent);
                    }
                } // end of keySearch loop
                // Reset groupIndex:
                groupIndexStep = 0;
                outboundOverflowCount_perGroupIncrements = 0;
                groupIndex = baseGroupIndex;
                // Fall-through to [Find empty slot]
            } // end of toFindEmptySlot block
            // Find empty slot: an alternative to finding an empty slot for insertion in a separate
            // loop is merge this loop with the above loop, but that would increase the number of
            // local variables greatly (variables like emptySlotFound, emptySlotGroupIndex,
            // insertionSlotIndexWithinGroup would be needed) that is critical because the register
            // pressure is already very high. TODO compare the approaches
            //
            // The decision to keep this loop separate from the [keySearch] loop partially
            // undermines the idea behind separate outboundOverflowCounts: see the comment for
            // ContinuousSegment_BitSetAndStateArea.outboundOverflowCountsPerGroup. However, in a
            // specialization of put() and similar methods for the case when removes cannot happen
            // from a SmoothieMap the outboundOverflowCount_perGroupIncrements logic can be merged
            // into the key search loop, similarly to how this is done in with
            // outboundOverflowCount_perGroupDecrements in methods such as remove().
            // TODO implement this specialization
            //noinspection InfiniteLoopStatement: https://youtrack.jetbrains.com/issue/IDEA-207495
            while (true) {
                long dataGroupOffset = dataGroupOffset(groupIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    break toInsertNewEntry; // to [Insert new entry]
                }
                outboundOverflowCount_perGroupIncrements =
                        outboundOverflowCount_markGroupForChange(
                                outboundOverflowCount_perGroupIncrements, groupIndex);
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // No break condition in a loop searching for an empty slot: unlike for key search
                // loops (see [Break from the loop when visited all groups in the hash table]) it's
                // not possible to visit all groups in a segment and to not find an empty slot
                // because SEGMENT_MAX_ALLOC_CAPACITY is less than HASH_TABLE_SLOTS.
            }
        } // end of toInsertNewEntry block
        // Insert new entry:
        int insertionSlotIndexWithinGroup = lowestMatchingSlotIndex(emptyBitMask);
        insert(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupIncrements, key, hash, value, groupIndex, dataGroup,
                insertionSlotIndexWithinGroup);
        return null;
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
            K key, Function<? super K, ? extends @Nullable V> mappingFunction) {
        checkNonNull(key);
        checkNonNull(mappingFunction);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        @Nullable V value;
        long groupIndex = baseGroupIndex;
        long dataGroup;
        long emptyBitMask;
        // TODO [Compare with precomputed outboundOverflowCount_perGroupChanges approach]
        long outboundOverflowCount_perGroupIncrements;
        toInsertNewEntry:
        {
            long groupIndexStep = 0;
            toFindEmptySlot:
            {
                keySearch:
                while (true) {
                    long tagGroupOffset = tagGroupOffset(groupIndex
                            /* if Interleaved segments Supported intermediateSegments */
                            , (long) isFullCapacitySegment/* endif */);
                    long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
                    long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
                    dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                    for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                         bitMask != 0L;
                         bitMask = clearLowestSetBit(bitMask)) {
                        long allocOffset = allocOffset(firstAllocIndex(dataGroup, bitMask)
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) isFullCapacitySegment/* endif */);
                        K internalKey = readKeyAtOffset(segment, allocOffset);
                        //noinspection ObjectEquality: identity comparision is intended
                        boolean keysIdentical = internalKey == key;
                        if (keysIdentical || keysEqual(key, internalKey)) {
                            return readValueAtOffset(segment, allocOffset);
                        }
                    }
                    // Likelihood of this branch depends on SmoothieMap's use case.
                    if (shouldStopProbing(dataGroup)) {
                        value = mappingFunction.apply(key);
                        if (value != null) { // [Positive likely branch]
                            // [Fast-path empty slot search]
                            if (groupIndexStep == 0) { // [Positive likely branch]
                                emptyBitMask = matchEmpty(dataGroup);
                                if (emptyBitMask != 0) { // [Positive likely branch]
                                    outboundOverflowCount_perGroupIncrements = 0;
                                    break toInsertNewEntry;
                                } else {
                                    outboundOverflowCount_perGroupIncrements =
                                            outboundOverflowCount_groupForChange(groupIndex);
                                    // The first iteration of [Quadratic probing] inlined
                                    groupIndexStep = 1;
                                    groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                                    break toFindEmptySlot;
                                }
                            } else {
                                break keySearch; // to [Reset groupIndex]
                            }
                        } else {
                            return null; // mappingFunction returned null, not recording any value.
                        }
                    }
                    // [InflatedSegment checking after unsuccessful key search]
                    // [Positive likely branch]
                    if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) {
                        groupIndexStep += 1; // [Quadratic probing]
                        groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                        // [Break from the loop when visited all groups in the hash table]
                        if (groupIndex == baseGroupIndex) { // Unlikely branch
                            value = mappingFunction.apply(key);
                            if (value != null) {
                                break keySearch; // to [Reset groupIndex]
                            } else {
                                // mappingFunction returned null, not recording any value.
                                return null;
                            }
                        }
                    } else {
                        return computeIfAbsentInflated(segment, key, hash, mappingFunction);
                    }
                } // end of keySearch loop
                // Reset groupIndex:
                groupIndexStep = 0;
                outboundOverflowCount_perGroupIncrements = 0;
                groupIndex = baseGroupIndex;
                // Fall-through to [Find empty slot]
            } // end of toFindEmptySlot block
            // [Find empty slot]
            //noinspection InfiniteLoopStatement: https://youtrack.jetbrains.com/issue/IDEA-207495
            while (true) {
                long dataGroupOffset = dataGroupOffset(groupIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    break toInsertNewEntry; // to [Insert new entry]
                }
                outboundOverflowCount_perGroupIncrements =
                        outboundOverflowCount_markGroupForChange(
                                outboundOverflowCount_perGroupIncrements, groupIndex);
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [No break condition in a loop searching for an empty slot]
            }
        } // end of toInsertNewEntry block
        // Insert new entry:
        int insertionSlotIndexWithinGroup = lowestMatchingSlotIndex(emptyBitMask);
        insert(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupIncrements, key, hash, value, groupIndex, dataGroup,
                insertionSlotIndexWithinGroup);
        return value;
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeIfAbsentInflated(Object segment,
            K key, long hash, Function<? super K, ? extends @Nullable V> mappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.computeIfAbsent(this, key, hash, mappingFunction);
    }

    @Override
    public final @Nullable V compute(K key,
            BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
        checkNonNull(key);
        checkNonNull(remappingFunction);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        @Nullable V newValue;
        long groupIndex = baseGroupIndex;
        long dataGroup;
        long emptyBitMask;
        // TODO [Compare with precomputed outboundOverflowCount_perGroupChanges approach]
        long outboundOverflowCount_perGroupIncrements;
        toInsertNewEntry:
        {
            long groupIndexStep = 0;
            toFindEmptySlot:
            {
                keySearch:
                while (true) {
                    long tagGroupOffset = tagGroupOffset(groupIndex
                            /* if Interleaved segments Supported intermediateSegments */
                            , (long) isFullCapacitySegment/* endif */);
                    long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
                    long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
                    dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                    for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                         bitMask != 0L;
                         bitMask = clearLowestSetBit(bitMask)) {
                        // [Inlined lowestMatchingSlotIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                        long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                        long allocOffset = allocOffset(allocIndex
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) isFullCapacitySegment/* endif */);
                        K internalKey = readKeyAtOffset(segment, allocOffset);
                        //noinspection ObjectEquality: identity comparision is intended
                        boolean keysIdentical = internalKey == key;
                        if (keysIdentical || keysEqual(key, internalKey)) {
                            V oldValue = readValueAtOffset(segment, allocOffset);
                            newValue = remappingFunction.apply(key, oldValue);
                            if (newValue != null) {
                                writeValueAtOffset(segment, allocOffset, newValue);
                            } else {
                                // [Computing outboundOverflowCount_perGroupDecrements in the end]
                                // TODO provide separate computeLikelyRemove() method
                                long outboundOverflowCount_perGroupDecrements =
                                        computeOutboundOverflowCount_perGroupChanges(
                                                baseGroupIndex, groupIndex);
                                // [Reusing local variable]
                                dataGroup = setSlotEmpty(dataGroup, trailingZeros);
                                removeAtSlot(hash, segment,
                                        /* if Interleaved segments Supported intermediateSegments */
                                        isFullCapacitySegment,/* endif */
                                        outboundOverflowCount_perGroupDecrements, dataGroupOffset,
                                        dataGroup, allocIndex, allocOffset);
                            }
                            return newValue;
                        }
                    }
                    // Likelihood of this branch depends on SmoothieMap's use case.
                    if (shouldStopProbing(dataGroup)) {
                        newValue = remappingFunction.apply(key, null);
                        if (newValue != null) { // [Positive likely branch]
                            // [Fast-path empty slot search]
                            if (groupIndexStep == 0) { // [Positive likely branch]
                                emptyBitMask = matchEmpty(dataGroup);
                                if (emptyBitMask != 0) { // [Positive likely branch]
                                    outboundOverflowCount_perGroupIncrements = 0;
                                    break toInsertNewEntry;
                                } else {
                                    outboundOverflowCount_perGroupIncrements =
                                            outboundOverflowCount_groupForChange(groupIndex);
                                    // The first iteration of [Quadratic probing] inlined
                                    groupIndexStep = 1;
                                    groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                                    break toFindEmptySlot;
                                }
                            } else {
                                break keySearch; // to [Reset groupIndex]
                            }
                        } else {
                            // remappingFunction returned null, not recording any value.
                            return null;
                        }
                    }
                    // [InflatedSegment checking after unsuccessful key search]
                    // [Positive likely branch]
                    if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) {
                        groupIndexStep += 1; // [Quadratic probing]
                        groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                        // [Break from the loop when visited all groups in the hash table]
                        if (groupIndex == baseGroupIndex) { // Unlikely branch
                            newValue = remappingFunction.apply(key, null);
                            if (newValue != null) {
                                break keySearch; // to [Reset groupIndex]
                            } else {
                                // remappingFunction returned null, not recording any value.
                                return null;
                            }
                        }
                    } else {
                        return computeInflated(segment, key, hash, remappingFunction);
                    }
                } // end of keySearch loop
                // Reset groupIndex:
                groupIndexStep = 0;
                outboundOverflowCount_perGroupIncrements = 0;
                groupIndex = baseGroupIndex;
                // Fall-through to [Find empty slot]
            } // end of toFindEmptySlot block
            // [Find empty slot]
            //noinspection InfiniteLoopStatement: https://youtrack.jetbrains.com/issue/IDEA-207495
            while (true) {
                long dataGroupOffset = dataGroupOffset(groupIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    break toInsertNewEntry; // to [Insert new entry]
                }
                outboundOverflowCount_perGroupIncrements =
                        outboundOverflowCount_markGroupForChange(
                                outboundOverflowCount_perGroupIncrements, groupIndex);
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [No break condition in a loop searching for an empty slot]
            }
        } // end of toInsertNewEntry block
        // Insert new entry:
        int insertionSlotIndexWithinGroup = lowestMatchingSlotIndex(emptyBitMask);
        insert(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupIncrements, key, hash, newValue, groupIndex,
                dataGroup, insertionSlotIndexWithinGroup);
        return newValue;
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V computeInflated(Object segment, K key, long hash,
            BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.compute(this, key, hash, remappingFunction);
    }

    @Override
    public final @Nullable V merge(K key, V value,
            BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
        checkNonNull(key);
        checkNonNull(value);
        checkNonNull(remappingFunction);

        final long hash = keyHashCode(key);
        final long hash_segmentLookupBits = segmentLookupBits(hash);
        final Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
        /* if Interleaved segments Supported intermediateSegments */
        final int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
        /* endif */

        final long baseGroupIndex = baseGroupIndex(hash);
        final long hashTagBits = tagBits(hash);
        long groupIndex = baseGroupIndex;
        long dataGroup;
        long emptyBitMask;
        // TODO [Compare with precomputed outboundOverflowCount_perGroupChanges approach]
        long outboundOverflowCount_perGroupIncrements;
        toInsertNewEntry:
        {
            long groupIndexStep = 0;
            toFindEmptySlot:
            {
                keySearch:
                while (true) {
                    long tagGroupOffset = tagGroupOffset(groupIndex
                            /* if Interleaved segments Supported intermediateSegments */
                            , (long) isFullCapacitySegment/* endif */);
                    long tagGroup = readTagGroupAtOffset(segment, tagGroupOffset);
                    long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
                    dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                    for (long bitMask = match(tagGroup, hashTagBits, dataGroup);
                         bitMask != 0L;
                         bitMask = clearLowestSetBit(bitMask)) {
                        // [Inlined lowestMatchingSlotIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                        long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                        long allocOffset = allocOffset(allocIndex
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) isFullCapacitySegment/* endif */);
                        K internalKey = readKeyAtOffset(segment, allocOffset);
                        //noinspection ObjectEquality: identity comparision is intended
                        boolean keysIdentical = internalKey == key;
                        if (keysIdentical || keysEqual(key, internalKey)) {
                            V internalVal = readValueAtOffset(segment, allocOffset);
                            @Nullable V newValue = remappingFunction.apply(internalVal, value);
                            if (newValue != null) {
                                writeValueAtOffset(segment, allocOffset, newValue);
                            } else {
                                // [Computing outboundOverflowCount_perGroupDecrements in the end]
                                // TODO provide separate mergeLikelyRemove() method
                                long outboundOverflowCount_perGroupDecrements =
                                        computeOutboundOverflowCount_perGroupChanges(
                                                baseGroupIndex, groupIndex);
                                // [Reusing local variable]
                                dataGroup = setSlotEmpty(dataGroup, trailingZeros);
                                removeAtSlot(hash, segment,
                                        /* if Interleaved segments Supported intermediateSegments */
                                        isFullCapacitySegment,/* endif */
                                        outboundOverflowCount_perGroupDecrements, dataGroupOffset,
                                        dataGroup, allocIndex, allocOffset);
                            }
                            return newValue;
                        }
                    }
                    // Likelihood of this branch depends on SmoothieMap's use case.
                    if (shouldStopProbing(dataGroup)) {
                        // [Fast-path empty slot search]
                        if (groupIndexStep == 0) { // [Positive likely branch]
                            emptyBitMask = matchEmpty(dataGroup);
                            if (emptyBitMask != 0) { // [Positive likely branch]
                                outboundOverflowCount_perGroupIncrements = 0;
                                break toInsertNewEntry;
                            } else {
                                outboundOverflowCount_perGroupIncrements =
                                        outboundOverflowCount_groupForChange(groupIndex);
                                // The first iteration of [Quadratic probing] inlined
                                groupIndexStep = 1;
                                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                                break toFindEmptySlot;
                            }
                        } else {
                            break keySearch; // to [Reset groupIndex]
                        }
                    }
                    // [InflatedSegment checking after unsuccessful key search]
                    // [Positive likely branch]
                    if (dataGroup != INFLATED_SEGMENT__MARKER_DATA_GROUP) {
                        groupIndexStep += 1; // [Quadratic probing]
                        groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                        // [Break from the loop when visited all groups in the hash table]
                        if (groupIndex == baseGroupIndex) { // Unlikely branch
                            break keySearch; // to [Reset groupIndex]
                        }
                    } else {
                        return mergeInflated(segment, key, hash, value, remappingFunction);
                    }
                } // end of keySearch loop
                // Reset groupIndex:
                groupIndexStep = 0;
                outboundOverflowCount_perGroupIncrements = 0;
                groupIndex = baseGroupIndex;
                // Fall-through to [Find empty slot]
            } // end of toFindEmptySlot block
            // [Find empty slot]
            //noinspection InfiniteLoopStatement: https://youtrack.jetbrains.com/issue/IDEA-207495
            while (true) {
                long dataGroupOffset = dataGroupOffset(groupIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) isFullCapacitySegment/* endif */);
                dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
                emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    break toInsertNewEntry; // to [Insert new entry]
                }
                outboundOverflowCount_perGroupIncrements =
                        outboundOverflowCount_markGroupForChange(
                                outboundOverflowCount_perGroupIncrements, groupIndex);
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [No break condition in a loop searching for an empty slot]
            }
        } // end of toInsertNewEntry block
        // Insert new entry:
        int insertionSlotIndexWithinGroup = lowestMatchingSlotIndex(emptyBitMask);
        insert(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupIncrements, key, hash, value, groupIndex, dataGroup,
                insertionSlotIndexWithinGroup);
        return value;
    }

    /** [Shallow xxxInflated() methods] */
    private @Nullable V mergeInflated(Object segment, K key, long hash, V value,
            BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
        @SuppressWarnings("unchecked")
        InflatedSegment<K, V> inflatedSegment = (InflatedSegment<K, V>) segment;
        return inflatedSegment.merge(this, key, hash, value, remappingFunction);
    }

    //endregion

    //region insert() and the family of makeSpaceAndInsert() methods called from it

    @HotPath
    private void insert(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCounts_perGroupIncrements, K key, long hash, V value,
            long groupIndex, long dataGroup, int insertionSlotIndexWithinGroup) {
        long bitSetAndState = getBitSetAndState(segment);
        /* if Continuous segments */
        int allocIndex = lowestFreeAllocIndex(bitSetAndState);
        // elif Interleaved segments */
        int allocIndex = freeAllocIndexClosestTo(bitSetAndState,
                allocIndexBoundaryForLocalAllocation((int) groupIndex
                /* if Supported intermediateSegments */, isFullCapacitySegment/* endif */));
        /* endif */
        int allocCapacity = allocCapacity(bitSetAndState);
        if (allocIndex < allocCapacity) { // [Positive likely branch]
            doInsert(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    outboundOverflowCounts_perGroupIncrements, key, hash, value,
                    groupIndex, dataGroup, insertionSlotIndexWithinGroup, bitSetAndState,
                    allocIndex);
        } else {
            makeSpaceAndInsert(allocCapacity, segment, outboundOverflowCounts_perGroupIncrements,
                    key, hash, value, groupIndex, dataGroup, insertionSlotIndexWithinGroup,
                    bitSetAndState);
        }
    }

    @HotPath
    private void doInsert(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCounts_perGroupIncrements,
            K key, long hash, V value, long groupIndex, long dataGroup,
            int insertionSlotIndexWithinGroup, long bitSetAndState, int allocIndex) {
        size++;
        modCount++;

        if (outboundOverflowCounts_perGroupIncrements != 0) { // Unlikely branch
            incrementOutboundOverflowCountsPerGroup(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    outboundOverflowCounts_perGroupIncrements);
        }

        /* if Continuous segments //
        // This assumes that allocIndex passed into this method was obtained via
        // lowestFreeAllocIndex() called on the same bitSetAndState.
        // setLowestAllocBit() incurs less operations than setting an alloc bit by allocIndex.
        bitSetAndState = setLowestAllocBit(bitSetAndState);
        // elif Interleaved segments */
        bitSetAndState = setAllocBit(bitSetAndState, allocIndex);
        /* endif */
        setBitSetAndState(segment, bitSetAndState);

        // The tag can also be passed down from the methods calling insert() (like put()) but
        // it's chosen not to do so because of the high register pressure in those methods.
        // Compare with a similar tradeoff in makeSpaceAndInsert(): see @apiNote to that method.
        // TODO compare the approaches
        byte tag = (byte) tagBits(hash);
        writeEntry(segment,
                /* if Interleaved segments Supported intermediateSegments */
                (long) isFullCapacitySegment,/* endif */
                key, tag, value, groupIndex, dataGroup, insertionSlotIndexWithinGroup, allocIndex);
    }

    /**
     * Makes space for an extra entry by means of either {@link #growCapacityAndInsert}, {@link
     * #splitAndInsert}, or {@link #inflateAndInsert}.
     *
     * @apiNote
     * allocCapacity could have been re-extracted from bitSetAndState instead of passing it as a
     * parameter to this method that would reduce the bytecode size of {@link #insert} (that is
     * good: see [Reducing bytecode size of a hot method]) and might be even cheaper than putting
     * variables that are in registers onto stack and then popping them from the stack (that happens
     * when so many arguments are passed to the method). Similarly,
     * outboundOverflowCounts_perGroupIncrements could be computed within this method from
     * groupIndex and {@link HashTable#baseGroupIndex}(hash) if
     * [Precomputed outboundOverflowCount_perGroupChanges] is implemented.
     *
     * Compare with a similar tradeoff in {@link #doInsert} itself where `tag` can be passed into
     * the method instead of recomputing it inside the method.
     * TODO compare the approaches
     */
    @AmortizedPerSegment
    final void makeSpaceAndInsert(int allocCapacity, Object segment,
            long outboundOverflowCounts_perGroupIncrements, K key, long hash, V value,
            long groupIndex, long dataGroup, int insertionSlotIndexWithinGroup,
            long bitSetAndState) {
        if (bitSetAndState == BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) {
            throw new ConcurrentModificationException();
        }

        // ### First route: check if the segment is intermediate-capacity and should be grown to
        // ### full size.
        // This branch could more naturally be placed in insert() (and then allocCapacity shouldn't
        // be passed into makeSpaceAndInsert()) but there is an objective to make bytecode size of
        // insert() as small as possible, see [Reducing bytecode size of a hot method]. TODO compare
        if (allocCapacity == SEGMENT_INTERMEDIATE_ALLOC_CAPACITY) {
            growCapacityAndInsert(segment, outboundOverflowCounts_perGroupIncrements, key, hash,
                    value, groupIndex, dataGroup, insertionSlotIndexWithinGroup, bitSetAndState);
            return;
        }

        // Need to read modCount here rather than inside methods splitAndInsert() and
        // inflateAndInsert() so that it is done before calling to
        // tryEnsureSegmentsArrayCapacityForSplit() that may update the modCount field (and is a
        // bulky method that needs to be surrounded with modCount read and check).
        int modCount = getModCountOpaque();

        // ### Second route: split or inflate the segment.
        int segmentOrder = segmentOrder(bitSetAndState);
        // InflatedSegment.shouldBeSplit() refers to and depends on the following call to
        // computeAverageSegmentOrder().
        int averageSegmentOrder = computeAverageSegmentOrder(size);
        boolean acceptableOrderAfterSplitting =
                segmentOrder < averageSegmentOrder + MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE;
        int modCountAddition;
        // In principle, we can still split to-become-outlier segments or segments that are
        // already outliers as long as their order is less than segmentsArray's order (which
        // might happen to be greater than averageSegmentOrder +
        // MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE if the SmoothieMap used to be larger and
        // has shrunk in size since). But it's not done because we don't want to disturb the
        // poor hash code distribution detection (see HashCodeDistribution), as well as
        // the functionality of moveToMapWithShrunkArray().
        if (acceptableOrderAfterSplitting &&
                (modCountAddition =
                        tryEnsureSegmentsArrayCapacityForSplit(segmentOrder)) >= 0) {
            modCount += modCountAddition;
            splitAndInsert(modCount, segment, key, hash, value, bitSetAndState, segmentOrder);
        } else {
            inflateAndInsert(modCount, segmentOrder, segment, bitSetAndState, key, hash, value);
        }
    }

    @AmortizedPerSegment
    private void growCapacityAndInsert(Object oldSegment,
            long outboundOverflowCounts_perGroupIncrements, K key, long hash, V value,
            long groupIndex, long dataGroup, int insertionSlotIndexWithinGroup,
            long bitSetAndState) {
        // TODO fix for interleaved segments
        int modCount = getModCountOpaque();

        // The old segment's bitSetAndState is never reset back to an operational value after this
        // statement.
        setBitSetAndState(oldSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        // ### Create a new segment.
        //noinspection UnnecessaryLocalVariable
        int oldAllocCapacity = SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
        //noinspection UnnecessaryLocalVariable
        int newAllocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
        Segment<?, ?> newSegment = grow(oldSegment, bitSetAndState, newAllocCapacity);
        // Reusing local variable: it's better to reuse an existing local variable than to introduce
        // a new variable because of a risk of using a wrong variable in the code below.
        bitSetAndState = getBitSetAndState(newSegment);

        // ### Replace references from oldSegment to newSegment in segmentsArray.
        int segmentOrder = segmentOrder(bitSetAndState);
        int firstSegmentIndex = firstSegmentIndexByHashAndOrder(hash, segmentOrder);
        replaceInSegmentsArray(
                getNonNullSegmentsArrayOrThrowCme(), firstSegmentIndex, segmentOrder, newSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        // ### Insert the new entry.
        /* if Continuous segments */
        // When we grow a continuous segment with alloc capacity smaller than
        // SEGMENT_MAX_ALLOC_CAPACITY that was full the next free alloc index can only be equal to
        // the old alloc capacity. See also [allocIndex = segmentSize optimization].
        //noinspection UnnecessaryLocalVariable
        int allocIndex = oldAllocCapacity;
        /* elif Interleaved segments */
        int allocIndex = freeAllocIndexClosestTo(bitSetAndState,
                FullCapacitySegment.allocIndexBoundaryForLocalAllocation((int) groupIndex));
        /* endif */
        /* if Interleaved segments Supported intermediateSegments */
        int newSegment_isFullCapacity = 1;
        /* endif */
        // No point in specializing doInsert() for full-capacity segments because we are in an
        // AmortizedPerSegment method.
        doInsert(newSegment,
                /* if Interleaved segments Supported intermediateSegments */
                newSegment_isFullCapacity,/* endif */
                outboundOverflowCounts_perGroupIncrements, key, hash, value,
                groupIndex, dataGroup, insertionSlotIndexWithinGroup, bitSetAndState, allocIndex);
        modCount++; // Matches the modCount field increment performed in doInsert().

        checkModCountOrThrowCme(modCount);
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

        // ### Create a new segment and split entries between fromSegment and the new segment.
        int intoSegmentAllocCapacity = getInitialSegmentAllocCapacity(siblingSegmentsOrder);
        // intoSegment's bitSetAndState is written and [Safe segment publication] is ensured inside
        // split(), closer to the end of the method.
        Segment<K, V> intoSegment =
                allocateNewSegmentWithoutSettingBitSetAndSet(intoSegmentAllocCapacity);
        int siblingSegmentsQualificationBitIndex =
                SEGMENT_LOOKUP_HASH_SHIFT + siblingSegmentsOrder - 1;

        long fromSegmentIsHigher = split(fromSegment,
                fromSegment_bitSetAndState, intoSegment, intoSegmentAllocCapacity,
                siblingSegmentsOrder, siblingSegmentsQualificationBitIndex);
        // storeFence() is called inside split() to make the publishing of intoSegment safe.

        // ### Publish intoSegment (the new segment) to segmentsArray.
        int intoSegmentIsLower = (int) (fromSegmentIsHigher >>>
                siblingSegmentsQualificationBitIndex);
        int firstSiblingSegmentsIndex =
                firstSegmentIndexByHashAndOrder(hash, priorSegmentOrder);
        int firstIntoSegmentIndex = chooseFirstSiblingSegmentIndex(
                firstSiblingSegmentsIndex, siblingSegmentsOrder, intoSegmentIsLower);
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(), firstIntoSegmentIndex,
                siblingSegmentsOrder, intoSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        /* if Tracking segmentOrderStats */
        addToSegmentCountWithOrder(siblingSegmentsOrder, 2);
        subtractFromSegmentCountWithOrder(priorSegmentOrder, 1);
        /* endif */

        // Unlike other similar methods which may be called from makeSpaceAndInsert(), namely
        // growCapacityAndInsert() and inflateAndInsert(), we check the modCount here before the
        // "insert" part of the method because the insertion might cause fromSegment or intoSegment
        // to be inflated or split itself if during the splitting all or almost all entries went to
        // one of the segments. There is no cheap way to detect if that have happened to
        // additionally increment the local copy of modCount. (Compare with the similar problem in
        // splitInflated().) After all, the main point is making a modCount check after bulky
        // operations: split() and replaceInSegmentsArray() which are called above. Including the
        // last point update (the internalPutIfAbsent() call below) in the scope of the modCount
        // check is not necessary.
        checkModCountOrThrowCme(modCount);

        // ### Insert the new entry into fromSegment or intoSegment: calling into
        // internalPutIfAbsent() which accesses the segmentsArray (and the bit set with
        // isFullCapacity flags, when this is implemented in isFullCapacitySegment() method)
        // although both fromSegment and intoSegment are already available as local variables
        // because choosing between fromSegment and intoSegment and determining whether the chosen
        // segment has full capacity in an ad-hoc manner would likely result in more branches than
        // internalPutIfAbsent(). Note that accessing segmentsArray (and the bit set) should read
        // from L1 because this path with the same key and hash has already been taken in the
        // beginning of the operation that initiated this splitAndInsert() call: in other words,
        // higher in the stack. Also, calling to internalPutIfAbsent() is simpler than an
        // alternative ad-hoc segment choice logic.
        if (internalPutIfAbsent(key, hash, value) != null) {
            throw new ConcurrentModificationException(
                    "New entry shouldn't replace existing during split");
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
     * tradeoff as in {@link #doInsert} and {@link #makeSpaceAndInsert}. However, in split() an
     * additional factor for passing siblingSegmentsQualificationBitIndex is making it being
     * computed only once in {@link #splitAndInsert}, hence less implicit dependency and probability
     * of making mistakes.
     */
    @SuppressWarnings({"UnnecessaryLabelOnBreakStatement", "UnnecessaryLabelOnContinueStatement"})
    @AmortizedPerSegment
    final long split(Object fromSegment, long fromSegment_bitSetAndState,
            Object intoSegment, int intoSegment_allocCapacity, int newSegmentOrder,
            int siblingSegmentsQualificationBitIndex) {
        // Updating fromSegment_bitSetAndState's segment order early in this method because it's
        // hard to track different segment orders of fromSegment and intoSegment and their possible
        // buggy interactions in InterleavedSegments.swapContentsDuringSplit().
        fromSegment_bitSetAndState = incrementSegmentOrder(fromSegment_bitSetAndState);

        // ### Defining variables that will be used in and after [fromSegment iteration].

        /* if Tracking hashCodeDistribution */
        int fromSegment_initialSize = segmentSize(fromSegment_bitSetAndState);
        verifyEqual(fromSegment_initialSize, SEGMENT_MAX_ALLOC_CAPACITY);
        // This variable is used to count how many hashes of keys in fromSegment have 1 in
        // HASH__BASE_GROUP_INDEX_BITS-th bit. See how it used in the end of this method.
        // Not counting numKeysForHalfOne or numKeysForHalfTwo directly to make the computation
        // inside [fromSegment iteration] loop cheaper.
        int hashTableHalfBits = 0;
        /* endif */
        /* if Continuous segments || Supported intermediateSegments */
        int intoSegment_currentSize = 0;
        /* endif */
        /* if Interleaved segments */
        long intoSegment_bitSetAndState =
                makeNewBitSetAndState(intoSegment_allocCapacity, newSegmentOrder);
        /* endif */
        long fromSegment_outboundOverflowCount_perGroupDeductions = 0;
        long intoSegment_outboundOverflowCount_perGroupAdditions = 0;
        // boolean as long: reusing a variable for two purposes, as a sanity boolean flag
        // ("swappedSegmentsInsideLoop") and as a bit indicating that fromSegment is the
        // higher-index segment ("fromSegmentIsHigher") to minimize the number of variables in this
        // giant method and thus reduce register pressure. This variable is of long type rather than
        // int to avoid conversion inside [fromSegment iteration] loop, where
        // entryShouldRemainInFromSegment is computed. "True" value of "swappedSegmentsInsideLoop"
        // part of this variable corresponds to siblingSegmentsQualificationBit =
        // 1 << siblingSegmentsQualificationBitIndex rather than just 1.
        long swappedSegmentsInsideLoopAndFromSegmentIsHigher = 0;
        // If this bit in a hash of some key is "fromSegmentIsHigher", then the entry should stay in
        // fromSegment rather than move to intoSegment.
        long siblingSegmentsQualificationBit = 1L << siblingSegmentsQualificationBitIndex;
        /* if Interleaved segments Supported intermediateSegments */
        // TODO check that Hotspot compiles this expression into branchless code.
        // TODO check if Hotspot emits more optimal machine code when a variable is kept as long and
        //  casted down to int when needed or vice versa.
        int intoSegment_isFullCapacity =
                intoSegment_allocCapacity == SEGMENT_MAX_ALLOC_CAPACITY ? 1 : 0;
        int fromSegment_isFullCapacity = 1;
        /* endif */

        // ### fromSegment iteration: iterating all entries in the hash table, while moving the
        // proper entries to intoSegment and reducing (if possible) unnecessary "group overflows"
        // for entries that should remain in fromSegment, that is, moving the entries to (or, at
        // least, closer to) their respective base groups (see HashTable.baseGroupIndex()).
        //
        // Reducing group overflows is expected to be effective starting from the second iteration
        // of the loop, because about half of the entries in the previous group are expected to be
        // moved out to intoSegment, leaving holes for entries which overflown by one group in the
        // current iteration group which should be just single percents of the total population of
        // entries (see [Non-overflown entry]), and entries overflown by two or more steps in the
        // group probing chain orders of magnitude more rare. Note: this reasoning depends on the
        // current [Quadratic probing] chain which is baseGroupIndex + [0, 1, 3, 6, 2, 7, 5, 4], and
        // in positive direction, as well as the following loop.
        //
        // However, on the first iteration of the following loop, overflown entries can't be shifted
        // back in their group probing chains. One way to fix this it to make HASH_TABLE_GROUPS + 1
        // steps in the loop rather than just HASH_TABLE_GROUPS, but that's relatively expensive
        // solution because the remaining entries in the first iteration group would be checked (in
        // particular, their hash code is computed) twice in the loop. There is no opportunity for
        // a "split phase" between 0-th and 8-th iteration because we don't known a priori which
        // entries should remain in fromSegment and which should move to intoSegment.
        //
        // A cheaper approach is taken here: missed opportunities for reducing entries' probing
        // chain length ending at the first group iterated in the following loop is just tolerated.
        // However, the first group in the iteration is chosen differently at random each time to
        // prevent overflown entries from "accruing" in the group 0 (if it's always used as the
        // start) in the series of generational segment splits.
        //
        // `size & HASH_TABLE_GROUPS_MASK` is effectively a pseudo-random value (after splitting the
        // first segment in the SmoothieMap with the order of 0), yet it is deterministic when the
        // map is populated with exactly the same data (e. g. in tests).
        long iterGroupIndexStart = size & HASH_TABLE_GROUPS_MASK;

        // [Branchless hash table iteration]: this instance of [Branchless hash table iteration] has
        // yet different properties than Segment.removeIf() and compactEntriesDuringSegmentSwap()
        // because the hash table should be SEGMENT_MAX_ALLOC_CAPACITY / HASH_TABLE_SLOTS = 75%
        // full here. So if byte-by-byte hash table iteration is better than the branchless
        // approach in any one of split(), Segment.removeIf(), and compactEntriesDuringSegmentSwap()
        // methods, it should be in split().
        // TODO compare the approaches for split().

        // [Int-indexed loop to avoid a safepoint poll]
        for (int extraGroupIndex = 0; extraGroupIndex < HASH_TABLE_GROUPS; extraGroupIndex++) {
            long iterGroupIndex = addGroupIndex(iterGroupIndexStart, (long) extraGroupIndex);
            long iterDataGroupOffset = dataGroupOffset(iterGroupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) fromSegment_isFullCapacity/* endif */);
            long iterDataGroup = readDataGroupAtOffset(fromSegment, iterDataGroupOffset);

            // TODO compare with int-indexed loop with bitCount(bitMask) limit. It may be executed
            //  without mispredicted branches on modern CPUs. However, this might be ineffective
            //  because of possible update of iterBitMask in [Swap segments], meaning that the loop
            //  limit might change in the course of the loop itself, which may inhibit CPU branch
            //  prediction behaviour.
            groupIteration:
            for (long iterBitMask = matchFull(iterDataGroup);
                 iterBitMask != 0L;
                 iterBitMask = clearLowestSetBit(iterBitMask)) {

                long fromSegment_allocIndex;
                // [Inlined lowestMatchingSlotIndex]
                int iterTrailingZeros = Long.numberOfTrailingZeros(iterBitMask);
                fromSegment_allocIndex = extractAllocIndex(iterDataGroup, iterTrailingZeros);
                long fromSegment_allocOffset = allocOffset(fromSegment_allocIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) fromSegment_isFullCapacity/* endif */);

                K key = readKeyAtOffset(fromSegment, fromSegment_allocOffset);
                long hash = keyHashCode(key);

                /* if Tracking hashCodeDistribution */
                int halfBit = 1 << (HASH__BASE_GROUP_INDEX_BITS - 1);
                // Counting hashTableHalfBits regardless of whether hashCodeDistribution is null
                // (see [hashCodeDistribution null check]), because adding a null check is
                // likely more expensive than doing this small branchless computation itself.
                hashTableHalfBits += ((int) hash) & halfBit;
                /* endif */

                long baseGroupIndex = baseGroupIndex(hash);

                // Using "fromSegmentIsHigher" meaning of the
                // swappedSegmentsInsideLoopAndFromSegmentIsHigher variable in this expression.
                final boolean entryShouldRemainInFromSegment =
                        (hash & siblingSegmentsQualificationBit) ==
                                swappedSegmentsInsideLoopAndFromSegmentIsHigher;

                if (entryShouldRemainInFromSegment) { // 50-50 unpredictable branch
                    // ### The entry remains in fromSegment.

                    // Non-overflown entry:
                    // If iterGroupIndex equals to baseGroupIndex then the condition of
                    // `if (newGroupIndex == iterGroupIndex)` inside the [Find empty slot] loop
                    // below must be true before the first iteration (although the loop is organized
                    // so that this condition is not checked before the first iteration). In fact,
                    // the condition below and the condition inside the loop can be both replaced
                    // with a single loop condition. This is not done to preserve
                    // [Positive likely branch], similarly to what is done in putImpl().
                    //
                    // This branch should be usually taken with the maximum load factor of
                    // SEGMENT_MAX_ALLOC_CAPACITY / HASH_TABLE_SLOTS = 0.75 and therefore CPU
                    // should predict this branch well.
                    // TODO evaluate exactly the probability of this branch (should be about 98%?)
                    if (iterGroupIndex == baseGroupIndex) { // [Positive likely branch]
                        continue groupIteration;
                    }
                    // iterGroupIndex != baseGroupIndex: an unlikely path
                    // [Find empty slot]
                    for (long newGroupIndex = baseGroupIndex, groupIndexStep = 0; ; ) {
                        long dataGroupOffset = dataGroupOffset(newGroupIndex
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) fromSegment_isFullCapacity/* endif */);
                        long dataGroup = readDataGroupAtOffset(fromSegment, dataGroupOffset);
                        long emptyBitMask = matchEmpty(dataGroup);
                        if (emptyBitMask != 0) { // [Positive likely branch]
                            int newSlotIndexWithinGroup = lowestMatchingSlotIndex(emptyBitMask);
                            // Calling makeData() here should be faster than doing something like
                            // extractDataByte(iterDataGroup, iterTrailingZeros) because makeData()
                            // is one bitwise op and a cast, while extractDataByte() would be two
                            // bitwise ops and a cast. Keeping the value when it was used inside the
                            // extractAllocIndex(iterDataGroup, iterTrailingZeros) call above to
                            // obtain allocIndex is also likely to be worse because of high register
                            // pressure: the kept data value is likely going to be pushed to the
                            // stack.
                            byte data = makeData(dataGroup, fromSegment_allocIndex);
                            writeTagAndData(fromSegment,
                                    /* if Interleaved segments Supported intermediateSegments */
                                    (long) fromSegment_isFullCapacity,/* endif */
                                    newGroupIndex, newSlotIndexWithinGroup,
                                    (byte) tagBits(hash), data);

                            // An entry at the slot is shifted backwards closer to baseGroupIndex.
                            // The difference between outboundOverflowCount changes should be
                            // subtracted. XOR operation is such difference.
                            fromSegment_outboundOverflowCount_perGroupDeductions +=
                                    computeOutboundOverflowCount_perGroupChanges(
                                            baseGroupIndex, newGroupIndex) ^
                                            computeOutboundOverflowCount_perGroupChanges(
                                                    baseGroupIndex, iterGroupIndex);

                            // Empty the iteration slot:
                            iterDataGroup = setSlotEmpty(iterDataGroup, iterTrailingZeros);
                            // Not doing anything with the corresponding tag group, i. e. leaving
                            // "garbage" in the tag byte of the emptied slot.

                            continue groupIteration;
                        }
                        groupIndexStep += 1; // [Quadratic probing]
                        newGroupIndex = addGroupIndex(newGroupIndex, groupIndexStep);
                        if (newGroupIndex == iterGroupIndex) { // [Positive likely branch]
                            // if newGroupIndex has reached iterGroupIndex then doing nothing
                            // and proceeding to the next iteration of the groupIteration loop,
                            // as well as in `if (iterGroupIndex == baseGroupIndex)` case above.
                            continue groupIteration;
                        }
                    }
                }
                else {
                    // ### The entry is moved to intoSegment.

                    /* if Supported intermediateSegments */
                    // Swap segments:
                    // #### Swap fromSegment's and intoSegment's contents if the intoSegment is
                    // #### full.
                    // The probability of taking the following branch once during the
                    // [fromSegment iteration], that is, when more than
                    // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY go into an intermediate-capacity
                    // intoSegment, equals to 1 -
                    // CDF[BinomialDistribution[SEGMENT_MAX_ALLOC_CAPACITY(48), 0.5], 30] ~= 3%
                    // (in case of Continuous segments), or 1 -
                    // CDF[BinomialDistribution[SEGMENT_MAX_ALLOC_CAPACITY(48), 0.5], 32] ~= 0.7%
                    // (in case of Interleaved segments), except for the cases when the distribution
                    // is skewed so that the majority of segments at some order split with much more
                    // keys going to higher-index siblings than to lower-index ones. There is no
                    // doubling of probability like for HashCodeDistribution's
                    // HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS values because
                    // if the distribution is skewed towards fromSegment the following branch is not
                    // taken.
                    //
                    // Therefore the following branch is very unlikely on each individual iteration.
                    //
                    // Doesn't seem practical to use [Positive likely branch] principle
                    // because it would require to add a dummy loop and organize code in a
                    // very unnatural way.
                    if (intoSegment_currentSize == intoSegment_allocCapacity) {
                        // ### Swapping segments if intermediate-capacity intoSegment is overflowed.
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
                        if (swappedSegmentsInsideLoopAndFromSegmentIsHigher != 0) {
                            // Already swapped segments inside the splitting loop once. This might
                            // only happen if entries are inserted into fromSegment concurrently
                            // with the splitting loop.
                            throw new ConcurrentModificationException();
                        }
                        swappedSegmentsInsideLoopAndFromSegmentIsHigher =
                                siblingSegmentsQualificationBit;

                        // Write out the iterDataGroup as it may have been updated at
                        // [Empty the iteration slot].
                        /* if Enabled extraChecks */
                        // fromSegment_isFullCapacity can change its initial value 1 only in the
                        // end of segment swap itself.
                        verifyEqual(fromSegment_isFullCapacity, 1);
                        /* endif */
                        /* if Interleaved segments */FullCapacitySegment./* endif */writeDataGroup(
                                fromSegment, iterGroupIndex, iterDataGroup);

                        // makeSpaceAndInsert[Second route] guarantees that only full-capacity
                        // segments are split:
                        /* if Enabled extraChecks */
                        verifyEqual(allocCapacity(fromSegment_bitSetAndState),
                                SEGMENT_MAX_ALLOC_CAPACITY);
                        /* endif */
                        int fromSegment_allocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
                        /* if Continuous segments */
                        long intoSegment_bitSetAndState = ContinuousSegments.SegmentBase
                                .swapContentsDuringSplit(fromSegment,
                                        fromSegment_allocCapacity, fromSegment_bitSetAndState,
                                        intoSegment, intoSegment_allocCapacity);
                        /* elif Interleaved segments */
                        intoSegment_bitSetAndState =
                                InterleavedSegments.swapContentsDuringSplit(
                                        fromSegment, fromSegment_bitSetAndState,
                                        intoSegment, intoSegment_bitSetAndState);
                        /* endif */
                        // Swap fromSegment and intoSegment variables.
                        {
                            fromSegment_bitSetAndState = intoSegment_bitSetAndState;
                            /* if Interleaved segments */
                            // The updated fromSegment_bitSetAndState is written into bitSetAndState
                            // field of fromSegment in the call to
                            // InterleavedSegments.swapContentsDuringSplit() above.
                            // See the documentation to that method.
                            intoSegment_bitSetAndState = getBitSetAndState(fromSegment);
                            /* endif */

                            Object tmpSegment = intoSegment;
                            intoSegment = fromSegment;
                            fromSegment = tmpSegment;

                            intoSegment_allocCapacity = fromSegment_allocCapacity;

                            fromSegment_isFullCapacity = intoSegment_isFullCapacity;
                            intoSegment_isFullCapacity = 1;
                        }

                        // iterDataGroup needs to be re-read here because it might be updated during
                        // a swapContentsDuringSplit() call above: in
                        // compactEntriesDuringSegmentSwap() (if this method is called) in case of
                        // Continuous segments and always in case of InterleavedSegments segments
                        // (see InterleavedSegments.swapContentsDuringSplit()).
                        iterDataGroupOffset = dataGroupOffset(iterGroupIndex
                                /* if Interleaved segments Supported intermediateSegments */
                                , (long) fromSegment_isFullCapacity/* endif */);
                        iterDataGroup = readDataGroupAtOffset(fromSegment, iterDataGroupOffset);
                        iterBitMask = matchFull(iterDataGroup);
                        // Clear the already iterated bits to continue the loop correctly.
                        iterBitMask = clearLowestNBits(iterBitMask, iterTrailingZeros);
                    }
                    // end of [Swap segments]
                    /* endif */

                    V value = readValueAtOffset(fromSegment, fromSegment_allocOffset);

                    // ### Put the entry into intoSegment:
                    {
                        // [Find empty slot]. Even if iterGroupIndex equals baseGroupIndex,
                        // [Find empty slot] loop can't be skipped because it's possible that
                        // intoSegment's iterGroupIndex is already full of entries moved from the
                        // previously visited groups in [fromSegment iteration] which is possible
                        // due to hash table wrap around.
                        // TODO [Unbounded search loop]
                        internalPutLoop:
                        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ;) {
                            long dataGroupOffset = dataGroupOffset(groupIndex
                                    /* if Interleaved segments Supported intermediateSegments */
                                    , (long) intoSegment_isFullCapacity/* endif */);
                            long dataGroup = readDataGroupAtOffset(intoSegment, dataGroupOffset);
                            long emptyBitMask = matchEmpty(dataGroup);
                            if (emptyBitMask != 0) { // [Positive likely branch]
                                int insertionSlotIndexWithinGroup =
                                        lowestMatchingSlotIndex(emptyBitMask);
                                intoSegment_outboundOverflowCount_perGroupAdditions +=
                                        computeOutboundOverflowCount_perGroupChanges(
                                                baseGroupIndex, groupIndex);

                                /* if Continuous segments */
                                // allocIndex = segmentSize optimization: just using the current
                                // intoSegment's size instead of calling
                                // lowestFreeAllocIndex(intoSegment_bitSetAndState) is an
                                // optimization based on the fact that we are populating intoSegment
                                // privately in split() so there can't be any "holes" in its alloc
                                // area due to entry removals.
                                int intoSegment_allocIndex = intoSegment_currentSize;
                                /* elif Interleaved segments */
                                int intoSegment_allocIndex = freeAllocIndexClosestTo(
                                        intoSegment_bitSetAndState,
                                        allocIndexBoundaryForLocalAllocation((int) groupIndex
                                            /* if Supported intermediateSegments */
                                            , intoSegment_isFullCapacity/* endif */));
                                intoSegment_bitSetAndState = setAllocBit(
                                        intoSegment_bitSetAndState, intoSegment_allocIndex);
                                /* endif */

                                byte tag = (byte) tagBits(hash);
                                writeEntry(intoSegment,
                                        /* if Interleaved segments Supported intermediateSegments */
                                        (long) intoSegment_isFullCapacity,/* endif */
                                        key, tag, value, groupIndex, dataGroup,
                                        insertionSlotIndexWithinGroup, intoSegment_allocIndex);
                                /* if Continuous segments || Supported intermediateSegments */
                                intoSegment_currentSize++;
                                /* endif */
                                break internalPutLoop;
                            }
                            groupIndexStep += 1; // [Quadratic probing]
                            groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                            // [No break condition in a loop searching for an empty slot]
                        }
                    }

                    // #### Purge the entry from fromSegment.
                    {
                        eraseKeyAndValueAtOffset(fromSegment, fromSegment_allocOffset);
                        fromSegment_bitSetAndState =
                                clearAllocBit(fromSegment_bitSetAndState, fromSegment_allocIndex);
                        // Empty the iteration slot:
                        iterDataGroup = setSlotEmpty(iterDataGroup, iterTrailingZeros);
                        // Not doing anything with the corresponding tag group, i. e. leaving
                        // "garbage" in the tag byte of the emptied slot.
                    }
                    continue groupIteration;
                }
                // Must break from or continue the groupIteration across all paths above.
                // Uncommenting the following statement should make the compiler complain about
                // "unreachable statement".
                /* comment */
                throw new AssertionError();
                /**/
            }
            // Write out iterDataGroup updated at [Empty the iteration slot] places inside
            // groupIteration loop.
            /* if Interleaved segments Supported intermediateSegments */
            // The value computed in the beginning of this loop cannot be reused because
            // fromSegment_isFullCapacity may change during the execution of this loop in
            // [Swap segments].
            iterDataGroupOffset =
                    dataGroupOffset(iterGroupIndex, (long) fromSegment_isFullCapacity);
            /* endif */
            writeDataGroupAtOffset(fromSegment, iterDataGroupOffset, iterDataGroup);
        }

        // TODO swap segments if intoSegment is of intermediate capacity and intoSegment_currentSize
        //  is above some threshold (so that there is a sufficient skew of entries towards
        //  intoSegment). The threshold may be a field of SmoothieMap dependent on the
        //  OptimizationFactors chosen for the map.

        // ### Update fromSegment's bitSetAndState and outbound overflow counts.
        setBitSetAndStateAfterBulkOperation(fromSegment, fromSegment_bitSetAndState);
        // Need to update all groups rather than only those where the outbound overflow count is
        // decremented to zero to overwrite potentially incorrect bits which may have been written
        // to slots in [Writing incorrect outbound overflow data bits].
        subtractOutboundOverflowCountsPerGroupAndUpdateAllGroups(fromSegment,
                /* if Interleaved segments Supported intermediateSegments */
                fromSegment_isFullCapacity,/* endif */
                fromSegment_outboundOverflowCount_perGroupDeductions);

        // ### Write out intoSegment's bitSetAndState and outbound overflow counts.
        /* if Continuous segments */
        long intoSegment_bitSetAndState = makeBitSetAndStateForPrivatelyPopulatedContinuousSegment(
                intoSegment_allocCapacity, newSegmentOrder, intoSegment_currentSize);
        /* endif */
        setBitSetAndState(intoSegment, intoSegment_bitSetAndState);
        addOutboundOverflowCountsPerGroup(intoSegment,
                /* if Interleaved segments Supported intermediateSegments */
                intoSegment_isFullCapacity,/* endif */
                intoSegment_outboundOverflowCount_perGroupAdditions);
        U.storeFence(); // [Safe segment publication]

        /* if Tracking hashCodeDistribution */
        // TODO [hashCodeDistribution null check]
        @Nullable HashCodeDistribution<K, V> hashCodeDistribution = this.hashCodeDistribution;
        if (hashCodeDistribution != null) {
            int numKeysForHalfOne =
                    hashTableHalfBits >>> (HASH__BASE_GROUP_INDEX_BITS - 1);
            hashCodeDistribution.accountSegmentSplit(
                    this, newSegmentOrder - 1, numKeysForHalfOne, fromSegment_initialSize);
        }
        /* endif */

        return swappedSegmentsInsideLoopAndFromSegmentIsHigher;
    }

    /** Invariant before calling this method: oldSegment's size is equal to the capacity. */
    @RarelyCalledAmortizedPerSegment
    private void inflateAndInsert(int modCount, int segmentOrder, Object oldSegment,
            long bitSetAndState, K key, long hash, V value) {

        // The old segment's bitSetAndState is never reset back to an operational value after this
        // statement.
        setBitSetAndState(oldSegment, BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE);

        /* if Enabled extraChecks */
        // Checking preconditions for copyEntriesDuringInflate()
        int allocCapacity = allocCapacity(bitSetAndState);
        verifyEqual(allocCapacity, SEGMENT_MAX_ALLOC_CAPACITY);
        verifyEqual(allocCapacity, segmentSize(bitSetAndState));
        /* endif */
        InflatedSegment<K, V> inflatedSegment = new InflatedSegment<>(segmentOrder, size);
        //noinspection unchecked
        ((Segment<K, V>) oldSegment).copyEntriesDuringInflate(this, inflatedSegment);

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

    //endregion

    //region removeAtSlot() and shrinking methods called from it

    /**
     * Returns the updated bitSetAndState, but doesn't update {@link Segment#bitSetAndState} field.
     * Callers should call {@link Segment#setBitSetAndState} themselves with the value returned from
     * this method.
     *
     * @apiNote allocOffset is passed to this method along with allocIndex because the computation
     * of the former is expensive in {@link InterleavedSegments}. However, when the computation is
     * cheap (in {@link ContinuousSegments}), this likely makes more harm than good because of high
     * register pressure and the necessity to push the values to and pull from the stack. See a
     * similar trade-off in {@link #insert} and {@link #makeSpaceAndInsert}.
     * TODO don't pass allocOffset for Continuous segments
     *  (and leave a to-do to compare perf in that case)
     */
    @HotPath
    private long removeAtSlotNoShrink(long bitSetAndState, Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupDecrements, long dataGroupOffset,
            long dataGroupWithEmptiedSlot, long allocIndex, long allocOffset) {
        size--;
        modCount++;

        if (outboundOverflowCount_perGroupDecrements != 0) { // Unlikely branch
            decrementOutboundOverflowCountsPerGroup(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    outboundOverflowCount_perGroupDecrements);
        }

        bitSetAndState = clearAllocBit(bitSetAndState, allocIndex);

        writeDataGroupAtOffset(segment, dataGroupOffset, dataGroupWithEmptiedSlot);
        eraseKeyAndValueAtOffset(segment, allocOffset);

        return bitSetAndState;
    }

    @HotPath
    private void removeAtSlot(long hash, Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupDecrements, long dataGroupOffset,
            long dataGroupWithEmptiedSlot, long allocIndex, long allocOffset) {
        long bitSetAndState = getBitSetAndState(segment);
        bitSetAndState = removeAtSlotNoShrink(bitSetAndState, segment,
                /* if Interleaved segments Supported intermediateSegments */
                isFullCapacitySegment,/* endif */
                outboundOverflowCount_perGroupDecrements, dataGroupOffset, dataGroupWithEmptiedSlot,
                allocIndex, allocOffset);
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
            //noinspection UnnecessaryReturnStatement
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
            //  (1) splitInflated() may produce two intermediate-capacity segments. This should be
            //  very rare because inflated segments themselves should be rare.
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
    @AmortizedPerSegment
    private void doShrinkInto(Object fromSegment, final long fromSegment_bitSetAndState,
            Object intoSegment, long intoSegment_bitSetAndState) {

        int intoSegment_allocCapacity = allocCapacity(intoSegment_bitSetAndState);
        /* if Interleaved segments Supported intermediateSegments */
        long fromSegment_isFullCapacity =
                isFullCapacity(fromSegment_bitSetAndState) ? 1L : 0L;
        // TODO check that Hotspot compiles this expression into branchless code.
        int intoSegment_isFullCapacity =
                intoSegment_allocCapacity == SEGMENT_MAX_ALLOC_CAPACITY ? 1 : 0;
        /* endif */
        long intoSegment_outboundOverflowCount_perGroupAdditions = 0;

        long fromSegment_bitSet = extractBitSetForIteration(fromSegment_bitSetAndState);
        // Branchless entries iteration: another option is checking every bit of the bitSet,
        // avoiding a relatively expensive call to numberOfLeadingZeros() and extra arithmetic
        // operations. But in doShrinkInto() there are expected to be many empty alloc indexes
        // (since we are able to shrink two segments into one) that would make a branch with a bit
        // checking unpredictable. This is a tradeoff similar to [Branchless hash table iteration].
        // TODO for Continuous segments, compare the approaches described above.
        // TODO for Interleaved segments, the cost of branchless iteration is exacerbated by an
        //  expensive allocOffset() call.
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

            long iterAllocOffset = allocOffset((long) iterAllocIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , fromSegment_isFullCapacity/* endif */);
            K key = readKeyAtOffset(fromSegment, iterAllocOffset);
            V value = readValueAtOffset(fromSegment, iterAllocOffset);

            // TODO check what is better - these two statements before or after
            //  the internal put operation, or one before and one after, or both after?
            fromSegment_bitSet = fromSegment_bitSet << iterAllocIndexStep;
            iterAllocIndexStep = Long.numberOfLeadingZeros(fromSegment_bitSet) + 1;

            // ### [Put the entry into intoSegment]
            long hash = keyHashCode(key);
            final long baseGroupIndex = baseGroupIndex(hash);
            // [Find empty slot]
            // TODO [Unbounded search loop]
            internalPutLoop:
            for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ; ) {
                long dataGroupOffset = dataGroupOffset(groupIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , (long) intoSegment_isFullCapacity/* endif */);
                long dataGroup = readDataGroupAtOffset(intoSegment, dataGroupOffset);
                long emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    int insertionSlotIndexWithinGroup =
                            lowestMatchingSlotIndex(emptyBitMask);
                    intoSegment_outboundOverflowCount_perGroupAdditions +=
                            computeOutboundOverflowCount_perGroupChanges(
                                    baseGroupIndex, groupIndex);

                    /* if Continuous segments */
                    int intoSegment_allocIndex =
                            lowestFreeAllocIndex(intoSegment_bitSetAndState);
                    intoSegment_bitSetAndState =
                            setLowestAllocBit(intoSegment_bitSetAndState);
                    /* elif Interleaved segments */
                    int intoSegment_allocIndex = freeAllocIndexClosestTo(
                            intoSegment_bitSetAndState,
                            allocIndexBoundaryForLocalAllocation((int) groupIndex
                                    /* if Supported intermediateSegments */
                                    , intoSegment_isFullCapacity/* endif */));
                    intoSegment_bitSetAndState = setAllocBit(
                            intoSegment_bitSetAndState, intoSegment_allocIndex);
                    /* endif */
                    if (intoSegment_allocIndex >= intoSegment_allocCapacity) {
                        // Hash table overflow is possible if there are put operations
                        // concurrent with this doShrinkInto() operation, or there is a racing
                        // doShrinkInto() operation.
                        throw new ConcurrentModificationException();
                    }

                    byte tag = (byte) tagBits(hash);
                    writeEntry(intoSegment,
                            /* if Interleaved segments Supported intermediateSegments */
                            (long) intoSegment_isFullCapacity,/* endif */
                            key, tag, value, groupIndex, dataGroup, insertionSlotIndexWithinGroup,
                            intoSegment_allocIndex);
                    break internalPutLoop;
                }
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [No break condition in a loop searching for an empty slot]
            }
        }

        // ### Update intoSegment's bitSetAndState and outbound overflow counts.
        intoSegment_bitSetAndState -= SEGMENT_ORDER_UNIT;
        setBitSetAndStateAfterBulkOperation(intoSegment, intoSegment_bitSetAndState);
        addOutboundOverflowCountsPerGroup(intoSegment,
                /* if Interleaved segments Supported intermediateSegments */
                intoSegment_isFullCapacity,/* endif */
                intoSegment_outboundOverflowCount_perGroupAdditions);
    }
    /* endif */ //comment*/ end if Flag|Always doShrink //*/

    //endregion

    //region Methods that replace inflated segments: deflate or split them

    /**
     * This method is called to deflate an inflated segment that became small and now an ordinary
     * segment can hold all it's entries.
     * @param hash a hash code of some entry belonging to the given segment. It allows to determine
     *        the index(es) of the given segment in {@link #segmentsArray}.
     */
    @RarelyCalledAmortizedPerSegment
    private void deflateSmall(long hash, InflatedSegment<K, V> inflatedSegment) {
        int modCount = getModCountOpaque();

        // The inflated segment's bitSetAndState is never reset back to an operational value after
        // this statement.
        long inflatedSegment_bitSetAndState =
                replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(inflatedSegment);
        int segmentOrder = segmentOrder(inflatedSegment_bitSetAndState);
        int deflatedSegment_allocCapacity = SEGMENT_MAX_ALLOC_CAPACITY;
        Segment<Object, Object> deflatedSegment =
                allocateNewSegmentWithoutSettingBitSetAndSet(deflatedSegment_allocCapacity);

        doDeflateSmall(
                segmentOrder, inflatedSegment, deflatedSegment, deflatedSegment_allocCapacity);
        // storeFence() is called inside doDeflateSmall() to make publishing of deflatedSegment
        // safe.

        int firstSegmentIndex = firstSegmentIndexByHashAndOrder(hash, segmentOrder);
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(), firstSegmentIndex, segmentOrder,
                deflatedSegment);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        checkModCountOrThrowCme(modCount);
    }

    /**
     * Returns the number of entries moved from the inflated segment into an ordinary segment.
     *
     * intoSegment_allocCapacity could have not been passed and {@link #SEGMENT_MAX_ALLOC_CAPACITY}
     * used instead, but doing this to make the choice of intoSegment_allocCapacity the sole
     * responsibility of {@link #deflateSmall}.
     */
    @RarelyCalledAmortizedPerSegment
    private void doDeflateSmall(int segmentOrder, InflatedSegment<K, V> inflatedSegment,
            Object intoSegment, int intoSegment_allocCapacity) {
        int intoSegment_currentSize = 0;
        long intoSegment_outboundOverflowCount_perGroupAdditions = 0;
        /* if Interleaved segments */
        long intoSegment_bitSetAndState =
                makeNewBitSetAndState(intoSegment_allocCapacity, segmentOrder);
        /* if Enabled extraChecks */
        verifyEqual(intoSegment_allocCapacity, SEGMENT_MAX_ALLOC_CAPACITY);
        /* endif */
        /* endif */

        for (Node<K, V> node : inflatedSegment.delegate.keySet()) {
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

            // ### [Put the entry into intoSegment]
            final long baseGroupIndex = baseGroupIndex(hash);
            // [Find empty slot]
            internalPutLoop:
            for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ; ) {
                long dataGroup = /* if Interleaved segments */FullCapacitySegment./* endif */
                        readDataGroup(intoSegment, groupIndex);
                long emptyBitMask = matchEmpty(dataGroup);
                if (emptyBitMask != 0) { // [Positive likely branch]
                    int insertionSlotIndexWithinGroup =
                            lowestMatchingSlotIndex(emptyBitMask);
                    intoSegment_outboundOverflowCount_perGroupAdditions +=
                            computeOutboundOverflowCount_perGroupChanges(
                                    baseGroupIndex, groupIndex);

                    /* if Continuous segments */
                    // [allocIndex = segmentSize optimization]
                    int intoSegment_allocIndex = intoSegment_currentSize;
                    /* elif Interleaved segments */
                    int intoSegment_allocIndex = freeAllocIndexClosestTo(
                            intoSegment_bitSetAndState,
                            // Using FullCapacitySegment's allocIndexBoundaryForLocalAllocation()
                            // method here rather than InterleavedSegment's because intoSegment is
                            // guaranteed to be a full-capacity segment. See a check in the
                            // beginning of this method.
                            /* if Interleaved segments */FullCapacitySegment./* endif */
                                    allocIndexBoundaryForLocalAllocation((int) groupIndex));
                    intoSegment_bitSetAndState = setAllocBit(
                            intoSegment_bitSetAndState, intoSegment_allocIndex);
                    /* endif */
                    if (intoSegment_allocIndex >= intoSegment_allocCapacity) {
                        // This is possible if entries are added to the inflated segment
                        // concurrently with the deflation.
                        throw new ConcurrentModificationException();
                    }

                    byte tag = (byte) tagBits(hash);
                    /* if Interleaved segments */FullCapacitySegment./* endif */writeEntry(
                            intoSegment, key, tag, value, groupIndex, dataGroup,
                            insertionSlotIndexWithinGroup, intoSegment_allocIndex);
                    break internalPutLoop;
                }
                groupIndexStep += 1; // [Quadratic probing]
                groupIndex = addGroupIndex(groupIndex, groupIndexStep);
                // [No break condition in a loop searching for an empty slot]
            }

            intoSegment_currentSize++;
            // Unlike in other similar procedures, don't increment modCount here because
            // intoSegment is not yet published to segmentsArray.
        }

        // Update intoSegment's bitSetAndState and outbound overflow counts.
        /* if Continuous segments */
        long intoSegment_bitSetAndState = makeBitSetAndStateForPrivatelyPopulatedContinuousSegment(
                intoSegment_allocCapacity, segmentOrder, intoSegment_currentSize);
        /* endif */
        setBitSetAndState(intoSegment, intoSegment_bitSetAndState);
        /* if Interleaved segments Supported intermediateSegments */
        int intoSegment_isFullCapacity = 1;
        /* endif */
        // No point in specializing addOutboundOverflowCountsPerGroup() for a full-capacity segment
        // because we are in a RarelyCalledAmortizedPerSegment method.
        addOutboundOverflowCountsPerGroup(intoSegment,
                /* if Interleaved segments Supported intermediateSegments */
                intoSegment_isFullCapacity,/* endif */
                intoSegment_outboundOverflowCount_perGroupAdditions);
        U.storeFence(); // [Safe segment publication]
    }

    @RarelyCalledAmortizedPerSegment
    private void replaceInflatedWithEmptyOrdinary(
            int segmentIndex, InflatedSegment<K, V> inflatedSegment) {
        // The inflated segment's bitSetAndState is never reset back to an operational value after
        // this statement.
        long inflatedSegment_bitSetAndState =
                replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(inflatedSegment);
        int segmentOrder = segmentOrder(inflatedSegment_bitSetAndState);
        int ordinarySegmentAllocCapacity = getInitialSegmentAllocCapacity(segmentOrder);
        Segment<Object, Object> ordinarySegment =
                createNewSegment(ordinarySegmentAllocCapacity, segmentOrder);
        int firstSegmentIndex = firstSegmentIndexByIndexAndOrder(segmentIndex, segmentOrder);
        // replaceInSegmentsArray() increments modCount.
        replaceInSegmentsArray(getNonNullSegmentsArrayOrThrowCme(),
                firstSegmentIndex, segmentOrder, ordinarySegment);
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
        // published to segmentsArray and then populated using the "public" putImpl() procedure (see
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
                createNewSegment(resultSegmentsAllocCapacity, resultSegmentsOrder);
        Object[] segmentsArray = getNonNullSegmentsArrayOrThrowCme();
        replaceInSegmentsArray(
                segmentsArray, firstIndexOfResultSegmentOne, resultSegmentsOrder, resultSegmentOne);
        modCount++; // Matches the modCount field increment performed in replaceInSegmentsArray().

        int firstIndexOfResultSegmentTwo =
                siblingSegmentIndex(firstIndexOfResultSegmentOne, resultSegmentsOrder);
        Segment<K, V> resultSegmentTwo =
                createNewSegment(resultSegmentsAllocCapacity, resultSegmentsOrder);
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
            long hash_segmentLookupBits = segmentLookupBits(hash);
            Object segment = segmentBySegmentLookupBits(hash_segmentLookupBits);
            /* if Interleaved segments Supported intermediateSegments */
            int isFullCapacitySegment = isFullCapacitySegment(hash_segmentLookupBits, segment);
            /* endif */
            // [Publishing result segments before population in splitInflated] explains why we are
            // using "public" putImpl() method here. (1)
            if (putImpl(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    key, hash, value, true /* onlyIfAbsent */) != null) {
                throw new ConcurrentModificationException();
            }
            numMovedEntries++;
        }
        // Restoring the correct size after calling putImpl() with entries that are already in the
        // map in the loop above.
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
        verifyThat(segmentOrder <= segmentsArrayOrder);
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
        checkNonNull(action);
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
        checkNonNull(predicate);
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
        checkNonNull(function);
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
        checkNonNull(filter);
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
            // TODO specialize to avoid access into value's memory: see
            //  [Protecting null comparisons].
            return SmoothieMap.this.remove(key) != null;
        }

        @Override
        public final void forEach(Consumer<? super K> action) {
            checkNonNull(action);
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
            checkNonNull(c);
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
            checkNonNull(action);
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
            checkNonNull(c);
            return SmoothieMap.this.removeIf((k, v) -> c.contains(v));
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            checkNonNull(c);
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
        /* if Interleaved segments Supported intermediateSegments */
        int currentSegment_isFullCapacity;
        /* endif */
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
            /* if Interleaved segments Supported intermediateSegments */
            this.currentSegment_isFullCapacity =
                    smoothie.isFullCapacitySegmentByIndex(currentSegmentIndex, currentSegment);
            /* endif */
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
                // Logically, writing iterAllocIndex to -1 belongs to
                // advanceOrdinarySegmentIteration(), but instead doing it here and in
                // initCurrentSegment() in the [Current segment is inflated] branch to avoid
                // unnecessary field writes when an ordinary segment is followed by another ordinary
                // segment (almost every time) in the expense of a single unnecessary field write
                // (here) in the end of the iteration if the last iterated segment was inflated
                // (which should almost never happen).
                this.iterAllocIndex = -1;
                // Don't advance, the iteration is complete. iterAllocIndex stores -1 and
                // inflatedSegmentIterator stores null at this point.
                return;
            }
            @Nullable Segment<K, V> nextSegment = this.nextSegment;
            /* if Enabled extraChecks */
            verifyNonNull(nextSegment);/* endif */
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
            K key = readKeyCheckedAtIndex(currentSegment, (long) allocIndex);
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
            V value = readValueCheckedAtIndex(currentSegment, (long) allocIndex);
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
            checkAllocIndex(currentSegment, (long) allocIndex);
            long allocOffset = allocOffset((long) allocIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) this.currentSegment_isFullCapacity/* endif */);
            K key = readKeyAtOffset(currentSegment, allocOffset);
            V value = readValueAtOffset(currentSegment, allocOffset);
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
        final long allocOffset;
        final int mc = modCount;

        SmoothieEntry(Object segment, long allocOffset) {
            super(readKeyAtOffset(segment, allocOffset), readValueAtOffset(segment, allocOffset));
            this.segment = segment;
            this.allocOffset = allocOffset;
        }

        @Override
        public V setValue(V value) {
            if (mc != modCount)
                throw new ConcurrentModificationException();
            writeValueAtOffset(segment, allocOffset, value);
            return super.setValue(value);
        }
    }

    //endregion

    /**
     * TODO describe what's implied by the comment to {@link BitSetAndState}.
     *
     * The lines of inheritance of Segment objects (intended to enforce field arrangement and
     * grouping in the memory layout of segment objects, since JVMs must obey to the inheritance
     * boundaries: see the comment for {@link ContinuousSegments.HashTableArea}) are the following:
     *
     * Continuous segments:
     *  {@link ContinuousSegments.HashTableArea} ->
     *  {@link ContinuousSegment_BitSetAndStateArea} ->
     *  Segment (this class) ->
     *    {@link ContinuousSegments.SegmentBase} ->
     *      {@link ContinuousSegments.Segment17}
     *      ..
     *      {@link ContinuousSegments.Segment48}
     *    {@link InflatedSegment}
     *
     * Full-capacity interleaved segments:
     *  {@link InterleavedSegment_BitSetAndStateArea} ->
     *  Segment (this class) ->
     *  {@link InterleavedSegments.FullCapacitySegment_AllocationSpaceBeforeGroup0} ->
     *  ...
     *  {@link InterleavedSegments.FullCapacitySegment_AllocationSpaceAfterGroup7} ->
     *  {@link InterleavedSegments.FullCapacitySegment} ->
     *  {@link InflatedSegment} (if intermediate-capacity segments are not supported)
     *
     * Intermediate-capacity interleaved segments:
     *  {@link InterleavedSegment_BitSetAndStateArea} ->
     *  Segment (this class) ->
     *  {@link InterleavedSegments.IntermediateCapacitySegment_AllocationSpaceBeforeGroup0} ->
     *  ...
     *  {@link InterleavedSegments.IntermediateCapacitySegment_AllocationSpaceAfterGroup7} ->
     *  {@link InterleavedSegments.IntermediateCapacitySegment} ->
     *  {@link InflatedSegment}
     *
     * The confusing part is that {@link ContinuousSegment_BitSetAndStateArea} and {@link
     * InterleavedSegment_BitSetAndStateArea} are generated from the same source but appear in
     * different places in the hierarchy for Continuous and Interleaved segments.
     */
    abstract static class Segment<K, V>
            /* comment // if-elif Continuous|Interleaved segments templating: The following JPSG's
            `if` looks like manual templating, but we don't want to add with Continuous|Inflated
            segments in the header of SmoothieMap.java because there are many distinct references in
            this class to both Continuous and Inflated segments and either one of them would be
            corrupted by Continuous|Inflated segments templating. //*/
            /* if Continuous segments */
            extends ContinuousSegment_BitSetAndStateArea<K, V>
            /* elif Interleaved segments //
            extends InterleavedSegment_BitSetAndStateArea<K, V>
            // endif */
            implements Cloneable {

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

        /** = numberOfTrailingZeros({@link HashTable#HASH_TABLE_SLOTS}). */
        @CompileTimeConstant
        static final int LOG_HASH_TABLE_SIZE = 6;
        /** = numberOfTrailingZeros({@link HashTable#HASH_TABLE_GROUPS}). */
        @CompileTimeConstant
        static final int HASH__BASE_GROUP_INDEX_BITS = 3;
        static final int TAG_HASH_BITS = 8;
        private static final long TAG_HASH_BIT_MASK = (1 << TAG_HASH_BITS) - 1;

        static {
            verifyEqual(LOG_HASH_TABLE_SIZE, numberOfTrailingZeros(HASH_TABLE_SLOTS));
            verifyEqual(HASH__BASE_GROUP_INDEX_BITS, numberOfTrailingZeros(HASH_TABLE_GROUPS));
        }

        static long tagBits(long hash) {
            return (hash >> HASH__BASE_GROUP_INDEX_BITS) & TAG_HASH_BIT_MASK;
        }

        static <K> K readKeyAtOffset(Object segment, long allocOffset) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            //noinspection unchecked
            K key = (K) U.getObject(segment, allocOffset);
            /* if Enabled extraConcurrencyChecks */
            // Protecting null comparisons: comparisons of objects with nulls leads to access into
            // object's memory at least with some GC algorithms on Hotspot, which is wasteful if
            // the queried key is the same object as stored inside the SmoothieMap.
            // TODO research with which GCs this is the case
            if (key == null) {
                throw new ConcurrentModificationException();
            }
            /* endif */
            return key;
        }

        /**
         * This method is called from iterators (either directly, or via {@link
         * #readKeyCheckedAtIndex} or {@link #readValueCheckedAtIndex}) where there is no guarantee
         * that the allocIndex belongs to the provided segment object because of potential racy
         * concurrent method calls on the Iterator object itself. allocIndex may be equal to {@link
         * #SEGMENT_MAX_ALLOC_CAPACITY} - 1 (in some segment) and the segment provided may be a
         * segment with {@link #SEGMENT_INTERMEDIATE_ALLOC_CAPACITY} which follows the full-capacity
         * to which the allocIndex really belongs.
         *
         * To avoid memory corruption or segmentation faults, we must read the segment's {@link
         * BitSetAndState#allocCapacity} and compare to the allocIndex, similar to what JVM does
         * during normal array accesses. We also check that the segment object is not null because
         * if the Iterator object is created in one thread and {@link ImmutableKeyIterator#next()}
         * is called from another thread {@link ImmutableSmoothieIterator#currentSegment} might not
         * be visible and null is read from this field and passed into readKeyCheckedAtIndex().
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
        static void checkAllocIndex(Object segment, @NonNegative long allocIndex) {
            if (segment == null) {
                throw new ConcurrentModificationException();
            }
            long bitSetAndState = getBitSetAndState(segment);
            int allocCapacity = allocCapacity(bitSetAndState);
            if (allocIndex >= (long) allocCapacity) {
                throw new ConcurrentModificationException();
            }
        }

        static <K> K readKeyCheckedAtIndex(Object segment, @NonNegative long allocIndex) {
            checkAllocIndex(segment, allocIndex);
            //noinspection unchecked
            K key = (K) U.getObject(segment, allocOffset(allocIndex));
            /* if Enabled extraConcurrencyChecks */
            // [Protecting null comparisons]
            if (key == null) {
                // Concurrent segment modification or an inflated segment: key may be null not only
                // because of concurrent modification (entry deletion) in the segment, but also
                // because the segment provided is in fact an InflatedSegment which has nulls at all
                // alloc indexes. The Javadoc comment for checkAllocIndex() explains how a wrong
                // segment object may be passed into this method.
                throw new ConcurrentModificationException();
            }
            /* endif */
            return key;
        }

        static <V> V readValueAtOffset(Object segment, long allocOffset) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            //noinspection unchecked
            V value = (V) U.getObject(segment, valueOffsetFromAllocOffset(allocOffset));
            /* if Enabled extraConcurrencyChecks */
            // [Protecting null comparisons]
            if (value == null) {
                throw new ConcurrentModificationException();
            }
            /* endif */
            return value;
        }

        static <V> V readValueCheckedAtIndex(Object segment, @NonNegative long allocIndex) {
            checkAllocIndex(segment, allocIndex);
            //noinspection unchecked
            V value = (V) U.getObject(segment, valueOffsetFromAllocOffset(allocOffset(allocIndex)));
            /* if Enabled extraConcurrencyChecks */
            // [Protecting null comparisons]
            if (value == null) {
                // [Concurrent segment modification or an inflated segment]
                throw new ConcurrentModificationException();
            }
            /* endif */
            return value;
        }
        static void writeValueAtOffset(Object segment, long allocOffset, Object value) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            U.putObject(segment, valueOffsetFromAllocOffset(allocOffset), value);
        }

        /**
         * Generalized version of {@link
         * InterleavedSegments.FullCapacitySegment#writeKeyAndValueAtIndex} and {@link
         * InterleavedSegments.IntermediateCapacitySegment#writeKeyAndValueAtIndex}.
         */
        static void writeKeyAndValueAtIndex(Object segment,
                /* if Interleaved segments Supported intermediateSegments */
                long isFullCapacitySegment,/* endif */
                int allocIndex, Object key, Object value) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            long allocOffset = allocOffset((long) allocIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , isFullCapacitySegment/* endif */);
            U.putObject(segment, allocOffset, key);
            U.putObject(segment, valueOffsetFromAllocOffset(allocOffset), value);
        }

        /**
         * This method is kept separate from {@link #writeKeyAndValueAtIndex} because with some GC
         * algorithms more optimal code may be emitted when writing nulls that any object (null or
         * non-null). Also it's semantically clearer and has a better name.
         */
        static void eraseKeyAndValueAtIndex(Object segment,
                /* if Interleaved segments Supported intermediateSegments */
                long isFullCapacitySegment,/* endif */
                int allocIndex) {
            long allocOffset = allocOffset((long) allocIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , isFullCapacitySegment/* endif */);
            eraseKeyAndValueAtOffset(segment, allocOffset);
        }

        static void eraseKeyAndValueAtOffset(Object segment, long allocOffset) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            U.putObject(segment, allocOffset, null);
            U.putObject(segment, valueOffsetFromAllocOffset(allocOffset), null);
        }

        /**
         * Generalized version of {@link
         * InterleavedSegments.FullCapacitySegment#writeEntry(Object, Object, byte, Object, long, long, int, int)}.
         */
        static <K, V> void writeEntry(Object segment,
                /* if Interleaved segments Supported intermediateSegments */
                long isFullCapacitySegment,/* endif */
                K key, byte tag, V value, long groupIndex, long dataGroup, int slotIndexWithinGroup,
                int allocIndex) {
            writeTagAndData(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    groupIndex, slotIndexWithinGroup, tag, makeData(dataGroup, allocIndex));
            writeKeyAndValueAtIndex(segment,
                    /* if Interleaved segments Supported intermediateSegments */
                    isFullCapacitySegment,/* endif */
                    allocIndex, key, value);
        }

        //region Segment's internal bulk operations

        @RarelyCalledAmortizedPerSegment
        abstract void copyEntriesDuringInflate(
                SmoothieMap<K, V> map, InflatedSegment<K, V> intoSegment);

        abstract void setAllDataGroups(long dataGroup);

        abstract void clearHashTableAndAllocArea();

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
                map.size -= (long) segmentSize;
                map.modCount++;
                clearHashTableAndAllocArea();
                this.bitSetAndState = clearBitSet(bitSetAndState);
                clearOutboundOverflowCountsPerGroup();
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

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#hashCode(SmoothieMap)} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#hashCode(SmoothieMap)}.
         */
        @Override
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
            // 3. [Int-indexed loop to avoid a safepoint poll].
            // TODO check that Hotspot actually removes a safepoint poll for this unusual loop shape
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);
                V value = readValueAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after
                //  the hash code computation, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                h += longKeyHashCodeToIntHashCode(map.keyHashCode(key)) ^ map.valueHashCode(value);
            }
            return h;
        }
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#forEach} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#forEach}.
         */
        @Override
        void forEach(BiConsumer<? super K, ? super V> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);
                V value = readValueAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(key, value);
            }
        }
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#forEachKey} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#forEachKey}.
         */
        @Override
        void forEachKey(Consumer<? super K> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(key);
            }
        }
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#forEachValue} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#forEachValue}.
         */
        @Override
        void forEachValue(Consumer<? super V> action) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                V value = readValueAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                action.accept(value);
            }
        }
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#forEachWhile} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#forEachWhile}.
         */
        @Override
        boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);
                V value = readValueAtOffset(this, iterAllocOffset);

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
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#replaceAll} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#replaceAll}.
         */
        @Override
        void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);
                V value = readValueAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after
                //  the action, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                writeValueAtOffset(this, iterAllocOffset, function.apply(key, value));
            }
        }
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#containsValue} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#containsValue}.
         */
        @Override
        boolean containsValue(SmoothieMap<K, V> map, V queriedValue) {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                V internalVal = readValueAtOffset(this, iterAllocOffset);

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
        /* endif */

        /* if !(Interleaved segments Supported intermediateSegments) */
        /**
         * Mirror of {@link InterleavedSegments.FullCapacitySegment#writeAllEntries} and
         * {@link InterleavedSegments.IntermediateCapacitySegment#writeAllEntries}.
         */
        @Override
        void writeAllEntries(ObjectOutputStream s) throws IOException {
            long bitSet = extractBitSetForIteration(bitSetAndState);
            // [Iteration in bulk segment methods]
            for (int iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1,
                 iterAllocIndex = Long.SIZE;
                 (iterAllocIndex -= iterAllocIndexStep) >= 0;) {
                long iterAllocOffset = allocOffset((long) iterAllocIndex);
                K key = readKeyAtOffset(this, iterAllocOffset);
                V value = readValueAtOffset(this, iterAllocOffset);

                // TODO check what is better - these two statements before or after writing the
                //  objects to the output stream, or one before and one after, or both after?
                bitSet = bitSet << iterAllocIndexStep;
                iterAllocIndexStep = Long.numberOfLeadingZeros(bitSet) + 1;

                s.writeObject(key);
                s.writeObject(value);
            }
        }
        /* endif */

        @Override
        int removeIf(
                SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount) {
            // TODO update according to other bulk methods above
            long bitSetAndState = this.bitSetAndState;
            int initialModCount = modCount;
            try {
                // [Branchless hash table iteration]. Unlike [Branchless hash table iteration] in
                // compactEntriesDuringSegmentSwap() the branchless iteration here is more likely to
                // be a better choice than byte-by-byte checking approach because the segment is
                // expected to be averagely filled during removeIf() (more specifically, expected
                // number of full slots is about (SEGMENT_MAX_ALLOC_CAPACITY +
                // (SEGMENT_MAX_ALLOC_CAPACITY / 2)) / 2 = 36 out of HASH_TABLE_SLOTS = 64, or 56%),
                // so byte-by-byte checking branch would be even less predictable in removeIf() than
                // in compactEntriesDuringSegmentSwap().
                // TODO compare the approaches, separately from compactEntriesDuringSegmentSwap()
                //  and split() because the branch probability is different.

                // [Int-indexed loop to avoid a safepoint poll]
                for (int iterGroupIndex = 0; iterGroupIndex < HASH_TABLE_GROUPS; iterGroupIndex++) {
                    long iterDataGroupOffset = dataGroupOffset((long) iterGroupIndex);
                    long dataGroup = readDataGroupAtOffset(this, iterDataGroupOffset);

                    groupIteration:
                    for (long bitMask = matchFull(dataGroup);
                         bitMask != 0L;
                         bitMask = clearLowestSetBit(bitMask)) {

                        // [Inlined lowestMatchingSlotIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                        long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                        long allocOffset = allocOffset(allocIndex);

                        K key = readKeyAtOffset(this, allocOffset);
                        V value = readValueAtOffset(this, allocOffset);

                        if (!filter.test(key, value)) {
                            continue groupIteration;
                        }

                        // TODO use hosted overflow mask (when implemented; inspired by
                        //  hostedOverflowCounts in F14) to avoid a potentially expensive
                        //  keyHashCode() call here.
                        long baseGroupIndex = baseGroupIndex(map.keyHashCode(key));
                        long outboundOverflowCount_perGroupDecrements =
                                computeOutboundOverflowCount_perGroupChanges(
                                        baseGroupIndex, (long) iterGroupIndex);
                        // TODO research if it's possible to do something better than just call
                        //  removeAtSlotNoShrink() in a loop, which may result in calling expensive
                        //  operations a lot of times if nearly all entries are removed from the
                        //  segment during this loop.
                        bitSetAndState = map.removeAtSlotNoShrink(bitSetAndState, this,
                                outboundOverflowCount_perGroupDecrements, iterDataGroupOffset,
                                setSlotEmpty(dataGroup, trailingZeros), allocIndex, allocOffset);
                        // Matches the modCount field increment performed in removeAtSlotNoShrink().
                        modCount++;
                    }
                }
            } finally {
                // Writing bitSetAndState in a finally block because we want the segment to remain
                // in a consistent state if an exception was thrown from filter.test(), or in a
                // more up-to-date, debuggable state if a ConcurrentModificationException was thrown
                // from readKeyAtOffset() or readValueAtOffset().
                if (modCount != initialModCount) {
                    setBitSetAndState(this, bitSetAndState);
                }
            }
            return modCount;
        }

        //endregion

        //region Segment's stats and debug bulk operations

        abstract void aggregateStats(
                SmoothieMap<K, V> map, OrdinarySegmentStats ordinarySegmentStats);

        /** @deprecated in order not to forget to remove calls from production code */
        @Deprecated
        abstract String debugToString();

        /** @deprecated in order not to forget to remove calls from production code */
        @Deprecated
        abstract @Nullable DebugHashTableSlot<K, V>[] debugHashTable(SmoothieMap<K, V> map);

        static class DebugHashTableSlot<K, V> {
            final byte tagByte;
            final byte dataByte;
            final int allocIndex;
            final @Nullable K key;
            final long hash;
            final byte tagByteFromKey;
            final @Nullable V value;

            @SuppressWarnings("unchecked")
            DebugHashTableSlot(SmoothieMap<K, V> map, Segment<K, V> segment,
                    int allocIndex, long valueOffset, byte tagByte, int dataByte) {
                this.tagByte = tagByte;
                this.dataByte = (byte) dataByte;
                this.allocIndex = allocIndex;
                /* if Interleaved segments Supported intermediateSegments */
                long isFullCapacitySegment = isFullCapacity(getBitSetAndState(segment)) ? 1L : 0L;
                /* endif */
                long allocOffset = allocOffset((long) allocIndex
                        /* if Interleaved segments Supported intermediateSegments */
                        , isFullCapacitySegment/* endif */);
                this.key = (K) U.getObject(segment, allocOffset);
                if (key != null) {
                    hash = map.keyHashCode(key);
                    tagByteFromKey = (byte) tagBits(hash);
                } else {
                    hash = 0L;
                    tagByteFromKey = 0;
                }
                this.value = (V) U.getObject(segment, valueOffset);
            }

            @SuppressWarnings("AutoBoxing")
            @Override
            public String toString() {
                return String.format("%8s,%2d,%8s,%s",
                        Integer.toBinaryString(Byte.toUnsignedInt(tagByte)), allocIndex,
                        Integer.toBinaryString(Byte.toUnsignedInt(tagByteFromKey)), key);
            }
        }

        //endregion
    }

    /**
     * InflatedSegment inherits {@link Segment} and therefore the hash table area (either {@link
     * ContinuousSegments.HashTableArea} or within {@link InterleavedSegments}) which is unused
     * in an InflatedSegment. This is done so that on the hot point access path checking whether the
     * segment being queried is inflated or not can be done _after_ unsuccessful key search in the
     * hash table (in the first accessed hash table's group), not before it.
     * See [InflatedSegment checking after unsuccessful key search].
     */
    static class InflatedSegment<K, V>
            /* if Continuous segments //
            extends Segment<K, V>
            // elif Interleaved segments Supported intermediateSegments */
            extends InterleavedSegments.IntermediateCapacitySegment<K, V>
            /* elif Interleaved segments NotSupported intermediateSegments //
            extends InterleavedSegments.FullCapacitySegment<K, V>
            // endif */ {
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
            setAllDataGroups(INFLATED_SEGMENT__MARKER_DATA_GROUP);
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
            setAllDataGroups(INFLATED_SEGMENT__MARKER_DATA_GROUP);
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
                K key, long hash,
                BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
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
            } else if (shouldBeSplit(smoothie, segmentOrder(bitSetAndState))) {
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
         */
        private boolean shouldBeSplit(SmoothieMap<K, V> smoothie, int segmentOrder) {
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
                // [Positive likely branch]
                if (segmentOrder > maxSplittableSegmentOrder(lastComputedAverageSegmentOrder)) {
                    return false;
                } else {
                    lastComputedAverageSegmentOrder =
                            smoothie.computeAverageSegmentOrder(smoothie.size);
                    int maxSplittableSegmentOrder =
                            maxSplittableSegmentOrder(lastComputedAverageSegmentOrder);
                    return segmentOrder <= maxSplittableSegmentOrder;
                }
            } else {
                // Cannot split a segment if it is already of the maximum possible order.
                return false;
            }
        }

        /**
         * @param matchValue if non-null the entry's value should be equal to matchValue for removal
         *        or replacement.
         * @return a value removed or replaced in the map, or null if no change was made. If
         * matchValue is null, the returned value is the internal value removed or replaced in the
         * map. If matchValue is non-null, the returned value could be the internal value _or_ the
         * matchValue itself.
         */
        @Nullable V removeOrReplace(SmoothieMap<K, V> smoothie, K key, long hash,
                @Nullable V matchValue, @Nullable V replacementValue) {
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
            if (shouldBeSplit(smoothie, segmentOrder)) {
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
                    checkAndReportIfTooLarge(
                            hashCodeDistribution, segmentOrder, smoothie, delegate.size(), key);
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
                Function<? super K, ? extends @Nullable V> mappingFunction) {
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

        @Nullable V compute(SmoothieMap<K, V> smoothie, K key, long hash,
                BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction)
        {
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
                BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
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
                int segmentOrder, SmoothieMap<K, V> smoothie, int delegateSize, K excludedKey) {
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
        final void clearHashTableAndAllocArea() {
            throw new UnsupportedOperationException();
        }

        @DoNotCall
        @Deprecated
        @Override
        final void copyEntriesDuringInflate(SmoothieMap<K, V> map, InflatedSegment<K, V> intoSegment) {
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
        void forEachKey(Consumer<? super K> action) {
            delegate.keySet().forEach(node -> action.accept(node.getKey()));
        }

        @Override
        void forEachValue(Consumer<? super V> action) {
            delegate.keySet().forEach(node -> action.accept(node.getValue()));
        }

        @Override
        boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
            for (Node<K, V> node : delegate.keySet()) {
                if (!predicate.test(node.getKey(), node.getValue())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            delegate.keySet().forEach(
                    node -> node.setValue(function.apply(node.getKey(), node.getValue()))
            );
        }

        @Override
        boolean containsValue(SmoothieMap<K, V> map, V queriedValue) {
            return delegate.keySet().stream().allMatch(node -> {
                V internalVal = node.getValue();
                //noinspection ObjectEquality: identity comparision is intended
                boolean valuesIdentical = queriedValue == internalVal;
                return valuesIdentical || map.valuesEqual(queriedValue, internalVal);
            });
        }

        @SuppressWarnings("RedundantThrows")
        @Override
        void writeAllEntries(ObjectOutputStream s) throws IOException {
            delegate.keySet().forEach(node -> {
                try {
                    s.writeObject(node.getKey());
                    s.writeObject(node.getValue());
                } catch (IOException e) {
                    rethrowUnchecked(e);
                }
            });
        }

        @Override
        int removeIf(
                SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount) {
            throw new UnsupportedOperationException("TODO");
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
}
