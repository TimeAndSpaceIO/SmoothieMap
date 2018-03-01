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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class SegmentClasses {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodHandle[] mhCache =
            new MethodHandle[SmoothieMap.MAX_ALLOC_CAPACITY + 1];

    static MethodHandle acquireClassConstructor(int allocationCapacity) {
        MethodHandle m;
        if ((m = mhCache[allocationCapacity]) != null)
            return m;
        return makeMethodHandle(allocationCapacity);
    }

    private static MethodHandle makeMethodHandle(int allocationCapacity) {
        try {
            String segmentClassName = "net.openhft.smoothie.Segment" + allocationCapacity;
            Class<?> segmentClass = Class.forName(segmentClassName);
            MethodHandle mh =
                    LOOKUP.findConstructor(segmentClass, MethodType.methodType(void.class));
            mhCache[allocationCapacity] = mh;
            return mh;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private SegmentClasses() {}
}
