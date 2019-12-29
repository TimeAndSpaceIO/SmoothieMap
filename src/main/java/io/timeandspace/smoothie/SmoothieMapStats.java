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

    int getNumAggregatedSegments() {
        return numAggregatedSegments;
    }

    int getNumInflatedSegments() {
        return numInflatedSegments;
    }

    @SuppressWarnings("WeakerAccess")
    int computeNumAggregatedOrdinarySegmentsWithOrder(int segmentOrder) {
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
