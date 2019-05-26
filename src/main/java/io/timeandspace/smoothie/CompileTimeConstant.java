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
public @interface CompileTimeConstant {
}
