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

import org.junit.jupiter.api.Test;

import static io.timeandspace.smoothie.HashTable.fullDataGroupForTesting;
import static io.timeandspace.smoothie.HashTable.match;
import static org.junit.Assert.assertEquals;

final class HashTableTest {

    @Test
    void testMatchJavadocExample() {
        assertEquals(0x80800000L, match(0x1716151413121110L, 0x12L, fullDataGroupForTesting()));
    }
}
