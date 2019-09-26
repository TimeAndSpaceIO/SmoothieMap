/* if Tracking hashCodeDistribution */
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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static java.lang.Math.min;

/**
 * Most of the reasoning from the class-level comment for {@link
 * BinomialDistributionInverseCdfApproximation} applies to this class. Whereas {@link
 * BinomialDistributionInverseCdfApproximation#inverseCumulativeProbability} makes a call into
 * {@link io.timeandspace.smoothie.Statistics.NormalDistribution#inverseCumulativeProbability} that
 * takes about 500 cycles, {@link #inverseCumulativeProbability}'s latency mainly comes from several
 * accesses to three large arrays (a second-level array in {@link
 * #NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS}, {@link
 * CdfValuesForNumSplits#cdfValues_offsetsForNumSplits}, and {@link CdfValuesForNumSplits#allCdfValues}). There
 * are no data dependency between the second level access to {@link
 * #NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS} and the access to {@link
 * CdfValuesForNumSplits#cdfValues_offsetsForNumSplits}, therefore, in the worst case, latency is determined
 * by L3 (the second-level access to {@link #NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS} _or_
 * the access to {@link CdfValuesForNumSplits#cdfValues_offsetsForNumSplits} + main memory (the access to
 * {@link  CdfValuesForNumSplits#allCdfValues} which is the largest array), or even main memory + main
 * memory. Sometimes binary search may hit multiple different cache lines in {@link
 * CdfValuesForNumSplits#allCdfValues}, resulting in total of three or even four main memory accesses:
 * the biggest {@code cdfValuesLengthForNumSplits} value (see {@link #inverseCumulativeProbability}
 * code) is 45, so the "embedded" array inside {@link CdfValuesForNumSplits#allCdfValues} of 4-byte
 * float elements spans 3 cache lines. (Note: all "L3" and "main memory" above are guesstimates of
 * what should fit in L3 of a modern server-class CPU; might be very off, I didn't evaluate that -
 * leventov.)
 *
 * How does that compare with {@link BinomialDistributionInverseCdfApproximation}'s 500 cycles
 * depends on the CPU frequency and the memory latency in CPU cycles. Two main memory accesses may
 * be faster or slower than 500 cycles, three or four are almost certainly slower. Despite of that,
 * we cannot make {@link #MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES} lower to call {@link
 * BinomialDistributionInverseCdfApproximation} more often because of the concerns about {@link
 * BinomialDistributionInverseCdfApproximation}'s precision, see a comment in {@link
 * BinomialDistributionInverseCdfApproximation#inverseCumulativeProbability}.
 *
 * TODO take advantage of high regularity of values in the "embedded" arrays inside {@link
 *  CdfValuesForNumSplits#allCdfValues} to make binary search that converges faster and most likely
 *  accesses just one cache line
 */
final class PrecomputedBinomialCdfValues {

    static final int MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES = 1023;

    /**
     * Returns max non-reported skewed segments for the given number of segment splits, the
     * specified probability of skewed segments as observed during splits, and the min required
     * confidence that an observed number of skewed segments is not occasional ("benign") and
     * therefore should be reported.
     *
      * The semantics and the interface of this method are parallel to those of {@link
     * BinomialDistributionInverseCdfApproximation#inverseCumulativeProbability}, but these methods
     * must be called on different ranges of numSplits: {@link PrecomputedBinomialCdfValues} when
     * the number of splits is less than or equal to {@link #MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES},
     * {@link BinomialDistributionInverseCdfApproximation} otherwise.
     *
     * @param probIndex index of probability in {@link
     *        HashCodeDistribution#HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}.
     *        This is the probability of success for binomial distribution.
     * @param numSplits a number of segment split events in a {@link SmoothieMap} of some order at
     *        some stage of a SmoothieMap's life. See the comment for {@link
     *        HashCodeDistribution#numSegmentSplitsToCurrentAverageOrder} for explanations.
     *        This number is used as the argument (N) for binomial distribution.
     * @param poorHashCodeDistribution_badOccasion_minRequiredConfidence the argument for inverse
     *        CDF function, equals to `1.0 - maxProbabilityOfOccasionIfHashFunctionWasRandom`, as
     *        specified in {@link SmoothieMapBuilder#reportPoorHashCodeDistribution}
     * @param numSkewedSegments_maxNonReported_prev some result returned from this method earlier
     *        for the same probIndex and poorHashCodeDistribution_badOccasion_minRequiredConfidence,
     *        but smaller numSplits than in this call. This value is used as a hint for this method,
     *        because the new result can't be smaller than the old one. Zero should be passed if
     *        inverseCumulativeProbability() hasn't yet been called with the same probIndex and
     *        poorHashCodeDistribution_badOccasion_minRequiredConfidence.
     * @return max non-reported skewed segments (inverse CDF result)
     */
    static int inverseCumulativeProbability(int probIndex, int numSplits,
            float poorHashCodeDistribution_badOccasion_minRequiredConfidence,
            int numSkewedSegments_maxNonReported_prev) {
        int numSkewedSegments_firstTrackedForNumSplits = Byte.toUnsignedInt(
                NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS[probIndex][numSplits - 1]);
        int nonReportedSkewedSegmentsToSearchFrom = Math.max(
                numSkewedSegments_maxNonReported_prev, numSkewedSegments_firstTrackedForNumSplits);
        CdfValuesForNumSplits cdfValuesForNumSplits = CDF_VALUES[probIndex];
        float[] cdfValues = cdfValuesForNumSplits.allCdfValues;
        int cdfValues_offsetForNumSplits =
                (int) cdfValuesForNumSplits.cdfValues_offsetsForNumSplits[numSplits - 1];
        int indexToSearchFrom =
                nonReportedSkewedSegmentsToSearchFrom - numSkewedSegments_firstTrackedForNumSplits;

        if (cdfValues[cdfValues_offsetForNumSplits + indexToSearchFrom] >=
                poorHashCodeDistribution_badOccasion_minRequiredConfidence) { // (*)
            return nonReportedSkewedSegmentsToSearchFrom; // see [Postcondition] below
        }

        int maxNonReportedSkewedSegmentsIndex;
        // Using exponential search (https://en.wikipedia.org/wiki/Exponential_search#Algorithm) to
        // find `maxNonReportedSkewedSegmentsIndex` because if the number of segments with skewed
        // distribution of entries between the halves is always on the edge of the reporting
        // probability threshold (maxProbabilityOfOccasionIfHashFunctionWasRandom) but doesn't cross
        // it (so that HashCodeDistribution.hasReportedTooManySkewedSegmentSplits is not set to
        // false and a SmoothieMap continues to track statistics and calculate probabilities) then
        // PrecomputedBinomialCdfValues.inverseCumulativeProbability() is called frequently with
        // `numSplits` argument not much larger than during the previous call and this method
        // returns results that are only a little larger than numSkewedSegments_maxNonReported_prev
        // (and sometimes are equal to it, that is covered by the short-circuit exit branch above),
        // exponential search makes this method relatively cheaper by ensuring that only a single
        // cache line within `cdfValues` is accessed (apart from the line with the array's header).
        {
            int bound = 1;

            int cdfValues_lengthForNumSplits =
                    (int) cdfValuesForNumSplits.cdfValues_offsetsForNumSplits[numSplits] -
                            cdfValues_offsetForNumSplits;
            while (indexToSearchFrom + bound < cdfValues_lengthForNumSplits &&
                    // If `cdfValues[cdfValues_offsetForNumSplits + indexToSearchFrom + bound] ==
                    //         poorHashCodeDistribution_badOccasion_minRequiredConfidence`, this
                    // while loop exits and the bound index will be "rediscovered" in binarySearch()
                    // below. There is no point in making special-case code because values in
                    // `cdfValues` array are not round and it's very unlikely that a user specifies
                    // maxProbabilityOfOccasionIfHashFunctionWasRandom in
                    // reportPoorHashCodeDistribution() that the complement probability corresponds
                    // exactly to any of those cdf values.
                    cdfValues[cdfValues_offsetForNumSplits + indexToSearchFrom + bound] <
                            poorHashCodeDistribution_badOccasion_minRequiredConfidence) {
                bound *= 2;
            }

            maxNonReportedSkewedSegmentsIndex = binarySearch(
                    cdfValues,
                    cdfValues_offsetForNumSplits,
                    // Could have been indexToSearchFrom + max(1, (bound >>> 1)) because
                    // cdfValues[cdfValues_offsetForNumSplits + indexToSearchFrom] is checked to be
                    // less than poorHashCodeDistribution_badOccasion_minRequiredConfidence in this
                    // branch (see (*) above), so this binarySearch() call will never return
                    // indexToSearchFrom. But the benefit doesn't worth the extra logic.
                    indexToSearchFrom + (bound >>> 1),
                    min(indexToSearchFrom + bound + 1, cdfValues_lengthForNumSplits),
                    poorHashCodeDistribution_badOccasion_minRequiredConfidence);
        }

        //noinspection UnnecessaryLocalVariable
        int maxNonReportedSkewedSegments =
                numSkewedSegments_firstTrackedForNumSplits + maxNonReportedSkewedSegmentsIndex;
        // Postcondition: CDF[maxNonReportedSkewedSegments] >=
        //                    poorHashCodeDistribution_badOccasion_minRequiredConfidence
        return maxNonReportedSkewedSegments;
    }

    /**
     * Simplified version of {@link java.util.Arrays#binarySearch(float[], int, int, float)} that
     * doesn't make positive/negative zero and NaN-related checks (because it's not possible in
     * {@link #inverseCumulativeProbability}) and doesn't invert the insertion index before
     * returning (exactly what's needed in {@link #inverseCumulativeProbability}).
     *
     * The code is based on fastutil's binary search.
     */
    @VisibleForTesting
    static int binarySearch(float[] a, int indexOffset, int indexFrom, int indexTo, float key) {
        indexTo--;
        while (indexFrom <= indexTo) {
            final int mid = (indexFrom + indexTo) >>> 1;
            float midVal = a[indexOffset + mid];
            if (midVal < key) {
                indexFrom = mid + 1;
            } else if (midVal > key) {
                indexTo = mid - 1;
            } else {
                return mid;
            }
        }
        return indexFrom;
    }

    /**
     * Each value in {@link #cdfValues_offsetsForNumSplits} (except the last one) identifies the
     * offset to an "embedded" (flattened) array inside {@link #allCdfValues}. Each "embedded" array
     * inside {@link #allCdfValues} corresponds to one number of segment splits, "n" or "numTrials"
     * in binomial distribution terms.
     *
     * Each value in an "embedded" array inside {@link #allCdfValues} corresponds to cumulative
     * probability of having some number of skewed segments (from 0 to numSplits - 1). In order to
     * save space in memory and reduce the library size not all possible numSkewedSegments are
     * represented; only those whose cumulative probabilities fall into the range between
     * 1.0 - {@link SmoothieMap#POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MAX}
     * and
     * 1.0 - {@link SmoothieMap#POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MIN}.
     *
     * Values in {@link #NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS} help to identify the
     * starting  numSkewedSegments of this "window" for each numSplits (see the code in {@link
     * #inverseCumulativeProbability}).
     *
     * @see #CDF_VALUES
     */
    private static class CdfValuesForNumSplits {
        private final char[] cdfValues_offsetsForNumSplits =
                new char[MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES + 1];
        private final float[] allCdfValues;

        CdfValuesForNumSplits(String filePrefix) {
            try (DataInputStream offsetsIn = new DataInputStream(
                    getClass().getResourceAsStream(filePrefix + ".offsets"))) {
                byte[] offsetsData =
                        new byte[cdfValues_offsetsForNumSplits.length * Character.BYTES];
                offsetsIn.readFully(offsetsData);
                ByteBuffer
                        .wrap(offsetsData)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asCharBuffer()
                        .get(cdfValues_offsetsForNumSplits);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            int allCdfValuesLength =
                    (int) cdfValues_offsetsForNumSplits[cdfValues_offsetsForNumSplits.length - 1];
            allCdfValues = new float[allCdfValuesLength];

            try (DataInputStream cdfValuesIn = new DataInputStream(
                    getClass().getResourceAsStream(filePrefix + ".cdfValues"))) {
                byte[] cdfValuesData = new byte[allCdfValues.length * Float.BYTES];
                cdfValuesIn.readFully(cdfValuesData);
                ByteBuffer
                        .wrap(cdfValuesData)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(allCdfValues);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // TODO test that all arrays are sorted

    // TODO test 7-8 transition separately

    /**
     * Each inner array corresponds to one probability of skewed segments (one of values from {@link
     * HashCodeDistribution#HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}), "p" or
     * "probabilityOfSuccess" in binomial distribution terms.
     *
     * Each value in inner arrays corresponds to one "embedded" array inside {@link
     * CdfValuesForNumSplits#allCdfValues}. The values are the numbers of skewed segments ("k", in
     * binomial distribution terms) that have cumulative probability equal to the first element in
     * the corresponding "embedded" array inside {@link CdfValuesForNumSplits#allCdfValues}.
     *
     * Computed and printed in {@link ComputeBinomialCdfValues#computeBinomialCdfValues}.
     *
     * TODO move inner arrays into {@link CdfValuesForNumSplits}
     */
    static final byte[][] NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS = {
            // Prob: 0.19341265286193732
            {0, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 6, 7, 7, 7, 7,
                    8, 8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 10, 11, 11, 11, 11, 12, 12, 12, 12,
                    12, 13, 13, 13, 13, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 16, 16, 16, 16, 17,
                    17, 17, 17, 17, 18, 18, 18, 18, 18, 19, 19, 19, 19, 19, 20, 20, 20, 20, 21, 21,
                    21, 21, 21, 22, 22, 22, 22, 22, 23, 23, 23, 23, 23, 24, 24, 24, 24, 25, 25, 25,
                    25, 25, 26, 26, 26, 26, 26, 27, 27, 27, 27, 27, 28, 28, 28, 28, 28, 29, 29, 29,
                    29, 30, 30, 30, 30, 30, 31, 31, 31, 31, 31, 32, 32, 32, 32, 32, 33, 33, 33, 33,
                    33, 34, 34, 34, 34, 34, 35, 35, 35, 35, 36, 36, 36, 36, 36, 37, 37, 37, 37, 37,
                    38, 38, 38, 38, 38, 39, 39, 39, 39, 39, 40, 40, 40, 40, 40, 41, 41, 41, 41, 42,
                    42, 42, 42, 42, 43, 43, 43, 43, 43, 44, 44, 44, 44, 44, 45, 45, 45, 45, 45, 46,
                    46, 46, 46, 46, 47, 47, 47, 47, 47, 48, 48, 48, 48, 48, 49, 49, 49, 49, 49, 50,
                    50, 50, 50, 51, 51, 51, 51, 51, 52, 52, 52, 52, 52, 53, 53, 53, 53, 53, 54, 54,
                    54, 54, 54, 55, 55, 55, 55, 55, 56, 56, 56, 56, 56, 57, 57, 57, 57, 57, 58, 58,
                    58, 58, 58, 59, 59, 59, 59, 59, 60, 60, 60, 60, 60, 61, 61, 61, 61, 62, 62, 62,
                    62, 62, 63, 63, 63, 63, 63, 64, 64, 64, 64, 64, 65, 65, 65, 65, 65, 66, 66, 66,
                    66, 66, 67, 67, 67, 67, 67, 68, 68, 68, 68, 68, 69, 69, 69, 69, 69, 70, 70, 70,
                    70, 70, 71, 71, 71, 71, 71, 72, 72, 72, 72, 72, 73, 73, 73, 73, 73, 74, 74, 74,
                    74, 74, 75, 75, 75, 75, 76, 76, 76, 76, 76, 77, 77, 77, 77, 77, 78, 78, 78, 78,
                    78, 79, 79, 79, 79, 79, 80, 80, 80, 80, 80, 81, 81, 81, 81, 81, 82, 82, 82, 82,
                    82, 83, 83, 83, 83, 83, 84, 84, 84, 84, 84, 85, 85, 85, 85, 85, 86, 86, 86, 86,
                    86, 87, 87, 87, 87, 87, 88, 88, 88, 88, 88, 89, 89, 89, 89, 89, 90, 90, 90, 90,
                    90, 91, 91, 91, 91, 91, 92, 92, 92, 92, 92, 93, 93, 93, 93, 93, 94, 94, 94, 94,
                    94, 95, 95, 95, 95, 95, 96, 96, 96, 96, 96, 97, 97, 97, 97, 97, 98, 98, 98, 98,
                    98, 99, 99, 99, 99, 99, 100, 100, 100, 100, 100, 101, 101, 101, 101, 101, 102,
                    102, 102, 102, 103, 103, 103, 103, 103, 104, 104, 104, 104, 104, 105, 105, 105,
                    105, 105, 106, 106, 106, 106, 106, 107, 107, 107, 107, 107, 108, 108, 108, 108,
                    108, 109, 109, 109, 109, 109, 110, 110, 110, 110, 110, 111, 111, 111, 111, 111,
                    112, 112, 112, 112, 112, 113, 113, 113, 113, 113, 114, 114, 114, 114, 114, 115,
                    115, 115, 115, 115, 116, 116, 116, 116, 116, 117, 117, 117, 117, 117, 118, 118,
                    118, 118, 118, 119, 119, 119, 119, 119, 120, 120, 120, 120, 120, 121, 121, 121,
                    121, 121, 122, 122, 122, 122, 122, 123, 123, 123, 123, 123, 124, 124, 124, 124,
                    124, 125, 125, 125, 125, 125, 126, 126, 126, 126, 126, 127, 127, 127, 127, 127,
                    //CHECKSTYLE:OFF: LineLength
                    (byte) 128, (byte) 128, (byte) 128, (byte) 128, (byte) 128, (byte) 129, (byte) 129, (byte) 129, (byte) 129, (byte) 129, (byte) 130, (byte) 130,
                    (byte) 130, (byte) 130, (byte) 130, (byte) 131, (byte) 131, (byte) 131, (byte) 131, (byte) 131, (byte) 132, (byte) 132, (byte) 132, (byte) 132,
                    (byte) 132, (byte) 133, (byte) 133, (byte) 133, (byte) 133, (byte) 133, (byte) 134, (byte) 134, (byte) 134, (byte) 134, (byte) 134, (byte) 135,
                    (byte) 135, (byte) 135, (byte) 135, (byte) 135, (byte) 136, (byte) 136, (byte) 136, (byte) 136, (byte) 136, (byte) 137, (byte) 137, (byte) 137,
                    (byte) 137, (byte) 137, (byte) 138, (byte) 138, (byte) 138, (byte) 138, (byte) 138, (byte) 139, (byte) 139, (byte) 139, (byte) 139, (byte) 139,
                    (byte) 140, (byte) 140, (byte) 140, (byte) 140, (byte) 140, (byte) 141, (byte) 141, (byte) 141, (byte) 141, (byte) 141, (byte) 142, (byte) 142,
                    (byte) 142, (byte) 142, (byte) 142, (byte) 143, (byte) 143, (byte) 143, (byte) 143, (byte) 143, (byte) 144, (byte) 144, (byte) 144, (byte) 144,
                    (byte) 144, (byte) 145, (byte) 145, (byte) 145, (byte) 145, (byte) 145, (byte) 146, (byte) 146, (byte) 146, (byte) 146, (byte) 146, (byte) 147,
                    (byte) 147, (byte) 147, (byte) 147, (byte) 147, (byte) 148, (byte) 148, (byte) 148, (byte) 148, (byte) 148, (byte) 149, (byte) 149, (byte) 149,
                    (byte) 149, (byte) 149, (byte) 150, (byte) 150, (byte) 150, (byte) 150, (byte) 150, (byte) 151, (byte) 151, (byte) 151, (byte) 151, (byte) 151,
                    (byte) 152, (byte) 152, (byte) 152, (byte) 152, (byte) 152, (byte) 153, (byte) 153, (byte) 153, (byte) 153, (byte) 153, (byte) 154, (byte) 154,
                    (byte) 154, (byte) 154, (byte) 154, (byte) 155, (byte) 155, (byte) 155, (byte) 155, (byte) 155, (byte) 156, (byte) 156, (byte) 156, (byte) 156,
                    (byte) 156, (byte) 157, (byte) 157, (byte) 157, (byte) 157, (byte) 157, (byte) 158, (byte) 158, (byte) 158, (byte) 158, (byte) 158, (byte) 159,
                    (byte) 159, (byte) 159, (byte) 159, (byte) 159, (byte) 160, (byte) 160, (byte) 160, (byte) 160, (byte) 160, (byte) 161, (byte) 161, (byte) 161,
                    (byte) 161, (byte) 161, (byte) 162, (byte) 162, (byte) 162, (byte) 162, (byte) 162, (byte) 163, (byte) 163, (byte) 163, (byte) 163, (byte) 163,
                    (byte) 164, (byte) 164, (byte) 164, (byte) 164, (byte) 164, (byte) 165, (byte) 165, (byte) 165, (byte) 165, (byte) 165, (byte) 165, (byte) 166,
                    (byte) 166, (byte) 166, (byte) 166, (byte) 166, (byte) 167, (byte) 167, (byte) 167, (byte) 167, (byte) 167, (byte) 168, (byte) 168, (byte) 168,
                    (byte) 168, (byte) 168, (byte) 169, (byte) 169, (byte) 169, (byte) 169, (byte) 169, (byte) 170, (byte) 170, (byte) 170, (byte) 170, (byte) 170,
                    (byte) 171, (byte) 171, (byte) 171, (byte) 171, (byte) 171, (byte) 172, (byte) 172, (byte) 172, (byte) 172, (byte) 172, (byte) 173, (byte) 173,
                    (byte) 173, (byte) 173, (byte) 173, (byte) 174, (byte) 174, (byte) 174, (byte) 174, (byte) 174, (byte) 175, (byte) 175, (byte) 175, (byte) 175,
                    (byte) 175, (byte) 176, (byte) 176, (byte) 176, (byte) 176, (byte) 176, (byte) 177, (byte) 177, (byte) 177, (byte) 177, (byte) 177, (byte) 178,
                    (byte) 178, (byte) 178, (byte) 178, (byte) 178, (byte) 179, (byte) 179, (byte) 179, (byte) 179, (byte) 179, (byte) 180, (byte) 180, (byte) 180,
                    (byte) 180, (byte) 180, (byte) 181, (byte) 181, (byte) 181, (byte) 181, (byte) 181, (byte) 182, (byte) 182, (byte) 182, (byte) 182, (byte) 182,
                    (byte) 183, (byte) 183, (byte) 183, (byte) 183, (byte) 183, (byte) 184, (byte) 184, (byte) 184, (byte) 184, (byte) 184, (byte) 185, (byte) 185,
                    (byte) 185, (byte) 185, (byte) 185, (byte) 186, (byte) 186, (byte) 186, (byte) 186, (byte) 186, (byte) 187, (byte) 187, (byte) 187, (byte) 187,
                    (byte) 187, (byte) 188, (byte) 188, (byte) 188, (byte) 188, (byte) 188, (byte) 189, (byte) 189, (byte) 189, (byte) 189, (byte) 189, (byte) 190,
                    (byte) 190, (byte) 190, (byte) 190, (byte) 190, (byte) 191, (byte) 191, (byte) 191, (byte) 191, (byte) 191, (byte) 192, (byte) 192, (byte) 192,
                    (byte) 192, (byte) 192, (byte) 193, (byte) 193, (byte) 193, (byte) 193, (byte) 193, (byte) 194, (byte) 194, (byte) 194, (byte) 194, (byte) 194,
                    (byte) 195, (byte) 195, (byte) 195, (byte) 195, (byte) 195, (byte) 196, (byte) 196, (byte) 196, (byte) 196, (byte) 196, (byte) 197, (byte) 197,
                    (byte) 197, (byte) 197, (byte) 197, (byte) 198, (byte) 198, (byte) 198, (byte) 198, (byte) 198, (byte) 199, (byte) 199, (byte) 199, (byte) 199,
                    (byte) 199, (byte) 200, (byte) 200, (byte) 200, (byte) 200, (byte) 200, (byte) 201, (byte) 201, (byte) 201, (byte) 201, (byte) 201, (byte) 202,
                    (byte) 202, (byte) 202, (byte) 202, (byte) 202, (byte) 203, (byte) 203, (byte) 203, (byte) 203, (byte) 203, (byte) 203, (byte) 204, (byte) 204,
                    (byte) 204, (byte) 204, (byte) 204, (byte) 205, (byte) 205, (byte) 205, (byte) 205, (byte) 205, (byte) 206, (byte) 206, (byte) 206, (byte) 206,
                    (byte) 206, (byte) 207, (byte) 207, (byte) 207, (byte) 207, (byte) 207, (byte) 208, (byte) 208, (byte) 208, (byte) 208, (byte) 208},
                    //CHECKSTYLE:ON: LineLength

            // Prob: 0.11140289106101875
            {0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 5,
                    5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8,
                    8, 8, 9, 9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11,
                    11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13, 13, 13,
                    14, 14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15, 15, 16, 16, 16, 16,
                    16, 16, 16, 16, 17, 17, 17, 17, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18,
                    19, 19, 19, 19, 19, 19, 19, 19, 20, 20, 20, 20, 20, 20, 20, 20, 21, 21, 21, 21,
                    21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 22, 23, 23, 23, 23, 23, 23, 23,
                    23, 24, 24, 24, 24, 24, 24, 24, 24, 25, 25, 25, 25, 25, 25, 25, 25, 26, 26, 26,
                    26, 26, 26, 26, 26, 26, 27, 27, 27, 27, 27, 27, 27, 27, 28, 28, 28, 28, 28, 28,
                    28, 28, 29, 29, 29, 29, 29, 29, 29, 29, 29, 30, 30, 30, 30, 30, 30, 30, 30, 31,
                    31, 31, 31, 31, 31, 31, 31, 32, 32, 32, 32, 32, 32, 32, 32, 32, 33, 33, 33, 33,
                    33, 33, 33, 33, 34, 34, 34, 34, 34, 34, 34, 34, 35, 35, 35, 35, 35, 35, 35, 35,
                    35, 36, 36, 36, 36, 36, 36, 36, 36, 37, 37, 37, 37, 37, 37, 37, 37, 37, 38, 38,
                    38, 38, 38, 38, 38, 38, 39, 39, 39, 39, 39, 39, 39, 39, 40, 40, 40, 40, 40, 40,
                    40, 40, 40, 41, 41, 41, 41, 41, 41, 41, 41, 42, 42, 42, 42, 42, 42, 42, 42, 42,
                    43, 43, 43, 43, 43, 43, 43, 43, 44, 44, 44, 44, 44, 44, 44, 44, 44, 45, 45, 45,
                    45, 45, 45, 45, 45, 46, 46, 46, 46, 46, 46, 46, 46, 47, 47, 47, 47, 47, 47, 47,
                    47, 47, 48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 49, 49, 49, 49, 49, 49, 49, 50,
                    50, 50, 50, 50, 50, 50, 50, 51, 51, 51, 51, 51, 51, 51, 51, 51, 52, 52, 52, 52,
                    52, 52, 52, 52, 53, 53, 53, 53, 53, 53, 53, 53, 53, 54, 54, 54, 54, 54, 54, 54,
                    54, 55, 55, 55, 55, 55, 55, 55, 55, 55, 56, 56, 56, 56, 56, 56, 56, 56, 57, 57,
                    57, 57, 57, 57, 57, 57, 57, 58, 58, 58, 58, 58, 58, 58, 58, 59, 59, 59, 59, 59,
                    59, 59, 59, 59, 60, 60, 60, 60, 60, 60, 60, 60, 61, 61, 61, 61, 61, 61, 61, 61,
                    61, 62, 62, 62, 62, 62, 62, 62, 62, 63, 63, 63, 63, 63, 63, 63, 63, 63, 64, 64,
                    64, 64, 64, 64, 64, 64, 65, 65, 65, 65, 65, 65, 65, 65, 65, 66, 66, 66, 66, 66,
                    66, 66, 66, 67, 67, 67, 67, 67, 67, 67, 67, 67, 68, 68, 68, 68, 68, 68, 68, 68,
                    68, 69, 69, 69, 69, 69, 69, 69, 69, 70, 70, 70, 70, 70, 70, 70, 70, 70, 71, 71,
                    71, 71, 71, 71, 71, 71, 72, 72, 72, 72, 72, 72, 72, 72, 72, 73, 73, 73, 73, 73,
                    73, 73, 73, 74, 74, 74, 74, 74, 74, 74, 74, 74, 75, 75, 75, 75, 75, 75, 75, 75,
                    76, 76, 76, 76, 76, 76, 76, 76, 76, 77, 77, 77, 77, 77, 77, 77, 77, 77, 78, 78,
                    78, 78, 78, 78, 78, 78, 79, 79, 79, 79, 79, 79, 79, 79, 79, 80, 80, 80, 80, 80,
                    80, 80, 80, 81, 81, 81, 81, 81, 81, 81, 81, 81, 82, 82, 82, 82, 82, 82, 82, 82,
                    83, 83, 83, 83, 83, 83, 83, 83, 83, 84, 84, 84, 84, 84, 84, 84, 84, 84, 85, 85,
                    85, 85, 85, 85, 85, 85, 86, 86, 86, 86, 86, 86, 86, 86, 86, 87, 87, 87, 87, 87,
                    87, 87, 87, 88, 88, 88, 88, 88, 88, 88, 88, 88, 89, 89, 89, 89, 89, 89, 89, 89,
                    89, 90, 90, 90, 90, 90, 90, 90, 90, 91, 91, 91, 91, 91, 91, 91, 91, 91, 92, 92,
                    92, 92, 92, 92, 92, 92, 93, 93, 93, 93, 93, 93, 93, 93, 93, 94, 94, 94, 94, 94,
                    94, 94, 94, 94, 95, 95, 95, 95, 95, 95, 95, 95, 96, 96, 96, 96, 96, 96, 96, 96,
                    96, 97, 97, 97, 97, 97, 97, 97, 97, 98, 98, 98, 98, 98, 98, 98, 98, 98, 99, 99,
                    99, 99, 99, 99, 99, 99, 99, 100, 100, 100, 100, 100, 100, 100, 100, 101, 101,
                    101, 101, 101, 101, 101, 101, 101, 102, 102, 102, 102, 102, 102, 102, 102, 102,
                    103, 103, 103, 103, 103, 103, 103, 103, 104, 104, 104, 104, 104, 104, 104, 104,
                    104, 105, 105, 105, 105, 105, 105, 105, 105, 106, 106, 106, 106, 106, 106, 106,
                    106, 106, 107, 107, 107, 107, 107, 107, 107, 107, 107, 108, 108, 108, 108, 108,
                    108, 108, 108, 109, 109, 109, 109, 109, 109, 109, 109, 109, 110, 110, 110, 110,
                    110, 110, 110, 110, 110, 111, 111, 111, 111, 111, 111, 111, 111, 112, 112, 112,
                    112, 112, 112, 112, 112, 112, 113, 113, 113, 113, 113, 113, 113, 113, 113, 114,
                    114, 114, 114, 114, 114, 114, 114, 115, 115, 115, 115, 115, 115, 115, 115, 115,
                    116, 116, 116, 116, 116, 116, 116, 116, 116, 117, 117, 117, 117, 117, 117, 117,
                    117, 118, 118, 118, 118, 118, 118, 118, 118, 118, 119, 119, 119, 119, 119, 119,
                    119, 119, 120, 120, 120, 120, 120, 120, 120, 120, 120, 121, 121, 121, 121, 121,
                    121, 121, 121, 121, 122, 122, 122, 122, 122, 122, 122, 122},

            // Prob: 0.05946337525377032
            {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3,
                    3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5,
                    5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7,
                    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9,
                    9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
                    10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 12,
                    12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13,
                    13, 13, 13, 13, 13, 13, 13, 13, 13, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14,
                    14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 16,
                    16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 17, 17,
                    17, 17, 17, 17, 17, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18,
                    18, 18, 18, 18, 18, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19,
                    20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 21, 21, 21, 21, 21,
                    21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 22, 22,
                    22, 22, 22, 22, 22, 22, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23,
                    23, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 25, 25, 25,
                    25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 26, 26, 26, 26, 26, 26, 26, 26,
                    26, 26, 26, 26, 26, 26, 26, 26, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27,
                    27, 27, 27, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 29,
                    29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 30, 30, 30, 30, 30, 30,
                    30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31,
                    31, 31, 31, 31, 31, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
                    32, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 34, 34, 34,
                    34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 35, 35, 35, 35, 35, 35, 35, 35,
                    35, 35, 35, 35, 35, 35, 35, 35, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36,
                    36, 36, 36, 36, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 37, 38,
                    38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 38, 39, 39, 39, 39, 39,
                    39, 39, 39, 39, 39, 39, 39, 39, 39, 39, 39, 40, 40, 40, 40, 40, 40, 40, 40, 40,
                    40, 40, 40, 40, 40, 40, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41,
                    41, 41, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 43, 43,
                    43, 43, 43, 43, 43, 43, 43, 43, 43, 43, 43, 43, 43, 43, 44, 44, 44, 44, 44, 44,
                    44, 44, 44, 44, 44, 44, 44, 44, 44, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45,
                    45, 45, 45, 45, 45, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 46,
                    46, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 47, 48, 48, 48,
                    48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 49, 49, 49, 49, 49,
                    49, 49, 49, 49, 49, 49, 49, 49, 49, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50,
                    50, 50, 50, 50, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51,
                    52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 52, 53, 53, 53, 53,
                    53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 54, 54, 54, 54, 54, 54, 54, 54,
                    54, 54, 54, 54, 54, 54, 54, 54, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55,
                    55, 55, 55, 55, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56, 56,
                    57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 58, 58, 58, 58,
                    58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 59, 59, 59, 59, 59, 59, 59, 59,
                    59, 59, 59, 59, 59, 59, 59, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60,
                    60, 60, 60, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 61, 62,
                    62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 62, 63, 63, 63, 63, 63,
                    63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64,
                    64, 64, 64, 64, 64, 64, 64, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65,
                    65, 65, 65, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 67,
                    67, 67, 67, 67, 67, 67, 67, 67, 67, 67},

            // Prob: 0.02930494672052930127392
            {0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2,
                    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3,
                    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4,
                    4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5,
                    5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6,
                    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7,
                    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8,
                    8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
                    8, 8, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
                    9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
                    10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11,
                    11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11,
                    11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
                    12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13, 13,
                    13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13,
                    13, 13, 13, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14,
                    14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15,
                    15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15,
                    15, 15, 15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
                    16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 17, 17,
                    17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17,
                    17, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18,
                    18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 19, 19, 19, 19,
                    19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19,
                    19, 19, 19, 19, 19, 19, 19, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20,
                    20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 21, 21,
                    21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21,
                    21, 21, 21, 21, 21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
                    22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
                    23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23,
                    23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 24, 24, 24, 24, 24, 24, 24, 24, 24,
                    24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
                    24, 24, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
                    25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 26, 26, 26, 26, 26, 26,
                    26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
                    26, 26, 26, 26, 26, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27,
                    27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 28, 28, 28, 28,
                    28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
                    28, 28, 28, 28, 28, 28, 28, 28, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29,
                    29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 30,
                    30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                    30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 31, 31, 31, 31, 31, 31, 31, 31, 31,
                    31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31,
                    31, 31, 31, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
                    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 33, 33, 33, 33, 33, 33,
                    33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33,
                    33, 33, 33, 33, 33, 33, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34,
                    34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34, 34}
    };

    /**
     * Each {@link CdfValuesForNumSplits} object in this array corresponds to one probability of
     * skewed segments (one of values from {@link
     * HashCodeDistribution#HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}), "p" or
     * "probabilityOfSuccess" in binomial distribution terms.
     *
     * Computed and printed in {@link ComputeBinomialCdfValues#computeBinomialCdfValues}.
     */
    static final CdfValuesForNumSplits[] CDF_VALUES = {
            // Prob: 0.19341265286193732, at least 29/19 entries in skewed splits
            new CdfValuesForNumSplits("0"),
            // Prob: 0.11140289106101875, at least 30/18 entries in skewed splits
            new CdfValuesForNumSplits("1"),
            // Prob: 0.05946337525377032, at least 31/17 entries in skewed splits
            new CdfValuesForNumSplits("2"),
            // Prob: 0.02930494672052930, at least 32/16 entries in skewed splits
            new CdfValuesForNumSplits("3")
    };

    // TODO move these checks to tests and verify values in CdfValuesPerSplits according to
    //  ComputeBinomialCdfValues
//    static {
//        for (int i = 0; i < 4; i++) {
//            // Comparing array lengths with MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES rather than with
//            // MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES + 1 because precomputed arrays start with
//            // splits = 1.
//            if (CDF_VALUES[i].length != MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES) {
//                throw new AssertionError();
//            }
//            if (FIRST_TRACKED_NUM_SKEWED_SEGMENTS_PER_SPLITS[i].length !=
//                    MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES) {
//                throw new AssertionError();
//            }
//        }
//    }

    private PrecomputedBinomialCdfValues() {}
}
