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

import io.timeandspace.smoothie.Statistics.BinomialDistribution;
import io.timeandspace.smoothie.Statistics.NormalDistribution;

import static io.timeandspace.smoothie.PrecomputedBinomialCdfValues.MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES;
import static io.timeandspace.smoothie.HashCodeDistribution.OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS;

/**
 * Inverse CDF of binomial distribution ({@link BinomialDistribution#inverseCumulativeProbability})
 * is approximated using inverse CDF of normal distribution because the latter is much faster:
 * {@link BinomialDistribution#inverseCumulativeProbability} takes from 1500 to 5000 cycles
 * (depends statically on the arguments for BinomialDistribution; apparently depends on the speed of
 * convergence) while {@link NormalDistribution#inverseCumulativeProbability} takes about 500
 * cycles. {@link #normalApproximationCorrection} additionally takes only about 25 cycles. These
 * numbers are obtained on Intel Skylake.
 * TODO move the benchmark from smoothie-map-benchmarks to this project when it builds
 *
 * In the absolute worst case (the number of segments with skewed distribution of entries between
 * the halves is always on the edge of the reporting probability threshold
 * (maxProbabilityOfOccasionIfHashFunctionWasRandom), but doesn't cross it, so that {@link
 * HashCodeDistribution#hasReportedTooManySkewedSegmentSplits} is not set to
 * false and a SmoothieMap continues to track statistics and calculate probabilities) {@link
 * #inverseCumulativeProbability} might be called approximately 4 times for every 10 segment splits
 * on average (TODO run a test to check that empirically): about one in five segment splits is
 * skewed to the degree of having at least {@link
 * HashCodeDistribution#OUTLIER_SEGMENT__HASH_TABLE_HALF__MAX_KEYS__MIN_ACCOUNTED} (29) keys in one
 * of the halves. (The "one in five" estimate is the inverse of the first probability in this table: {@link
 * HashCodeDistribution#OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}.)
 * Accordingly, for 11% of splits the probability should be checked two times (for two different
 * skewness levels independently, see the loop in {@link
 * HashCodeDistribution#accountOutlierSegmentSplit}), three times for 6% of splits and four times
 * for 3% of splits; this results in approximately 4 probability checks for every 10 segment splits
 * on average.
 *
 * The average occupancy of segments in a SmoothieMap always floats around 3/4 of the segment's max
 * capacity ({@link SmoothieMap.BitSetAndStateArea#SEGMENT_MAX_ALLOC_CAPACITY}), i. e. 32 entries.
 * The number of segment splits is equal to the number of segments minus one in a SmoothieMap, so
 * considering them equal for this approximate calculation. It means that one {@link
 * #inverseCumulativeProbability} call is amortized among 32 * (10 / 4) = 80 insertions in a
 * SmoothieMap. It means that the cost of tracking and reporting skewed segment's hash table halves
 * is about 500+ / 80 ~= 7 cycles per an insertion. Note again that this is the absolute worst case
 * of always having almost enough skewed segments to exceed the reporting threshold; if there are
 * less skewed segments, caching of lastComputedMaxNonReportedSkewedSplits (see {@link
 * HashCodeDistribution#outlierSegmentSplitStatsToCurrentAverageOrder}) allows to call {@link
 * #inverseCumulativeProbability} more rarely than one time per four probability checks.
 *
 * So if {@link BinomialDistribution#inverseCumulativeProbability} itself was used the overhead of
 * reporting would be from 1500..5000 / 80 = 18..60 cycles per inserting in a SmoothieMap, that is
 * a prohibitively high cost.
 */
final class BinomialDistributionInverseCdfApproximation {

    /**
     * Returns max non-reported skewed segments for the given number of segment splits,
     * the specified probability of skewed segments as observed during splits, and the min required
     * confidence that an observed number of skewed segments is not occasional ("benign") and
     * therefore should be reported.
     *
     * Approximates {@code new BinomialDistribution(numSplits,
     *     OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS[probIndex])
     *         .inverseCumulativeProbability(
     *             poorHashCodeDistribution_badOccasion_minRequiredConfidence)}.
     *
     * The semantics and the interface of this method are parallel to those of {@link
     * PrecomputedBinomialCdfValues#inverseCumulativeProbability}, but these methods must be called
     * on different ranges of numSplits: {@link PrecomputedBinomialCdfValues} when the number of
     * splits is less than or equal to {@link
     * PrecomputedBinomialCdfValues#MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES}, {@link
     * BinomialDistributionInverseCdfApproximation} otherwise.
     *
     * @param probIndex index of probability value in {@link
     *        HashCodeDistribution#OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}.
     *        This is the probability of success for binomial distribution.
     * @param numSplits a number of segment split events in a {@link SmoothieMap} of some order at
     *        some stage of a SmoothieMap's life. See the comment for {@link
     *        HashCodeDistribution#numSegmentSplitsToCurrentAverageOrder} for explanations.
     *        This number is used as the argument (N) for binomial distribution.
     * @param poorHashCodeDistribution_badOccasion_minRequiredConfidence the argument for inverse
     *        CDF function, equals to `1.0 - maxProbabilityOfOccasionIfHashFunctionWasRandom`, as
     *        specified in {@link SmoothieMapBuilder#reportPoorHashCodeDistribution}
     * @return max non-reported skewed segments (inverse CDF result)
     */
    static int inverseCumulativeProbability(int probIndex, int numSplits,
            double poorHashCodeDistribution_badOccasion_minRequiredConfidence) {
        if (numSplits <= MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES) {
            // PrecomputedBinomialCdfValues must be called instead, not only because it is able
            // to accept the numSplits, but also because BinomialDistributionInverseCdfApproximation
            // is increasingly imprecise the smaller numSplits:
            // https://en.wikipedia.org/wiki/Binomial_distribution#Normal_approximation says that
            // a good test for applicability of approximation of binomial distribution with normal
            // is numSplits > 9 * (1 - p) / p, that gives numSplits > 298 for the smallest
            // probability of success accepted by this method (0.02930494672052930127392
            // for probIndex = 3). But it's unknown whether the test should become even stricter
            // for approximation of inverse CDF of binomial distribution with inverse CDF of normal
            // distribution, so playing on the safe side here by allowing only numSplits greater
            // than MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES (~1000).
            throw new AssertionError();
        }
        double p = OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS[probIndex];
        double mean = ((double) numSplits) * p;
        double stdDev = mean * (1.0 - p);
        double normalApproximation = NormalDistribution.inverseCumulativeProbability(
                mean, stdDev, poorHashCodeDistribution_badOccasion_minRequiredConfidence);
        double poorHashCodeDistribution_benignOccasion_maxProbability =
                1.0 - poorHashCodeDistribution_badOccasion_minRequiredConfidence;
        double correction = normalApproximationCorrection(
                probIndex, poorHashCodeDistribution_benignOccasion_maxProbability);
        return Math.toIntExact(Math.round(normalApproximation + correction));
    }

    /**
     * A correction for normal approximation of inverse CDF of binomial distribution is needed
     * because the former is biased. Read the code and the comments in {@link
     * BinomialDistributionInverseCdfNormalApproximationBias} to gain better understanding of how
     * does this method work.
     */
    @SuppressWarnings({"UnnecessaryLocalVariable"})
    @VisibleForTesting
    static double normalApproximationCorrection(
            int probIndex, double poorHashCodeDistribution_benignOccasion_maxProbability) {
        float[] corrections = CORRECTIONS_PER_SKEW_PROB[probIndex];
        // The following logic in the inverse of the logic of [Exponential loop] in main() in
        // BinomialDistributionInverseCdfNormalApproximationBias.
        double maxProbability_fractionOfMax =
                poorHashCodeDistribution_benignOccasion_maxProbability /
                        SmoothieMap.MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;
        // lowerCorrectionIndex
        //   = Log[POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE, maxProbability_fractionOfMax]
        //   = Log[maxProbability_fractionOfMax] / Log[POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE]
        int lowerCorrectionIndex = (int) (
                Math.log(maxProbability_fractionOfMax)
                        / POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE__LOG);
        int higherCorrectionIndex = lowerCorrectionIndex + 1;

        double lowerCorrection = (double) corrections[lowerCorrectionIndex];
        double higherCorrection = (double) corrections[higherCorrectionIndex];

        double higherProbability =
                SmoothieMap.MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB *
                        Math.pow(POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE,
                                (double) lowerCorrectionIndex);
        double lowerProbability;
        if (higherCorrectionIndex < corrections.length - 1) {
            lowerProbability = higherProbability *
                    POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE;
        } else {
            lowerProbability =
                    SmoothieMap.MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;
        }

        if (poorHashCodeDistribution_benignOccasion_maxProbability < lowerProbability ||
                poorHashCodeDistribution_benignOccasion_maxProbability > higherProbability) {
            throw new AssertionError();
        }

        // Linear interpolation: "probabilities" are Xs, "corrections" are Ys. higherProbability and
        // lowerProbability are not actual values on the X axis, the actual values on the X
        // axis are `1 - xxxProbability`.
        double minusX0_plus1 = higherProbability;
        double minusX_plus1 = poorHashCodeDistribution_benignOccasion_maxProbability;
        double minusX1_plus1 = lowerProbability;

        double xMinusX0 = minusX0_plus1 - minusX_plus1;
        double x1MinusX0 = minusX0_plus1 - minusX1_plus1;

        double y0 = lowerCorrection;
        double y1 = higherCorrection;

        // Using approach (1) from this question: https://math.stackexchange.com/questions/907327.
        // Monotonicity and exactness-at-bounds concerns raised in that question are not important
        // here, because the precision of y0 and y1 (which are values from CORRECTIONS_PER_SKEW_PROB
        // array) is only 0.001 anyway: see a comment in
        // BinomialDistributionInverseCdfNormalApproximationBias.estimateMeanErrorAndSimpleRegression().
        double t = xMinusX0 / x1MinusX0;
        double y = y0 + ((y1 - y0) * t);

        double interpolatedCorrection = y;
        return interpolatedCorrection;
    }

    /**
     * Copied {@link
     * BinomialDistributionInverseCdfNormalApproximationBias#POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE}.
     */
    private static final double POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE =
            0.9255464154186664;

    private static final double POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE__LOG =
            -0.07737099650418856;

    static {
        if (Math.log(POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE) !=
                POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE__LOG) {
            throw new AssertionError();
        }
    }

    /**
     * Each inner array corresponds to one probability of skewed segments (one of values from
     * {@link HashCodeDistribution#OUTLIER_SEGMENT__HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}),
     * "p" or "probabilityOfSuccess" in binomial distribution terms.
     *
     * Each inner array's value is a correction that should be applied to {@link
     * NormalDistribution#inverseCumulativeProbability} so that it approximates {@link
     * BinomialDistribution#inverseCumulativeProbability} without a bias for some specific argument.
     * Indexes in inner arrays translate to values of the arguments with an exponentiation rule: see
     * {@link
     * BinomialDistributionInverseCdfNormalApproximationBias#POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE}
     * and {@link #normalApproximationCorrection} for more info.
     *
     * The precision of the bias corrections is about 0.001: see a comment in {@link
     * BinomialDistributionInverseCdfNormalApproximationBias#estimateMeanErrorAndSimpleRegression}.
     *
     * Computed in {@link BinomialDistributionInverseCdfNormalApproximationBias}, copied from
     * {@link io.timeandspace.smoothie.BinomialDistributionInverseCdfNormalApproximationBias.Output}.
     *
     * TODO in warmup procedure, access all values in all arrays to ensure they are mapped into
     *  memory
     */
    private static final float[][] CORRECTIONS_PER_SKEW_PROB = {
            // Prob: 0.19341265286193732
            {-0.0293498039f, -0.0199182779f, -0.0106528075f, 0.0003727727f, 0.0107068541f,
                    0.0212952457f, 0.0325701572f, 0.0437135622f, 0.0543619283f, 0.0663404092f,
                    0.0777928978f, 0.0894301608f, 0.1006891504f, 0.1137766540f, 0.1253962368f,
                    0.1375181377f, 0.1498875022f, 0.1620640904f, 0.1747992486f, 0.1877894998f,
                    0.2006140351f, 0.2129683346f, 0.2264611423f, 0.2396189719f, 0.2518413961f,
                    0.2652647197f, 0.2784822285f, 0.2915766835f, 0.3049605489f, 0.3180832267f,
                    0.3317475319f, 0.3454345465f, 0.3587292433f, 0.3718332946f, 0.3857926726f,
                    0.3990947902f, 0.4127724171f, 0.4264045656f, 0.4396668971f, 0.4538764060f,
                    0.4678765535f, 0.4819669724f, 0.4955078661f, 0.5091878176f, 0.5237478614f,
                    0.5374088287f, 0.5512601137f, 0.5653114915f, 0.5801442266f, 0.5933577418f,
                    0.6078637838f, 0.6218159199f, 0.6361058354f, 0.6507502794f, 0.6645386815f,
                    0.6789593101f, 0.6930854321f, 0.7072513103f, 0.7214302421f, 0.7358757854f,
                    0.7500143051f, 0.7645889521f, 0.7787081599f, 0.7934131026f, 0.8078096509f,
                    0.8219586611f, 0.8362841606f, 0.8511216640f, 0.8653536439f, 0.8796117902f,
                    0.8942174911f, 0.9091762304f, 0.9234464765f, 0.9381239414f, 0.9527274966f,
                    0.9666527510f, 0.9811434150f, 0.9961093664f, 1.0100607872f, 1.0259015560f,
                    1.0394554138f, 1.0547440052f, 1.0697293282f, 1.0837690830f, 1.0987898111f,
                    1.1129040718f, 1.1279237270f, 1.1427096128f, 1.1572735310f, 1.1720954180f,
                    1.1868517399f, 1.2013897896f, 1.2170144320f, 1.2306869030f, 1.2460783720f,
                    1.2604910135f, 1.2755695581f, 1.2900885344f, 1.3047913313f, 1.3200113773f,
                    1.3343120813f, 1.3496599197f, 1.3644692898f, 1.3789991140f, 1.3941851854f,
                    1.4089064598f, 1.4238531590f, 1.4384306669f, 1.4538522959f, 1.4682853222f,
                    1.4832617044f, 1.4981952906f, 1.5129330158f, 1.5277873278f, 1.5427365303f,
                    1.5581328869f, 1.5730190277f, 1.5879575014f, 1.6028145552f, 1.6181076765f,
                    1.6329592466f, 1.6478259563f, 1.6620504856f, 1.6776720285f, 1.6929413080f,
                    1.7077519894f, 1.7226113081f, 1.7376224995f, 1.7524070740f},

            // Prob: 0.11140289106101875
            {-0.0379014313f, -0.0261197854f, -0.0130192898f, -0.0000673962f, 0.0134068942f,
                    0.0269587263f, 0.0409180820f, 0.0549202710f, 0.0691877976f, 0.0837387294f,
                    0.0982605219f, 0.1131160334f, 0.1284906864f, 0.1432594210f, 0.1588796228f,
                    0.1743642986f, 0.1904094964f, 0.2053262889f, 0.2216300368f, 0.2375045717f,
                    0.2538444996f, 0.2703366876f, 0.2864021957f, 0.3030304313f, 0.3196849823f,
                    0.3365378380f, 0.3525343835f, 0.3696685433f, 0.3861532509f, 0.4033874571f,
                    0.4205023944f, 0.4373828471f, 0.4545614421f, 0.4711523056f, 0.4888570309f,
                    0.5057988167f, 0.5232188702f, 0.5410544276f, 0.5576581955f, 0.5756082535f,
                    0.5932683349f, 0.6106078029f, 0.6281859875f, 0.6458594799f, 0.6635311842f,
                    0.6811907291f, 0.6987587810f, 0.7164686918f, 0.7345904708f, 0.7523578405f,
                    0.7699162364f, 0.7882824540f, 0.8057317138f, 0.8244326115f, 0.8419961333f,
                    0.8600861430f, 0.8780527115f, 0.8961583972f, 0.9143464565f, 0.9328238964f,
                    0.9507938623f, 0.9690241218f, 0.9872370362f, 1.0055892467f, 1.0229716301f,
                    1.0418566465f, 1.0597735643f, 1.0788867474f, 1.0964469910f, 1.1152989864f,
                    1.1329318285f, 1.1522475481f, 1.1699405909f, 1.1888303757f, 1.2068895102f,
                    1.2255815268f, 1.2440547943f, 1.2623819113f, 1.2811008692f, 1.2994682789f,
                    1.3179864883f, 1.3369151354f, 1.3552340269f, 1.3738572598f, 1.3925466537f,
                    1.4112933874f, 1.4296802282f, 1.4482580423f, 1.4671256542f, 1.4857743979f,
                    1.5041145086f, 1.5231261253f, 1.5421836376f, 1.5604972839f, 1.5792224407f,
                    1.5982604027f, 1.6168763638f, 1.6356155872f, 1.6539545059f, 1.6734272242f,
                    1.6918655634f, 1.7111874819f, 1.7296278477f, 1.7485281229f, 1.7670475245f,
                    1.7863335609f, 1.8049553633f, 1.8237490654f, 1.8425047398f, 1.8610275984f,
                    1.8805258274f, 1.8989101648f, 1.9185605049f, 1.9372274876f, 1.9557738304f,
                    1.9749890566f, 1.9939780235f, 2.0130231380f, 2.0315704346f, 2.0503461361f,
                    2.0694956779f, 2.0882911682f, 2.1076831818f, 2.1267554760f, 2.1462895870f,
                    2.1645300388f, 2.1837921143f, 2.2022449970f, 2.2217822075f},

            // Prob: 0.05946337525377032
            {-0.0427835360f, -0.0291024800f, -0.0148676159f, -0.0007729941f, 0.0149348695f,
                    0.0303475931f, 0.0460020080f, 0.0620034374f, 0.0784203187f, 0.0946559906f,
                    0.1116630360f, 0.1282888800f, 0.1454821527f, 0.1627380848f, 0.1804449409f,
                    0.1974658966f, 0.2152377218f, 0.2338209450f, 0.2509340644f, 0.2696189284f,
                    0.2879048884f, 0.3065820932f, 0.3250016272f, 0.3433329463f, 0.3622799218f,
                    0.3813498318f, 0.3989969492f, 0.4189024866f, 0.4378204048f, 0.4568325877f,
                    0.4758471251f, 0.4954762459f, 0.5149607062f, 0.5346294045f, 0.5536031723f,
                    0.5737468004f, 0.5932075977f, 0.6124647260f, 0.6328640580f, 0.6523823142f,
                    0.6727102399f, 0.6917463541f, 0.7114446759f, 0.7317919135f, 0.7520277500f,
                    0.7717351913f, 0.7919486165f, 0.8124851584f, 0.8320114613f, 0.8529548645f,
                    0.8734592795f, 0.8933904767f, 0.9134525657f, 0.9342185855f, 0.9540500045f,
                    0.9746896625f, 0.9951276779f, 1.0156608820f, 1.0366032124f, 1.0569708347f,
                    1.0776567459f, 1.0977989435f, 1.1185109615f, 1.1395140886f, 1.1602398157f,
                    1.1810902357f, 1.2013999224f, 1.2225233316f, 1.2427847385f, 1.2638908625f,
                    1.2845036983f, 1.3054964542f, 1.3262797594f, 1.3469794989f, 1.3683544397f,
                    1.3895046711f, 1.4104121923f, 1.4317548275f, 1.4522105455f, 1.4730441570f,
                    1.4935805798f, 1.5149184465f, 1.5363477468f, 1.5570597649f, 1.5783772469f,
                    1.5995383263f, 1.6205117702f, 1.6416691542f, 1.6628873348f, 1.6834287643f,
                    1.7053501606f, 1.7261861563f, 1.7475475073f, 1.7687762976f, 1.7896190882f,
                    1.8112030029f, 1.8324615955f, 1.8532754183f, 1.8749551773f, 1.8962292671f,
                    1.9174963236f, 1.9388232231f, 1.9602180719f, 1.9815039635f, 2.0025525093f,
                    2.0240356922f, 2.0457215309f, 2.0668418407f, 2.0884573460f, 2.1096544266f,
                    2.1312608719f, 2.1528117657f, 2.1735870838f, 2.1953878403f, 2.2171030045f,
                    2.2383842468f, 2.2590410709f, 2.2812907696f, 2.3025312424f, 2.3236744404f,
                    2.3458576202f, 2.3671686649f, 2.3886539936f, 2.4101204872f, 2.4319715500f,
                    2.4534490108f, 2.4748907089f, 2.4969248772f, 2.5183219910f},

            // Prob: 0.02930494672052930127392
            {-0.0462927930f, -0.0309282579f, -0.0157382768f, -0.0005130560f, 0.0160107948f,
                    0.0325537696f, 0.0492135435f, 0.0665163398f, 0.0838017538f, 0.1007185280f,
                    0.1188094169f, 0.1368042529f, 0.1550475508f, 0.1735061556f, 0.1920966953f,
                    0.2113856077f, 0.2300842255f, 0.2492610216f, 0.2684667408f, 0.2881497741f,
                    0.3073111475f, 0.3269310296f, 0.3467698395f, 0.3664225638f, 0.3864870965f,
                    0.4067004919f, 0.4270678163f, 0.4476672113f, 0.4675426781f, 0.4878982306f,
                    0.5086892843f, 0.5292266607f, 0.5497279167f, 0.5706669688f, 0.5911690593f,
                    0.6120008230f, 0.6333612204f, 0.6541322470f, 0.6759487987f, 0.6966789365f,
                    0.7181183696f, 0.7391004562f, 0.7598546147f, 0.7816494703f, 0.8026309609f,
                    0.8243218660f, 0.8459652066f, 0.8675382137f, 0.8893296719f, 0.9104934335f,
                    0.9320321679f, 0.9541980028f, 0.9760572314f, 0.9970070720f, 1.0189453363f,
                    1.0410242081f, 1.0626718998f, 1.0850458145f, 1.1068470478f, 1.1288017035f,
                    1.1501957178f, 1.1727114916f, 1.1949956417f, 1.2165093422f, 1.2390278578f,
                    1.2604625225f, 1.2825100422f, 1.3051807880f, 1.3274835348f, 1.3497557640f,
                    1.3717058897f, 1.3938970566f, 1.4161829948f, 1.4388599396f, 1.4607384205f,
                    1.4834687710f, 1.5058617592f, 1.5279083252f, 1.5505100489f, 1.5733710527f,
                    1.5950889587f, 1.6181439161f, 1.6402724981f, 1.6625990868f, 1.6855672598f,
                    1.7080913782f, 1.7302352190f, 1.7530299425f, 1.7749903202f, 1.7984309196f,
                    1.8206942081f, 1.8439601660f, 1.8660695553f, 1.8883332014f, 1.9114925861f,
                    1.9339004755f, 1.9563375711f, 1.9797099829f, 2.0019302368f, 2.0251233578f,
                    2.0468974113f, 2.0700869560f, 2.0928807259f, 2.1159744263f, 2.1386311054f,
                    2.1620137691f, 2.1840658188f, 2.2073173523f, 2.2302541733f, 2.2524335384f,
                    2.2762923241f, 2.2984127998f, 2.3209290504f, 2.3446996212f, 2.3668880463f,
                    2.3897564411f, 2.4129936695f, 2.4359345436f, 2.4590475559f, 2.4817214012f,
                    2.5041790009f, 2.5278310776f, 2.5511963367f, 2.5735521317f, 2.5963966846f,
                    2.6195063591f, 2.6425979137f, 2.6656162739f, 2.6885669231f}
    };

    private BinomialDistributionInverseCdfApproximation() {}
}
