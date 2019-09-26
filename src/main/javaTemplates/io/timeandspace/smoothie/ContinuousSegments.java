/* if Continuous segments */
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

import com.sun.xml.internal.bind.v2.TODO;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ConcurrentModificationException;

import static io.timeandspace.smoothie.BitSetAndState.allocCapacity;
import static io.timeandspace.smoothie.BitSetAndState.clearAllocBit;
import static io.timeandspace.smoothie.BitSetAndState.lowestFreeAllocIndex;
import static io.timeandspace.smoothie.BitSetAndState.makeBitSetAndStateWithNewAllocCapacity;
import static io.timeandspace.smoothie.BitSetAndState.makeNewBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.segmentSize;
import static io.timeandspace.smoothie.BitSetAndState.setLowestAllocBit;
import static io.timeandspace.smoothie.HashTable.GROUP_SLOTS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_SLOTS;
import static io.timeandspace.smoothie.LongMath.clearLowestSetBit;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.UnsafeUtils.minInstanceFieldOffset;
import static io.timeandspace.smoothie.Utils.verifyThat;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;

final class ContinuousSegments {
    /**
     * HashTableArea places all 8 (= {@link HashTable#HASH_TABLE_GROUPS}) groups of {@link
     * HashTable} in a continuous block of memory.
     *
     * HashTableArea is made a superclass of {@link SmoothieMap.Segment} to ensure that {@link SmoothieMap.Segment#k0} and
     * {@link SmoothieMap.Segment#v0} are laid before the primitive fields by the JVM and therefore are followed
     * directly by {@link SmoothieMap.Segment17#k1}, etc. This is guaranteed because JVMs don't mix fields
     * declared in superclasses and subclasses.
     *
     * All instance fields of HashTableArea are private and must not be accessed using normal
     * .fieldName Java syntax, because JVMs are free to lay the fields in an arbitrary order, while
     * we need a specific, stable layout. This layout corresponds to the order in which the fields
     * are declared. The data is accessed using {@link #CONTROLS_OFFSET} and {@link
     * #DATA_SLOTS_OFFSET}.
     */
    static class HashTableArea<K, V> extends AbstractSegment<K, V> {
        /**
         * 64 1-byte control slots. The lower 7 bits of a used slot are taken from {@link
         * SmoothieMap}'s key hash code. The highest bit of 1 denotes a special control, either
         * {@link SmoothieMap.Segment#EMPTY_CONTROL} or {@link SmoothieMap.Segment#DELETED_CONTROL}.
         *
         * {@link SmoothieMap.Segment#INFLATED_SEGMENT_MARKER_CONTROL} value indicates that the segment is an
         * {@link SmoothieMap.InflatedSegment}.
         */
        @SuppressWarnings("unused")
        private long c0, c1, c2, c3, c4, c5, c6, c7;

        /** The cloned {@link #c0} enables branchless operations. */
        @SuppressWarnings("unused")
        private long c0Clone;

        /**
         * 1-byte data slots, corresponding to the control slots. The lower 6 bits of a used slot
         * contain an entry allocation index, e. g. 0 means that {@link SmoothieMap}'s entry is
         * stored in {@link SmoothieMap.Segment#k0} and {@link SmoothieMap.Segment#v0}. The highest 2 bits are taken from
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

        static final long CONTROLS_OFFSET;
        /**
         * Pre-casted constant: since {@link #CONTROLS_OFFSET} is not a compile-time constant, it
         * might be better to use a pre-casted int constant rather than casting from long to int
         * each time at some usage sites.
         */
        static final int CONTROLS_OFFSET_AS_INT;
        private static final long DATA_SLOTS_OFFSET;

        static {
            CONTROLS_OFFSET = minInstanceFieldOffset(HashTableArea.class);
            CONTROLS_OFFSET_AS_INT = (int) CONTROLS_OFFSET;
            DATA_SLOTS_OFFSET = CONTROLS_OFFSET + HASH_TABLE_SLOTS;
        }

        @HotPath
        static long readTagGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            return U.getLong(segment, CONTROLS_OFFSET + firstSlotIndex);
        }

        @HotPath
        static long readDataGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            return U.getLong(segment, DATA_SLOTS_OFFSET + firstSlotIndex);
        }

        static void writeDataGroup(Object segment, long groupIndex, long dataGroup) {
            TODO
        }

        @HotPath
        static void writeTagAndData(
                Object segment, long groupIndex, int slotIndexWithinGroup, byte tag, byte data) {
            TODO
        }

//        static int readControlByte(Object segment, long slotIndex) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            return (int) U.getByte(segment, CONTROLS_OFFSET + slotIndex);
//        }
//
//        static void writeControlByte(Object segment, long slotIndex, byte controlByte) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            U.putByte(segment, CONTROLS_OFFSET + slotIndex, controlByte);
//            // Sets the cloned control. For slot indexes between 8th and 63th, i. e. 87.5% of the
//            // time this computation results in the same index as the original, and the same byte in
//            // memory is set twice. The alternative is a conditional block as in
//            // writeControlByteWithConditionalCloning(), but that is a branch.
//            // TODO compare the approaches, importantly with Shenandoah GC that emits primitive
//            //  write barriers
//            long clonedSlotIndex =
//                    ((slotIndex - GROUP_SLOTS) & HASH_TABLE_SLOTS_MASK) + GROUP_SLOTS;
//            U.putByte(segment, CONTROLS_OFFSET + clonedSlotIndex, controlByte);
//        }
//
//        static void writeControlByteWithConditionalCloning(Object segment, long slotIndex,
//                byte controlByte) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            U.putByte(segment, CONTROLS_OFFSET + slotIndex, controlByte);
//            if (slotIndex < GROUP_SLOTS) {
//                long clonedSlotIndex = slotIndex + HASH_TABLE_SLOTS;
//                U.putByte(segment, CONTROLS_OFFSET + clonedSlotIndex, controlByte);
//            }
//        }
//
//        static int readData(Object segment, long slotIndex) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            // Another option is `DATA_SLOTS_OFFSET + 63 - slotIndex`, that makes more control byte
//            // and data byte accesses to fall in a single cache line (and on the order hand, more
//            // accesses are two cache lines apart, compared to `DATA_SLOTS_OFFSET + slotIndex`).
//            // Warning: it would require to add a Long.reverseBytes() call in
//            // readDataGroupByGroupIndex(). Other data-access methods also would need to be updated.
//            // TODO compare the approaches
//            return (int) U.getByte(segment, DATA_SLOTS_OFFSET + slotIndex);
//        }
//
//        static void writeData(Object segment, long slotIndex, byte data) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            U.putByte(segment, DATA_SLOTS_OFFSET + slotIndex, data);
//        }
//
//        static int replaceData(Object segment, long slotIndex, byte newData) {
//            /* if Enabled extraChecks */assert segment != null;/* endif */
//            long offset = DATA_SLOTS_OFFSET + slotIndex;
//            int oldData = (int) U.getByte(segment, offset);
//            U.putByte(segment, offset, newData);
//            return oldData;
//        }
    }

    static class SegmentBase<K, V> extends SmoothieMap.Segment<K, V> {
        @SuppressWarnings("unused")
        private @Nullable Object k0, v0;

        static final long KEY_OFFSET_BASE;
        private static final long VALUE_OFFSET_BASE;

        /** `* 2` because there are two objects, key and value, that contribute to the scale. */
        private static final int ALLOC_INDEX_SHIFT =
                Integer.numberOfTrailingZeros(ARRAY_OBJECT_INDEX_SCALE * 2);

        static {
            // TODO Excelsior JET: ensure it works with Excelsior JET's negative object field
            //  offsets, see https://www.excelsiorjet.com/blog/articles/unsafe-harbor/
            KEY_OFFSET_BASE = minInstanceFieldOffset(SegmentBase.class);
            // Segments.valueOffsetFromAllocOffset() depends on the fact that we lay values after
            // keys here.
            VALUE_OFFSET_BASE = KEY_OFFSET_BASE + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
        }

        /** Mirror of {@link InterleavedSegments.FullCapacitySegment#allocOffset} */
        @HotPath
        static long allocOffset(long allocIndex) {
            return KEY_OFFSET_BASE + (allocIndex << ALLOC_INDEX_SHIFT);
        }

        @HotPath
        static long valueOffset(long allocIndex) {
            return VALUE_OFFSET_BASE + (allocIndex << ALLOC_INDEX_SHIFT);
        }

        /**
         * Returns the nonFullSegment_bitSetAndState with an updated bit set: allocations are moved
         * around in this method, so the allocation index mapping might change. Also, sets
         * allocCapacity encoded in nonFullSegment_bitSetAndState to fullSegment_allocCapacity.
         *
         * nonFullSegment_bitSetAndState's fields other than the bit set itself (see {@link
         * ContinuousSegment_BitSetAndStateArea#bitSetAndState}) are not reliable and must not be
         * used.
         *
         * This method doesn't write into {@link ContinuousSegment_BitSetAndStateArea}'s fields,
         * {@link ContinuousSegment_BitSetAndStateArea#bitSetAndState} and {@link
         * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup} of nonFullSegment
         * or fullSegment.
         *
         * @return fullSegment's bitSetAndState as it should be after this method call; the new
         *         nonFullSegment's bitSetAndState can be obtained via combining
         *         nonFullSegment_allocCapacity and bit set of the first fullSegment_allocCapacity
         *         alloc indexes set full and the rest set empty.
         */
        @RarelyCalledAmortizedPerSegment
        static long swapContentsDuringSplit(SmoothieMap.Segment<?, ?> nonFullSegment,
                int nonFullSegment_allocCapacity, long nonFullSegment_bitSetAndState,
                SmoothieMap.Segment<?, ?> fullSegment, int fullSegment_allocCapacity) {
//            TODO copy outbound overflow counts
            // Compacting entries of nonFullSegment if needed to be able to swap allocation areas.
            // This must be done before swapping the hash tables, because
            // compactEntriesDuringSegmentSwap() updates some data slots of nonFullSegment.
            if (fullSegment_allocCapacity < nonFullSegment_allocCapacity) { // Likelihood - ???
                ((SegmentBase) nonFullSegment).compactEntriesDuringSegmentSwap(
                        nonFullSegment_allocCapacity, nonFullSegment_bitSetAndState,
                        fullSegment_allocCapacity);
            }

            if (fullSegment_allocCapacity > nonFullSegment_allocCapacity) {
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

            return makeBitSetAndStateWithNewAllocCapacity(
                    nonFullSegment_bitSetAndState, fullSegment_allocCapacity);
        }

        /**
         * "Defragment" entries so that all entries that are stored at alloc indexes equal to and
         * beyond fitInAllocCapacity are moved to smaller alloc indexes.
         *
         * @return an updated bitSetAndState
         */
        @RarelyCalledAmortizedPerSegment
        private long compactEntriesDuringSegmentSwap(int allocCapacity,
                long bitSetAndState, int fitInAllocCapacity) {
            int segmentSize = segmentSize(bitSetAndState);
            // Must not happen since this segment's bitSetAndState is not re-read during
            // SmoothieMap.doSplit(): see the flow of fromSegment_bitSetAndState variable in that
            // method.
            verifyThat(segmentSize <= fitInAllocCapacity);
            // compactEntriesDuringSegmentSwap() method must not be called if it's not needed
            // anyway.
            verifyThat(fitInAllocCapacity < allocCapacity);

            // Branchless hash table iteration:
            // Using bitMask-style loop to go over all full slots in the group. The alternative
            // approach is to iterate over each byte in separation. It's hard to say which approach
            // is better without benchmarking.
            //
            // The advantages of the bitMask approach:
            //  - Branchless
            //  - Incurs less reads and writes from and to segment object's memory, while the cost
            //  of read and write barriers can be relatively high for some GC algorithms.
            //
            // The disadvantage of the bitMask approach is that it incurs more arithmetic operations
            // and a (relatively expensive?) numberOfTrailingZeros() call.
            //
            // The advantages of byte-by-byte hash table iteration:
            //  - Simpler, both in terms of implementation complexity and CPU intensiveness,
            //  . has an
            //
            // The disadvantages of the byte-by-byte approach:
            //  - Includes a branch which may be fairly poorly predicated: about 28% (more
            //  specifically, SEGMENT_MAX_ALLOC_CAPACITY - SEGMENT_INTERMEDIATE_ALLOC_CAPACITY = 18
            //  slots out of HASH_TABLE_SLOTS = 64) of slots are expected to be matched.
            //  - More memory read and write operations may be issued on each iteration.
            //
            // TODO compare the approaches
            // See also [Branchless entries iteration].

            // [Int-indexed loop to avoid a safepoint poll]
            for (int iterationGroupFirstSlotIndex = 0;
                 iterationGroupFirstSlotIndex < HASH_TABLE_SLOTS;
                 iterationGroupFirstSlotIndex += GROUP_SLOTS) {

                long controlsGroup =
                        readControlsGroup(this, (long) iterationGroupFirstSlotIndex);
                long dataGroup = readDataGroup(this, (long) iterationGroupFirstSlotIndex);

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
                    { // [Inlined lowestMatchingSlotIndex]
                        int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                        // TODO lowestMatchIndexFromTrailingZeros() makes & HASH_TABLE_SLOTS_MASK, maybe this is
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
                    int newAllocIndex = lowestFreeAllocIndex(bitSetAndState);
                    // Transitively checks that newAllocIndex < allocCapacity as required by
                    // lowestFreeAllocIndex(), because it's ensured that fitInAllocCapacity is less
                    // than allocCapacity in a guard condition above.
                    if (newAllocIndex >= fitInAllocCapacity) {
                        throw cmeDuringCompactEntries();
                    }
                    // First calling setLowestAllocBit() and then clearAllocBit() leads to less
                    // data dependencies than if these methods were called in the reverse order.
                    bitSetAndState = clearAllocBit(setLowestAllocBit(bitSetAndState), allocIndex);

                    Object key = readKeyAtOffset(this, allocOffset);
                    Object value = readValueAtOffset(this, allocOffset);

                    eraseKeyAndValueAtIndex(segment, allocIndex);

                    writeKeyAndValueAtIndex(segment, newAllocIndex, key, value);

                    // Alternative is to update a long word and write it out after the inner
                    // loop if diverges from originalDataGroup. But that's more complicated.
                    // TODO compare approaches
                    writeData(this, (long) slotIndex,
                            changeAllocIndexInData(dataByte, newAllocIndex));
                }
            }
            return bitSetAndState;
        }

        /**
         * Can happen if entries are inserted into the segment concurrently with {@link
         * SmoothieMap#doSplit}, including concurrently with {@link
         * #compactEntriesDuringSegmentSwap} which is called from {@link #swapContentsDuringSplit}
         * which is called from {@link SmoothieMap#doSplit}.
         */
        private static ConcurrentModificationException cmeDuringCompactEntries() {
            return new ConcurrentModificationException();
        }

        @RarelyCalledAmortizedPerSegment
        private static void swapHashTables(
                SmoothieMap.Segment<?, ?> segmentOne, SmoothieMap.Segment<?, ?> segmentTwo) {
            /* if Enabled extraChecks */
            assert segmentOne != null;
            assert segmentTwo != null;
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

        @Override
        @RarelyCalledAmortizedPerSegment
        void copyEntriesDuringInflate(
                SmoothieMap<K, V> map, SmoothieMap.InflatedSegment<K, V> intoSegment) {
            TODO
        }

        @Override
        void setAllDataGroups(long dataGroup) {
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
        void copyHashTableFrom(SmoothieMap.Segment<?, ?> oldSegment) {
            /* if Enabled extraChecks */assert oldSegment != null;/* endif */
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
            /* if Enabled extraChecks */assert oldSegment != null;/* endif */
            for (int allocIndex = 0; allocIndex < oldSegmentCapacity; allocIndex++) {
                long keyOffset = allocKeyOffset(allocIndex);
                long valueOffset = keyOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
                // Raw key and value objects copy: not using readKeyAtOffset() and readValueAtOffset() for
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
    }

    /**
     * nonFullCapSegment's bitSetAndState is passed as a parameter to this method because
     * nonFullCapSegment.bitSetAndState is already set to bulk operation placeholder value
     * before calling this method.
     */
    static <K, V> SmoothieMap.Segment<K, V> grow(SmoothieMap.Segment<?, ?> nonFullCapSegment,
            long nonFullCapSegment_bitSetAndState, int toAllocCapacity) {
        SmoothieMap.Segment<K, V> newSegment = allocateSegment(toAllocCapacity);
        newSegment.copyHashTableFrom(nonFullCapSegment);
        int nonFullCapSegment_allocCapacity = allocCapacity(nonFullCapSegment_bitSetAndState);
        newSegment.copyAllocAreaFrom(newSegment, nonFullCapSegment_allocCapacity);
        long fullCapSegment_bitSetAndState = makeBitSetAndStateWithNewAllocCapacity(
                nonFullCapSegment_bitSetAndState, toAllocCapacity);
        newSegment.bitSetAndState = fullCapSegment_bitSetAndState;
        newSegment.outboundOverflowCountsPerGroup =
                nonFullCapSegment.outboundOverflowCountsPerGroup;
        U.storeFence(); // [Safe segment publication]
        return newSegment;
    }

    static <K, V> SmoothieMap.Segment<K, V> allocateNewSegmentWithoutSettingBitSetAndSet(
            int allocCapacity) {
        @SuppressWarnings("UnnecessaryLocalVariable")
        SmoothieMap.Segment<K, V> segment = allocateSegment(allocCapacity);
        // Since HashTable.EMPTY_DATA_GROUP == 0 no additional hash table initialization is needed.
        return segment;
    }

    static <K, V> SmoothieMap.Segment<K, V> createNewSegment(int allocCapacity, int segmentOrder) {
        SmoothieMap.Segment<K, V> segment =
                allocateNewSegmentWithoutSettingBitSetAndSet(allocCapacity);
        segment.bitSetAndState = makeNewBitSetAndState(allocCapacity, segmentOrder);
        // Safe segment publication: ensure racy readers always see correctly initialized
        // bitSetAndState. It's hard to prove that no races are possible that could lead to
        // a JVM crash or memory corruption due to access to an alloc index beyond the alloc
        // capacity of the segment, especially considering the possibility of non-atomic
        // bitSetAndState access on 32-bit platforms.
        U.storeFence();
        return segment;
    }

    private static <K, V> SmoothieMap.Segment<K, V> allocateSegment(int allocCapacity) {
        Class<? extends SmoothieMap.Segment<K, V>> c = acquireClass(allocCapacity);
        try {
            @SuppressWarnings("unchecked")
            SmoothieMap.Segment<K, V> segment = (SmoothieMap.Segment<K, V>) U.allocateInstance(c);
            return segment;
        } catch (InstantiationException e) {
            throw new AssertionError(e);
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
    private static final Class[] segmentClassCache =
            new Class[SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY + 1];

    @SuppressWarnings("unchecked")
    static <K, V> Class<? extends SmoothieMap.Segment<K, V>> acquireClass(int allocCapacity) {
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
     * See {@link SmoothieMap.Segment} javadoc for more info about the following classes
     */
    @SuppressWarnings({"unused"})
    private static class Segment17<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16;
    }

    /**
     * Don't create a single long extension chain (e. g. {@link Segment18} extends {@link
     * Segment17}, {@link Segment19} extends {@link Segment20}, etc), because it might cause more
     * classes to be loaded (e. g. if only {@link Segment48} is ever used, that happens if {@link
     * OptimizationObjective#ALLOCATION_RATE} is used and {@link SmoothieMap#shrinkAndTrimToSize()}
     * is never called), and also might be more troublesome for some GC heap traversal
     * implementations (?)
     */
    @SuppressWarnings({"unused"})
    private static class Segment18<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17;
    }

    @SuppressWarnings({"unused"})
    private static class Segment19<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18;
    }

    @SuppressWarnings({"unused"})
    private static class Segment20<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
    }


    @SuppressWarnings({"unused"})
    private static class Segment21<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20;
    }

    @SuppressWarnings({"unused"})
    private static class Segment22<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21;
    }

    @SuppressWarnings({"unused"})
    private static class Segment23<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22;
    }

    @SuppressWarnings({"unused"})
    private static class Segment24<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23;
    }

    @SuppressWarnings({"unused"})
    private static class Segment25<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24;
    }

    @SuppressWarnings({"unused"})
    private static class Segment26<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25;
    }

    @SuppressWarnings({"unused"})
    private static class Segment27<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26;
    }

    @SuppressWarnings({"unused"})
    private static class Segment28<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27;
    }

    @SuppressWarnings({"unused"})
    private static class Segment29<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28;
    }

    @SuppressWarnings({"unused"})
    private static class Segment30<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
    }

    @SuppressWarnings({"unused"})
    private static class Segment31<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30;
    }

    @SuppressWarnings({"unused"})
    private static class Segment32<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31;
    }

    @SuppressWarnings({"unused"})
    private static class Segment33<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32;
    }

    @SuppressWarnings({"unused"})
    private static class Segment34<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33;
    }

    @SuppressWarnings({"unused"})
    private static class Segment35<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34;
    }

    @SuppressWarnings({"unused"})
    private static class Segment36<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35;
    }

    @SuppressWarnings({"unused"})
    private static class Segment37<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36;
    }

    @SuppressWarnings({"unused"})
    private static class Segment38<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37;
    }

    @SuppressWarnings({"unused"})
    private static class Segment39<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38;
    }

    @SuppressWarnings({"unused"})
    private static class Segment40<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
    }

    @SuppressWarnings({"unused"})
    private static class Segment41<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40;
    }

    @SuppressWarnings({"unused"})
    private static class Segment42<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41;
    }

    @SuppressWarnings({"unused"})
    private static class Segment43<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42;
    }

    @SuppressWarnings({"unused"})
    private static class Segment44<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42, k43, v43;
    }

    @SuppressWarnings({"unused"})
    private static class Segment45<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44;
    }

    @SuppressWarnings({"unused"})
    private static class Segment46<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45;
    }

    @SuppressWarnings({"unused"})
    private static class Segment47<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45, k46, v46;
    }

    @SuppressWarnings({"unused"})
    private static class Segment48<K, V> extends SegmentBase<K, V> {
        private @Nullable Object k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9;
        private @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, k16, v16, k17, v17, k18, v18, k19, v19;
        private @Nullable Object k20, v20, k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26, k27, v27, k28, v28, k29, v29;
        private @Nullable Object k30, v30, k31, v31, k32, v32, k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38, k39, v39;
        private @Nullable Object k40, v40, k41, v41, k42, v42, k43, v43, k44, v44, k45, v45, k46, v46, k47, v47;
    }

    //endregion
}
