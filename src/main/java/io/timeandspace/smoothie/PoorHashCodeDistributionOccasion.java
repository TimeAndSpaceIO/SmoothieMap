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

import io.timeandspace.collect.map.KeyValue;

import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.Utils.assertNonNull;

/**
 * Beware of storing {@code PoorHashCodeDistributionOccasion} objects in a queue that might not
 * be processed for long time, because {@code PoorHashCodeDistributionOccasion} objects hold a
 * reference to the SmoothieMap in which the poor hash code distribution occasion took place, that
 * might result in a memory leak.
 *
 * @param <K>
 * @param <V>
 */
public final class PoorHashCodeDistributionOccasion<K, V> {

    private final SmoothieMap<K, V> map;
    private final String message;
    private final Supplier<String> debugInformation;
    private final SmoothieMap.@Nullable InflatedSegment<K, V> segment;
    private final @Nullable K excludedKey;
    private boolean removedSomeElement = false;

    PoorHashCodeDistributionOccasion(SmoothieMap<K, V> map, String message,
            Supplier<String> debugInformation, SmoothieMap.@Nullable InflatedSegment<K, V> segment,
            @Nullable K excludedKey) {
        this.map = map;
        this.message = message;
        this.debugInformation = debugInformation;
        if (segment == null ^ excludedKey == null) {
            throw new AssertionError("segment and excludedKey must be both null or both non-null");
        }
        this.segment = segment;
        this.excludedKey = excludedKey;
    }

    public SmoothieMap<K, V> getMap() {
        return map;
    }

    public String getMessage() {
        return message;
    }

    public ToLongFunction<K> getHashFunction() {
        return map.getKeyHashFunction();
    }

    /**
     * Get the extended debug information. The cost of calling this method might be up to several
     * microseconds.
     */
    public String assembleDebugInformation() {
        return debugInformation.get();
    }

    public Optional<Iterable<KeyValue<K, V>>> getEntries() {
        if (segment == null) {
            return Optional.empty();
        }
        assertNonNull(excludedKey);
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
