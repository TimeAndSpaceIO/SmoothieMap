/*
 * Copyright (C) The SmoothieMap Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.timeandspace.smoothie;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntVal;
import sun.misc.Unsafe;

import java.nio.ByteOrder;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Set;

import static io.timeandspace.smoothie.SmoothieMap.LONG_PHI_MAGIC;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_OBJECT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.Utils.BYTE_SIZE_DIVISION_SHIFT;

public final class SwissTable<K, V> extends AbstractMap<K, V> {

    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final int CONTROL_BITS = 7;
    private static final long CONTROL_BITS_MASK = (1 << CONTROL_BITS) - 1;

    private static final int GROUP_SLOTS = 8;

    /** [Pre-casted constant] */
    private static final long ARRAY_LONG_BASE_OFFSET_AS_LONG = (long) Unsafe.ARRAY_LONG_BASE_OFFSET;

    /** `* 2` because there are two objects in each slot: key and value. */
    private static final long TABLE_SLOT_INDEX_SCALE = ARRAY_OBJECT_INDEX_SCALE_AS_LONG * 2;

    private static final long TABLE_VALUE_BASE_OFFSET =
            ARRAY_OBJECT_BASE_OFFSET_AS_LONG + ARRAY_OBJECT_INDEX_SCALE_AS_LONG;

    private static final long MOST_SIGNIFICANT_BYTE_BITS = 0x8080808080808080L;
    private static final long LEAST_SIGNIFICANT_BYTE_BITS = 0x0101010101010101L;

    /**
     * EMPTY_CONTROL and {@link #DELETED_CONTROL} values are taken from hashbrown:
     * https://github.com/Amanieu/hashbrown/blob/6b9cc4e01090553c5928ccc0ee4568319ee0ed33/
     * src/raw/mod.rs#L46-L50
     *
     * It differs from raw_hash_set.h, because Segment doesn't need a "sentinel" value (see
     * https://github.com/abseil/abseil-cpp/blob/3088e76c597e068479e82508b1770a7ad0c806b6/
     * absl/container/internal/raw_hash_set.h#L292) for iteration. The sentinel value is only
     * necessary in C++ due to how C++ iterators work, requiring the end() pointer. See
     * https://github.com/Amanieu/hashbrown/issues/35#issuecomment-444684378 for more info.
     */
    private static final @IntVal(0b1111_1111) byte EMPTY_CONTROL = -1;
    private static final @IntVal(0b1000_0000) byte DELETED_CONTROL = -128;

    /**
     * Has all bits set because {@link #EMPTY_CONTROL} is all bits set.
     */
    private static final long EMPTY_CONTROL_GROUP = -1L;

    /**
     * Max positive int power of two.
     */
    private static final int MAX_CAPACITY = 1 << 30;

    private static final int DEFAULT_EXPECTED_SIZE = 10;

    private static int nextPowerOfTwo(int n) {
        int highestOneBit = Integer.highestOneBit(n);
        if (n == highestOneBit) {
            return highestOneBit;
        }
        int nextPowerOfTwo = highestOneBit * 2;
        if (nextPowerOfTwo < 0) {
            throw new IllegalArgumentException();
        }
        return nextPowerOfTwo;
    }

    private static long clearLowestSetBit(long bitMask) {
        return bitMask & (bitMask - 1);
    }

    /**
     * Determines whether the lowest empty or deleted slot in the controls group is empty (then
     * returns 1), or deleted (then returns 0).
     * @param trailingZeros the number of trailing zeros in a result of
     *        {@link #matchEmptyOrDeleted} call. I. e. this argument must be equal to
     *        Long.numberOfTrailingZeros(matchEmptyOrDeleted(controlsGroup)).
     *
     * @implNote implementation of this method exploits the fact that {@link #EMPTY_CONTROL}'s
     * 7th bit is 1, and {@link #DELETED_CONTROL}'s 7th bit is 0. trailingZeros is the number of
     * bits until the most significant bit of the corresponding matched control byte (see {@link
     * #matchEmptyOrDeleted}), i. e. until the 7th bit.
     */
    private static int extractMatchingEmpty(long controlsGroup, int trailingZeros) {
        return (int) ((controlsGroup >>> (trailingZeros - 1)) & 1);
    }

    /**
     * For the technique, see:
     * http://graphics.stanford.edu/~seander/bithacks.html##ValueInWord
     * (Determine if a word has a byte equal to n).
     *
     * Caveat: there are false positives but:
     * - they only occur if there is a real match
     * - they never occur on {@link #EMPTY_CONTROL} or {@link #DELETED_CONTROL}
     * - they will be handled gracefully by subsequent checks in code
     *
     * Example:
     *   controlsGroup = 0x1716151413121110
     *   hashControlBits = 0x12
     *   return (x - LEAST_SIGNIFICANT_BYTE_BITS) & ~x & MOST_SIGNIFICANT_BYTE_BITS
     *     = 0x0000000080800000
     *
     * This method is copied from https://github.com/abseil/abseil-cpp/blob/
     * 3088e76c597e068479e82508b1770a7ad0c806b6/absl/container/internal/raw_hash_set.h#L428-L446
     */
    private static long match(long controlsGroup, long hashControlBits) {
        long x = controlsGroup ^ (LEAST_SIGNIFICANT_BYTE_BITS * hashControlBits);
        return (x - LEAST_SIGNIFICANT_BYTE_BITS) & ~x & MOST_SIGNIFICANT_BYTE_BITS;
    }

    private static long matchEmpty(long controlsGroup) {
        // Matches when the two highest bits in a control byte are ones.
        return (controlsGroup & (controlsGroup << 1)) & MOST_SIGNIFICANT_BYTE_BITS;
    }

    private static long matchEmptyOrDeleted(long controlsGroup) {
        return controlsGroup & MOST_SIGNIFICANT_BYTE_BITS;
    }

    private static long matchFull(long controlsGroup) {
        // Matches when the highest bit in a byte is zero.
        return (~controlsGroup) & MOST_SIGNIFICANT_BYTE_BITS;
    }

    private long[] controls;
    private int lookupMask;
    private Object[] table;
    private int size;
    private int numDeletedSlots;
    private int modCount;

    public SwissTable() {
        this(DEFAULT_EXPECTED_SIZE);
    }

    @SuppressWarnings("WeakerAccess")
    public SwissTable(int expectedSize) {
        int capacity = nextPowerOfTwo(expectedSize);
        if (expectedSize > maxNumNonEmptySlotsBeforeRehash(capacity) && capacity < MAX_CAPACITY) {
            capacity *= 2;
        }
        init(capacity);
    }

    private void init(int capacity) {
        lookupMask = capacity - 1;
        // Extra GROUP_SLOTS (1 long word) of control bytes facilitates branchless
        // readControlsGroup() in lookup methods like get() and put(). See also writing
        // secondarySlotIndex in writeControlByte().
        controls = new long[(capacity + GROUP_SLOTS) / GROUP_SLOTS];
        table = new Object[capacity * 2];
        Arrays.fill(controls, EMPTY_CONTROL_GROUP);
    }

    @Override
    public int size() {
        return size;
    }

    private int capacity() {
        return lookupMask + 1;
    }

    private int maxNumNonEmptySlotsBeforeRehash() {
        return maxNumNonEmptySlotsBeforeRehash(capacity());
    }

    private static int maxNumNonEmptySlotsBeforeRehash(int capacity) {
        // [Replacing division with shift]
        return (capacity >>> 2) * 3; // Corresponds to 0.75 max load factor.
    }

    protected long keyHashCode(Object key) {
        long x = ((long) key.hashCode()) * LONG_PHI_MAGIC;
        return x ^ (x >>> (Long.SIZE - CONTROL_BITS));
    }

    private boolean keysEqual(Object queriedKey, K internalKey) {
        return queriedKey.equals(internalKey);
    }

    private long firstSlotIndex(long hash) {
        return (hash >>> CONTROL_BITS) & (long) lookupMask;
    }

    private static long hashControlBits(long hash) {
        return hash & CONTROL_BITS_MASK;
    }

    private static long readControlsGroup(long[] controls, long groupFirstSlotIndex) {
        long controlsGroup =
                U.getLong(controls, ARRAY_LONG_BASE_OFFSET_AS_LONG + groupFirstSlotIndex);
        if (LITTLE_ENDIAN) {
            return controlsGroup;
        } else {
            return Long.reverseBytes(controlsGroup);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K> K readKey(Object[] table, long slotIndex) {
        return (K) U.getObject(table,
                ARRAY_OBJECT_BASE_OFFSET_AS_LONG + TABLE_SLOT_INDEX_SCALE * slotIndex);
    }

    @SuppressWarnings("unchecked")
    private static <V> V readValue(Object[] table, long slotIndex) {
        return (V) U.getObject(table, TABLE_VALUE_BASE_OFFSET + TABLE_SLOT_INDEX_SCALE * slotIndex);
    }

    private static void writeValue(Object[] table, long slotIndex, Object value) {
        U.putObject(table, TABLE_VALUE_BASE_OFFSET + TABLE_SLOT_INDEX_SCALE * slotIndex, value);
    }

    private static void writeEntry(Object[] table, long slotIndex, Object key, Object value) {
        long offset = ARRAY_OBJECT_BASE_OFFSET_AS_LONG + TABLE_SLOT_INDEX_SCALE * slotIndex;
        U.putObject(table, offset, key);
        U.putObject(table, offset + ARRAY_OBJECT_INDEX_SCALE_AS_LONG, value);
    }

    private void writeControlByte(long slotIndex, byte controlByte) {
        U.putByte(controls, ARRAY_LONG_BASE_OFFSET_AS_LONG + slotIndex, controlByte);
        if (slotIndex < GROUP_SLOTS) {
            long secondarySlotIndex = ((long) capacity()) + slotIndex;
            U.putByte(controls, ARRAY_LONG_BASE_OFFSET_AS_LONG + secondarySlotIndex, controlByte);
        }
    }

    private long addSlotIndex(long slotIndex, long addition) {
        return (slotIndex + addition) & (long) lookupMask;
    }

    private long lowestMatchIndex(long groupFirstSlotIndex, long groupBitMask) {
        return addSlotIndex(groupFirstSlotIndex,
                // [Replacing division with shift]
                (long) Long.numberOfTrailingZeros(groupBitMask) >>> BYTE_SIZE_DIVISION_SHIFT);
    }

    private long lowestMatchIndexFromTrailingZeros(long groupFirstSlotIndex, int trailingZeros) {
        return addSlotIndex(groupFirstSlotIndex,
                // [Replacing division with shift]
                (long) trailingZeros >>> BYTE_SIZE_DIVISION_SHIFT);
    }

    @Override
    public @Nullable V get(Object key) {
        Utils.checkNonNull(key);
        long hash = keyHashCode(key);
        long hashControlBits = hashControlBits(hash);
        long groupFirstSlotIndex = firstSlotIndex(hash);
        long[] controls = this.controls;
        Object[] table = this.table;
        for (long slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(controls, groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits);
                 bitMask != 0L;
                 bitMask = clearLowestSetBit(bitMask)) {
                long matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                K internalKey = readKey(table, matchSlotIndex);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    return readValue(table, matchSlotIndex);
                }
            }
            if (matchEmpty(controlsGroup) != 0) {
                return null;
            }
            // Quadratic probing:
            slotIndexStep += GROUP_SLOTS;
            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
        }
    }

    @Override
    public @Nullable V put(K key, V value) {
        Utils.checkNonNull(key);
        long hash = keyHashCode(key);
        return putInternal(key, hash, value);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }

    private @Nullable V putInternal(K key, long hash, V value) {
        long hashControlBits = hashControlBits(hash);
        long initialGroupFirstSlotIndex = firstSlotIndex(hash);
        long groupFirstSlotIndex = initialGroupFirstSlotIndex;
        long[] controls = this.controls;
        Object[] table = this.table;
        for (long slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(controls, groupFirstSlotIndex);
            for (long bitMask = match(controlsGroup, hashControlBits); ;) {
                // Positive likely branch: the following condition is in a separate if block rather
                // than the loop condition (as in all other operations: find(), compute(), etc.) in
                // order to make it positive and so it's more likely that JIT compiles the code with
                // an assumption that this branch is taken (i. e. that bitMask is 0), that is what
                // we really expect during Map.put() or putIfAbsent().
                // TODO check bytecode output of javac
                // TODO check if this even makes sense
                // TODO check a different approach, with a distinguished check and then do-while
                if (bitMask == 0) {
                    break;
                }
                long matchSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                K internalKey = readKey(table, matchSlotIndex);
                //noinspection ObjectEquality: identity comparision is intended
                boolean keysIdentical = internalKey == key;
                if (keysIdentical || keysEqual(key, internalKey)) {
                    V internalVal = readValue(table, matchSlotIndex);
                    writeValue(table, matchSlotIndex, value);
                    return internalVal;
                }
                bitMask = clearLowestSetBit(bitMask);
            }
            long emptyBitMask = matchEmpty(controlsGroup);
            if (emptyBitMask != 0) {
                int numDeletedSlots = this.numDeletedSlots;
                long insertionSlotIndex;
                // boolean as int: enables branchless operations in insert(). 0 = false, 1 = true
                int replacingEmptySlot;
                // Zero deleted slots fast path: an always-taken branch if entries are never removed
                // from the SmoothieMap.
                if (numDeletedSlots == 0) {
                    insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, emptyBitMask);
                    replacingEmptySlot = 1;
                } else if (slotIndexStep == 0) {
                    long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                    // Inlined lowestMatchIndex: computing the number of trailing zeros directly
                    // and then dividing by Byte.SIZE is inlined lowestMatchIndex(). It's inlined
                    // because the trailingZeros value is also needed for extractMatchingEmpty().
                    int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                    insertionSlotIndex =
                            lowestMatchIndexFromTrailingZeros(groupFirstSlotIndex, trailingZeros);
                    replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                } else {
                    // Deleted slot search heuristic:
                    // TODO instead of making a full-blown search, we can just save and check the
                    //  initial controls group, and if it contains no deleted controls, use the
                    //  same code as in `slotIndexStep == 0` branch above. This is very unlikely
                    //  that the probe chain is longer than 2 control groups, where the simplified
                    //  approach can be any worse because it might miss the opportunity to insert
                    //  the entry into a deleted slot in one of the middle (neither first nor last)
                    //  control groups in the probe chain

                    // Reusing local variables: it is better here to reuse existing local variables
                    // than introducing new variables, because of the risk of using a wrong
                    // variable. The old variables are not needed at this point because the outer
                    // `if` block ends with `return` unconditionally.
                    groupFirstSlotIndex = initialGroupFirstSlotIndex;
                    slotIndexStep = 0;
                    while (true) {
                        // Reusing another local variable.
                        controlsGroup = readControlsGroup(controls, groupFirstSlotIndex);
                        long emptyOrDeletedBitMask = matchEmptyOrDeleted(controlsGroup);
                        if (emptyOrDeletedBitMask != 0) {
                            // [Inlined lowestMatchIndex]
                            int trailingZeros = Long.numberOfTrailingZeros(emptyOrDeletedBitMask);
                            insertionSlotIndex = lowestMatchIndexFromTrailingZeros(
                                    groupFirstSlotIndex, trailingZeros);
                            replacingEmptySlot = extractMatchingEmpty(controlsGroup, trailingZeros);
                            break;
                        }
                        slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
                        groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
                    }
                }
                insert(key, hash, value, insertionSlotIndex, replacingEmptySlot);
                return null;
            }
            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
        }
    }

    private void insertDuringRehash(K key, V value) {
        long hash = keyHashCode(key);
        long hashControlBits = hashControlBits(hash);
        long groupFirstSlotIndex = firstSlotIndex(hash);
        long[] controls = this.controls;
        Object[] table = this.table;
        for (long slotIndexStep = 0; ;) {
            long controlsGroup = readControlsGroup(controls, groupFirstSlotIndex);
            long bitMask = matchEmpty(controlsGroup);
            if (bitMask != 0) {
                long insertionSlotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                writeControlByte(insertionSlotIndex, (byte) hashControlBits);
                writeEntry(table, insertionSlotIndex, key, value);
                return;
            }
            slotIndexStep += GROUP_SLOTS; // [Quadratic probing]
            groupFirstSlotIndex = addSlotIndex(groupFirstSlotIndex, slotIndexStep);
        }
    }

    /**
     * @param replacingEmptySlot must be 1 if the slot being filled had {@link #EMPTY_CONTROL} value
     * before, 0 if {@link #DELETED_CONTROL}.
     */
    @HotPath
    private void insert(K key, long hash, V value, long insertionSlotIndex,
            int replacingEmptySlot) {
        int maxNumNonEmptySlotsBeforeRehash = maxNumNonEmptySlotsBeforeRehash();
        boolean enoughNonEmptySlots = size + numDeletedSlots + replacingEmptySlot <=
                maxNumNonEmptySlotsBeforeRehash;
        if (enoughNonEmptySlots) { // [Positive likely branch]
            doInsert(key, hash, value, insertionSlotIndex, replacingEmptySlot);
        } else {
            rehash();
            if (putInternal(key, hash, value) != null) {
                throw new ConcurrentModificationException();
            }
        }
    }

    private void doInsert(K key, long hash, V value, long insertionSlotIndex,
            int replacingEmptySlot) {
        writeControlByte(insertionSlotIndex, (byte) hashControlBits(hash));
        writeEntry(table, insertionSlotIndex, key, value);
        int replacingDeletedSlot = 1 - replacingEmptySlot;
        numDeletedSlots -= replacingDeletedSlot;
        size++;
        modCount++;
    }

    private void rehash() {
        int modCount = this.modCount;

        int oldCapacity = capacity();
        long[] oldControls = controls;
        Object[] oldTable = table;
        init(oldCapacity * 2);

        for (int groupIndex = 0; groupIndex < oldCapacity / GROUP_SLOTS; groupIndex++) {
            long controlsGroup = oldControls[groupIndex];
            long groupFirstSlotIndex = ((long) groupIndex) * GROUP_SLOTS;
            for (long bitMask = matchFull(controlsGroup);
                 bitMask != 0;
                 bitMask = clearLowestSetBit(bitMask)) {
                long slotIndex = lowestMatchIndex(groupFirstSlotIndex, bitMask);
                K key = readKey(oldTable, slotIndex);
                V value = readValue(oldTable, slotIndex);
                insertDuringRehash(key, value);
            }
        }

        Utils.checkModCount(modCount, this.modCount);
    }
}
