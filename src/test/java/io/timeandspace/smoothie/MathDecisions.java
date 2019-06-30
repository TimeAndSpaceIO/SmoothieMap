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

import org.apache.commons.math3.distribution.BinomialDistribution;
import org.apache.commons.math3.distribution.PoissonDistribution;

import java.util.DoubleSummaryStatistics;
import java.util.stream.IntStream;

@SuppressForbidden
public class MathDecisions {

    /**
     * Since real hashes are not perfect, theoretical probability of doubling table of 30% means
     * that in reality we will double table with a very good chance
     */
    static final double THRESHOLD = 0.3;

    static final int SEGMENT_CAPACITY = 56;
    static final int HASH_TABLE_BYTES = 64;
    static final int HASH_TABLE_SLOTS = 64;

    public static void main(String[] args) {
        int min = SmoothieMap.MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        int max = SmoothieMap.MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
//        for (int refSize : new int[] {4, 8}) {
//            System.out.println("REF SIZE " + refSize);
//            printSegmentsToScaleSegmentsArrayFrom(min, max, refSize);
            printFootprints2();
//        }
    }

    /**
     * Computes number of segments, from which probability of doubling (quadrupling, eight-ing)
     * segments array according to Poisson distribution laws exceeds {@link #THRESHOLD} assuming
     * perfectly uniform hash code distribution, for each rounded up average number of entries per
     * segment. In this case we are going to allocate doubled (quadrupled, eight-ed) segments array
     * up front, to avoid even little pause and garbaging previous segments array.
     */
    private static void printSegmentsToScaleSegmentsArrayFrom(int min, int max, int refSize) {
        System.out.println("Segments to double (quadruple, eight) segments from:");
        for (int d = 1; d <= 4; d *= 2) {
            for (int i = min; i <= max; i++) {
                PoissonDistribution p = new PoissonDistribution(i / (1.0 * d));
                System.out.printf("%d, ",
                        (long) (THRESHOLD / (1.0 - p.cumulativeProbability(SEGMENT_CAPACITY))));
            }
            System.out.println();
        }
    }

    static final int[] entriesForAveraging;
    static {
        long totalEntriesMin = (1 << 20);
        long totalEntriesMax = (1 << 24);
        entriesForAveraging = new int[501];
        for (int i = 0; i < 501; i++) {
            entriesForAveraging[i] =
                    (int) ((totalEntriesMin * i + totalEntriesMax * (500 - i)) / 500);
        }
    }

    private static void printFootprints() {
        System.out.println("Footprints:");

        for (int allocatedEntriesPerSegment = 20; allocatedEntriesPerSegment <= 60;
             allocatedEntriesPerSegment += 2) {
            System.out.println("allocated entries per segment: " + allocatedEntriesPerSegment);
            for (int javaReferenceSize : new int[] {4, 8}) {
                StringBuilder sb = new StringBuilder(javaReferenceSize + ": ");
                for (int entrySize : new int[] {8, 16, 32, 96}) {
                    DoubleSummaryStatistics averageEntryOverheadStats =
                            computeAverageEntryOverheadStats(entrySize, allocatedEntriesPerSegment,
                                    javaReferenceSize);
                    String s = String.format("%d[%.1f-%.1f av %.2f]   ",
                            entrySize, averageEntryOverheadStats.getMin(),
                            averageEntryOverheadStats.getMax(),
                            averageEntryOverheadStats.getAverage());
                    sb.append(s);
                }
                System.out.println(sb.toString());
            }
        }
    }

    private static void printFootprints2() {
        System.out.println("Footprints:");

        for (int entriesPerSmallSegment = 32; entriesPerSmallSegment <= 48;
             entriesPerSmallSegment += 1) {
            System.out.println("entries per small segment: " + entriesPerSmallSegment);
            for (int javaReferenceSize : new int[] {8}) {
                StringBuilder sb = new StringBuilder(javaReferenceSize + ": ");
                for (int entrySize : new int[] {4, 8, 16, 32, 96}) {
                    DoubleSummaryStatistics averageEntryOverheadStats =
                            computeAverageEntryOverheadStats2(entrySize, 56, entriesPerSmallSegment,
                                    javaReferenceSize);
                    String s = String.format("%d[%.1f-%.1f av %.2f]   ",
                            entrySize, averageEntryOverheadStats.getMin(),
                            averageEntryOverheadStats.getMax(),
                            averageEntryOverheadStats.getAverage());
                    sb.append(s);
                }
                System.out.println(sb.toString());
            }
        }
    }

    private static DoubleSummaryStatistics computeAverageEntryOverheadStats(int entrySize,
            int allocatedEntriesPerSegment, int javaReferenceSize) {
        return IntStream
                .of(entriesForAveraging)
                .mapToDouble(totalEntries -> footprint(totalEntries, entrySize,
                        allocatedEntriesPerSegment, javaReferenceSize) / totalEntries)
                .summaryStatistics();
    }

    private static DoubleSummaryStatistics computeAverageEntryOverheadStats2(int entrySize,
            int allocatedEntriesPerSegment, int entriesPerSmallSegment, int javaReferenceSize) {
        return IntStream
                .of(entriesForAveraging)
                .mapToDouble(totalEntries -> footprint2(totalEntries, entrySize,
                        allocatedEntriesPerSegment, entriesPerSmallSegment, javaReferenceSize) /
                        totalEntries)
                .summaryStatistics();
    }

    /**
     * Computes total map footprint in bytes.
     */
    private static double footprint(long totalEntries, int entrySize,
            int allocatedEntriesPerSegment, int javaReferenceSize) {
        int objectHeaderSize = 8 + javaReferenceSize;
        int segmentSize = objectSizeRoundUp(
                (/*objectHeaderSize + */HASH_TABLE_BYTES +
                        24 + // alloc refs
                        //48 + // bit set
                        //4 + // tier or next segment ref
                        //16 + // tombstone markers
                        (allocatedEntriesPerSegment * entrySize)));

        long totalVirtualSegments =
                findExpectedTotalVirtualSegments(totalEntries, allocatedEntriesPerSegment);
        double averageEntriesPerVirtualSegment = (double) totalEntries / totalVirtualSegments;

        // For simplicity, consider just three classes of segments. There could also be super
        // underpopulated segments with poisson(averageEntriesPerVirtualSegment * 4)
        // .cumulativeProbability(allocatedEntriesPerSegment), etc. and similarly overpopulated
        // segments. But their shares are so small that we could ignore that without losing much
        // accuracy.
        double underpopulatedVirtualSegmentsShare =
                new PoissonDistribution(averageEntriesPerVirtualSegment * 2).cumulativeProbability(
                        allocatedEntriesPerSegment);
        double overpopulatedVirtualSegmentsShare = 1 -
                new PoissonDistribution(averageEntriesPerVirtualSegment)
                        .cumulativeProbability(allocatedEntriesPerSegment);
        double regularSegmentsShare = 1 - underpopulatedVirtualSegmentsShare -
                overpopulatedVirtualSegmentsShare;

        double underpopulatedVirtualSegmentsFootprint = totalVirtualSegments *
                underpopulatedVirtualSegmentsShare * (segmentSize / 2.0);
        double regularSegmentsFootprint = totalVirtualSegments * regularSegmentsShare * segmentSize;
        double overpopulatedVirtualSegmentsFootprint = totalVirtualSegments *
                overpopulatedVirtualSegmentsShare * (segmentSize * 2);

        // Assuming there are almost always some overpopulated segments, the array is almost always
        // doubled. With large totalEntries, there is a good probability that it's even quadrupled,
        // i. e. 1 - dist.cumulativeProbability(allocatedEntriesPerSegment * 2) is not so small.
        // But ignoring it here.
        long segmentArrayFootprint = totalVirtualSegments * 2 * javaReferenceSize;

        return segmentArrayFootprint + underpopulatedVirtualSegmentsFootprint +
                regularSegmentsFootprint + overpopulatedVirtualSegmentsFootprint;
    }

    static long findExpectedTotalVirtualSegments(long totalEntries,
            int allocatedEntriesPerSegment) {
        long totalVirtualSegments = 1;
        while (true) {
            double averageEntriesPerVirtualSegment = (double) totalEntries / totalVirtualSegments;
            if (averageEntriesPerVirtualSegment <= allocatedEntriesPerSegment) {
                return totalVirtualSegments;
            }
            totalVirtualSegments *= 2;
        }
    }

    /**
     * Computes total map footprint in bytes.
     */
    private static double footprint2(long totalEntries, int entrySize,
            int allocatedEntriesPerSegment, int entriesPerSmallSegment, int javaReferenceSize) {

        int objectHeaderSize = 8 + javaReferenceSize;
        int segmentSize = objectSizeRoundUp(
                (/*objectHeaderSize + */HASH_TABLE_BYTES +
                        64 + // alloc refs
                        //48 + // bit set
                        //4 + // tier or next segment ref
                        //16 + // tombstone markers
                        (allocatedEntriesPerSegment * entrySize)));

        int smallSegmentSize = objectSizeRoundUp(
                (/*objectHeaderSize + */HASH_TABLE_BYTES +
                        64 + // alloc refs
                        //48 + // bit set
                        //4 + // tier or next segment ref
                        //16 + // tombstone markers
                        (entriesPerSmallSegment * entrySize)));

        long totalVirtualSegments =
                findExpectedTotalVirtualSegments(totalEntries, allocatedEntriesPerSegment);
        double averageEntriesPerVirtualSegment = (double) totalEntries / totalVirtualSegments;


        PoissonDistribution entriesPerTwoVirtualSegmentsDist =
                new PoissonDistribution(averageEntriesPerVirtualSegment * 2);
        // For simplicity, consider just the most common classes of segments. There could also be
        // super underpopulated segments with poisson(averageEntriesPerVirtualSegment * 4)
        // .cumulativeProbability(allocatedEntriesPerSegment), etc. and similarly overpopulated
        // segments. But their shares are so small that we could ignore that without losing much
        // accuracy.
        double underpopulatedVirtualSegmentsShare =
                entriesPerTwoVirtualSegmentsDist.cumulativeProbability(allocatedEntriesPerSegment);
        final double underpopulatedVirtualSegmentsFootprint = totalVirtualSegments *
                underpopulatedVirtualSegmentsShare * (segmentSize / 2.0);

        double smallishSegmentsShare = smallishSegmentsShare(allocatedEntriesPerSegment,
                entriesPerSmallSegment, entriesPerTwoVirtualSegmentsDist);
        double smallishSegmentsFootprint =
                smallishSegmentsShare * smallSegmentSize * totalVirtualSegments;

        double overpopulatedVirtualSegmentsShare = 1 -
                new PoissonDistribution(averageEntriesPerVirtualSegment)
                        .cumulativeProbability(allocatedEntriesPerSegment);
        double regularSegmentsShare = 1 - underpopulatedVirtualSegmentsShare -
                smallishSegmentsShare - overpopulatedVirtualSegmentsShare;

        double regularSegmentsFootprint = regularSegmentsShare * totalVirtualSegments * segmentSize;

        double oneAndHalfSegmentsShare = oneAndHalfSegmentsShare(allocatedEntriesPerSegment,
                entriesPerSmallSegment, averageEntriesPerVirtualSegment);
        double oneAndHalfSegmentsFootprint = oneAndHalfSegmentsShare *
                (segmentSize + smallSegmentSize) * totalVirtualSegments;

        double doubleSegmentsShare =
                overpopulatedVirtualSegmentsShare - oneAndHalfSegmentsShare;
        double doubleSegmentsFootprint = doubleSegmentsShare * (segmentSize * 2) *
                totalVirtualSegments;

        // Assuming there are almost always some overpopulated segments, the array is almost always
        // doubled. With large totalEntries, there is a good probability that it's even quadrupled,
        // i. e. 1 - dist.cumulativeProbability(allocatedEntriesPerSegment * 2) is not so small.
        // But ignoring it here.
        long segmentArrayFootprint = totalVirtualSegments * 2 * javaReferenceSize;

        return segmentArrayFootprint + underpopulatedVirtualSegmentsFootprint +
                smallishSegmentsFootprint + regularSegmentsFootprint + oneAndHalfSegmentsFootprint +
                doubleSegmentsFootprint;
    }

    private static double smallishSegmentsShare(int allocatedEntriesPerSegment,
            int entriesPerSmallSegment, PoissonDistribution entriesPerTwoVirtualSegmentsDist) {
        double smallishSegmentsShare = 0.0;
        for (int entriesPerTwoSegments = allocatedEntriesPerSegment + 1;
             entriesPerTwoSegments <= allocatedEntriesPerSegment * 2;
             entriesPerTwoSegments++) {
            BinomialDistribution twoSegmentsDist =
                    new BinomialDistribution(entriesPerTwoSegments, 0.5);
            smallishSegmentsShare += twoSegmentsDist.cumulativeProbability(entriesPerSmallSegment) *
                    entriesPerTwoVirtualSegmentsDist.probability(entriesPerTwoSegments);
        }
        return smallishSegmentsShare;
    }

    private static double oneAndHalfSegmentsShare(int allocatedEntriesPerSegment,
            int entriesPerSmallSegment, double averageEntriesPerVirtualSegment) {
        PoissonDistribution entriesPerVirtualSegmentDist =
                new PoissonDistribution(averageEntriesPerVirtualSegment);
        double oneAndHalfSegmentsShare = 0.0;
        for (int entriesPerTwoOverflowSegments = allocatedEntriesPerSegment + 1;
             entriesPerTwoOverflowSegments <= allocatedEntriesPerSegment * 2;
             entriesPerTwoOverflowSegments++) {
            BinomialDistribution twoSegmentsDist =
                    new BinomialDistribution(entriesPerTwoOverflowSegments, 0.5);
            oneAndHalfSegmentsShare += twoSegmentsDist.cumulativeProbability(entriesPerSmallSegment)
                    * entriesPerVirtualSegmentDist.probability(entriesPerTwoOverflowSegments);
        }
        return oneAndHalfSegmentsShare;
    }

    static int objectSizeRoundUp(int size) {
        return (size + 7) & ~7;
    }
}
