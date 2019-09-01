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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GrowTest {

    private SmoothieMap<Integer, Integer> map =
            SmoothieMap.<Integer, Integer>newBuilder().allocateIntermediateSegments(true).build();
    private List<Integer> keys = new ArrayList<>();
    private Random random = new Random(0);

    @Before
    public void fillMap() {
        for (int i = 0; i < 20_000_000; i++) {
            int key = random.nextInt();
            keys.add(key);
            @Nullable Integer res = map.put(key, key);
            if (map.size() > keys.size()) {
                throw new AssertionError();
            }
            assertTrue(res == null || res == key);
        }
    }

    @Test
    public void growTest() {
        verifyAllKeys();
    }

    private void verifyAllKeys() {
        for (int i = 0; i < keys.size(); i++) {
            Integer key = keys.get(i);
            if (!key.equals(map.get(key))) {
                throw new AssertionError("" + i);
            }
        }
    }
}
