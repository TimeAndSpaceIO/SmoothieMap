/* if Tracking hashCodeDistribution */
package io.timeandspace.smoothie;

import one.util.streamex.StreamEx;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.timeandspace.smoothie.PoorHashCodeDistributionOccasion.Type.TOO_LARGE_INFLATED_SEGMENT;
import static io.timeandspace.smoothie.PoorHashCodeDistributionOccasion.Type.TOO_MANY_SKEWED_SEGMENT_SPLITS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;


final class PoorHashCodeDistributionDetectionTest {

    private static Stream<Float> generateMaxOccasionProbabilities() {
        return StreamEx
                .iterate(0.2f, prob -> prob *= 0.5f)
                .takeWhile(prob -> prob >= 0.00001f);
    }

    @ParameterizedTest
    @MethodSource("generateMaxOccasionProbabilities")
    void testTooLargeInflatedSegmentReported(float maxOccasionProbability) {
        @Nullable PoorHashCodeDistributionOccasion.Type[] reported =
                new PoorHashCodeDistributionOccasion.Type[] {null};
        Consumer<PoorHashCodeDistributionOccasion<BadHashCodeObject, Object>> reportingAction =
                poorHashCodeDistributionOccasion -> {
                    Map<String, Object> debugInfo =
                            poorHashCodeDistributionOccasion.assembleDebugInformation();
                    assertThat((Double) debugInfo.get("occasionProbability"),
                            lessThanOrEqualTo((double) maxOccasionProbability));
                    reported[0] = poorHashCodeDistributionOccasion.getType();
                };
        SmoothieMap<BadHashCodeObject, Object> smoothieMap = SmoothieMap
                .<BadHashCodeObject, Object>newBuilder()
                .reportPoorHashCodeDistribution(maxOccasionProbability, reportingAction)
                .build();
        SplittableRandom r =
                new SplittableRandom((long) Float.floatToRawIntBits(maxOccasionProbability));
        cadenceLoop:
        for (int badHashCodeCadence = 1000; badHashCodeCadence >= 1; badHashCodeCadence /= 10) {
            reported[0] = null;
            for (int i = 0; i < 10000; i++) {
                int hashCode = i % badHashCodeCadence == 0 ? 0 : r.nextInt();
                smoothieMap.put(new BadHashCodeObject(hashCode), new Object());
                if (reported[0] != null) {
                    if (reported[0] == TOO_LARGE_INFLATED_SEGMENT) {
                        return;
                    }
                    continue cadenceLoop;
                }
            }
        }
        // Not reported too large inflated segment at any cadence of bad hash codes, including 1
        // (that is, all hash codes are colliding).
        fail();
    }

    @ParameterizedTest
    @MethodSource("generateMaxOccasionProbabilities")
    void checkHashCodeBitCorrelationDetected(float maxOccasionProbability) {
        boolean[] reported = new boolean[] {false};
        Consumer<PoorHashCodeDistributionOccasion<BadHashCodeObject, Object>> reportingAction =
                poorHashCodeDistributionOccasion -> {
                    assertEquals(TOO_MANY_SKEWED_SEGMENT_SPLITS,
                            poorHashCodeDistributionOccasion.getType());
                    Map<String, Object> debugInfo =
                            poorHashCodeDistributionOccasion.assembleDebugInformation();
                    assertThat((Double) debugInfo.get("occasionProbability"),
                            lessThanOrEqualTo((double) maxOccasionProbability));
                    reported[0] = true;
                };
        SmoothieMap<BadHashCodeObject, Object> smoothieMap = SmoothieMap
                .<BadHashCodeObject, Object>newBuilder()
                .reportPoorHashCodeDistribution(maxOccasionProbability, reportingAction)
                .build();
        SplittableRandom r =
                new SplittableRandom((long) Float.floatToRawIntBits(maxOccasionProbability));
        int badHashCodeCadence = 10;
        for (int i = 0; i < 1_000_000; i++) {
            int hashCode = r.nextInt();
            if (i % badHashCodeCadence == 0) {
                if ((hashCode & (1 << SmoothieMap.Segment.HASH__BASE_GROUP_INDEX_BITS - 1)) != 0) {
                    hashCode |= 1 << SmoothieMap.HASH__SEGMENT_LOOKUP_SHIFT;
                } else {
                    hashCode &= ~(1 << SmoothieMap.HASH__SEGMENT_LOOKUP_SHIFT);
                }
            }
            smoothieMap.put(new BadHashCodeObject(hashCode), new Object());
            if (reported[0]) {
                return;
            }
        }
        // Not reported too many skewed segment splits.
        fail();
    }

    static class BadHashCodeObject {
        private final int hashCode;

        BadHashCodeObject(int hashCode) {
            this.hashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
