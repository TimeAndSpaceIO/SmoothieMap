package io.timeandspace.collect;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.function.Predicate;

public interface ObjCollection<E> extends Collection<E> {
    /**
     * Checks the given {@code predicate} on each element in this collection until all elements have
     * been processed or the predicate returns false for some element, or throws an Exception.
     * Exceptions thrown by the predicate are relayed to the caller.
     *
     * <p>The elements will be processed in the same order as they appear in {@link #iterator()} and
     * {@link #forEach}.
     *
     * <p>If the collection is empty, this method returns {@code true} immediately.
     *
     * @param predicate the predicate to be checked for each element
     * @return {@code true} if there are no elements in the collection, or if the predicate returned
     * {@code true} for all elements of the collection, {@code false} if the predicate returned
     * {@code false} for some element
     * @throws NullPointerException if the specified predicate is null
     * @throws ConcurrentModificationException if any structural modification of the collection
     * (addition or removal of an element) is detected during iteration
     * @see #forEach
     */
    boolean forEachWhile(Predicate<? super E> predicate);

    long sizeAsLong();
}
