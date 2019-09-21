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
