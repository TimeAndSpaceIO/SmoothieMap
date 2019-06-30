package io.timeandspace.smoothie;

import org.junit.jupiter.api.Test;

import static io.timeandspace.smoothie.HashTable.fullDataGroupForTesting;
import static io.timeandspace.smoothie.HashTable.match;
import static org.junit.Assert.assertEquals;

final class HashTableTest {

    @Test
    void testMatchJavadocExample() {
        assertEquals(0x80800000L, match(0x1716151413121110L, 0x12L, fullDataGroupForTesting()));
    }
}
