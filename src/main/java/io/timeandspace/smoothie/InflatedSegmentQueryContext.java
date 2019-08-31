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

import io.timeandspace.collect.Equivalence;
import io.timeandspace.collect.map.KeyValue;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;
import static io.timeandspace.smoothie.ObjectSize.objectSizeInBytes;
import static io.timeandspace.smoothie.Utils.checkNonNull;
import static io.timeandspace.smoothie.Utils.verifyNonNull;
import static io.timeandspace.smoothie.Utils.verifyThat;
import static io.timeandspace.smoothie.Utils.nonNullOrThrowCme;

/**
 * InflatedSegmentQueryContext is an object that is cached per a SmoothieMap (see {@link
 * SmoothieMap#inflatedSegmentQueryContext}) and includes the query-time state necessary to
 * implement {@link SmoothieMap.InflatedSegment}.
 *
 * An inflated segment is created instead of an ordinary segment (i. e. an ordinary segment is
 * "inflated") when the order of a segment should greater than the average segment order in the
 * map (which is usually equal to {@link SmoothieMap#segmentsArray}'s order, but not always; for
 * example, there may be a significant difference if the map used to be larger and has shrunk since)
 * plus {@link SmoothieMap#MAX_SEGMENT_ORDER_DIFFERENCE_FROM_AVERAGE}. The maximum probability of
 * this event, assuming perfectly well distributed hash codes and {@link
 * SmoothieMap.Segment#SEGMENT_MAX_ALLOC_CAPACITY} of 48, is the probability of having 49 or more
 * entries in a hash range area that contains 23.(9) entries on average,
 * Poisson(lambda = 23.(9) = 24, k >= 49) ~= 0.000005 (see also {@link
 * SmoothieMap#getMinReportedOutlierSegmentSize()}). I. e. it's expected that
 * approximately one segment in 200k is inflated. In other terms, a SmoothieMap of 5 to 10 millions
 * entries is expected to have one inflated segment. In practice, inflated segments could be much
 * more common, because hash codes are not distributed perfectly.
 *
 * Inflated segments serve three purposes:
 *  1. If hash codes are distributed well and inflated segments are very rare indeed, the
 *     alternative to inflating a segment would be doubling {@link SmoothieMap#segmentsArray} in
 *     {@link SmoothieMap#tryDoubleSegmentsArray()}. But doubling a segments array of tens or hundreds
 *     of thousands elements would cost much more memory (and would reduce the cache utilization of
 *     the whole SmoothieMap) than inflating a single segment.
 *
 *  2. If hash codes are distributed poorly, inflated segment makes probability checks on each entry
 *     insertion and reports a {@link PoorHashCodeDistributionOccasion} to the callback configured via
 *     {@link SmoothieMapBuilder#reportPoorHashCodeDistribution} when appropriate. It allows
 *     users to be notified about potential problems with the quality of the hash code function,
 *     instead of having a map that silently operates suboptimally.
 *
 *  3. If a SmoothieMap is under a hash DoS attack, i. e. there are hundreds or thousands of keys
 *     with colliding hash codes, inflated segment piggy-backs the HashMap's protection against such
 *     events (balanced tree bins), so that at least in terms of CPU, SmoothieMap doesn't degrade
 *     to linear access complexity. However, since inflated segment's HashMap has an indirection
 *     layer of {@link Node} (see below), under a hash DoS attack an inflated segment is slower and
 *     consumes more memory than a plain HashMap. The proper ways to protect a SmoothieMap against
 *     hash DoS attacks are configuring a randomly seeded hash function (see an example in {@link
 *     SmoothieMapBuilder#hashFunctionFactory} Javadoc), or removing malicious keys in a {@link
 *     PoorHashCodeDistributionOccasion} callback (see an example in {@link
 *     SmoothieMapBuilder#reportPoorHashCodeDistribution} Javadoc).
 *
 * Since inflated segments are normal (albeit rare) in big SmoothieMaps, SmoothieMap should still
 * uphold it's promises about throughput and allocating little or no garbage when inflated segments
 * are accessed. So even if the most frequently accessed key in a SmoothieMap happens to fall into
 * an inflated segment, accesses to that key shouldn't generate garbage. This is why node objects
 * for lookup are cached in {@link #cachedNode}, {@link #cachedComparableNode} and {@link
 * #cachedNodeWithCustomKeyEquivalence} fields, as well as capturing lambdas. Cached node objects
 * may not prevent allocation of garbage if that hot key is constantly being inserted into the map
 * and then removed from the map, but this seems to be a rather artificial use case.
 *
 * For the same reason, all operations with a single key on an inflated segment could only require
 * somewhat constantly more CPU than operations with a ordinary SmoothieMap's segment and could
 * access more memory locations, but could not be times or an order of magnitude slower. This is why
 * {@link Node} and {@link ComparableNode} objects are used in the first place (see below). If
 * some hot keys happen to fall into an inflated segment, the fact that accessing an inflated
 * segment is slower than accessing an ordinary segment and touches more memory locations will be
 * smoothed out by refreshing that memory locations in close CPU caches, so the average
 * SmoothieMap's throughput shouldn't be noticeably affected.
 *
 * Inflated segments use the "HashMap with keys mapping to themselves" pattern in order to be able
 * to implement {@link SmoothieMap#getInternalKey(Object)} without scanning all keys in the segment.
 * Effectively HashMap's entry points to another entry object, {@link Node}, that's a waste. But
 * this could be avoided only by reimplementing HashMap almost entirely, that would contribute
 * significantly to the bytecode size and the compiled code size of SmoothieMap, and could lead to
 * longer compilation pauses when InflatedSegmentQueryContext's methods are first called. Not to
 * mention increasing the overall complexity and bug-proneness of SmoothieMap, that is already very
 * high.
 */
final class InflatedSegmentQueryContext<K, V> {
    private static final long SIZE_IN_BYTES = classSizeInBytes(InflatedSegmentQueryContext.class);

    static final Object COMPUTE_IF_PRESENT_ENTRY_REMOVED = new Object();

    private final SmoothieMap<K, V> map;
    private final Equivalence<K> keyEquivalence;
    private final boolean useDefaultKeyEquivalence;
    private @MonotonicNonNull Class<?> primaryKeyClass;
    private boolean isPrimaryKeyClassComparable;

    private @Nullable Node<K, V> cachedNode;
    private @Nullable ComparableNode cachedComparableNode;
    private @Nullable NodeWithCustomKeyEquivalence<K, V> cachedNodeWithCustomKeyEquivalence;

    /**
     * Capturing lambdas are cached in order to avoid allocating garbage, see {@link
     * InflatedSegmentQueryContext} Javadoc.
     */
    private final ComputeIfPresentLambda<K, V> computeIfPresentLambda;
    private final RemoveOrReplaceLambda<K, V> removeOrReplaceLambda;
    private final ComputeIfAbsentLambda<K, V> computeIfAbsentLambda;
    private final ComputeLambda<K, V> computeLambda;
    private final MergeLambda<K, V> mergeLambda;

    InflatedSegmentQueryContext(SmoothieMap<K, V> map) {
        this.map = map;
        keyEquivalence = map.keyEquivalence();
        useDefaultKeyEquivalence = keyEquivalence.equals(Equivalence.defaultEquality());

        computeIfPresentLambda = new ComputeIfPresentLambda<>(map);
        removeOrReplaceLambda = new RemoveOrReplaceLambda<>(map);
        computeIfAbsentLambda = new ComputeIfAbsentLambda<>(this);
        computeLambda = new ComputeLambda<>(this);
        mergeLambda = new MergeLambda<>(this);
    }

    long sizeInBytes() {
        return SIZE_IN_BYTES +
                ((cachedNode != null) ? Node.SIZE_IN_BYTES : 0) +
                ((cachedComparableNode != null) ? ComparableNode.SIZE_IN_BYTES : 0) +
                ((cachedNodeWithCustomKeyEquivalence != null) ?
                        NodeWithCustomKeyEquivalence.SIZE_IN_BYTES : 0) +
                ComputeIfPresentLambda.SIZE_IN_BYTES +
                RemoveOrReplaceLambda.SIZE_IN_BYTES +
                ComputeIfAbsentLambda.SIZE_IN_BYTES +
                ComputeLambda.SIZE_IN_BYTES +
                MergeLambda.SIZE_IN_BYTES;
    }

    @EnsuresNonNull("primaryKeyClass")
    private void initPrimaryKeyClass(Object key) {
        primaryKeyClass = key.getClass();
        boolean primaryKeyClassIsString = primaryKeyClass == String.class;
        // Fast path. Likelihood of this branch depends on the application using SmoothieMap.
        if (primaryKeyClassIsString) {
            isPrimaryKeyClassComparable = true;
        } else {
            isPrimaryKeyClassComparable = ComparableClassValue.INSTANCE.get(primaryKeyClass);
        }
    }

    Node<K, V> getNodeForKey(Object key, long hash) {
        @MonotonicNonNull Node<K, V> node;
        if (useDefaultKeyEquivalence) { // [Positive likely branch]
            if (primaryKeyClass == null) {
                // Inits isPrimaryKeyClassComparable field too.
                initPrimaryKeyClass(key);
            }
            boolean keyIsOfPrimaryKeyClass = primaryKeyClass == key.getClass();
            // Comparable qualification check: this condition ensures that mutually incomparable
            // keys are not wrapped as ComparableNode objects, that could lead to
            // ClassCastException in ComparableNode.compareTo() when a HashMap's bin is treeified.
            if (keyIsOfPrimaryKeyClass && isPrimaryKeyClassComparable) {
                //noinspection unchecked
                node = cachedComparableNode;
                if (node == null) {
                    ComparableNode comparableNode = new ComparableNode();
                    //noinspection unchecked
                    node = comparableNode;
                    cachedComparableNode = comparableNode;
                }
            } else {
                node = cachedNode;
                if (node == null) {
                    node = new Node<>();
                    cachedNode = node;
                }
            }

        } else {
            node = cachedNodeWithCustomKeyEquivalence;
            if (node == null) {
                NodeWithCustomKeyEquivalence<K, V> nodeWithCustomKeyEquivalence =
                        new NodeWithCustomKeyEquivalence<>(keyEquivalence);
                node = nodeWithCustomKeyEquivalence;
                cachedNodeWithCustomKeyEquivalence = nodeWithCustomKeyEquivalence;
            }
        }
        //noinspection unchecked
        node.key = (K) key;
        node.hash = hash;
        return node;
    }

    /** Identity comparisons are intended in this method */
    @SuppressWarnings({"ObjectEquality", "ReferenceEquality"})
    private void dropInternedNodeFromCache(Node<K, V> node) {
        if (cachedNode == node) {
            cachedNode = null;
        } else if (cachedComparableNode == node) {
            cachedComparableNode = null;
        } else {
            cachedNodeWithCustomKeyEquivalence = null;
        }
    }

    @Nullable V get(HashMap<Node<K, V>, Node<K, V>> delegate, Object key, long hash) {
        Node<K, V> node = getNodeForKey(key, hash);
        try {
            Node<K, V> internalNode = delegate.get(node);
            return internalNode != null ? nonNullOrThrowCme(internalNode.value) : null;
        } finally {
            node.key = null;
        }
    }

    @Nullable K getInternalKey(HashMap<Node<K, V>, Node<K, V>> delegate, Object key, long hash) {
        Node<K, V> node = getNodeForKey(key, hash);
        try {
            Node<K, V> internalNode = delegate.get(node);
            return internalNode != null ? nonNullOrThrowCme(internalNode.key) : null;
        } finally {
            node.key = null;
        }
    }

    /**
     * Returns the new computed value, or null if there were no value corresponding to the key
     * stored in the delegate map so the remapping function hasn't been executed, or {@link
     * #COMPUTE_IF_PRESENT_ENTRY_REMOVED} if the remapping function returned null and therefore an
     * entry has been removed from the delegate map.
     *
     * This method has to have such rather unusual contract to allow the caller ({@link
     * SmoothieMap.InflatedSegment}) to distinguish between null result
     * when the delegate has not been updated structurally and null result when an entry was removed
     * from the delegate and therefore it's possible to do segment structure modification: see
     * [Segment structure modification only after entry structure modification].
     */
    @Nullable Object computeIfPresent(HashMap<Node<K, V>, Node<K, V>> delegate, K key,
            long hash, BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
        Node<K, V> node = getNodeForKey(key, hash);
        ComputeIfPresentLambda<K, V> computeIfPresentLambda = this.computeIfPresentLambda;
        computeIfPresentLambda.key = key;
        computeIfPresentLambda.remappingFunction = remappingFunction;
        // This check doesn't guarantee against concurrent anomalies. It's put here more to clarify
        // the expectations rather than to protect against concurrent modifications.
        if (computeIfPresentLambda.newValueOrEntryRemoved != null) {
            throw new ConcurrentModificationException();
        }
        try {
            delegate.computeIfPresent(node, computeIfPresentLambda);
            // If there is no value corresponding to the key stored in the delegate map,
            // ComputeIfPresentLambda.apply() won't be entered and newValueOrEntryRemoved should
            // remain null as it is expected to be before the delegate.computeIfPresent() call.
            return computeIfPresentLambda.newValueOrEntryRemoved;
        } finally {
            computeIfPresentLambda.clear();
            node.key = null;
        }
    }

    private static class ComputeIfPresentLambda<K, V>
            implements BiFunction<Node<K, V>, Node<K, V>, Node<K, V>> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(ComputeIfPresentLambda.class);

        private final SmoothieMap<K, V> map;
        /**
         * Capturing the key (although it is also available via internalNode) for consistency with
         * {@link HashMap#computeIfPresent} and {@link SmoothieMap#computeIfPresent} that apply the
         * remapping function to the queried, not the internal map key.
         */
        private @Nullable K key;
        private @Nullable BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction;
        private @Nullable Object newValueOrEntryRemoved;

        private ComputeIfPresentLambda(SmoothieMap<K, V> map) {
            this.map = map;
        }

        @Override
        public @Nullable Node<K, V> apply(Node<K, V> ignored, Node<K, V> internalNode) {
            SmoothieMap<K, V> map = this.map;
            BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction =
                    nonNullOrThrowCme(this.remappingFunction);
            K key = nonNullOrThrowCme(this.key);
            V internalVal = nonNullOrThrowCme(internalNode.value);
            int modCount = map.getModCountOpaque();
            @Nullable V newValue = remappingFunction.apply(key, internalVal);
            map.checkModCountOrThrowCme(modCount);
            if (newValue != null) {
                internalNode.value = newValue;
                this.newValueOrEntryRemoved = newValue;
                // Don't increment SmoothieMap's modCount on non-structural modification: to adhere
                // to the HashMap's contract which, unfortunately, permits code with non-structural
                // modifications within an iteration loop over the same map. See also
                // [Segment structure modification only after entry structure modification].
                return internalNode;
            } else {
                map.decrementSize();
                newValueOrEntryRemoved = COMPUTE_IF_PRESENT_ENTRY_REMOVED;
                return null;
            }
        }

        void clear() {
            key = null;
            remappingFunction = null;
            newValueOrEntryRemoved = null;
        }
    }

    @Nullable V remove(HashMap<Node<K, V>, Node<K, V>> delegate, K key, long hash) {
        Node<K, V> node = getNodeForKey(key, hash);
        try {
            Node<K, V> internalNode = delegate.remove(node);
            if (internalNode != null) {
                map.decrementSize();
                return nonNullOrThrowCme(internalNode.value);
            } else {
                return null;
            }
        } finally {
            node.key = null;
        }
    }

    @Nullable V replace(HashMap<Node<K, V>, Node<K, V>> delegate, K key, long hash, V value) {
        Node<K, V> node = getNodeForKey(key, hash);
        try {
            Node<K, V> internalNode = delegate.get(node);
            if (internalNode != null) {
                V internalVal = nonNullOrThrowCme(internalNode.value);
                internalNode.value = value;
                // [Don't increment SmoothieMap's modCount on non-structural modification]
                return internalVal;
            } else {
                return null;
            }
        } finally {
            node.key = null;
        }
    }

    /**
     * A boolean is returned from this method rather than the internal value removed or replaced in
     * the map because it incurs less object field reads and writes in the {@link
     * RemoveOrReplaceLambda}, that is better (?) when a GC algorithm with heavy barriers is used.
     *
     * This decision affects the contracts of {@link SmoothieMap#removeImpl}, {@link
     * SmoothieMap#replaceImpl}, and {@link SmoothieMap#removeOrReplaceInflated} internal methods,
     * but it doesn't affect the behaviour of public SmoothieMap's methods.
     */
    boolean removeOrReplaceEntry(HashMap<Node<K, V>, Node<K, V>> delegate,
            K key, long hash, Object matchValue, @Nullable V replacementValue) {
        Node<K, V> node = getNodeForKey(key, hash);
        RemoveOrReplaceLambda<K, V> removeOrReplaceLambda = this.removeOrReplaceLambda;
        removeOrReplaceLambda.matchValue = matchValue;
        removeOrReplaceLambda.replacementValue = replacementValue;
        try {
            delegate.computeIfPresent(node, removeOrReplaceLambda);
            return removeOrReplaceLambda.removedOrReplaced;
        } finally {
            removeOrReplaceLambda.clear();
            node.key = null;
        }
    }

    private static class RemoveOrReplaceLambda<K, V>
            implements BiFunction<Node<K, V>, Node<K, V>, Node<K, V>> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(RemoveOrReplaceLambda.class);

        private final SmoothieMap<K, V> map;
        @Nullable Object matchValue;
        @Nullable V replacementValue;
        boolean removedOrReplaced;

        private RemoveOrReplaceLambda(SmoothieMap<K, V> map) {
            this.map = map;
        }

        @Override
        public @Nullable Node<K, V> apply(Node<K, V> ignored, Node<K, V> internalNode) {
            Object matchValue = nonNullOrThrowCme(this.matchValue);
            V internalVal = nonNullOrThrowCme(internalNode.value);
            //noinspection ObjectEquality: identity comparision is intended
            boolean valuesIdentical = internalVal == matchValue;
            if (valuesIdentical || map.valuesEqual(matchValue, internalVal)) {
                removedOrReplaced = true;
                if (replacementValue == null) {
                    map.decrementSize();
                    return null;
                } else {
                    internalNode.value = replacementValue;
                    // [Don't increment SmoothieMap's modCount on non-structural modification]
                    return internalNode;
                }
            } else {
                removedOrReplaced = false;
                return internalNode;
            }
        }

        private void clear() {
            matchValue = null;
            replacementValue = null;
        }
    }

    @Nullable V put(HashMap<Node<K, V>, Node<K, V>> delegate,
            K key, long hash, V value, boolean onlyIfAbsent) {
        Node<K, V> node = getNodeForKey(key, hash);
        @Nullable Node<K, V> internalNode;
        try {
            internalNode = delegate.putIfAbsent(node, node);
        } catch (Throwable t) {
            // Cleanup to help GC if the delegate HashMap has thrown an exception.
            node.key = null;
            throw t;
        }
        if (internalNode != null) {
            // Cleaning up the node before potentially throwing a ConcurrentModificationException
            // in the next statement to help GC.
            node.key = null;
            V internalVal = nonNullOrThrowCme(internalNode.value);
            if (!onlyIfAbsent) {
                internalNode.value = value;
                // [Don't increment SmoothieMap's modCount on non-structural modification]
            }
            return internalVal;
        } else {
            map.incrementSize();
            node.value = value;
            dropInternedNodeFromCache(node);
            return null;
        }
    }

    /**
     * The main difference from {@link #put} is that this method doesn't increment {@link
     * SmoothieMap#size} and {@link SmoothieMap#modCount}.
     */
    void putDuringInflation(HashMap<Node<K, V>, Node<K, V>> delegate, K key, long hash, V value) {
        Node<K, V> node = getNodeForKey(key, hash);
        @Nullable Node<K, V> internalNode;
        try {
            internalNode = delegate.putIfAbsent(node, node);
        } catch (Throwable t) {
            // Cleanup to help GC if the delegate HashMap has thrown an exception.
            node.key = null;
            throw t;
        }
        if (internalNode == null) { // [Positive likely branch]
            node.value = value;
            dropInternedNodeFromCache(node);
        } else {
            // Cleanup to help GC before throwing an exception.
            node.key = null;
            // During inflation, every node is expected to be new, not present in the delegate map.
            // If there is already an node for the key in the delegate map there should be
            // concurrent modification going on.
            throw new ConcurrentModificationException();
        }
    }

    /**
     * Returns the node which is stored in the delegate map as the result of this computeIfAbsent()
     * call. If the returned node is identical to the passed nodeWithKeyAndHash, it means that an
     * entry was inserted into the delegate map. The caller of this method ({@link
     * SmoothieMap.InflatedSegment}) uses this information to decide
     * whether it's possible to make segment structure modifications or not: see
     * [Segment structure modification only after entry structure modification].
     *
     * When this method returns null, it means that mappingFunction returned null and hence an entry
     * wasn't stored in the delegate map.
     *
     * @param nodeWithKeyAndHash must be obtained by calling {@link #getNodeForKey}.
     *        computeIfAbsent() takes full responsibility for cleaning up the reference to the
     *        queried key from this Node if this Node is not interned into the delegate map for
     *        whatever reason (a Node with the same key already exists in the delegate map, or
     *        mappingFunction returned null, or an exception was thrown), so the caller of
     *        computeIfAbsent() shouldn't take any extra action to clean up nodeWithKeyAndHash.
     */
    @Nullable Node<K, V> computeIfAbsent(HashMap<Node<K, V>, Node<K, V>> delegate,
            Node<K, V> nodeWithKeyAndHash,
            Function<? super K, ? extends @Nullable V> mappingFunction) {
        ComputeIfAbsentLambda<K, V> computeIfAbsentLambda = this.computeIfAbsentLambda;
        computeIfAbsentLambda.mappingFunction = mappingFunction;
        try {
            @Nullable Node<K, V> internalNode =
                    delegate.computeIfAbsent(nodeWithKeyAndHash, computeIfAbsentLambda);
            return internalNode;
        } catch (Throwable t) {
            // nodeWithKeyAndHash cleanup upon exception: cleanup to help GC if the delegate HashMap
            // has thrown an exception or we threw a ConcurrentModificationException inside
            // ComputeIfAbsentLambda. According to the HashMap's contract, an entry is not stored
            // in the delegate map in the latter case, so we can rightfully cleanup
            // nodeWithKeyAndHash.
            // Simple node cleaning: nodeWithKeyAndHash.value may not be written in
            // ComputeIfAbsentLambda.apply() before the exception is thrown, so cleaning up the
            // value might be excessive. However, it's not important here to avoid extra
            // writes (which may make difference for some GC algorithms) by doing a conditional
            // write because we are already on the exceptional path and don't care about the
            // performance anymore.
            nodeWithKeyAndHash.value = null;
            nodeWithKeyAndHash.key = null;
            throw t;
        } finally {
            computeIfAbsentLambda.mappingFunction = null;
        }
    }

    private static class ComputeIfAbsentLambda<K, V> implements Function<Node<K, V>, Node<K, V>> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(ComputeIfAbsentLambda.class);

        private final InflatedSegmentQueryContext<K, V> context;
        @Nullable Function<? super K, ? extends @Nullable V> mappingFunction;

        private ComputeIfAbsentLambda(InflatedSegmentQueryContext<K, V> context) {
            this.context = context;
        }

        @Override
        public @Nullable Node<K, V> apply(Node<K, V> node) {
            InflatedSegmentQueryContext<K, V> context = this.context;
            SmoothieMap<K, V> map = context.map;
            Function<? super K, ? extends @Nullable V> mappingFunction =
                    nonNullOrThrowCme(this.mappingFunction);
            K key = nonNullOrThrowCme(node.key);
            int modCount = map.getModCountOpaque();
            @Nullable V value = mappingFunction.apply(key);
            map.checkModCountOrThrowCme(modCount);
            if (value != null) {
                map.incrementSize();
                node.value = value;
                context.dropInternedNodeFromCache(node);
                return node;
            } else {
                node.key = null;
                return null;
            }
        }
    }

    /**
     * Returns the node which is stored in the delegate map as the result of this compute() call. If
     * the returned node is identical to the passed nodeWithKeyAndHash, it means that an entry was
     * inserted into the delegate map. The caller of this method ({@link
     * SmoothieMap.InflatedSegment}) uses this information to decide
     * whether it's possible to make segment structure modifications or not: see
     * [Segment structure modification only after entry structure modification].
     *
     * When this method returns null, it means that remappingFunction returned null and hence an
     * entry wasn't stored in the delegate map (if there was no entry corresponding for the key in
     * the delegate map before this compute() call), or that an entry was removed from the delegate
     * map. These two situations should be distinguished (to decide on the legitimacy of segment
     * structure modification, again) by probing nodeWithKeyAndHash.key: if it's null after a
     * compute() call that returned null then there was no entry structure modification to the
     * delegate map. If nodeWithKeyAndHash.key is non-null, then an entry was removed from the
     * delegate map as the result of the compute() call. In the latter case, nodeWithKeyAndHash.key
     * must be additionally written to null.
     *
     * @param nodeWithKeyAndHash must be obtained by calling {@link #getNodeForKey}. compute() takes
     *        responsibility for cleaning up the reference to the queried key from this Node if this
     *        Node is not interned into the delegate map for any reason (including if an exception
     *        was thrown), except when an entry was removed from the delegate map because
     *        remappingFunction returned null. In this case, the caller of compute() must
     *        write null to nodeWithKeyAndHash.key itself.
     *
     * @apiNote compute() has a rather complex and fragile contract with facts communicated
     * non-semantically via the reference value of nodeWithKeyAndHash.getKey() after the compute()
     * call returns. An alternative to this is creating a structure like ComputeResult with value
     * and delegateMapWasStructurallyModified fields ({@link ComputeLambda} may extend that
     * structure, so there is no separate object passed around). However, that approach has its own
     * complexities, and incurs more field writes (including an object field ComputeResult.value)
     * than the approach with non-semantic facts communication.
     */
    @Nullable Node<K, V> compute(HashMap<Node<K, V>, Node<K, V>> delegate,
            Node<K, V> nodeWithKeyAndHash,
            BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
        ComputeLambda<K, V> computeLambda = this.computeLambda;
        computeLambda.remappingFunction = remappingFunction;
        try {
            @Nullable Node<K, V> internalNode = delegate.compute(nodeWithKeyAndHash, computeLambda);
            return internalNode;
        } catch (Throwable t) {
            // [nodeWithKeyAndHash cleanup upon exception]
            // [Simple node cleaning]
            nodeWithKeyAndHash.value = null;
            nodeWithKeyAndHash.key = null;
            throw t;
        } finally {
            computeLambda.remappingFunction = null;
        }
    }

    private static class ComputeLambda<K, V>
            implements BiFunction<Node<K, V>, Node<K, V>, Node<K, V>> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(ComputeLambda.class);

        private final InflatedSegmentQueryContext<K, V> context;
        @Nullable
        BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction;

        private ComputeLambda(InflatedSegmentQueryContext<K, V> context) {
            this.context = context;
        }

        @Override
        public @Nullable Node<K, V> apply(Node<K, V> node, @Nullable Node<K, V> internalNode) {
            InflatedSegmentQueryContext<K, V> context = this.context;
            SmoothieMap<K, V> map = context.map;
            BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction =
                    nonNullOrThrowCme(this.remappingFunction);
            // It's important to use node.key, not internalNode.key even when internalNode !=
            // null for consistency with HashMap's and SmoothieMap's compute() that apply the
            // remapping function to the queried, not the internal key.
            K key = nonNullOrThrowCme(node.key);
            if (internalNode != null) {
                V internalVal = nonNullOrThrowCme(internalNode.value);
                int modCount = map.getModCountOpaque();
                @Nullable V newValue = remappingFunction.apply(key, internalVal);
                map.checkModCountOrThrowCme(modCount);
                if (newValue != null) {
                    node.key = null;
                    // [Don't increment SmoothieMap's modCount on non-structural modification]
                    internalNode.value = newValue;
                    return internalNode;
                } else {
                    // Don't clear node.key, unlike in (*) below in this method, to satisfy to the
                    // contract of InflatedSegmentQueryContext.compute(): see the Javadoc comment to
                    // the method. Cleaning node.key becomes the responsibility of the caller
                    // (InflatedSegment) in this case.

                    map.decrementSize();
                    return null;
                }
            } else {
                int modCount = map.getModCountOpaque();
                @Nullable V value = remappingFunction.apply(key, null);
                map.checkModCountOrThrowCme(modCount);
                if (value != null) {
                    map.incrementSize();
                    node.value = value;
                    context.dropInternedNodeFromCache(node);
                    return node;
                } else {
                    // (*)
                    node.key = null;
                    return null;
                }
            }
        }
    }

    /**
     * Returns the node which is stored in the delegate map as the result of this merge() call. If
     * the returned node is identical to the passed nodeWithKeyAndHash, it means that an entry was
     * inserted into the delegate map. The caller of this method ({@link
     * SmoothieMap.InflatedSegment}) uses this information to decide
     * whether it's possible to make segment structure modifications or not: see
     * [Segment structure modification only after entry structure modification].
     *
     * When this method returns null, it means that remappingFunction returned null and hence an
     * entry was removed from the delegate map, so segment structure modification is also possible.
     *
     * @param nodeWithKeyAndHash must be obtained by calling {@link #getNodeForKey}.
     *        computeIfAbsent() takes full responsibility for cleaning up the reference to the
     *        queried key from this Node if this Node is not interned into the delegate map for
     *        whatever reason (a Node with the same key already exists in the delegate map, or
     *        remappingFunction returned null, or an exception was thrown), so the caller of merge()
     *        shouldn't take any extra action to clean up nodeWithKeyAndHash.
     */
    @Nullable Node<K, V> merge(HashMap<Node<K, V>, Node<K, V>> delegate,
            Node<K, V> nodeWithKeyAndHash, V value,
            BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
        nodeWithKeyAndHash.value = value;
        MergeLambda<K, V> mergeLambda = this.mergeLambda;
        mergeLambda.remappingFunction = remappingFunction;
        try {
            @Nullable Node<K, V> internalNode = delegate.compute(nodeWithKeyAndHash, mergeLambda);
            return internalNode;
        } catch (Throwable t) {
            // [nodeWithKeyAndHash cleanup upon exception]
            // [Simple node cleaning]
            nodeWithKeyAndHash.value = null;
            nodeWithKeyAndHash.key = null;
            throw t;
        } finally {
            mergeLambda.remappingFunction = null;
        }
    }

    private static class MergeLambda<K, V>
            implements BiFunction<Node<K, V>, Node<K, V>, Node<K, V>> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(MergeLambda.class);

        private final InflatedSegmentQueryContext<K, V> context;
        @Nullable BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction;

        private MergeLambda(InflatedSegmentQueryContext<K, V> context) {
            this.context = context;
        }

        @Override
        public @Nullable Node<K, V> apply(Node<K, V> node, @Nullable Node<K, V> internalNode) {
            InflatedSegmentQueryContext<K, V> context = this.context;
            SmoothieMap<K, V> map = context.map;
            if (internalNode != null) {
                BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction =
                        nonNullOrThrowCme(this.remappingFunction);
                V internalVal = nonNullOrThrowCme(internalNode.value);
                V value = nonNullOrThrowCme(node.value);
                // Not caring about cleaning up the node before potentially throwing a
                // ConcurrentModificationException in one of the statements above (unlike in put())
                // because it will be done in [nodeWithKeyAndHash cleanup upon exception] in merge()
                // as well.
                node.key = null;
                node.value = null;
                int modCount = map.getModCountOpaque();
                @Nullable V newValue = remappingFunction.apply(internalVal, value);
                map.checkModCountOrThrowCme(modCount);
                if (newValue != null) {
                    internalNode.value = newValue;
                    // [Don't increment SmoothieMap's modCount on non-structural modification]
                    return internalNode;
                } else {
                    map.decrementSize();
                    return null;
                }
            } else {
                map.incrementSize();
                context.dropInternedNodeFromCache(node);
                return node;
            }
        }
    }

    static class Node<K, V> implements KeyValue<K, V> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(Node.class);

        long hash;
        private @Nullable K key;
        private @Nullable V value;

        final boolean clearKeyIfNonNull() {
            if (key != null) {
                key = null;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public final K getKey() {
            return nonNullOrThrowCme(key);
        }

        @Override
        public final V getValue() {
            return nonNullOrThrowCme(value);
        }

        void setValue(V newValue) {
            checkNonNull(newValue);
            this.value = newValue;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            verifyNonNull(obj);
            Class<?> objClass = obj.getClass();
            // Comparing Node with other types of objects or null should be a error in
            // InflatedSegmentQueryContext's logic.
            verifyThat(objClass == Node.class || objClass == ComparableNode.class);
            K thisKey = nonNullOrThrowCme(key);
            @SuppressWarnings("unchecked")
            K otherKey = nonNullOrThrowCme(((Node<K, ?>) obj).key);
            return thisKey.equals(otherKey);
        }

        @Override
        public final int hashCode() {
            // Note: not using SmoothieMap.longKeyHashCodeToIntHashCode() because Node's hash code
            // is not the same thing as key's hash code, semantically, and in theory could be
            // implemented differently.
            return Long.hashCode(hash);
        }
    }

    /**
     * This class should not be generic to qualify as comparable inside HashMap (see
     * HashMap.comparableClassFor() in OpenJDK 8-11; {@link ComparableClassValue} does the same
     * check). HashMap doesn't compare keys of the form "Key<T> implements Comparable<Key<T>>",
     * because since generics are erased in runtime it's possible to provide keys with different
     * generic arguments that could lead to ClassCastException. InflatedSegmentQueryContext protects
     * from this in {@link #getNodeForKey}, see [Comparable qualification check].
     */
    private static class ComparableNode extends Node implements Comparable<ComparableNode> {
        private static final long SIZE_IN_BYTES = classSizeInBytes(ComparableNode.class);

        @Override
        public int compareTo(ComparableNode other) {
            Comparable thisKey = (Comparable) getKey();
            Comparable otherKey = (Comparable) other.getKey();
            //noinspection unchecked
            return thisKey.compareTo(otherKey);
        }
    }

    /**
     * This class could be paired with a comparable subclass just like {@link Node} is
     * paired with {@link ComparableNode}, but a SmoothieMap user could benefit from that
     * only if provides a custom {@link java.util.Comparator} for keys along with custom {@link
     * Equivalence} in {@link SmoothieMapBuilder}, that would increase the complexity of the
     * {@link SmoothieMapBuilder} interface and hardly anybody would ever do it. A better
     * investment of user's time is developing a seeded hash function with good mixing properties.
     * Using such function makes it virtually impossible that long collision chains appear in
     * an inflated segment's HashMap, therefore tree bins and key comparisons are not needed in the
     * first place.
     */
    private static class NodeWithCustomKeyEquivalence<K, V> extends Node<K, V> {
        private static final long SIZE_IN_BYTES =
                classSizeInBytes(NodeWithCustomKeyEquivalence.class);

        private final Equivalence<K> equivalence;

        NodeWithCustomKeyEquivalence(Equivalence<K> equivalence) {
            this.equivalence = equivalence;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof NodeWithCustomKeyEquivalence)) {
                // Comparing NodeWithCustomKeyEquivalence with other types of objects should be
                // a error in InflatedSegmentQueryContext's logic.
                throw new AssertionError();
            }
            K thisKey = getKey();
            @SuppressWarnings("unchecked")
            K otherKey = ((NodeWithCustomKeyEquivalence<K, ?>) obj).getKey();
            return equivalence.equivalent(thisKey, otherKey);
        }
    }
}
