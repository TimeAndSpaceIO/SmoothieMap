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

/**
 * Copied from Guava:
 * https://github.com/google/guava/blob/f15fdd10cd1e3e81ad2c31f555dfaaf70a72cf69/
 * guava/src/com/google/common/math/IntMath.java
 *
 * Copyright (C) The Guava Authors
 */
final class IntMath {

    /**
     * Returns {@code true} if {@code x} represents a power of two.
     *
     * <p>This differs from {@code Integer.bitCount(x) == 1}, because {@code
     * Integer.bitCount(Integer.MIN_VALUE) == 1}, but {@link Integer#MIN_VALUE} is not a power of
     * two.
     */
    static boolean isPowerOfTwo(int x) {
        return x > 0 & (x & (x - 1)) == 0;
    }

    private IntMath() {}
}
