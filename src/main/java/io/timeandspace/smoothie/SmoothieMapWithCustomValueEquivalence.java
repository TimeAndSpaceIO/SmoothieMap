package io.timeandspace.smoothie;

import io.timeandspace.collect.Equivalence;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

/**
 * The logic of this class is the same as of {@link SmoothieMapWithCustomKeyAndValueEquivalences}.
 * These classes should be updated in parallel.
 */
final class SmoothieMapWithCustomValueEquivalence<K, V> extends SmoothieMap<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomValueEquivalence.class);

    private final Equivalence<V> valueEquivalence;

    SmoothieMapWithCustomValueEquivalence(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        valueEquivalence = builder.valueEquivalence();
    }

    @Override
    boolean valuesEqual(Object queriedValue, V internalValue) {
        //noinspection unchecked
        return valueEquivalence.equivalent((V) queriedValue, internalValue);
    }

    @Override
    public Equivalence<V> valueEquivalence() {
        return valueEquivalence;
    }

    @Override
    int valueHashCodeForAggregateHashCodes(Object value) {
        //noinspection unchecked
        return valueEquivalence.hash((V) value);
    }

    /** Doesn't account for {@link #valueEquivalence}. */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
