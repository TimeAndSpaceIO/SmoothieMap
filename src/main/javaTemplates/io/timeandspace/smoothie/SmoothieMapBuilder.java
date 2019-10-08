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

/* if Tracking hashCodeDistribution */
import java.util.function.Consumer;
/* endif */
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.Utils.checkNonNegative;
import static io.timeandspace.smoothie.Utils.checkNonNull;

/**
 * SmoothieMapBuilder is used to configure and create {@link SmoothieMap}s. A new builder could be
 * created via {@link SmoothieMap#newBuilder()} method. A SmoothieMap is created using {@link
 * #build()} method.
 *
 * <p>SmoothieMapBuilder is mutable: all its configuration methods change its state, and return the
 * receiver builder object to enable the "fluent builder" pattern:
 * <pre>{@code
 * SmoothieMap.newBuilder().expectedSize(100_000).doShrink(false).build();
 * }</pre>
 *
 * <p>SmoothieMapBuilder could be used to create any number of SmoothieMap objects. Created
 * SmoothieMaps don't retain a link to the builder and therefore don't depend on any possible
 * subsequent modifications of the builder's state.
 *
 * @param <K> the type of keys in created SmoothieMaps
 * @param <V> the type of values in created SmoothieMaps
 */
public final class SmoothieMapBuilder<K, V> {

    static final long UNKNOWN_SIZE = Long.MAX_VALUE;
    private static final double MAX_EXPECTED_SIZE_ERROR_FRACTION = 0.05;

    @Contract(value = " -> new", pure = true)
    static <K, V> SmoothieMapBuilder<K, V> create() {
        return new SmoothieMapBuilder<>();
    }

    /* if Supported intermediateSegments */
    /** Mirror field: {@link SmoothieMap#allocateIntermediateSegments}. */
    private boolean allocateIntermediateCapacitySegments;
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
    private long minPeakSize = UNKNOWN_SIZE;

    /* if Tracking hashCodeDistribution */
    private float maxProbabilityOfOccasionIfHashFunctionWasRandom;
    private @Nullable Consumer<PoorHashCodeDistributionOccasion<K, V>> reportingAction = null;
    /* endif */

    private SmoothieMapBuilder() {
        defaultOptimizationConfiguration();
    }

    /**
     * Specifies whether SmoothieMaps created using this builder should operate in the "low-garbage"
     * mode (if {@link OptimizationObjective#LOW_GARBAGE} is passed into this method) or the
     * "footprint" mode (if {@link OptimizationObjective#FOOTPRINT} is passed into this method). See
     * the documentation for these enum constants for more details about the modes.
     *
     * <p>By default, SmoothieMaps operate in the "mixed" mode which is a compromise between the
     * "low-garbage" and the "footprint" modes. Calling {@link #defaultOptimizationConfiguration()}
     * configures this mode explicitly.
     *
     * @param optimizationObjective the primary optimization objective for created SmoothieMaps
     * @return this builder back
     * @throws NullPointerException if the provided optimization objective is null
     * @see #defaultOptimizationConfiguration
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> optimizeFor(OptimizationObjective optimizationObjective) {
        Utils.checkNonNull(optimizationObjective);
        switch (optimizationObjective) {
            case LOW_GARBAGE:
                /* if Supported intermediateSegments */
                this.allocateIntermediateCapacitySegments = false;
                this.splitBetweenTwoNewSegments = false;
                /* endif */
                /* if Flag doShrink */
                this.doShrink = false;
                /* endif */
                break;
            case FOOTPRINT:
                /* if Supported intermediateSegments */
                this.allocateIntermediateCapacitySegments = true;
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
     * Specifies that SmoothieMaps created using this builder should operate in "mixed" mode which
     * is a compromise between {@link OptimizationObjective#FOOTPRINT} and {@link
     * OptimizationObjective#LOW_GARBAGE}.
     *
     * @implSpec the "mixed" mode includes {@linkplain
     * #allocateIntermediateCapacitySegments(boolean) allocating intermediate-capacity segments} and
     * {@linkplain #doShrink(boolean) turning SmoothieMap shrinking on}, but <i>doesn't</i> include
     * {@linkplain #splitBetweenTwoNewSegments(boolean) splitting segments between two new ones}.
     *
     * @return this builder back
     * @see #optimizeFor
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultOptimizationConfiguration() {
        /* if Supported intermediateSegments */
        this.allocateIntermediateCapacitySegments = true;
        this.splitBetweenTwoNewSegments = false;
        /* endif */
        /* if Flag doShrink */
        this.doShrink = true;
        /* endif */
        return this;
    }

    /* if Supported intermediateSegments */
    /**
     * Specifies whether during the growth of SmoothieMaps created with this builder they should
     * first allocate intermediate-capacity segments and then reallocate them as full-capacity
     * segments when needed, or allocate full-capacity segments right away.
     *
     * <p>SmoothieMap stores the entries in small <i>segments</i> which are mini hash tables on
     * their own. This hash table becomes full when the number of entries stored in it exceeds N.
     * At this point, a segment is <i>split into two parts</i>. At least one new segment should be
     * allocated upon a split to become the second part (or two new segments, if configured via
     * {@link #splitBetweenTwoNewSegments(boolean)}). The population of each of these two parts just
     * after the split is approximately N/2, subject to some variability (while their joint
     * population is N + 1). The entry storage capacity of segments is decoupled from the hash
     * table, so a newly allocated segment may have entry storage capacity smaller than N. A segment
     * of storage capacity of approximately (2/3) * N is called an <i>intermediate-capacity
     * segment</i>, and a segment  of storage capacity N is called a <i>full-capacity segment</i>.
     *
     * <p>Thus, upon a segment split, a newly allocated intermediate-capacity segment is on average
     * 75% full in terms of the entry storage capacity, while a newly allocated full-capacity
     * segment is on average 50% full. When the number of entries stored in an intermediate-capacity
     * segment grows beyond its storage capacity (approximately, (2/3) * N), it's <i>reallocated</i>
     * in place as a full-capacity segment.
     *
     * <p>Thus, intermediate-capacity segments reduce the total SmoothieMap's footprint per stored
     * entry as well as the variability of the footprint at different SmoothieMap's sizes. The
     * drawbacks of intermediate-capacity segments are:
     * <ul>
     *     <li>They are transient: being allocated and reclaimed during the growth of a SmoothieMap.
     *     The total amount of garbage produced by a SmoothieMap instance (assuming it grows from
     *     size 0 and neither {@link #expectedSize(long)} nor {@link #minPeakSize(long)} was
     *     configured) becomes comparable with the SmoothieMap's footprint, though still lower than
     *     the total amount of garbage produced by a typical open-addressing hash table
     *     implementation such as {@link java.util.IdentityHashMap}, and comparable to the the total
     *     amount of garbage produced by entry-based hash maps ({@link java.util.HashMap} and {@link
     *     java.util.concurrent.ConcurrentHashMap}) without pre-configured capacity and assuming
     *     that entries are not removed from them.</li>
     *     <li>Reallocation of intermediate-capacity segments into full-capacity segments
     *     contributes the amortized cost of inserting entries into a SmoothieMap.</li>
     *     <li>The mix of full-capacity and intermediate-capacity segments precludes certain
     *     variables to be hard-coded and generally adds some unpredictability (from the CPU
     *     perspective) to SmoothieMap's key lookup and insertion operations which might slower them
     *     relative to the setup where only full-capacity segments are used.</li>
     * </ul>
     *
     * <p>By default, intermediate-capacity segments <i>are</i> allocated as a part of {@linkplain
     * #defaultOptimizationConfiguration() the "mixed" mode (the default mode)} of SmoothieMap's
     * operation. Intermediate-capacity segments are allocated in the "mixed" and the {@linkplain
     * OptimizationObjective#FOOTPRINT "footprint"} modes, but are not allocated in the {@linkplain
     * OptimizationObjective#LOW_GARBAGE "low-garbage"} mode.
     *
     * <p>Disabling allocating intermediate-capacity segments (that is, passing {@code false} into
     * this method) also implicitly disables {@linkplain #splitBetweenTwoNewSegments(boolean)
     * splitting between two new segments} because it doesn't make sense to allocate two new
     * full-capacity segments and move entries there while we already have one full-capacity segment
     * at hand (the old one).
     *
     * @param allocateIntermediateCapacitySegments whether the created SmoothieMaps should allocate
     *        intermediate-capacity segments during the growth
     * @return this builder back
     * @see #splitBetweenTwoNewSegments(boolean)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> allocateIntermediateCapacitySegments(
            boolean allocateIntermediateCapacitySegments) {
        if (!allocateIntermediateCapacitySegments) {
            splitBetweenTwoNewSegments(false);
        }
        this.allocateIntermediateCapacitySegments = allocateIntermediateCapacitySegments;
        return this;
    }

    /**
     * Specifies whether when segments are split in SmoothieMaps created with this builder, the
     * entries from the segment being split should be moved into two new intermediate-capacity
     * segments or the entries should be distributed between the old segment one newly allocated
     * segment (full-capacity or intermediate-capacity).
     *
     * <p>See the description of the model of segments and the definitions of
     * <i>intermediate-capacity</i> and <i>full-capacity</i> segments in the documentation for
     * {@link #allocateIntermediateCapacitySegments(boolean)}. Moving entries from the old segment
     * into two newly allocated intermediate-capacity segments means that just after the split they
     * are jointly 75% full in terms of the entry storage capacity. Both newly allocated segments
     * may then be reallocated as full-capacity segments when needed. One full-capacity segment and
     * one newly allocated intermediate-capacity segment are jointly 60% full. Two full-capacity
     * segments, the old one and a newly allocated one (if the allocation of intermediate-capacity
     * segments is turned off) are jointly 50% full.
     *
     * <p>Thus, splitting between two newly allocated segments lowers the footprint per entry and
     * the footprint variability at different SmoothieMap's sizes as much as possible. The downside
     * of this strategy is that during SmoothieMap's growth, one full-capacity and two
     * intermediate-capacity segments are allocated and then dropped per every 2 * N entries (given
     * that a segment's hash table capacity is N) every time the map doubles in size. This is a
     * significant rate of heap memory churn, exceeding that of typical open-addressing hash table
     * implementations such as {@link java.util.IdentityHashMap} and entry-based hash maps: {@link
     * java.util.HashMap} or {@link java.util.concurrent.ConcurrentHashMap}.
     *
     * <p>By default, during a split, entries are distributed between the old (full-capacity)
     * segment and a newly allocated intermediate-capacity segment, as per {@linkplain
     * #defaultOptimizationConfiguration() the "mixed" mode (the default mode)} of SmoothieMap's
     * operation, that is, by default, splitting is <i>not</i> done between two newly allocated
     * segments. Splitting between two new segments is not done in the {@linkplain
     * OptimizationObjective#LOW_GARBAGE "low-garbage"} and the "mixed" modes. It's enabled only in
     * the {@linkplain OptimizationObjective#FOOTPRINT "footprint"} mode.
     *
     * <p>Enabling splitting between two new segments also implicitly enables {@linkplain
     * #allocateIntermediateCapacitySegments(boolean) allocation of intermediate-capacity segments}
     * because the former doesn't make sense without the latter.
     *
     * @param splitBetweenTwoNewSegments whether created SmoothieMaps should move entries from
     *        full-capacity segments into two newly allocated intermediate-capacity segments during
     *        growth
     * @return this builder back
     * @see #allocateIntermediateCapacitySegments(boolean)
     * @see OptimizationObjective#FOOTPRINT
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> splitBetweenTwoNewSegments(boolean splitBetweenTwoNewSegments) {
        if (splitBetweenTwoNewSegments) {
            allocateIntermediateCapacitySegments(true);
        }
        this.splitBetweenTwoNewSegments = splitBetweenTwoNewSegments;
        return this;
    }
    /* endif */

    /* if Flag doShrink */
    /**
     * Specifies whether SmoothieMaps created with this builder should automatically shrink, i. e.
     * reclaim the allocated heap space when they reduce in size.
     *
     * <p>By default, shrinking is turned on as a part of {@linkplain
     * #defaultOptimizationConfiguration() the "mixed" mode (the default mode)} of SmoothieMap's
     * operation. The "mixed" and the {@linkplain OptimizationObjective#FOOTPRINT "footprint"} modes
     * turn shrinking on, but the {@linkplain OptimizationObjective#LOW_GARBAGE "low-garbage"} mode
     * turns shrinking off.
     *
     * <p>Automatic shrinking creates some low-volume stream of allocations and reclamations of
     * heap memory objects when entries are dynamically put into and removed from the SmoothieMap
     * even if the number of entries in the map remains relatively stable during this process,
     * though at a much lower rate than garbage is produced by entry-based Map implementations such
     * as {@link java.util.HashMap}, {@link java.util.TreeMap}, or {@link
     * java.util.concurrent.ConcurrentHashMap} during a similar process.
     *
     * @param doShrink whether the created SmoothieMaps should automatically reclaim memory when
     *        they reduce in size
     * @return this builder back
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> doShrink(boolean doShrink) {
        this.doShrink = doShrink;
        return this;
    }
    /* endif */

    /**
     * Sets the {@link Equivalence} {@linkplain io.timeandspace.collect.map.ObjObjMap#keyEquivalence
     * used for comparing keys} in SmoothieMaps created with this builder to the given equivalence.
     *
     * @param keyEquivalence the key equivalence to use in created SmoothieMaps
     * @return this builder back
     * @throws NullPointerException if the given equivalence object is null
     * @see #defaultKeyEquivalence()
     * @see io.timeandspace.collect.map.ObjObjMap#keyEquivalence
     * @see #valueEquivalence(Equivalence)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> keyEquivalence(Equivalence<K> keyEquivalence) {
        checkNonNull(keyEquivalence);
        this.keyEquivalence = keyEquivalence;
        return this;
    }

    /**
     * Sets the {@link Equivalence} {@linkplain io.timeandspace.collect.map.ObjObjMap#keyEquivalence
     * used for comparing keys} in SmoothieMaps created with this builder to {@link
     * Equivalence#defaultEquality()}.
     *
     * @return this builder back
     * @see #keyEquivalence(Equivalence)
     * @see io.timeandspace.collect.map.ObjObjMap#keyEquivalence
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultKeyEquivalence() {
        this.keyEquivalence = Equivalence.defaultEquality();
        return this;
    }

    /**
     * Sets a key hash function to be used in {@linkplain SmoothieMap SmoothieMaps} created with
     * this builder.
     *
     * <p>The default key hash function derives 64-bit hash codes from the 32-bit result of calling
     * {@link Object#hashCode()} on the key objects (or {@link Equivalence#hash}, if {@link
     * #keyEquivalence(Equivalence)} is configured for the builder). This means that if the number
     * of entries in the SmoothieMap approaches or exceeds 2^32 (4 billion), a large number of
     * hash code collisions is inevitable. Therefore, it's recommended to always configure a custom
     * key hash function (using this method) for ultra-large SmoothieMaps.
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
     * @throws NullPointerException if the given hash function object is null
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
     * builder. Compared to {@link #keyHashFunction(ToLongFunction)}, this method allows inserting
     * variability or randomness into the hash function:
     * <pre>{@code
     * builder.keyHashFunctionFactory(() -> {
     *     LongHashFunction hashF = LongHashFunction.xx(ThreadLocalRandom.current().nextLong());
     *     return hashF::hashChars;
     * });}</pre>
     *
     * <p>Hash functions returned by the specified factory must be consistent with {@link
     * Object#equals} on the key objects, or a custom key equivalence if specified via {@link
     * #keyEquivalence(Equivalence)} in the same way as {@link Object#hashCode()} must be consistent
     * with {@code equals()}. This is the user's responsibility to ensure the consistency. When
     * {@link #keyEquivalence(Equivalence)} is called on a builder object, the key hash function
     * factory, if already configured to a non-default, is <i>not</i> reset. See the Javadoc comment
     * for {@link #keyHashFunction(ToLongFunction)} for more information.
     *
     * @param hashFunctionFactory the factory to create a key hash function for each {@code
     * SmoothieMap} created using this builder
     * @return this builder back
     * @throws NullPointerException if the given hash function factory object is null
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

    /**
     * Specifies that SmoothieMaps created with this builder should use the default key hash
     * function which derives a 64-bit hash code from the 32-bit result of calling {@link
     * Object#hashCode()} on the key object (or {@link Equivalence#hash}, if {@link
     * #keyEquivalence(Equivalence)} is configured for the builder).
     *
     * @return this builder back
     * @see #keyHashFunction(ToLongFunction)
     * @see #keyHashFunctionFactory(Supplier)
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultKeyHashFunction() {
        this.keyHashFunctionFactory = null;
        return this;
    }

    /**
     * Sets the {@link Equivalence} {@linkplain
     * io.timeandspace.collect.map.ObjObjMap#valueEquivalence used for comparing values} in
     * SmoothieMaps created with this builder to the given equivalence.
     *
     * @param valueEquivalence the value equivalence to use in created SmoothieMaps
     * @return this builder back
     * @throws NullPointerException if the given equivalence object is null
     * @see #defaultValueEquivalence()
     * @see io.timeandspace.collect.map.ObjObjMap#valueEquivalence()
     * @see #keyEquivalence(Equivalence)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> valueEquivalence(Equivalence<V> valueEquivalence) {
        checkNonNull(valueEquivalence);
        this.valueEquivalence = valueEquivalence;
        return this;
    }

    /**
     * Sets the {@link Equivalence} {@linkplain
     * io.timeandspace.collect.map.ObjObjMap#valueEquivalence used for comparing values} in
     * SmoothieMaps created with this builder to {@link Equivalence#defaultEquality()}.
     *
     * @return this builder back
     * @see #valueEquivalence(Equivalence)
     * @see io.timeandspace.collect.map.ObjObjMap#valueEquivalence()
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> defaultValueEquivalence() {
        this.valueEquivalence = Equivalence.defaultEquality();
        return this;
    }

    /**
     * Specifies the expected <i>steady-state</i> size of each {@link SmoothieMap} created using
     * this builder. The steady-state size is the map size after it's fully populated (when the map
     * is used in a simple populate-then-access pattern), or if entries are dynamically inserted
     * into and removed from the map, the steady-state size is the map size at which it should
     * eventually balance.
     *
     * <p>The default expected size is considered unknown. Calling {@link #unknownExpectedSize()}
     * after this method has been once called on the builder with a specific value overrides that
     * values and sets the expected size to be unknown again.
     *
     * <p>Calling this method is a performance hint for created SmoothieMap(s) and doesn't affect
     * the semantics of operations. The configured value must not be the exact steady-state size of
     * a created SmoothieMap, but it should be within 5% from the actual steady-state size. If the
     * steady-state size of created SmoothieMap(s) cannot be known with this level of precision,
     * configuring {@code expectedSize()} might make more harm than good and therefore shouldn't be
     * done. {@link #minPeakSize(long)} might be known with more confidence though and it's
     * recommended to configure it instead.
     *
     * <p>In theory, the {@linkplain #minPeakSize(long) minimum peak size} might be less than the
     * expected size by at most 5% (higher difference is not possible if the expected size is
     * configured according with the configuration above). If both expected size and minimum peak
     * size are specified and the latter is less than the former by more than 5% an {@link
     * IllegalStateException} is thrown when {@link #build()} is called.
     *
     * <p>Most often, the minimum peak size should be equal to the expected size (e. g. in the
     * populate-then-access usage pattern, there is no ground for differentiating the peak and the
     * expected sizes). Because of this, by default, if the expected size is configured for a
     * builder and the minimum peak size is not configured, the expected size is used as a
     * substitute for the minimum peak size.
     *
     * <p>When entries are dynamically put into and removed from a SmoothieMap, the minimum peak
     * size might also be greater than the expected size, if after reaching the peak size
     * SmoothieMaps are consistently expected to shrink. This might also be the case
     * when X entries are first inserted into a SmoothieMap and then some entries are removed until
     * the map reduces to some specific size Y smaller than X.
     *
     * @param expectedSize the expected steady-state size of created SmoothieMaps
     * @return this builder back
     * @throws IllegalArgumentException if the provided expected size is not positive
     * @see #unknownExpectedSize()
     * @see #minPeakSize(long)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> expectedSize(long expectedSize) {
        checkNonNegative(expectedSize, "expected size");
        this.expectedSize = expectedSize;
        return this;
    }

    /**
     * Specifies that the expected size of each {@link SmoothieMap} created using this builder is
     * unknown.
     *
     * @return this builder back
     * @see #expectedSize(long)
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> unknownExpectedSize() {
        this.expectedSize = UNKNOWN_SIZE;
        return this;
    }

    /**
     * Specifies the minimum bound of the <i>peak</i> size of each {@link SmoothieMap} created
     * using this builder. For example, it may be unknown what size the created SmoothieMap will
     * have after it is fully populated (or reaches "saturation" if entries are dynamically put into
     * and removed from the map): 1 million entries, or 2 million, or 3 million. But if it known
     * that under no circumstance the peak size of the map will be less than 1 million, then it
     * makes sense to configure this bound via this method.
     *
     * <p>The default minimum bound for the peak size of created SmoothieMaps is considered unknown,
     * or 0. However, to configure unknown minimum bound of the peak size (the override a specific
     * values which may have been once specified for the builder), {@link #unknownMinPeakSize()}
     * should be called and not this method.
     *
     * <p>Calling this method is a performance hint for created SmoothieMap(s) and doesn't affect
     * the semantics of operations. The configured value may be used to preallocate data structure
     * when a SmoothieMap is created, so it's better to err towards a value lower than the actual
     * peak size than towards a value higher than the actual peak size, especially when the
     * {@linkplain #optimizeFor optimization objective} is set to {@link
     * OptimizationObjective#LOW_GARBAGE}.
     *
     * <p>If the minimum peak size is not configured for a SmoothieMapBuilder, but {@linkplain
     * #expectedSize(long) expected size} is configured, the minimum peak size of created maps is
     * assumed to be equal to the expected size, although in some cases it may be about 5% less than
     * that even if the expected size is configured properly. If this is the case, configure the
     * minimum peak size along with the expected size explicitly. See the documentation for {@link
     * #expectedSize(long)} method for more information.
     *
     * @param minPeakSize the minimum bound for the peak size of created SmoothieMaps
     * @return this builder back
     * @throws IllegalArgumentException if the provided minimum peak size is not positive
     * @see #unknownMinPeakSize()
     * @see #expectedSize(long)
     */
    @CanIgnoreReturnValue
    @Contract("_ -> this")
    public SmoothieMapBuilder<K, V> minPeakSize(long minPeakSize) {
        checkNonNegative(minPeakSize, "minimum peak size");
        this.minPeakSize = minPeakSize;
        return this;
    }

    /**
     * Specifies that the minimum bound of the peak size of each {@link SmoothieMap} created using
     * this builder is unknown.
     *
     * @return this builder back
     * @see #minPeakSize(long)
     */
    @CanIgnoreReturnValue
    @Contract(" -> this")
    public SmoothieMapBuilder<K, V> unknownMinPeakSize() {
        this.minPeakSize = UNKNOWN_SIZE;
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
                maxProbabilityOfOccasionIfHashFunctionWasRandom > (float)
                        SmoothieMap.POOR_HASH_CODE_DISTRIB__BENIGN_OCCASION__MAX_PROB__MAX) {
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
    boolean allocateIntermediateCapacitySegments() {
        return allocateIntermediateCapacitySegments;
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

    private void checkSizes() {
        if (expectedSize == UNKNOWN_SIZE || minPeakSize == UNKNOWN_SIZE) {
            // Nothing to check
            return;
        }
        if (minPeakSize <
                (long) ((double) expectedSize * (1.0 - MAX_EXPECTED_SIZE_ERROR_FRACTION))) {
            throw new IllegalStateException("minPeakSize[" + minPeakSize + "] is less than " +
                    "expectedSize[" + expectedSize + "] * " +
                    (1.0 - MAX_EXPECTED_SIZE_ERROR_FRACTION));
        }
    }

    long expectedSize() {
        checkSizes();
        return expectedSize;
    }

    long minPeakSize() {
        checkSizes();
        return minPeakSize != UNKNOWN_SIZE ? minPeakSize : expectedSize;
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
