/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
        printAllocCapacities(min, max);
        printSegmentsToScaleSegmentsArrayFrom(min, max);
        printFootprints(min, max);
    }

    private static void printAllocCapacities(int min, int max) {
        System.out.println("Alloc capacities:");
        for (int i = min; i <= max; i++) {
            System.out.printf("%d, ", cap(i));
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
    private static void printSegmentsToScaleSegmentsArrayFrom(int min, int max) {
        System.out.println("Segments to double (quadruple, eight) segments from:");
        for (int d = 1; d <= 4; d *= 2) {
            for (int i = min; i <= max; i++) {
                PoissonDistribution p = new PoissonDistribution(i / (1.0 * d));
                int cap = cap(i);
                System.out.printf("%d, ",
                        (long) (THRESHOLD / (1.0 - p.cumulativeProbability(cap))));
            }
            System.out.println();
        }
    }

    private static void printFootprints(int min, int max) {
        System.out.println("Footprints:");
        for (int i = min; i <= max; i++) {
            System.out.println("average entries/segment: " + i + " " +
                    "4, double: " + footprint(i, 4, 1) +
                    " 4, quad: " + footprint(i, 4, 2) +
                    " 8, double: " + footprint(i, 8, 1) +
                    " 8, quad: " + footprint(i, 8, 2));
        }
        System.out.println("Footprint if size not specified:");
        for (int refSize : new int[] {4, 8}) {
            double minF = Double.MAX_VALUE, maxF = Double.MIN_VALUE;
            int minE = 0, maxE = 0;
            for (int i = cap(max) / 2; i <= cap(max); i++) {
                double f = footprint(i, refSize, 1, max);
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

    }

    /**
     * @param average average number of entries going to the segment
     * @return capacity of segment, so that overflow probability < 10%
     */
    static int cap(int average) {
        PoissonDistribution p = new PoissonDistribution(average);
        for (int cap = average; ; cap++) {
            if (p.cumulativeProbability(cap) >= PROB_63_BY_53)
                return cap;
        }
    }

    /** ~ 0.92 */
    static final double PROB_63_BY_53 = new PoissonDistribution(53).cumulativeProbability(63);

    /**
     * Average extra retention bytes per entry
     */
    static double footprint(int averageEntries, int refSize, int upFrontScale) {
        int cap = cap(averageEntries);
        return footprint(averageEntries, refSize, upFrontScale, cap);
    }

    private static double footprint(int averageEntries, int refSize, int upFrontScale, int cap) {
        PoissonDistribution p = new PoissonDistribution(averageEntries);
        double stayCapProb = p.cumulativeProbability(cap);
        int objectHeaderSize = 8 + refSize;
        int segmentSize = objectSizeRoundUp(
                (objectHeaderSize + (Segment.HASH_TABLE_SIZE * 2) +
                        8 + /* bit set*/
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
