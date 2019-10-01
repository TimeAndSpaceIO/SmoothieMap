/*
 *    Copyright (C) Smoothie Map Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.openhft.smoothie;

import com.google.common.collect.testing.MapTestSuiteBuilder;
import com.google.common.collect.testing.SampleElements;
import com.google.common.collect.testing.TestMapGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import junit.framework.Test;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.testing.features.MapFeature.*;


public class GuavaTest extends TestCase {

    public static Test suite() {
        return MapTestSuiteBuilder.using(new TestGenerator())
                .named("Heap Chronicle Map tests")
                .withFeatures(GENERAL_PURPOSE)
                .withFeatures(CollectionFeature.REMOVE_OPERATIONS)
                .withFeatures(CollectionSize.ANY)
                .withFeatures(ALLOWS_ANY_NULL_QUERIES)
                .withFeatures(FAILS_FAST_ON_CONCURRENT_MODIFICATION)
                .withFeatures(ALLOWS_NULL_KEYS, ALLOWS_NULL_VALUES)
                .withFeatures(CollectionFeature.SERIALIZABLE)
                .createTestSuite();
    }
    
    static class TestGenerator implements TestMapGenerator<String, String> {

        public String[] createKeyArray(int length) {
            return new String[length];
        }

        @Override
        public String[] createValueArray(int length) {
            return new String[length];
        }

        @Override
        public SampleElements<Map.Entry<String, String>> samples() {
            return SampleElements.mapEntries(
                    new SampleElements<>("k0", "k1", "k2", "k3", "k4"),
                    new SampleElements<>("v0", "v1", "v2", "v3", "v4")
            );
        }

        @Override
        public Map<String, String> create(Object... objects) {
            Map<String, String> map = new SmoothieMap<>();
            for (Object obj : objects) {
                Map.Entry e = (Map.Entry) obj;
                map.put((String) e.getKey(), (String) e.getValue());
            }
            return map;
        }

        @Override
        public Map.Entry<String, String>[] createArray(int length) {
            return new Map.Entry[length];
        }

        @Override
        public Iterable<Map.Entry<String, String>> order(
                List<Map.Entry<String, String>> insertionOrder) {
            return insertionOrder;
        }
    }
}
