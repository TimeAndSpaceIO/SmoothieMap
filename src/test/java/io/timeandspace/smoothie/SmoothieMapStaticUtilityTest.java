package io.timeandspace.smoothie;

import org.junit.Assert;
import org.junit.Test;

import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;
import static io.timeandspace.smoothie.SmoothieMap.MAX_SEGMENTS_ARRAY_ORDER;
import static io.timeandspace.smoothie.SmoothieMap.doComputeAverageSegmentOrder;

public class SmoothieMapStaticUtilityTest {

    @Test
    public void testDoComputeAverageSegmentOrder() {
        for (long size = 1; size <= SEGMENT_MAX_ALLOC_CAPACITY; size++) {
            Assert.assertEquals(0, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 2; size++) {
            Assert.assertEquals(1, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY * 2 + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 4; size++) {
            Assert.assertEquals(2, doComputeAverageSegmentOrder(size));
        }
        for (long size = SEGMENT_MAX_ALLOC_CAPACITY * 4 + 1;
             size <= SEGMENT_MAX_ALLOC_CAPACITY * 8; size++) {
            Assert.assertEquals(3, doComputeAverageSegmentOrder(size));
        }
        Assert.assertEquals(MAX_SEGMENTS_ARRAY_ORDER, doComputeAverageSegmentOrder(Long.MAX_VALUE));
    }
}
