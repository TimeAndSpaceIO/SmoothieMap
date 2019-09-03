package io.timeandspace.smoothie;

import io.timeandspace.collect.Equivalence;

import java.util.function.ToLongFunction;

final class EquivalenceBasedHashFunction<T> implements ToLongFunction<T> {
    private final Equivalence<T> equivalence;

    EquivalenceBasedHashFunction(Equivalence<T> equivalence) {
        this.equivalence = equivalence;
    }

    @Override
    public long applyAsLong(T value) {
        return SmoothieMap.intToLongHashCode(equivalence.hash(value));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{equivalence=" + equivalence + "}";
    }
}
