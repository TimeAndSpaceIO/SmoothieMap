package io.timeandspace.smoothie;

import io.timeandspace.collect.Equivalence;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"StringOperationCanBeSimplified", "AutoBoxing", "UnnecessaryBoxing"})
final class CustomEquivalencesTest {

    private static void doTestCustomKeyEquivalence(SmoothieMap<String, Integer> m,
            String key1, String equalToKey1, String key2, String key3) {
        m.put(key1, 1);
        m.put(key2, 2);
        assertEquals(2, m.size());
        assertEquals(1, m.get(key1));
        assertEquals(1, m.get(equalToKey1));
        assertEquals(2, m.get(key2));
        assertTrue(m.containsKey(equalToKey1));
        assertTrue(m.containsEntry(equalToKey1, 1));
        assertNull(m.get(key3));
        assertNull(m.remove(key3));
        assertNull(m.replace(key3, 1));
        assertFalse(m.replace(key3, 1, 2));
        boolean[] visitedLambda = new boolean[] {false};
        assertNull(m.computeIfPresent(key3, (k, v) -> {
            visitedLambda[0] = true;
            return 3;
        }));
        assertFalse(visitedLambda[0]);
        assertNull(m.computeIfPresent(equalToKey1, (k, v) -> {
            visitedLambda[0] = true;
            return null;
        }));
        assertTrue(visitedLambda[0]);
        assertNull(m.putIfAbsent(key1, 1));
        assertNull(m.compute(key3, (k, v) -> {
            assertNull(v);
            return null;
        }));
        assertEquals(2, m.compute(equalToKey1, (k, v) -> {
            assertEquals(1, v);
            return 2;
        }));
    }

    @Test
    void testCustomKeyEquivalence() {
        SmoothieMap<String, Integer> m = SmoothieMap
                .<String, Integer>newBuilder().keyEquivalence(Equivalence.identity()).build();
        String key1 = new String("");
        String key2 = new String("");
        String key3 = "";
        doTestCustomKeyEquivalence(m, key1, key1, key2, key3);
    }

    @Test
    void testCustomHashFunction() {
        SmoothieMap<String, Integer> m = SmoothieMap.<String, Integer>newBuilder()
                .keyHashFunction(LongHashFunction.xx()::hashChars).build();
        doTestCustomKeyEquivalence(m, "key1", new String("key1"), "key2", "key3");
    }

    @Test
    void testCustomValueEquivalence() {
        SmoothieMap<String, Integer> m = SmoothieMap
                .<String, Integer>newBuilder().valueEquivalence(Equivalence.identity()).build();
        doTestCustomValueEquivalence(m);
    }

    private static void doTestCustomValueEquivalence(SmoothieMap<String, Integer> m) {
        Integer v1 = new Integer(1);
        Integer v2 = new Integer(1);
        String k = "k";
        m.put(k, v1);
        assertTrue(m.containsEntry(k, v1));
        assertFalse(m.containsEntry(k, v2));
        assertTrue(m.containsValue(v1));
        assertFalse(m.containsValue(v2));
        assertTrue(m.replace(k, v1, v2));
        assertFalse(m.replace(k, v1, v2));
        assertFalse(m.remove(k, v1));
        assertTrue(m.remove(k, v2));
    }

    @Test
    void testMapWithCustomKeyAndValueEquivalences() {
        SmoothieMap<String, Integer> m = SmoothieMap
                .<String, Integer>newBuilder()
                .keyEquivalence(Equivalence.caseInsensitive())
                .valueEquivalence(Equivalence.identity())
                .build();
        doTestCustomKeyEquivalence(m, "k1", "K1", "k2", "k3");
        m.clear();
        doTestCustomValueEquivalence(m);
    }
}
