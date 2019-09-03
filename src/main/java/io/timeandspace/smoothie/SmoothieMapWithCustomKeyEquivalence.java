package io.timeandspace.smoothie;

import io.timeandspace.collect.Equivalence;

import static io.timeandspace.smoothie.ObjectSize.classSizeInBytes;

class SmoothieMapWithCustomKeyEquivalence<K, V> extends SmoothieMapWithCustomKeyHashFunction<K, V> {
    private static final long SIZE_IN_BYTES =
            classSizeInBytes(SmoothieMapWithCustomKeyEquivalence.class);

    private final Equivalence<K> keyEquivalence;

    SmoothieMapWithCustomKeyEquivalence(SmoothieMapBuilder<K, V> builder) {
        super(builder);
        keyEquivalence = builder.keyEquivalence();
    }

    @Override
    boolean keysEqual(Object queriedKey, K internalKey) {
        //noinspection unchecked
        return keyEquivalence.equivalent((K) queriedKey, internalKey);
    }

    @Override
    public Equivalence<K> keyEquivalence() {
        return keyEquivalence;
    }

    @Override
    int keyHashCodeForMapAndEntryHashCode(Object key) {
        //noinspection unchecked
        return keyEquivalence.hash((K) key);
    }

    /** Doesn't account for {@link #keyHashFunction} and {@link #keyEquivalence}. */
    @Override
    long smoothieMapClassSizeInBytes() {
        return SIZE_IN_BYTES;
    }
}
