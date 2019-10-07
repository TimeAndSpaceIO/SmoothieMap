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

/**
 * OptimizationObjectives correspond to {@link SmoothieMap}'s <i>modes of operation</i> which could
 * be configured via {@link SmoothieMapBuilder#optimizeFor} method.
 */
public enum OptimizationObjective {
    /**
     * In the "low-garbage" mode, {@link SmoothieMap} generates very little garbage (that is, heap
     * objects that later become unreachable and need to be swept by the GC), either during the
     * growth phase or the shrinkage phase (when a map reduces in size after the peak growth).
     *
     * @implSpec Configuring {@link SmoothieMapBuilder#optimizeFor
     * smoothieMapBuilder.optimizeFor(LOW_GARBAGE)} includes <i>not</i> {@linkplain
     * SmoothieMapBuilder#allocateIntermediateCapacitySegments(boolean) allocating
     * intermediate-capacity segments} (nor, consequently, {@linkplain
     * SmoothieMapBuilder#splitBetweenTwoNewSegments(boolean) splitting between two new segments})
     * and <i>not</i> {@linkplain SmoothieMapBuilder#doShrink(boolean) shrinking SmoothieMap
     * automatically} when it reduces in size.
     */
    LOW_GARBAGE,

    /**
     * In the "footprint" mode, {@link SmoothieMap} has the lowest possible footprint per entry and
     * the lowest variability of the footprint at different SmoothieMap's sizes.
     *
     * @implSpec Configuring {@link SmoothieMapBuilder#optimizeFor
     * smoothieMapBuilder.optimizeFor(FOOTPRINT)} includes {@linkplain
     * SmoothieMapBuilder#allocateIntermediateCapacitySegments(boolean) allocating
     * intermediate-capacity segments}, {@linkplain
     * SmoothieMapBuilder#splitBetweenTwoNewSegments(boolean) splitting between two new segments},
     * and {@linkplain SmoothieMapBuilder#doShrink(boolean) automatic shrinking} when a SmoothieMap
     * reduces in size.
     */
    FOOTPRINT
}
