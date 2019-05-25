package io.timeandspace.smoothie;

import java.util.function.IntConsumer;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;

import static io.timeandspace.smoothie.LongMath.percentOf;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.HashTableArea.*;

/**
 * Stats of collision chain lengths and deleted slot counts for ordinary segments (that are, not
 * {@link io.timeandspace.smoothie.SmoothieMap.InflatedSegment}s).
 */
final class OrdinarySegmentStats {
    private static final int[] QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH =
            new int[HASH_TABLE_GROUPS];
    static {
        int groupIndex = 0;
        int step = 0;
        for (int chainLength = 0;
             chainLength < QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH.length;
             chainLength++) {
            QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH[groupIndex] = chainLength;
            step += 1; // [Quadratic probing]
            groupIndex = (groupIndex + step) % HASH_TABLE_GROUPS;
        }
    }

    private int numAggregatedSegments = 0;
    private long numAggregatedFullSlots = 0;
    private long numDeletedSlots = 0;
    private final long[] numSlotsPerCollisionChainSlotLengths =
            new long[SEGMENT_MAX_ALLOC_CAPACITY];
    private final long[] numSlotsPerCollisionChainGroupLengths =
            new long[SEGMENT_MAX_ALLOC_CAPACITY / GROUP_SLOTS];

    int getNumAggregatedSegments() {
        return numAggregatedSegments;
    }

    void aggregateFullSlot(int slotIndexBase, int slotIndex) {
        int quadraticProbingChainGroupIndex =
                // [Replacing division with shift]
                ((slotIndex - slotIndexBase) & SLOT_MASK) >>> GROUP_SLOTS_DIVISION_SHIFT;
        int collisionChainGroupLength = QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH[
                quadraticProbingChainGroupIndex];
        numSlotsPerCollisionChainGroupLengths[collisionChainGroupLength]++;
        int collisionChainSlotRemainder = (slotIndex - slotIndexBase) & (GROUP_SLOTS - 1);
        int collisionChainSlotLength =
                GROUP_SLOTS * collisionChainGroupLength + collisionChainSlotRemainder;
        numSlotsPerCollisionChainSlotLengths[collisionChainSlotLength]++;
        numAggregatedFullSlots++;
    }

    void aggregateDeletedSlot() {
        numDeletedSlots++;
    }

    void incrementAggregatedSegments() {
        numAggregatedSegments++;
    }

    void add(OrdinarySegmentStats other) {
        numAggregatedSegments += other.numAggregatedSegments;
        numAggregatedFullSlots += other.numAggregatedFullSlots;
        numDeletedSlots += other.numDeletedSlots;
        for (int i = 0; i < numSlotsPerCollisionChainSlotLengths.length; i++) {
            numSlotsPerCollisionChainSlotLengths[i] +=
                    other.numSlotsPerCollisionChainSlotLengths[i];
        }
        for (int i = 0; i < numSlotsPerCollisionChainGroupLengths.length; i++) {
            numSlotsPerCollisionChainGroupLengths[i] +=
                    other.numSlotsPerCollisionChainGroupLengths[i];
        }
    }

    @SuppressWarnings("AutoBoxing")
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Number of segments: %d%n", numAggregatedSegments));

        double averageFullSlots = (double) numAggregatedFullSlots / (double) numAggregatedSegments;
        long totalFullAndDeletedSlots = numAggregatedFullSlots + numDeletedSlots;
        double fullSlotsPercent = 100.0 *
                (double) numAggregatedFullSlots / (double) totalFullAndDeletedSlots;
        sb.append(String.format("Average full slots: %.2f (%3.2f%%)%n", averageFullSlots,
                fullSlotsPercent));
        double averageDeletedSlots = (double) numDeletedSlots / (double) numAggregatedSegments;
        double deletedSlotsPercent = 100.0 - fullSlotsPercent;
        sb.append(String.format("Average deleted slots: %.2f (%3.2f%%)%n", averageDeletedSlots,
                deletedSlotsPercent));

        appendChainLengthStats(sb, numSlotsPerCollisionChainGroupLengths, "group");
        appendChainLengthStats(sb, numSlotsPerCollisionChainSlotLengths, "slot");
        return sb.toString();
    }

    @SuppressWarnings("AutoBoxing")
    private void appendChainLengthStats(
            StringBuilder sb, long[] numSlotsPerChainLengths, String chainLengthType) {
        long totalChainLength = 0;
        for (int chainLength = 0; chainLength < numSlotsPerChainLengths.length; chainLength++) {
            long numSlotsWithLength = numSlotsPerChainLengths[chainLength];
            totalChainLength += numSlotsWithLength * (long) chainLength;
        }
        double averageChainLength = (double) totalChainLength / (double) numAggregatedFullSlots;
        sb.append(String.format("Average collision chain %s length: %.2f%n", chainLengthType,
                averageChainLength));

        appendNonZeroOrderedCountsWithPercentiles(
                sb, "chain length =", "slots", numSlotsPerChainLengths.length,
                chainLength -> numSlotsPerChainLengths[chainLength],
                chainLength -> {});
    }

    @SuppressWarnings("AutoBoxing")
    static void appendNonZeroOrderedCountsWithPercentiles(
            StringBuilder sb, String orderPrefix, String countSuffix, int maxOrderExclusive,
            IntToLongFunction getCount, IntConsumer perOrderAction) {
        int maxOrderWidth = String.valueOf(maxOrderExclusive - 1).length();
        long maxCount = IntStream.range(0, maxOrderExclusive).mapToLong(getCount).max().orElse(0);
        int maxCountWidth = String.valueOf(maxCount).length();
        // Ensures all counts, and the subsequent percentile columns are aligned.
        String lineFormat = orderPrefix + " %" + maxOrderWidth + "d: " +
                "%" + maxCountWidth + "d " + countSuffix + ", %3.2f%% %3.2f%%%n";

        long totalCount = IntStream.range(0, maxOrderExclusive).mapToLong(getCount).sum();
        long currentAggregatedCount = 0;
        for (int i = 0; i < maxOrderExclusive; i++) {
            long count = getCount.applyAsLong(i);
            if (count == 0) {
                continue; // skip zeros
            }
            currentAggregatedCount += count;
            double percentile = percentOf(count, totalCount);
            double currentAggregatedPercentile = percentOf(currentAggregatedCount, totalCount);
            sb.append(String.format(lineFormat, i, count, percentile, currentAggregatedPercentile));
            perOrderAction.accept(i);
        }
    }
}
