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

import org.apache.commons.math3.analysis.solvers.AbstractUnivariateSolver;
import org.apache.commons.math3.analysis.solvers.BaseAbstractUnivariateSolver;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.util.Pair;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * {@link #main} of this class computes and prints mean absolute error (MAE) corrections to be
 * applied to {@link Statistics.NormalDistribution#inverseCumulativeProbability} so that it
 * approximates {@link Statistics.BinomialDistribution#inverseCumulativeProbability} (see {@link
 * #approxNormalResult}) without a bias, estimated across two parameters:
 *
 *  1) four specific probabilities of success for binomial distribution
 *  ({@link #SKEW_PROBS}). These values correspond to cumulative probabilities of a segment of 48
 *  entries to have at least 28, 29, 30 and 31 entries in one of their halves.
 *
 *  2) inverseCumulativeProbability() arguments, ranging from 0.8 to 0.99999. These values
 *  correspond to complements of poor hash code distribution probability reporting thresholds,
 *  ranging from {@link #MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB} to {@link
 *  #MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB}. We choose {@link #CORRECTION_POINTS}
 *  points on this range, so that the total of {@link #CORRECTION_POINTS} * {@code SKEW_PROBS.length}
 *  precomputed MAE correction values are stored and used from SmoothieMap. Points are spaced
 *  exponentially, approaching 1.0. See the comment for {@link
 *  #POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE} for more information on this.
 *
 * In SmoothieMap code, when having a concrete user-specified max probability of benign
 * (non-reported) occasion of poor hash code distribution, correction MAE is obtained via linear
 * approximation using the precomputed points: see {@link
 * BinomialDistributionInverseCdfApproximation#normalApproximationCorrection}.
 *
 * Within the limits of the specified parameters MAE varies from approximately -0.05 to 2.73 (see
 * "Min MAE" and "Max MAE" output of this program per probability of success and {@link
 * Output#CORRECTIONS_PER_SKEW_PROB}). In other words, this means that {@link
 * Statistics.NormalDistribution#inverseCumulativeProbability} may overestimate {@link
 * Statistics.BinomialDistribution#inverseCumulativeProbability} by 0.05 or less or underestimate
 * it by 2.73 or less. Somewhat surprisingly, this error almost doesn't depend on the n parameter
 * for binomial distribution (corresponds to the number of segment split events in SmoothieMap), so
 * the number of segment split events (or its logarithm) is not a parameter of the MAE
 * quantification. See a comment regarding SimpleRegression in {@link #main} for more information on
 * this.
 *
 * The cause of the bias (and so the need for MAE correction) is unknown. It might be natural to
 * the approximation of the inverse CDF of binomial distribution with the inverse CDF of normal
 * distribution, or it might come from either {@link Statistics.NormalDistribution} or {@link
 * Statistics.BinomialDistribution} (or both), as they may bias actual normal and binomial
 * distributions themselves (or only their inverseCumulativeProbability() may be biased). See
 * https://stats.stackexchange.com/questions/392281.
 *
 * {@link Output#CORRECTIONS_PER_SKEW_PROB} are used in {@link
 * BinomialDistributionInverseCdfApproximation}.
 */
@SuppressWarnings({"AutoBoxing", "ImplicitNumericConversion"})
final class BinomialDistributionInverseCdfNormalApproximationBias {

    private static final SplittableRandom REPEATABLE_RANDOM = new SplittableRandom(0);

    /**
     * A copy of {@link
     * HashCodeDistribution#HASH_TABLE_HALF__SLOTS_MINUS_MAX_KEYS__SPLIT_CUMULATIVE_PROBS}.
     */
    @SuppressWarnings("FloatingPointLiteralPrecision")
    private static final double[] SKEW_PROBS =
            new double[] {
                    0.1934126528619373175388, // 2 * (1 - CDF[BinomialDistribution[48,0.5], 28])
                    0.1114028910610187494967, // 2 * (1 - CDF[same binomial, 29])
                    0.05946337525377032307006, // 2 * (1 - CDF[same binomial, 30])
                    0.02930494672052930127392 }; // 2 * (1 - CDF[same binomial, 31])

    private static final double NUM_SPLITS_EXP_BASE = 1.01;
    private static final int MIN_SPLITS = 1_000;
    private static final int MAX_SPLITS = 1_000_000_000;

    /** Should be much less than {@link #MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB} */
    private static final double SOLVER_ABSOLUTE_ACCURACY = 0.0000001;
    /** Copied from {@link BaseAbstractUnivariateSolver} */
    private static final double SOLVER_RELATIVE_ACCURACY = 1e-14;
    /**
     * Corresponds to the accuracy of {@link #estimateMeanErrorAndSimpleRegression}. See a comment
     * inside this method.
     */
    private static final double SOLVER_FUNCTION_VALUE_ACCURACY = 0.001;
    private static final AbstractUnivariateSolver SOLVER = new BrentSolver(
            SOLVER_RELATIVE_ACCURACY,
            SOLVER_ABSOLUTE_ACCURACY,
            SOLVER_FUNCTION_VALUE_ACCURACY
    );

    /**
     * This value is made a multiple of 16 so that the array of error corrections (4-byte floats)
     * fits an integral number of cache lines. However, the benefit of that is very elusive (should
     * help to use CPU data cache a little more efficiently). There is a tradeoff between storage
     * space required and the final accuracy of the bias correction. There is no reason why
     * there should be 128 rather than 112 or 144 points.
     *
     * TODO use 2 bytes to store each MAE correction (instead of 4-byte floats) and increase the
     *  number of points (or just reduce the amount of memory used by arrays of precomputed
     *  corrections).
     * 2 bytes is enough because essentially MAE corrections are all fixed point (1.3) with only 4
     * significant digits (that's the precision of {@link #estimateMeanErrorAndSimpleRegression},
     * see a comment inside this method).
     *
     * With 128 points spaced exponentially (so that MAE difference between neighbouring points is
     * approximately the same; see {@link
     * #POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE}) the error of linear
     * approximation over the points is much less than the precision of computed MAEs themselves
     * (0.001), and they are both much less than the root mean square error (RMSE) of the corrected
     * approximation.
     *
     * RMSE of correction grows from approximately 0.02 (closer to {@link
     * #MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB}, for all {@link #SKEW_PROBS}), up to
     * 0.13, 0.14, 0.15 and 0.18 closer to {@link
     * #MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB}, for probabilities from {@link
     * #SKEW_PROBS} array respectively.
     */
    private static final int CORRECTION_POINTS = 128;

    /** Copied {@link SmoothieMap#MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB} */
    private static final double MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB = 0.2;
    /** Copied {@link SmoothieMap#MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB} */
    private static final double MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB = 0.00001;

    /**
     * Choosing points for piecewise linear approximation on the poor hash code distribution
     * probability reporting thresholds range with a simple exponential rule (see [Exponential loop]
     * in {@link #main}) allows to compute the closest points and, therefore, the MAE correction for
     * some given reporting threshold without branches (if we assume that {@link Math#log} is
     * branchless) and without explicitly storing the reporting thresholds corresponding to each
     * computed MAE correction as it would be necessary if binary search was used, thus halving the
     * memory required for MAE correction tables.
     *
     * The differences between neighbouring MAE correction points don't vary dramatically (e. g.
     * they vary from 0.01 to 0.016 for the first of {@link #SKEW_PROBS}, and from 0.015 to 0.023
     * for the last of {@link #SKEW_PROBS}).
     *
     * The alternative approach is to determine reporting threshold points for MAE computation so
     * that the differences between neighbouring MAE correction points are almost equal and use
     * binary search over reporting thresholds (stored in parallel) to navigate to the neighbouring
     * corrections needed for linear approximation. This approach is much more expensive in terms of
     * CPU (binary search incurs many unpredictable jumps on branches) and memory (the array of
     * reporting threshold points is required).
     *
     * Equals 0.9255464154186664.
     */
    private static final double POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE =
            1.0 /
                    Math.pow(
                            MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB /
                                    MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB,
                            1.0 / CORRECTION_POINTS
                    );

    public static void main(String[] args) {
        System.out.println("Exponentiation base: " +
                POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE);

        for (double skewProb : SKEW_PROBS) {
            System.out.println("prob: " + skewProb);
            double min_poorHashCodeDistrib_badOccasion_minRequiredConfidence =
                    1.0 - MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;
            double max_poorHashCodeDistrib_badOccasion_minRequiredConfidence =
                    1.0 - MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;

            // Mean error correction is optimal:
            // see https://math.stackexchange.com/questions/2554243
            double meanErrorForMin_poorHashCodeDistrib_badOccasion_minRequiredConfidence =
                    estimateMeanErrorAndSimpleRegression(skewProb,
                            min_poorHashCodeDistrib_badOccasion_minRequiredConfidence)
                            .getFirst();
            System.out.println("Min MAE: " +
                    meanErrorForMin_poorHashCodeDistrib_badOccasion_minRequiredConfidence);

            double meanErrorForMax_poorHashCodeDistrib_badOccasion_minRequiredConfidence =
                    estimateMeanErrorAndSimpleRegression(skewProb,
                            max_poorHashCodeDistrib_badOccasion_minRequiredConfidence)
                            .getFirst();
            System.out.println("Max MAE: " +
                    meanErrorForMax_poorHashCodeDistrib_badOccasion_minRequiredConfidence);

            double poorHashCodeDistrib_badOccasion_minRequiredConfidence_WithNoBias = SOLVER.solve(
                    Integer.MAX_VALUE,
                    t -> estimateMeanErrorAndSimpleRegression(skewProb, t).getFirst(),
                    min_poorHashCodeDistrib_badOccasion_minRequiredConfidence,
                    max_poorHashCodeDistrib_badOccasion_minRequiredConfidence);
            System.out.println("CDF that yields no inverse CDF bias: " +
                    poorHashCodeDistrib_badOccasion_minRequiredConfidence_WithNoBias);

            List<Float> meanErrorCorrections = new ArrayList<>();

            // Exponential loop
            for (double poorHashCodeDistrib_benignOccasion_maxProb =
                 MAX__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;
                 poorHashCodeDistrib_benignOccasion_maxProb >
                         MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB;
                 poorHashCodeDistrib_benignOccasion_maxProb *=
                         POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__EXP_BASE) {
                double poorHashCodeDistrib_badOccasion_minRequiredConfidence =
                        1.0 - poorHashCodeDistrib_benignOccasion_maxProb;
                System.out.println(poorHashCodeDistrib_badOccasion_minRequiredConfidence);

                Pair<Double, SimpleRegression> meanErrorAndSimpleRegression =
                        estimateMeanErrorAndSimpleRegression(skewProb,
                                poorHashCodeDistrib_badOccasion_minRequiredConfidence);

                float meanErrorCorrection =
                        -(float) (double) meanErrorAndSimpleRegression.getFirst();
                meanErrorCorrections.add(meanErrorCorrection);

                System.out.printf("  rmse=%f, mae=%f%n",
                        estimateRootMeanSquaredError(
                                skewProb,
                                poorHashCodeDistrib_badOccasion_minRequiredConfidence,
                                numSplitsExponentValue -> meanErrorCorrection
                        ),
                        meanErrorCorrection
                );

                // RMSE of SimpleRegression-based correction is printed for illustrative purposes.
                // Errors almost don't depend on "numSplitsExponentValues", that is log with base
                // NUM_SPLITS_EXP_BASE of different numbers of splits (`numSplitsExponentValue`s,
                // in turn, linearly translate to average segment orders of SmoothieMaps). Slope is
                // always very close to zero and gets only slightly more significant when the poor
                // hash code distribution reporting probability threshold approaches
                // MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB (for all four
                // probabilities in SKEW_PROBS alike).
                //
                // SimpleRegression data (slope and intercept, two float values) requires two times
                // more storage space than MAE corrections (a single float value). It allows
                // to improve RMSE of the approximation by at most 0.04 (closer to
                // MIN__POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB).
                //
                // It is chosen to apply MAE correction instead of SimpleRegression-based correction
                // because that 0.04 difference in RMSE of correction almost doesn't affect the
                // accuracy of SmoothieMap's poor hash code distribution reporting, especially
                // considering that the accuracy of the BinomialDistribution class itself (in other
                // words, how well this class estimates real binomial distribution) is probably (?)
                // far worse when the numbers of splits are very big. Such small benefit (if it
                // actually exists) doesn't justify even a little increase of the complexity of
                // SmoothieMap and a little increase of the required storage space.
                SimpleRegression regression =
                        meanErrorAndSimpleRegression.getSecond();
                float intercept = (float) regression.getIntercept();
                float slope = (float) regression.getSlope();
                DoubleUnaryOperator correctionF =
                        numSplitsExponentValue -> -intercept - slope * numSplitsExponentValue;
                System.out.printf("  rmse=%f, simple regression: %f + %f * splitsExponentValue%n",
                        estimateRootMeanSquaredError(skewProb,
                                poorHashCodeDistrib_badOccasion_minRequiredConfidence, correctionF),
                        -intercept,
                        -slope
                );
            }

            meanErrorCorrections.add(
                    -(float) meanErrorForMax_poorHashCodeDistrib_badOccasion_minRequiredConfidence);

            System.out.println(floatListToString(meanErrorCorrections));
        }
    }

    private static Pair<Double, SimpleRegression> estimateMeanErrorAndSimpleRegression(
            double skewProb, double poorHashCodeDistrib_badOccasion_minRequiredConfidence) {
        SimpleRegression regression = new SimpleRegression();

        List<Pair<Integer, Double>> splitsAndExponentValues = new ArrayList<>();
        int numSplitsExponent = 0;
        for (int baseNumSplits = MIN_SPLITS; baseNumSplits < MAX_SPLITS;
             baseNumSplits = (int) (baseNumSplits * NUM_SPLITS_EXP_BASE)) {
            int nextNumSplits = (int) (baseNumSplits * NUM_SPLITS_EXP_BASE);
            // Sample size of 1000 per "splits log" ensures approximately 3-digit precision
            // (+/- 0.001) of MAE. That could be verified by removing seed from REPEATABLE_RANDOM
            // and seeing how this program prints different "Min correction" values from run to
            // run. Final RMSE of MAE correction ranges from 0.02 to 0.18 (see the comment for the
            // CORRECTION_POINTS field). 0.001 inaccuracy in MAE shouldn't considerably affect such
            // (relatively) high RMSE.
            int splitsSampleSize = 1000;
            Collection<Integer> numSplitsSelection =
                    generateRandomSelection(splitsSampleSize, baseNumSplits, nextNumSplits);
            for (Integer numSplits : numSplitsSelection) {
                double splitsExponentValue =
                        numSplitsExponent + ((numSplits - baseNumSplits) / (double) baseNumSplits);
                splitsAndExponentValues.add(new Pair<>(numSplits, splitsExponentValue));
            }
            numSplitsExponent++;
        }
        List<Pair<Double, Double>> splitsExponentValuesAndErrors = splitsAndExponentValues
                .stream()
                .parallel()
                .map(p -> {
                    Integer numSplits = p.getFirst();
                    int binomialResult = new Statistics.BinomialDistribution(numSplits, skewProb)
                            .inverseCumulativeProbability(
                                    poorHashCodeDistrib_badOccasion_minRequiredConfidence);

                    // For computing MAE, approxNormalResult is not rounded (as it should be when
                    // making a real approximation) because presumably it leads only to a loss of
                    // information and the MAE estimation should be more precise when averaging
                    // differences between not rounded normal approximations with actual
                    // BinomialDistribution.inverseCumulativeProbability() values.
                    double approxNormalResult = approxNormalResult(skewProb,
                            poorHashCodeDistrib_badOccasion_minRequiredConfidence, numSplits);

                    return new Pair<>(p.getSecond(), approxNormalResult - binomialResult);
                })
                .collect(Collectors.toList());

        // Using Stream.average() rather than calculating average by manual summation and division,
        // because Stream.average() corrects rounding errors with compensated summation.
        @SuppressWarnings("OptionalGetWithoutIsPresent")
        double meanError = splitsExponentValuesAndErrors
                .stream()
                .mapToDouble(Pair::getSecond)
                .average()
                .getAsDouble();

        splitsExponentValuesAndErrors
                .forEach(p -> regression.addData(p.getFirst(), p.getSecond()));

        return new Pair<>(meanError, regression);
    }

    private static Collection<Integer> generateRandomSelection(
            int sampleSize, int origin, int bound) {
        if (bound - origin <= sampleSize) {
            return IntStream.range(origin, bound).boxed().collect(Collectors.toList());
        }
        Set<Integer> selected;
        if (bound - origin < sampleSize * 2) {
            selected = IntStream.range(origin, bound).boxed().collect(Collectors.toSet());
            while (selected.size() > sampleSize) {
                selected.remove(REPEATABLE_RANDOM.nextInt(origin, bound));
            }
        } else {
            selected = new HashSet<>();
            while (selected.size() < sampleSize) {
                selected.add(REPEATABLE_RANDOM.nextInt(origin, bound));
            }
        }
        return selected;
    }

    private static double estimateRootMeanSquaredError(double skewProb,
            double poorHashCodeDistrib_badOccasion_minRequiredConfidence,
            DoubleUnaryOperator correctionFunction) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int maxSplitsExponent =
                (int) (Math.log(MAX_SPLITS * 1.0 / MIN_SPLITS) / Math.log(NUM_SPLITS_EXP_BASE));
        long squaredErrorsSum = 0;
        int N = 10000;
        for (int i = 0; i < N; i++) {
            int numSplitsExponent = random.nextInt(maxSplitsExponent);
            int baseNumSplits = Math.toIntExact(
                    (long) (MIN_SPLITS * Math.pow(NUM_SPLITS_EXP_BASE, numSplitsExponent)));
            int numSplitsLimit = Math.toIntExact((long) (baseNumSplits * NUM_SPLITS_EXP_BASE));
            int numSplits = random.nextInt(baseNumSplits, numSplitsLimit);
            int binomialResult = new Statistics.BinomialDistribution(numSplits, skewProb)
                    .inverseCumulativeProbability(
                            poorHashCodeDistrib_badOccasion_minRequiredConfidence);

            double approxNormalResult = approxNormalResult(skewProb,
                    poorHashCodeDistrib_badOccasion_minRequiredConfidence, numSplits);

            double numSplitsExponentValue =
                    numSplitsExponent + ((numSplits - baseNumSplits) / (double) baseNumSplits);
            double correction = correctionFunction.applyAsDouble(numSplitsExponentValue);
            double correctedApproxResult = approxNormalResult + correction;
            int error = Math.toIntExact(
                    Math.round(correctedApproxResult) - binomialResult);
            squaredErrorsSum += error * error;
        }
        return Math.sqrt(((double) squaredErrorsSum) / N);
    }

    /**
     * Approximates BinomialDistribution(p, n) as NormalDistribution(np, np(1 - p)), see
     * https://en.wikipedia.org/wiki/Binomial_distribution#Normal_approximation.
     *
     * @param skewProb p of the BinomialDistribution
     * @param numSplits n of the BinomialDistribution
     * @param poorHashCodeDistrib_badOccasion_minRequiredConfidence the argument of inverse CDF.
     */
    private static double approxNormalResult(double skewProb,
            double poorHashCodeDistrib_badOccasion_minRequiredConfidence, int numSplits) {
        double mean = numSplits * skewProb;
        double stdDev = Math.sqrt(numSplits * skewProb * (1 - skewProb));
        return new Statistics.NormalDistribution(mean, stdDev)
                .inverseCumulativeProbability(
                        poorHashCodeDistrib_badOccasion_minRequiredConfidence);
    }

    private static String floatListToString(List<Float> l) {
        return l.stream()
                // It seems that 8-digit precision is always enough to uniquely identify a float
                // value, but here we are "playing it safe" with 10 digits. Using exact precision
                // rather than letting Float.toString() decide that for us to make the output look
                // more regular.
                .map(f -> String.format("%.10ff", f))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * Output of this program, printed by {@link #main}. See the comment in {@link
     * #floatListToString} regarding precision.
     */
    @SuppressWarnings({"unused", "FloatingPointLiteralPrecision"})
    private static class Output {
        private static final float[][] CORRECTIONS_PER_SKEW_PROB = {
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
    }
}
