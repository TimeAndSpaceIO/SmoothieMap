package io.timeandspace.smoothie;

import java.util.function.ToLongFunction;

final class DefaultHashFunction<T> implements ToLongFunction<T> {

    @SuppressWarnings("unchecked")
    public static <T> DefaultHashFunction<T> instance() {
        return (DefaultHashFunction<T>) INSTANCE;
    }
    private static final DefaultHashFunction<Object> INSTANCE = new DefaultHashFunction<>();

    @Override
    public long applyAsLong(T value) {
        return SmoothieMap.defaultKeyHashCode(value);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
