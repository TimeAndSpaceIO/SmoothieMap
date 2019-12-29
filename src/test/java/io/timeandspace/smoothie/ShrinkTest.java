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

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ShrinkTest {
    @Test
    public void testShrink() {
        final SmoothieMap<Integer, Integer> map = SmoothieMap
                .<Integer, Integer>newBuilder().doShrink(true).build();

        Random r = new Random(1);
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            int key = r.nextInt();
            keys.add(key);
            map.put(key, key);
        }

        for (Integer key : keys) {
            map.remove(key);
        }
        final SmoothieMapStats stats = new SmoothieMapStats();
        map.aggregateStats(stats);
        Assert.assertEquals(1, stats.getNumAggregatedSegments());
    }
}
