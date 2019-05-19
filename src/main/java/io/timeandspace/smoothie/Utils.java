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


import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.jetbrains.annotations.Contract;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ConcurrentModificationException;

final class Utils {

    @Contract("false -> fail")
    static void assertThat(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }

    static void assertEqual(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected: " + expected + ", actual: " + actual);
        }
    }

    /**
     * Replacing division with shift: Java doesn't optimize division by a power-of-two constant
     * ({@link Byte#SIZE}, in this case) to a raw shift because the arithmetic in Java is
     * always signed. See
     * lemire.me/blog/2017/05/09/signed-integer-division-by-a-power-of-two-can-be-expensive/
     */
    public static final int BYTE_SIZE_DIVISION_SHIFT = 3;

    static {
        assertThat(BYTE_SIZE_DIVISION_SHIFT == Integer.numberOfTrailingZeros(Byte.SIZE));
    }

    /**
     * The difference between this method and {@link java.util.Objects#requireNonNull(Object)} is
     * that this method doesn't return the argument back, resulting in less bytecodes that
     * ultimately makes SmoothieMap friendlier for method inlining, because inlining thresholds and
     * limits are defined in terms of the numbers of bytecodes in Hotspot.
     */
    static void requireNonNull(Object obj) {
        if (obj == null) {
            throw new NullPointerException();
        }
    }

    static void checkNonNegative(long value, String meaning) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    meaning + " must be non-negative, " + value + " given");
        }
    }

    @Contract(value = "null -> fail; !null -> param1", pure = true)
    static <T> T nonNullOrThrowCme(@Nullable T obj) {
        if (obj == null) {
            throw new ConcurrentModificationException();
        }
        return obj;
    }

    @CanIgnoreReturnValue
    @Contract(value = "null -> fail; !null -> param1", pure = true)
    static <T> T assertNonNull(@Nullable T obj) {
        if (obj == null) {
            throw new AssertionError();
        }
        return obj;
    }

    static void assertIsPowerOfTwo(int n, String meaning) {
        if (!IntMath.isPowerOfTwo(n)) {
            throw new AssertionError(meaning + ": " + n);
        }
    }

    private Utils() {}
}
