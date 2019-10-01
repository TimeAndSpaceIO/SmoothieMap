/*
 *    Copyright (C) Smoothie Map Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.openhft.smoothie;

import org.apache.commons.math3.distribution.PoissonDistribution;


public class MathDecisions {

    /**
     * Since real hashes are not perfect, theoretical probability of doubling table of 30% means
     * that in reality we will double table with a very good chance
     */
    static final double THRESHOLD = 0.3;

    public static void main(String[] args) {
        int min = SmoothieMap.MIN_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        int max = SmoothieMap.MAX_ROUNDED_UP_AVERAGE_ENTRIES_PER_SEGMENT;
        for (int refSize : new int[] {4, 8}) {
            System.out.println("REF SIZE " + refSize);
            printAllocCapacities(min, max, refSize);
            printSegmentsToScaleSegmentsArrayFrom(min, max, refSize);
            printFootprints(min, max, refSize);
            printOptimalCaps(refSize);
        }
    }

    private static void printAllocCapacities(int min, int max, int refSize) {
        System.out.println("Alloc capacities:");
        for (int i = min; i <= max; i++) {
            System.out.printf("%d, ", cap(i, refSize));
        }
        System.out.println();
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
                int cap = cap(i, refSize);
                System.out.printf("%d, ",
                        (long) (THRESHOLD / (1.0 - p.cumulativeProbability(cap))));
            }
            System.out.println();
        }
    }

    private static void printFootprints(int min, int max, int refSize) {
        System.out.println("Footprints:");
        for (int i = min; i <= max; i++) {
            System.out.println("average entries/segment: " + i + " " +
                    " double: " + footprint(i, refSize, 1) +
                    " quad: " + footprint(i, refSize, 2));
        }
        System.out.println("Footprint if size not specified:");
        double minF = Double.MAX_VALUE, maxF = Double.MIN_VALUE;
        int minE = 0, maxE = 0;
        int maxCap = cap(max, refSize);
        for (int i = maxCap / 2; i <= maxCap; i++) {
            double f = footprint(i, refSize, 1, maxCap);
            if (f < minF) {
                minF = f;
                minE = i;
            }
            if (f > maxF) {
                maxF = f;
                maxE = i;
            }
        }
        System.out.println("Best case: " + refSize + ": " + minE + " " + minF);
        System.out.println("Worst case: " + refSize + ": " + maxE + " " + maxF);
    }

    static int chooseOptimalCap(int average, int refSize) {
        double minFootprint = Double.MAX_VALUE;
        int bestCap = -1;
        for (int cap = average; cap <= Math.min(average * 2, 63); cap++) {
            double footprint = footprint(average, refSize, 1, cap);
            if (footprint < minFootprint) {
                minFootprint = footprint;
                bestCap = cap;
            }
        }
        return bestCap;
    }

    static void printOptimalCaps(int refSize) {
        for (int average = 20; average <= 63; average++) {
            int cap = chooseOptimalCap(average, refSize);
            System.out.println("Optimal for average " + average + " is " + cap +
                    " with footprint " + footprint(average, refSize, 1, cap));
        }
    }

    /**
     * @param average average number of entries going to the segment
     */
    static int cap(int average, int refSize) {
        return chooseOptimalCap(average, refSize);
    }

    /**
     * Average extra retention bytes per entry
     */
    static double footprint(int averageEntries, int refSize, int upFrontScale) {
        int cap = cap(averageEntries, refSize);
        return footprint(averageEntries, refSize, upFrontScale, cap);
    }

    private static double footprint(int averageEntries, int refSize, int upFrontScale, int cap) {
        PoissonDistribution p = new PoissonDistribution(averageEntries);
        double stayCapProb = p.cumulativeProbability(cap);
        int objectHeaderSize = 8 + refSize;
        int segmentSize = objectSizeRoundUp(
                (objectHeaderSize + (Segment.HASH_TABLE_SIZE * 2) +
                        8 + /* bit set */
                        4 /* tier */ + (cap * 2 * refSize)));
        double totalSegmentsSize =
                (stayCapProb + (1 - stayCapProb) * 2) * segmentSize;
        int segmentsArraySize = refSize << upFrontScale;

        return (totalSegmentsSize + segmentsArraySize) / averageEntries;
    }

    static int objectSizeRoundUp(int size) {
        return (size + 7) & ~7;
    }
}
