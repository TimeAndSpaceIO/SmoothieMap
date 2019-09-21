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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.timeandspace.collect.Equivalence;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.Contract;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.Utils.checkNonNegative;
import static io.timeandspace.smoothie.Utils.checkNonNull;

public final class SmoothieMapBuilder<K, V> {

    static final long UNKNOWN_SIZE = Long.MAX_VALUE;

    @Contract(value = " -> new", pure = true)
    public static <K, V> SmoothieMapBuilder<K, V> create() {
        return new SmoothieMapBuilder<>();
    }

    /* if Supported intermediateSegments */
    /** Mirror field: {@link SmoothieMap#allocateIntermediateSegments}. */
    private boolean allocateIntermediateSegments;
    /** Mirror field: {@link SmoothieMap#splitBetweenTwoNewSegments}. */
    private boolean splitBetweenTwoNewSegments;
    /* endif */
    /* if Flag doShrink */
    /** Mirror field: {@link SmoothieMap#doShrink}. */
    private boolean doShrink;
    /* endif */

    private Equivalence<K> keyEquivalence = Equivalence.defaultEquality();
    private @Nullable Supplier<ToLongFunction<K>> keyHashFunctionFactory = null;
    private Equivalence<V> valueEquivalence = Equivalence.defaultEquality();

    private long expectedSize = UNKNOWN_SIZE;
    private long minExpectedSize = UNKNOWN_SIZE;

    /* if Tracking hashCodeDistribution */
    private float maxProbabilityOfOccasionIfHashFunctionWasRandom;
    private @Nullable Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction = null;
    /* endif */

    private SmoothieMapBuilder() {
        defaultOptimizationConfiguration();
    }

    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> optimizeFor(OptimizationObjective optimizationObjective) {
        switch (optimizationObjective) {
            case ALLOCATION_RATE:
                /* if Supported intermediateSegments */
                this.allocateIntermediateSegments = false;
                this.splitBetweenTwoNewSegments = false;
                /* endif */
                /* if Flag doShrink */
                this.doShrink = false;
                /* endif */
                break;
            case MEMORY_FOOTPRINT:
                /* if Supported intermediateSegments */
                this.allocateIntermediateSegments = true;
                this.splitBetweenTwoNewSegments = true;
                /* endif */
                /* if Flag doShrink */
                this.doShrink = true;
                /* endif */
                break;
            default:
                throw new AssertionError("Unknown OptimizationObjective: " + optimizationObjective);
        }
        return this;
    }

    /**
     * The default objective is a compromise between {@link OptimizationObjective#MEMORY_FOOTPRINT}
     * and {@link OptimizationObjective#ALLOCATION_RATE}.
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultOptimizationConfiguration() {
        /* if Supported intermediateSegments */
        this.allocateIntermediateSegments = true;
        this.splitBetweenTwoNewSegments = false;
        /* endif */
        /* if Flag doShrink */
        this.doShrink = true;
        /* endif */
        return this;
    }

    /* if Supported intermediateSegments */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> allocateIntermediateSegments(
            boolean allocateIntermediateSegments) {
        this.allocateIntermediateSegments = allocateIntermediateSegments;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> splitBetweenTwoNewSegments(boolean splitBetweenTwoNewSegments) {
        this.splitBetweenTwoNewSegments = splitBetweenTwoNewSegments;
        return this;
    }
    /* endif */

    /* if Flag doShrink */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> doShrink(boolean doShrink) {
        this.doShrink = doShrink;
        return this;
    }
    /* endif */

    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> keyEquivalence(Equivalence<K> keyEquivalence) {
        checkNonNull(keyEquivalence);
        this.keyEquivalence = keyEquivalence;
        return this;
    }

    /**
     * Sets the {@link Equivalence} used for comparing keys in SmoothieMaps created by this builder
     * to {@link Equivalence#defaultEquality()}.
     *
     * @return this builder back
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultKeyEquivalence() {
        this.keyEquivalence = Equivalence.defaultEquality();
        return this;
    }

    /**
     * Sets a key hash function to be used in {@linkplain SmoothieMap SmoothieMaps} created by this
     * builder.
     *
     * <p>The specified hash function must be consistent with {@link Object#equals} on the key
     * objects, or a custom key equivalence if specified via {@link #keyEquivalence(Equivalence)} in
     * the same way as {@link Object#hashCode()} must be consistent with {@code equals()}. This is
     * the user's responsibility to ensure this. When {@link #keyEquivalence(Equivalence)} is called
     * on a builder object, the key hash function, if already configured to a non-default, is
     * <i>not</i> reset. On the other hand, if {@code keyHashFunction()} (or {@link
     * #keyHashFunctionFactory(Supplier)}) is never called on a builder, or {@link
     * #defaultKeyHashFunction()} is called, it's not necessary to configure the key hash function
     * along with any custom {@link #keyEquivalence(Equivalence)} because by default {@code
     * SmoothieMapBuilder} does respect {@link Equivalence#hash}.
     *
     * @param hashFunction a key hash function for each {@code SmoothieMap} created using this
     * builder
     * @return this builder back
     * @see #keyHashFunctionFactory(Supplier)
     * @see #defaultKeyHashFunction()
     * @see #keyEquivalence(Equivalence)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> keyHashFunction(ToLongFunction<K> hashFunction) {
        checkNonNull(hashFunction);
        this.keyHashFunctionFactory = () -> hashFunction;
        return this;
    }

    /**
     * Sets a factory to obtain a key hash function for each {@link SmoothieMap} created using this
     * builder. Compared to {@link #keyHashFunction(ToLongFunction)}, this method allows to insert
     * randomness into the hash function:
     * <pre>{@code
     * builder.keyHashFunctionFactory(() -> {
     *     LongHashFunction hashF = LongHashFunction.xx(ThreadLocalRandom.current().nextLong());
     *     return hashF::hashChars;
     * });}</pre>
     *
     * <p>Hash functions returned by the specified factory must be consistent with {@link
     * Object#equals} on the key objects, or a custom key equivalence if specified via {@link
     * #keyEquivalence(Equivalence)} in the same way as {@link Object#hashCode()} must be consistent
     * with {@code equals()}. This is the user's responsibility to ensure this. When {@link
     * #keyEquivalence(Equivalence)} is called on a builder object, the key hash function factory,
     * if already configured to a non-default, is <i>not</i> reset. See the Javadoc comment for
     * {@link #keyHashFunction(ToLongFunction)} for more info.
     *
     * @param hashFunctionFactory the factory to create a key hash function for each {@code
     * SmoothieMap} created using this builder
     * @return this builder back
     * @see #keyHashFunction(ToLongFunction)
     * @see #defaultKeyHashFunction()
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> keyHashFunctionFactory(
            Supplier<ToLongFunction<K>> hashFunctionFactory) {
        checkNonNull(hashFunctionFactory);
        this.keyHashFunctionFactory = hashFunctionFactory;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultKeyHashFunction() {
        this.keyHashFunctionFactory = null;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> valueEquivalence(Equivalence<V> valueEquivalence) {
        checkNonNull(valueEquivalence);
        this.valueEquivalence = valueEquivalence;
        return this;
    }

    /**
     * Sets the {@link Equivalence} used for comparing values in SmoothieMaps created by this
     * builder to {@link Equivalence#defaultEquality()}.
     *
     * @return this builder back
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultValueEquivalence() {
        this.valueEquivalence = Equivalence.defaultEquality();
        return this;
    }


    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> expectedSize(long expectedSize) {
        checkNonNegative(expectedSize, "expected size");
        this.expectedSize = expectedSize;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> unknownExpectedSize() {
        this.expectedSize = UNKNOWN_SIZE;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> minExpectedSize(long minExpectedSize) {
        checkNonNegative(minExpectedSize, "minimum expected size");
        this.minExpectedSize = minExpectedSize;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> unknownMinExpectedSize() {
        this.minExpectedSize = UNKNOWN_SIZE;
        return this;
    }

    /* if Tracking hashCodeDistribution */
    /**
     * When poor hash code distribution is detected in a {@link SmoothieMap}, the probability of
     * this occasion (assuming a perfectly random hash function) is evaluated as if the {@code
     * SmoothieMap} was populated from size 0 to the current size (N) by just inserting N entries
     * without additional entry removals and insertions. If this probability is less than or equal
     * to the given {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} value, the specified
     * {@code reportingAction} is called.
     *
     * <p>{@code reportingAction} is fed with a {@link PoorHashCodeDistributionOccasion} object
     * with an additional information about the event.
     *
     * <p>In some sense, {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} can be viewed as
     * the <i>maximum allowed probability of false positive reporting</i>, while the hash function
     * does in fact distribute hash codes well. However, whether a hash function is "good" or "bad"
     * is not a binary conclusion: every hash function distributes hash codes with different
     * quality. Therefore "false positive" is not actually defined. The poorer the hash function
     * configured for a {@link SmoothieMap} (via {@link #keyHashFunctionFactory(Supplier)}, {@link
     * #keyHashFunction(ToLongFunction)}, or {@link #keyEquivalence(Equivalence)}), the higher the
     * chances that the {@code reportingAction} is called in the course of the map's operation.
     * {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} affects the chances of reporting
     * regardless of the hash function quality.
     *
     * <p>Value of {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} can be between 0.00001
     * and 0.2. The choice of the value should depend on the escalation level of the reporting
     * action (e. g. logging vs. alerting) and the acceptability of false positives.
     *
     * <p>If there is a "fleet" of SmoothieMaps many of which are expected to be small, it may be
     * impossible to capture a hash code distribution anomaly across the fleet with a small
     * {@code maxProbabilityOfOccasionIfHashFunctionWasRandom} value configured for each of these
     * SmoothieMaps. Instead, a relatively high probability could be configured in this case (e. g.
     * in the 0.01-0.1 range) and the reporting action incrementing a common counter of occurrences.
     * When the counter increases to the value that {@code
     * configured probability per SmoothieMap ^ counterValue < target probability across the fleet},
     * there is a hash code distribution anomaly. ({@code probability ^ counterValue} exponentiation
     * is used assuming the independence of the SmoothieMaps; the probability of the compound event
     * is the product of probabilities.)
     *
     * <p>If entries are continuously removed from and inserted into a {@code SmoothieMap} (i. e.
     * there is a continuous entry turnover), the probability that at some point the hash code
     * distribution will be arbitrarily bad approaches 100% even if the hash function is of very
     * high quality. The poor hash code distribution detection algorithm in the {@code SmoothieMap}
     * doesn't discount for this effect. It means that for long-living SmoothieMaps with a
     * continuous entry turnover, the probability of a false positive call to {@code
     * reportingAction} approaches 100% for arbitrarily small {@code
     * maxProbabilityOfOccasionIfHashFunctionWasRandom} value. In such a case, false positive calls
     * to {@code reportingAction} with probability higher than {@code
     * maxProbabilityOfOccasionIfHashFunctionWasRandom} should either be tolerable, or discounting
     * for the continuous entry churn should be done inside the {@code reportingAction} based on the
     * total number of removals and insertions into a {@code SmoothieMap} that have happened from
     * the moment of its creation. Note, however, that this is a
     * <a href="https://stats.stackexchange.com/questions/384836">hard statistical problem</a> to do
     * such discounting correctly. Such discounting should also depend on the size of the total pool
     * of the keys that may in a {@code SmoothieMap} during its lifetime. The pool may be
     * practically unbounded (e. g. if the keys are UUIDs or session keys), or it may be only
     * slightly greater than the size of the map at any given moment.
     *
     * <p>Currently, {@code SmoothieMap} supports only a single {@code reportingAction} configured
     * for a single {@code maxProbabilityOfOccasionIfHashFunctionWasRandom}. If this method is
     * called repetitively, the previous reporting action is overridden and not used in the
     * SmoothieMaps created by this builder. {@link #dontReportPoorHashCodeDistribution()} could be
     * called to cancel any reporting if previously configured.
     *
     * <p>The {@code reportingAction} is called in the context of the thread in which the last
     * insertion into a {@code SmoothieMap} has occurred after which the distribution of hash codes
     * among all keys in the map is considered poor. Therefore, the {@code reportingAction} may add
     * to the latency of insertions into a {@code SmoothieMap}. You may want to delegate heavyweight
     * analysis of poor hash code distribution events, logging, or alerting to a different executor.
     *
     * @param maxProbabilityOfOccasionIfHashFunctionWasRandom the max tolerable probability that
     * the poor hash code distribution happens while the key hash function is perfectly benign
     * @param reportingAction the action to perform when a poor hash code distribution is detected
     * @return this builder back
     * @see PoorHashCodeDistributionOccasion
     * @see #keyHashFunctionFactory(Supplier)
     * @see #keyHashFunction(ToLongFunction)
     * @see #keyEquivalence(Equivalence)
     * @see #dontReportPoorHashCodeDistribution()
     */
    @CanIgnoreReturnValue
    @Contract("_, _ -> this")
    public SmoothieMapBuilder<K, V> reportPoorHashCodeDistribution(
            float maxProbabilityOfOccasionIfHashFunctionWasRandom,
            Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction) {
        if (maxProbabilityOfOccasionIfHashFunctionWasRandom <
                (float) SmoothieMap.POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MIN ||
                maxProbabilityOfOccasionIfHashFunctionWasRandom >
                        (float) SmoothieMap.POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MAX)
        {
            throw new IllegalArgumentException(
                    maxProbabilityOfOccasionIfHashFunctionWasRandom + " is out of [" +
                    SmoothieMap.POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MIN + ", " +
                    SmoothieMap.POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MAX + "] bounds"
            );
        }
        checkNonNull(reportingAction);
        this.maxProbabilityOfOccasionIfHashFunctionWasRandom =
                maxProbabilityOfOccasionIfHashFunctionWasRandom;
        this.reportingAction = reportingAction;
        return this;
    }

    /**
     * Specifies that poor hash code distribution events shouldn't be tracked and reported in
     * SmoothieMaps created by this builder. Since this is also the default behaviour, it might make
     * sense to call this method only if {@link #reportPoorHashCodeDistribution} was previously
     * called on the same builder object.
     *
     * @return this builder back
     * @see #reportPoorHashCodeDistribution
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> dontReportPoorHashCodeDistribution() {
        this.reportingAction = null;
        return this;
    }
    /* endif */

    /**
     * Creates a new {@link SmoothieMap} with the configurations from this builder. The created
     * {@code SmoothieMap} doesn't hold an internal reference to the builder, and all subsequent
     * changes to the builder doesn't affect the configurations of the map.
     *
     * @return a new {@code SmoothieMap} with the configurations from this builder
     */
    @Contract(" -> new")
    public SmoothieMap<K, V> build() {
        boolean isDefaultKeyEquivalence = keyEquivalence.equals(Equivalence.defaultEquality());
        boolean isDefaultValueEquivalence = valueEquivalence.equals(Equivalence.defaultEquality());
        if (keyHashFunctionFactory == null && isDefaultKeyEquivalence &&
                isDefaultValueEquivalence) {
            return new SmoothieMap<>(this);
        } else if (keyHashFunctionFactory != null && isDefaultKeyEquivalence &&
                isDefaultValueEquivalence) {
            return new SmoothieMapWithCustomKeyHashFunction<>(this);
        } else if (!isDefaultKeyEquivalence && isDefaultValueEquivalence) {
            return new SmoothieMapWithCustomKeyEquivalence<>(this);
        } else if (keyHashFunctionFactory == null && isDefaultKeyEquivalence) {
            Utils.verifyThat(!isDefaultValueEquivalence);
            return new SmoothieMapWithCustomValueEquivalence<>(this);
        } else {
            return new SmoothieMapWithCustomKeyAndValueEquivalences<>(this);
        }
    }

    /* if Supported intermediateSegments */
    boolean allocateIntermediateSegments() {
        return allocateIntermediateSegments;
    }

    boolean splitBetweenTwoNewSegments() {
        return splitBetweenTwoNewSegments;
    }
    /* endif */

    /* if Flag doShrink */
    boolean doShrink() {
        return doShrink;
    }
    /* endif */

    Equivalence<K> keyEquivalence() {
        return keyEquivalence;
    }

    ToLongFunction<K> keyHashFunction() {
        if (keyHashFunctionFactory != null) {
            return keyHashFunctionFactory.get();
        }
        if (keyEquivalence.equals(Equivalence.defaultEquality())) {
            return DefaultHashFunction.instance();
        }
        return new EquivalenceBasedHashFunction<>(keyEquivalence);
    }

    Equivalence<V> valueEquivalence() {
        return valueEquivalence;
    }

    long expectedSize() {
        return expectedSize;
    }

    long minExpectedSize() {
        return minExpectedSize;
    }

    /* if Tracking hashCodeDistribution */
    @Nullable HashCodeDistribution<K, V> createHashCodeDistributionIfNeeded() {
        if (reportingAction == null) {
            return null;
        }
        float poorHashCodeDistrib_badOccasion_minReportingProb =
                1.0f - maxProbabilityOfOccasionIfHashFunctionWasRandom;
        return new HashCodeDistribution<>(
                poorHashCodeDistrib_badOccasion_minReportingProb, reportingAction);
    }
    /* endif */
}
