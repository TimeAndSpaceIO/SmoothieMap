/* if Interleaved segments */
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

import java.util.Arrays;

import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_BASE_OFFSET_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.ARRAY_INT_INDEX_SCALE_AS_LONG;
import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.Utils.duplicateArray;
import static io.timeandspace.smoothie.Utils.verifyEqual;
import static io.timeandspace.smoothie.Utils.verifyIsPowerOfTwo;

/**
 * This class is a collection of static utility methods to work with a "bit set" structure, similar
 * to {@link java.util.BitSet} but [Avoid normal array access] in {@link #getValue} method which
 * is on the {@link HotPath}.
 */
final class IsFullCapacitySegmentBitSet {

    /** = Integer.numberOfTrailingZeros({@link Integer#SIZE}). */
    @CompileTimeConstant
    private static final int INDEX_SHIFT = 5;
    private static final int BIT_INDEX_MASK = Integer.SIZE - 1;

    static {
        verifyEqual(INDEX_SHIFT, Integer.numberOfTrailingZeros(Integer.SIZE));
    }

    static int[] allocate(int segmentsArrayLength) {
        return new int[bitSetArrayLengthFromSegmentsArrayLength(segmentsArrayLength)];
    }

    @AmortizedPerSegment
    static int bitSetArrayLengthFromSegmentsArrayLength(int segmentsArrayLength) {
        verifyIsPowerOfTwo(segmentsArrayLength, "segmentsArrayLength");
        return (segmentsArrayLength + Integer.SIZE - 1) / Integer.SIZE;
    }

    static void setAll(int[] bitSet) {
        int allSetWord = -1;
        Arrays.fill(bitSet, allSetWord);
    }

    /**
     * Returns an int[] array which is a IsFullCapacitySegmentBitSet whose value at every index in
     * the 0..newSegmentsArrayLength - 1 range is equal to the value in the given bitSet at
     * index % oldSegmentsArrayLength.
     *
     * If both oldSegmentsArrayLength and newSegmentsArrayLength are less than {@link
     * Integer#SIZE} then this method may update and return bitSet object itself back.
     */
    @AmortizedPerOrder
    static int[] duplicate(int[] bitSet, int oldSegmentsArrayLength, int newSegmentsArrayLength) {
        verifyIsPowerOfTwo(oldSegmentsArrayLength, "oldSegmentsArrayLength");
        verifyIsPowerOfTwo(newSegmentsArrayLength, "newSegmentsArrayLength");
        int newArrayLength = Math.max(1, newSegmentsArrayLength >>> INDEX_SHIFT);
        int[] newBitSet;
        if (newSegmentsArrayLength <= Integer.SIZE) {
            // If newSegmentsArrayLength (and therefore oldSegmentsArrayLength) fit within a single
            // word, updating and returning the existing array to avoid unnecessary allocations.
            newBitSet = bitSet;
        } else {
            newBitSet = new int[newArrayLength];
            int oldArrayLength = Math.max(1, oldSegmentsArrayLength >>> INDEX_SHIFT);
            System.arraycopy(bitSet, 0, newBitSet, 0, oldArrayLength);
        }
        int numBitsGrowthLimitWithinWord = Math.min(newSegmentsArrayLength, Integer.SIZE);
        int numFilledBits = oldSegmentsArrayLength;
        for (; numFilledBits < numBitsGrowthLimitWithinWord; numFilledBits *= 2) {
            int word = newBitSet[0];
            int mask = ((1 << numFilledBits) - 1) << numFilledBits;
            // `& ~mask` clears the temporary values in the high bits within a sole word.
            word = (word & ~mask) | (word << numFilledBits);
            newBitSet[0] = word;
        }
        if (newSegmentsArrayLength > Integer.SIZE) {
            int filledLowPartLength = numFilledBits >>> INDEX_SHIFT;
            duplicateArray(newBitSet, newArrayLength, filledLowPartLength);
        }
        return newBitSet;
    }

    @HotPath
    static int getValue(Object bitSet, long index) {
        // [Replacing division with shift]
        long wordIndex = index >>> INDEX_SHIFT;
        long bitIndex = index & BIT_INDEX_MASK;
        // [Avoid normal array access]
        long offset = ARRAY_INT_BASE_OFFSET_AS_LONG + ARRAY_INT_INDEX_SCALE_AS_LONG * wordIndex;
        int word = U.getInt(bitSet, offset);
        return (word >>> bitIndex) & 1;
    }

    /** @param value must be 0 or 1 */
    @AmortizedPerSegment
    static void setValue(int[] bitSet, int index, int value) {
        // [Replacing division with shift]
        int wordIndex = index >>> INDEX_SHIFT;
        int bitIndex = index & BIT_INDEX_MASK;
        // [Not avoiding normal array access]
        int word = bitSet[wordIndex];
        word = (word & ~(1 << bitIndex)) | (value << bitIndex);
        bitSet[wordIndex] = word;
    }

    private IsFullCapacitySegmentBitSet() {}
}
