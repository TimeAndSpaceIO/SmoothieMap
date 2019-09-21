package io.timeandspace.smoothie;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

class SwissTableGrowTest {

    private static final SwissTable<Integer, Integer> map = new SwissTable<>();
    private static final List<Integer> keys = new ArrayList<>();
    private static final Random random = new Random(0);

    @BeforeAll
    static void fillMap() {
        for (int i = 0; i < 20_000_000; i++) {
            int key = random.nextInt();
            keys.add(key);
            @Nullable Integer res = map.put(key, key);
            if (map.size() > keys.size()) {
                throw new AssertionError();
            }
            assertTrue(res == null || res == key);
        }
    }

    @Test
    void growTest() {
        verifyAllKeys();
    }

    private static void verifyAllKeys() {
        for (int i = 0; i < keys.size(); i++) {
            Integer key = keys.get(i);
            if (!key.equals(map.get(key))) {
                throw new AssertionError("" + i);
            }
        }
    }
}
