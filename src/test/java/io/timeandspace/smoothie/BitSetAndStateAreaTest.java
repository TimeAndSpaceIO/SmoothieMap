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

import io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.DebugBitSetAndState;
import org.junit.Test;

import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.EMPTY_BIT_SET;
import static io.timeandspace.smoothie.SmoothieMap.BitSetAndStateArea.updateDeletedSlotCountAndSetLowestAllocBit;

public final class BitSetAndStateAreaTest {

    @Test
    public void testUpdateDeletedSlotCountAndSetLowestAllocBit() {
        DebugBitSetAndState bitSetAndState = new DebugBitSetAndState(
                updateDeletedSlotCountAndSetLowestAllocBit(EMPTY_BIT_SET, 0));
//        Assert.assertEquals(bitSetAndState.);
    }
}
