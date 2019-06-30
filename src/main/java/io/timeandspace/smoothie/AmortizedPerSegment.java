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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For methods that are called approximately once per every {@link
 * SmoothieMap.Segment#SEGMENT_MAX_ALLOC_CAPACITY} / 2 .. SEGMENT_MAX_ALLOC_CAPACITY (i. e. 24..48)
 * point queries to a SmoothieMap or entries during iteration/stream operations.
 *
 * @see RarelyCalledAmortizedPerSegment
 * @see HotPath
 * @see ColdPath
 * @see AmortizedPerOrder
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@interface AmortizedPerSegment {
}
