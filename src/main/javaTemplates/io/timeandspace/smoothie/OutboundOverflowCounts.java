package io.timeandspace.smoothie;

/* if Continuous segments */
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.getOutboundOverflowCountsPerGroup;
import static io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea.setOutboundOverflowCountsPerGroup;
/* elif Interleaved segments //
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.getOutboundOverflowCountsPerGroup;
import static io.timeandspace.smoothie.InterleavedSegment_BitSetAndStateArea.setOutboundOverflowCountsPerGroup;
// endif */
/* if Continuous segments //
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.dataGroupOffset;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.readDataGroup;
import static io.timeandspace.smoothie.ContinuousSegments.HashTableArea.writeDataGroup;
/* elif Interleaved segments */
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS;
import static io.timeandspace.smoothie.HashTable.shouldStopProbing;
import static io.timeandspace.smoothie.InterleavedSegments.dataGroupOffset;
import static io.timeandspace.smoothie.InterleavedSegments.readDataGroupAtOffset;
import static io.timeandspace.smoothie.InterleavedSegments.writeDataGroupAtOffset;
// endif */
import static io.timeandspace.smoothie.HashTable.DATA_GROUP__OUTBOUND_OVERFLOW_MASK;
import static io.timeandspace.smoothie.HashTable.GROUP_SLOTS;
import static io.timeandspace.smoothie.HashTable.addGroupIndex;
import static io.timeandspace.smoothie.HashTable.assertValidGroupIndex;
import static io.timeandspace.smoothie.LongMath.clearLowestSetBit;
import static io.timeandspace.smoothie.Utils.BYTE_SIZE_DIVISION_SHIFT;

/**
 * This class is a collection of static utility methods for working with {@link
 * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}.
 */
final class OutboundOverflowCounts {

    /**
     * Values of these two constants correspond to {@link HashTable#MOST_SIGNIFICANT_SLOT_BITS} and
     * {@link HashTable#LEAST_SIGNIFICANT_SLOT_BITS} but semantics are independent so they are kept
     * separate.
     */
    private static final long MOST_SIGNIFICANT_BYTE_BITS = 0x8080808080808080L;
    private static final long LEAST_SIGNIFICANT_BYTE_BITS = 0x0101010101010101L;

    static long outboundOverflowCount_markGroupForChange(
            long outboundOverflowCount_perGroupChanges, long groupIndex) {
        /* if Enabled extraChecks */assertValidGroupIndex(groupIndex);/* endif */
        return outboundOverflowCount_perGroupChanges |
                outboundOverflowCount_groupForChange(groupIndex);
    }

    static long outboundOverflowCount_groupForChange(long groupIndex) {
        /* if Enabled extraChecks */assertValidGroupIndex(groupIndex);/* endif */
        return 1L << (groupIndex * GROUP_SLOTS);
    }

    static long computeOutboundOverflowCount_perGroupChanges(
            long baseGroupIndex, long finalGroupIndex) {
        if (baseGroupIndex == finalGroupIndex) { // [Positive likely branch]
            return 0L;
        } else {
            return computeOutboundOverflowCount_perGroupChanges0(baseGroupIndex, finalGroupIndex);
        }
    }

    /**
     * Precomputed outboundOverflowCount_perGroupChanges: an alternative approach to implementing
     * this method is a precomputed table of
     * outboundOverflowCount_perGroupChanges[baseGroupIndex][finalGroupIndex].
     * Or it can be outboundOverflowCount_perGroupChanges[finalGroupIndex - baseGroupIndex] and then
     *  Long.rotateLeft/Right(baseGroupIndex).
     * TODO compare the approaches
     */
    private static long computeOutboundOverflowCount_perGroupChanges0(
            final long baseGroupIndex, final long finalGroupIndex) {
        long outboundOverflowCount_perGroupChanges = 0;
        for (long groupIndex = baseGroupIndex, groupIndexStep = 0; ; ) {
            outboundOverflowCount_perGroupChanges = outboundOverflowCount_markGroupForChange(
                    outboundOverflowCount_perGroupChanges, groupIndex);
            groupIndexStep += 1; // [Quadratic probing]
            groupIndex = addGroupIndex(groupIndex, groupIndexStep);
            if (groupIndex == finalGroupIndex) {
                return outboundOverflowCount_perGroupChanges;
            }
        }
    }

    /**
     * Increments the per-group outbound overflow entry counts ({@link
     * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}). If the count was zero
     * for a group, changes the corresponding dataGroup to indicate the presence of overflow entries
     * via setting {@link HashTable#DATA_GROUP__OUTBOUND_OVERFLOW_MASK} bits (see {@link
     * HashTable#shouldStopProbing}).
     */
    @ColdPath
    static void incrementOutboundOverflowCountsPerGroup(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupIncrements) {
        // TODO add check that every byte in outboundOverflowCount_perGroupIncrements is either 0 or
        //  1. For values greater than 1, addOutboundOverflowCountsPerGroup() should be called
        //  instead.
        long matchIncrements = outboundOverflowCount_perGroupIncrements << (Byte.SIZE - 1);
        addOutboundOverflowCountsPerGroup(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupIncrements, matchIncrements);
    }

    @AmortizedPerSegment
    static void addOutboundOverflowCountsPerGroup(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupAdditions) {
        addOutboundOverflowCountsPerGroup(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupAdditions,
                matchChanges(outboundOverflowCount_perGroupAdditions));
    }

    /**
     * This method and {@link #subtractOutboundOverflowCountsPerGroup(Object, int, long, long)} have
     * the same structure. These methods should be changed in parallel.
     */
    @ColdPath
    private static void addOutboundOverflowCountsPerGroup(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupAdditions, long matchAdditions) {
        long oldOutboundOverflowCountsPerGroup = getOutboundOverflowCountsPerGroup(segment);
        long newOutboundOverflowCountsPerGroup =
                oldOutboundOverflowCountsPerGroup + outboundOverflowCount_perGroupAdditions;
        setOutboundOverflowCountsPerGroup(segment, newOutboundOverflowCountsPerGroup);

        // Find groups where the outbound overflow count is increased from zero and set
        // DATA__OUTBOUND_OVERFLOW_BIT in all slots in the corresponding dataGroup.

        // The same bitwise technique as in HashTable.match().
        // `~(oldOutboundOverflowCountsPerGroup << (Byte.SIZE - 1))` excludes false-positives when
        // a zero byte precedes a byte with value 1 to which addition has been done.
        long bitMask = (oldOutboundOverflowCountsPerGroup - LEAST_SIGNIFICANT_BYTE_BITS) &
                matchAdditions & ~(oldOutboundOverflowCountsPerGroup << (Byte.SIZE - 1));
        for (; bitMask != 0; bitMask = clearLowestSetBit(bitMask)) {
            // TODO check if unsigned conversion produces shorter assembly:
            // some JIT may move the value between registers unnecessarily to extend the sign. We
            // know here that the value is always positive.
            // TODO check the comparison is made and it turns out that unsigned conversion is better
            //  go over all `(long) ` occurrences in the codebase and replace with unsigned
            //  conversion if the value is always positive.
            // [Replacing division with shift]
            long groupIndex =
                    (long) Long.numberOfTrailingZeros(bitMask) >>> BYTE_SIZE_DIVISION_SHIFT;
            long dataGroupOffset = dataGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            dataGroup |= DATA_GROUP__OUTBOUND_OVERFLOW_MASK;
            writeDataGroupAtOffset(segment, dataGroupOffset, dataGroup);
        }
        checkOutboundOverflowCounts(segment
                /* if Interleaved segments Supported intermediateSegments */, isFullCapacitySegment
                /* endif */);
    }

    /**
     * Decrements the per-group outbound overflow entry counts ({@link
     * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}). If the count becomes
     * zero for a group, changes the corresponding dataGroup to indicate the absence of overflow
     * entries via clearing {@link HashTable#DATA_GROUP__OUTBOUND_OVERFLOW_MASK} bits (see {@link
     * HashTable#shouldStopProbing}).
     */
    @ColdPath
    static void decrementOutboundOverflowCountsPerGroup(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupDecrements) {
        // TODO add check that every byte in outboundOverflowCount_perGroupDecrements is either 0
        //  or 1. For values greater than 1, subtractOutboundOverflowCountsPerGroup() should be
        //  called instead.
        long matchDecrements = outboundOverflowCount_perGroupDecrements << (Byte.SIZE - 1);
        subtractOutboundOverflowCountsPerGroup(segment,
                /* if Interleaved segments Supported intermediateSegments */isFullCapacitySegment,
                /* endif */
                outboundOverflowCount_perGroupDecrements, matchDecrements);
    }

    @AmortizedPerSegment
    static void subtractOutboundOverflowCountsPerGroupAndUpdateAllGroups(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupDeductions) {
        long outboundOverflowCountsPerGroup = getOutboundOverflowCountsPerGroup(segment);
        outboundOverflowCountsPerGroup -= outboundOverflowCount_perGroupDeductions;
        setOutboundOverflowCountsPerGroup(segment, outboundOverflowCountsPerGroup);

        // Update DATA__OUTBOUND_OVERFLOW_BIT (see HashTable class) in all slots in the hash table
        // in a branchless manner.

        // [Int-indexed loop to avoid a safepoint poll]
        for (int groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
            long outboundOverflowCount = outboundOverflowCountsPerGroup & 0xFF;
            outboundOverflowCountsPerGroup >>>= Byte.SIZE;

            long outboundOverflowCountIsZero = (outboundOverflowCount - 1) >>> (Long.SIZE - 1);
            long dataGroupOffset = dataGroupOffset((long) groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long outboundOverflowCountIsPositive = 1L - outboundOverflowCountIsZero;
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            dataGroup &= ~(DATA_GROUP__OUTBOUND_OVERFLOW_MASK * outboundOverflowCountIsZero);
            dataGroup |= DATA_GROUP__OUTBOUND_OVERFLOW_MASK * outboundOverflowCountIsPositive;
            writeDataGroupAtOffset(segment, dataGroupOffset, dataGroup);
        }
        checkOutboundOverflowCounts(segment
                /* if Interleaved segments Supported intermediateSegments */, isFullCapacitySegment
                /* endif */);
    }

    /**
     * This method and {@link #addOutboundOverflowCountsPerGroup(Object, int, long, long)} have the
     * same structure. These methods should be changed in parallel.
     */
    @ColdPath
    private static void subtractOutboundOverflowCountsPerGroup(Object segment,
            /* if Interleaved segments Supported intermediateSegments */int isFullCapacitySegment,
            /* endif */
            long outboundOverflowCount_perGroupDeductions, long matchDeductions) {
        long outboundOverflowCountsPerGroup = getOutboundOverflowCountsPerGroup(segment);
        outboundOverflowCountsPerGroup -= outboundOverflowCount_perGroupDeductions;
        setOutboundOverflowCountsPerGroup(segment, outboundOverflowCountsPerGroup);

        // Find groups where the outbound overflow count is decreased to zero and unset
        // DATA__OUTBOUND_OVERFLOW_BIT in all slots in the corresponding dataGroup.

        // The same bitwise technique as in HashTable.match().
        // `~(outboundOverflowCountsPerGroup << (Byte.SIZE - 1))` excludes false-positives when a
        // zero byte precedes a byte with value 1 from which outbound overflow count deduction has
        // been done.
        long bitMask = (outboundOverflowCountsPerGroup - LEAST_SIGNIFICANT_BYTE_BITS) &
                matchDeductions & ~(outboundOverflowCountsPerGroup << (Byte.SIZE - 1));
        for (; bitMask != 0; bitMask = clearLowestSetBit(bitMask)) {
            // TODO [check if unsigned conversion produces shorter assembly]
            // [Replacing division with shift]
            long groupIndex =
                    (long) Long.numberOfTrailingZeros(bitMask) >>> BYTE_SIZE_DIVISION_SHIFT;
            long dataGroupOffset = dataGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            dataGroup &= ~DATA_GROUP__OUTBOUND_OVERFLOW_MASK;
            writeDataGroupAtOffset(segment, dataGroupOffset, dataGroup);
        }
        checkOutboundOverflowCounts(segment
                /* if Interleaved segments Supported intermediateSegments */, isFullCapacitySegment
                /* endif */);
    }

    /**
     * This operation is in the spirit of `hasmore()`: see
     * https://graphics.stanford.edu/~seander/bithacks.html#HasMoreInWord, except that the `| x`
     * step is omitted because it's needed for the cases when the unsigned values in bytes can
     * be more than 127, while outbound overflow counts can't be more than {@link
     * SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY} - {@link HashTable#GROUP_SLOTS} = 40.
     */
    @AmortizedPerSegment
    private static long matchChanges(long outboundOverflowCount_perGroupChanges) {
        // Add 127 to every byte so that everything except zeros overflows to the most
        // significant bit.
        return (outboundOverflowCount_perGroupChanges + ~MOST_SIGNIFICANT_BYTE_BITS)
                & MOST_SIGNIFICANT_BYTE_BITS;
    }

    private static void checkOutboundOverflowCounts(Object segment
            /* if Interleaved segments Supported intermediateSegments */, int isFullCapacitySegment
            /* endif */) {
        long outboundOverflowCountsPerGroup = getOutboundOverflowCountsPerGroup(segment);
        for (long groupIndex = 0; groupIndex < HASH_TABLE_GROUPS; groupIndex++) {
            byte outboundOverflowCount = (byte) outboundOverflowCountsPerGroup;
            boolean outboundOverflowCountIsPositive = (int) outboundOverflowCount != 0;
            long dataGroupOffset = dataGroupOffset(groupIndex
                    /* if Interleaved segments Supported intermediateSegments */
                    , (long) isFullCapacitySegment/* endif */);
            long dataGroup = readDataGroupAtOffset(segment, dataGroupOffset);
            boolean dataGroupHasOutboundOverflowMask = !shouldStopProbing(dataGroup);
            if (outboundOverflowCountIsPositive ^ dataGroupHasOutboundOverflowMask) {
                throw new IllegalStateException();
            }
            outboundOverflowCountsPerGroup >>>= Byte.SIZE;
        }
    }

    private OutboundOverflowCounts() {}
}
