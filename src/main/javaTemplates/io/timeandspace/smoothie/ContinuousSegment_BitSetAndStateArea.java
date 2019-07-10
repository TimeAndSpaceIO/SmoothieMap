/* with Continuous|Interleaved segments */
package io.timeandspace.smoothie;

import java.util.ConcurrentModificationException;

import static io.timeandspace.smoothie.BitSetAndState.isBulkOperationPlaceholderBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.makeBulkOperationPlaceholderBitSetAndState;
import static io.timeandspace.smoothie.UnsafeUtils.U;
/* if Continuous segments */
/* elif Interleaved segments //
import static io.timeandspace.smoothie.InterleavedSegments.FullCapacitySegment.readDataGroup;
import static io.timeandspace.smoothie.InterleavedSegments.FullCapacitySegment.writeDataGroup;
// endif */

abstract class ContinuousSegment_BitSetAndStateArea<K, V>
        /* if Continuous segments //extends ContinuousSegments.HashTableArea<K, V>
        // elif Interleaved segments */
        extends AbstractSegment<K, V>
        /* endif */{

    /** @see BitSetAndState */
    long bitSetAndState;

    /**
     * Each byte of this long value holds an "outbound overflow count" for a {@link HashTable}'s
     * group with the same index. Outbound overflow count is the number of entries that would have
     * been placed in in a group unless it was full (all group's {@link HashTable#GROUP_SLOTS} are
     * full) when the entry was inserted into the table.
     *
     * In F14, the outbound overflow counts are kept right within hash table's "chunks" (equivalent
     * of {@link HashTable}'s groups in SmoothieMap):
     * https://github.com/facebook/folly/blob/436efbfed/folly/container/detail/F14Table.h#L360-L364
     *
     * In SmoothieMap, the outbound overflow counts are kept separately from the groups in
     * BitSetAndStateArea for the following reasons:
     *
     *  - It allows to avoid repetitive probing of the hash table's groups during all modification
     *  operations because in all of them it's unknown whether the entry will be actually inserted,
     *  or deleted, or no structural modification will happen to the hash table until the end of the
     *  search. (Note: this is currently implemented only in operations like {@link
     *  SmoothieMap#removeImpl}, but not {@link SmoothieMap#putImpl}: see [Find empty slot] in the
     *  latter. But when SmoothieMap is specialized for no-removals case, this can also be
     *  implemented for insertions.)
     *
     *  - When a modification operation does require a structural modification of the hash table
     *  (that is, an entry is inserted or deleted), separate outbound overflow counts possibly
     *  reduce memory write amplification because the cache lines containing the hash table's groups
     *  are not updated when outbound overflow counts are changed between 1 and 2, 2 and 3, etc.
     *  The dataGroups are only changed when the count changes between 0 and 1: {@link
     *  HashTable#DATA__OUTBOUND_OVERFLOW_BIT} should be set or unset in all slots in a group (see
     *  {@link OutboundOverflowCounts#incrementOutboundOverflowCountsPerGroup} and {@link
     *  OutboundOverflowCounts#decrementOutboundOverflowCountsPerGroup}). On the other hand, this field
     *  (outboundOverflowCountsPerGroup) is likely on the same cache line as {@link #bitSetAndState}
     *  which needs to be updated on an insertion or deletion to the segment anyway, so changing
     *  outboundOverflowCountsPerGroup likely won't contribute to memory write amplification. Note,
     *  however, that in practice, write amplification reduction might be small if transitions
     *  between 0 and 1 counts are in fact the most common (TODO quantify). Also, there is a 12.5%
     *  chance that outboundOverflowCountsPerGroup will be on a different cache line than {@link
     *  #bitSetAndState} (given that we have no control over the layout of segment objects; in an
     *  implementation of SmoothieMap in an environment that allows to control layout of data
     *  structures they can be ensured to lay in the same cache line).
     *
     *  - It allows to make {@link HashTable}'s tagGroups' and dataGroups' slots of 8 bits each (
     *  they have to be the same to allow efficient bitMask-based extraction in {@link
     *  HashTable#extractAllocIndex}) that in turn allows the tags to hold 8 rather than only 7 bits
     *  of keys' hash codes, thus reducing collisions during the search for a key. (Another
     *  alternative is incrementing a "scattered" outbound overflow count within a data group which
     *  is comprised of dataGroup's bits #6, #14, ... #62 instead of just {@link
     *  HashTable#DATA_GROUP__OUTBOUND_OVERFLOW_MASK} but it doesn't seem possible to increment and
     *  decrement such a scattered value efficiently, at least without parallel bit deposit and
     *  extract:
     *  en.wikipedia.org/wiki/Bit_Manipulation_Instruction_Sets#Parallel_bit_deposit_and_extract
     *  TODO experiment with that in native impl, or with Panama?
     */
    long outboundOverflowCountsPerGroup;

    private static final long BIT_SET_AND_STATE_OFFSET;
    private static final long OUTBOUND_OVERFLOW_COUNTS_PER_GROUP_OFFSET;

    static {
        BIT_SET_AND_STATE_OFFSET = UnsafeUtils.getFieldOffset(
                ContinuousSegment_BitSetAndStateArea.class, "bitSetAndState");
        OUTBOUND_OVERFLOW_COUNTS_PER_GROUP_OFFSET = UnsafeUtils.getFieldOffset(
                ContinuousSegment_BitSetAndStateArea.class, "outboundOverflowCountsPerGroup");
    }

    static long getBitSetAndState(Object segment) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        return U.getLong(segment, BIT_SET_AND_STATE_OFFSET);
    }

    static void setBitSetAndState(Object segment, long bitSetAndState) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        U.putLong(segment, BIT_SET_AND_STATE_OFFSET, bitSetAndState);
    }

    static long getOutboundOverflowCountsPerGroup(Object segment) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        return U.getLong(segment, OUTBOUND_OVERFLOW_COUNTS_PER_GROUP_OFFSET);
    }

    static void setOutboundOverflowCountsPerGroup(
            Object segment, long outboundOverflowCountsPerGroup) {
        /* if Enabled extraChecks */assert segment != null;/* endif */
        U.putLong(
                segment, OUTBOUND_OVERFLOW_COUNTS_PER_GROUP_OFFSET, outboundOverflowCountsPerGroup);
    }

    void clearOutboundOverflowCountsPerGroup() {
        outboundOverflowCountsPerGroup = 0;
    }

    static void copyOutboundOverflowCountsPerGroup(Object fromSegment, Object toSegment) {
        long outboundOverflowCountsPerGroup = getOutboundOverflowCountsPerGroup(fromSegment);
        setOutboundOverflowCountsPerGroup(toSegment, outboundOverflowCountsPerGroup);
    }

    static long replaceBitSetAndStateWithBulkOperationPlaceholderOrThrowCme(Object segment) {
        long bitSetAndState = getBitSetAndState(segment);
        if (isBulkOperationPlaceholderBitSetAndState(bitSetAndState)) {
            throw new ConcurrentModificationException();
        }
        setBitSetAndState(segment, makeBulkOperationPlaceholderBitSetAndState(bitSetAndState));
        return bitSetAndState;
    }

    static void setBitSetAndStateAfterBulkOperation(Object segment, long newBitSetAndState) {
        // This extra check doesn't guarantee anything because the update is not atomic, but it
        // raises the chances of catching concurrent modification.
        if (!isBulkOperationPlaceholderBitSetAndState(getBitSetAndState(segment))) {
            throw new ConcurrentModificationException();
        }
        setBitSetAndState(segment, newBitSetAndState);
    }

    /** @deprecated in order not to forget to remove calls from production code */
    @Deprecated
    final BitSetAndState.DebugBitSetAndState debugBitSetAndState() {
        return new BitSetAndState.DebugBitSetAndState(bitSetAndState);
    }
}
