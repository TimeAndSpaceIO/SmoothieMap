package io.timeandspace.smoothie;

import io.timeandspace.collect.Equivalence;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

/**
 * The logic of this class is the same as of {@link SmoothieMapWithCustomValueEquivalence}. These
 * classes should be updated in parallel.
 */
final class SmoothieMapWithCustomKeyAndValueEquivalences<K, V>
        extends SmoothieMapWithCustomKeyEquivalence<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyAndValueEquivalences.class);

    private final Equivalence<V> valueEquivalence;

    SmoothieMapWithCustomKeyAndValueEquivalences(SmoothieMapBuilder<K, V> builder) {
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
    int valueHashCodeForMapAndEntryHashCode(Object value) {
        //noinspection unchecked
        return valueEquivalence.hash((V) value);
    }

    /**
     * Doesn't account for {@link #keyHashFunction}, {@link #keyEquivalence}, and
     * {@link #valueEquivalence}.
     */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
