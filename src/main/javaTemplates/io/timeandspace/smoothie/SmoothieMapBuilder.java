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
import static io.timeandspace.smoothie.Utils.verifyThat;

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
    private double maxProbabilityOfOccasionIfHashFunctionWasRandom;
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
     * Sets a key hash function to be used in SmoothieMaps created by this builder.
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
     * @param hashFunction a key hash function for each SmoothieMap created using this builder
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
     * Sets a factory to obtain a key hash function for each SmoothieMap created using this builder.
     * Compared to {@link #keyHashFunction(ToLongFunction)}, this method allows to insert randomness
     * into the hash function:
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
     * @param hashFunctionFactory the factory to create a key hash function for each SmoothieMap
     * created using this builder
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
    @CanIgnoreReturnValue
    @Contract("_, _ -> this")
    public SmoothieMapBuilder<K, V> reportPoorHashCodeDistribution(
            double maxProbabilityOfOccasionIfHashFunctionWasRandom,
            Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction) {
        checkNonNull(reportingAction);
        this.maxProbabilityOfOccasionIfHashFunctionWasRandom =
                maxProbabilityOfOccasionIfHashFunctionWasRandom;
        this.reportingAction = reportingAction;
        return this;
    }

    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> dontReportPoorHashCodeDistribution() {
        this.reportingAction = null;
        return this;
    }
    /* endif */

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
    double maxProbabilityOfOccasionIfHashFunctionWasRandom() {
        return maxProbabilityOfOccasionIfHashFunctionWasRandom;
    }

    @Nullable Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction() {
        return reportingAction;
    }
    /* endif */
}
