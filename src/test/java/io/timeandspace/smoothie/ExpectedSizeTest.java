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

import org.junit.Test;

/**
 * TODO rewrite with assertThrows, see TestExceptionChecker in error-prone
 */
@SuppressWarnings("CheckReturnValue") // Unused builders
public class ExpectedSizeTest {

    @Test(expected = IllegalArgumentException.class)
    public void minNegativeExpectedSizeTest() {
        SmoothieMap.newBuilder().expectedSize(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxNegativeExpectedSizeTest() {
        SmoothieMap.newBuilder().expectedSize(Long.MIN_VALUE);
    }

    @Test
    public void tooLargeExpectedSizeTest() {
        SmoothieMap.newBuilder().expectedSize(Long.MAX_VALUE);
    }

    @Test
    public void smallExpectedSizeTest() {
        for (int i = 0; i < 63; i++) {
            SmoothieMap.newBuilder().expectedSize(i);
        }
    }
}
