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

import org.junit.Test;

public class ExpectedSizeTest {

    @Test(expected = IllegalArgumentException.class)
    public void minNegativeExpectedSizeTest() {
        new SmoothieMap<>(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxNegativeExpectedSizeTest() {
        new SmoothieMap<>(Long.MIN_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void tooLargeExpectedSizeTest() {
        new SmoothieMap<>(Long.MAX_VALUE);
    }

    @Test
    public void smallExpectedSizeTest() {
        for (int i = 0; i < 63; i++) {
            new SmoothieMap<>(i);
        }
    }
}
