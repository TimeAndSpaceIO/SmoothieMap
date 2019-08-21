package io.timeandspace.smoothie;

import io.timeandspace.smoothie.SmoothieMap.InflatedSegment;
import org.checkerframework.common.value.qual.IntVal;

import java.nio.ByteOrder;

import static io.timeandspace.smoothie.Utils.BYTE_SIZE_DIVISION_SHIFT;
import static io.timeandspace.smoothie.Utils.verifyEqual;
import static java.lang.Integer.numberOfTrailingZeros;

/**
 * HashTable class contains a set of static operations to implement an open-addressing hash table of
 * 8 ({@link #HASH_TABLE_GROUPS}) "groups", each the group having 8 ({@link #GROUP_SLOTS}) (logical)
 * "slots".
 *
 * Each slot can be in a "full" or an "empty" state.
 *
 * Each group consists of two subgroups (which are called simply "groups" in the code, as well as
 * their containing group): tagGroup and dataGroup, both are 64-bit long values (alternatively, they
 * can be seen as small arrays of 8 bytes). In both tagGroups and dataGroups, each byte corresponds
 * to a hash table' slot.
 *
 * In an tagGroup, a slot's byte stores 8 bits from some key's hash code if the slot is full, or
 * arbitrary garbage otherwise.
 *
 * In a dataGroup, each of the 8 bytes corresponds to one slot. A byte with one in the highest bit
 * indicates a full slot. A full slot stores an allocation index (in the allocation space of the
 * segment which includes the hash table) in its lower 6 bits. A byte with zero in the highest bit
 * indicates an empty slot.
 *
 * 6th bits in slots' bytes in a dataGroup are either all set to zero or to one (which corresponds
 * to {@link #DATA_GROUP__OUTBOUND_OVERFLOW_MASK}). If they are set to ones then the outbound
 * overflow count for the group is non-zero. See {@link
 * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}.
 *
 * HashTable is inspired by and borrows some logic from SwissTable, specifically this version:
 * https://github.com/abseil/abseil-cpp/blob/3088e76c5/absl/container/internal/raw_hash_set.h,
 * hashbrown: https://github.com/Amanieu/hashbrown/tree/6b9cc4e01/src, and F14:
 * https://github.com/facebook/folly/blob/436efbfed/folly/container/detail/F14Table.h  Naming of
 * variables and methods is also partially borrowed from these sources.
 */
final class HashTable {

    static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    static final int HASH_TABLE_SLOTS = 64;
    static final int HASH_TABLE_SLOTS_MASK = HASH_TABLE_SLOTS - 1;
    static final int GROUP_SLOTS = Long.BYTES;
    static final int GROUP_SLOTS_MASK = GROUP_SLOTS - 1;
    /**
     * = numberOfTrailingZeros({@link #GROUP_SLOTS})
     * [Replacing division with shift]
     */
    @CompileTimeConstant
    static final int GROUP_SLOTS_DIVISION_SHIFT = 3;
    @IntVal(8)
    static final int HASH_TABLE_GROUPS = HASH_TABLE_SLOTS / GROUP_SLOTS;
    static final int HASH_TABLE_GROUPS_MASK = HASH_TABLE_GROUPS - 1;

    static {
        verifyEqual(GROUP_SLOTS_DIVISION_SHIFT, numberOfTrailingZeros(GROUP_SLOTS));
        verifyEqual(HASH_TABLE_SLOTS, HASH_TABLE_GROUPS * GROUP_SLOTS);
        // The layout and the treatment of
        // ContinuousSegment_BitSetAndStateArea.outboundOverflowCountsPerGroup as a single long
        // value is dependent on this condition.
        verifyEqual(HASH_TABLE_GROUPS, Long.BYTES);
    }

    static long baseGroupIndex(long hash) {
        return hash & HASH_TABLE_GROUPS_MASK;
    }

    static long addGroupIndex(long groupIndex, long addition) {
        return (groupIndex + addition) & HASH_TABLE_GROUPS_MASK;
    }

    static void assertValidGroupIndex(long groupIndex) {
        assert (groupIndex & HASH_TABLE_GROUPS_MASK) == groupIndex : "groupIndex=" + groupIndex;
    }

    static void assertValidSlotIndexWithinGroup(int slotIndexWithinGroup) {
        assert (slotIndexWithinGroup & GROUP_SLOTS_MASK) == slotIndexWithinGroup :
                "slotIndexWithinGroup=" + slotIndexWithinGroup;
    }

    static long slotByteOffset(int slotIndexWithinGroup) {
        if (IS_LITTLE_ENDIAN) {
            // TODO [check if unsigned conversion produces shorter assembly]
            return (long) slotIndexWithinGroup;
        } else {
            // TODO [check if unsigned conversion produces shorter assembly]
            return Long.BYTES - 1 - (long) slotIndexWithinGroup;
        }
    }

    private static final int DATA__ALLOC_INDEX_BITS = 6;
    private static final int DATA__ALLOC_INDEX_MASK = (1 << DATA__ALLOC_INDEX_BITS) - 1; // = 63

    private static final int DATA__OUTBOUND_OVERFLOW_BIT = 1 << DATA__ALLOC_INDEX_BITS;
    private static final int DATA__FULLNESS_BIT = DATA__OUTBOUND_OVERFLOW_BIT << 1;

    /** {@link ContinuousSegments#allocateNewSegmentWithoutSettingBitSetAndSet} and */
    static final long EMPTY_DATA_GROUP = 0L;

    /**
     * Values of these two constants correspond to {@link
     * ContinuousSegment_BitSetAndStateArea#MOST_SIGNIFICANT_BYTE_BITS} and
     * {@link ContinuousSegment_BitSetAndStateArea#LEAST_SIGNIFICANT_BYTE_BITS} but semantics are
     * independent and the values of these constant are subject for change (because slot size might
     * also be 7 - see the class-level comment for {@link HashTable}) so these pairs of constants
     * are kept separate.
     */
    private static final long MOST_SIGNIFICANT_SLOT_BITS = 0x8080808080808080L;
    private static final long LEAST_SIGNIFICANT_SLOT_BITS = 0x0101010101010101L;

    static {
        // It needs to be so for matchFull() to work (see the comment for this method).
        verifyEqual(MOST_SIGNIFICANT_SLOT_BITS, fullDataGroupForTesting());
        verifyEqual(DATA__ALLOC_INDEX_MASK, 63);
    }

    static final long DATA_GROUP__OUTBOUND_OVERFLOW_MASK =
            DATA__OUTBOUND_OVERFLOW_BIT * LEAST_SIGNIFICANT_SLOT_BITS;

    /**
     * In {@link InflatedSegment}'s pseudo hash table, all slots should be empty (hence not setting
     * {@link #DATA__FULLNESS_BIT}), but the outbound overflow bits should be set to reach the
     * [InflatedSegment checking after unsuccessful key search] branch. Additionally, each marker
     * inflated segment's data "stores" {@link #DATA__ALLOC_INDEX_MASK} as alloc index, which is an
     * impossible alloc index because {@link #DATA__ALLOC_INDEX_MASK} = 63 is greater than {@link
     * SmoothieMap#SEGMENT_MAX_ALLOC_CAPACITY} = 48. This is needed to distinguish between the
     * inflated segment's marker data and an ordinary data group with {@link
     * #DATA_GROUP__OUTBOUND_OVERFLOW_MASK} set (see {@link
     * ContinuousSegment_BitSetAndStateArea#outboundOverflowCountsPerGroup}) but all slots are empty
     * because all entries which were stored in the group have been deleted.
     */
    private static final int INFLATED_SEGMENT_MARKER_DATA =
            DATA__OUTBOUND_OVERFLOW_BIT | DATA__ALLOC_INDEX_MASK;

    /**
     * The same value as {@link #DATA_GROUP__OUTBOUND_OVERFLOW_MASK}, but semantically they are
     * different hence there are two separate constants.
     */
    static final long INFLATED_SEGMENT__MARKER_DATA_GROUP =
            INFLATED_SEGMENT_MARKER_DATA * LEAST_SIGNIFICANT_SLOT_BITS;

    /**
     * For the technique, see:
     * http://graphics.stanford.edu/~seander/bithacks.html##ValueInWord
     * (Determine if a word has a byte equal to n).
     *
     * Caveat: there are false positives but:
     * - they only occur if there is a real match
     * - they will be immediately filtered on empty slots via `& matchFull(dataGroup)`
     * - they will be handled gracefully by subsequent checks in code
     *
     * Specifically, a false positive happens when there is a matching byte and the result of the
     * first xor on the next higher byte is 0000_0001. That one bit gets carried away by the
     * ` - LEAST_SIGNIFICANT_SLOT_BITS`, when the previous byte is all-zero, i. e. a match.
     *
     * Example:
     *   tagGroup = 0x1716151413121110
     *   tagBitsToMatch = 0x12
     *   xor result
     *     = 0000_0101__0000_0100__0000_0111__0000_0110__0000_0001__0000_0000__0000_0011__0000_0010
     *   (x - LEAST_SIGNIFICANT_SLOT_BITS) & (~x & MOST_SIGNIFICANT_SLOT_BITS)
     *     = 0x0000000080800000
     *
     * This method is adapted from https://github.com/abseil/abseil-cpp/blob/
     * 3088e76c597e068479e82508b1770a7ad0c806b6/absl/container/internal/raw_hash_set.h#L428-L446.
     * In Abseil, the `(~x & MOST_SIGNIFICANT_SLOT_BITS)` part of the haszero() algorithm (see
     * http://graphics.stanford.edu/~seander/bithacks.html#ZeroInWord) is omitted because there are
     * just 7 control bits (vs. 8 tag bits in SmoothieMap) so the highest bit of `x` is always zero
     * anyway.
     */
    static long match(long tagGroup, long tagBitsToMatch, long dataGroup) {
        long x = tagGroup ^ (LEAST_SIGNIFICANT_SLOT_BITS * tagBitsToMatch);
        // Similar to ContinuousSegment_BitSetAndStateArea.incrementOutboundOverflowCountsPerGroup()
        // and decrementOutboundOverflowCountsPerGroup().
        long matchZeroSlots = (x - LEAST_SIGNIFICANT_SLOT_BITS) & (~x & MOST_SIGNIFICANT_SLOT_BITS);
        return matchZeroSlots & matchFull(dataGroup);
    }

    static boolean shouldStopProbing(long dataGroup) {
        return (dataGroup & DATA_GROUP__OUTBOUND_OVERFLOW_MASK) == 0;
    }

    static long copyOutboundOverflowBits(long fromDataGroup, long toDataGroup) {
        return toDataGroup | (fromDataGroup & DATA_GROUP__OUTBOUND_OVERFLOW_MASK);
    }

    static void verifyOutboundOverflowBitsZero(long dataGroup) {
        verifyEqual(dataGroup & DATA_GROUP__OUTBOUND_OVERFLOW_MASK, 0L);
    }

    static long matchEmpty(long dataGroup) {
        // Matches when the highest bit in a data byte is zero.
        return (~dataGroup) & MOST_SIGNIFICANT_SLOT_BITS;
    }

    /**
     * @implNote `dataGroup & {@link #MOST_SIGNIFICANT_SLOT_BITS}` works as long as {@link
     * #DATA__FULLNESS_BIT} _is_ the most significant slot bit.
     */
    static long matchFull(long dataGroup) {
        return dataGroup & MOST_SIGNIFICANT_SLOT_BITS;
    }

    static int lowestMatchingSlotIndex(long matchBitMask) {
        // [Replacing division with shift]
        return Long.numberOfTrailingZeros(matchBitMask) >>> BYTE_SIZE_DIVISION_SHIFT;
    }

    /**
     * The second part of inlined {@link #lowestMatchingSlotIndex} method, see
     * [Inlined lowestMatchingSlotIndex].
     */
    static int lowestMatchingSlotIndexFromTrailingZeros(int bitMaskTrailingZeros) {
        // [Replacing division with shift]
        return bitMaskTrailingZeros >>> BYTE_SIZE_DIVISION_SHIFT;
    }

    static long firstAllocIndex(long dataGroup, long matchBitMask) {
        return extractAllocIndex(dataGroup, Long.numberOfTrailingZeros(matchBitMask));
    }

    /**
     * @param bitMaskTrailingZeros the number of trailing zeros in a result of any matching method,
     *        like {@link #match}, {@link #matchFull}, etc. I. e. this argument must be equal
     *        to Long.numberOfTrailingZeros(match*(...)).
     *
     * @implNote it would be a little cleaner and less error-prone if the type of allocIndex (the
     * return value of this method) was int rather than long (which adds the risk of confusing
     * allocIndex with address offsets (such as {@link
     * InterleavedSegments.FullCapacitySegment#allocOffset}) in the call sites, but casting down to
     * integer and then eventually back to long when translating to an address adds unnecessary
     * bytecodes and assembly instructions.
     */
    static long extractAllocIndex(long dataGroup, int bitMaskTrailingZeros) {
        return (dataGroup >>> (bitMaskTrailingZeros - (Byte.SIZE - 1))) & DATA__ALLOC_INDEX_MASK;
    }

    /**
     * @param bitMaskTrailingZeros the number of trailing zeros in a result of any matching method,
     *        like {@link #match}, {@link #matchFull}, etc. I. e. this argument must be equal
     *        to Long.numberOfTrailingZeros(match*(...)).
     */
    static byte extractDataByte(long dataGroup, int bitMaskTrailingZeros) {
        return (byte) (dataGroup >>> (bitMaskTrailingZeros - (Byte.SIZE - 1)));
    }

    /**
     * @param bitMaskTrailingZeros the number of trailing zeros in a result of any matching method,
     *        like {@link #match}, {@link #matchFull}, etc. I. e. this argument must be equal
     *        to Long.numberOfTrailingZeros(match*(...)).
     */
    static byte extractTagByte(long tagGroup, int bitMaskTrailingZeros) {
        return (byte) (tagGroup >>> (bitMaskTrailingZeros - (Byte.SIZE - 1)));
    }

    /**
     * Returns a data group derived from the given dataGroup with the slot corresponding to
     * bitMaskTrailingZeros set to the empty state.
     *
     * @param bitMaskTrailingZeros the number of trailing zeros in a result of any matching method,
     *        like {@link #match} and {@link #matchFull}. I. e. this argument must be equal to
     *        Long.numberOfTrailingZeros(match*(...)).
     */
    static long setSlotEmpty(long dataGroup, int bitMaskTrailingZeros) {
        return dataGroup & ~(1L << bitMaskTrailingZeros);
    }

    /**
     * This method preserves the {@link #DATA__OUTBOUND_OVERFLOW_BIT} of the given data.
     * @param dirtyData an int value containing the data byte in its lower 8 bits. Higher bits may
     *        contain garbage.
     */
    static byte changeAllocIndexInData(int dirtyData, int newAllocIndex) {
        return (byte) ((dirtyData & ~DATA__ALLOC_INDEX_MASK) | newAllocIndex);
    }

    /**
     * @param dirtyData an int value containing the data byte in its lower 8 bits. Higher bits may
     *        contain garbage.
     */
    static int allocIndex(int dirtyData) {
        return dirtyData & DATA__ALLOC_INDEX_MASK;
    }

    static byte makeData(long dataGroup, int allocIndex) {
        return (byte) (allocIndex | DATA__FULLNESS_BIT |
                // Copy DATA__OUTBOUND_OVERFLOW_BIT from the first slot in the group.
                ((int) dataGroup & DATA__OUTBOUND_OVERFLOW_BIT));
    }

    static byte makeDataWithZeroOutboundOverflowBit(int allocIndex) {
        return (byte) (allocIndex | DATA__FULLNESS_BIT);
    }

    /**
     * Having specialized makeData for int and long allocIndex may result in better assembly
     * produced by the JIT compiler than just one method with extra casts needed in some cases.
     */
    static byte makeData(long dataGroup, long allocIndex) {
        return (byte) (allocIndex | DATA__FULLNESS_BIT |
                // Copy DATA__OUTBOUND_OVERFLOW_BIT from the first slot in the group.
                (dataGroup & DATA__OUTBOUND_OVERFLOW_BIT));
    }

    @VisibleForTesting
    @SuppressWarnings({"NumericOverflow", "ConstantOverflow"})
    static long fullDataGroupForTesting() {
        return DATA__FULLNESS_BIT * LEAST_SIGNIFICANT_SLOT_BITS;
    }

    private HashTable() {}
}
