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

import com.google.common.collect.ImmutableSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShiftRemoveTest {

    @Test
    public void shiftRemoveTest() {
        SmoothieMap<Key, Key> map = SmoothieMap.<Key, Key>newBuilder().build();
        Key k1 = new Key();
        Key k2 = new Key();
        Key k3 = new Key();
        map.put(k1, k1);
        map.put(k2, k2);
        map.put(k3, k3);
        assertEquals(ImmutableSet.of(k1, k2, k3), map.keySet());

        map.keySet().retainAll(ImmutableSet.of(k3));
        assertEquals(ImmutableSet.of(k3), map.keySet());
    }

    static class Key {
        @Override
        public int hashCode() {
            return 0;
        }
    }
}
