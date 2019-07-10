package io.timeandspace.smoothie;

import java.util.ConcurrentModificationException;

import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_INTERMEDIATE_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.Utils.verifyEqual;
import static io.timeandspace.smoothie.Utils.verifyThat;

/**
 * This class is a collection of static utility methods for working with {@link
 * io.timeandspace.smoothie.ContinuousSegment_BitSetAndStateArea#bitSetAndState}.
 *
 * The lower 48 bits ({@link #BIT_SET_BITS}; equal to {@link
 * SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY}) of a bitSetAndState is a bit set denoting free and
 * occupied allocation indexes. In intermediate-capacity segments, the higher of these bits are just
 * not used and always appear as free allocation indexes.
 *
 * The next 5 bits (from 48th to 52nd) represent the segment order. See {@link
 * SmoothieMap#lastComputedAverageSegmentOrder} and {@link SmoothieMap#order} for more info about
 * this concept.
 *
 * The next 5 bits (from 53rd to 57th) are used to codify the allocation capacity of the segment
 * when {@link ContinuousSegments} rather than {@link InterleavedSegments} are used. The value of 0
 * in these bits (i. e, all bits from 59th to 63rd are zeros) is assigned for {@link
 * ContinuousSegments.Segment17}, 1 - for {@link ContinuousSegments.Segment18}, etc. through 31 for
 * {@link ContinuousSegments.Segment48}.
 *
 * The final 6 bits (from 58th to 63rd) are used to hold special values to identify ordinary
 * segments (value 0), {@link SmoothieMap.InflatedSegment}s ({@link
 * #INFLATED_SEGMENT_SPECIAL_VALUE}), and segments in bulk operation (value 1; see {@link
 * #MIN_BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE}). This choice of special values allows
 * single-operation {@link #isInflatedBitSetAndState} and {@link
 * #isBulkOperationPlaceholderBitSetAndState}.
 *
 * All four values are packed into a single long rather than having their own fields in order to
 * save memory. The saving is 8 bytes if UseCompressedOops is false and
 * UseCompressedClassPointers is false, because ObjectAlignmentInBytes is at least 8 bytes, the
 * object header size is 16 bytes, and all other fields and logical units (such as allocation
 * indexes - key-value pairs) in segments (either {@link ContinuousSegments} or
 * {@link InterleavedSegments}) also take from 8 to 16 bytes. In addition, storing four values in
 * a single long field allows to reduce the number of memory reads and writes required during
 * insert and delete operations, when several of those values are read and/or updated.
 */
final class BitSetAndState {

    private static final int BIT_SET_BITS = SEGMENT_MAX_ALLOC_CAPACITY;
    private static final long BIT_SET_MASK = (1L << BIT_SET_BITS) - 1;

    static {
        // Rest of the logic of BitSetAndState depends on the exact value of BIT_SET_BITS and, thus,
        // SEGMENT_MAX_ALLOC_CAPACITY.
        verifyEqual(BIT_SET_BITS, 48);
    }

    /**
     * Free allocation slots correspond to set bits, and vice versa, occupied slots correspond to
     * clear bits in a bitSetAndState. This is done to avoid extra bitwise inversions for the
     * typical operation of finding the lowest free slot, because there are only {@link
     * Long#numberOfTrailingZeros(long)} and {@link Long#numberOfLeadingZeros(long)} methods in
     * {@code Long} class, no methods for leading/trailing "ones".
     */
    static final long EMPTY_BIT_SET = BIT_SET_MASK;

    private static final int SEGMENT_ORDER_SHIFT = BIT_SET_BITS;
    static final long SEGMENT_ORDER_UNIT = 1L << SEGMENT_ORDER_SHIFT;
    /**
     * 5 bits is enough to store values from 0 to {@link SmoothieMap#MAX_SEGMENTS_ARRAY_ORDER} = 30.
     */
    private static final int SEGMENT_ORDER_BITS = 5;
    private static final int SEGMENT_ORDER_MASK = (1 << SEGMENT_ORDER_BITS) - 1;

    /**
     * There are not enough bits to store the alloc capacity directly, because it requires 6 bits
     * (numbers up to {@link SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY}). Therefore it's not possible
     * to create a segment of any alloc capacity between 0 and {@link
     * SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY}. The minimum supported alloc capacity is {@link
     * #BASE_ALLOC_CAPACITY}.
     */
    private static final int EXTRA_ALLOC_CAPACITY_SHIFT =
            SEGMENT_ORDER_SHIFT + SEGMENT_ORDER_BITS;
    private static final int EXTRA_ALLOC_CAPACITY_BITS = 5;
    private static final int EXTRA_ALLOC_CAPACITY_MASK = (1 << EXTRA_ALLOC_CAPACITY_BITS) - 1;
    private static final int MAX_EXTRA_ALLOC_CAPACITY = (1 << EXTRA_ALLOC_CAPACITY_BITS) - 1;
    /**
     * Equals to 17.
     * Warning: {@link #allocCapacityMinusOneHalved} relies on this value to be odd. {@link
     * SmoothieMap.Segment#tryShrink1} relies on this value to be greater than 2.
     */
    private static final int BASE_ALLOC_CAPACITY =
            SEGMENT_MAX_ALLOC_CAPACITY - MAX_EXTRA_ALLOC_CAPACITY;

    private static final int SPECIAL_VALUE_SHIFT =
            EXTRA_ALLOC_CAPACITY_SHIFT + EXTRA_ALLOC_CAPACITY_BITS;
    private static final int SPECIAL_VALUE_BITS = 6;
    private static final int SPECIAL_VALUE_MASK = (1 << SPECIAL_VALUE_BITS) - 1;

    /**
     * A special value for {@link SmoothieMap.InflatedSegment} to make it possible to identify it
     * before checking the class of a segment object.
     *
     * Implementation of {@link #isInflatedBitSetAndState} depends on the fact that this value is
     * equal to 0b100000, so that all inflated segment's bitSetAndStates are negative and the state
     * of an inflated segment with order 0 is equal to {@link Long#MIN_VALUE}.
     *
     * @see #makeInflatedBitSetAndState
     */
    private static final int INFLATED_SEGMENT_SPECIAL_VALUE = 1 << (SPECIAL_VALUE_BITS - 1);

    /**
     * This value is OR-ed with bitSetAndStates of segments in the beginning of bulk operations
     * (like {@link SmoothieMap.Segment#tryShrink2} and {@link SmoothieMap#splitAndInsert})) so that
     * concurrent operations could catch this condition and throw a {@link
     * ConcurrentModificationException} more likely.
     */
    private static final long MIN_BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE =
            1L << SPECIAL_VALUE_SHIFT;

    static {
        // Checking that all bitSetAndState's areas are set up correctly
        long fullBitSetAndState = BIT_SET_MASK ^
                ((long) SEGMENT_ORDER_MASK << SEGMENT_ORDER_SHIFT) ^
                ((long) EXTRA_ALLOC_CAPACITY_MASK << EXTRA_ALLOC_CAPACITY_SHIFT) ^
                ((long) SPECIAL_VALUE_MASK << SPECIAL_VALUE_SHIFT);
        verifyEqual(fullBitSetAndState, -1L);
        verifyEqual(BASE_ALLOC_CAPACITY, 17);
    }

    static long makeNewBitSetAndState(int allocCapacity, int segmentOrder) {
        int extraAllocCapacity = allocCapacity - BASE_ALLOC_CAPACITY;
        return EMPTY_BIT_SET |
                (((long) segmentOrder) << SEGMENT_ORDER_SHIFT) |
                (((long) extraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
    }

    static long clearBitSet(long bitSetAndState) {
        return bitSetAndState | EMPTY_BIT_SET;
    }

    /**
     * A privately populated segment has the first {@code segmentSize} contiguous allocation slots
     * occupied.
     */
    static long makeBitSetAndStateForPrivatelyPopulatedContinuousSegment(
            int allocCapacity, int segmentOrder, int segmentSize) {
        long bitSet = ((EMPTY_BIT_SET << segmentSize) & EMPTY_BIT_SET);
        int extraAllocCapacity = allocCapacity - BASE_ALLOC_CAPACITY;
        return bitSet |
                (((long) segmentOrder) << SEGMENT_ORDER_SHIFT) |
                (((long) extraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
    }

    static long incrementSegmentOrder(long bitSetAndState) {
        bitSetAndState += SEGMENT_ORDER_UNIT;
        /* if Enabled extraChecks */
        verifyThat(segmentOrder(bitSetAndState) <= SmoothieMap.MAX_SEGMENTS_ARRAY_ORDER);
        /* endif */
        return bitSetAndState;
    }

    static long makeBitSetAndStateWithNewAllocCapacity(
            long bitSetAndState, int newAllocCapacity) {
        long negativeExtraAllocCapacityMask =
                ~(((long) EXTRA_ALLOC_CAPACITY_MASK) << EXTRA_ALLOC_CAPACITY_SHIFT);
        int newExtraAllocCapacity = newAllocCapacity - BASE_ALLOC_CAPACITY;
        return (bitSetAndState & negativeExtraAllocCapacityMask) |
                (((long) newExtraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT);
    }

    /**
     * Returns a bitSetAndState that includes a full bit set, an extra alloc capacity of 0 for
     * {@link ContinuousSegments} and {@link SmoothieMap#SEGMENT_INTERMEDIATE_ALLOC_CAPACITY} -
     * {@link #BASE_ALLOC_CAPACITY} for {@link InterleavedSegments}, the specified segment order,
     * and a special value {@link #INFLATED_SEGMENT_SPECIAL_VALUE}.
     */
    static long makeInflatedBitSetAndState(int segmentOrder) {
        /* if Interleaved segments */
        int extraAllocCapacity = SEGMENT_INTERMEDIATE_ALLOC_CAPACITY - BASE_ALLOC_CAPACITY;
        /* endif */
        return (((long) INFLATED_SEGMENT_SPECIAL_VALUE) << SPECIAL_VALUE_SHIFT) |
                /* if Interleaved segments */
                (((long) extraAllocCapacity) << EXTRA_ALLOC_CAPACITY_SHIFT) |
                /* endif */
                (((long) segmentOrder) << SEGMENT_ORDER_SHIFT);
    }

    /**
     * Bulk operation placeholder includes a full bit set (see {@link #EMPTY_BIT_SET}), a special
     * value 1. Any entry insertion into the segment triggers {@link SmoothieMap#makeSpaceAndInsert}
     * if encounters this bitSetAndState. Bulk operation placeholder bitSetAndState is identified
     * there and then a {@link ConcurrentModificationException} is thrown.
     */
    static long makeBulkOperationPlaceholderBitSetAndState(long bitSetAndState) {
        return (bitSetAndState | MIN_BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE) &
                (~EMPTY_BIT_SET);
    }

    static boolean isInflatedBitSetAndState(long bitSetAndState) {
        long inflatedSegment_maxBitSetAndState = ((1L << SPECIAL_VALUE_SHIFT) - 1) |
                ((long) INFLATED_SEGMENT_SPECIAL_VALUE) << SPECIAL_VALUE_SHIFT;
        // This kind of comparison is possible because of INFLATED_SEGMENT_SPECIAL_VALUE which is
        // 0b100000.
        return bitSetAndState <= inflatedSegment_maxBitSetAndState;
    }

    static boolean isBulkOperationPlaceholderBitSetAndState(long bitSetAndState) {
        return bitSetAndState >= MIN_BULK_OPERATION_PLACEHOLDER_BIT_SET_AND_STATE;
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
     * This method might return a value outside of the allowed range if the segment is already
     * full. The result must be checked for being less than {@link #allocCapacity}.
     */
    static int lowestFreeAllocIndex(long bitSetAndState) {
        // TODO consider organizing bitSetAndState so that LZCNT rather than TZCNT is used because
        //  it's cheaper on AMD CPUs up to Ryzen.
        // TODO in a SmoothieMap's specialization without support for entry removals, consider
        //  optimizing this as bitCount(extractBitSetForIteration()) to employ POPCNT instruction
        //  which is available for Intel Nehalem through Ivy Bridge, while TZCNT/LZCNT is available
        //  only starting from Intel Haswell. However, note that POPCNT is more exprensive that
        //  LZCNT/TZCNT on pre-Ryzen AMD's architectures.
        return Long.numberOfTrailingZeros(bitSetAndState);
    }

    /**
     * @param allocIndexBoundary must be positive and less than {@link #allocCapacity} called on
     *        the same bitSetAndState
     * @return a free alloc index closest to the specified boundary (e. g. 3 is a boundary between
     *         alloc indexes 2 and 3), or a value equal to or greater than {@link #allocCapacity}
     *         if the segment is full.
     * @see InterleavedSegments#allocIndexBoundaryForLocalAllocation
     *
     * @implNote TODO document pass allocCapacity vs. compute it from bitSetAndState tradeoff.
     */
    static int freeAllocIndexClosestTo(long bitSetAndState, int allocIndexBoundary
            /* if Supported intermediateSegments */, int allocCapacity/* endif */) {

        /* if NotSupported intermediateSegments */
        long bitSetMask = BIT_SET_MASK;
        /* elif Supported intermediateSegments */
        long bitSetMask = BIT_SET_MASK >>> (SEGMENT_MAX_ALLOC_CAPACITY - allocCapacity);
        /* endif */
        // If there are no free alloc indexes beyond allocIndexBoundary
        // distanceToClosestNextFreeAllocIndex must be at least BIT_SET_BITS so that it will be
        // greater than distanceToClosestPrevFreeAllocIndex if the latter points to a free alloc
        // index and, therefore, closestPrevFreeAllocIndex is "chosen" in the code below. (If there
        // are no free alloc indexes before allocIndexBoundary, too, then
        // distanceToClosestPrevFreeAllocIndex will be 64.) The simplest way to achieve that is to
        // mask the bit set: `bitSetAndState & BIT_SET_MASK`, which makes
        // distanceToClosestNextFreeAllocIndex computed to 64 if there are no free alloc indexes
        // beyond allocIndexBoundary.
        int distanceToClosestNextFreeAllocIndex =
                Long.numberOfTrailingZeros((bitSetAndState & bitSetMask) >>> allocIndexBoundary);
        // If all alloc indexes before the boundary are occupied (that is, the corresponding bits
        // are zero), the left shift just extends the bitSetAndState's values with zeros so that it
        // is all zeros and numberOfLeadingZeros() returns 64. distanceToClosestNextFreeAllocIndex
        // cannot be less than that distanceToClosestPrevFreeAllocIndex. Therefore, the next
        // closest free alloc index (perhaps, beyond allocCapacity) will be returned from this
        // method because the logic below is organized to choose the next rather than the previous
        // index if the distances from the boundary are equal. Therefore, this method never returns
        // negative result.
        int distanceToClosestPrevFreeAllocIndex =
                // `<< -allocIndexBoundary` doesn't need to consider allocIndexBoundary = 0 because
                // the method's contract requires allocIndexBoundary to be positive.
                Long.numberOfLeadingZeros(bitSetAndState << -allocIndexBoundary);

        // The following code is a branchless version of
        //
        // if (distanceToClosestNextFreeAllocIndex <= distanceToClosestPrevFreeAllocIndex) {
        //     return allocIndexBoundary + distanceToClosestNextFreeAllocIndex;
        // } else {
        //     return allocIndexBoundary - 1 - distanceToClosestPrevFreeAllocIndex;
        // }
        //
        // It's important that if distanceToClosestNextFreeAllocIndex and
        // distanceToClosestPrevFreeAllocIndex are equal then the preference is given to
        // `allocIndexBoundary + distanceToClosestNextFreeAllocIndex` because it covers the edge
        // case of a full bit set: distanceToClosestNextFreeAllocIndex will be 64 (see the
        // definition of this variable) and distanceToClosestPrevFreeAllocIndex will be 64 too. We
        // have to return `allocIndexBoundary + distanceToClosestNextFreeAllocIndex` to comply with
        // the specification of this method which states that this method should return an
        // impossible positive (rather than negative) value if the bit set is full.

        // This value contains all zeros if distanceToClosestPrevFreeAllocIndex <
        // distanceToClosestNextFreeAllocIndex, all ones otherwise.
        int distanceToPrevIsLessThanDistanceToPrev = signExtend(
                distanceToClosestPrevFreeAllocIndex - distanceToClosestNextFreeAllocIndex);

        int closestNextFreeAllocIndex = allocIndexBoundary + distanceToClosestNextFreeAllocIndex;
        int closestPrevFreeAllocIndex =
                allocIndexBoundary - 1 - distanceToClosestPrevFreeAllocIndex;

        // Hopefully, JIT will simplify this expression and avoid computing
        // closestPrevFreeAllocIndex altogether.
        return closestNextFreeAllocIndex + ((closestPrevFreeAllocIndex - closestNextFreeAllocIndex)
                & distanceToPrevIsLessThanDistanceToPrev);
    }

    private static int signExtend(int v) {
        return v >> (Integer.SIZE - 1);
    }

    static long clearAllocBit(long bitSetAndState, long allocIndex) {
        return bitSetAndState | (1L << allocIndex);
    }

    static long setAllocBit(long bitSetAndState, int allocIndex) {
        return bitSetAndState & ~(1L << allocIndex);
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

    /* if Interleaved segments Supported intermediateSegments */
    /**
     * Works consistently for ordinary and inflated segments given that {@link
     * SmoothieMap.InflatedSegment} is *not* a full-capacity segment (it extends {@link
     * InterleavedSegments.IntermediateCapacitySegment}) and {@link #makeInflatedBitSetAndState}
     * returns a bitSetAndState with the corresponding extra alloc capacity.
     */
    static boolean isFullCapacity(long bitSetAndState) {
        long fullAllocCapacityMask = ((long) SEGMENT_MAX_ALLOC_CAPACITY - BASE_ALLOC_CAPACITY) <<
                EXTRA_ALLOC_CAPACITY_SHIFT;
        return (bitSetAndState & fullAllocCapacityMask) == fullAllocCapacityMask;
    }
    /* endif */

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

    /** A structured view on a bitSetAndState for debugging. */
    static class DebugBitSetAndState {
        enum Type {
            ORDINARY, INFLATED, BULK_OPERATION_PLACEHOLDER, UNKNOWN;

            static Type fromSpecialValue(int specialValue) {
                switch (specialValue) {
                    case 0: return ORDINARY;
                    case 1: return BULK_OPERATION_PLACEHOLDER;
                    case INFLATED_SEGMENT_SPECIAL_VALUE: return INFLATED;
                    default: return UNKNOWN;
                }
            }
        }

        final Type type;
        final long bitSet;
        final int size;
        final int order;
        final int allocCapacity;

        DebugBitSetAndState(long bitSetAndState) {
            type = Type.fromSpecialValue((int) (bitSetAndState >>> SPECIAL_VALUE_SHIFT));
            bitSet = extractBitSetForIteration(bitSetAndState);
            size = Long.bitCount(bitSet);
            order = segmentOrder(bitSetAndState);
            allocCapacity = allocCapacity(bitSetAndState);
        }
    }

    private BitSetAndState() {}
}
