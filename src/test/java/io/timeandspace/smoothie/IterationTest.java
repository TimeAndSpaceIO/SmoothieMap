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

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IterationTest {

    SmoothieMap<Integer, Integer> map = SmoothieMap.<Integer, Integer>newBuilder().build();
    HashSet<Integer> keys = new HashSet<>();
    Random random = new Random(53);

    @Before
    public void setup() {
        for (int i = 0; i < 1_000_000; i++) {
            int k = random.nextInt();
            map.put(k, k);
            keys.add(k);
        }
    }

    @Test
    public void testForEach() {
        int[] size = new int[1];
        map.forEach((k, v) -> {
            assertTrue(keys.contains(k));
            assertEquals(k, v);
            size[0]++;
        });
        assertEquals(keys.size(), size[0]);
    }

    @Test
    public void testEntrySetIteration() {
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            assertTrue(keys.contains(e.getKey()));
            assertEquals(e.getKey(), e.getValue());
        }
    }

    @Test
    public void testKeySetIterationWithRemove() {
        for (Iterator<Integer> iterator = map.keySet().iterator(); iterator.hasNext(); ) {
            iterator.next();
            iterator.remove();
        }
        assertEquals(0, map.size());
    }
}
