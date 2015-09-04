/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.smoothie;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.function.*;

import static net.openhft.smoothie.SmoothieMap.GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY;
import static net.openhft.smoothie.UnsafeAccess.U;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;

/**
 * SegmentPrimitiveFields are separated from Segment, because there is no confidence that all VMs
 * store reference fields last in object memory layout
 */
class SegmentPrimitiveArea {
    /**
     * Free slots are designated by one-bits to avoid extra inversions, needed because there are
     * only {@link Long#numberOfTrailingZeros(long)} and {@link Long#numberOfLeadingZeros(long)}
     * methods in {@code Long} class, no methods for leading/trailing "ones".
     */
    static final long CLEAR_BIT_SET = ~0L;

    /**
     * 2 bytes per slot; 128 slots => 128 * 2 / 8 = 32 long words
     */
    @SuppressWarnings("unused")
    long t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
    @SuppressWarnings("unused")
    long p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;

    long bitSet = CLEAR_BIT_SET;
    int tier;
}

class Segment<K, V> extends SegmentPrimitiveArea implements Cloneable {

    @SuppressWarnings("unused")
    Object k0, v0;

    static final int STORED_HASH_BITS = 10;
    static final int ALLOC_INDEX_BITS = 6;
    static final int HASH_TABLE_SLOT_SIZE = 2; // in bytes
    static final int HASH_TABLE_SIZE = 128; // in slots
    static final int LOG_HASH_TABLE_SIZE = Integer.numberOfTrailingZeros(HASH_TABLE_SIZE);

    static long storedHash(long hash) {
        return (hash >>> (64 - STORED_HASH_BITS - 1)) & ((1 << STORED_HASH_BITS) - 1);
    }

    static long slotIndex(long hash) {
        return hash >>> (64 - LOG_HASH_TABLE_SIZE);
    }

    static long nextSlotIndex(long slotIndex) {
        return (slotIndex + 1) & (HASH_TABLE_SIZE - 1);
    }

    final long readSlot(long slotIndex) {
        return U.getChar(this, HASH_TABLE_OFFSET + (slotIndex << 1));
    }

    final void writeSlot(long slotIndex, long slot) {
        U.putChar(this, HASH_TABLE_OFFSET + (slotIndex << 1), (char) slot);
    }

    final void clearSlot(long slotIndex) {
        U.putChar(this, HASH_TABLE_OFFSET + (slotIndex << 1), (char) 0);
    }

    static long hash(long slot) {
        return slot & ((1 << STORED_HASH_BITS) - 1);
    }

    static long allocIndex(long slot) {
        return slot >> STORED_HASH_BITS;
    }

    static long shiftDistance(long shiftedSlotIndex, long initialSlotIndex) {
        // Since there could be at most 2^ALLOC_INDEX_BITS allocations, i. e. non empty slots,
        // this is also the real limit of shift distance.
        return (shiftedSlotIndex - initialSlotIndex) & ((1 << ALLOC_INDEX_BITS) - 1);
    }

    static long makeSlot(long storedHash, long allocIndex) {
        return storedHash | (allocIndex << STORED_HASH_BITS);
    }

    final K readKey(long allocIndex) {
        //noinspection unchecked
        return (K) U.getObject(this, allocOffset(allocIndex));
    }

    final V readValue(long allocIndex) {
        //noinspection unchecked
        return (V) U.getObject(this, allocOffset(allocIndex) + ARRAY_OBJECT_INDEX_SCALE);
    }

    final void writeKey(long allocIndex, Object key) {
        U.putObject(this, allocOffset(allocIndex), key);
    }

    final void writeValue(long allocIndex, Object value) {
        U.putObject(this, allocOffset(allocIndex) + ARRAY_OBJECT_INDEX_SCALE, value);
    }

    /**
     * @return 1-indexed alloc index, to allow {@code slot != 0} conditions,
     * i. e. allocIndex = 0 means empty hash table slot
     */
    final long alloc() {
        long bitSet = this.bitSet;
        this.bitSet = (bitSet - 1) & bitSet;
        return Long.numberOfTrailingZeros(bitSet) + 1;
    }

    final void free(long allocIndex) {
        bitSet |= (1L << (allocIndex - 1));
    }

    final boolean isFree(long allocIndex) {
        return (bitSet & (1L << (allocIndex - 1))) != 0;
    }

    static long fullBitSet(SmoothieMap<?, ?> map) {
        return (Long.MIN_VALUE >> ~map.allocCapacity);
    }

    final void cancelAllocBeyondCapacity(SmoothieMap<K, V> map) {
        bitSet = fullBitSet(map);
    }

    final int size() {
        return 64 - Long.bitCount(bitSet);
    }

    final boolean isEmpty() {
        return bitSet == CLEAR_BIT_SET;
    }

    /**
     * @return allocation index, or 0 if not found
     */
    final long find(SmoothieMap<K, V> map, long hash, Object key) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                return allocIndex;
            }
        }
        return 0;
    }

    final V put(SmoothieMap<K, V> map, long hash, K key, V value, boolean onlyIfAbsent) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V oldValue;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                oldValue = readValue(allocIndex);
                if (!onlyIfAbsent || oldValue == null)
                    writeValue(allocIndex, value);
                return oldValue;
            }
        }
        insert(map, hash, key, value, slotIndex, storedHash);
        return null;
    }

    private void insert(SmoothieMap<K, V> map, long hash, K key, V value,
            long slotIndex, long storedHash) {
        long allocIndex;
        if ((allocIndex = alloc()) <= map.allocCapacity) {
            writeEntry(key, value, slotIndex, storedHash, allocIndex);
            map.size++;
            map.modCount++;
        } else {
            splitSegmentAndPut(map, hash, key, value);
        }
    }

    private void writeEntry(K key, V value, long slotIndex, long storedHash, long allocIndex) {
        writeSlot(slotIndex, makeSlot(storedHash, allocIndex));
        writeKey(allocIndex, key);
        writeValue(allocIndex, value);
    }

    final void putOnSplit(SmoothieMap<K, V> map, long hash, K key, V value) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                throw new IllegalStateException(
                        "When inserting entries into newly split segment it could not find\n" +
                                "a duplicate key. It means either SmoothieMap is updated\n" +
                                "concurrently OR keys are mutable and keyHashCode() and\n" +
                                "keysEqual() calls return different results in time, applied to\n" +
                                "the same arguments.");
            }
        }
        if ((allocIndex = alloc()) <= map.allocCapacity) {
            writeEntry(key, value, slotIndex, storedHash, allocIndex);
        } else {
            throw new ConcurrentModificationException(
                    "When inserting entries into newly split segment it could be filled up " +
                            "only concurrently");
        }
    }

    final V computeIfAbsent(SmoothieMap<K, V> map, long hash, K key,
            Function<? super K, ? extends V> mappingFunction) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V value;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                if ((value = readValue(allocIndex)) != null)
                    return value;
                if ((value = mappingFunction.apply(key)) != null)
                    writeValue(allocIndex, value);
                return value;
            }
        }
        if ((value = mappingFunction.apply(key)) != null)
            insert(map, hash, key, value, slotIndex, storedHash);
        return value;
    }

    final V computeIfPresent(SmoothieMap<K, V> map, long hash, K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V value;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                if ((value = readValue(allocIndex)) != null) {
                    if ((value = remappingFunction.apply(key, value)) != null) {
                        writeValue(allocIndex, value);
                        return value;
                    } else {
                        remove(map, slotIndex, allocIndex);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /**
     * @param matchValue {@code true} if should compare the mapped value to the given {@code value}
     * before remove
     */
    final long remove(SmoothieMap<K, V> map, long hash, Object key, Object value,
            boolean matchValue) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V v;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                if (!matchValue || (v = readValue(allocIndex)) == value ||
                        (value != null && map.valuesEqual(value, v))) {
                    removeButAlloc(map, slotIndex);
                    return allocIndex;
                } else {
                    return 0;
                }
            }
        }
        return 0;
    }

    final void iterationRemove(SmoothieMap<K, V> map, K key, long allocIndex) {
        long slotIndex, slot, hash = map.keyHashCode(key);
        long storedHash = storedHash(hash);
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash && allocIndex(slot) == allocIndex) {
                remove(map, slotIndex, allocIndex);
                return;
            }
        }
        throw new ConcurrentModificationException("Unable to find entry in segment's hash table");
    }

    final void remove(SmoothieMap<K, V> map, long slotIndex, long allocIndex) {
        eraseAlloc(allocIndex);
        removeButAlloc(map, slotIndex);
    }

    private void removeButAlloc(SmoothieMap<K, V> map, long slotIndex) {
        shiftRemove(slotIndex);
        map.size--;
        map.modCount++;
    }

    final V compute(SmoothieMap<K, V> map, long hash, K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V value;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                if ((value = remappingFunction.apply(key, this.<V>readValue(allocIndex))) != null) {
                    writeValue(allocIndex, value);
                    return value;
                } else {
                    remove(map, slotIndex, allocIndex);
                    return null;
                }
            }
        }
        if ((value = remappingFunction.apply(key, null)) != null)
            insert(map, hash, key, value, slotIndex, storedHash);
        return value;
    }

    final V merge(SmoothieMap<K, V> map, long hash, K key, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        long slotIndex, slot, allocIndex, storedHash = storedHash(hash);
        K k;
        V oldValue;
        for (slotIndex = slotIndex(hash); (slot = readSlot(slotIndex)) != 0;
             slotIndex = nextSlotIndex(slotIndex)) {
            if (hash(slot) == storedHash &&
                    ((k = readKey((allocIndex = allocIndex(slot)))) == key ||
                            (key != null && map.keysEqual(key, k)))) {
                if ((oldValue = readValue(allocIndex)) == null ||
                        (value = remappingFunction.apply(oldValue, value)) != null) {
                    writeValue(allocIndex, value);
                    return value;
                } else {
                    remove(map, slotIndex, allocIndex);
                    return null;
                }
            }
        }
        insert(map, hash, key, value, slotIndex, storedHash);
        return value;
    }

    final void splitSegmentAndPut(SmoothieMap<K, V> map, long hash, K key, V value) {
        int mc = map.modCount;
        cancelAllocBeyondCapacity(map);

        int segments = map.segments();
        int tierDiff = map.segmentsTier - this.tier;
        // Double segments array, if needed
        if (tierDiff == 0) {
            map.doubleSegments();
            segments *= 2;
            tierDiff = 1;
        }

        // Create a new Segment and replace half indexes pointing to the old segment with
        // refs to the new
        int segmentOccursEach = segments >>> tierDiff;
        int lowestSegmentIndex = (int) (hash & (segmentOccursEach - 1));
        int newTier = this.tier += 1;
        Segment<K, V> higherSegment = map.makeSegment(newTier);
        for (int segmentIndex = lowestSegmentIndex + segmentOccursEach; segmentIndex < segments;
             segmentIndex += segmentOccursEach * 2) {
            map.segments[segmentIndex] = higherSegment;
        }

        // Rebalance entries between two segments
        int balance = 0;
        for (long slotIndex = 0, slot; slotIndex < HASH_TABLE_SIZE; slotIndex++) {
            if ((slot = readSlot(slotIndex)) != 0) {
                long allocIndex;
                K k;
                long kHash = map.keyHashCode(k = readKey(allocIndex = allocIndex(slot)));
                if ((kHash & segmentOccursEach) != 0) {
                    V v = readValue(allocIndex);
                    eraseAlloc(allocIndex);
                    if (shiftRemove(slotIndex) != slotIndex)
                        slotIndex--; // don't skip shifted slot
                    higherSegment.putOnSplit(map, kHash, k, v);
                    balance++;
                }
            }
        }
        // Check rebalanced something, otherwise we are going to split segments infinitely
        // ending up with OutOfMemoryError
        if (balance == 0)
            checkHashCodesAreNotAllSame(map);
        if (balance >= map.allocCapacity)
            higherSegment.checkHashCodesAreNotAllSame(map);

        // Finally, put the entry
        Segment<K, V> segmentForPut;
        segmentForPut = (hash & segmentOccursEach) != 0 ? higherSegment : this;
        segmentForPut.put(map, hash, key, value, false);
        mc++;

        if (mc != map.modCount)
            throw new ConcurrentModificationException();
    }

    private void checkHashCodesAreNotAllSame(SmoothieMap<K, V> map) {
        long firstHash = -1;
        for (long slotIndex = 0, slot; slotIndex < HASH_TABLE_SIZE; slotIndex++) {
            if ((slot = readSlot(slotIndex)) != 0) {
                long kHash = map.keyHashCode(readKey(allocIndex(slot)));
                kHash &= GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY - 1;
                if (firstHash < 0)
                    firstHash = kHash;
                if (kHash != firstHash)
                    return;
            }
        }
        // we checked all keys and all hashes collide
        throw new IllegalStateException(map.allocCapacity + " inserted keys has " +
                Integer.numberOfTrailingZeros(GUARANTEED_JAVA_ARRAY_POWER_OF_TWO_CAPACITY) +
                " lowest bits of hash code\n" +
                "colliding, try to override SmoothieMap.keyHashCode() and implement a hash\n" +
                "function with better distribution");
    }

    final void eraseAlloc(long allocIndex) {
        writeKey(allocIndex, null);
        writeValue(allocIndex, null);
        free(allocIndex);
    }

    /**
     * @return last removed index
     */
    final long shiftRemove(long slotIndexToRemove) {
        for (long slotIndexToShift = slotIndexToRemove, slotToShift;
             (slotToShift = readSlot((slotIndexToShift = nextSlotIndex(slotIndexToShift)))) != 0;) {
            if (shiftDistance(slotIndexToShift,
                    slotToShift >>> (STORED_HASH_BITS + 1 - LOG_HASH_TABLE_SIZE)) >=
                    shiftDistance(slotIndexToShift, slotIndexToRemove)) {
                writeSlot(slotIndexToRemove, slotToShift);
                slotIndexToRemove = slotIndexToShift;
            }
        }
        clearSlot(slotIndexToRemove);
        return slotIndexToRemove;
    }

    final boolean containsValue(SmoothieMap<K, V> map, V value) {
        V v;
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0) {
                if (((v = readValue(allocIndex)) == value ||
                        (value != null && map.valuesEqual(value, v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    final void clear(SmoothieMap<K, V> map) {
        U.setMemory(this, HASH_TABLE_OFFSET, HASH_TABLE_SIZE * HASH_TABLE_SLOT_SIZE, (byte) 0);
        map.size -= size();
        for (long allocIndex = 1; allocIndex <= map.allocCapacity; allocIndex++) {
            writeKey(allocIndex, null);
            writeValue(allocIndex, null);
        }
        bitSet = CLEAR_BIT_SET;
    }

    @Override
    public final int hashCode() {
        int h = 0;
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0) {
                h += Objects.hashCode(readKey(allocIndex)) ^
                        Objects.hashCode(readValue(allocIndex));
            }
        }
        return h;
    }

    final void forEach(BiConsumer<? super K, ? super V> action) {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0)
                action.accept(this.<K>readKey(allocIndex), this.<V>readValue(allocIndex));
        }
    }

    final void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0) {
                writeValue(allocIndex,
                        function.apply(this.<K>readKey(allocIndex), this.<V>readValue(allocIndex)));
            }
        }
    }

    final void forEachKey(Consumer<? super K> action) {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0)
                action.accept(this.<K>readKey(allocIndex));
        }
    }

    final void forEachValue(Consumer<? super V> action) {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0)
                action.accept(this.<V>readValue(allocIndex));
        }
    }

    final boolean forEachWhile(BiPredicate<? super K, ? super V> predicate) {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0 &&
                    !predicate.test(this.<K>readKey(allocIndex), this.<V>readValue(allocIndex))) {
                return false;
            }
        }
        return true;
    }

    final void writeAllEntries(ObjectOutputStream s) throws IOException {
        for (long a, tail, allocations = (a = ~bitSet) << (tail = Long.numberOfLeadingZeros(a)),
             allocIndex = 64 - tail; allocations != 0; allocations <<= 1, allocIndex--) {
            if (allocations < 0) {
                s.writeObject(readKey(allocIndex));
                s.writeObject(readValue(allocIndex));
            }
        }
    }

    @Override
    public final Segment<K, V> clone() {
        try {
            //noinspection unchecked
            return (Segment<K, V>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    final int removeIf(SmoothieMap<K, V> map, BiPredicate<? super K, ? super V> filter,
            int modCount) {
        if (isEmpty())
            return modCount;
        long startSlot = 0;
        while (readSlot(startSlot) != 0) {
            startSlot++;
        }
        for (long slotIndex = startSlot + 1, slot, allocIndex; slotIndex != startSlot;
             slotIndex = nextSlotIndex(slotIndex)) {
            if ((slot = readSlot(slotIndex)) != 0) {
                if (filter.test(readKey((allocIndex = allocIndex(slot))), readValue(allocIndex))) {
                    remove(map, slotIndex, allocIndex);
                    modCount++;
                }
            }
        }
        return modCount;
    }

    static final long HASH_TABLE_OFFSET;
    static final long ALLOC_OFFSET;
    static final int ALLOC_INDEX_SHIFT = (ARRAY_OBJECT_INDEX_SCALE == 4 ? 2 : 3) + 1;

    private static long allocOffset(long allocIndex) {
        return ALLOC_OFFSET + (allocIndex << ALLOC_INDEX_SHIFT);
    }

    static {
        try {
            HASH_TABLE_OFFSET = U.objectFieldOffset(
                    SegmentPrimitiveArea.class.getDeclaredField("t0"));
            // because slots are 1-indexed, make offset "wrong" to allow simple #allocOffset() impl
            ALLOC_OFFSET = U.objectFieldOffset(Segment.class.getDeclaredField("k0")) -
                    (ARRAY_OBJECT_INDEX_SCALE * 2);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
