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

import java.util.function.DoubleUnaryOperator;


/**
 * Nested classes are copied from Apache Commons Numbers and Apache Commons Statistics projects with
 * minimal modifications. Javadoc of each class contains a reference to the exact version of the
 * file, to make it easier to track improvements and bug fixes.
 *
 * To not be confused with "Stats" classes: {@link OrdinarySegmentStats}, {@link KeySearchStats},
 * {@link SmoothieMapStats}, which are about actual empirical properties of (SmoothieMap) algorithm
 * obtained via direct counting.
 *
 * TODO consider replace copying with dependency when Commons Numbers and Commons Statistics are
 *  released.
 *
 * Copyright (C) The Apache Software Foundation
 */
@SuppressWarnings({"ImplicitNumericConversion", "FloatingPointLiteralPrecision"})
final class Statistics {
    //CHECKSTYLE:OFF: MagicNumber

    private Statistics() {}

    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/ContinuousDistribution.java
     *
     * Base interface for distributions on the reals.
     */
    public interface ContinuousDistribution {
        /**
         * For a random variable {@code X} whose values are distributed according
         * to this distribution, this method returns {@code P(X = x)}.
         * In other words, this method represents the probability mass function
         * (PMF) for the distribution.
         *
         * @param x Point at which the PMF is evaluated.
         * @return the value of the probability mass function at point {@code x}.
         */
        default double probability(double x) {
            return 0;
        }

        /**
         * For a random variable {@code X} whose values are distributed according
         * to this distribution, this method returns {@code P(x0 < X <= x1)}.
         * The default implementation uses the identity
         * {@code P(x0 < X <= x1) = P(X <= x1) - P(X <= x0)}
         *
         * @param x0 Lower bound (exclusive).
         * @param x1 Upper bound (inclusive).
         * @return the probability that a random variable with this distribution
         * takes a value between {@code x0} and {@code x1},  excluding the lower
         * and including the upper endpoint.
         * @throws IllegalArgumentException if {@code x0 > x1}.
         */
        default double probability(double x0,
                double x1) {
            if (x0 > x1) {
                throw new IllegalArgumentException(x0 + " > " + x1);
            }
            return cumulativeProbability(x1) - cumulativeProbability(x0);
        }

        /**
         * Returns the probability density function (PDF) of this distribution
         * evaluated at the specified point {@code x}.
         * In general, the PDF is the derivative of the {@link #cumulativeProbability(double) CDF}.
         * If the derivative does not exist at {@code x}, then an appropriate
         * replacement should be returned, e.g. {@code Double.POSITIVE_INFINITY},
         * {@code Double.NaN}, or  the limit inferior or limit superior of the
         * difference quotient.
         *
         * @param x Point at which the PDF is evaluated.
         * @return the value of the probability density function at {@code x}.
         */
        double density(double x);

        /**
         * Returns the natural logarithm of the probability density function
         * (PDF) of this distribution evaluated at the specified point {@code x}.
         *
         * @param x Point at which the PDF is evaluated.
         * @return the logarithm of the value of the probability density function
         * at {@code x}.
         */
        default double logDensity(double x) {
            return Math.log(density(x));
        }

        /**
         * For a random variable {@code X} whose values are distributed according
         * to this distribution, this method returns {@code P(X <= x)}.
         * In other words, this method represents the (cumulative) distribution
         * function (CDF) for this distribution.
         *
         * @param x Point at which the CDF is evaluated.
         * @return the probability that a random variable with this
         * distribution takes a value less than or equal to {@code x}.
         */
        double cumulativeProbability(double x);

        /**
         * Computes the quantile function of this distribution. For a random
         * variable {@code X} distributed according to this distribution, the
         * returned value is
         * <ul>
         * <li>{@code inf{x in R | P(X<=x) >= p}} for {@code 0 < p <= 1},</li>
         * <li>{@code inf{x in R | P(X<=x) > 0}} for {@code p = 0}.</li>
         * </ul>
         *
         * @param p Cumulative probability.
         * @return the smallest {@code p}-quantile of this distribution
         * (largest 0-quantile for {@code p = 0}).
         * @throws IllegalArgumentException if {@code p < 0} or {@code p > 1}.
         */
        double inverseCumulativeProbability(double p);

        /**
         * Gets the mean of this distribution.
         *
         * @return the mean, or {@code Double.NaN} if it is not defined.
         */
        double getMean();

        /**
         * Gets the variance of this distribution.
         *
         * @return the variance, or {@code Double.NaN} if it is not defined.
         */
        double getVariance();

        /**
         * Gets the lower bound of the support.
         * It must return the same value as
         * {@code inverseCumulativeProbability(0)}, i.e.
         * {@code inf {x in R | P(X <= x) > 0}}.
         *
         * @return the lower bound of the support.
         */
        double getSupportLowerBound();

        /**
         * Gets the upper bound of the support.
         * It must return the same
         * value as {@code inverseCumulativeProbability(1)}, i.e.
         * {@code inf {x in R | P(X <= x) = 1}}.
         *
         * @return the upper bound of the support.
         */
        double getSupportUpperBound();

        /**
         * Indicates whether the support is connected, i.e. whether
         * all values between the lower and upper bound of the support
         * are included in the support.
         *
         * @return whether the support is connected.
         */
        boolean isSupportConnected();
    }


    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/AbstractContinuousDistribution.java
     *
     * Base class for probability distributions on the reals.
     * Default implementations are provided for some of the methods
     * that do not vary from distribution to distribution.
     */
    abstract static class AbstractContinuousDistribution
            implements ContinuousDistribution {
        /**
         * {@inheritDoc}
         *
         * The default implementation returns
         * <ul>
         * <li>{@link #getSupportLowerBound()} for {@code p = 0},</li>
         * <li>{@link #getSupportUpperBound()} for {@code p = 1}.</li>
         * </ul>
         */
        @Override
        public double inverseCumulativeProbability(final double p) {
            /*
             * IMPLEMENTATION NOTES
             * --------------------
             * Where applicable, use is made of the one-sided Chebyshev inequality
             * to bracket the root. This inequality states that
             * P(X - mu >= k * sig) <= 1 / (1 + k^2),
             * mu: mean, sig: standard deviation. Equivalently
             * 1 - P(X < mu + k * sig) <= 1 / (1 + k^2),
             * F(mu + k * sig) >= k^2 / (1 + k^2).
             *
             * For k = sqrt(p / (1 - p)), we find
             * F(mu + k * sig) >= p,
             * and (mu + k * sig) is an upper-bound for the root.
             *
             * Then, introducing Y = -X, mean(Y) = -mu, sd(Y) = sig, and
             * P(Y >= -mu + k * sig) <= 1 / (1 + k^2),
             * P(-X >= -mu + k * sig) <= 1 / (1 + k^2),
             * P(X <= mu - k * sig) <= 1 / (1 + k^2),
             * F(mu - k * sig) <= 1 / (1 + k^2).
             *
             * For k = sqrt((1 - p) / p), we find
             * F(mu - k * sig) <= p,
             * and (mu - k * sig) is a lower-bound for the root.
             *
             * In cases where the Chebyshev inequality does not apply, geometric
             * progressions 1, 2, 4, ... and -1, -2, -4, ... are used to bracket
             * the root.
             */
            if (p < 0 ||
                    p > 1) {
                throw new IllegalArgumentException(p + " out of range [0, 1]");
            }

            double lowerBound = getSupportLowerBound();
            if (p == 0) {
                return lowerBound;
            }

            double upperBound = getSupportUpperBound();
            if (p == 1) {
                return upperBound;
            }

            final double mu = getMean();
            final double sig = Math.sqrt(getVariance());
            final boolean chebyshevApplies;
            chebyshevApplies = !(Double.isInfinite(mu) ||
                    Double.isNaN(mu) ||
                    Double.isInfinite(sig) ||
                    Double.isNaN(sig));

            if (lowerBound == Double.NEGATIVE_INFINITY) {
                if (chebyshevApplies) {
                    lowerBound = mu - sig * Math.sqrt((1 - p) / p);
                } else {
                    lowerBound = -1;
                    while (cumulativeProbability(lowerBound) >= p) {
                        lowerBound *= 2;
                    }
                }
            }

            if (upperBound == Double.POSITIVE_INFINITY) {
                if (chebyshevApplies) {
                    upperBound = mu + sig * Math.sqrt(p / (1 - p));
                } else {
                    upperBound = 1;
                    while (cumulativeProbability(upperBound) < p) {
                        upperBound *= 2;
                    }
                }
            }

            // XXX Values copied from defaults in class
            // "o.a.c.math4.analysis.solvers.BaseAbstractUnivariateSolver"
            final double solverRelativeAccuracy = 1e-14;
            final double solverAbsoluteAccuracy = 1e-9;
            final double solverFunctionValueAccuracy = 1e-15;

            double x = new BrentSolver(solverRelativeAccuracy,
                    solverAbsoluteAccuracy,
                    solverFunctionValueAccuracy)
                    .solve((arg) -> cumulativeProbability(arg) - p,
                            lowerBound,
                            0.5 * (lowerBound + upperBound),
                            upperBound);

            if (!isSupportConnected()) {
                /* Test for plateau. */
                final double dx = solverAbsoluteAccuracy;
                if (x - dx >= getSupportLowerBound()) {
                    double px = cumulativeProbability(x);
                    if (cumulativeProbability(x - dx) == px) {
                        upperBound = x;
                        while (upperBound - lowerBound > dx) {
                            final double midPoint = 0.5 * (lowerBound + upperBound);
                            if (cumulativeProbability(midPoint) < px) {
                                lowerBound = midPoint;
                            } else {
                                upperBound = midPoint;
                            }
                        }
                        return upperBound;
                    }
                }
            }
            return x;
        }

        /**
         * This class implements the <a href="http://mathworld.wolfram.com/BrentsMethod.html">
         * Brent algorithm</a> for finding zeros of real univariate functions.
         * The function should be continuous but not necessarily smooth.
         * The {@code solve} method returns a zero {@code x} of the function {@code f}
         * in the given interval {@code [a, b]} to within a tolerance
         * {@code 2 eps abs(x) + t} where {@code eps} is the relative accuracy and
         * {@code t} is the absolute accuracy.
         * <p>The given interval must bracket the root.</p>
         * <p>
         *  The reference implementation is given in chapter 4 of
         *  <blockquote>
         *   <b>Algorithms for Minimization Without Derivatives</b>,
         *   <em>Richard P. Brent</em>,
         *   Dover, 2002
         *  </blockquote>
         *
         * Used by {@link AbstractContinuousDistribution#inverseCumulativeProbability(double)}.
         */
        private static class BrentSolver {
            /** Relative accuracy. */
            private final double relativeAccuracy;
            /** Absolutee accuracy. */
            private final double absoluteAccuracy;
            /** Function accuracy. */
            private final double functionValueAccuracy;

            /**
             * Construct a solver.
             *
             * @param relativeAccuracy Relative accuracy.
             * @param absoluteAccuracy Absolute accuracy.
             * @param functionValueAccuracy Function value accuracy.
             */
            BrentSolver(double relativeAccuracy,
                    double absoluteAccuracy,
                    double functionValueAccuracy) {
                this.relativeAccuracy = relativeAccuracy;
                this.absoluteAccuracy = absoluteAccuracy;
                this.functionValueAccuracy = functionValueAccuracy;
            }

            /**
             * @param func Function to solve.
             * @param min Lower bound.
             * @param initial Initial guess.
             * @param max Upper bound.
             * @return the root.
             */
            double solve(DoubleUnaryOperator func,
                    double min,
                    double initial,
                    double max) {
                if (min > max) {
                    throw new IllegalArgumentException(min + " > " + max);
                }
                if (initial < min ||
                        initial > max) {
                    throw new IllegalArgumentException(
                            initial + " out of range [" + min + ", " + max + "]");
                }

                // Return the initial guess if it is good enough.
                double yInitial = func.applyAsDouble(initial);
                if (Math.abs(yInitial) <= functionValueAccuracy) {
                    return initial;
                }

                // Return the first endpoint if it is good enough.
                double yMin = func.applyAsDouble(min);
                if (Math.abs(yMin) <= functionValueAccuracy) {
                    return min;
                }

                // Reduce interval if min and initial bracket the root.
                if (yInitial * yMin < 0) {
                    return brent(func, min, initial, yMin, yInitial);
                }

                // Return the second endpoint if it is good enough.
                double yMax = func.applyAsDouble(max);
                if (Math.abs(yMax) <= functionValueAccuracy) {
                    return max;
                }

                // Reduce interval if initial and max bracket the root.
                if (yInitial * yMax < 0) {
                    return brent(func, initial, max, yInitial, yMax);
                }

                throw new IllegalArgumentException(
                        "No bracketing: f(" + min + ")=" + yMin + ", f(" + max + ")=" + yMax);
            }

            /**
             * Search for a zero inside the provided interval.
             * This implementation is based on the algorithm described at page 58 of
             * the book
             * <blockquote>
             *  <b>Algorithms for Minimization Without Derivatives</b>,
             *  <it>Richard P. Brent</it>,
             *  Dover 0-486-41998-3
             * </blockquote>
             *
             * @param func Function to solve.
             * @param lo Lower bound of the search interval.
             * @param hi Higher bound of the search interval.
             * @param fLo Function value at the lower bound of the search interval.
             * @param fHi Function value at the higher bound of the search interval.
             * @return the value where the function is zero.
             */
            private double brent(DoubleUnaryOperator func,
                    double lo, double hi,
                    double fLo, double fHi) {
                double a = lo;
                double fa = fLo;
                double b = hi;
                double fb = fHi;
                double c = a;
                double fc = fa;
                double d = b - a;
                double e = d;

                final double t = absoluteAccuracy;
                final double eps = relativeAccuracy;

                while (true) {
                    if (Math.abs(fc) < Math.abs(fb)) {
                        a = b;
                        b = c;
                        c = a;
                        fa = fb;
                        fb = fc;
                        fc = fa;
                    }

                    final double tol = 2 * eps * Math.abs(b) + t;
                    final double m = 0.5 * (c - b);

                    if (Math.abs(m) <= tol ||
                            Precision.equals(fb, 0))  {
                        return b;
                    }
                    if (Math.abs(e) < tol ||
                            Math.abs(fa) <= Math.abs(fb)) {
                        // Force bisection.
                        d = m;
                        e = d;
                    } else {
                        double s = fb / fa;
                        double p;
                        double q;
                        // The equality test (a == c) is intentional,
                        // it is part of the original Brent's method and
                        // it should NOT be replaced by proximity test.
                        if (a == c) {
                            // Linear interpolation.
                            p = 2 * m * s;
                            q = 1 - s;
                        } else {
                            // Inverse quadratic interpolation.
                            q = fa / fc;
                            final double r = fb / fc;
                            p = s * (2 * m * q * (q - r) - (b - a) * (r - 1));
                            q = (q - 1) * (r - 1) * (s - 1);
                        }
                        if (p > 0) {
                            q = -q;
                        } else {
                            p = -p;
                        }
                        s = e;
                        e = d;
                        if (p >= 1.5 * m * q - Math.abs(tol * q) ||
                                p >= Math.abs(0.5 * s * q)) {
                            // Inverse quadratic interpolation gives a value
                            // in the wrong direction, or progress is slow.
                            // Fall back to bisection.
                            d = m;
                            e = d;
                        } else {
                            d = p / q;
                        }
                    }
                    a = b;
                    fa = fb;

                    if (Math.abs(d) > tol) {
                        b += d;
                    } else if (m > 0) {
                        b += tol;
                    } else {
                        b -= tol;
                    }
                    fb = func.applyAsDouble(b);
                    if ((fb > 0 && fc > 0) ||
                            (fb <= 0 && fc <= 0)) {
                        c = a;
                        fc = fa;
                        d = b - a;
                        e = d;
                    }
                }
            }
        }
    }


    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/NormalDistribution.java
     */
    public static class NormalDistribution extends AbstractContinuousDistribution {
        /** &radic;(2) */
        private static final double SQRT2 = Math.sqrt(2.0);
        /** Mean of this distribution. */
        private final double mean;
        /** Standard deviation of this distribution. */
        private final double standardDeviation;
        /** The value of {@code log(sd) + 0.5*log(2*pi)} stored for faster computation. */
        private final double logStandardDeviationPlusHalfLog2Pi;

        /**
         * Creates a distribution.
         *
         * @param mean Mean for this distribution.
         * @param sd Standard deviation for this distribution.
         * @throws IllegalArgumentException if {@code sd <= 0}.
         */
        NormalDistribution(double mean,
                double sd) {
            if (sd <= 0) {
                throw new IllegalArgumentException(sd + " <= 0");
            }

            this.mean = mean;
            standardDeviation = sd;
            logStandardDeviationPlusHalfLog2Pi = Math.log(sd) + 0.5 * Math.log(2 * Math.PI);
        }

        /**
         * Access the standard deviation.
         *
         * @return the standard deviation for this distribution.
         */
        double getStandardDeviation() {
            return standardDeviation;
        }

        /** {@inheritDoc} */
        @Override
        public double density(double x) {
            return Math.exp(logDensity(x));
        }

        /** {@inheritDoc} */
        @Override
        public double logDensity(double x) {
            final double x0 = x - mean;
            final double x1 = x0 / standardDeviation;
            return -0.5 * x1 * x1 - logStandardDeviationPlusHalfLog2Pi;
        }

        /**
         * {@inheritDoc}
         *
         * If {@code x} is more than 40 standard deviations from the mean, 0 or 1
         * is returned, as in these cases the actual value is within
         * {@code Double.MIN_VALUE} of 0 or 1.
         */
        @Override
        public double cumulativeProbability(double x)  {
            final double dev = x - mean;
            if (Math.abs(dev) > 40 * standardDeviation) {
                return dev < 0 ? 0.0d : 1.0d;
            }
            return 0.5 * Erfc.value(-dev / (standardDeviation * SQRT2));
        }

        /** {@inheritDoc} */
        @Override
        public double inverseCumulativeProbability(final double p) {
            if (p < 0 ||
                    p > 1) {
                throw new IllegalArgumentException(p + " out of range [0, 1]");
            }
            return mean + standardDeviation * SQRT2 * InverseErf.value(2 * p - 1);
        }

        public static double inverseCumulativeProbability(double mean, double sd, double p) {
            if (sd <= 0) {
                throw new IllegalArgumentException(sd + " <= 0");
            }
            if (p < 0 ||
                    p > 1) {
                throw new IllegalArgumentException(p + " out of range [0, 1]");
            }
            return mean + sd * SQRT2 * InverseErf.value(2 * p - 1);
        }

        /** {@inheritDoc} */
        @Override
        public double probability(double x0,
                double x1) {
            if (x0 > x1) {
                throw new IllegalArgumentException(x0 + " > " + x1);
            }
            final double denom = standardDeviation * SQRT2;
            final double v0 = (x0 - mean) / denom;
            final double v1 = (x1 - mean) / denom;
            return 0.5 * ErfDifference.value(v0, v1);
        }

        /** {@inheritDoc} */
        @Override
        public double getMean() {
            return mean;
        }

        /**
         * {@inheritDoc}
         *
         * For standard deviation parameter {@code s}, the variance is {@code s^2}.
         */
        @Override
        public double getVariance() {
            final double s = getStandardDeviation();
            return s * s;
        }

        /**
         * {@inheritDoc}
         *
         * The lower bound of the support is always negative infinity
         * no matter the parameters.
         *
         * @return lower bound of the support (always
         * {@code Double.NEGATIVE_INFINITY})
         */
        @Override
        public double getSupportLowerBound() {
            return Double.NEGATIVE_INFINITY;
        }

        /**
         * {@inheritDoc}
         *
         * The upper bound of the support is always positive infinity
         * no matter the parameters.
         *
         * @return upper bound of the support (always
         * {@code Double.POSITIVE_INFINITY})
         */
        @Override
        public double getSupportUpperBound() {
            return Double.POSITIVE_INFINITY;
        }

        /**
         * {@inheritDoc}
         *
         * The support of this distribution is connected.
         *
         * @return {@code true}
         */
        @Override
        public boolean isSupportConnected() {
            return true;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * 463183281c06a2398a70a00651fbb5feab5b0413/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/Erf.java
     *
     * <a href="http://mathworld.wolfram.com/Erf.html">Error function</a>.
     */
    static class Erf {
        /**
         * <p>
         * This implementation computes erf(x) using the
         * {@link RegularizedGamma.P#value(double, double, double, int) regularized gamma function},
         * following <a href="http://mathworld.wolfram.com/Erf.html"> Erf</a>, equation (3)
         * </p>
         *
         * <p>
         * The returned value is always between -1 and 1 (inclusive).
         * If {@code abs(x) > 40}, then {@code Erf.value(x)} is indistinguishable from
         * either 1 or -1 at {@code double} precision, so the appropriate extreme value
         * is returned.
         * </p>
         *
         * @param x the value.
         * @return the error function.
         * @throws ArithmeticException if the algorithm fails to converge.
         *
         * @see RegularizedGamma.P#value(double, double, double, int)
         */
        public static double value(double x) {
            if (Math.abs(x) > 40) {
                return x > 0 ? 1 : -1;
            }
            final double ret = RegularizedGamma.P.value(0.5, x * x, 1e-15, 10000);
            return x < 0 ? -ret : ret;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * 463183281c06a2398a70a00651fbb5feab5b0413/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/InverseErf.java
     *
     * Inverse of the <a href="http://mathworld.wolfram.com/Erf.html">error function</a>.
     * <p>
     * This implementation is described in the paper:
     * <a href="http://people.maths.ox.ac.uk/gilesm/files/gems_erfinv.pdf">Approximating
     * the erfinv function</a> by Mike Giles, Oxford-Man Institute of Quantitative Finance,
     * which was published in GPU Computing Gems, volume 2, 2010.
     * The source code is available <a href="http://gpucomputing.net/?q=node/1828">here</a>.
     * </p>
     */
    static class InverseErf {
        /**
         * Returns the inverse error function.
         *
         * @param x Value.
         * @return t such that {@code x =} {@link Erf#value(double) Erf.value(t)}.
         */
        public static double value(final double x) {
            // Beware that the logarithm argument must be
            // commputed as (1 - x) * (1 + x),
            // it must NOT be simplified as 1 - x * x as this
            // would induce rounding errors near the boundaries +/-1
            double w = -Math.log((1 - x) * (1 + x));
            double p;

            if (w < 6.25) {
                w -= 3.125;
                p =  -3.6444120640178196996e-21;
                p =   -1.685059138182016589e-19 + p * w;
                p =   1.2858480715256400167e-18 + p * w;
                p =    1.115787767802518096e-17 + p * w;
                p =   -1.333171662854620906e-16 + p * w;
                p =   2.0972767875968561637e-17 + p * w;
                p =   6.6376381343583238325e-15 + p * w;
                p =  -4.0545662729752068639e-14 + p * w;
                p =  -8.1519341976054721522e-14 + p * w;
                p =   2.6335093153082322977e-12 + p * w;
                p =  -1.2975133253453532498e-11 + p * w;
                p =  -5.4154120542946279317e-11 + p * w;
                p =    1.051212273321532285e-09 + p * w;
                p =  -4.1126339803469836976e-09 + p * w;
                p =  -2.9070369957882005086e-08 + p * w;
                p =   4.2347877827932403518e-07 + p * w;
                p =  -1.3654692000834678645e-06 + p * w;
                p =  -1.3882523362786468719e-05 + p * w;
                p =    0.0001867342080340571352 + p * w;
                p =  -0.00074070253416626697512 + p * w;
                p =   -0.0060336708714301490533 + p * w;
                p =      0.24015818242558961693 + p * w;
                p =       1.6536545626831027356 + p * w;
            } else if (w < 16.0) {
                w = Math.sqrt(w) - 3.25;
                p =   2.2137376921775787049e-09;
                p =   9.0756561938885390979e-08 + p * w;
                p =  -2.7517406297064545428e-07 + p * w;
                p =   1.8239629214389227755e-08 + p * w;
                p =   1.5027403968909827627e-06 + p * w;
                p =   -4.013867526981545969e-06 + p * w;
                p =   2.9234449089955446044e-06 + p * w;
                p =   1.2475304481671778723e-05 + p * w;
                p =  -4.7318229009055733981e-05 + p * w;
                p =   6.8284851459573175448e-05 + p * w;
                p =   2.4031110387097893999e-05 + p * w;
                p =   -0.0003550375203628474796 + p * w;
                p =   0.00095328937973738049703 + p * w;
                p =   -0.0016882755560235047313 + p * w;
                p =    0.0024914420961078508066 + p * w;
                p =   -0.0037512085075692412107 + p * w;
                p =     0.005370914553590063617 + p * w;
                p =       1.0052589676941592334 + p * w;
                p =       3.0838856104922207635 + p * w;
            } else if (!Double.isInfinite(w)) {
                w = Math.sqrt(w) - 5;
                p =  -2.7109920616438573243e-11;
                p =  -2.5556418169965252055e-10 + p * w;
                p =   1.5076572693500548083e-09 + p * w;
                p =  -3.7894654401267369937e-09 + p * w;
                p =   7.6157012080783393804e-09 + p * w;
                p =  -1.4960026627149240478e-08 + p * w;
                p =   2.9147953450901080826e-08 + p * w;
                p =  -6.7711997758452339498e-08 + p * w;
                p =   2.2900482228026654717e-07 + p * w;
                p =  -9.9298272942317002539e-07 + p * w;
                p =   4.5260625972231537039e-06 + p * w;
                p =  -1.9681778105531670567e-05 + p * w;
                p =   7.5995277030017761139e-05 + p * w;
                p =  -0.00021503011930044477347 + p * w;
                p =  -0.00013871931833623122026 + p * w;
                p =       1.0103004648645343977 + p * w;
                p =       4.8499064014085844221 + p * w;
            } else {
                // this branch does not appears in the original code, it
                // was added because the previous branch does not handle
                // x = +/-1 correctly. In this case, w is positive infinity
                // and as the first coefficient (-2.71e-11) is negative.
                // Once the first multiplication is done, p becomes negative
                // infinity and remains so throughout the polynomial evaluation.
                // So the branch above incorrectly returns negative infinity
                // instead of the correct positive infinity.
                p = Double.POSITIVE_INFINITY;
            }

            return p * x;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * 463183281c06a2398a70a00651fbb5feab5b0413/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/ErfDifference.java
     *
     * Computes the difference between {@link Erf error function values}.
     */
    public static class ErfDifference {
        /**
         * This number solves {@code erf(x) = 0.5} within 1 ulp.
         * More precisely, the current implementations of
         * {@link Erf#value(double)} and {@link Erfc#value(double)} satisfy:
         * <ul>
         *  <li>{@code Erf.value(X_CRIT) < 0.5},</li>
         *  <li>{@code Erf.value(Math.nextUp(X_CRIT) > 0.5},</li>
         *  <li>{@code Erfc.value(X_CRIT) = 0.5}, and</li>
         *  <li>{@code Erfc.value(Math.nextUp(X_CRIT) < 0.5}</li>
         * </ul>
         */
        private static final double X_CRIT = 0.4769362762044697;

        /**
         * The implementation uses either {@link Erf} or {@link Erfc},
         * depending on which provides the most precise result.
         *
         * @param x1 First value.
         * @param x2 Second value.
         * @return {@link Erf#value(double) Erf.value(x2) - Erf.value(x1)}.
         */
        public static double value(double x1,
                double x2) {
            if (x1 > x2) {
                return -value(x2, x1);
            } else {
                if (x1 < -X_CRIT) {
                    if (x2 < 0) {
                        return Erfc.value(-x2) - Erfc.value(-x1);
                    } else {
                        return Erf.value(x2) - Erf.value(x1);
                    }
                } else {
                    if (x2 > X_CRIT &&
                            x1 > 0) {
                        return Erfc.value(x1) - Erfc.value(x2);
                    } else {
                        return Erf.value(x2) - Erf.value(x1);
                    }
                }
            }
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * 463183281c06a2398a70a00651fbb5feab5b0413/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/Erfc.java
     *
     * <a href="http://mathworld.wolfram.com/Erfc.html">Complementary error function</a>.
     */
    public static class Erfc {
        /**
         * <p>
         * This implementation computes erfc(x) using the
         * {@link RegularizedGamma.Q#value(double, double, double, int) regularized gamma function},
         * following <a href="http://mathworld.wolfram.com/Erf.html">Erf</a>, equation (3).
         * </p>
         *
         * <p>
         * The value returned is always between 0 and 2 (inclusive).
         * If {@code abs(x) > 40}, then {@code erf(x)} is indistinguishable from
         * either 0 or 2 at {@code double} precision, so the appropriate extreme
         * value is returned.
         * </p>
         *
         * @param x Value.
         * @return the complementary error function.
         * @throws ArithmeticException if the algorithm fails to converge.
         *
         * @see RegularizedGamma.Q#value(double, double, double, int)
         */
        public static double value(double x) {
            if (Math.abs(x) > 40) {
                return x > 0 ? 0 : 2;
            }
            final double ret = RegularizedGamma.Q.value(0.5, x * x, 1e-15, 10000);
            return x < 0 ?
                    2 - ret :
                    ret;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/AbstractDiscreteDistribution.java
     *
     * Base class for integer-valued discrete distributions.  Default
     * implementations are provided for some of the methods that do not vary
     * from distribution to distribution.
     */
    abstract static class AbstractDiscreteDistribution {

        abstract double cumulativeProbability(int x);

        public abstract double getMean();

        public abstract double getVariance();

        abstract int getSupportLowerBound();

        abstract int getSupportUpperBound();

        /**
         * {@inheritDoc}
         *
         * The default implementation returns
         * <ul>
         * <li>{@link #getSupportLowerBound()} for {@code p = 0},</li>
         * <li>{@link #getSupportUpperBound()} for {@code p = 1}, and</li>
         * <li>{@link #solveInverseCumulativeProbability(double, int, int)} for
         *     {@code 0 < p < 1}.</li>
         * </ul>
         */
        int inverseCumulativeProbability(final double p) {
            if (p < 0 ||
                    p > 1) {
                throw new IllegalArgumentException(p + " out of range [0, 1]");
            }

            int lower = getSupportLowerBound();
            if (p == 0.0) {
                return lower;
            }
            if (lower == Integer.MIN_VALUE) {
                if (checkedCumulativeProbability(lower) >= p) {
                    return lower;
                }
            } else {
                lower -= 1; // this ensures cumulativeProbability(lower) < p, which
                // is important for the solving step
            }

            int upper = getSupportUpperBound();
            if (p == 1.0) {
                return upper;
            }

            // use the one-sided Chebyshev inequality to narrow the bracket
            // cf. AbstractRealDistribution.inverseCumulativeProbability(double)
            final double mu = getMean();
            final double sigma = Math.sqrt(getVariance());
            final boolean chebyshevApplies = !(Double.isInfinite(mu) ||
                    Double.isNaN(mu) ||
                    Double.isInfinite(sigma) ||
                    Double.isNaN(sigma) ||
                    sigma == 0.0);
            if (chebyshevApplies) {
                double k = Math.sqrt((1.0 - p) / p);
                double tmp = mu - k * sigma;
                if (tmp > lower) {
                    lower = ((int) Math.ceil(tmp)) - 1;
                }
                k = 1.0 / k;
                tmp = mu + k * sigma;
                if (tmp < upper) {
                    upper = ((int) Math.ceil(tmp)) - 1;
                }
            }

            return solveInverseCumulativeProbability(p, lower, upper);
        }

        /**
         * This is a utility function used by {@link
         * #inverseCumulativeProbability(double)}. It assumes {@code 0 < p < 1} and
         * that the inverse cumulative probability lies in the bracket {@code
         * (lower, upper]}. The implementation does simple bisection to find the
         * smallest {@code p}-quantile {@code inf{x in Z | P(X <= x) >= p}}.
         *
         * @param p Cumulative probability.
         * @param lower Value satisfying {@code cumulativeProbability(lower) < p}.
         * @param upper Value satisfying {@code p <= cumulativeProbability(upper)}.
         * @return the smallest {@code p}-quantile of this distribution.
         */
        private int solveInverseCumulativeProbability(final double p,
                int lower,
                int upper) {
            // Applied https://github.com/apache/commons-statistics/pull/2

            // Using long variables instead of int to avoid overflow when computing
            // xm inside the loop.
            long l = lower;
            long u = upper;
            while (l + 1 < u) {
                // Cannot replace division by 2 with a right shift because (l + u)
                // can be negative. This can be optimized when we know that both
                // lower and upper arguments of this method are positive, for
                // example, for PoissonDistribution.
                long xm = (l + u) / 2;
                double pm = checkedCumulativeProbability((int) xm);
                if (pm >= p) {
                    u = xm;
                } else {
                    l = xm;
                }
            }
            return (int) u;
        }

        /**
         * Computes the cumulative probability function and checks for {@code NaN}
         * values returned. Throws {@code MathInternalError} if the value is
         * {@code NaN}. Rethrows any exception encountered evaluating the cumulative
         * probability function. Throws {@code MathInternalError} if the cumulative
         * probability function returns {@code NaN}.
         *
         * @param argument Input value.
         * @return the cumulative probability.
         * @throws IllegalStateException if the cumulative probability is {@code NaN}.
         */
        private double checkedCumulativeProbability(int argument) {
            final double result = cumulativeProbability(argument);
            if (Double.isNaN(result)) {
                throw new IllegalStateException("Internal error");
            }
            return result;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/BinomialDistribution.java
     */
    static final class BinomialDistribution extends AbstractDiscreteDistribution {
        /** The number of trials. */
        private final int numberOfTrials;
        /** The probability of success. */
        private final double probabilityOfSuccess;

        /**
         * Creates a binomial distribution.
         *
         * @param trials Number of trials.
         * @param p Probability of success.
         * @throws IllegalArgumentException if {@code trials < 0}, or if
         * {@code p < 0} or {@code p > 1}.
         */
        BinomialDistribution(int trials,
                double p) {
            if (trials < 0) {
                throw new IllegalArgumentException(trials + " trials is negative");
            }
            if (p < 0 ||
                    p > 1) {
                throw new IllegalArgumentException(p + " is out of range [0, 1]");
            }

            probabilityOfSuccess = p;
            numberOfTrials = trials;
        }

        /** {@inheritDoc} */
        public double probability(int x) {
            final double logProbability = logProbability(x);
            return logProbability == Double.NEGATIVE_INFINITY ? 0 : Math.exp(logProbability);
        }

        /** {@inheritDoc} **/
        double logProbability(int x) {
            if (numberOfTrials == 0) {
                return (x == 0) ? 0. : Double.NEGATIVE_INFINITY;
            }
            double ret;
            if (x < 0 || x > numberOfTrials) {
                ret = Double.NEGATIVE_INFINITY;
            } else {
                ret = SaddlePointExpansion.logBinomialProbability(x,
                        numberOfTrials, probabilityOfSuccess,
                        1.0 - probabilityOfSuccess);
            }
            return ret;
        }

        /** {@inheritDoc} */
        @Override
        public double cumulativeProbability(int x) {
            double ret;
            if (x < 0) {
                ret = 0.0;
            } else if (x >= numberOfTrials) {
                ret = 1.0;
            } else {
                ret = 1.0 - RegularizedBeta.value(probabilityOfSuccess,
                        x + 1.0, numberOfTrials - x);
            }
            return ret;
        }

        /**
         * {@inheritDoc}
         *
         * For {@code n} trials and probability parameter {@code p}, the mean is
         * {@code n * p}.
         */
        @Override
        public double getMean() {
            return numberOfTrials * probabilityOfSuccess;
        }

        /**
         * {@inheritDoc}
         *
         * For {@code n} trials and probability parameter {@code p}, the variance is
         * {@code n * p * (1 - p)}.
         */
        @Override
        public double getVariance() {
            final double p = probabilityOfSuccess;
            return numberOfTrials * p * (1 - p);
        }

        /**
         * {@inheritDoc}
         *
         * The lower bound of the support is always 0 except for the probability
         * parameter {@code p = 1}.
         *
         * @return lower bound of the support (0 or the number of trials)
         */
        @Override
        public int getSupportLowerBound() {
            return probabilityOfSuccess < 1.0 ? 0 : numberOfTrials;
        }

        /**
         * {@inheritDoc}
         *
         * The upper bound of the support is the number of trials except for the
         * probability parameter {@code p = 0}.
         *
         * @return upper bound of the support (number of trials or 0)
         */
        @Override
        public int getSupportUpperBound() {
            return probabilityOfSuccess > 0.0 ? numberOfTrials : 0;
        }

        @Override
        public String toString() {
            return "BinomialDistribution[" + numberOfTrials + ", " + probabilityOfSuccess + "]";
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-fraction/src/main/java/
     * org/apache/commons/numbers/fraction/ContinuedFraction.java
     *
     * Provides a generic means to evaluate
     * <a href="http://mathworld.wolfram.com/ContinuedFraction.html">continued fractions</a>.
     * Subclasses must provide the {@link #getA(int,double) a} and {@link #getB(int,double) b}
     * coefficients to evaluate the continued fraction.
     */
    abstract static class ContinuedFraction {
        /** Maximum allowed numerical error. */
        private static final double DEFAULT_EPSILON = 1e-9;

        /**
         * Defines the <a href="http://mathworld.wolfram.com/ContinuedFraction.html">
         * {@code n}-th "a" coefficient</a> of the continued fraction.
         *
         * @param n Index of the coefficient to retrieve.
         * @param x Evaluation point.
         * @return the coefficient <code>a<sub>n</sub></code>.
         */
        protected abstract double getA(int n, double x);

        /**
         * Defines the <a href="http://mathworld.wolfram.com/ContinuedFraction.html">
         * {@code n}-th "b" coefficient</a> of the continued fraction.
         *
         * @param n Index of the coefficient to retrieve.
         * @param x Evaluation point.
         * @return the coefficient <code>b<sub>n</sub></code>.
         */
        protected abstract double getB(int n, double x);

        /**
         * Evaluates the continued fraction.
         *
         * @param x Point at which to evaluate the continued fraction.
         * @return the value of the continued fraction evaluated at {@code x}.
         * @throws ArithmeticException if the algorithm fails to converge.
         * @throws ArithmeticException if the maximal number of iterations is reached
         * before the expected convergence is achieved.
         *
         * @see #evaluate(double,double,int)
         */
        public double evaluate(double x) {
            return evaluate(x, DEFAULT_EPSILON, Integer.MAX_VALUE);
        }

        /**
         * Evaluates the continued fraction.
         *
         * @param x the evaluation point.
         * @param epsilon Maximum error allowed.
         * @return the value of the continued fraction evaluated at {@code x}.
         * @throws ArithmeticException if the algorithm fails to converge.
         * @throws ArithmeticException if the maximal number of iterations is reached
         * before the expected convergence is achieved.
         *
         * @see #evaluate(double,double,int)
         */
        public double evaluate(double x, double epsilon) {
            return evaluate(x, epsilon, Integer.MAX_VALUE);
        }

        /**
         * Evaluates the continued fraction at the value x.
         * @param x the evaluation point.
         * @param maxIterations Maximum number of iterations.
         * @return the value of the continued fraction evaluated at {@code x}.
         * @throws ArithmeticException if the algorithm fails to converge.
         * @throws ArithmeticException if the maximal number of iterations is reached
         * before the expected convergence is achieved.
         *
         * @see #evaluate(double,double,int)
         */
        public double evaluate(double x, int maxIterations) {
            return evaluate(x, DEFAULT_EPSILON, maxIterations);
        }

        /**
         * Evaluates the continued fraction.
         * <p>
         * The implementation of this method is based on the modified Lentz algorithm as described
         * on page 18 ff. in:
         * </p>
         *
         * <ul>
         *   <li>
         *   I. J. Thompson,  A. R. Barnett. "Coulomb and Bessel Functions of Complex Arguments and
         *   Order."
         *   <a target="_blank" href="http://www.fresco.org.uk/papers/Thompson-JCP64p490.pdf">
         *   http://www.fresco.org.uk/papers/Thompson-JCP64p490.pdf</a>
         *   </li>
         * </ul>
         *
         * @param x Point at which to evaluate the continued fraction.
         * @param epsilon Maximum error allowed.
         * @param maxIterations Maximum number of iterations.
         * @return the value of the continued fraction evaluated at {@code x}.
         * @throws ArithmeticException if the algorithm fails to converge.
         * @throws ArithmeticException if the maximal number of iterations is reached
         * before the expected convergence is achieved.
         */
        double evaluate(double x, double epsilon, int maxIterations) {
            final double small = 1e-50;
            double hPrev = getA(0, x);

            // use the value of small as epsilon criteria for zero checks
            if (Precision.equals(hPrev, 0.0, small)) {
                hPrev = small;
            }

            int n = 1;
            double dPrev = 0.0;
            double cPrev = hPrev;
            double hN = hPrev;

            while (n <= maxIterations) {
                final double a = getA(n, x);
                final double b = getB(n, x);

                double dN = a + b * dPrev;
                if (Precision.equals(dN, 0.0, small)) {
                    dN = small;
                }
                double cN = a + b / cPrev;
                if (Precision.equals(cN, 0.0, small)) {
                    cN = small;
                }

                dN = 1 / dN;
                final double deltaN = cN * dN;
                hN = hPrev * deltaN;

                if (Double.isInfinite(hN)) {
                    throw new RuntimeException("Continued fraction convergents diverged to " +
                            "+/- infinity for value " + x);
                }
                if (Double.isNaN(hN)) {
                    throw new RuntimeException("Continued fraction diverged to NaN for value " + x);
                }

                if (Math.abs(deltaN - 1) < epsilon) {
                    return hN;
                }

                dPrev = dN;
                cPrev = cN;
                hPrev = hN;
                ++n;
            }

            throw new RuntimeException("maximal count (" + maxIterations + ") exceeded");
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * d6d7b047cd45530986c34a3e66a1251940d6f7e9/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/Gamma.java
     *
     * <a href="http://mathworld.wolfram.com/GammaFunction.html">Gamma
     * function</a>.
     * <p>
     * The <a href="http://mathworld.wolfram.com/GammaFunction.html">gamma
     * function</a> can be seen to extend the factorial function to cover real and
     * complex numbers, but with its argument shifted by {@code -1}. This
     * implementation supports real numbers.
     * </p>
     * <p>
     * This class is immutable.
     * </p>
     */
    static final class Gamma {
        /** &radic;(2&pi;). */
        private static final double SQRT_TWO_PI = 2.506628274631000502;

        /**
         * Computes the value of \( \Gamma(x) \).
         * <p>
         * Based on the <em>NSWC Library of Mathematics Subroutines</em> double
         * precision implementation, {@code DGAMMA}.
         *
         * @param x Argument.
         * @return \( \Gamma(x) \)
         */
        static double value(final double x) {

            if ((x == Math.rint(x)) && (x <= 0.0)) {
                return Double.NaN;
            }

            final double absX = Math.abs(x);
            if (absX <= 20) {
                if (x >= 1) {
                    /*
                     * From the recurrence relation
                     * Gamma(x) = (x - 1) * ... * (x - n) * Gamma(x - n),
                     * then
                     * Gamma(t) = 1 / [1 + InvGamma1pm1.value(t - 1)],
                     * where t = x - n. This means that t must satisfy
                     * -0.5 <= t - 1 <= 1.5.
                     */
                    double prod = 1;
                    double t = x;
                    while (t > 2.5) {
                        t -= 1;
                        prod *= t;
                    }
                    return prod / (1 + InvGamma1pm1.value(t - 1));
                } else {
                    /*
                     * From the recurrence relation
                     * Gamma(x) = Gamma(x + n + 1) / [x * (x + 1) * ... * (x + n)]
                     * then
                     * Gamma(x + n + 1) = 1 / [1 + InvGamma1pm1.value(x + n)],
                     * which requires -0.5 <= x + n <= 1.5.
                     */
                    double prod = x;
                    double t = x;
                    while (t < -0.5) {
                        t += 1;
                        prod *= t;
                    }
                    return 1 / (prod * (1 + InvGamma1pm1.value(t)));
                }
            } else {
                final double y = absX + LanczosApproximation.g() + 0.5;
                final double gammaAbs = SQRT_TWO_PI / absX *
                        Math.pow(y, absX + 0.5) *
                        Math.exp(-y) * LanczosApproximation.value(absX);
                if (x > 0) {
                    return gammaAbs;
                } else {
                    /*
                     * From the reflection formula
                     * Gamma(x) * Gamma(1 - x) * sin(pi * x) = pi,
                     * and the recurrence relation
                     * Gamma(1 - x) = -x * Gamma(-x),
                     * it is found
                     * Gamma(x) = -pi / [x * sin(pi * x) * Gamma(-x)].
                     */
                    return -Math.PI / (x * Math.sin(Math.PI * x) * gammaAbs);
                }
            }
        }

        private Gamma() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/InvGamma1pm1.java
     *
     * Function \( \frac{1}{\Gamma(1 + x)} - 1 \).
     */
    static final class InvGamma1pm1 {
        /*
         * Constants copied from DGAM1 in the NSWC library.
         */
        /** The constant {@code A0} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_A0 = .611609510448141581788E-08;
        /** The constant {@code A1} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_A1 = .624730830116465516210E-08;
        /** The constant {@code B1} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B1 = .203610414066806987300E+00;
        /** The constant {@code B2} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B2 = .266205348428949217746E-01;
        /** The constant {@code B3} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B3 = .493944979382446875238E-03;
        /** The constant {@code B4} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B4 = -.851419432440314906588E-05;
        /** The constant {@code B5} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B5 = -.643045481779353022248E-05;
        /** The constant {@code B6} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B6 = .992641840672773722196E-06;
        /** The constant {@code B7} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B7 = -.607761895722825260739E-07;
        /** The constant {@code B8} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_B8 = .195755836614639731882E-09;
        /** The constant {@code P0} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P0 = .6116095104481415817861E-08;
        /** The constant {@code P1} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P1 = .6871674113067198736152E-08;
        /** The constant {@code P2} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P2 = .6820161668496170657918E-09;
        /** The constant {@code P3} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P3 = .4686843322948848031080E-10;
        /** The constant {@code P4} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P4 = .1572833027710446286995E-11;
        /** The constant {@code P5} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P5 = -.1249441572276366213222E-12;
        /** The constant {@code P6} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_P6 = .4343529937408594255178E-14;
        /** The constant {@code Q1} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_Q1 = .3056961078365221025009E+00;
        /** The constant {@code Q2} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_Q2 = .5464213086042296536016E-01;
        /** The constant {@code Q3} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_Q3 = .4956830093825887312020E-02;
        /** The constant {@code Q4} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_Q4 = .2692369466186361192876E-03;
        /** The constant {@code C} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C = -.422784335098467139393487909917598E+00;
        /** The constant {@code C0} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C0 = .577215664901532860606512090082402E+00;
        /** The constant {@code C1} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C1 = -.655878071520253881077019515145390E+00;
        /** The constant {@code C2} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C2 = -.420026350340952355290039348754298E-01;
        /** The constant {@code C3} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C3 = .166538611382291489501700795102105E+00;
        /** The constant {@code C4} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C4 = -.421977345555443367482083012891874E-01;
        /** The constant {@code C5} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C5 = -.962197152787697356211492167234820E-02;
        /** The constant {@code C6} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C6 = .721894324666309954239501034044657E-02;
        /** The constant {@code C7} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C7 = -.116516759185906511211397108401839E-02;
        /** The constant {@code C8} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C8 = -.215241674114950972815729963053648E-03;
        /** The constant {@code C9} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C9 = .128050282388116186153198626328164E-03;
        /** The constant {@code C10} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C10 = -.201348547807882386556893914210218E-04;
        /** The constant {@code C11} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C11 = -.125049348214267065734535947383309E-05;
        /** The constant {@code C12} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C12 = .113302723198169588237412962033074E-05;
        /** The constant {@code C13} defined in {@code DGAM1}. */
        private static final double INV_GAMMA1P_M1_C13 = -.205633841697760710345015413002057E-06;

        /**
         * Computes the function \( \frac{1}{\Gamma(1 + x)} - 1 \) for {@code -0.5 <= x <= 1.5}.
         *
         * This implementation is based on the double precision implementation in
         * the <em>NSWC Library of Mathematics Subroutines</em>, {@code DGAM1}.
         *
         * @param x Argument.
         * @return \( \frac{1}{\Gamma(1 + x)} - 1 \)
         * @throws IllegalArgumentException if {@code x < -0.5} or {@code x > 1.5}
         */
        static double value(final double x) {
            if (x < -0.5 || x > 1.5) {
                throw new IllegalArgumentException(x + " is out of range [-0.5, 1.5]");
            }

            final double t = x <= 0.5 ? x : (x - 0.5) - 0.5;
            if (t < 0) {
                final double a = INV_GAMMA1P_M1_A0 + t * INV_GAMMA1P_M1_A1;
                double b = INV_GAMMA1P_M1_B8;
                b = INV_GAMMA1P_M1_B7 + t * b;
                b = INV_GAMMA1P_M1_B6 + t * b;
                b = INV_GAMMA1P_M1_B5 + t * b;
                b = INV_GAMMA1P_M1_B4 + t * b;
                b = INV_GAMMA1P_M1_B3 + t * b;
                b = INV_GAMMA1P_M1_B2 + t * b;
                b = INV_GAMMA1P_M1_B1 + t * b;
                b = 1.0 + t * b;

                double c = INV_GAMMA1P_M1_C13 + t * (a / b);
                c = INV_GAMMA1P_M1_C12 + t * c;
                c = INV_GAMMA1P_M1_C11 + t * c;
                c = INV_GAMMA1P_M1_C10 + t * c;
                c = INV_GAMMA1P_M1_C9 + t * c;
                c = INV_GAMMA1P_M1_C8 + t * c;
                c = INV_GAMMA1P_M1_C7 + t * c;
                c = INV_GAMMA1P_M1_C6 + t * c;
                c = INV_GAMMA1P_M1_C5 + t * c;
                c = INV_GAMMA1P_M1_C4 + t * c;
                c = INV_GAMMA1P_M1_C3 + t * c;
                c = INV_GAMMA1P_M1_C2 + t * c;
                c = INV_GAMMA1P_M1_C1 + t * c;
                c = INV_GAMMA1P_M1_C + t * c;
                if (x > 0.5) {
                    return t * c / x;
                } else {
                    return x * ((c + 0.5) + 0.5);
                }
            } else {
                double p = INV_GAMMA1P_M1_P6;
                p = INV_GAMMA1P_M1_P5 + t * p;
                p = INV_GAMMA1P_M1_P4 + t * p;
                p = INV_GAMMA1P_M1_P3 + t * p;
                p = INV_GAMMA1P_M1_P2 + t * p;
                p = INV_GAMMA1P_M1_P1 + t * p;
                p = INV_GAMMA1P_M1_P0 + t * p;

                double q = INV_GAMMA1P_M1_Q4;
                q = INV_GAMMA1P_M1_Q3 + t * q;
                q = INV_GAMMA1P_M1_Q2 + t * q;
                q = INV_GAMMA1P_M1_Q1 + t * q;
                q = 1.0 + t * q;

                double c = INV_GAMMA1P_M1_C13 + (p / q) * t;
                c = INV_GAMMA1P_M1_C12 + t * c;
                c = INV_GAMMA1P_M1_C11 + t * c;
                c = INV_GAMMA1P_M1_C10 + t * c;
                c = INV_GAMMA1P_M1_C9 + t * c;
                c = INV_GAMMA1P_M1_C8 + t * c;
                c = INV_GAMMA1P_M1_C7 + t * c;
                c = INV_GAMMA1P_M1_C6 + t * c;
                c = INV_GAMMA1P_M1_C5 + t * c;
                c = INV_GAMMA1P_M1_C4 + t * c;
                c = INV_GAMMA1P_M1_C3 + t * c;
                c = INV_GAMMA1P_M1_C2 + t * c;
                c = INV_GAMMA1P_M1_C1 + t * c;
                c = INV_GAMMA1P_M1_C0 + t * c;

                if (x > 0.5) {
                    return (t / x) * ((c - 0.5) - 0.5);
                } else {
                    return x * c;
                }
            }
        }

        private InvGamma1pm1() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/LanczosApproximation.java
     *
     * <a href="http://mathworld.wolfram.com/LanczosApproximation.html">
     * Lanczos approximation</a> to the Gamma function.
     *
     * It is related to the Gamma function by the following equation
     * \[
     * \Gamma(x) = \sqrt{2\pi} \, \frac{(g + x + \frac{1}{2})^{x + \frac{1}{2}} \,
     *   e^{-(g + x + \frac{1}{2})} \, \mathrm{lanczos}(x)}
     *                                 {x}
     * \]
     * where \( g \) is the Lanczos constant.
     *
     * See equations (1) through (5), and Paul Godfrey's
     * <a href="http://my.fit.edu/~gabdo/gamma.txt">Note on the computation
     * of the convergent Lanczos complex Gamma approximation</a>.
     */
    static final class LanczosApproximation {
        /** \( g = \frac{607}{128} \). */
        private static final double LANCZOS_G = 607d / 128d;
        /** Lanczos coefficients. */
        private static final double[] LANCZOS = {
                0.99999999999999709182,
                57.156235665862923517,
                -59.597960355475491248,
                14.136097974741747174,
                -0.49191381609762019978,
                .33994649984811888699e-4,
                .46523628927048575665e-4,
                -.98374475304879564677e-4,
                .15808870322491248884e-3,
                -.21026444172410488319e-3,
                .21743961811521264320e-3,
                -.16431810653676389022e-3,
                .84418223983852743293e-4,
                -.26190838401581408670e-4,
                .36899182659531622704e-5,
        };

        /**
         * Computes the Lanczos approximation.
         *
         * @param x Argument.
         * @return the Lanczos approximation.
         */
        static double value(final double x) {
            double sum = 0;
            for (int i = LANCZOS.length - 1; i > 0; i--) {
                sum += LANCZOS[i] / (x + i);
            }
            return sum + LANCZOS[0];
        }

        /**
         * @return the Lanczos constant \( g = \frac{607}{128} \).
         */
        static double g() {
            return LANCZOS_G;
        }

        private LanczosApproximation() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * d6d7b047cd45530986c34a3e66a1251940d6f7e9/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/LogBeta.java
     *
     * Computes \( log_e \Beta(p, q) \).
     * <p>
     * This class is immutable.
     * </p>
     */
    static final class LogBeta {
        /** The constant value of ½log 2π. */
        private static final double HALF_LOG_TWO_PI = 0.9189385332046727;

        /**
         * <p>
         * The coefficients of the series expansion of the Δ function. This function
         * is defined as follows
         * </p>
         * <center>Δ(x) = log Γ(x) - (x - 0.5) log a + a - 0.5 log 2π,</center>
         * <p>
         * see equation (23) in Didonato and Morris (1992). The series expansion,
         * which applies for x ≥ 10, reads
         * </p>
         * <pre>
         *                 14
         *                ====
         *             1  \                2 n
         *     Δ(x) = ---  >    d  (10 / x)
         *             x  /      n
         *                ====
         *                n = 0
         * <pre>
         */
        private static final double[] DELTA = {
                .833333333333333333333333333333E-01,
                -.277777777777777777777777752282E-04,
                .793650793650793650791732130419E-07,
                -.595238095238095232389839236182E-09,
                .841750841750832853294451671990E-11,
                -.191752691751854612334149171243E-12,
                .641025640510325475730918472625E-14,
                -.295506514125338232839867823991E-15,
                .179643716359402238723287696452E-16,
                -.139228964661627791231203060395E-17,
                .133802855014020915603275339093E-18,
                -.154246009867966094273710216533E-19,
                .197701992980957427278370133333E-20,
                -.234065664793997056856992426667E-21,
                .171348014966398575409015466667E-22
        };

        /**
         * Returns the value of Δ(b) - Δ(a + b), with 0 ≤ a ≤ b and b ≥ 10. Based
         * on equations (26), (27) and (28) in Didonato and Morris (1992).
         *
         * @param a First argument.
         * @param b Second argument.
         * @return the value of {@code Delta(b) - Delta(a + b)}
         * @throws IllegalArgumentException if {@code a < 0} or {@code a > b}
         * @throws IllegalArgumentException if {@code b < 10}
         */
        private static double deltaMinusDeltaSum(final double a,
                final double b) {
            if (a < 0 ||
                    a > b) {
                throw new IllegalArgumentException(a + " is out of range [0, " + b + "]");
            }
            if (b < 10) {
                throw new IllegalArgumentException(b + " is out of range [10, ∞)");
            }

            final double h = a / b;
            final double p = h / (1 + h);
            final double q = 1 / (1 + h);
            final double q2 = q * q;
            /*
             * s[i] = 1 + q + ... - q**(2 * i)
             */
            final double[] s = new double[DELTA.length];
            s[0] = 1;
            for (int i = 1; i < s.length; i++) {
                s[i] = 1 + (q + q2 * s[i - 1]);
            }
            /*
             * w = Delta(b) - Delta(a + b)
             */
            final double sqrtT = 10 / b;
            final double t = sqrtT * sqrtT;
            double w = DELTA[DELTA.length - 1] * s[s.length - 1];
            for (int i = DELTA.length - 2; i >= 0; i--) {
                w = t * w + DELTA[i] * s[i];
            }
            return w * p / b;
        }

        /**
         * Returns the value of Δ(p) + Δ(q) - Δ(p + q), with p, q ≥ 10.
         * Based on the <em>NSWC Library of Mathematics Subroutines</em> implementation,
         * {@code DBCORR}.
         *
         * @param p First argument.
         * @param q Second argument.
         * @return the value of {@code Delta(p) + Delta(q) - Delta(p + q)}.
         * @throws IllegalArgumentException if {@code p < 10} or {@code q < 10}.
         */
        private static double sumDeltaMinusDeltaSum(final double p,
                final double q) {

            if (p < 10) {
                throw new IllegalArgumentException(p + " is out of range [10, ∞)");
            }
            if (q < 10) {
                throw new IllegalArgumentException(q + " is out of range [10, ∞)");
            }

            final double a = Math.min(p, q);
            final double b = Math.max(p, q);
            final double sqrtT = 10 / a;
            final double t = sqrtT * sqrtT;
            double z = DELTA[DELTA.length - 1];
            for (int i = DELTA.length - 2; i >= 0; i--) {
                z = t * z + DELTA[i];
            }
            return z / a + deltaMinusDeltaSum(a, b);
        }

        /**
         * Returns the value of {@code log B(p, q)} for {@code 0 ≤ x ≤ 1} and {@code p, q > 0}.
         * Based on the <em>NSWC Library of Mathematics Subroutines</em> implementation,
         * {@code DBETLN}.
         *
         * @param p First argument.
         * @param q Second argument.
         * @return the value of {@code log(Beta(p, q))}, {@code NaN} if
         * {@code p <= 0} or {@code q <= 0}.
         */
        static double value(double p,
                double q) {
            if (Double.isNaN(p) ||
                    Double.isNaN(q) ||
                    p <= 0 ||
                    q <= 0) {
                return Double.NaN;
            }

            final double a = Math.min(p, q);
            final double b = Math.max(p, q);
            if (a >= 10) {
                final double w = sumDeltaMinusDeltaSum(a, b);
                final double h = a / b;
                final double c = h / (1 + h);
                final double u = -(a - 0.5) * Math.log(c);
                final double v = b * Math.log1p(h);
                if (u <= v) {
                    return (((-0.5 * Math.log(b) + HALF_LOG_TWO_PI) + w) - u) - v;
                } else {
                    return (((-0.5 * Math.log(b) + HALF_LOG_TWO_PI) + w) - v) - u;
                }
            } else if (a > 2) {
                if (b > 1000) {
                    final int n = (int) Math.floor(a - 1);
                    double prod = 1;
                    double ared = a;
                    for (int i = 0; i < n; i++) {
                        ared -= 1;
                        prod *= ared / (1 + ared / b);
                    }
                    return (Math.log(prod) - n * Math.log(b)) +
                            (LogGamma.value(ared) +
                                    logGammaMinusLogGammaSum(ared, b));
                } else {
                    double prod1 = 1;
                    double ared = a;
                    while (ared > 2) {
                        ared -= 1;
                        final double h = ared / b;
                        prod1 *= h / (1 + h);
                    }
                    if (b < 10) {
                        double prod2 = 1;
                        double bred = b;
                        while (bred > 2) {
                            bred -= 1;
                            prod2 *= bred / (ared + bred);
                        }
                        return Math.log(prod1) +
                                Math.log(prod2) +
                                (LogGamma.value(ared) +
                                        (LogGamma.value(bred) -
                                                LogGammaSum.value(ared, bred)));
                    } else {
                        return Math.log(prod1) +
                                LogGamma.value(ared) +
                                logGammaMinusLogGammaSum(ared, b);
                    }
                }
            } else if (a >= 1) {
                if (b > 2) {
                    if (b < 10) {
                        double prod = 1;
                        double bred = b;
                        while (bred > 2) {
                            bred -= 1;
                            prod *= bred / (a + bred);
                        }
                        return Math.log(prod) +
                                (LogGamma.value(a) +
                                        (LogGamma.value(bred) -
                                                LogGammaSum.value(a, bred)));
                    } else {
                        return LogGamma.value(a) +
                                logGammaMinusLogGammaSum(a, b);
                    }
                } else {
                    return LogGamma.value(a) +
                            LogGamma.value(b) -
                            LogGammaSum.value(a, b);
                }
            } else {
                if (b >= 10) {
                    return LogGamma.value(a) +
                            logGammaMinusLogGammaSum(a, b);
                } else {
                    // The original NSWC implementation was
                    //   LogGamma.value(a) + (LogGamma.value(b) - LogGamma.value(a + b));
                    // but the following command turned out to be more accurate.
                    return Math.log(Gamma.value(a) * Gamma.value(b) /
                            Gamma.value(a + b));
                }
            }
        }

        /**
         * Returns the value of log[Γ(b) / Γ(a + b)] for a ≥ 0 and b ≥ 10.
         * Based on the <em>NSWC Library of Mathematics Subroutines</em> implementation,
         * {@code DLGDIV}.
         *
         * @param a First argument.
         * @param b Second argument.
         * @return the value of {@code log(Gamma(b) / Gamma(a + b))}.
         * @throws IllegalArgumentException if {@code a < 0} or {@code b < 10}.
         */
        private static double logGammaMinusLogGammaSum(double a,
                double b) {
            if (a < 0) {
                throw new IllegalArgumentException(a + " is out of range [0, ∞)");
            }
            if (b < 10) {
                throw new IllegalArgumentException(b + " is out of range [10, ∞)");
            }

            /*
             * d = a + b - 0.5
             */
            final double d;
            final double w;
            if (a <= b) {
                d = b + (a - 0.5);
                w = deltaMinusDeltaSum(a, b);
            } else {
                d = a + (b - 0.5);
                w = deltaMinusDeltaSum(b, a);
            }

            final double u = d * Math.log1p(a / b);
            final double v = a * (Math.log(b) - 1);

            return u <= v ?
                    (w - u) - v :
                    (w - v) - u;
        }

        private LogBeta() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/LogGamma.java
     *
     * Function \( \ln \Gamma(x) \).
     */
    static final class LogGamma {
        /** Lanczos constant. */
        private static final double LANCZOS_G = 607d / 128d;
        /** Performance. */
        private static final double HALF_LOG_2_PI = 0.5 * Math.log(2.0 * Math.PI);

        /**
         * Computes the function \( \ln \Gamma(x) \) for {@code x >= 0}.
         *
         * For {@code x <= 8}, the implementation is based on the double precision
         * implementation in the <em>NSWC Library of Mathematics Subroutines</em>,
         * {@code DGAMLN}. For {@code x >= 8}, the implementation is based on
         * <ul>
         * <li><a href="http://mathworld.wolfram.com/GammaFunction.html">Gamma
         *     Function</a>, equation (28).</li>
         * <li><a href="http://mathworld.wolfram.com/LanczosApproximation.html">
         *     Lanczos Approximation</a>, equations (1) through (5).</li>
         * <li><a href="http://my.fit.edu/~gabdo/gamma.txt">Paul Godfrey, A note on
         *     the computation of the convergent Lanczos complex Gamma
         *     approximation</a></li>
         * </ul>
         *
         * @param x Argument.
         * @return \( \ln \Gamma(x) \), or {@code NaN} if {@code x <= 0}.
         */
        static double value(double x) {
            if (Double.isNaN(x) || (x <= 0.0)) {
                return Double.NaN;
            } else if (x < 0.5) {
                return LogGamma1p.value(x) - Math.log(x);
            } else if (x <= 2.5) {
                return LogGamma1p.value((x - 0.5) - 0.5);
            } else if (x <= 8.0) {
                final int n = (int) Math.floor(x - 1.5);
                double prod = 1.0;
                for (int i = 1; i <= n; i++) {
                    prod *= x - i;
                }
                return LogGamma1p.value(x - (n + 1)) + Math.log(prod);
            } else {
                final double sum = LanczosApproximation.value(x);
                final double tmp = x + LANCZOS_G + .5;
                return ((x + .5) * Math.log(tmp)) - tmp +
                        HALF_LOG_2_PI + Math.log(sum / x);
            }
        }

        private LogGamma() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/LogGamma1p.java
     *
     * Function \( \ln \Gamma(1 + x) \).
     */
    static final class LogGamma1p {
        /**
         * Computes the function \( \ln \Gamma(1 + x) \) for \( -0.5 \leq x \leq 1.5 \).
         *
         * This implementation is based on the double precision implementation in
         * the <em>NSWC Library of Mathematics Subroutines</em>, {@code DGMLN1}.
         *
         * @param x Argument.
         * @return \( \ln \Gamma(1 + x) \)
         * @throws IllegalArgumentException if {@code x < -0.5} or {@code x > 1.5}.
         */
        static double value(final double x) {
            if (x < -0.5 || x > 1.5) {
                throw new IllegalArgumentException(x + " is out of range [-0.5, 1.5]");
            }

            return -Math.log1p(InvGamma1pm1.value(x));
        }

        private LogGamma1p() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * d6d7b047cd45530986c34a3e66a1251940d6f7e9/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/LogGammaSum.java
     *
     * Computes \( \log_e(\Gamma(a+b)) \).
     * <p>
     * This class is immutable.
     * </p>
     */
    static final class LogGammaSum {
        /**
         * Computes the value of log Γ(a + b) for 1 ≤ a, b ≤ 2.
         * Based on the <em>NSWC Library of Mathematics Subroutines</em>
         * implementation, {@code DGSMLN}.
         *
         * @param a First argument.
         * @param b Second argument.
         * @return the value of {@code log(Gamma(a + b))}.
         * @throws IllegalArgumentException if {@code a} or {@code b} is lower than 1
         * or larger than 2.
         */
        static double value(double a,
                double b) {
            if (a < 1 ||
                    a > 2) {
                throw new IllegalArgumentException(a + "is out of range [1, 2]");
            }
            if (b < 1 ||
                    b > 2) {
                throw new IllegalArgumentException(b + "is out of range [1, 2]");
            }

            final double x = (a - 1) + (b - 1);
            if (x <= 0.5) {
                return LogGamma1p.value(1 + x);
            } else if (x <= 1.5) {
                return LogGamma1p.value(x) + Math.log1p(x);
            } else {
                return LogGamma1p.value(x - 1) + Math.log(x * (1 + x));
            }
        }

        private LogGammaSum() {}
    }

    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/PoissonDistribution.java
     */
    static final class PoissonDistribution extends AbstractDiscreteDistribution {
        /** ln(2 &pi;). */
        private static final double LOG_TWO_PI = Math.log(2 * Math.PI);
        /** Default maximum number of iterations. */
        private static final int DEFAULT_MAX_ITERATIONS = 10000000;
        /** Default convergence criterion. */
        private static final double DEFAULT_EPSILON = 1e-12;
        /** Mean of the distribution. */
        private final double mean;
        /** Maximum number of iterations for cumulative probability. */
        private final int maxIterations;
        /** Convergence criterion for cumulative probability. */
        private final double epsilon;

        /**
         * Creates a new Poisson distribution with specified mean.
         *
         * @param p the Poisson mean
         * @throws IllegalArgumentException if {@code p <= 0}.
         */
        PoissonDistribution(double p) {
            this(p, DEFAULT_EPSILON, DEFAULT_MAX_ITERATIONS);
        }

        /**
         * Creates a new Poisson distribution with specified mean, convergence
         * criterion and maximum number of iterations.
         *
         * @param p Poisson mean.
         * @param epsilon Convergence criterion for cumulative probabilities.
         * @param maxIterations Maximum number of iterations for cumulative
         * probabilities.
         * @throws IllegalArgumentException if {@code p <= 0}.
         */
        private PoissonDistribution(double p,
                double epsilon,
                int maxIterations) {
            if (p <= 0) {
                throw new IllegalArgumentException();
            }
            mean = p;
            this.epsilon = epsilon;
            this.maxIterations = maxIterations;
        }

        public double probability(int x) {
            final double logProbability = logProbability(x);
            return logProbability == Double.NEGATIVE_INFINITY ? 0 : Math.exp(logProbability);
        }

        double logProbability(int x) {
            double ret;
            if (x < 0 || x == Integer.MAX_VALUE) {
                ret = Double.NEGATIVE_INFINITY;
            } else if (x == 0) {
                ret = -mean;
            } else {
                ret = -SaddlePointExpansion.getStirlingError(x) -
                        SaddlePointExpansion.getDeviancePart(x, mean) -
                        0.5 * LOG_TWO_PI - 0.5 * Math.log(x);
            }
            return ret;
        }

        @Override
        public double cumulativeProbability(int x) {
            if (x < 0) {
                return 0;
            }
            if (x == Integer.MAX_VALUE) {
                return 1;
            }
            return RegularizedGamma.Q.value((double) x + 1, mean, epsilon,
                    maxIterations);
        }

        @Override
        public double getMean() {
            return mean;
        }

        /**
         * {@inheritDoc}
         *
         * For mean parameter {@code p}, the variance is {@code p}.
         */
        @Override
        public double getVariance() {
            return mean;
        }

        /**
         * {@inheritDoc}
         *
         * The lower bound of the support is always 0 no matter the mean parameter.
         *
         * @return lower bound of the support (always 0)
         */
        @Override
        public int getSupportLowerBound() {
            return 0;
        }

        /**
         * {@inheritDoc}
         *
         * The upper bound of the support is positive infinity,
         * regardless of the parameter values. There is no integer infinity,
         * so this method returns {@code Integer.MAX_VALUE}.
         *
         * @return upper bound of the support (always {@code Integer.MAX_VALUE} for
         * positive infinity)
         */
        @Override
        public int getSupportUpperBound() {
            return Integer.MAX_VALUE;
        }

        @Override
        public String toString() {
            return "PoissonDistribution[" + mean + "]";
        }
    }

    /**
     * Implementation of the <a href="http://en.wikipedia.org/wiki/Chi-squared_distribution">
     * chi-squared distribution</a>.
     *
     * Copied from https://github.com/apache/commons-statistics/blob/
     * 97d23965e8c8770b2b52263b3576681ece4c43a0/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/ChiSquaredDistribution.java
     */
    static class ChiSquaredDistribution extends AbstractContinuousDistribution {
        /** Internal Gamma distribution. */
        private final GammaDistribution gamma;

        /**
         * Creates a distribution.
         *
         * @param degreesOfFreedom Degrees of freedom.
         */
        ChiSquaredDistribution(double degreesOfFreedom) {
            gamma = new GammaDistribution(degreesOfFreedom / 2, 2);
        }

        /**
         * Access the number of degrees of freedom.
         *
         * @return the degrees of freedom.
         */
        double getDegreesOfFreedom() {
            return gamma.getShape() * 2;
        }

        /** {@inheritDoc} */
        @Override
        public double density(double x) {
            return gamma.density(x);
        }

        /** {@inheritDoc} **/
        @Override
        public double logDensity(double x) {
            return gamma.logDensity(x);
        }

        /** {@inheritDoc} */
        @Override
        public double cumulativeProbability(double x)  {
            return gamma.cumulativeProbability(x);
        }

        /**
         * {@inheritDoc}
         *
         * For {@code k} degrees of freedom, the mean is {@code k}.
         */
        @Override
        public double getMean() {
            return getDegreesOfFreedom();
        }

        /**
         * {@inheritDoc}
         *
         * @return {@code 2 * k}, where {@code k} is the number of degrees of freedom.
         */
        @Override
        public double getVariance() {
            return 2 * getDegreesOfFreedom();
        }

        /**
         * {@inheritDoc}
         *
         * The lower bound of the support is always 0 no matter the
         * degrees of freedom.
         *
         * @return zero.
         */
        @Override
        public double getSupportLowerBound() {
            return 0;
        }

        /**
         * {@inheritDoc}
         *
         * The upper bound of the support is always positive infinity no matter the
         * degrees of freedom.
         *
         * @return {@code Double.POSITIVE_INFINITY}.
         */
        @Override
        public double getSupportUpperBound() {
            return Double.POSITIVE_INFINITY;
        }

        /**
         * {@inheritDoc}
         *
         * The support of this distribution is connected.
         *
         * @return {@code true}
         */
        @Override
        public boolean isSupportConnected() {
            return true;
        }
    }

    /**
     * Implementation of the <a href="http://en.wikipedia.org/wiki/Gamma_distribution">
     * Gamma distribution</a>.
     *
     * Copied from https://github.com/apache/commons-statistics/blob/
     * 97d23965e8c8770b2b52263b3576681ece4c43a0/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/GammaDistribution.java
     */
    static class GammaDistribution extends AbstractContinuousDistribution {
        /** Lanczos constant. */
        private static final double LANCZOS_G = LanczosApproximation.g();
        /** The shape parameter. */
        private final double shape;
        /** The scale parameter. */
        private final double scale;
        /**
         * The constant value of {@code shape + g + 0.5}, where {@code g} is the
         * Lanczos constant {@link LanczosApproximation#g()}.
         */
        private final double shiftedShape;
        /**
         * The constant value of
         * {@code shape / scale * sqrt(e / (2 * pi * (shape + g + 0.5))) / L(shape)},
         * where {@code L(shape)} is the Lanczos approximation returned by
         * {@link LanczosApproximation#value(double)}. This prefactor is used in
         * {@link #density(double)}, when no overflow occurs with the natural
         * calculation.
         */
        private final double densityPrefactor1;
        /**
         * The constant value of
         * {@code log(shape / scale * sqrt(e / (2 * pi * (shape + g + 0.5))) / L(shape))},
         * where {@code L(shape)} is the Lanczos approximation returned by
         * {@link LanczosApproximation#value(double)}. This prefactor is used in
         * {@link #logDensity(double)}, when no overflow occurs with the natural
         * calculation.
         */
        private final double logDensityPrefactor1;
        /**
         * The constant value of
         * {@code shape * sqrt(e / (2 * pi * (shape + g + 0.5))) / L(shape)},
         * where {@code L(shape)} is the Lanczos approximation returned by
         * {@link LanczosApproximation#value(double)}. This prefactor is used in
         * {@link #density(double)}, when overflow occurs with the natural
         * calculation.
         */
        private final double densityPrefactor2;
        /**
         * The constant value of
         * {@code log(shape * sqrt(e / (2 * pi * (shape + g + 0.5))) / L(shape))},
         * where {@code L(shape)} is the Lanczos approximation returned by
         * {@link LanczosApproximation#value(double)}. This prefactor is used in
         * {@link #logDensity(double)}, when overflow occurs with the natural
         * calculation.
         */
        private final double logDensityPrefactor2;
        /**
         * Lower bound on {@code y = x / scale} for the selection of the computation
         * method in {@link #density(double)}. For {@code y <= minY}, the natural
         * calculation overflows.
         */
        private final double minY;
        /**
         * Upper bound on {@code log(y)} ({@code y = x / scale}) for the selection
         * of the computation method in {@link #density(double)}. For
         * {@code log(y) >= maxLogY}, the natural calculation overflows.
         */
        private final double maxLogY;

        /**
         * Creates a distribution.
         *
         * @param shape the shape parameter
         * @param scale the scale parameter
         * @throws IllegalArgumentException if {@code shape <= 0} or
         * {@code scale <= 0}.
         */
        GammaDistribution(double shape,
                double scale) {
            if (shape <= 0) {
                throw new IllegalArgumentException(shape + " <= 0");
            }
            if (scale <= 0) {
                throw new IllegalArgumentException(scale + " <= 0");
            }

            this.shape = shape;
            this.scale = scale;
            this.shiftedShape = shape + LANCZOS_G + 0.5;
            final double aux = Math.E / (2.0 * Math.PI * shiftedShape);
            this.densityPrefactor2 = shape * Math.sqrt(aux) / LanczosApproximation.value(shape);
            this.logDensityPrefactor2 = Math.log(shape) + 0.5 * Math.log(aux) -
                    Math.log(LanczosApproximation.value(shape));
            this.densityPrefactor1 = this.densityPrefactor2 / scale *
                    Math.pow(shiftedShape, -shape) *  // XXX FastMath vs Math
                    Math.exp(shape + LANCZOS_G);
            this.logDensityPrefactor1 = this.logDensityPrefactor2 - Math.log(scale) -
                    Math.log(shiftedShape) * shape +
                    shape + LANCZOS_G;
            this.minY = shape + LANCZOS_G - Math.log(Double.MAX_VALUE);
            this.maxLogY = Math.log(Double.MAX_VALUE) / (shape - 1.0);
        }

        /**
         * Returns the shape parameter of {@code this} distribution.
         *
         * @return the shape parameter
         */
        public double getShape() {
            return shape;
        }

        /** {@inheritDoc} */
        @Override
        public double density(double x) {
            /* The present method must return the value of
             *
             *     1       x a     - x
             * ---------- (-)  exp(---)
             * x Gamma(a)  b        b
             *
             * where a is the shape parameter, and b the scale parameter.
             * Substituting the Lanczos approximation of Gamma(a) leads to the
             * following expression of the density
             *
             * a              e            1         y      a
             * - sqrt(------------------) ---- (-----------)  exp(a - y + g),
             * x      2 pi (a + g + 0.5)  L(a)  a + g + 0.5
             *
             * where y = x / b. The above formula is the "natural" computation, which
             * is implemented when no overflow is likely to occur. If overflow occurs
             * with the natural computation, the following identity is used. It is
             * based on the BOOST library
             * http://www.boost.org/doc/libs/1_35_0/libs/math/doc/sf_and_dist/html/math_toolkit/
             * special/sf_gamma/igamma.html
             * Formula (15) needs adaptations, which are detailed below.
             *
             *       y      a
             * (-----------)  exp(a - y + g)
             *  a + g + 0.5
             *                              y - a - g - 0.5    y (g + 0.5)
             *               = exp(a log1pm(---------------) - ----------- + g),
             *                                a + g + 0.5      a + g + 0.5
             *
             *  where log1pm(z) = log(1 + z) - z. Therefore, the value to be
             *  returned is
             *
             * a              e            1
             * - sqrt(------------------) ----
             * x      2 pi (a + g + 0.5)  L(a)
             *                              y - a - g - 0.5    y (g + 0.5)
             *               * exp(a log1pm(---------------) - ----------- + g).
             *                                a + g + 0.5      a + g + 0.5
             */
            if (x < 0) {
                return 0;
            }
            final double y = x / scale;
            if ((y <= minY) || (Math.log(y) >= maxLogY)) {
                /*
                 * Overflow.
                 */
                final double aux1 = (y - shiftedShape) / shiftedShape;
                final double aux2 = shape * (Math.log1p(aux1) - aux1); // XXX FastMath vs Math
                final double aux3 = -y * (LANCZOS_G + 0.5) / shiftedShape + LANCZOS_G + aux2;
                return densityPrefactor2 / x * Math.exp(aux3);
            }
            /*
             * Natural calculation.
             */
            return densityPrefactor1 * Math.exp(-y) * Math.pow(y, shape - 1);
        }

        /** {@inheritDoc} **/
        @Override
        public double logDensity(double x) {
            /*
             * see the comment in {@link #density(double)} for computation details
             */
            if (x < 0) {
                return Double.NEGATIVE_INFINITY;
            }
            final double y = x / scale;
            if ((y <= minY) || (Math.log(y) >= maxLogY)) {
                /*
                 * Overflow.
                 */
                final double aux1 = (y - shiftedShape) / shiftedShape;
                final double aux2 = shape * (Math.log1p(aux1) - aux1);
                final double aux3 = -y * (LANCZOS_G + 0.5) / shiftedShape + LANCZOS_G + aux2;
                return logDensityPrefactor2 - Math.log(x) + aux3;
            }
            /*
             * Natural calculation.
             */
            return logDensityPrefactor1 - y + Math.log(y) * (shape - 1);
        }

        /**
         * {@inheritDoc}
         *
         * The implementation of this method is based on:
         * <ul>
         *  <li>
         *   <a href="http://mathworld.wolfram.com/Chi-SquaredDistribution.html">
         *    Chi-Squared Distribution</a>, equation (9).
         *  </li>
         *  <li>Casella, G., &amp; Berger, R. (1990). <i>Statistical Inference</i>.
         *    Belmont, CA: Duxbury Press.
         *  </li>
         * </ul>
         */
        @Override
        public double cumulativeProbability(double x) {
            double ret;

            if (x <= 0) {
                ret = 0;
            } else {
                ret = RegularizedGamma.P.value(shape, x / scale);
            }

            return ret;
        }

        /**
         * {@inheritDoc}
         *
         * For shape parameter {@code alpha} and scale parameter {@code beta}, the
         * mean is {@code alpha * beta}.
         */
        @Override
        public double getMean() {
            return shape * scale;
        }

        /**
         * {@inheritDoc}
         *
         * For shape parameter {@code alpha} and scale parameter {@code beta}, the
         * variance is {@code alpha * beta^2}.
         *
         * @return {@inheritDoc}
         */
        @Override
        public double getVariance() {
            return shape * scale * scale;
        }

        /**
         * {@inheritDoc}
         *
         * The lower bound of the support is always 0 no matter the parameters.
         *
         * @return lower bound of the support (always 0)
         */
        @Override
        public double getSupportLowerBound() {
            return 0;
        }

        /**
         * {@inheritDoc}
         *
         * The upper bound of the support is always positive infinity
         * no matter the parameters.
         *
         * @return upper bound of the support (always Double.POSITIVE_INFINITY)
         */
        @Override
        public double getSupportUpperBound() {
            return Double.POSITIVE_INFINITY;
        }

        /**
         * {@inheritDoc}
         *
         * The support of this distribution is connected.
         *
         * @return {@code true}
         */
        @Override
        public boolean isSupportConnected() {
            return true;
        }
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-core/src/main/java/
     * org/apache/commons/numbers/core/Precision.java
     *
     * Utilities for comparing numbers.
     */
    static final class Precision {
        /**
         * <p>
         * Largest double-precision floating-point number such that
         * {@code 1 + EPSILON} is numerically equal to 1. This value is an upper
         * bound on the relative error due to rounding real numbers to double
         * precision floating-point numbers.
         * </p>
         * <p>
         * In IEEE 754 arithmetic, this is 2<sup>-53</sup>.
         * </p>
         *
         * @see <a href="http://en.wikipedia.org/wiki/Machine_epsilon">Machine epsilon</a>
         */
        static final double EPSILON;

        /**
         * Safe minimum, such that {@code 1 / SAFE_MIN} does not overflow.
         * In IEEE 754 arithmetic, this is also the smallest normalized
         * number 2<sup>-1022</sup>.
         */
        static final double SAFE_MIN;

        /** Exponent offset in IEEE754 representation. */
        private static final long EXPONENT_OFFSET = 1023L;

        /** Offset to order signed double numbers lexicographically. */
        private static final long SGN_MASK = 0x8000000000000000L;
        /** Offset to order signed double numbers lexicographically. */
        private static final int SGN_MASK_FLOAT = 0x80000000;
        /** Positive zero bits. */
        private static final long POSITIVE_ZERO_DOUBLE_BITS = Double.doubleToRawLongBits(+0.0);
        /** Negative zero bits. */
        private static final long NEGATIVE_ZERO_DOUBLE_BITS = Double.doubleToRawLongBits(-0.0);
        /** Positive zero bits. */
        private static final int POSITIVE_ZERO_FLOAT_BITS = Float.floatToRawIntBits(+0.0f);
        /** Negative zero bits. */
        private static final int NEGATIVE_ZERO_FLOAT_BITS = Float.floatToRawIntBits(-0.0f);

        static {
            /*
             *  This was previously expressed as = 0x1.0p-53;
             *  However, OpenJDK (Sparc Solaris) cannot handle such small
             *  constants: MATH-721
             */
            EPSILON = Double.longBitsToDouble((EXPONENT_OFFSET - 53L) << 52);

            /*
             * This was previously expressed as = 0x1.0p-1022;
             * However, OpenJDK (Sparc Solaris) cannot handle such small
             * constants: MATH-721
             */
            SAFE_MIN = Double.longBitsToDouble((EXPONENT_OFFSET - 1022L) << 52);
        }

        /**
         * Private constructor.
         */
        private Precision() {}

        /**
         * Returns true if the arguments are equal or within the range of allowed
         * error (inclusive).  Returns {@code false} if either of the arguments
         * is NaN.
         *
         * @param x first value
         * @param y second value
         * @param eps the amount of absolute error to allow.
         * @return {@code true} if the values are equal or within range of each other.
         */
        public static boolean equals(float x, float y, float eps) {
            return equals(x, y, 1) || Math.abs(y - x) <= eps;
        }

        /**
         * Returns true if the arguments are equal or within the range of allowed
         * error (inclusive).
         * Two float numbers are considered equal if there are {@code (maxUlps - 1)}
         * (or fewer) floating point numbers between them, i.e. two adjacent floating
         * point numbers are considered equal.
         * Adapted from <a href="
         * http://randomascii.wordpress.com/2012/02/25/comparing-floating-point-numbers-2012-edition
         * ">Bruce Dawson</a>.  Returns {@code false} if either of the arguments is NaN.
         *
         * @param x first value
         * @param y second value
         * @param maxUlps {@code (maxUlps - 1)} is the number of floating point
         * values between {@code x} and {@code y}.
         * @return {@code true} if there are fewer than {@code maxUlps} floating
         * point values between {@code x} and {@code y}.
         */
        static boolean equals(final float x, final float y, final int maxUlps) {

            final int xInt = Float.floatToRawIntBits(x);
            final int yInt = Float.floatToRawIntBits(y);

            final boolean isEqual;
            if (((xInt ^ yInt) & SGN_MASK_FLOAT) == 0) {
                // number have same sign, there is no risk of overflow
                isEqual = Math.abs(xInt - yInt) <= maxUlps;
            } else {
                // number have opposite signs, take care of overflow
                final int deltaPlus;
                final int deltaMinus;
                if (xInt < yInt) {
                    deltaPlus  = yInt - POSITIVE_ZERO_FLOAT_BITS;
                    deltaMinus = xInt - NEGATIVE_ZERO_FLOAT_BITS;
                } else {
                    deltaPlus  = xInt - POSITIVE_ZERO_FLOAT_BITS;
                    deltaMinus = yInt - NEGATIVE_ZERO_FLOAT_BITS;
                }

                if (deltaPlus > maxUlps) {
                    isEqual = false;
                } else {
                    isEqual = deltaMinus <= (maxUlps - deltaPlus);
                }

            }

            return isEqual && !Float.isNaN(x) && !Float.isNaN(y);

        }

        /**
         * Returns true iff they are equal as defined by
         * {@link #equals(double,double,int) equals(x, y, 1)}.
         *
         * @param x first value
         * @param y second value
         * @return {@code true} if the values are equal.
         */
        static boolean equals(double x, double y) {
            return equals(x, y, 1);
        }

        /**
         * Returns {@code true} if there is no double value strictly between the
         * arguments or the difference between them is within the range of allowed
         * error (inclusive). Returns {@code false} if either of the arguments
         * is NaN.
         *
         * @param x First value.
         * @param y Second value.
         * @param eps Amount of allowed absolute error.
         * @return {@code true} if the values are two adjacent floating point
         * numbers or they are within range of each other.
         */
        static boolean equals(double x, double y, double eps) {
            return equals(x, y, 1) || Math.abs(y - x) <= eps;
        }

        /**
         * Returns true if the arguments are equal or within the range of allowed
         * error (inclusive).
         * <p>
         * Two float numbers are considered equal if there are {@code (maxUlps - 1)}
         * (or fewer) floating point numbers between them, i.e. two adjacent
         * floating point numbers are considered equal.
         * </p>
         * <p>
         * Adapted from <a href="
         * http://randomascii.wordpress.com/2012/02/25/comparing-floating-point-numbers-2012-edition
         * ">Bruce Dawson</a>. Returns {@code false} if either of the arguments is NaN.
         * </p>
         *
         * @param x first value
         * @param y second value
         * @param maxUlps {@code (maxUlps - 1)} is the number of floating point
         * values between {@code x} and {@code y}.
         * @return {@code true} if there are fewer than {@code maxUlps} floating
         * point values between {@code x} and {@code y}.
         */
        static boolean equals(final double x, final double y, final int maxUlps) {

            final long xInt = Double.doubleToRawLongBits(x);
            final long yInt = Double.doubleToRawLongBits(y);

            final boolean isEqual;
            if (((xInt ^ yInt) & SGN_MASK) == 0L) {
                // number have same sign, there is no risk of overflow
                isEqual = Math.abs(xInt - yInt) <= maxUlps;
            } else {
                // number have opposite signs, take care of overflow
                final long deltaPlus;
                final long deltaMinus;
                if (xInt < yInt) {
                    deltaPlus  = yInt - POSITIVE_ZERO_DOUBLE_BITS;
                    deltaMinus = xInt - NEGATIVE_ZERO_DOUBLE_BITS;
                } else {
                    deltaPlus  = xInt - POSITIVE_ZERO_DOUBLE_BITS;
                    deltaMinus = yInt - NEGATIVE_ZERO_DOUBLE_BITS;
                }

                if (deltaPlus > maxUlps) {
                    isEqual = false;
                } else {
                    isEqual = deltaMinus <= (maxUlps - deltaPlus);
                }

            }

            return isEqual && !Double.isNaN(x) && !Double.isNaN(y);

        }

    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * d6d7b047cd45530986c34a3e66a1251940d6f7e9/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/RegularizedBeta.java
     *
     * <a href="http://mathworld.wolfram.com/RegularizedBetaFunction.html">
     * Regularized Beta function</a>.
     * <p>
     * This class is immutable.
     * </p>
     */
    static final class RegularizedBeta {
        /** Maximum allowed numerical error. */
        private static final double DEFAULT_EPSILON = 1e-14;

        /**
         * Computes the value of the
         * <a href="http://mathworld.wolfram.com/RegularizedBetaFunction.html">
         * regularized beta function</a> I(x, a, b).
         *
         * @param x Value.
         * @param a Parameter {@code a}.
         * @param b Parameter {@code b}.
         * @return the regularized beta function I(x, a, b).
         * @throws ArithmeticException if the algorithm fails to converge.
         */
        static double value(double x,
                double a,
                double b) {
            return value(x, a, b, DEFAULT_EPSILON, Integer.MAX_VALUE);
        }


        /**
         * Computes the value of the
         * <a href="http://mathworld.wolfram.com/RegularizedBetaFunction.html">
         * regularized beta function</a> I(x, a, b).
         *
         * The implementation of this method is based on:
         * <ul>
         *  <li>
         *   <a href="http://mathworld.wolfram.com/RegularizedBetaFunction.html">
         *   Regularized Beta Function</a>.
         *  </li>
         *  <li>
         *   <a href="http://functions.wolfram.com/06.21.10.0001.01">
         *   Regularized Beta Function</a>.
         *  </li>
         * </ul>
         *
         * @param x the value.
         * @param a Parameter {@code a}.
         * @param b Parameter {@code b}.
         * @param epsilon When the absolute value of the nth item in the
         * series is less than epsilon the approximation ceases to calculate
         * further elements in the series.
         * @param maxIterations Maximum number of "iterations" to complete.
         * @return the regularized beta function I(x, a, b).
         * @throws ArithmeticException if the algorithm fails to converge.
         */
        static double value(double x,
                final double a,
                final double b,
                double epsilon,
                int maxIterations) {
            if (Double.isNaN(x) ||
                    Double.isNaN(a) ||
                    Double.isNaN(b) ||
                    x < 0 ||
                    x > 1 ||
                    a <= 0 ||
                    b <= 0) {
                return Double.NaN;
            } else if (x > (a + 1) / (2 + b + a) &&
                    1 - x <= (b + 1) / (2 + b + a)) {
                return 1 - value(1 - x, b, a, epsilon, maxIterations);
            } else {
                final ContinuedFraction fraction = new ContinuedFraction() {
                    /** {@inheritDoc} */
                    @Override
                    protected double getB(int n, double x) {
                        if (n % 2 == 0) { // even
                            final double m = n / 2d;
                            return (m * (b - m) * x) /
                                    ((a + (2 * m) - 1) * (a + (2 * m)));
                        } else {
                            final double m = (n - 1d) / 2d;
                            return -((a + m) * (a + b + m) * x) /
                                    ((a + (2 * m)) * (a + (2 * m) + 1));
                        }
                    }

                    /** {@inheritDoc} */
                    @Override
                    protected double getA(int n, double x) {
                        return 1;
                    }
                };

                return Math.exp((a * Math.log(x)) + (b * Math.log1p(-x)) -
                        Math.log(a) - LogBeta.value(a, b)) /
                        fraction.evaluate(x, epsilon, maxIterations);
            }
        }

        private RegularizedBeta() {}
    }

    /**
     * Copied from https://github.com/apache/commons-numbers/blob/
     * c64fa8ae373cb8c71e3754788529d298a4d051a5/commons-numbers-gamma/src/main/java/
     * org/apache/commons/numbers/gamma/RegularizedGamma.java
     *
     * <a href="http://mathworld.wolfram.com/RegularizedGammaFunction.html">
     * Regularized Gamma functions</a>.
     *
     * Class is immutable.
     */
    static final class RegularizedGamma {
        /** Maximum allowed numerical error. */
        private static final double DEFAULT_EPSILON = 1e-15;

        /**
         * \( P(a, x) \) <a href="http://mathworld.wolfram.com/RegularizedGammaFunction.html">
         * regularized Gamma function</a>.
         *
         * Class is immutable.
         */
        public static class P {
            /**
             * Computes the regularized gamma function \( P(a, x) \).
             *
             * @param a Argument.
             * @param x Argument.
             * @return \( P(a, x) \).
             */
            public static double value(double a,
                    double x) {
                return value(a, x, DEFAULT_EPSILON, Integer.MAX_VALUE);
            }

            /**
             * Computes the regularized gamma function \( P(a, x) \).
             *
             * The implementation of this method is based on:
             * <ul>
             *  <li>
             *   <a href="http://mathworld.wolfram.com/RegularizedGammaFunction.html">
             *   Regularized Gamma Function</a>, equation (1)
             *  </li>
             *  <li>
             *   <a href="http://mathworld.wolfram.com/IncompleteGammaFunction.html">
             *   Incomplete Gamma Function</a>, equation (4).
             *  </li>
             *  <li>
             *   <a href="
             *   http://mathworld.wolfram.com/ConfluentHypergeometricFunctionoftheFirstKind.html">
             *   Confluent Hypergeometric Function of the First Kind</a>, equation (1).
             *  </li>
             * </ul>
             *
             * @param a Argument.
             * @param x Argument.
             * @param epsilon Tolerance in continued fraction evaluation.
             * @param maxIterations Maximum number of iterations in continued fraction evaluation.
             * @return \( P(a, x) \).
             */
            static double value(double a,
                    double x,
                    double epsilon,
                    int maxIterations) {
                if (Double.isNaN(a) ||
                        Double.isNaN(x) ||
                        a <= 0 ||
                        x < 0) {
                    return Double.NaN;
                } else if (x == 0) {
                    return 0;
                } else if (x >= a + 1) {
                    // Q should converge faster in this case.
                    return 1 - Q.value(a, x, epsilon, maxIterations);
                } else {
                    // Series.
                    double n = 0; // current element index
                    double an = 1 / a; // n-th element in the series
                    double sum = an; // partial sum
                    while (Math.abs(an / sum) > epsilon &&
                            n < maxIterations &&
                            sum < Double.POSITIVE_INFINITY) {
                        // compute next element in the series
                        n += 1;
                        an *= x / (a + n);

                        // update partial sum
                        sum += an;
                    }
                    if (n >= maxIterations) {
                        throw new RuntimeException(
                                "Failed to converge within " + maxIterations + " iterations");
                    } else if (Double.isInfinite(sum)) {
                        return 1;
                    } else {
                        return Math.exp(-x + (a * Math.log(x)) - LogGamma.value(a)) * sum;
                    }
                }
            }
        }

        /**
         * Creates the \( Q(a, x) \equiv 1 - P(a, x) \)
         * <a href="http://mathworld.wolfram.com/RegularizedGammaFunction.html">
         * regularized Gamma function</a>.
         *
         * Class is immutable.
         */
        public static class Q {
            /**
             * Computes the regularized gamma function \( Q(a, x) = 1 - P(a, x) \).
             *
             * @param a Argument.
             * @param x Argument.
             * @return \( Q(a, x) \).
             */
            public static double value(double a,
                    double x) {
                return value(a, x, DEFAULT_EPSILON, Integer.MAX_VALUE);
            }

            /**
             * Computes the regularized gamma function \( Q(a, x) = 1 - P(a, x) \).
             *
             * The implementation of this method is based on:
             * <ul>
             *  <li>
             *   <a href="http://mathworld.wolfram.com/RegularizedGammaFunction.html">
             *   Regularized Gamma Function</a>, equation (1).
             *  </li>
             *  <li>
             *   <a href="http://functions.wolfram.com/GammaBetaErf/GammaRegularized/10/0003/">
             *   Regularized incomplete gamma function: Continued fraction representations
             *   (formula 06.08.10.0003)</a>
             *  </li>
             * </ul>
             *
             * @param a Argument.
             * @param x Argument.
             * @param epsilon Tolerance in continued fraction evaluation.
             * @param maxIterations Maximum number of iterations in continued fraction evaluation.
             * @return \( Q(a, x) \).
             */
            static double value(final double a,
                    double x,
                    double epsilon,
                    int maxIterations) {
                if (Double.isNaN(a) ||
                        Double.isNaN(x) ||
                        a <= 0 ||
                        x < 0) {
                    return Double.NaN;
                } else if (x == 0) {
                    return 1;
                } else if (x < a + 1) {
                    // P should converge faster in this case.
                    return 1 - P.value(a, x, epsilon, maxIterations);
                } else {
                    final ContinuedFraction cf = new ContinuedFraction() {
                        /** {@inheritDoc} */
                        @Override
                        protected double getA(int n, double x) {
                            return ((2 * n) + 1) - a + x;
                        }

                        /** {@inheritDoc} */
                        @Override
                        protected double getB(int n, double x) {
                            return n * (a - n);
                        }
                    };

                    return Math.exp(-x + (a * Math.log(x)) - LogGamma.value(a)) /
                            cf.evaluate(x, epsilon, maxIterations);
                }
            }
        }

        private RegularizedGamma() {}
    }

    /**
     * Copied from https://github.com/apache/commons-statistics/blob/
     * aa5cbad11346df9e3feb789c5e3e85df29c1e3cc/commons-statistics-distribution/src/main/java/
     * org/apache/commons/statistics/distribution/SaddlePointExpansion.java
     *
     * Utility class used by various distributions to accurately compute their
     * respective probability mass functions. The implementation for this class is
     * based on the Catherine Loader's
     * <a href="http://www.herine.net/stat/software/dbinom.html">dbinom</a> routines.
     *
     * This class is not intended to be called directly.
     */
    static final class SaddlePointExpansion {
        /** 2 &pi; */
        private static final double TWO_PI = 2 * Math.PI;
        /** 1/2 * log(2 &pi;). */
        private static final double HALF_LOG_TWO_PI = 0.5 * Math.log(TWO_PI);

        /** exact Stirling expansion error for certain values. */
        private static final double[] EXACT_STIRLING_ERRORS = {
                0.0, /* 0.0 */
                0.1534264097200273452913848, /* 0.5 */
                0.0810614667953272582196702, /* 1.0 */
                0.0548141210519176538961390, /* 1.5 */
                0.0413406959554092940938221, /* 2.0 */
                0.03316287351993628748511048, /* 2.5 */
                0.02767792568499833914878929, /* 3.0 */
                0.02374616365629749597132920, /* 3.5 */
                0.02079067210376509311152277, /* 4.0 */
                0.01848845053267318523077934, /* 4.5 */
                0.01664469118982119216319487, /* 5.0 */
                0.01513497322191737887351255, /* 5.5 */
                0.01387612882307074799874573, /* 6.0 */
                0.01281046524292022692424986, /* 6.5 */
                0.01189670994589177009505572, /* 7.0 */
                0.01110455975820691732662991, /* 7.5 */
                0.010411265261972096497478567, /* 8.0 */
                0.009799416126158803298389475, /* 8.5 */
                0.009255462182712732917728637, /* 9.0 */
                0.008768700134139385462952823, /* 9.5 */
                0.008330563433362871256469318, /* 10.0 */
                0.007934114564314020547248100, /* 10.5 */
                0.007573675487951840794972024, /* 11.0 */
                0.007244554301320383179543912, /* 11.5 */
                0.006942840107209529865664152, /* 12.0 */
                0.006665247032707682442354394, /* 12.5 */
                0.006408994188004207068439631, /* 13.0 */
                0.006171712263039457647532867, /* 13.5 */
                0.005951370112758847735624416, /* 14.0 */
                0.005746216513010115682023589, /* 14.5 */
                0.005554733551962801371038690 /* 15.0 */
        };

        /**
         * Forbid construction.
         */
        private SaddlePointExpansion() {}

        /**
         * Compute the error of Stirling's series at the given value.
         * <p>
         * References:
         * <ol>
         * <li>Eric W. Weisstein. "Stirling's Series." From MathWorld--A Wolfram Web
         * Resource. <a target="_blank"
         * href="http://mathworld.wolfram.com/StirlingsSeries.html">
         * http://mathworld.wolfram.com/StirlingsSeries.html</a></li>
         * </ol>
         * </p>
         *
         * @param z the value.
         * @return the Striling's series error.
         */
        static double getStirlingError(double z) {
            double ret;
            if (z < 15.0) {
                double z2 = 2.0 * z;
                if (Math.floor(z2) == z2) {
                    ret = EXACT_STIRLING_ERRORS[(int) z2];
                } else {
                    ret = LogGamma.value(z + 1.0) - (z + 0.5) * Math.log(z) +
                            z - HALF_LOG_TWO_PI;
                }
            } else {
                double z2 = z * z;
                ret = (0.083333333333333333333 -
                        (0.00277777777777777777778 -
                                (0.00079365079365079365079365 -
                                        (0.000595238095238095238095238 -
                                                0.0008417508417508417508417508 /
                                                        z2) / z2) / z2) / z2) / z;
            }
            return ret;
        }

        /**
         * A part of the deviance portion of the saddle point approximation.
         * <p>
         * References:
         * <ol>
         * <li>Catherine Loader (2000). "Fast and Accurate Computation of Binomial
         * Probabilities.". <a target="_blank"
         * href="http://www.herine.net/stat/papers/dbinom.pdf">
         * http://www.herine.net/stat/papers/dbinom.pdf</a></li>
         * </ol>
         * </p>
         *
         * @param x the x value.
         * @param mu the average.
         * @return a part of the deviance.
         */
        static double getDeviancePart(double x, double mu) {
            double ret;
            if (Math.abs(x - mu) < 0.1 * (x + mu)) {
                double d = x - mu;
                double v = d / (x + mu);
                double s1 = v * d;
                double s = Double.NaN;
                double ej = 2.0 * x * v;
                v *= v;
                int j = 1;
                while (s1 != s) {
                    s = s1;
                    ej *= v;
                    s1 = s + ej / ((j * 2) + 1);
                    ++j;
                }
                ret = s1;
            } else {
                if (x == 0) {
                    return mu;
                }
                ret = x * Math.log(x / mu) + mu - x;
            }
            return ret;
        }

        /**
         * Compute the logarithm of the PMF for a binomial distribution
         * using the saddle point expansion.
         *
         * @param x the value at which the probability is evaluated.
         * @param n the number of trials.
         * @param p the probability of success.
         * @param q the probability of failure (1 - p).
         * @return log(p(x)).
         */
        static double logBinomialProbability(int x, int n, double p, double q) {
            double ret;
            if (x == 0) {
                if (p < 0.1) {
                    ret = -getDeviancePart(n, n * q) - n * p;
                } else {
                    if (n == 0) {
                        return 0;
                    }
                    ret = n * Math.log(q);
                }
            } else if (x == n) {
                if (q < 0.1) {
                    ret = -getDeviancePart(n, n * p) - n * q;
                } else {
                    ret = n * Math.log(p);
                }
            } else {
                ret = getStirlingError(n) - getStirlingError(x) -
                        getStirlingError(n - x) - getDeviancePart(x, n * p) -
                        getDeviancePart(n - x, n * q);
                final double f = (TWO_PI * x * (n - x)) / n;
                ret = -0.5 * Math.log(f) + ret;
            }
            return ret;
        }
    }
    //CHECKSTYLE:ON: MagicNumber
}
