/*
 *    Copyright 2015, 2016 Chronicle Software
 *    Copyright 2016, 2018 Roman Leventov
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

import org.junit.Test;

public class CollisionBloatTest {

    @Test
    public void collisionBloatTest() {
        SmoothieMap<BadHash, Integer> map = new SmoothieMap<>();
        try {
            for (int i = 0; i < 64; i++) {
                map.put(new BadHash(), 0);
            }
            throw new AssertionError("show throw IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    static class BadHash {
        @Override
        public int hashCode() {
            return 0;
        }
    }
}
