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
