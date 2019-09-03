package io.timeandspace.smoothie;

import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

class SmoothieMapWithCustomKeyHashFunction<K, V> extends SmoothieMap<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyHashFunction.class);

    private final ToLongFunction<K> keyHashFunction;

    SmoothieMapWithCustomKeyHashFunction(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        keyHashFunction = builder.keyHashFunction();
    }

    @Override
    long keyHashCode(Object key) {
        //noinspection unchecked
        return keyHashFunction.applyAsLong((K) key);
    }

    @Override
    ToLongFunction<K> getKeyHashFunction() {
        return keyHashFunction;
    }

    /** Doesn't account for {@link #keyHashFunction}. */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
