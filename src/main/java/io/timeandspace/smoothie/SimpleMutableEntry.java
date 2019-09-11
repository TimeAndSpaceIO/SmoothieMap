package io.timeandspace.smoothie;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import static io.timeandspace.smoothie.Utils.nonNullOrThrowCme;

final class SimpleMutableEntry<K, V> extends AbstractEntry<K, V> {
    private @MonotonicNonNull K key;
    private @MonotonicNonNull V value;

    @CanIgnoreReturnValue
    SimpleMutableEntry<K, V> with(K key, V value) {
        this.key = key;
        this.value = value;
        return this;
    }

    @Override
    public K getKey() {
        return nonNullOrThrowCme(key);
    }

    @Override
    public V getValue() {
        return nonNullOrThrowCme(value);
    }

    @Override
    public V setValue(V value) {
        // The object is designed for reuse, setting value doesn't make sense as in entries in
        // entry-object-based  map implementations such as HashMap.
        throw new UnsupportedOperationException("Use SmoothieMap.");
    }
}
