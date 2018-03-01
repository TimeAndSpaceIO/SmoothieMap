/*
 *    Copyright 2015, 2016 Chronicle Software
 *    Copyright 2016, 2018 Roman Leventov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.openhft.smoothie;

import com.google.common.collect.ImmutableSet;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShiftRemoveTest {

    @Test
    public void shiftRemoveTest() {
        SmoothieMap<Key, Key> map = new SmoothieMap<>();
        Key k1 = new Key();
        Key k2 = new Key();
        Key k3 = new Key();
        map.put(k1, null);
        map.put(k2, null);
        map.put(k3, null);
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

    @Test
    public void shiftRemoveEarlyBreakTest() {
        SmoothieMap<Long, Integer> map = new SmoothieMap<Long, Integer>() {
            @Override
            protected long keyHashCode(@Nullable Object key) {
                return ((Long) key).longValue();
            }
        };

        map.put(0L, 0);
        map.put(2L << (64 - Segment.LOG_HASH_TABLE_SIZE), 2);
        map.put(3L << (64 - Segment.LOG_HASH_TABLE_SIZE), 3);

        map.removeIf((k, v) -> true);
        assertEquals(0, map.size());
    }
}
