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

import java.util.Map;
import java.util.Objects;

abstract class AbstractEntry<K, V> implements Map.Entry<K, V> {

    @Override
    public final int hashCode() {
        return getKey().hashCode() ^ getValue().hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof Map.Entry))
            return false;
        Map.Entry<?, ?> e = (Map.Entry<?, ?>) obj;
        return Objects.equals(getKey(), e.getKey()) &&
                Objects.equals(getValue(), e.getValue());
    }

    @Override
    public final String toString() {
        return getKey() + "=" + getValue();
    }
}
