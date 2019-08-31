/* if Interleaved segments */
package io.timeandspace.smoothie;

import io.timeandspace.smoothie.BitSetAndState.DebugBitSetAndState;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ConcurrentModificationException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static io.timeandspace.smoothie.BitSetAndState.clearBitSet;
import static io.timeandspace.smoothie.BitSetAndState.extractBitSetForIteration;
import static io.timeandspace.smoothie.BitSetAndState.freeAllocIndexClosestTo;
import static io.timeandspace.smoothie.BitSetAndState.makeNewBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.segmentOrder;
import static io.timeandspace.smoothie.BitSetAndState.segmentSize;
import static io.timeandspace.smoothie.BitSetAndState.setAllocBit;
// comment // Dummy templating to make this class "compilable" in IntelliJ before code generation
/**/
/* if Continuous segments */
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.getOutboundOverflowCountsPerGroup;
/* elif Interleaved segments //
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.getOutboundOverflowCountsPerGroup;
// endif */
import static io.timeandspace.smoothie.HashTable.EMPTY_DATA_GROUP;
import static io.timeandspace.smoothie.HashTable.GROUP_SLOTS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_SLOTS;
import static io.timeandspace.smoothie.HashTable.assertValidGroupIndex;
import static io.timeandspace.smoothie.HashTable.assertValidSlotIndexWithinGroup;
import static io.timeandspace.smoothie.HashTable.baseGroupIndex;
import static io.timeandspace.smoothie.HashTable.copyOutboundOverflowBits;
import static io.timeandspace.smoothie.HashTable.extractAllocIndex;
import static io.timeandspace.smoothie.HashTable.extractDataByte;
import static io.timeandspace.smoothie.HashTable.extractTagByte;
import static io.timeandspace.smoothie.HashTable.firstAllocIndex;
import static io.timeandspace.smoothie.HashTable.lowestMatchingSlotIndexFromTrailingZeros;
import static io.timeandspace.smoothie.HashTable.makeData;
import static io.timeandspace.smoothie.HashTable.makeDataWithZeroOutboundOverflowBit;
import static io.timeandspace.smoothie.HashTable.matchFull;
import static io.timeandspace.smoothie.HashTable.setSlotEmpty;
import static io.timeandspace.smoothie.HashTable.slotByteOffset;
import static io.timeandspace.smoothie.LongMath.clearLowestSetBit;
import static io.timeandspace.smoothie.OutboundOverflowCounts.computeOutboundOverflowCount_perGroupChanges;
import static io.timeandspace.smoothie.Segments.valueOffsetFromAllocOffset;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.longKeyHashCodeToIntHashCode;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.UnsafeUtils.minInstanceFieldOffset;
import static io.timeandspace.smoothie.Utils.verifyEqual;
import static io.timeandspace.smoothie.Utils.verifyThat;

final class InterleavedSegments {

    private static final int NUM_FULL_STRIDES = 7;

    /** `* 2` means that each allocation contains a key and a value, i. e. two objects. */
    private static final long ALLOCATION_INDEX_SIZE_IN_BYTES = ARRAY_OBJECT_INDEX_SCALE_AS_LONG * 2;

    /** @see BitSetAndState#freeAllocIndexClosestTo */
    @HotPath
    static int allocIndexBoundaryForLocalAllocation(int groupIndex
            /* if Supported intermediateSegments */, int isFullCapacitySegment/* endif */) {
        /* if Supported intermediateSegments */

        // Branchless version of the following logic:
        // if (isFullCapacitySegment == 1)
        //     return FullCapacitySegment.allocIndexBoundaryForLocalAllocation(groupIndex);
        // else // assuming isFullCapacitySegment == 0
        //     return IntermediateCapacitySegment.allocIndexBoundaryForLocalAllocation(groupIndex);
        return (IntermediateCapacitySegment.STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES +
                // Just adding isFullCapacitySegment relying on the fact that
                // FullCapacitySegment.STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES -
                // IntermediateCapacitySegment.STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES = 1.
                isFullCapacitySegment) +
                groupIndex * (IntermediateCapacitySegment.STRIDE_SIZE_IN_ALLOC_INDEXES +
                        isFullCapacitySegment * (FullCapacitySegment.STRIDE_SIZE_IN_ALLOC_INDEXES -
                                IntermediateCapacitySegment.STRIDE_SIZE_IN_ALLOC_INDEXES));

        /* elif NotSupported intermediateSegments */
        return FullCapacitySegment.allocIndexBoundaryForLocalAllocation(groupIndex);
        /* endif */
    }

    static long allocOffset(long allocIndex
            /* if Supported intermediateSegments */, long isFullCapacitySegment/* endif */) {
        /* if Supported intermediateSegments */

        // Both FullCapacitySegment.allocOffset() and IntermediateCapacitySegment.allocOffset()
        // include a fair amount of work for CPU. The alternative is to inline these methods and
        // choose all constants in a branchless manner, but there are as many as 5 constants to be
        // chosen: strideIncrement, divMultiplier, strideSizeInAllocIndexes, stride0_offset, and
        // strideSizeInBytes so there is even more work for CPU in this approach.

        long fullCap_allocOffset = FullCapacitySegment.allocOffset(allocIndex);
        long intermediateCap_allocOffset = IntermediateCapacitySegment.allocOffset(allocIndex);

        return intermediateCap_allocOffset +
                isFullCapacitySegment * (fullCap_allocOffset - intermediateCap_allocOffset);

        /* elif NotSupported intermediateSegments */
        return FullCapacitySegment.allocOffset(allocIndex);
        /* endif */
    }

    static long readTagGroupAtOffset(Object segment, long tagGroupOffset) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        return U.getLong(segment, tagGroupOffset);
    }

    static void writeTagGroupAtOffset(Object segment, long tagGroupOffset, long tagGroup) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        U.putLong(segment, tagGroupOffset, tagGroup);
    }

    static long readDataGroupAtOffset(Object segment, long dataGroupOffset) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        return U.getLong(segment, dataGroupOffset);
    }

    static void writeDataGroupAtOffset(Object segment, long dataGroupOffset, long dataGroup) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        U.putLong(segment, dataGroupOffset, dataGroup);
    }

    @HotPath
    static void writeTagAndData(Object segment,
            /* if Supported intermediateSegments */long isFullCapacitySegment,/* endif */
            long groupIndex, int slotIndexWithinGroup, byte tag, byte data) {
        /* if Supported intermediateSegments */

        /* if Enabled extraChecks */
        assert segment != null;
        assertValidSlotIndexWithinGroup(slotIndexWithinGroup);
        /* endif */
        long slotByteOffset = slotByteOffset(slotIndexWithinGroup);
        long tagGroupOffset = tagGroupOffset(groupIndex, isFullCapacitySegment);
        U.putByte(segment, tagGroupOffset + slotByteOffset, tag);
        long dataGroupOffset = dataGroupFromTagGroupOffset(tagGroupOffset);
        U.putByte(segment, dataGroupOffset + slotByteOffset, data);

        /* elif NotSupported intermediateSegments */
        FullCapacitySegment.writeTagAndData(segment, groupIndex, slotIndexWithinGroup, tag, data);
        /* endif */
    }

    static long tagGroupOffset(long groupIndex
            /* if Supported intermediateSegments */, long isFullCapacitySegment/* endif */) {
        /* if Enabled extraChecks */assertValidGroupIndex(groupIndex);/* endif */

        /* if Supported intermediateSegments */

        // Branchless version of the following logic:
        // if (isFullCapacitySegment == 1)
        //     return FullCapacitySegment.tagGroupOffset(groupIndex);
        // else // assuming isFullCapacitySegment == 0
        //     return IntermediateCapacitySegment.tagGroupOffset(groupIndex);
        return IntermediateCapacitySegment.TAG_GROUP_0_OFFSET + isFullCapacitySegment *
                (FullCapacitySegment.TAG_GROUP_0_OFFSET -
                        IntermediateCapacitySegment.TAG_GROUP_0_OFFSET) +
                groupIndex * (IntermediateCapacitySegment.STRIDE_SIZE_IN_BYTES +
                        isFullCapacitySegment * (FullCapacitySegment.STRIDE_SIZE_IN_BYTES -
                                IntermediateCapacitySegment.STRIDE_SIZE_IN_BYTES));

        /* elif NotSupported intermediateSegments */
        return FullCapacitySegment.tagGroupOffset(groupIndex);
        /* endif */
    }

    static long dataGroupOffset(long groupIndex
            /* if Supported intermediateSegments */, long isFullCapacitySegment/* endif */) {
        /* if Enabled extraChecks */assertValidGroupIndex(groupIndex);/* endif */

        /* if Supported intermediateSegments */

        // Branchless version of the following logic:
        // if (isFullCapacitySegment == 1)
        //     return FullCapacitySegment.dataGroupOffset(groupIndex);
        // else // assuming isFullCapacitySegment == 0
        //     return IntermediateCapacitySegment.dataGroupOffset(groupIndex);
        return IntermediateCapacitySegment.DATA_GROUP_0_OFFSET + isFullCapacitySegment *
                (FullCapacitySegment.DATA_GROUP_0_OFFSET -
                        IntermediateCapacitySegment.DATA_GROUP_0_OFFSET) +
                groupIndex * (IntermediateCapacitySegment.STRIDE_SIZE_IN_BYTES +
                        isFullCapacitySegment * (FullCapacitySegment.STRIDE_SIZE_IN_BYTES -
                                IntermediateCapacitySegment.STRIDE_SIZE_IN_BYTES));

        /* elif NotSupported intermediateSegments */
        return FullCapacitySegment.dataGroupOffset(groupIndex);
        /* endif */
    }

    /**
     * @implNote the implementation of this method depends on how {@link
     * FullCapacitySegment#DATA_GROUP_0_OFFSET} is initialized with respect to {@link
     * FullCapacitySegment#TAG_GROUP_0_OFFSET} which also aligns with how the corresponding fields
     * are initialized in {@link IntermediateCapacitySegment}.
     */
    static long tagGroupFromDataGroupOffset(long dataGroupOffset) {
        return dataGroupOffset - Long.BYTES;
    }

    /**
     * @implNote the implementation of this method depends on how {@link
     * FullCapacitySegment#DATA_GROUP_0_OFFSET} is initialized with respect to {@link
     * FullCapacitySegment#TAG_GROUP_0_OFFSET} which also aligns with how the corresponding fields
     * are initialized in {@link IntermediateCapacitySegment}.
     */
    static long dataGroupFromTagGroupOffset(long tagGroupOffset) {
        return tagGroupOffset + Long.BYTES;
    }

    /**
     * Swaps segments' contents, namely:
     *  - Hash tables (see {@link HashTable})
     *  - Alloc areas
     *  - Outbound overflow counts (see {@link
     *    ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup})
     *  - Bit set parts of bitSetAndStates (see {@link BitSetAndState})
     *
     * Conceptually, swapping hash tables and alloc areas in InterleavedSegments is more complicated
     * than in {@link ContinuousSegments} because the need to preserve "affinity" between hash
     * table's groups and alloc indexes of entries stored in the corresponding groups: see {@link
     * FullCapacitySegment#allocIndexBoundaryForLocalAllocation}, {@link
     * FullCapacitySegment#insertDuringContentsMove}, and the parallel methods in {@link
     * IntermediateCapacitySegment}. This method changes the layout of entries in the allocation
     * areas of both segments.
     *
     * This method is placed outside of both {@link FullCapacitySegment} and {@link
     * IntermediateCapacitySegment} to not accidentally call a wrong static method (there are
     * static methods with same names in both).
     *
     * @return the updated fullCapacitySegment_bitSetAndState, not yet written into
     *         fullCapacitySegment; Note that the updated intermediateCapacitySegment_bitSetAndState
     *         is written into intermediateCapacitySegment's {@link
     *         ContinuousSegment_BitSetAndStateArea#bitSetAndState} inside this method. Note that
     *         it's opposite of the contract of the parallel {@link
     *         ContinuousSegments.SegmentBase#swapContentsDuringSplit} method. It needs to be so
     *         because fullCapacitySegment's bitSetAndState is expected to be a bulk operation
     *         placeholder (see {@link BitSetAndState#makeBulkOperationPlaceholderBitSetAndState})
     *         when this method is called and should remain so.
     *
     * @implNote this method operates by first copying intermediateCapacitySegment's hash table
     * groups and entries to temporary arrays, then copying fullCapacitySegment's contents into
     * intermediateCapacitySegment, then copying intermediateCapacitySegment's contents from the
     * temporary arrays to fullCapacitySegment. So this method is not garbage-free: it allocates
     * temporary arrays. It's possible to swap segments' contents in-place, but it's very
     * complicated (requires something like several bit sets stored in long values for tracking
     * individual swapping of hash table's slots and alloc indexes in both segments) and doesn't
     * make much sense since the probability of calling this method is just 0.7% (see
     * [Swap segments] in {@link SmoothieMap#doSplit}) and since intermediate-capacity segments are
     * used at all, the garbage produce of the SmoothieMap is much higher already so temporary array
     * allocations in this method are an insignificant contribution to the total garbage produce.
     */
    @RarelyCalledAmortizedPerSegment
    static long swapContentsDuringSplit(SmoothieMap.Segment<?, ?> fullCapacitySegment,
            long fullCapSegment_bitSetAndState,
            SmoothieMap.Segment<?, ?> intermediateCapacitySegment,
            long intermediateCapSegment_bitSetAndState) {
        FullCapacitySegment fullCapSegment = (FullCapacitySegment) fullCapacitySegment;
        IntermediateCapacitySegment intermediateCapSegment =
                (IntermediateCapacitySegment) intermediateCapacitySegment;

        // Copy contents from intermediateCapSegment into temporary arrays.
        long[] intermediateCapSegment_hashTable =
                intermediateCapSegment.copyHashTableToArrayDuringSegmentSwap();
        Object[] intermediateCapSegment_entries =
                intermediateCapSegment.copyEntriesToArrayDuringSegmentSwap();
        long intermediateCapSegment_outboundOverflowCountsPerGroup =
                intermediateCapSegment.outboundOverflowCountsPerGroup;
        // Zero intermediateCapSegment_outboundOverflowCountsPerGroup: this method expects
        // intermediateCapacitySegment's outbound overflow counts to be zero for all groups. This is
        // because swapContentsDuringSplit() is called from SmoothieMap.doSplit() where
        // intermediateCapacitySegment is created privately (so no other thread can possibly modify
        // it in parallel) and in the logic of SmoothieMap.doSplit() itself outbound overflow counts
        // are not written until the end of the method (after the call to
        // swapContentsDuringSplit()).
        //
        // However, FullCapacitySegment.copyContentsFromArrays() which is called below doesn't
        // specialize for this fact for symmetry with
        // moveContentsFromFullToIntermediateCapacitySegment() and flexibility wrt. future change.
        verifyEqual(intermediateCapSegment_outboundOverflowCountsPerGroup, 0);

        // Need to clear intermediateCapSegment's alloc area before copying data from fullCapSegment
        // into it because the current size of fullCapSegment is less than
        // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY (which is because intermediateCapSegment is full so
        // its size is SEGMENT_INTERMEDIATE_ALLOC_CAPACITY, and the total size of fullCapSegment and
        // intermediateCapSegment can't be more than SEGMENT_MAX_ALLOC_CAPACITY, and
        // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY (32) > SEGMENT_MAX_ALLOC_CAPACITY / 2 (24)) so
        // moveContentsFromFullToIntermediateCapacitySegment() won't overwrite all indexes in
        // intermediateCapSegment's alloc area.
        intermediateCapSegment.clearHashTableAndAllocArea();
        moveContentsFromFullToIntermediateCapacitySegment(
                fullCapSegment, intermediateCapSegment, intermediateCapSegment_bitSetAndState);

        // Need to clear fullCapSegment's alloc area because although the number of entries used to
        // reside it is smaller than the number of entries in intermediateCapSegment_entries (see
        // the explanation in the comment for intermediateCapSegment.clearHashTableAndAllocArea())
        // the capacity of fullCapSegment (SEGMENT_MAX_ALLOC_CAPACITY) allows the old entries to be
        // distributed in alloc indexes that are not going to be overwritten with
        // intermediateCapSegment_entries.
        fullCapSegment.clearHashTableAndAllocArea();
        fullCapSegment_bitSetAndState = fullCapSegment.copyContentsFromArrays(
                fullCapSegment_bitSetAndState, intermediateCapSegment_hashTable,
                intermediateCapSegment_entries,
                intermediateCapSegment_outboundOverflowCountsPerGroup);

        return fullCapSegment_bitSetAndState;
    }

    /**
     * This method is completely symmetric with {@link
     * #moveContentsFromIntermediateToFullCapacitySegment} and has a similar structure with {@link
     * FullCapacitySegment#copyContentsFromArrays}. These methods should all be updated in parallel.
     *
     * This method writes the updated intermediateCapSegment_bitSetAndState into
     * intermediateCapSegment's {@link ContinuousSegment_BitSetAndStateArea#bitSetAndState}.
     */
    @RarelyCalledAmortizedPerSegment
    private static void moveContentsFromFullToIntermediateCapacitySegment(
            FullCapacitySegment fullCapSegment, IntermediateCapacitySegment intermediateCapSegment,
            long intermediateCapSegment_bitSetAndState) {
        intermediateCapSegment_bitSetAndState = clearBitSet(intermediateCapSegment_bitSetAndState);

        // Same hash table iteration mode in symmetric methods: the performance considerations of
        // branchless vs. byte-by-byte hash table iteration (see the description of
        // [Branchless hash table iteration] for details) are not important in this method, but
        // since moveContentsFromIntermediateToFullCapacitySegment() takes the branchless approach
        // this method follows to preserve the symmetry between these two methods. Similar reasoning
        // is applied in [Sticking to [Branchless hash table iteration] in a cold method].
        // [Branchless hash table iteration]:
        for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
            long tagGroup = FullCapacitySegment.readTagGroup(fullCapSegment, groupIndex);
            IntermediateCapacitySegment.writeTagGroup(intermediateCapSegment, groupIndex, tagGroup);

            long fullCapSegment_dataGroup =
                    FullCapacitySegment.readDataGroup(fullCapSegment, groupIndex);

            for (long bitMask = matchFull(fullCapSegment_dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int trailingZeros = Long.numberOfTrailingZeros(bitMask);

                Object key;
                Object value;
                // Read key and value from the full-capacity segment.
                {
                    long allocIndex = extractAllocIndex(fullCapSegment_dataGroup, trailingZeros);
                    long allocOffset = FullCapacitySegment.allocOffset(allocIndex);
                    key = FullCapacitySegment.readKeyAtOffset(fullCapSegment, allocOffset);
                    value = FullCapacitySegment.readValueAtOffset(fullCapSegment, allocOffset);
                }

                int slotIndexWithinGroup = lowestMatchingSlotIndexFromTrailingZeros(trailingZeros);
                intermediateCapSegment_bitSetAndState =
                        intermediateCapSegment.insertDuringContentsMove(
                                intermediateCapSegment_bitSetAndState, groupIndex,
                                slotIndexWithinGroup, key, value);
            }

            intermediateCapSegment.copyOutboundOverflowBitsFrom(
                    groupIndex, fullCapSegment_dataGroup);
        }

        intermediateCapSegment.bitSetAndState = intermediateCapSegment_bitSetAndState;
        intermediateCapSegment.outboundOverflowCountsPerGroup =
                fullCapSegment.outboundOverflowCountsPerGroup;
    }

    /**
     * intermediateCapSegment's bitSetAndState is passed as a parameter to this method because
     * intermediateCapSegment.bitSetAndState is already set to bulk operation placeholder value
     * before calling this method.
     */
    @AmortizedPerSegment
    static <K, V> FullCapacitySegment<K, V> grow(SmoothieMap.Segment<K, V> intermediateCapSegment,
            long intermediateCapSegment_bitSetAndState, int toAllocCapacity) {
        verifyEqual(toAllocCapacity, SEGMENT_MAX_ALLOC_CAPACITY);

        FullCapacitySegment<K, V> fullCapSegment = new FullCapacitySegment<>();
        int segmentOrder = segmentOrder(intermediateCapSegment_bitSetAndState);
        long fullCapSegment_bitSetAndState =
                makeNewBitSetAndState(SEGMENT_MAX_ALLOC_CAPACITY, segmentOrder);
        fullCapSegment_bitSetAndState = moveContentsFromIntermediateToFullCapacitySegment(
                (IntermediateCapacitySegment) intermediateCapSegment, fullCapSegment,
                fullCapSegment_bitSetAndState);
        fullCapSegment.bitSetAndState = fullCapSegment_bitSetAndState;
        U.storeFence(); // [Safe segment publication]
        return fullCapSegment;
    }

    /**
     * This method is completely symmetric with {@link
     * #moveContentsFromFullToIntermediateCapacitySegment} and has a similar structure with {@link
     * FullCapacitySegment#copyContentsFromArrays}. These methods should all be updated in parallel.
     *
     * @param fullCapSegment_bitSetAndState must have all bits clear in the bit set, identifying
     *        that all alloc indexes are clear
     * @return the updated fullCapSegment_bitSetAndState, not yet written into fullCapSegment
     */
    @AmortizedPerSegment
    private static long moveContentsFromIntermediateToFullCapacitySegment(
            IntermediateCapacitySegment intermediateCapSegment, FullCapacitySegment fullCapSegment,
            long fullCapSegment_bitSetAndState) {
        /* if Enabled extraChecks */
        verifyEqual(segmentSize(fullCapSegment_bitSetAndState), 0);
        /* endif */

        // [Branchless hash table iteration]: TODO discuss
        for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
            long tagGroup =
                    IntermediateCapacitySegment.readTagGroup(intermediateCapSegment, groupIndex);
            FullCapacitySegment.writeTagGroup(fullCapSegment, groupIndex, tagGroup);

            long intermediateCapSegment_dataGroup =
                    IntermediateCapacitySegment.readDataGroup(intermediateCapSegment, groupIndex);

            for (long bitMask = matchFull(intermediateCapSegment_dataGroup);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                int trailingZeros = Long.numberOfTrailingZeros(bitMask);

                Object key;
                Object value;
                // Read key and value from the intermediate-capacity segment.
                {
                    long allocIndex =
                            extractAllocIndex(intermediateCapSegment_dataGroup, trailingZeros);
                    long allocOffset = IntermediateCapacitySegment.allocOffset(allocIndex);
                    key = IntermediateCapacitySegment.readKeyAtOffset(
                            intermediateCapSegment, allocOffset);
                    value = IntermediateCapacitySegment.readValueAtOffset(
                            intermediateCapSegment, allocOffset);
                }

                int slotIndexWithinGroup = lowestMatchingSlotIndexFromTrailingZeros(trailingZeros);
                fullCapSegment_bitSetAndState =
                        fullCapSegment.insertDuringContentsMove(
                                fullCapSegment_bitSetAndState, groupIndex,
                                slotIndexWithinGroup, key, value);
            }

            fullCapSegment.copyOutboundOverflowBitsFrom(
                    groupIndex, intermediateCapSegment_dataGroup);
        }

        fullCapSegment.outboundOverflowCountsPerGroup =
                intermediateCapSegment.outboundOverflowCountsPerGroup;

        return fullCapSegment_bitSetAndState;
    }

    //region FullCapacitySegment's layout classes
    static abstract class FullCapacitySegment_AllocationSpaceBeforeGroup0<K, V>
            extends SmoothieMap.Segment<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k0, v0, k1, v1, k2, v2;
    }

    static abstract class FullCapacitySegment_Group0<K, V>
            extends FullCapacitySegment_AllocationSpaceBeforeGroup0<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup0, dataGroup0;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups0And1<K, V>
            extends FullCapacitySegment_Group0<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8;
    }

    static abstract class FullCapacitySegment_Group1<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups0And1<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup1, dataGroup1;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups1And2<K, V>
            extends FullCapacitySegment_Group1<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14;
    }

    static abstract class FullCapacitySegment_Group2<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups1And2<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup2, dataGroup2;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups2And3<K, V>
            extends FullCapacitySegment_Group2<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k15, v15, k16, v16, k17, v17, k18, v18, k19, v19, k20, v20;
    }

    static abstract class FullCapacitySegment_Group3<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups2And3<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup3, dataGroup3;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups3And4<K, V>
            extends FullCapacitySegment_Group3<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k21, v21, k22, v22, k23, v23, k24, v24, k25, v25, k26, v26;
    }

    static abstract class FullCapacitySegment_Group4<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups3And4<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup4, dataGroup4;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups4And5<K, V>
            extends FullCapacitySegment_Group4<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k27, v27, k28, v28, k29, v29, k30, v30, k31, v31, k32, v32;
    }

    static abstract class FullCapacitySegment_Group5<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups4And5<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup5, dataGroup5;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups5And6<K, V>
            extends FullCapacitySegment_Group5<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k33, v33, k34, v34, k35, v35, k36, v36, k37, v37, k38, v38;
    }

    static abstract class FullCapacitySegment_Group6<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups5And6<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup6, dataGroup6;
    }

    static abstract class FullCapacitySegment_AllocationSpaceBetweenGroups6And7<K, V>
            extends FullCapacitySegment_Group6<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k39, v39, k40, v40, k41, v41, k42, v42, k43, v43, k44, v44;
    }

    static abstract class FullCapacitySegment_Group7<K, V>
            extends FullCapacitySegment_AllocationSpaceBetweenGroups6And7<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup7, dataGroup7;
    }

    static abstract class FullCapacitySegment_AllocationSpaceAfterGroup7<K, V>
            extends FullCapacitySegment_Group7<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k45, v45, k46, v46, k47, v47;
    }
    //endregion

    /**
     * The memory layout of a full-capacity segment is the following:
     * ...td......td......td......td......td......td......td......td...
     * Where each dot identifies an allocation index (space for a key and a value), letter 't'
     * identifies a tag group, and letter 'd' identifies a data group.
     * 48 (= {@link SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY}) dots (that is, allocation indexes) in
     * total.
     */
    static class FullCapacitySegment<K, V>
            extends FullCapacitySegment_AllocationSpaceAfterGroup7<K, V> {
        static final long TAG_GROUP_0_OFFSET;
        private static final long DATA_GROUP_0_OFFSET;

        static final long STRIDE_SIZE_IN_BYTES;

        /**
         * = {@link SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY} / {@link HashTable#HASH_TABLE_GROUPS}
         */
        static final int STRIDE_SIZE_IN_ALLOC_INDEXES = 6;

        static {
            verifyEqual(STRIDE_SIZE_IN_ALLOC_INDEXES,
                    SEGMENT_MAX_ALLOC_CAPACITY / HASH_TABLE_GROUPS);
        }

        /**
         * Stride 0 begins before actual data starts, at an impossible location "before" {@link
         * FullCapacitySegment_AllocationSpaceBeforeGroup0}, to simplify the implementation of
         * {@link #allocOffset}.
         */
        static final long STRIDE_0_OFFSET;

        static final long ALLOC_INDEX_0_OFFSET;
        static final long ALLOC_INDEX_45_OFFSET;

        /**
         * The number of "real" alloc indexes in stride 0, in other words, in {@link
         * FullCapacitySegment_AllocationSpaceBeforeGroup0}.
         */
        static final int STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES = 3;

        /**
         * The number of "real" alloc indexes in stride 8, in other words, in {@link
         * FullCapacitySegment_AllocationSpaceAfterGroup7}.
         */
        static final int STRIDE_8__NUM_ACTUAL_ALLOC_INDEXES = 3;

        static {
            // IntermediateCapacitySegment has the same initialization block. These blocks should
            // be updated in parallel.

            TAG_GROUP_0_OFFSET = minInstanceFieldOffset(FullCapacitySegment_Group0.class);
            // TODO detect negative field offsets and reverse all offset increments
            // tagGroupFromDataGroupOffset() depends on the fact that we lay tag groups before
            // data groups.
            DATA_GROUP_0_OFFSET = TAG_GROUP_0_OFFSET + Long.BYTES;

            long tagGroup1_offset = minInstanceFieldOffset(FullCapacitySegment_Group1.class);
            STRIDE_SIZE_IN_BYTES = tagGroup1_offset - TAG_GROUP_0_OFFSET;

            long stride1_offset = minInstanceFieldOffset(
                    FullCapacitySegment_AllocationSpaceBetweenGroups0And1.class);
            STRIDE_0_OFFSET = stride1_offset - STRIDE_SIZE_IN_BYTES;
            verifyEqual(STRIDE_0_OFFSET,
                    // Long.BYTES * 2 means tag group and data group.
                    TAG_GROUP_0_OFFSET - STRIDE_SIZE_IN_BYTES + (Long.BYTES * 2));

            ALLOC_INDEX_0_OFFSET = allocOffset(0);
            verifyEqual(ALLOC_INDEX_0_OFFSET,
                    minInstanceFieldOffset(FullCapacitySegment_AllocationSpaceBeforeGroup0.class));

            ALLOC_INDEX_45_OFFSET = allocOffset(45);
            verifyEqual(ALLOC_INDEX_45_OFFSET,
                    minInstanceFieldOffset(FullCapacitySegment_AllocationSpaceAfterGroup7.class));
        }

        @HotPath
        private static long tagGroupOffset(long groupIndex) {
            return TAG_GROUP_0_OFFSET + (groupIndex * STRIDE_SIZE_IN_BYTES);
        }

        @HotPath
        private static long readTagGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            return U.getLong(segment, tagGroupOffset(groupIndex));
        }

        private static void writeTagGroup(Object segment, long groupIndex, long tagGroup) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            U.putLong(segment, tagGroupOffset(groupIndex), tagGroup);
        }

        @HotPath
        private static long dataGroupOffset(long groupIndex) {
            return DATA_GROUP_0_OFFSET + (groupIndex * STRIDE_SIZE_IN_BYTES);
        }

        @HotPath
        static long readDataGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            return U.getLong(segment, dataGroupOffset(groupIndex));
        }

        static void writeDataGroup(Object segment, long groupIndex, long dataGroup) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            U.putLong(segment, dataGroupOffset(groupIndex), dataGroup);
        }

        @HotPath
        static void writeTagAndData(
                Object segment, long groupIndex, int slotIndexWithinGroup, byte tag, byte data) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            assertValidSlotIndexWithinGroup(slotIndexWithinGroup);
            /* endif */
            long slotByteOffset = slotByteOffset(slotIndexWithinGroup);
            U.putByte(segment, tagGroupOffset(groupIndex) + slotByteOffset, tag);
            U.putByte(segment, dataGroupOffset(groupIndex) + slotByteOffset, data);
        }

        private static void writeData(
                Object segment, long groupIndex, int slotIndexWithinGroup, byte data) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            assertValidSlotIndexWithinGroup(slotIndexWithinGroup);
            /* endif */
            long slotByteOffset = slotByteOffset(slotIndexWithinGroup);
            U.putByte(segment, dataGroupOffset(groupIndex) + slotByteOffset, data);
        }

        /** @see BitSetAndState#freeAllocIndexClosestTo */
        static int allocIndexBoundaryForLocalAllocation(int groupIndex) {
            return STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES + groupIndex * STRIDE_SIZE_IN_ALLOC_INDEXES;
        }

        @HotPath
        static long allocOffset(long allocIndex) {
            // Making allocIndex to be relative to stride 0. [Reusing local variable].
            allocIndex += STRIDE_SIZE_IN_ALLOC_INDEXES - STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES;
            // Equivalent to allocIndex / STRIDE_SIZE_IN_ALLOC_INDEXES (= 6).
            // Replacing division by 3 with multiplication and shift, similarly to what is done in
            // doComputeAverageSegmentOrder().
            long strideIndex = ((allocIndex) * 2863311531L) >>> 34;
            // TODO check if can apply the hack from
            //  https://lemire.me/blog/2019/02/08/
            //  faster-remainders-when-the-divisor-is-a-constant-beating-compilers-and-libdivide/
            //  to speed this up
            long allocIndexWithinStride = allocIndex - strideIndex * STRIDE_SIZE_IN_ALLOC_INDEXES;
            return STRIDE_0_OFFSET + strideIndex * STRIDE_SIZE_IN_BYTES +
                    allocIndexWithinStride * ALLOCATION_INDEX_SIZE_IN_BYTES;
        }

        /**
         * Parallel to {@link IntermediateCapacitySegment#writeKeyAndValueAtIndex}. A generalized
         * version is {@link SmoothieMap.Segment#writeKeyAndValueAtIndex}.
         */
        static void writeKeyAndValueAtIndex(
                Object segment, int allocIndex, Object key, Object value) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            long allocOffset = allocOffset((long) allocIndex);
            U.putObject(segment, allocOffset, key);
            U.putObject(segment, valueOffsetFromAllocOffset(allocOffset), value);
        }

        /** Specialized version of {@link SmoothieMap.Segment#writeEntry}. */
        static <K, V> void writeEntry(Object segment, K key, byte tag, V value, long groupIndex,
                long dataGroup, int slotIndexWithinGroup, int allocIndex) {
            byte data = makeData(dataGroup, allocIndex);
            writeTagAndData(segment, groupIndex, slotIndexWithinGroup, tag, data);
            writeKeyAndValueAtIndex(segment, allocIndex, key, value);
        }

        /**
         * A special method which must only be called from segment contents moving operations:
         * {@link IntermediateCapacitySegment#grow} and {@link #copyContentsFromArrays}. It finds
         * the best free alloc index for the given groupIndex and inserts a data byte at the given
         * slotIndexWithinGroup (with {@link HashTable#DATA__OUTBOUND_OVERFLOW_BIT} set to zero) and
         * writes the key and the value at the found alloc index.
         *
         * This method is parallel to {@link IntermediateCapacitySegment#insertDuringContentsMove}.
         *
         * @return the updated bitSetAndState
         */
        private long insertDuringContentsMove(long bitSetAndState,
                long groupIndex, int slotIndexWithinGroup, Object key, Object value) {
            // TODO check if specialization of freeAllocIndexClosestTo() makes any difference here
            //  or JIT compiler scalarizes the SEGMENT_MAX_ALLOC_CAPACITY argument cleanly.
            int insertionAllocIndex = freeAllocIndexClosestTo(bitSetAndState,
                    allocIndexBoundaryForLocalAllocation((int) groupIndex)
                    /* if Supported intermediateSegments */, SEGMENT_MAX_ALLOC_CAPACITY/* endif */);
            /* if Enabled extraChecks */
            // Copying entries from a intermediate-capacity segment into a full-capacity segment
            // cannot result in an alloc area overflow.
            verifyThat(insertionAllocIndex < SEGMENT_MAX_ALLOC_CAPACITY);
            /* endif */

            // Write the new data slot into the segment's hash table.
            byte dataToInsert = makeDataWithZeroOutboundOverflowBit(insertionAllocIndex);
            writeData(this, groupIndex, slotIndexWithinGroup, dataToInsert);

            writeKeyAndValueAtIndex(this, insertionAllocIndex, key, value);

            bitSetAndState = setAllocBit(bitSetAndState, insertionAllocIndex);
            return bitSetAndState;
        }

        /** Parallel to {@link IntermediateCapacitySegment#copyOutboundOverflowBitsFrom}. */
        private void copyOutboundOverflowBitsFrom(long groupIndex, long fromDataGroup) {
            long dataGroup = readDataGroup(this, groupIndex);
            dataGroup = copyOutboundOverflowBits(fromDataGroup, dataGroup);
            writeDataGroup(this, groupIndex, dataGroup);
        }

        /**
         * This method has the same structure as {@link
         * #moveContentsFromFullToIntermediateCapacitySegment}. These two methods should be updated
         * in parallel.
         *
         * This method writes zeros into this segment's {@link
         * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}: see
         * [Zero intermediateCapSegment_outboundOverflowCountsPerGroup].
         *
         * @param entries an array produced by {@link
         *        IntermediateCapacitySegment#copyEntriesToArrayDuringSegmentSwap} (see the
         *        structure of the array in the documentation for that method).
         * @return the updated bitSetAndState, not yet written into this segment's memory
         */
        @RarelyCalledAmortizedPerSegment
        private long copyContentsFromArrays(long bitSetAndState, long[] hashTable, Object[] entries,
                long outboundOverflowCountsPerGroup) {
            bitSetAndState = clearBitSet(bitSetAndState);

            for (int groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                long tagGroup = hashTable[groupIndex * 2];
                writeTagGroup(this, (long) groupIndex, tagGroup);

                long dataGroupToCopy = hashTable[groupIndex * 2 + 1];

                for (long bitMask = matchFull(dataGroupToCopy);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {
                    int trailingZeros = Long.numberOfTrailingZeros(bitMask);

                    Object key;
                    Object value;
                    {
                        int allocIndex = (int) extractAllocIndex(dataGroupToCopy, trailingZeros);
                        key = entries[allocIndex * 2];
                        value = entries[allocIndex * 2 + 1];
                    }

                    int slotIndexWithinGroup =
                            lowestMatchingSlotIndexFromTrailingZeros(trailingZeros);
                    bitSetAndState = insertDuringContentsMove(
                            bitSetAndState, (long) groupIndex, slotIndexWithinGroup, key, value);
                }

                copyOutboundOverflowBitsFrom((long) groupIndex, dataGroupToCopy);
            }

            this.outboundOverflowCountsPerGroup = outboundOverflowCountsPerGroup;

            return bitSetAndState;
        }

        @Override
        @RarelyCalledAmortizedPerSegment
        void copyEntriesDuringInflate(
                SmoothieMap<K, V> map, SmoothieMap.InflatedSegment<K, V> intoSegment) {
            // Just iterating all alloc indexes until constant SEGMENT_MAX_ALLOC_CAPACITY expecting
            // all keys and values to be non-null (otherwise ConcurrentModificationException should
            // be thrown from readKeyAtOffset() or readValueAtOffset() calls) because it must be
            // true in inflateAndInsert() from where this method is called that this segment is
            // full.

            // This loop calls expensive allocOffset(). Alternative is to unroll three separate
            // loops for stride 0, strides 1-7 and stride 8 respectively. But the method is
            // RarelyCalledAmortizedPerSegment so simplicity is valued more than performance.
            for (int allocIndex = 0; allocIndex < SEGMENT_MAX_ALLOC_CAPACITY; allocIndex++) {
                long allocOffset = allocOffset((long) allocIndex);
                K key = readKeyAtOffset(this, allocOffset);
                V value = readValueAtOffset(this, allocOffset);
                intoSegment.putDuringInflation(map, key, map.keyHashCode(key), value);
            }
        }

        /** Parallel to {@link IntermediateCapacitySegment#clearHashTableAndAllocArea}. */
        @Override
        void clearHashTableAndAllocArea() {
            // Clear the hash table.
            setAllDataGroups(EMPTY_DATA_GROUP);
            // Leaving garbage in tag groups.

            clearAllocArea();
        }

        /** Parallel to {@link IntermediateCapacitySegment#setAllDataGroups}. */
        @Override
        void setAllDataGroups(long dataGroup) {
            // [Int-indexed loop to avoid a safepoint poll]
            for (int groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                writeDataGroup(this, (long) groupIndex, dataGroup);
            }
        }

        /** Parallel to {@link IntermediateCapacitySegment#clearAllocArea}. */
        private void clearAllocArea() {
            // Having three separate loops instead of a simple loop for allocIndex from 0 to
            // SEGMENT_MAX_ALLOC_CAPACITY because allocOffset(allocIndex) operation is expensive.

            // ### Clearing partial stride 0.
            {
                U.putObject(this, ALLOC_INDEX_0_OFFSET, null);
                U.putObject(this, valueOffsetFromAllocOffset(ALLOC_INDEX_0_OFFSET), null);

                {
                    long allocIndex1_offset = ALLOC_INDEX_0_OFFSET + ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocIndex1_offset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocIndex1_offset), null);
                }

                {
                    long allocIndex2_offset =
                            ALLOC_INDEX_0_OFFSET + 2 * ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocIndex2_offset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocIndex2_offset), null);
                }
            }

            // ### Iterating full strides 1-7.
            // [Int-indexed loop to avoid a safepoint poll]
            for (int strideIndex = 1; strideIndex <= NUM_FULL_STRIDES; strideIndex++) {
                long strideOffset = STRIDE_0_OFFSET + ((long) strideIndex) * STRIDE_SIZE_IN_BYTES;
                // [Int-indexed loop to avoid a safepoint poll]
                for (int allocIndexWithinStride = 0;
                     allocIndexWithinStride < STRIDE_SIZE_IN_ALLOC_INDEXES;
                     allocIndexWithinStride++) {
                    long allocOffset = strideOffset +
                            ((long) allocIndexWithinStride) * ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocOffset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocOffset), null);
                }
            }

            // ### Clearing partial stride 8.
            {
                U.putObject(this, ALLOC_INDEX_45_OFFSET, null);
                U.putObject(this, valueOffsetFromAllocOffset(ALLOC_INDEX_45_OFFSET), null);

                {
                    long allocIndex46_offset =
                            ALLOC_INDEX_45_OFFSET + ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocIndex46_offset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocIndex46_offset), null);
                }

                {
                    long allocIndex47_offset =
                            ALLOC_INDEX_45_OFFSET + 2 * ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocIndex47_offset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocIndex47_offset), null);
                }
            }
        }

        /**
         * Method cloned in FullCapacitySegment and IntermediateCapacitySegment: this method is an
         * exact clone of {@link IntermediateCapacitySegment#aggregateStats}. The effective
         * difference between these methods is that they call to different static methods with the
         * same names defined in FullCapacitySegment and {@link IntermediateCapacitySegment}
         * respectively.
         */
        @Override
        void aggregateStats(SmoothieMap<K, V> map, OrdinarySegmentStats ordinarySegmentStats) {
            ordinarySegmentStats.incrementAggregatedSegments(bitSetAndState);
            // Sticking to [Branchless hash table iteration] in a cold method: the performance
            // considerations of branchless vs. byte-by-byte hash table iteration (see the
            // description of [Branchless hash table iteration] for details) are not important in
            // this method, but since performance-critical methods (such as SmoothieMap.doSplit())
            // already take the branchless approach other methods such as aggregateStats() follow
            // the performance-critical methods to reduce the variability in the codebase, that is,
            // to maintain just one mode of hash table iteration. Similar reasoning is applied in
            // [Same hash table iteration mode in symmetric methods]
            for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                long dataGroup = readDataGroup(this, groupIndex);
                int allocIndexBoundaryForGroup =
                        allocIndexBoundaryForLocalAllocation((int) groupIndex);
                for (long bitMask = matchFull(dataGroup);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {
                    long allocIndex = firstAllocIndex(dataGroup, bitMask);
                    long allocOffset = allocOffset(allocIndex);
                    K key = readKeyAtOffset(this, allocOffset);
                    long hash = map.keyHashCode(key);
                    long baseGroupIndex = baseGroupIndex(hash);
                    ordinarySegmentStats.aggregateFullSlot(baseGroupIndex, groupIndex,
                            map.countCollisionKeyComparisons(this, key, hash),
                            (int) allocIndex, allocIndexBoundaryForGroup);
                }
            }
        }

        /** [Method cloned in FullCapacitySegment and IntermediateCapacitySegment] */
        @Override
        String debugToString() {
            DebugBitSetAndState bitSetAndState = new DebugBitSetAndState(this.bitSetAndState);
            StringBuilder sb = new StringBuilder();
            sb.append(bitSetAndState).append('\n');
            sb.append("Slots:\n");
            for (int allocIndex = 0; allocIndex < bitSetAndState.allocCapacity; allocIndex++) {
                long allocOffset = allocOffset((long) allocIndex);
                Object key = U.getObject(this, allocOffset);
                Object value = U.getObject(this, valueOffsetFromAllocOffset(allocOffset));
                sb.append(key).append('=').append(value).append('\n');
            }
            return sb.toString();
        }

        /** [Method cloned in FullCapacitySegment and IntermediateCapacitySegment] */
        @Override
        @Nullable DebugHashTableSlot<K, V>[] debugHashTable(SmoothieMap<K, V> map) {
            @Nullable DebugHashTableSlot[] debugHashTableSlots =
                    new DebugHashTableSlot[HASH_TABLE_SLOTS];
            // [Sticking to [Branchless hash table iteration] in a cold method]
            for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                long dataGroup = readDataGroup(this, groupIndex);
                long tagGroup = readTagGroup(this, groupIndex);
                for (long bitMask = matchFull(dataGroup);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {
                    int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                    long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                    long valueOffset = valueOffsetFromAllocOffset(allocOffset(allocIndex));
                    byte tagByte = extractTagByte(tagGroup, trailingZeros);
                    int dataByte = Byte.toUnsignedInt(
                            extractDataByte(dataGroup, trailingZeros));
                    int slotIndexWithinGroup =
                            lowestMatchingSlotIndexFromTrailingZeros(trailingZeros);
                    int slotIndex = (int) (groupIndex * GROUP_SLOTS) + slotIndexWithinGroup;
                    debugHashTableSlots[slotIndex] = new DebugHashTableSlot<>(
                            map, this, (int) allocIndex, valueOffset, tagByte, dataByte);
                }
            }
            //noinspection unchecked
            return debugHashTableSlots;
        }

        //region specialized bulk methods

        /**
         * Mirror of {@link SmoothieMap.Segment#hashCode(SmoothieMap)}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEach}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachKey}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachValue}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachWhile}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#replaceAll}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#containsValue}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#writeAllEntries}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#removeIf}.
         *
         * Unlike other similar methods above belonging to the category
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment], this method is
         * very similar but not an exact clone of {@link IntermediateCapacitySegment#removeIf}:
         * isFullCapacitySegment local variable is initialized differently and there is a
         * difference in comments.
         */
        @Override
        int removeIf(
                SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount) {
            // TODO update according to other bulk methods above
            long bitSetAndState = this.bitSetAndState;
            int initialModCount = modCount;
            try {
                // [Branchless hash table iteration in removeIf()]
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
                        /* if Supported intermediateSegments */
                        int isFullCapacitySegment = 1;
                        /* endif */
                        // TODO research if it's possible to do something better than just call
                        //  removeAtSlotNoShrink() in a loop, which may result in calling expensive
                        //  operations a lot of times if nearly all entries are removed from the
                        //  segment during this loop.
                        bitSetAndState = map.removeAtSlotNoShrink(bitSetAndState, this,
                                // Not specializing removeAtSlotNoShrink(): it doesn't provide much
                                // performance benefit to specialize removeAtSlotNoShrink() for
                                // full-capacity segments because isFullCapacitySegment is rarely
                                // used inside that method, while having a copy of
                                // removeAtSlotNoShrink() is an additional maintenance burden. JVM's
                                // code cache and instruction cache are unlikely to be important
                                // performance factors here because this removeAtSlotNoShrink() call
                                // is expected to be inlined into this method.
                                /* if Supported intermediateSegments */isFullCapacitySegment,
                                /* endif */
                                outboundOverflowCount_perGroupDecrements, iterDataGroupOffset,
                                setSlotEmpty(dataGroup, trailingZeros), allocIndex, allocOffset);
                        // Matches the modCount field increment performed in removeAtSlotNoShrink().
                        modCount++;
                    }
                }
            } finally {
                // [Writing bitSetAndState in a finally block]
                if (modCount != initialModCount) {
                    this.bitSetAndState = bitSetAndState;
                }
            }
            return modCount;
        }

        //endregion
    }

    //region IntermediateCapacitySegment's layout classes
    static abstract class IntermediateCapacitySegment_AllocationSpaceBeforeGroup0<K, V>
            extends SmoothieMap.Segment<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k0, v0, k1, v1;
    }

    static abstract class IntermediateCapacitySegment_Group0<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBeforeGroup0<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup0, dataGroup0;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups0And1<K, V>
            extends IntermediateCapacitySegment_Group0<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k2, v2, k3, v3, k4, v4, k5, v5;
    }

    static abstract class IntermediateCapacitySegment_Group1<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups0And1<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup1, dataGroup1;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups1And2<K, V>
            extends IntermediateCapacitySegment_Group1<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k6, v6, k7, v7, k8, v8, k9, v9;
    }

    static abstract class IntermediateCapacitySegment_Group2<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups1And2<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup2, dataGroup2;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups2And3<K, V>
            extends IntermediateCapacitySegment_Group2<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k10, v10, k11, v11, k12, v12, k13, v13;
    }

    static abstract class IntermediateCapacitySegment_Group3<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups2And3<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup3, dataGroup3;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups3And4<K, V>
            extends IntermediateCapacitySegment_Group3<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k14, v14, k15, v15, k16, v16, k17, v17;
    }

    static abstract class IntermediateCapacitySegment_Group4<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups3And4<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup4, dataGroup4;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups4And5<K, V>
            extends IntermediateCapacitySegment_Group4<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k18, v18, k19, v19, k20, v20, k21, v21;
    }

    static abstract class IntermediateCapacitySegment_Group5<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups4And5<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup5, dataGroup5;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups5And6<K, V>
            extends IntermediateCapacitySegment_Group5<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k22, v22, k23, v23, k24, v24, k25, v25;
    }

    static abstract class IntermediateCapacitySegment_Group6<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups5And6<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup6, dataGroup6;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceBetweenGroups6And7<K, V>
            extends IntermediateCapacitySegment_Group6<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k26, v26, k27, v27, k28, v28, k29, v29;
    }

    static abstract class IntermediateCapacitySegment_Group7<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceBetweenGroups6And7<K, V> {
        @SuppressWarnings("unused")
        private long tagGroup7, dataGroup7;
    }

    static abstract class IntermediateCapacitySegment_AllocationSpaceAfterGroup7<K, V>
            extends IntermediateCapacitySegment_Group7<K, V> {
        @SuppressWarnings("unused")
        @Nullable Object k30, v30, k31, v31;
    }
    //endregion

    /**
     * The memory layout of an intermediate-capacity segment is the following:
     * ..td....td....td....td....td....td....td....td..
     * Where each dot identifies an allocation index (space for a key and a value), letter 't'
     * identifies a tag group, and letter 'd' identifies a data group. 32 (=
     * {@link SmoothieMap#SEGMENT_INTERMEDIATE_ALLOC_CAPACITY}) dots (that is, allocation indexes)
     * in total.
     */
    static class IntermediateCapacitySegment<K, V>
            extends IntermediateCapacitySegment_AllocationSpaceAfterGroup7<K, V> {
        static final long TAG_GROUP_0_OFFSET;
        private static final long DATA_GROUP_0_OFFSET;

        static final long STRIDE_SIZE_IN_BYTES;

        /**
         * = {@link SmoothieMap#SEGMENT_INTERMEDIATE_ALLOC_CAPACITY} /
         *   {@link HashTable#HASH_TABLE_GROUPS}
         */
        static final int STRIDE_SIZE_IN_ALLOC_INDEXES = 4;
        /** = numberOfTrailingZeros({@link #STRIDE_SIZE_IN_ALLOC_INDEXES}) */
        @CompileTimeConstant
        static final int STRIDE_SIZE_IN_ALLOC_INDEXES__SHIFT = 2;
        static final int STRIDE_SIZE_IN_ALLOC_INDEXES__MASK = STRIDE_SIZE_IN_ALLOC_INDEXES - 1;

        static {
            verifyEqual(STRIDE_SIZE_IN_ALLOC_INDEXES,
                    SEGMENT_INTERMEDIATE_ALLOC_CAPACITY / HASH_TABLE_GROUPS);
            verifyEqual(STRIDE_SIZE_IN_ALLOC_INDEXES__SHIFT,
                    Integer.numberOfTrailingZeros(STRIDE_SIZE_IN_ALLOC_INDEXES));
        }

        /**
         * Stride 0 begins before actual data starts, at an impossible location "before" {@link
         * IntermediateCapacitySegment_AllocationSpaceBeforeGroup0}, to simplify the implementation
         * of {@link #allocOffset}.
         */
        static final long STRIDE_0_OFFSET;

        static final long ALLOC_INDEX_0_OFFSET;
        static final long ALLOC_INDEX_30_OFFSET;

        /**
         * The number of "real" alloc indexes in stride 0, in other words, in {@link
         * IntermediateCapacitySegment_AllocationSpaceBeforeGroup0}.
         */
        static final int STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES = 2;

        /**
         * The number of "real" alloc indexes in stride 8, in other words, in {@link
         * IntermediateCapacitySegment_AllocationSpaceAfterGroup7}.
         */
        static final int STRIDE_8__NUM_ACTUAL_ALLOC_INDEXES = 2;

        static {
            // FullCapacitySegment has the same initialization block. These blocks should be updated
            // in parallel.

            TAG_GROUP_0_OFFSET = minInstanceFieldOffset(IntermediateCapacitySegment_Group0.class);
            // tagGroupFromDataGroupOffset() depends on the fact that we lay tag groups before
            // data groups.
            // TODO detect negative field offsets and reverse all offset increments
            DATA_GROUP_0_OFFSET = TAG_GROUP_0_OFFSET + Long.BYTES;

            long tagGroup1_offset =
                    minInstanceFieldOffset(IntermediateCapacitySegment_Group1.class);
            STRIDE_SIZE_IN_BYTES = tagGroup1_offset - TAG_GROUP_0_OFFSET;
            long stride1_offset = minInstanceFieldOffset(
                    IntermediateCapacitySegment_AllocationSpaceBetweenGroups0And1.class);
            STRIDE_0_OFFSET = stride1_offset - STRIDE_SIZE_IN_BYTES;
            verifyEqual(STRIDE_0_OFFSET,
                    // Long.BYTES * 2 means tag group and data group.
                    TAG_GROUP_0_OFFSET - STRIDE_SIZE_IN_BYTES + (Long.BYTES * 2));

            ALLOC_INDEX_0_OFFSET = allocOffset(0);
            long allocIndex0_offset_obtainedFromFields = minInstanceFieldOffset(
                    IntermediateCapacitySegment_AllocationSpaceBeforeGroup0.class);
            verifyEqual(ALLOC_INDEX_0_OFFSET, allocIndex0_offset_obtainedFromFields);

            ALLOC_INDEX_30_OFFSET = allocOffset(30);
            long allocIndex30_offset_obtainedFromFields = minInstanceFieldOffset(
                    IntermediateCapacitySegment_AllocationSpaceAfterGroup7.class);
            verifyEqual(ALLOC_INDEX_30_OFFSET, allocIndex30_offset_obtainedFromFields);
        }

        @HotPath
        private static long tagGroupOffset(long groupIndex) {
            return TAG_GROUP_0_OFFSET + (groupIndex * STRIDE_SIZE_IN_BYTES);
        }

        @HotPath
        private static long readTagGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            return U.getLong(segment, tagGroupOffset(groupIndex));
        }

        private static void writeTagGroup(Object segment, long groupIndex, long tagGroup) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            U.putLong(segment, tagGroupOffset(groupIndex), tagGroup);
        }

        @HotPath
        private static long dataGroupOffset(long groupIndex) {
            return DATA_GROUP_0_OFFSET + (groupIndex * STRIDE_SIZE_IN_BYTES);
        }

        @HotPath
        private static long readDataGroup(Object segment, long groupIndex) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            return U.getLong(segment, dataGroupOffset(groupIndex));
        }

        private static void writeDataGroup(Object segment, long groupIndex, long dataGroup) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            /* endif */
            U.putLong(segment, dataGroupOffset(groupIndex), dataGroup);
        }

        private static void writeData(
                Object segment, long groupIndex, int slotIndexWithinGroup, byte data) {
            /* if Enabled extraChecks */
            assert segment != null;
            assertValidGroupIndex(groupIndex);
            assertValidSlotIndexWithinGroup(slotIndexWithinGroup);
            /* endif */
            long slotByteOffset = slotByteOffset(slotIndexWithinGroup);
            U.putByte(segment, dataGroupOffset(groupIndex) + slotByteOffset, data);
        }

        /** @see BitSetAndState#freeAllocIndexClosestTo */
        private static int allocIndexBoundaryForLocalAllocation(int groupIndex) {
            return STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES + groupIndex * STRIDE_SIZE_IN_ALLOC_INDEXES;
        }

        @HotPath
        private static long allocOffset(long allocIndex) {
            // Making allocIndex to be relative to stride 0. [Reusing local variable].
            allocIndex += STRIDE_SIZE_IN_ALLOC_INDEXES - STRIDE_0__NUM_ACTUAL_ALLOC_INDEXES;
            long strideIndex = allocIndex >>> STRIDE_SIZE_IN_ALLOC_INDEXES__SHIFT;
            long allocIndexWithinStride = allocIndex & STRIDE_SIZE_IN_ALLOC_INDEXES__MASK;
            return STRIDE_0_OFFSET + strideIndex * STRIDE_SIZE_IN_BYTES +
                    allocIndexWithinStride * ALLOCATION_INDEX_SIZE_IN_BYTES;
        }

        /**
         * Parallel to {@link FullCapacitySegment#writeKeyAndValueAtIndex}. A generalized version is
         * {@link SmoothieMap.Segment#writeKeyAndValueAtIndex}.
         */
        static void writeKeyAndValueAtIndex(
                Object segment, int allocIndex, Object key, Object value) {
            /* if Enabled extraChecks */assert segment != null;/* endif */
            long allocOffset = allocOffset((long) allocIndex);
            U.putObject(segment, allocOffset, key);
            U.putObject(segment, valueOffsetFromAllocOffset(allocOffset), value);
        }

        /**
         * A special method which must only be called from {@link #swapContentsDuringSplit}. It
         * finds the best free alloc index for the given groupIndex and inserts a data byte at the
         * given slotIndexWithinGroup (with {@link HashTable#DATA__OUTBOUND_OVERFLOW_BIT} set to
         * zero) and writes the key and the value at the found alloc index.
         *
         * This method is parallel to {@link FullCapacitySegment#insertDuringContentsMove}.
         *
         * @return the updated bitSetAndState
         */
        private long insertDuringContentsMove(long bitSetAndState,
                long groupIndex, int slotIndexWithinGroup, Object key, Object value) {
            // TODO check if specialization of freeAllocIndexClosestTo() makes any difference here
            //  or JIT compiler scalarizes the SEGMENT_INTERMEDIATE_ALLOC_CAPACITY argument cleanly.
            int insertionAllocIndex = freeAllocIndexClosestTo(bitSetAndState,
                    allocIndexBoundaryForLocalAllocation((int) groupIndex)
                    /* if Supported intermediateSegments */, SEGMENT_INTERMEDIATE_ALLOC_CAPACITY
                    /* endif */);
            if (insertionAllocIndex >= SEGMENT_INTERMEDIATE_ALLOC_CAPACITY) {
                // Can happen if entries are inserted into fullCapacitySegment (see the code of
                // swapContentsDuringSplit()) concurrently with doSplit(), including concurrently
                // with swapContentsDuringSplit() which is called from doSplit().
                throw new ConcurrentModificationException();
            }

            // Write the new data slot into the segment's hash table.
            byte dataToInsert = makeDataWithZeroOutboundOverflowBit(insertionAllocIndex);
            writeData(this, groupIndex, slotIndexWithinGroup, dataToInsert);

            writeKeyAndValueAtIndex(this, insertionAllocIndex, key, value);

            bitSetAndState = setAllocBit(bitSetAndState, insertionAllocIndex);
            return bitSetAndState;
        }

        /** Parallel to {@link FullCapacitySegment#copyOutboundOverflowBitsFrom}. */
        private void copyOutboundOverflowBitsFrom(long groupIndex, long fromDataGroup) {
            long dataGroup = readDataGroup(this, groupIndex);
            dataGroup = copyOutboundOverflowBits(fromDataGroup, dataGroup);
            writeDataGroup(this, groupIndex, dataGroup);
        }

        /**
         * This method has the same structure as {@link FullCapacitySegment#copyEntriesDuringInflate
         * }. These methods should be changed in parallel.
         *
         * @return an array of {@link SmoothieMap#SEGMENT_INTERMEDIATE_ALLOC_CAPACITY} * 2 of the
         * form [k0, v0, k1, v1, ...] where 'kX' and 'vX' means "key (or value) from the alloc index
         * X in this segment".
         */
        @RarelyCalledAmortizedPerSegment
        private Object[] copyEntriesToArrayDuringSegmentSwap() {
            // Just iterating all alloc indexes until constant SEGMENT_INTERMEDIATE_ALLOC_CAPACITY
            // expecting all keys and values to be non-null (otherwise
            // ConcurrentModificationException should be thrown from readKeyAtOffset() or
            // readValueAtOffset() calls) because it must be true in swapContentsDuringSplit() from
            // where this method is called that this segment is full.

            Object[] entries = new Object[SEGMENT_INTERMEDIATE_ALLOC_CAPACITY * 2];
            int entriesIndex = 0;
            // This loop calls expensive allocOffset(). Alternative is to unroll three separate
            // loops for stride 0, strides 1-7 and stride 8 respectively. But the method is
            // RarelyCalledAmortizedPerSegment so simplicity is valued more than performance.
            for (int allocIndex = 0; allocIndex < SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
                 allocIndex++) {
                long allocOffset = allocOffset((long) allocIndex);
                Object key = readKeyAtOffset(this, allocOffset);
                Object value = readValueAtOffset(this, allocOffset);
                entries[entriesIndex++] = key;
                entries[entriesIndex++] = value;
            }
            return entries;
        }

        @RarelyCalledAmortizedPerSegment
        private long[] copyHashTableToArrayDuringSegmentSwap() {
            long[] hashTable = new long[HASH_TABLE_GROUPS * 2];
            int hashTableIndex = 0;
            for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                hashTable[hashTableIndex++] = readTagGroup(this, groupIndex);
                hashTable[hashTableIndex++] = readDataGroup(this, groupIndex);
            }
            return hashTable;
        }

        /** Parallel to {@link FullCapacitySegment#clearHashTableAndAllocArea}. */
        @Override
        void clearHashTableAndAllocArea() {
            // Clear the hash table.
            setAllDataGroups(EMPTY_DATA_GROUP);
            // Leaving garbage in tag groups.

            clearAllocArea();
        }

        /** Parallel to {@link FullCapacitySegment#setAllDataGroups}. */
        @Override
        void setAllDataGroups(long dataGroup) {
            // [Int-indexed loop to avoid a safepoint poll]
            for (int groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                writeDataGroup(this, (long) groupIndex, dataGroup);
            }
        }

        /** Parallel to {@link FullCapacitySegment#clearAllocArea}. */
        private void clearAllocArea() {
            // Having three separate loops instead of a simple loop for allocIndex from 0 to
            // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY because allocOffset(allocIndex) operation is
            // expensive.

            // ### Clearing partial stride 0.
            {
                U.putObject(this, ALLOC_INDEX_0_OFFSET, null);
                U.putObject(this, valueOffsetFromAllocOffset(ALLOC_INDEX_0_OFFSET), null);

                long allocIndex1_offset = ALLOC_INDEX_0_OFFSET + ALLOCATION_INDEX_SIZE_IN_BYTES;
                U.putObject(this, allocIndex1_offset, null);
                U.putObject(this, valueOffsetFromAllocOffset(allocIndex1_offset), null);
            }

            // ### Iterating full strides 1-7.
            // [Int-indexed loop to avoid a safepoint poll]
            for (int strideIndex = 1; strideIndex <= NUM_FULL_STRIDES; strideIndex++) {
                long strideOffset = STRIDE_0_OFFSET + ((long) strideIndex) * STRIDE_SIZE_IN_BYTES;
                // [Int-indexed loop to avoid a safepoint poll]
                for (int allocIndexWithinStride = 0;
                     allocIndexWithinStride < STRIDE_SIZE_IN_ALLOC_INDEXES;
                     allocIndexWithinStride++) {
                    long allocOffset = strideOffset +
                            ((long) allocIndexWithinStride) * ALLOCATION_INDEX_SIZE_IN_BYTES;
                    U.putObject(this, allocOffset, null);
                    U.putObject(this, valueOffsetFromAllocOffset(allocOffset), null);
                }
            }

            // ### Clearing partial stride 8.
            {
                U.putObject(this, ALLOC_INDEX_30_OFFSET, null);
                U.putObject(this, valueOffsetFromAllocOffset(ALLOC_INDEX_30_OFFSET), null);

                long allocIndex31_offset = ALLOC_INDEX_30_OFFSET + ALLOCATION_INDEX_SIZE_IN_BYTES;
                U.putObject(this, allocIndex31_offset, null);
                U.putObject(this, valueOffsetFromAllocOffset(allocIndex31_offset), null);
            }
        }

        @Override
        void copyEntriesDuringInflate(
                SmoothieMap<K, V> map, SmoothieMap.InflatedSegment<K, V> intoSegment) {
            throw new UnsupportedOperationException(
                    "copyEntriesDuringInflate should be called only on full-capacity segments");
        }

        /** [Method cloned in FullCapacitySegment and IntermediateCapacitySegment] */
        @Override
        void aggregateStats(SmoothieMap<K, V> map, OrdinarySegmentStats ordinarySegmentStats) {
            ordinarySegmentStats.incrementAggregatedSegments(bitSetAndState);
            // [Sticking to [Branchless hash table iteration] in a cold method]
            for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                long dataGroup = readDataGroup(this, groupIndex);
                int allocIndexBoundaryForGroup =
                        allocIndexBoundaryForLocalAllocation((int) groupIndex);
                for (long bitMask = matchFull(dataGroup);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {
                    long allocIndex = firstAllocIndex(dataGroup, bitMask);
                    long allocOffset = allocOffset(allocIndex);
                    K key = readKeyAtOffset(this, allocOffset);
                    long hash = map.keyHashCode(key);
                    long baseGroupIndex = baseGroupIndex(hash);
                    ordinarySegmentStats.aggregateFullSlot(baseGroupIndex, groupIndex,
                            map.countCollisionKeyComparisons(this, key, hash),
                            (int) allocIndex, allocIndexBoundaryForGroup);
                }
            }
        }

        /** [Method cloned in FullCapacitySegment and IntermediateCapacitySegment] */
        @Override
        String debugToString() {
            DebugBitSetAndState bitSetAndState = new DebugBitSetAndState(this.bitSetAndState);
            StringBuilder sb = new StringBuilder();
            sb.append(bitSetAndState).append('\n');
            sb.append("Slots:\n");
            for (int allocIndex = 0; allocIndex < bitSetAndState.allocCapacity; allocIndex++) {
                long allocOffset = allocOffset((long) allocIndex);
                Object key = U.getObject(this, allocOffset);
                Object value = U.getObject(this, valueOffsetFromAllocOffset(allocOffset));
                sb.append(key).append('=').append(value).append('\n');
            }
            return sb.toString();
        }

        /** [Method cloned in FullCapacitySegment and IntermediateCapacitySegment] */
        @Override
        @Nullable DebugHashTableSlot<K, V>[] debugHashTable(SmoothieMap<K, V> map) {
            @Nullable DebugHashTableSlot[] debugHashTableSlots =
                    new DebugHashTableSlot[HASH_TABLE_SLOTS];
            // [Sticking to [Branchless hash table iteration] in a cold method]
            for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
                long dataGroup = readDataGroup(this, groupIndex);
                long tagGroup = readTagGroup(this, groupIndex);
                for (long bitMask = matchFull(dataGroup);
                     bitMask != 0L;
                     bitMask = clearLowestSetBit(bitMask)) {
                    int trailingZeros = Long.numberOfTrailingZeros(bitMask);
                    long allocIndex = extractAllocIndex(dataGroup, trailingZeros);
                    long valueOffset = valueOffsetFromAllocOffset(allocOffset(allocIndex));
                    byte tagByte = extractTagByte(tagGroup, trailingZeros);
                    int dataByte = Byte.toUnsignedInt(
                            extractDataByte(dataGroup, trailingZeros));
                    int slotIndexWithinGroup =
                            lowestMatchingSlotIndexFromTrailingZeros(trailingZeros);
                    int slotIndex = (int) (groupIndex * GROUP_SLOTS) + slotIndexWithinGroup;
                    debugHashTableSlots[slotIndex] = new DebugHashTableSlot<>(
                            map, this, (int) allocIndex, valueOffset, tagByte, dataByte);
                }
            }
            //noinspection unchecked
            return debugHashTableSlots;
        }

        //region specialized bulk methods

        /**
         * Mirror of {@link SmoothieMap.Segment#hashCode(SmoothieMap)}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEach}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachKey}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachValue}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#forEachWhile}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#replaceAll}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#containsValue}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#writeAllEntries}.
         *
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment]
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

        /**
         * Mirror of {@link SmoothieMap.Segment#removeIf}.
         *
         * Unlike other similar methods above belonging to the category
         * [Method cloned in FullCapacitySegment and IntermediateCapacitySegment], this method is
         * very similar but not an exact clone of {@link FullCapacitySegment#removeIf}:
         * isFullCapacitySegment local variable is initialized differently and there is a
         * difference in comments.
         */
        @Override
        int removeIf(
                SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter, int modCount) {
            long bitSetAndState = this.bitSetAndState;
            int initialModCount = modCount;
            try {
                // [Branchless hash table iteration in removeIf()]
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
                        /* if Supported intermediateSegments */
                        int isFullCapacitySegment = 0;
                        /* endif */
                        // TODO research if it's possible to do something better than just call
                        //  removeAtSlotNoShrink() in a loop, which may result in calling expensive
                        //  operations a lot of times if nearly all entries are removed from the
                        //  segment during this loop.
                        bitSetAndState = map.removeAtSlotNoShrink(bitSetAndState, this,
                                // [Not specializing removeAtSlotNoShrink()]
                                /* if Supported intermediateSegments */isFullCapacitySegment,
                                /* endif */
                                outboundOverflowCount_perGroupDecrements, iterDataGroupOffset,
                                setSlotEmpty(dataGroup, trailingZeros), allocIndex, allocOffset);
                        // Matches the modCount field increment performed in removeAtSlotNoShrink().
                        modCount++;
                    }
                }
            } finally {
                // [Writing bitSetAndState in a finally block]
                if (modCount != initialModCount) {
                    this.bitSetAndState = bitSetAndState;
                }
            }
            return modCount;
        }

        //endregion
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
        if (allocCapacity == SEGMENT_MAX_ALLOC_CAPACITY) {
            return new FullCapacitySegment<>();
        } else if (allocCapacity == SEGMENT_INTERMEDIATE_ALLOC_CAPACITY) {
            return new IntermediateCapacitySegment<>();
        } else {
            // More capacities than just SEGMENT_MAX_ALLOC_CAPACITY = 48 and
            // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY = 32 could be supported: for example, 40 and 24.
            // Also, capacities near the multiples of 8 (e. g. +2/-2) could also be supported if
            // some asymmetry of alloc indexes around groups is tolerated (see the comment for
            // SEGMENT_INTERMEDIATE_ALLOC_CAPACITY).
            throw new AssertionError("Interleaved segments cannot have arbitrary capacity");
        }
    }
}
