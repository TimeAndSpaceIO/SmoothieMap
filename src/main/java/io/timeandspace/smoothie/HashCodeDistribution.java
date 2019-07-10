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

import io.timeandspace.smoothie.SmoothieMap.Segment;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.timeandspace.smoothie.HashTable.HASH_TABLE_SLOTS;
import static io.timeandspace.smoothie.SmoothieMap.maxSplittableSegmentOrder;
import static io.timeandspace.smoothie.Statistics.PoissonDistribution;
import static java.lang.Math.max;

/**
 * This class contains the parts of the {@link SmoothieMap}'s state that are only ever accessed if
 * poor hash code distribution events need to be reported.
 *
 * The objective of this class is minimization of the memory footprint of SmoothieMaps that don't
 * track poor hash code distribution events.
 */
class HashCodeDistribution<K, V> {
    /** @see #HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS */
    private static final int SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED =
            3;
    static final int SKEWED_SEGMENT__HASH_TABLE_HALF__MAX_KEYS__MIN_ACCOUNTED =
            (HASH_TABLE_SLOTS / 2) -
                    SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED;

    /**
     * numSkewedSplits + lastComputedMaxNonReportedSkewedSplits. See {@link
     * #skewedSegment_splitStatsToCurrentAverageOrder}.
     */
    private static final int SKEWED_SEGMENT__SPLIT_STAT_SIZE = 2;
    private static final int SKEWED_SEGMENT__SPLIT_STATS__INT_ARRAY_LENGTH =
            (SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED + 1) *
                    SKEWED_SEGMENT__SPLIT_STAT_SIZE;

    /**
     * The number of elements in this array corresponds to {@link
     * #SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED} + 1.
     *
     * A value at index `i` is a cumulative distribution of the event that a segment split ({@link
     * SmoothieMap#split}) observes at least 29 + i keys that should have been fallen in either
     * the lower or the higher half of the segment's hash table (and, conversely, at most 19 - i
     * keys should have been fallen in the less populated half). It is called a "skewness level"
     * below in this class.
     *
     * We don't account stats for events of 28 or less keys falling in a hash table's half for three
     * reasons:
     *
     *  1) the probability of such events is too large (~0.31 for 28 keys), so
     *  HashCodeDistribution's methods would be called too often from {@link SmoothieMap#split},
     *  contributing more significantly to the average CPU cost of the latter.
     *
     *  2) Both {@link #skewedSegment_splitStatsToCurrentAverageOrder} and {@link
     *  #skewedSegment_splitStatsToNextAverageOrder} would need to hold a stat for one more
     *  skewness level, contributing to the footprint of SmoothieMaps.
     *
     *  3) On the other hand, 28 keys in a hash table's half (of 32 slots) means that there should
     *  be 4 empty slots "on average" (not considering that some of them may be taken by shifted
     *  keys from the other half), i. e. one empty slot per a 8-slot group on average (see {@link
     *  SmoothieMap.HashTableArea}).
     *
     *  At this (or smaller) load factor the performance of a SwissTable suffers much less than at
     *  higher load factors, because SwissTable examines the slots in groups. For the performance of
     *  successful key search, it doesn't matter if a key is not shifted at or is shifted by one,
     *  two, or seven slots. The performance of unsuccessful search and entry insertion depends on
     *  the fact that each 8-slot group has at least one empty slot that allows to stop the search
     *  after checking the first group.
     *
     *  So although having unusually many segments in a SmoothieMap with hash tables that have 28
     *  (or 27, or 26, or 25) keys in one of their halves does indicate some problem with hash code
     *  distribution, this is effectively harmless for a SmoothieMap and thus it doesn't make much
     *  sense to try to spot and report such problems unless a SmoothieMap is used specifically
     *  to test the quality of hash code distribution (that is not a target use case).
     *
     * Conversely, we don't account stats for events of 33 or more keys falling in a hash table's
     * half (better to say - should have fallen, because 33 keys can't all reside in their "origin"
     * 32-slot half, at least one key will be shifted to another half) separately from the previous
     * four buckets (they aggregate all splits of segments with levels of skewness higher than
     * theirs, so they all include segments of 33+ keys in a half).
     * TODO try to understand if there any reason to not account stats for 33 or more keys other
     *  than limiting memory footprint of stats arrays (as in reason #2 for not accounting stats for
     *  28 or less keys). Specifically, since no more than 32 keys can fit a single half anyway,
     *  don't 33 or more keys add relatively less harm than 30->31, 31->32 keys? Check empirically?
     */
    static final
    double[] HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS =
            new double[] { // Below, splitsDistribution = BinomialDistribution[48, 0.5]
                    0.1934126528619373175388, // 2 * (1 - CDF[splitsDistribution, 28])
                    0.1114028910610187494967,  // 2 * (1 - CDF[splitsDistribution, 29])
                    0.05946337525377032307006,  // 2 * (1 - CDF[splitsDistribution, 30])
                    0.02930494672052930127392 }; // 2 * (1 - CDF[splitsDistribution, 31])

    static {
        if (SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED + 1 !=
                HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS
                        .length) {
            throw new AssertionError();
        }
    }

    final float poorHashCodeDistrib_badOccasion_minReportingProb;
    final Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction;

    /**
     * Whether to report a poor hash code distribution event when there are so many segments in the
     * SmoothieMap with entries concentrated in either the lower or the higher half of a hash table
     * (determined during {@link SmoothieMap#split}) that statistical probability of this event
     * (assuming the hash function was truly random) is below {@code
     * maxProbabilityOfOccasionIfHashFunctionWasRandom} passed into {@link
     * SmoothieMapBuilder#reportPoorHashCodeDistribution}.
     *
     * There should be a bias in the {@link Segment#HASH__BASE_GROUP_INDEX_BITS}-th bit (i. e. the
     * third bit, or the bit number 2 if zero-indexed) of hash codes, either global: most hash codes
     * of keys in the SmoothieMap have either 0 or 1 in the third bit, or there is a correlation
     * between the third bit and one of the bits that are used to determine to which segment a key
     * with a hash code should go (see {@link SmoothieMap#segmentLookupBits} and {@link
     * SmoothieMap#firstSegmentIndexByHashAndOrder}), i. e. a correlation with the {@link
     * SmoothieMap#SEGMENT_LOOKUP_HASH_SHIFT}-th bit or any higher bit, or a combination of some of
     * these bits.
     *
     * TODO determine the correlating mask of the higher bits by maintaining exponential +1/-1
     *  windows per each bit.
     */
    private boolean hasReportedTooManySkewedSegmentSplits = true;
    boolean reportTooManyInflatedSegments = true;
    private boolean reportTooLargeInflatedSegment = true;

    private int numInflatedSegments;
    private byte minReportedNumInflatedSegments;
    private byte segmentWithAverageOrder_maxNonReportedSize_lastComputed;
    private byte lastAverageSegmentOrderForWhichMaxNonReportedSizeIsComputed;
    private long minMapSizeForWhichLastComputedMaxNonReportedSegmentSizeIsValid;

    /**
     * The following four fields contain statistics of segment splits: "Current" are about the
     * splits of segments of the order equal to the current average segment order - 1 into two
     * segments of the current average segment order, "Next" are about the splits of segments of the
     * current average segment order into two segments of the order equal to the current average
     * segment order + 1. When the current average segment order is updated, statistics are rotated:
     * "Next" becomes "Current" when the average segment order increases, or vice versa when the
     * average segment order decreases, and the other facet is zeroed out respectively.
     *
     * Some information is lost: statistics for some order represent splits at the current stage of
     * the SmoothieMap's lifetime, rather than all splits of segments of the given order that have
     * happened in the SmoothieMap during its entire lifetime. This is done for optimization (allows
     * to not keep an array of statistics corresponding to all orders), and also this approach is
     * perhaps actually better for identifying distribution anomalies in hash codes: on different
     * stages of a SmoothieMap's lifetime properties of keys (more specifically, the quality of
     * distribution of their hash codes) in a SmoothieMap may be different.
     */
    int numSegmentSplitsToCurrentAverageOrder;
    /**
     * An array with {@link #SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED}
     * "stats" (each corresponding to one skewness level), each stat is comprised of two "fields":
     *   [0] numSkewedSplits: 4-byte integer, the number of segment splits during which it was
     *       observed that at least 29 + statIndex keys have been fallen in either the lower or
     *       the higher half of a segment's hash table since the last rotation of the stats (see
     *       {@link #rotateStatsOneOrderForward} and similar methods). This value is always less
     *       than or equal to {@link #numSegmentSplitsToCurrentAverageOrder}.
     *   [1] lastComputedMaxNonReportedSkewedSplits: 4-byte integer, the maximum non-reported
     *       number of skewed segment splits (i. e. those counted as numSkewedSplits), computed or
     *       lower-bounded at some point for some {@link #numSegmentSplitsToCurrentAverageOrder}
     *       since the last rotation of the stats.
     */
    int @Nullable[] skewedSegment_splitStatsToCurrentAverageOrder;
    private int numSegmentSplitsToNextAverageOrder;
    private int @Nullable[] skewedSegment_splitStatsToNextAverageOrder;

    public HashCodeDistribution(float poorHashCodeDistrib_badOccasion_minReportingProb,
            Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction) {
        this.poorHashCodeDistrib_badOccasion_minReportingProb =
                poorHashCodeDistrib_badOccasion_minReportingProb;
        this.reportingAction = reportingAction;
    }

    boolean isReportingTooLargeInflatedSegment() {
        return reportTooLargeInflatedSegment;
    }

    /**
     * In theory, this method is amortized per every insertion into a SmoothieMap, but with a very
     * low factor because usually there should be only one inflated segment per 200k ordinary ones;
     * see {@link InflatedSegmentQueryContext}'s class-level Javadoc. However, under some rare
     * circumstances this method might become relatively hot, see the comment for {@link
     * #reportTooLargeInflatedSegment}.
     */
    void checkAndReportTooLargeInflatedSegment(int inflatedSegmentOrder,
            SmoothieMap.InflatedSegment<K, V> inflatedSegment,
            long mapSize, SmoothieMap<K, V> map, int inflatedSegmentSize, K excludedKey) {
        // Using compareNormalizedSegmentSizes() because both inflatedSegmentOrder might be greater
        // than lastAverageSegmentOrderForWhichMaxNonReportedSizeIsComputed and vice versa, if there
        // were no operations involving the inflated segment for some time while the average
        // segment order in the SmoothieMap has changed because of insertions (or removals) in other
        // segments. Although checkAndReportTooLargeInflatedSegment() is always called after
        // SmoothieMap.InflatedSegment.shouldBeSplit() that should intercept the case of
        // inflatedSegmentOrder being smaller than the average segment order, it might be possible
        // to construct a sequence of actions that allow to avoid that interception by not updating
        // SmoothieMap.lastComputedAverageSegmentOrder for long time: see a comment in
        // InflatedSegment.shouldBeSplit().
        //
        // compareNormalizedSegmentSizes() is called inside segmentShouldBeReported() for the same
        // reason.
        //
        // If the size of the SmoothieMap is now less than
        // minMapSizeForWhichLastComputedMaxNonReportedSegmentSizeIsValid, then we
        // need to recompute segmentWithAverageOrder_maxNonReportedSize_lastComputed via
        // segmentShouldBeReported().
        // TODO should use `|` instead of `||`?
        boolean distributionMightBePoor =
                mapSize < minMapSizeForWhichLastComputedMaxNonReportedSegmentSizeIsValid
                        ||
                        compareNormalizedSegmentSizes(inflatedSegmentSize, inflatedSegmentOrder,
                                (int) segmentWithAverageOrder_maxNonReportedSize_lastComputed,
                                (int) lastAverageSegmentOrderForWhichMaxNonReportedSizeIsComputed)
                                > 0;
        if (!distributionMightBePoor) { // [Positive likely branch]
            return;
        }
        if (!segmentShouldBeReported(mapSize, map.computeAverageSegmentOrder(mapSize),
                inflatedSegmentSize, inflatedSegmentOrder)) { // [Positive likely branch]
            return;
        }

        reportTooLargeInflatedSegment(
                inflatedSegment, map, inflatedSegmentSize, excludedKey, mapSize);
    }

    /**
     * Determines whether a segment of the given size with the given order is an example of poor
     * inter-segment hash distribution, i. e. distribution of bits of hash codes that are used
     * to compute indexes in {@link SmoothieMap#segmentsArray} (see {@link
     * SmoothieMap#segmentLookupBits}), considering {@link
     * #poorHashCodeDistrib_badOccasion_minReportingProb}.
     *
     * The given averageSegmentOrder must be equal to the result of {@link
     * SmoothieMap#doComputeAverageSegmentOrder} called with the given size as the argument.
     *
     * See {@link InflatedSegmentQueryContext} Javadoc for more info about the statistical model
     * TODO redo the stats, see https://stats.stackexchange.com/questions/384836/
     *  time-component-in-the-distribution-of-n-elements-randomly-falling-in-m-bins
     */
    private boolean segmentShouldBeReported(long mapSize, int averageSegmentOrder,
            int segmentSize, int segmentOrder) {
        int averageSegments = 1 << averageSegmentOrder;
        double averageSegmentSize = ((double) mapSize) / (double) averageSegments;
        // TODO check Statistics code or benchmark, whether it's less computationally expensive
        //  to make distribution calculations with smaller mean
        // TODO avoid allocation
        PoissonDistribution segmentSizeDistribution =
                new PoissonDistribution(averageSegmentSize);
        double minPoorHashCodeHalfSegmentOccupancyProbabilityForReporting = Math.pow(
                (double) poorHashCodeDistrib_badOccasion_minReportingProb,
                1.0 / (double) averageSegments);
        // The inverseCumulativeProbability() result is the max non-reported size, not + 1, not - 1,
        // because the discrete value is the bar (in a histogram visualisation of the distribution)
        // where the threshold probability falls. See also computeMaxNonReportedSkewedSplits(), the
        // same idea.
        int segmentWithAverageOrder_maxNonReportedSize =
                segmentSizeDistribution.inverseCumulativeProbability(
                        minPoorHashCodeHalfSegmentOccupancyProbabilityForReporting);
        // No need to guard the writes as in SmoothieMap.computeAverageSegmentOrder(), because
        // segmentShouldBeReported() should be called rarely, and statistical calculations is a
        // much heavier part of it.
        segmentWithAverageOrder_maxNonReportedSize_lastComputed =
                (byte) segmentWithAverageOrder_maxNonReportedSize;
        lastAverageSegmentOrderForWhichMaxNonReportedSizeIsComputed = (byte) averageSegmentOrder;
        // minMapSizeForWhichLastComputedMaxNonReportedSegmentSizeIsValid = TODO;
        // Comparing segment size with "maxNonReported" rather than "minReported" size: if the
        // segment order and the threshold order were for the same order, there would be no
        // difference whether to compare the segment size with "maxNonReported" or "minReported"
        // size (apart from choosing > and >= properly). But since the averageSegmentOrder may be
        // higher than the order of the segment (see a comment in
        // checkAndReportTooLargeInflatedSegment() explaining when it is possible), comparing with
        // "minReported" would be a loss of precision.
        //
        // TODO redo, inflated segments with order less than average must simply be split. Note
        //  that not returning a enum indicating this for simplicity and because of extreme rareness
        //  of such cases.
        // Although generally it may seem that when the average segment order is higher than the
        // order of the _inflated_ segment, this comparison is trivial anyway because then
        return compareNormalizedSegmentSizes(
                segmentSize, segmentOrder,
                segmentWithAverageOrder_maxNonReportedSize, averageSegmentOrder) > 0;
    }

    /**
     * This method is extracted from {@link #checkAndReportTooLargeInflatedSegment} to reduce the
     * bytecode size of the latter (along the lines with [Reducing bytecode size of a hot method]).
     * {@link #checkAndReportTooLargeInflatedSegment} is not generally a hot method, but it may
     * become so in some rare cases:
     *  - some key that happens to fall into an inflated segment is removed and inserted into a
     *  SmoothieMap frequently (this case is also mentioned in {@link InflatedSegmentQueryContext}'s
     *  class-level Javadoc. However, {@link InflatedSegmentQueryContext} doesn't optimize for it.)
     *  - A SmoothieMap grows so large that the average segment order becomes {@link
     *  SmoothieMap#MAX_SEGMENTS_ARRAY_ORDER} and a significant portion of segments are inflated.
     *  Obviously SmoothieMap doesn't and cannot optimize for this case in general, but nevertheless
     *  helping this case to some degree (by extracting reportTooLargeInflatedSegment() as a method)
     *  is good while there is little or no downside.
     */
    private void reportTooLargeInflatedSegment(
            SmoothieMap.InflatedSegment<K, V> inflatedSegment,
            SmoothieMap<K, V> map, int inflatedSegmentSize, K excludedKey, long mapSize) {
        float poorHashCodeDistribution_benignOccasion_maxProbability =
                1.0f - poorHashCodeDistrib_badOccasion_minReportingProb;
        String message = "In a map of " + mapSize + " entries the probability for a segment of " +
                "above the average order to have " + inflatedSegmentSize + " entries " +
                "(assuming the hash function distributes keys perfectly well) " +
                "is below the configured threshold " +
                poorHashCodeDistribution_benignOccasion_maxProbability;
        Supplier<String> debugInformation = () -> {
            // The following two fields are just updated in the segmentShouldBeReported() call
            // above, so it's ok to call "lastAverageSegmentOrder..." _the_ averageSegmentOrder.
            return "averageSegmentOrder=" +
                    lastAverageSegmentOrderForWhichMaxNonReportedSizeIsComputed + "\n" +
                    "minReportedSizeOfSegmentWithAverageOrder=" +
                    segmentWithAverageOrder_maxNonReportedSize_lastComputed + "\n";
            // TODO more fields

        };

        PoorHashCodeDistributionOccasion<K, V> poorHashCodeDistribOccasion =
                new PoorHashCodeDistributionOccasion<>(
                        map,
                        message,
                        debugInformation,
                        inflatedSegment,
                        excludedKey

                );
        reportingAction.accept(poorHashCodeDistribOccasion);

        // If the reporting action doesn't actively remove elements, it doesn't make sense to
        // continue reporting about a too large inflated segment for the same SmoothieMap.
        reportTooLargeInflatedSegment = poorHashCodeDistribOccasion.removedSomeElement();
    }

    /**
     * Compares sizes of segments of different orders as if virtually they were of the same order.
     * Returns a negative value, zero or a positive value if the first segment size is less than,
     * equal to or greater than the second, respectively.
     *
     * This method is used when it's unknown how do the orders of the segments compare. When it's
     * known that the order of one segment is always less than the order of another, simpler
     * one-sided normalization should be done inline.
     */
    private static long compareNormalizedSegmentSizes(int segmentSize1, int segmentOrder1,
            int segmentSize2, int segmentOrder2) {
        // Normalizing both sizes to the same virtual order, branchless and without losing precision
        // due to division.
        long normalizedSize1 =
                ((long) segmentSize1) * (1L << max(segmentOrder2 - segmentOrder1, 0));
        long normalizedSize2 =
                ((long) segmentSize2) * (1L << max(segmentOrder1 - segmentOrder2, 0));
        // Unlike Long.compare(), can use simple subtraction here because both sizes are positive.
        return normalizedSize1 - normalizedSize2;
    }

    @AmortizedPerOrder
    void averageSegmentOrderUpdated(
            int previouslyComputedAverageSegmentOrder, int newAverageSegmentOrder) {
        int differenceInComputedAverageSegmentOrders =
                newAverageSegmentOrder - previouslyComputedAverageSegmentOrder;
        if (differenceInComputedAverageSegmentOrders == 1) { // [Positive likely branch]
            // "Shift" stats one order forward when a SmoothieMap is growing.
            rotateStatsOneOrderForward();
        } else if (differenceInComputedAverageSegmentOrders == -1) {
            // "Shift" stats one order backward when a SmoothieMap is shrinking.
            rotateStatsOneOrderBackward();
        } else if (differenceInComputedAverageSegmentOrders < -1) {
            // This may happen when SmoothieMap just started growing again after significant
            // shrinking, see the comment for SmoothieMap.lastComputedAverageSegmentOrder.
            rotateStatsSeveralOrdersBackward();
        } else {
            throw new IllegalStateException(
                    "Unexpected change of average segment order: previously computed = " +
                            previouslyComputedAverageSegmentOrder + ", newly computed = " +
                            newAverageSegmentOrder);
        }
    }

    /** Make "Next" new "Current", zero out "Next". */
    @AmortizedPerOrder
    private void rotateStatsOneOrderForward() {
        numSegmentSplitsToCurrentAverageOrder = numSegmentSplitsToNextAverageOrder;
        numSegmentSplitsToNextAverageOrder = 0;
        int @Nullable[] tmpStats = skewedSegment_splitStatsToCurrentAverageOrder;
        skewedSegment_splitStatsToCurrentAverageOrder = skewedSegment_splitStatsToNextAverageOrder;
        if (tmpStats != null) {
            Arrays.fill(tmpStats, 0);
        }
        skewedSegment_splitStatsToNextAverageOrder = tmpStats;
    }

    /** Make "Current" new "Next", zero out "Current". */
    @AmortizedPerOrder
    private void rotateStatsOneOrderBackward() {
        numSegmentSplitsToNextAverageOrder = numSegmentSplitsToCurrentAverageOrder;
        numSegmentSplitsToCurrentAverageOrder = 0;
        int @Nullable[] tmpStats = skewedSegment_splitStatsToNextAverageOrder;
        skewedSegment_splitStatsToNextAverageOrder = skewedSegment_splitStatsToCurrentAverageOrder;
        if (tmpStats != null) {
            Arrays.fill(tmpStats, 0);
        }
        skewedSegment_splitStatsToCurrentAverageOrder = tmpStats;
    }

    /**
     * Zero out both "Current" and "Next". This may happen when a SmoothieMap starts growing after
     * significant shrinking, see the comment for {@link
     * SmoothieMap#lastComputedAverageSegmentOrder}.
     */
    @BarelyCalled
    private void rotateStatsSeveralOrdersBackward() {
        numSegmentSplitsToCurrentAverageOrder = 0;
        numSegmentSplitsToNextAverageOrder = 0;
        int @Nullable[] tmpStats = this.skewedSegment_splitStatsToCurrentAverageOrder;
        if (tmpStats != null) {
            Arrays.fill(tmpStats, 0);
        }
        tmpStats = this.skewedSegment_splitStatsToNextAverageOrder;
        if (tmpStats != null) {
            Arrays.fill(tmpStats, 0);
        }
    }

    @AmortizedPerSegment
    void accountSegmentSplit(SmoothieMap<?, ?> map, int priorSegmentOrder, int numKeysForHalfOne,
            int totalNumKeysBeforeSplit) {

        // ### Compute maxKeysForHalf.
        int numKeysForHalfTwo = totalNumKeysBeforeSplit - numKeysForHalfOne;
        int maxKeysForHalf = max(numKeysForHalfOne, numKeysForHalfTwo);

        // ### Choose numSegmentSplitsToNewOrder and skewedSegment_splitStats.
        // Depending on priorSegmentOrder, determine whether the "current" or the "next"
        // numSegmentSplits and skewedSegment_splitStats should be used to account this split, or
        // exit the method if the skewness level of the split is not accounted (see the comment for
        // HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS expanding
        // on this) or if this is a split to the order which is neither the current average segment
        // order nor the next average segment order in the SmoothieMap (see the comment for
        // handleSplitToNeitherCurrentNorNextAverageOrder() for details).
        int lastComputedAverageSegmentOrder = (int) map.lastComputedAverageSegmentOrder;
        int numSegmentSplitsToNewOrder;
        int @MonotonicNonNull[] skewedSegment_splitStats;
        boolean splittingToCurrentAverageSegmentOrder =
                priorSegmentOrder == lastComputedAverageSegmentOrder - 1;
        // TODO make this logic branchless by memory alignment, field offsets, and Unsafe.
        if (splittingToCurrentAverageSegmentOrder) { // 50-50 unpredictable branch
            numSegmentSplitsToNewOrder = numSegmentSplitsToCurrentAverageOrder + 1;
            this.numSegmentSplitsToCurrentAverageOrder = numSegmentSplitsToNewOrder;
            // [Positive likely branch]
            if (maxKeysForHalf < SKEWED_SEGMENT__HASH_TABLE_HALF__MAX_KEYS__MIN_ACCOUNTED) {
                return;
            }
            // TODO report to IntelliJ if not fixed by
            //  https://youtrack.jetbrains.com/issue/IDEA-214972 in 2019.2
            //noinspection ConstantConditions
            skewedSegment_splitStats = skewedSegment_splitStatsToCurrentAverageOrder;
            if (skewedSegment_splitStats == null) {
                // TODO allocate smaller array initially and grow as needed in
                //  doAccountSkewedSegmentSplit()
                skewedSegment_splitStats =
                        new int[SKEWED_SEGMENT__SPLIT_STATS__INT_ARRAY_LENGTH];
                skewedSegment_splitStatsToCurrentAverageOrder = skewedSegment_splitStats;
            }
            // Fall-through to the doAccountSkewedSegmentSplit() call
        } else {
            boolean splittingToNextAverageSegmentOrder =
                    priorSegmentOrder == lastComputedAverageSegmentOrder;
            if (splittingToNextAverageSegmentOrder) { // [Positive likely branch]
                numSegmentSplitsToNewOrder = numSegmentSplitsToNextAverageOrder + 1;
                this.numSegmentSplitsToNextAverageOrder = numSegmentSplitsToNewOrder;
                // [Positive likely branch]
                if (maxKeysForHalf < SKEWED_SEGMENT__HASH_TABLE_HALF__MAX_KEYS__MIN_ACCOUNTED) {
                    return;
                }
                // TODO report to IntelliJ if not fixed by
                //  https://youtrack.jetbrains.com/issue/IDEA-214972 in 2019.2
                //noinspection ConstantConditions
                skewedSegment_splitStats = skewedSegment_splitStatsToNextAverageOrder;
                if (skewedSegment_splitStats == null) {
                    // TODO allocate smaller array initially and grow as needed in
                    //  doAccountSkewedSegmentSplit()
                    skewedSegment_splitStats =
                            new int[SKEWED_SEGMENT__SPLIT_STATS__INT_ARRAY_LENGTH];
                    skewedSegment_splitStatsToNextAverageOrder = skewedSegment_splitStats;
                }
                // Fall-through to the doAccountSkewedSegmentSplit() call
            } else {
                // See the comment for numSegmentSplitsToCurrentAverageOrder,
                // [Some information is lost] section.
                handleSplitToNeitherCurrentNorNextAverageOrder(
                        priorSegmentOrder, lastComputedAverageSegmentOrder);
                return;
            }
        }

        doAccountSkewedSegmentSplit(numSegmentSplitsToNewOrder,
                maxKeysForHalf, skewedSegment_splitStats);
    }

    @AmortizedPerSegment
    void doAccountSkewedSegmentSplit(
            int numSplits, int maxKeysForHalf, int[] skewedSegment_splitStats) {
        int statIndex = SKEWED_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__MAX_ACCOUNTED -
                max(0, (HASH_TABLE_SLOTS / 2) - maxKeysForHalf);
        // Iterate over stats for different skewness levels.
        for (; statIndex >= 0; statIndex--) {
            int statOffset = statIndex * SKEWED_SEGMENT__SPLIT_STAT_SIZE;
            // TODO use Unsafe to access the array for speed
            int numSkewedSplits = skewedSegment_splitStats[statOffset];
            numSkewedSplits += 1;
            skewedSegment_splitStats[statOffset] = numSkewedSplits;

            // Check whether the number of splits of segments with at least the currently iterated
            // level of skewness has become large enough for reporting
            // (reportTooManySkewedSegmentSplits()) in a series of three checks of increasing cost:
            // 1) Compare with a bound computed previously (either on the step 2 or 3 below) for the
            // current skewness level: a single L1 access memory access and an integer comparison
            // with a likely branch.
            // TODO read long word (int + int) from skewedSegment_splitStats to avoid an extra read.
            int lastComputedMaxNonReportedSkewedSplits = skewedSegment_splitStats[statOffset + 1];
            if (numSplits <= lastComputedMaxNonReportedSkewedSplits) { // [Positive likely branch]
                continue; // Don't report, continue to account and check in the next stat
            }

            // 2) Update the conservative bound: two floating point loads, a multiplication, a
            // truncation to an integer, an integer comparison with a likely branch, and an L1
            // write. Note that we don't store the newly computed conservative bound directly in
            // lastComputedMaxNonReportedSkewedSplits but rather creating a new variable because
            // it's unknown which value is greater (lastComputedMaxNonReportedSkewedSplits which is
            // read on step 1 may be written on step 3 which computes exact bound, hence it might
            // be greater than the conservative bound computed on this step). We later compute the
            // maximum of them inside computeMaxNonReportedSkewedSplits().
            int maxNonReportedSkewedSplitsLowerBound =
                    computeMaxNonReportedSkewedSplitsLowerBound(statIndex, numSplits);
            if (numSkewedSplits <= maxNonReportedSkewedSplitsLowerBound) {//[Positive likely branch]
                skewedSegment_splitStats[statOffset + 1] = maxNonReportedSkewedSplitsLowerBound;
                continue; // Don't report, continue to account and check in the next stat
            }

            // 3) Compute the exact max num skewed splits on this skewness level: a very expensive
            // operation (500 cycles or 4 main memory reads), see the comment for
            // computeMaxNonReportedSkewedSplits().
            int maxNonReportedSkewedSplits = computeMaxNonReportedSkewedSplits(statIndex,
                    numSplits, lastComputedMaxNonReportedSkewedSplits,
                    maxNonReportedSkewedSplitsLowerBound);
            if (numSkewedSplits <= maxNonReportedSkewedSplits) { // [Positive likely branch]
                skewedSegment_splitStats[statOffset + 1] = maxNonReportedSkewedSplits;
                continue; // Don't report, continue to account and check in the next stat
            }

            // numSkewedSplits is not within the precise max bound, reporting.
            reportTooManySkewedSegmentSplits();
            // Don't need to continue the loop after reporting the event,
            // hasReportedTooManySkewedSegmentSplits is set to true in the above call to
            // reportTooManySkewedSegmentSplits().
            return;
        }
    }

    @AmortizedPerSegment
    private static int computeMaxNonReportedSkewedSplitsLowerBound(int statIndex, int numSplits) {
        double prob = HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS[statIndex];
        return (int) (prob * (double) numSplits);
    }

    /**
     * Passing lastComputedMaxNonReportedSkewedSplits and maxNonReportedSkewedSplitsLowerBound
     * separately into this method breaks the abstraction, but helps to reduce the bytecode size of
     * the caller {@link #doAccountSkewedSegmentSplit} which calls this method relatively rarely. See
     * [Reducing bytecode size of a hot method]. (See the comment in {@link
     * #doAccountSkewedSegmentSplit}, step 2 explaining why these are two different values).
     *
     * This operation may take either ~500 cycles (see the comment for {@link
     * BinomialDistributionInverseCdfApproximation}) or require up to 4 main memory accesses (see
     * the comment for {@link PrecomputedBinomialCdfValues}).
     */
    @AmortizedPerSegment
    private int computeMaxNonReportedSkewedSplits(int statIndex, int numSplits,
            int lastComputedMaxNonReportedSkewedSplits, int maxNonReportedSkewedSplitsLowerBound) {
        if (numSplits <= PrecomputedBinomialCdfValues.MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES) {
            int prevMaxNonReportedSkewedSegments = max(lastComputedMaxNonReportedSkewedSplits,
                    maxNonReportedSkewedSplitsLowerBound);
            return PrecomputedBinomialCdfValues.inverseCumulativeProbability(statIndex, numSplits,
                    poorHashCodeDistrib_badOccasion_minReportingProb,
                    prevMaxNonReportedSkewedSegments);
        } else {
            return BinomialDistributionInverseCdfApproximation.inverseCumulativeProbability(
                    statIndex, numSplits,
                    (double) poorHashCodeDistrib_badOccasion_minReportingProb);
        }
    }

    /**
     * Extracted for [Reducing bytecode size of a hot method] {@link #doAccountSkewedSegmentSplit}.
     */
    @BarelyCalled
    private void reportTooManySkewedSegmentSplits() {
        hasReportedTooManySkewedSegmentSplits = true;
        reportingAction.accept(null); // TODO
    }

    /**
     * For rare splits of segments that are more than one order behind the average, or have an order
     * greater than the last computed average. The latter shouldn't normally happen (as long as
     * {@link SmoothieMap#MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE} equals to 1), unless there are
     * concurrent modifications going on between the moment of making a {@link
     * SmoothieMap#MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE}-involving in {@link
     * SmoothieMap#makeSpaceAndInsert} and the moment of reading {@link
     * SmoothieMap#lastComputedAverageSegmentOrder} in {@link #accountSegmentSplit} (which is called
     * downstream from {@link SmoothieMap#makeSpaceAndInsert}) which result in {@link
     * SmoothieMap#lastComputedAverageSegmentOrder} being updated.
     *
     * Hence the only purpose of this method is to check the condition that means there are
     * concurrent modifications and throw a {@link ConcurrentModificationException}. Rare, but
     * normal splits of segments that are more than one order behind the average are not accounted
     * in statistics.
     */
    private static void handleSplitToNeitherCurrentNorNextAverageOrder(
            int priorSegmentOrder, int lastComputedAverageSegmentOrder) {
        if (priorSegmentOrder > maxSplittableSegmentOrder(lastComputedAverageSegmentOrder)) {
            throw new ConcurrentModificationException(
                    "Prior segment order: " + priorSegmentOrder +
                            ", last computed average segment order: " +
                            lastComputedAverageSegmentOrder + ". " +
                            "This cannot be an ordinary segment split without concurrent " +
                            "modification of the map going on.");
        }
    }

}
