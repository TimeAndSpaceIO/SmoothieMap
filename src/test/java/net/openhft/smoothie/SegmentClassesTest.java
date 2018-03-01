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

import org.junit.Assert;
import org.junit.Test;

import java.lang.invoke.MethodHandle;

public class SegmentClassesTest {

    @Test
    public void segmentClassGeneratorTest() throws Throwable {
        for (int i = SmoothieMap.MIN_ALLOC_CAPACITY; i <= SmoothieMap.MAX_ALLOC_CAPACITY; i++) {
            MethodHandle mh = SegmentClasses.acquireClassConstructor(i);
            mh.invoke();
            MethodHandle mh2 = SegmentClasses.acquireClassConstructor(i);
            Assert.assertEquals(mh, mh2);
        }
    }
}
