package io.timeandspace.smoothie;

import org.apache.commons.math3.util.Pair;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Collectors;

/**
 * This class computes data for {@link
 * PrecomputedBinomialCdfValues#NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS}. and {@link
 * PrecomputedBinomialCdfValues#CDF_VALUES}
 */
@SuppressForbidden
@SuppressWarnings("AutoBoxing")
final class ComputeBinomialCdfValues {
    private static final
    double[] PROBS =
            new double[] {
                    0.1934126528619373175388, // 2 * (1 - CDF[BinomialDistribution[48,0.5], 28])
                    0.1114028910610187494967, // 2 * (1 - CDF[same binomial, 29])
                    0.05946337525377032307006, // 2 * (1 - CDF[same binomial, 30])
                    0.02930494672052930127392 }; // 2 * (1 - CDF[same binomial, 31])

    private static final int MAX_TRIALS =
            PrecomputedBinomialCdfValues.MAX_SPLITS_WITH_PRECOMPUTED_CDF_VALUES;

    /** As in SmoothieMap TODO javadoc link */
    private static final double MAX_POOR_HASH_CODE_DISTRIB_PROB_THRESHOLD = 0.2;
    /** As in SmoothieMap TODO javadoc link */
    private static final double MIN_POOR_HASH_CODE_DISTRIB_PROB_THRESHOLD = 0.00001;

    private static final double MIN_ALLOWANCE_THRESHOLD =
            1.0 - MAX_POOR_HASH_CODE_DISTRIB_PROB_THRESHOLD;
    private static final double MAX_ALLOWANCE_THRESHOLD =
            1.0 - MIN_POOR_HASH_CODE_DISTRIB_PROB_THRESHOLD;

    public static void main(String[] args) {
        for (double probabilityOfSuccess : PROBS) {
            computeBinomialCdfValues(probabilityOfSuccess);
        }
    }

    private static void computeBinomialCdfValues(double probabilityOfSuccess) {
        System.out.println("Prob: " + probabilityOfSuccess);
        List<List<Float>> trackedCdfValuesPerTrials = new ArrayList<>();
        // Stored in PrecomputedBinomialCdfValues.NUM_SKEWED_SEGMENTS__FIRST_TRACKED_FOR_NUM_SPLITS.
        List<Integer> firstTrackedSkewedSegmentsPerTrials = new ArrayList<>();
        int nextStartSearchSkewedSegments = 0;
        for (int trials = 1; trials <= MAX_TRIALS; trials++) {
            IntToDoubleFunction cdf = cachingBinomialCdf(probabilityOfSuccess, trials);
            Pair<Integer, List<Float>> firstTrackedSkewedSegmentsAndCdfValues =
                    computeFirstTrackedSkewedSegmentsAndCdfValues(
                            trials, cdf, nextStartSearchSkewedSegments);
            firstTrackedSkewedSegmentsPerTrials.add(
                    firstTrackedSkewedSegmentsAndCdfValues.getFirst());
            System.out.println(trials + ": " +
                    floatListToString(firstTrackedSkewedSegmentsAndCdfValues.getSecond()));

            // Printing output of Statistics.BinomialDistribution for showing how much does it
            // diverge from the actual binomial distribution. It turns out that with the precision
            // of 32-bit floats outputs of BinomialDistribution.cumulativeProbability always exactly
            // correspond to the outputs of binomialCdf().
            IntToDoubleFunction approximateCdf =
                    new Statistics.BinomialDistribution(trials, probabilityOfSuccess)
                            ::cumulativeProbability;
            System.out.println(trials + ": " +
                    floatListToString(
                            computeFirstTrackedSkewedSegmentsAndCdfValues(
                                    trials, approximateCdf, nextStartSearchSkewedSegments)
                                    .getSecond()));

            nextStartSearchSkewedSegments = firstTrackedSkewedSegmentsAndCdfValues.getFirst();
            trackedCdfValuesPerTrials.add(firstTrackedSkewedSegmentsAndCdfValues.getSecond());
        }
        System.out.println(firstTrackedSkewedSegmentsPerTrials);
        for (List<Float> trackedCdfValuesPerTrial : trackedCdfValuesPerTrials) {
            System.out.println(floatListToString(trackedCdfValuesPerTrial) + ",");
        }
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

    private static Pair<Integer, List<Float>> computeFirstTrackedSkewedSegmentsAndCdfValues(
            int trials, IntToDoubleFunction cdf, int startSearchSkewedSegments) {
        List<Float> cdfValues = new ArrayList<>();
        int skewedSegments = startSearchSkewedSegments;
        while (cdf.applyAsDouble(skewedSegments) < MIN_ALLOWANCE_THRESHOLD) {
            skewedSegments++;
        }
        int firstTrackedSkewedSegments = skewedSegments;
        for (; skewedSegments <= trials; skewedSegments++) {
            double cumulativeProb = cdf.applyAsDouble(skewedSegments);
            if (cumulativeProb > MAX_ALLOWANCE_THRESHOLD) {
                break;
            }
            cdfValues.add((float) cumulativeProb);
        }
        return new Pair<>(firstTrackedSkewedSegments, cdfValues);
    }

    private static IntToDoubleFunction cachingBinomialCdf(double probabilityOfSuccess, int trials) {
        BigDecimal[] cachedCdfs = new BigDecimal[trials + 1];
        return k -> {
            BigDecimal prevCdf = k > 0 ? cachedCdfs[k - 1] : BigDecimal.ZERO;
            BigDecimal cdf;
            if (prevCdf == null) {
                cdf = binomialCdf(trials, k, probabilityOfSuccess);
            } else {
                cdf = prevCdf.add(binomialPdf(trials, k, probabilityOfSuccess));
            }
            cachedCdfs[k] = cdf;
            return cdf.doubleValue();
        };
    }

    private static BigDecimal binomialCdf(int n, int k, double prob) {
        BigDecimal cdf = BigDecimal.ZERO;
        for (int i = 0; i <= k; i++) {
            cdf = cdf.add(binomialPdf(n, i, prob));
        }
        return cdf;
    }

    private static BigDecimal binomialPdf(int n, int k, double prob) {
        BigDecimal binomialCoeff = binomialCoeff(n, k);
        BigDecimal pK = BigDecimal.valueOf(prob).pow(k);
        BigDecimal inv = BigDecimal.valueOf(1 - prob).pow(n - k);
        return binomialCoeff.multiply(pK).multiply(inv);
    }

    private static BigDecimal binomialCoeff(int n, int k) {
        return new BigDecimal(FACTORIALS[n]).divide(
                new BigDecimal(FACTORIALS[k].multiply(FACTORIALS[n - k])), RoundingMode.HALF_DOWN);
    }

    private static final BigInteger[] FACTORIALS = new BigInteger[MAX_TRIALS + 2];
    static {
        for (int i = 0; i <= MAX_TRIALS + 1; i++) {
            FACTORIALS[i] = factorial(i);
        }
    }

    private static BigInteger factorial(int n) {
        BigInteger result = BigInteger.ONE;
        for (int i = 1; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf((long) i));
        }

        return result;
    }
}
