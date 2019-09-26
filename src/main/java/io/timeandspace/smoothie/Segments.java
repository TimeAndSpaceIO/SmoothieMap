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

import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SCALE_AS_LONG;

/**
 * Static utilities shared between {@link ContinuousSegments} and {@link InterleavedSegments}.
 */
final class Segments {

    /**
     * This method accepts allocOffset, rather than allocIndex so it should be called like
     * valueOffsetFromAllocOffset(allocOffset(allocIndex)). This is done to avoid expensive
     * translation from allocIndex to the address offset as done in {@link
     * InterleavedSegments#allocOffset}.
     *
     * @implNote the implementation of this method depends on how {@link
     * ContinuousSegments.SegmentBase#VALUE_OFFSET_BASE} is initialized with respect to {@link
     * ContinuousSegments.SegmentBase#KEY_OFFSET_BASE}.
     */
    static long valueOffsetFromAllocOffset(long allocOffset) {
        return allocOffset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
    }

    private Segments() {}
}
