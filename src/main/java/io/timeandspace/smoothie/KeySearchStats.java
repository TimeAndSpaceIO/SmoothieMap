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

import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;

final class KeySearchStats {

    private final long[] numSearchesPerCollisionChainGroupLength = new long[HASH_TABLE_GROUPS];
    private final long[] numSearchesPerNumCollisionKeyComparisons =
            new long[SEGMENT_MAX_ALLOC_CAPACITY];

    void aggregate(int collisionChainGroupLength, int numCollisionKeyComparisons) {
        numSearchesPerCollisionChainGroupLength[collisionChainGroupLength]++;
        numSearchesPerNumCollisionKeyComparisons[numCollisionKeyComparisons]++;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        OrdinarySegmentStats.appendMetricStats(sb, "searches",
                numSearchesPerCollisionChainGroupLength, "collision chain group length");
        OrdinarySegmentStats.appendMetricStats(sb, "searches",
                numSearchesPerNumCollisionKeyComparisons, "num collision key comparisons");
        return sb.toString();
    }
}
