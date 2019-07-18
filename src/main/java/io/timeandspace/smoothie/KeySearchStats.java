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
