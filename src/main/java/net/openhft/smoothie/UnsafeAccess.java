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

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

final class UnsafeAccess {
    public static final Unsafe U;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            U = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static long minInstanceFieldOffset(Class<?> objectClass) {
        // U::objectFieldOffset triggers forbidden-apis of Objects.requireNonNull() for some reason
        //noinspection Convert2MethodRef
        return Stream
                .of(objectClass.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .mapToLong((Field field) -> U.objectFieldOffset(field))
                .min()
                .getAsLong();
    }

    private UnsafeAccess() {
    }
}
