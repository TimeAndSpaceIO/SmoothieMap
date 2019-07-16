package io.timeandspace.smoothie;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

public class SwissTableGrowTest {

    private SwissTable<Integer, Integer> map = new SwissTable<>();
    private List<Integer> keys = new ArrayList<>();
    private Random random = new Random(0);

    @Before
    public void fillMap() {
        for (int i = 0; i < 20_000_000; i++) {
            int key = random.nextInt();
            keys.add(key);
            Integer res = map.put(key, key);
            if (map.size() > keys.size()) {
                throw new AssertionError();
            }
            assertTrue(res == null || res == key);
        }
    }

    @Test
    public void growTest() {
        verifyAllKeys();
    }

    private void verifyAllKeys() {
        for (int i = 0; i < keys.size(); i++) {
            Integer key = keys.get(i);
            if (!key.equals(map.get(key))) {
                throw new AssertionError("" + i);
            }
        }
    }
}
