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
 * Annotation for constants that must be declared as literals or derived from other compile-time
 * constants using arithmetic operations, but not method calls. JIT can bake compile-time constants
 * into generated machine code.
 *
 * Currently, this is just an informational annotation, the compile-time property of the annotated
 * fields (and that they are static) is not enforced.
 * TODO check whether it's possible to enforce that via IntelliJ's Structural Search
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
@interface CompileTimeConstant {
}
