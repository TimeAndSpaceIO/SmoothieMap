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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.Contract;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.Utils.checkNonNegative;

public final class SmoothieMapBuilder<K, V> {

    static final long UNKNOWN_SIZE = Long.MAX_VALUE;

    @Contract(value = " -> new", pure = true)
    public static <K, V> SmoothieMapBuilder<K, V> newBuilder() {
        return new SmoothieMapBuilder<>();
    }

    private SmoothieMapBuilder() {}

    private boolean allocateIntermediateSegments = true;
    private boolean doShrink = true;
    private @Nullable Supplier<ToLongFunction<K>> hashFunctionFactory = null;

    private long expectedSize = UNKNOWN_SIZE;
    private long minExpectedSize = UNKNOWN_SIZE;

    /* if Tracking hashCodeDistribution */
    private double maxProbabilityOfOccasionIfHashFunctionWasRandom;
    private @Nullable Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction = null;
    /* endif */

    /**
     * The default objective is {@link OptimizationObjective#MEMORY_FOOTPRINT}.
     */
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> optimizeFor(OptimizationObjective optimizationObjective) {
        switch (optimizationObjective) {
            case ALLOCATION_RATE:
                this.allocateIntermediateSegments = false;
                this.doShrink = false;
                break;
            case MEMORY_FOOTPRINT:
                this.allocateIntermediateSegments = true;
                this.doShrink = true;
                break;
            default:
                throw new AssertionError("Unknown OptimizationObjective: " + optimizationObjective);
        }
        return this;
    }

    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> allocateIntermediateSegments(
            boolean allocateIntermediateSegments) {
        this.allocateIntermediateSegments = allocateIntermediateSegments;
        return this;
    }

    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> doShrink(boolean doShrink) {
        this.doShrink = doShrink;
        return this;
    }

    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> hashFunctionFactory(
            Supplier<ToLongFunction<K>> hashFunctionFactory) {
        Utils.requireNonNull(hashFunctionFactory);
        this.hashFunctionFactory = hashFunctionFactory;
        return this;
    }

    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultHashFunctionFactory() {
        this.hashFunctionFactory = null;
        return this;
    }

    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> expectedSize(long expectedSize) {
        checkNonNegative(expectedSize, "expected size");
        this.expectedSize = expectedSize;
        return this;
    }

    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> unknownExpectedSize() {
        this.expectedSize = UNKNOWN_SIZE;
        return this;
    }

    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> minExpectedSize(long minExpectedSize) {
        checkNonNegative(minExpectedSize, "minimum expected size");
        this.minExpectedSize = minExpectedSize;
        return this;
    }

    public SmoothieMapBuilder<K, V> unknownMinExpectedSize() {
        this.minExpectedSize = UNKNOWN_SIZE;
        return this;
    }

    /* if Tracking hashCodeDistribution */
    /**
     * TODO amend this doc
     *
     * When poor hash code distribution is detected, the probability of that occasion (assuming
     * a truly <i>random</i> hash function) is evaluated as if the {@code SmoothieMap} was
     * populated from size 0 to the current size (N) by just inserting N entries without
     * additional entry removals and insertions (in other words, the hash code distribution is
     * evaluated statically rather than dynamically). If this probability is less than or equal
     * to the given {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} value, the specified
     * {@code reportingAction} is called.
     *
     * <p>In some sense, {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} can be viewed
     * as the <i>maximum allowed probability of false positive reporting</i>, while the hash
     * function does in fact distribute hash codes well. However, whether a hash function is
     * "good" or "bad" is not a binary conclusion: every hash function distributes hash codes
     * with different quality. Therefore "false positive" is not actually defined. The poorer
     * the hash function configured for a {@code SmoothieMap}, the higher the chances that the
     * {@code reportingAction} is called in the course of the map's operation. {@code
     * maxProbabilityOfOccasionIfHashFunctionWasRandom} affects the chances of reporting
     * regardless of the hash function quality.
     *
     * <p>Note that if a {@code SmoothieMap} has very long lifecycle and there is a constant
     * turnover of entries (some entries are removed and some new entries are inserted),
     * statistically it might be very likely that sooner or later there will be a moment when
     * the distribution of hash codes in the map is poor and it's going to be reported. This is
     * because it's impossible to estimate the size of the pool of the keys that appear in a
     * SmoothieMap during its lifetime (it may be practically unbounded, or it may be only
     * slightly greater than the size of a map at any given moment) and therefore correctly
     * discount probability for the dynamic turnover factor. It's recommended to specify lower
     * {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} if the lifetimes of SmoothieMaps
     * that are constructed with this builder are long and there is a turnover of keys and there
     * are infinitely many different keys that may in the maps.
     *
     * @param maxProbabilityOfOccasionIfHashFunctionWasRandom
     * @param reportingAction
     * @return
     * TODO add a higher bound estimate of the key pool cardinality as a parameter?
     *  or just a boolean indicating infinite number of possible keys
     */
    @Contract("_, _ -> this")
    public SmoothieMapBuilder<K, V> reportPoorHashCodeDistribution(
            double maxProbabilityOfOccasionIfHashFunctionWasRandom,
            Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction) {
        Utils.requireNonNull(reportingAction);
        this.maxProbabilityOfOccasionIfHashFunctionWasRandom =
                maxProbabilityOfOccasionIfHashFunctionWasRandom;
        this.reportingAction = reportingAction;
        return this;
    }
    /* endif */

    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> dontReportPoorHashCodeDistrubution() {
        this.reportingAction = null;
        return this;
    }

    @Contract(" -> new")
    public SmoothieMap<K, V> build() {
        return new SmoothieMap<>(this);
    }

    boolean allocateIntermediateSegments() {
        return allocateIntermediateSegments;
    }

    boolean doShrink() {
        return doShrink;
    }

    @Nullable Supplier<ToLongFunction<K>> hashFunctionFactory() {
        return hashFunctionFactory;
    }

    long expectedSize() {
        return expectedSize;
    }

    long minExpectedSize() {
        return minExpectedSize;
    }

    double maxProbabilityOfOccasionIfHashFunctionWasRandom() {
        return maxProbabilityOfOccasionIfHashFunctionWasRandom;
    }

    @Nullable Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction() {
        return reportingAction;
    }
}
