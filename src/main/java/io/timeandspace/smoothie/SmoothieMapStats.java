package io.timeandspace.smoothie;

import io.timeandspace.smoothie.OrdinarySegmentStats.Count;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Objects;

import static io.timeandspace.smoothie.OrdinarySegmentStats.appendNonZeroOrderedCountsWithPercentiles;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.deletedSlotCount;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.isInflatedBitSetAndState;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.segmentOrder;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.segmentSize;
import static io.timeandspace.smoothie.SmoothieMap.MAX_SEGMENTS_ARRAY_ORDER;
import static io.timeandspace.smoothie.SmoothieMap.Segment.SEGMENT_MAX_NON_EMPTY_SLOTS;

final class SmoothieMapStats {
    private int numAggregatedMaps = 0;
    private int numAggregatedSegments = 0;
    private int numInflatedSegments = 0;
    private final
    @Nullable OrdinarySegmentStats @Nullable [][] ordinarySegmentStatsPerOrderAndNumNonEmptySlots =
            new OrdinarySegmentStats[MAX_SEGMENTS_ARRAY_ORDER + 1][];

    int getNumInflatedSegments() {
        return numInflatedSegments;
    }

    @SuppressWarnings("WeakerAccess")
    int computeNumAggregatedOrdinarySegmentsWithOrder(int segmentOrder) {
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        @Nullable OrdinarySegmentStats @Nullable [] statsForSegmentOrder =
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots[segmentOrder];
        if (statsForSegmentOrder == null) {
            return 0;
        }
        return Arrays
                .stream(statsForSegmentOrder)
                .filter(Objects::nonNull)
                .mapToInt(OrdinarySegmentStats::getNumAggregatedSegments)
                .sum();
    }

    private long computeNumAggregatedFullSlotsInSegmentsWithOrder(int segmentOrder) {
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        @Nullable OrdinarySegmentStats @Nullable [] statsForSegmentOrder =
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots[segmentOrder];
        if (statsForSegmentOrder == null) {
            return 0;
        }
        return Arrays
                .stream(statsForSegmentOrder)
                .filter(Objects::nonNull)
                .mapToLong(OrdinarySegmentStats::getNumAggregatedFullSlots)
                .sum();
    }

    void incrementAggregatedMaps() {
        numAggregatedMaps++;
    }

    private OrdinarySegmentStats acquireSegmentStats(int segmentOrder, int numNonEmptySlots) {
        // might be fixed in IntelliJ 2019.2 by https://youtrack.jetbrains.com/issue/IDEA-210087
        // TODO check when update to 2019.2
        //noinspection ConstantConditions
        @Nullable OrdinarySegmentStats @Nullable [] ordinarySegmentStatsPerNumNonEmptySlots =
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots[segmentOrder];
        if (ordinarySegmentStatsPerNumNonEmptySlots == null) {
            ordinarySegmentStatsPerNumNonEmptySlots =
                    new OrdinarySegmentStats[SEGMENT_MAX_NON_EMPTY_SLOTS + 1];
            ordinarySegmentStatsPerOrderAndNumNonEmptySlots[segmentOrder] =
                    ordinarySegmentStatsPerNumNonEmptySlots;
        }
        @Nullable OrdinarySegmentStats ordinarySegmentStats =
                ordinarySegmentStatsPerNumNonEmptySlots[numNonEmptySlots];
        if (ordinarySegmentStats == null) {
            ordinarySegmentStats = new OrdinarySegmentStats();
            ordinarySegmentStatsPerNumNonEmptySlots[numNonEmptySlots] = ordinarySegmentStats;
        }
        return ordinarySegmentStats;
    }

    <K, V> void aggregateSegment(SmoothieMap<K, V> map, SmoothieMap.Segment<K, V> segment) {
        numAggregatedSegments++;
        long bitSetAndState = segment.bitSetAndState;
        if (isInflatedBitSetAndState(bitSetAndState)) {
            numInflatedSegments++;
            return;
        }
        int segmentOrder = segmentOrder(bitSetAndState);
        int numNonEmptySlots = segmentSize(bitSetAndState) + deletedSlotCount(bitSetAndState);
        OrdinarySegmentStats ordinarySegmentStats =
                acquireSegmentStats(segmentOrder, numNonEmptySlots);
        segment.aggregateStats(map, ordinarySegmentStats);
    }

    OrdinarySegmentStats computeTotalOrdinarySegmentStats() {
        OrdinarySegmentStats totalStats = new OrdinarySegmentStats();
        // TODO Might be a bug in IntelliJ that it doesn't require for-each iteration variable to be
        //  Nullable when the array has Nullable elements - check when update to 2019.2
        //noinspection ConstantConditions: same as above in acquireSegmentStats()
        for (@Nullable OrdinarySegmentStats @Nullable [] statsPerNumNonEmptySlots :
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots) {
            if (statsPerNumNonEmptySlots == null) {
                continue;
            }
            for (@Nullable OrdinarySegmentStats stats : statsPerNumNonEmptySlots) {
                if (stats != null) {
                    totalStats.add(stats);
                }
            }
        }
        return totalStats;
    }

    OrdinarySegmentStats computeOrdinarySegmentStatsPerNumNonEmptySlots(int numNonEmptySlots) {
        OrdinarySegmentStats totalStatsForNumNonEmptySlots = new OrdinarySegmentStats();
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        for (@Nullable OrdinarySegmentStats @Nullable [] statsPerNumNonEmptySlots :
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots) {
            if (statsPerNumNonEmptySlots == null) {
                continue;
            }
            @Nullable OrdinarySegmentStats stats = statsPerNumNonEmptySlots[numNonEmptySlots];
            if (stats != null) {
                totalStatsForNumNonEmptySlots.add(stats);
            }
        }
        return totalStatsForNumNonEmptySlots;
    }

    String segmentOrderAndLoadDistribution() {
        StringBuilder sb = new StringBuilder();

        Count numSegmentsWithOrder_count = new Count(
                "segments",
                segmentOrder -> (long) computeNumAggregatedOrdinarySegmentsWithOrder(segmentOrder)
        );
        Count numFullSlotsInSegmentsWithOrder_count =
                new Count("full slots", this::computeNumAggregatedFullSlotsInSegmentsWithOrder);
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        appendNonZeroOrderedCountsWithPercentiles(sb, "order",
                ordinarySegmentStatsPerOrderAndNumNonEmptySlots.length,
                Arrays.asList(numSegmentsWithOrder_count, numFullSlotsInSegmentsWithOrder_count),
                segmentOrder -> {
                    @Nullable OrdinarySegmentStats @Nullable [] statsForSegmentOrder =
                            ordinarySegmentStatsPerOrderAndNumNonEmptySlots[segmentOrder];
                    if (statsForSegmentOrder == null) {
                        return;
                    }
                    Count numSegmentsWithNonEmptySlots_count = new Count(
                            "segments",
                            numNonEmptySlots -> {
                                @Nullable OrdinarySegmentStats stats =
                                        statsForSegmentOrder[numNonEmptySlots];
                                return stats == null ? 0 : (long) stats.getNumAggregatedSegments();
                            }
                    );
                    Count numFullSlotsInSegmentsWithNonEmptySlots_count = new Count(
                            "full slots",
                            numNonEmptySlots -> {
                                @Nullable OrdinarySegmentStats stats =
                                        statsForSegmentOrder[numNonEmptySlots];
                                return stats == null ? 0 : stats.getNumAggregatedFullSlots();
                            }
                    );
                    appendNonZeroOrderedCountsWithPercentiles(sb, "# non-empty slots =",
                            statsForSegmentOrder.length,
                            Arrays.asList(numSegmentsWithNonEmptySlots_count,
                                    numFullSlotsInSegmentsWithNonEmptySlots_count),
                            numNonEmptySlots -> {});
                });
        return sb.toString();
    }
}
