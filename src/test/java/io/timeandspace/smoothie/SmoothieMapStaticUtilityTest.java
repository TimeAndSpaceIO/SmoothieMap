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

import org.junit.jupiter.api.Test;

import static io.timeandspace.smoothie.SmoothieMap.MAX_SEGMENTS_ARRAY_ORDER;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.doComputeAverageSegmentOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmoothieMapStaticUtilityTest {

    @Test
    void testDoComputeAverageSegmentOrder() {
        for (long size = 1; size <= SEGMENT_MAX_ALLOC_CAPACITY; size++) {
            assertEquals(0, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 2; size++) {
            assertEquals(1, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY * 2 + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 4; size++) {
            assertEquals(2, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY * 4 + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 8; size++) {
            assertEquals(3, doComputeAverageSegmentOrder(size));
        }
        assertEquals(MAX_SEGMENTS_ARRAY_ORDER, doComputeAverageSegmentOrder(Long.MAX_VALUE));
    }
}
