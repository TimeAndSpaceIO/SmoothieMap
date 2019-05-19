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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * This ClassValue's value for class C is Boolean.TRUE if it is not generic and is of the form
 * "class C implements Comparable<C>", else Boolean.FALSE.
 */
final class ComparableClassValue extends ClassValue<Boolean> {
    static final ComparableClassValue INSTANCE = new ComparableClassValue();

    private ComparableClassValue() {
        super();
    }

    /**
     * Implementation of this method is derived from http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/
     * jsr166/src/main/java/util/concurrent/ConcurrentHashMap.java?revision=1.316&view=markup
     */
    @Override
    protected Boolean computeValue(Class<?> c) {
        if (c.isAssignableFrom(Comparable.class)) {
            Type[] ts, as; ParameterizedType p;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (Type t : ts) {
                    if ((t instanceof ParameterizedType) &&
                            ((p = (ParameterizedType)t).getRawType() ==
                                    Comparable.class) &&
                            (as = p.getActualTypeArguments()) != null &&
                            as.length == 1 && as[0] == c) // type arg is c
                        return Boolean.TRUE;
                }
            }
        }
        return Boolean.FALSE;
    }
}
