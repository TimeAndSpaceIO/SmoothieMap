package io.timeandspace.smoothie;

/**
 * For methods that are expected to be normally called less frequently than once per every ten
 * {@link SmoothieMap.Segment#SEGMENT_MAX_ALLOC_CAPACITY} / 2 .. SEGMENT_MAX_ALLOC_CAPACITY
 * (i. e. 24..48) point queries to a SmoothieMap or entries during iteration/stream operations. In
 * other words, methods with this annotation are expected to be normally called an order of
 * magnitude less frequently than methods annotated with {@link AmortizedPerSegment}.
 *
 * The performance of these methods is not important. Achieving zero allocations might still be
 * important in order to achieve overall SmoothieMap's zero allocation.
 *
 * @see AmortizedPerSegment
 */
public @interface RarelyCalledAmortizedPerSegment {
}
