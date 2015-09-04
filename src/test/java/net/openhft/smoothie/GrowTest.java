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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GrowTest {

    SmoothieMap<Integer, Integer> map = new SmoothieMap<>();
    List<Integer> keys = new ArrayList<>();
    Random random = new Random(42);

    @Before
    public void fillMap() {
        for (int i = 0; i < 10_000_000; i++) {
            int key = random.nextInt();
            keys.add(key);
            Integer res = map.put(key, 0);
            assertTrue(res == null || res == 0);
        }
    }

    @Test
    public void growTest() {
        for (int i = 0; i < keys.size(); i++) {
            Integer key = keys.get(i);
            assertEquals((Integer) 0, map.get(key));
        }
    }

    @Test
    public void testClone() {
        SmoothieMap<Integer, Integer> clone = map.clone();
        assertEquals(map, clone);
    }
}
