/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.smoothie;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IterationTest {

    SmoothieMap<Integer, Integer> map = new SmoothieMap<>();
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
