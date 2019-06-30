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

final class LongMath {

    static long clearLowestSetBit(long bitMask) {
        return bitMask & (bitMask - 1);
    }

    static long clearLowestNBits(long bitMask, int n) {
        if (n <= 0 || n > 63) {
            throw new IllegalArgumentException("n must be between 1 and 63, " + n + " given");
        }
        return bitMask & (-1L << n);
    }

    static long floorPowerOfTwo(long x) {
        if (x <= 0) {
            throw new IllegalArgumentException("x must be positive, " + x + " given");
        }
        return Long.highestOneBit(x);
    }

    /**
     * Usually this would be too trivial of a function to deem extraction, but since there is a
     * requirement in the project to make numeric conversions explicit, inlined code becomes a
     * little too cumbersome.
     */
    static double percentOf(long x, long total) {
        return (100.0 * (double) x) / ((double) total);
    }
}
