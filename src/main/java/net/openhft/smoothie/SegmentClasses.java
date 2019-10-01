/*
 *    Copyright (C) Smoothie Map Authors
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
