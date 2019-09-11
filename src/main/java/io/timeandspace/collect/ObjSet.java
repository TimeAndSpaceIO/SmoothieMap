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

package io.timeandspace.collect;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

public interface ObjSet<E> extends Set<E>, ObjCollection<E> {

    /**
     * Returns the object held by this set internally and equivalent to the given object {@code e},
     * if there is one, or {@code null} if this set contains no such element (optional operation).
     *
     * <p>This method could be used to deduplicate objects in the application, to reduce the memory
     * footprint and make the application to conform to the "most objects die young" hypothesis that
     * most GC algorithms are optimized for. This method is functionally similar to {@link
     * String#intern()} and Guava's Interner, but allows to piggy-back a map data structure which
     * may already exist in the application.
     *
     * @param e the object whose equivalent held by this set internally is to be returned
     * @return the set-internal equivalent of the specified object, or {@code null} if the set
     * contains no such element
     * @throws UnsupportedOperationException if this set doesn't support returning an internal copy
     * of an element, e. g. because it's a "virtual" view of another collection
     */
    @Nullable E getInternal(E e);
}
