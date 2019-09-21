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

import io.timeandspace.collect.Equivalence;
import io.timeandspace.collect.map.KeyValue;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.Utils.verifyNonNull;

/**
 * An event object representing a poor hash code distribution occasion in some SmoothieMap. It is
 * passed into a callback provided via {@link SmoothieMapBuilder#reportPoorHashCodeDistribution} for
 * monitoring.
 *
 * <p>It's possible to remove entries from the iterator of the iterable returned from {@link
 * #getEntries()} method (if present) to actively protect against malicious key flooding (hash DoS
 * attack).
 *
 * @param <K> the map key type
 * @param <V> the map value type
 * @see SmoothieMapBuilder#reportPoorHashCodeDistribution
 */
public final class PoorHashCodeDistributionOccasion<K, V> {

    enum Type {
        TOO_LARGE_INFLATED_SEGMENT,
        TOO_MANY_SKEWED_SEGMENT_SPLITS
    }

    private final Type type;
    private final SmoothieMap<K, V> map;
    private final String message;
    private final Supplier<Map<String, Object>> debugInformation;
    private final SmoothieMap.@Nullable InflatedSegment<K, V> segment;
    private final @Nullable K excludedKey;
    private boolean removedSomeElement = false;

    PoorHashCodeDistributionOccasion(Type type, SmoothieMap<K, V> map, String message,
            Supplier<Map<String, Object>> debugInformation,
            SmoothieMap.@Nullable InflatedSegment<K, V> segment, @Nullable K excludedKey) {
        this.type = type;
        this.map = map;
        this.message = message;
        this.debugInformation = debugInformation;
        if (segment == null ^ excludedKey == null) {
            throw new AssertionError("segment and excludedKey must be both null or both non-null");
        }
        this.segment = segment;
        this.excludedKey = excludedKey;
    }

    @VisibleForTesting
    Type getType() {
        return type;
    }

    /**
     * Returns the map in which the poor hash code distribution has occurred.
     */
    public SmoothieMap<K, V> getMap() {
        return map;
    }

    /**
     * Returns the main message characterizing this poor hash code distribution occasion.
     * @see #assembleDebugInformation()
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the hash function used in the SmoothieMap. Its low quality may be the reason for
     * the poor hash code distribution occasion.
     *
     * @see SmoothieMapBuilder#keyHashFunction(ToLongFunction)
     * @see SmoothieMapBuilder#keyHashFunctionFactory(Supplier)
     * @see SmoothieMapBuilder#keyEquivalence(Equivalence)
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // Public API; TODO call in tests
    public ToLongFunction<K> getHashFunction() {
        return map.getKeyHashFunction();
    }

    /**
     * Prepare extended debug information. The returned map object is suitable for conversion into
     * text via {@code toString()} and for JSON serialization.
     *
     * <p>The cost of calling this method may be up to several microseconds.
     *
     * @see #getMessage()
     */
    public Map<String, Object> assembleDebugInformation() {
        return debugInformation.get();
    }

    /**
     * If there are entries in the SmoothieMap which may be inserted maliciously (a hash DoS
     * attack), or just some specific (benign) entries which exemplify the poor hash code
     * distribution occasion, this method returns an optional with an iterable containing these
     * entries. This methods returns an empty optional if there are no such entries (which can be
     * easily pinpointed in the SmoothieMap without a full scan of it).
     *
     * <p>The iterable may contain no more than a few dozens of entries. The entries may be printed
     * for debugging purposes or additionally analyzed, e. g. {@link #getHashFunction() the hash
     * function} may be applied to the keys of these entries to see how exactly the hash codes are
     * poorly distributed.
     *
     * <p>The iterator of the returned iterable supports the {@link Iterator#remove()} operation
     * which removes the corresponding entries from the underlying SmoothieMap. This may be used for
     * active protection against hash DoS attacks, by pruning the potentially maliciously inserted
     * entries from the map to avoid the performance/efficiency problems. Note, however, that it may
     * be hard or impossible to ensure that certain entries are malicious rather than benign, so
     * it must be tolerable to occasionally remove some benign entries from the map.
     *
     * <p>When a {@code reportingAction} configured via {@link
     * SmoothieMapBuilder#reportPoorHashCodeDistribution} is called in the context of an operation
     * on a SmoothieMap which inserts a new entry, {@code getEntries()} iterable <i>doesn't</i>
     * include that entry even if it logically belongs to the same set of "malicious" entries. This
     * ensures that entry can't be removed from the SmoothieMap via {@link Iterator#remove()} on
     * these {@code getEntries()} before the insertion operation returned the control to the user.
     * That would break local "read-your-writes" consistency of a SmoothieMap which could be highly
     * confusing.
     */
    public Optional<Iterable<KeyValue<K, V>>> getEntries() {
        if (segment == null) {
            return Optional.empty();
        }
        verifyNonNull(excludedKey);
        Predicate<KeyValue<K, V>> filter = entry -> !map.keysEqual(excludedKey, entry.getKey());
        Iterable<KeyValue<K, V>> entries = () -> {
            Iterator<? extends KeyValue<K, V>> allEntries = segment.getEntries().iterator();
            return new FilteringIterator<KeyValue<K, V>>(allEntries, filter) {
                @Override
                public void remove() {
                    super.remove();
                    removedSomeElement = true;
                }
            };
        };
        return Optional.of(entries);
    }

    boolean removedSomeElement() {
        return removedSomeElement;
    }
}
