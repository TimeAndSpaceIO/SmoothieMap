package io.timeandspace.smoothie;

import io.timeandspace.smoothie.OrdinarySegmentStats.Count;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Objects;

import static io.timeandspace.smoothie.BitSetAndState.isInflatedBitSetAndState;
import static io.timeandspace.smoothie.BitSetAndState.segmentOrder;
import static io.timeandspace.smoothie.BitSetAndState.segmentSize;
import static io.timeandspace.smoothie.OrdinarySegmentStats.appendNonZeroOrderedCountsWithPercentiles;
import static io.timeandspace.smoothie.SmoothieMap.MAX_SEGMENTS_ARRAY_ORDER;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;

final class SmoothieMapStats {
    private int numAggregatedMaps = 0;
    private int numAggregatedSegments = 0;
    private int numInflatedSegments = 0;
    private final
    @Nullable OrdinarySegmentStats @Nullable [][] ordinarySegmentStatsPerOrderAndNumFullSlots =
            new OrdinarySegmentStats[MAX_SEGMENTS_ARRAY_ORDER + 1][];

    int getNumInflatedSegments() {
        return numInflatedSegments;
    }

    @SuppressWarnings("WeakerAccess")
    int computeNumAggregatedOrdinarySegmentsWithOrder(int segmentOrder) {
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        @Nullable OrdinarySegmentStats @Nullable [] statsForSegmentOrder =
                ordinarySegmentStatsPerOrderAndNumFullSlots[segmentOrder];
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
                ordinarySegmentStatsPerOrderAndNumFullSlots[segmentOrder];
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

    private OrdinarySegmentStats acquireSegmentStats(int segmentOrder, int numFullSlots) {
        // might be fixed in IntelliJ 2019.2 by https://youtrack.jetbrains.com/issue/IDEA-210087
        // TODO check when update to 2019.2
        //noinspection ConstantConditions
        @Nullable OrdinarySegmentStats @Nullable [] ordinarySegmentStatsPerNumFullSlots =
                ordinarySegmentStatsPerOrderAndNumFullSlots[segmentOrder];
        if (ordinarySegmentStatsPerNumFullSlots == null) {
            ordinarySegmentStatsPerNumFullSlots =
                    new OrdinarySegmentStats[SEGMENT_MAX_ALLOC_CAPACITY + 1];
            ordinarySegmentStatsPerOrderAndNumFullSlots[segmentOrder] =
                    ordinarySegmentStatsPerNumFullSlots;
        }
        @Nullable OrdinarySegmentStats ordinarySegmentStats =
                ordinarySegmentStatsPerNumFullSlots[numFullSlots];
        if (ordinarySegmentStats == null) {
            ordinarySegmentStats = new OrdinarySegmentStats();
            ordinarySegmentStatsPerNumFullSlots[numFullSlots] = ordinarySegmentStats;
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
        int numFullSlots = segmentSize(bitSetAndState);
        OrdinarySegmentStats ordinarySegmentStats = acquireSegmentStats(segmentOrder, numFullSlots);
        segment.aggregateStats(map, ordinarySegmentStats);
    }

    OrdinarySegmentStats computeTotalOrdinarySegmentStats() {
        OrdinarySegmentStats totalStats = new OrdinarySegmentStats();
        // TODO Might be a bug in IntelliJ that it doesn't require for-each iteration variable to be
        //  Nullable when the array has Nullable elements - check when update to 2019.2
        //noinspection ConstantConditions: same as above in acquireSegmentStats()
        for (@Nullable OrdinarySegmentStats @Nullable [] statsPerNumFullSlots :
                ordinarySegmentStatsPerOrderAndNumFullSlots) {
            if (statsPerNumFullSlots == null) {
                continue;
            }
            for (@Nullable OrdinarySegmentStats stats : statsPerNumFullSlots) {
                if (stats != null) {
                    totalStats.add(stats);
                }
            }
        }
        return totalStats;
    }

    OrdinarySegmentStats computeOrdinarySegmentStatsPerNumFullSlots(int numFullSlots) {
        OrdinarySegmentStats totalStatsForNumFullSlots = new OrdinarySegmentStats();
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        for (@Nullable OrdinarySegmentStats @Nullable [] statsPerNumFullSlots :
                ordinarySegmentStatsPerOrderAndNumFullSlots) {
            if (statsPerNumFullSlots == null) {
                continue;
            }
            @Nullable OrdinarySegmentStats stats = statsPerNumFullSlots[numFullSlots];
            if (stats != null) {
                totalStatsForNumFullSlots.add(stats);
            }
        }
        return totalStatsForNumFullSlots;
    }

    String segmentOrderAndLoadDistribution() {
        StringBuilder sb = new StringBuilder();
        sb.append("Num inflated segments: ").append(numInflatedSegments).append('\n');

        Count numSegmentsWithOrder_count = new Count(
                "segments",
                segmentOrder -> (long) computeNumAggregatedOrdinarySegmentsWithOrder(segmentOrder)
        );
        Count numFullSlotsInSegmentsWithOrder_count =
                new Count("full slots", this::computeNumAggregatedFullSlotsInSegmentsWithOrder);
        //noinspection ConstantConditions: same as in computeTotalOrdinarySegmentStats()
        appendNonZeroOrderedCountsWithPercentiles(sb, "order",
                ordinarySegmentStatsPerOrderAndNumFullSlots.length,
                Arrays.asList(numSegmentsWithOrder_count, numFullSlotsInSegmentsWithOrder_count),
                segmentOrder -> {
                    @Nullable OrdinarySegmentStats @Nullable [] statsForSegmentOrder =
                            ordinarySegmentStatsPerOrderAndNumFullSlots[segmentOrder];
                    if (statsForSegmentOrder == null) {
                        return;
                    }
                    Count numSegmentsWithFullSlots_count = new Count(
                            "segments",
                            numFullSlots -> {
                                @Nullable OrdinarySegmentStats stats =
                                        statsForSegmentOrder[numFullSlots];
                                return stats == null ? 0 : (long) stats.getNumAggregatedSegments();
                            }
                    );
                    Count numFullSlotsInSegmentsWithFullSlots_count = new Count(
                            "full slots",
                            numFullSlots -> {
                                @Nullable OrdinarySegmentStats stats =
                                        statsForSegmentOrder[numFullSlots];
                                return stats == null ? 0 : stats.getNumAggregatedFullSlots();
                            }
                    );
                    appendNonZeroOrderedCountsWithPercentiles(sb, "# full slots =",
                            statsForSegmentOrder.length,
                            Arrays.asList(numSegmentsWithFullSlots_count,
                                    numFullSlotsInSegmentsWithFullSlots_count),
                            numFullSlots -> {});
                });
        return sb.toString();
    }

    @Override
    public String toString() {
        return segmentOrderAndLoadDistribution();
    }
}
