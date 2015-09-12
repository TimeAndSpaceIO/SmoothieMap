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

import com.google.common.collect.ImmutableSet;
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
}
