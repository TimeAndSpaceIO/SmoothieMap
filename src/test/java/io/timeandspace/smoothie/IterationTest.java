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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IterationTest {

    private static final Map<Integer, Integer> map =
            SmoothieMap.<Integer, Integer>newBuilder().build().asMapWithMutableIterators();
    private static final HashSet<Integer> keys = new HashSet<>();
    private static final Random random = new Random(53);

    @BeforeAll
    static void setup() {
        for (int i = 0; i < 1_000_000; i++) {
            int k = random.nextInt();
            map.put(k, k);
            keys.add(k);
        }
    }

    @Test
    void testForEach() {
        int[] size = new int[1];
        map.forEach((k, v) -> {
            assertTrue(keys.contains(k));
            assertEquals(k, v);
            size[0]++;
        });
        assertEquals(keys.size(), size[0]);
    }

    @Test
    void testEntrySetIteration() {
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            assertTrue(keys.contains(e.getKey()));
            assertEquals(e.getKey(), e.getValue());
        }
    }

    @Test
    void testKeySetIterationWithRemove() {
        for (Iterator<Integer> iterator = map.keySet().iterator(); iterator.hasNext(); ) {
            iterator.next();
            iterator.remove();
        }
        assertEquals(0, map.size());
    }
}
