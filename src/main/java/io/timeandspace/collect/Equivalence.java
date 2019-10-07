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

package io.timeandspace.collect;

import com.google.auto.value.AutoValue;
import io.timeandspace.collect.map.ObjObjMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A strategy for determining whether two instances are considered equivalent.
 *
 * <p>This class is inspired and very similar to
 * <a href="https://guava.dev/releases/27.1-jre/api/docs/com/google/common/base/Equivalence.html">
 * Guava's {@code Equivalence}</a>, with one notable difference: this {@code Equivalence} forces
 * the actual implementation to override {@link #equals(Object)} and {@link #hashCode()}. Notice
 * these are {@code Equivalence}'s own equals and hashCode, not the strategy
 * {@link #equivalent(Object, Object)} and {@link #hash(Object)} methods. It is needed because,
 * for example, {@link ObjCollection}'s equality depends on {@code Equivalence} equality.
 *
 * <p>In most cases, when {@code Equivalence} is stateless, you can extend
 * {@link StatelessEquivalence} not to bother with implementing these methods. See examples
 * in the documentation to {@linkplain #identity() identity} and
 * {@linkplain #caseInsensitive() case insensitive} equivalences.
 *
 * @param <T> type of objects compared by this equivalence
 */
public abstract class Equivalence<T> {

    /**
     * Returns the default, built-in equivalence in Java, driven by {@link Object#equals(Object)}
     * and {@link Object#hashCode()} methods.
     *
     * @param <T> type of objects, needed to compare. {@link Object#equals} could be applied to
     *        object of any type, so there aren't any constraints over the generic type parameter.
     * @return the built-in Java equality
     */
    @SuppressWarnings("unchecked")
    public static <T> Equivalence<T> defaultEquality() {
        return (Equivalence<T>) DEFAULT_EQUALITY;
    }

    /**
     * Returns the equivalence that uses {@code ==} to compare objects and
     * {@link System#identityHashCode(Object)} to compute the hash code.
     * {@link Equivalence#nullableEquivalent} returns {@code true} if {@code a == b}, including
     * in the case when {@code a} and {@code b} are both {@code null}.
     *
     * <p>This equivalence could be implemented as follows:
     * <pre><code>
     * final class Identity extends StatelessEquivalence&lt;Object&gt; {
     *     static final Identity INSTANCE = new Identity();
     *     private Identity() {}
     *     &#064;Override
     *     public boolean equivalent(Object a, Object b) {
     *         return a == b;
     *     }
     *     &#064;Override
     *     public int hash(Object t) {
     *         return System.identityHashCode(t);
     *     }
     * }
     * </code></pre>
     *
     * @param <T> type of objects, needed to compare. Identity check could be applied to objects
     *        of any type, so there aren't any constraints over the generic type parameter.
     * @return the identity equivalence
     */
    @SuppressWarnings("unchecked")
    public static <T> Equivalence<T> identity() {
        return (Equivalence<T>) IDENTITY;
    }

    /**
     * Returns the equivalence that compares {@link CharSequence}s by their contents.
     *
     * <p>This equivalence could be implemented as follows (actual implementation, of cause,
     * is more efficient and doesn't allocate garbage objects):
     * <pre><code>
     * final class CharSequenceEquivalence extends StatelessEquivalence&lt;CharSequence&gt; {
     *     static final CharSequenceEquivalence INSTANCE = new CharSequenceEquivalence();
     *     private CharSequenceEquivalence() {}
     *     &#064;Override
     *     public boolean equivalent(CharSequence a, CharSequence b) {
     *         return a.toString().equals(b.toString());
     *     }
     *     &#064;Override
     *     public int hash(CharSequence cs) {
     *         return cs.toString().hashCode();
     *     }
     * }</code></pre>
     *
     * @return the {@link CharSequence} equivalence
     */
    @SuppressWarnings("unused") // Public API; TODO use in tests
    public static Equivalence<CharSequence> charSequence() {
        return CHAR_SEQUENCE;
    }

    /**
     * Returns the {@link String} equivalence that uses {@link String#equalsIgnoreCase} to compare
     * strings.
     *
     * <p>This equivalence could be implemented as follows:
     * <pre><code>
     * final class CaseInsensitive extends StatelessEquivalence&lt;String&gt; {
     *     static final CaseInsensitive INSTANCE = new CaseInsensitive();
     *     private CaseInsensitive() {}
     *     &#064;Override
     *     public boolean equivalent(String a, String b) {
     *         return a.equalsIgnoreCase(b);
     *     }
     *     &#064;Override
     *     public int hash(String s) {
     *         return s.toLowerCase().hashCode();
     *     }
     * }
     * </code></pre>
     *
     * @return the case-insensitive {@link String} equivalence
     */
    public static Equivalence<String> caseInsensitive() {
        return CASE_INSENSITIVE;
    }

    /**
     * Returns a {@link java.util.Map.Entry} equivalence for the given key and value equivalences.
     *
     * @param keyEquivalence the entry key equivalence
     * @param valueEquivalence the entry value equivalence
     * @param <K> the entry key type
     * @param <V> the entry value type
     * @return a {@link java.util.Map.Entry} equivalence for the given key and value equivalences
     */
    @SuppressWarnings("unused") // Public API; TODO use in tests
    public static <K, V> Equivalence<Map.Entry<K, V>> entryEquivalence(
            Equivalence<K> keyEquivalence, Equivalence<V> valueEquivalence) {
        if (keyEquivalence.equals(defaultEquality()) && valueEquivalence.equals(defaultEquality()))
            return defaultEquality();
        return new AutoValue_Equivalence_EntryEquivalence<>(keyEquivalence, valueEquivalence);
    }

    @AutoValue
    static abstract class EntryEquivalence<K, V> extends Equivalence<Map.Entry<K, V>> {

        abstract Equivalence<K> keyEquivalence();

        abstract Equivalence<V> valueEquivalence();

        @Override
        public boolean equivalent(Map.Entry<K, V> a, Map.Entry<K, V> b) {
            //noinspection ObjectEquality: identity comparison is intended
            boolean entriesIdentical = a == b;
            return entriesIdentical ||
                    (keyEquivalence().nullableEquivalent(a.getKey(), b.getKey()) &&
                            valueEquivalence().nullableEquivalent(a.getValue(), b.getValue()));
        }

        @Override
        public int hash(Map.Entry<K, V> entry) {
            return keyEquivalence().nullableHash(entry.getKey()) ^
                    valueEquivalence().nullableHash(entry.getValue());
        }
    }

    private static final Equivalence<Object> DEFAULT_EQUALITY = new DefaultEquality();

    private static class DefaultEquality extends StatelessEquivalence<Object> {

        @Override
        public boolean equivalent(Object a, Object b) {
            return a.equals(b);
        }

        @Override
        public int hash(Object o) {
            return o.hashCode();
        }
    }

    private static final Equivalence<Object> IDENTITY = new Identity();

    private static class Identity extends StatelessEquivalence<Object> {

        @Override
        public boolean nullableEquivalent(@Nullable Object a, @Nullable Object b) {
            //noinspection ObjectEquality: identity comparison is intended
            return a == b;
        }

        @Override
        public boolean equivalent(Object a, Object b) {
            //noinspection ObjectEquality: identity comparison is intended
            return a == b;
        }

        @Override
        public int nullableHash(@Nullable Object o) {
            return System.identityHashCode(o);
        }

        @Override
        public int hash(Object o) {
            return System.identityHashCode(o);
        }
    }

    private static final Equivalence<String> CASE_INSENSITIVE = new CaseInsensitive();

    private static class CaseInsensitive extends StatelessEquivalence<String> {

        @Override
        public boolean equivalent(String a, String b) {
            return a.equalsIgnoreCase(b);
        }

        @Override
        public int hash(String s) {
            return s.toLowerCase().hashCode();
        }
    }

    private static final Equivalence<CharSequence> CHAR_SEQUENCE = new OfCharSequence();

    private static class OfCharSequence extends StatelessEquivalence<CharSequence> {

        @Override
        public boolean equivalent(CharSequence a, CharSequence b) {
            // Intentionally trying equals to return earlier when `a` and `b` are identical or both
            // are Strings.
            @SuppressWarnings("UndefinedEquals")
            boolean charSequencesEqual = a.equals(b);
            if (charSequencesEqual)
                return true;
            if (a instanceof String)
                return ((String) a).contentEquals(b);
            if (b instanceof String)
                return ((String) b).contentEquals(a);
            int len = a.length();
            if (len != b.length())
                return false;
            for (int i = 0; i < len; i++) {
                if ((int) a.charAt(i) != (int) b.charAt(i))
                    return false;
            }
            return true;
        }

        @Override
        public int hash(CharSequence cs) {
            if (cs instanceof String)
                return cs.hashCode();
            int h = 0;
            for (int i = 0, len = cs.length(); i < len; i++) {
                h = 31 * h + (int) cs.charAt(i);
            }
            return h;
        }
    }

    /**
     * Constructor for use by subclasses.
     */
    protected Equivalence() {}

    /**
     * Returns {@code true} if {@code a} and {@code b} are considered equivalent,
     * {@code false} otherwise. {@code a} and {@code b} both might be {@code null}.
     *
     * <p>If the implementation overrides this method, it <em>must</em> ensure that it returns
     * {@code true} if both the given objects are nulls and {@code false}, if only one of them
     * is {@code null}. If both {@code a} and {@code b} are non-null, this method should perform
     * just the same as {@link #equivalent(Object, Object)} method does.
     *
     * @param a the first object to compare
     * @param b the second object to compare
     * @return {@code true} if {@code a} and {@code b} are considered equivalent,
     *         {@code false} otherwise
     */
    public boolean nullableEquivalent(@Nullable T a, @Nullable T b) {
        //noinspection ObjectEquality: identity comparison is intended
        boolean objectsIdentical = a == b;
        return objectsIdentical || (a != null && b != null && equivalent(a, b));
    }

    /**
     * Returns {@code true} if {@code a} and {@code b} are considered equivalent,
     * {@code false} otherwise. {@code a} and {@code b} are assumed to be non-null.
     *
     * <p>This method implements an equivalence relation on object references:
     *
     * <ul>
     * <li>It is <i>reflexive</i>: for any reference {@code x}, {@code equivalent(x, x)}
     *     returns {@code true}.
     * <li>It is <i>symmetric</i>: for any references {@code x} and {@code y},
     *     {@code equivalent(x, y) == equivalent(y, x)}.
     * <li>It is <i>transitive</i>: for any references {@code x}, {@code y}, and {@code z},
     *     if {@code equivalent(x, y)} returns {@code true} and {@code equivalent(y, z)} returns
     *     {@code true}, then {@code equivalent(x, z)} returns {@code true}.
     * <li>It is <i>consistent</i>: for any references {@code x} and {@code y}, multiple invocations
     *     of {@code equivalent(x, y)} consistently return {@code true} or consistently return
     *     {@code false} (provided that neither {@code x} nor {@code y} is modified).
     * </ul>
     *
     * <p>This method is called by {@link #nullableEquivalent(Object, Object)}.
     *
     * @param a the first object to compare
     * @param b the second object to compare
     * @return {@code true} if {@code a} and {@code b} are considered equivalent,
     *         {@code false} otherwise
     */
    public abstract boolean equivalent(T a, T b);

    /**
     * Returns a hash code for the given object. The {@code t} object might be {@code null}.
     *
     * <p>If the implementation overrides this method, it <em>must</em> ensure that it returns
     * {@code 0} if the given object is {@code null}. Otherwise this method should perform just
     * the same as {@link #hash(Object)} method does.
     *
     * @param t the object to compute hash code for
     * @return a hash code for the given object
     */
    public int nullableHash(@Nullable T t) {
        return t != null ? hash(t) : 0;
    }

    /**
     * Returns a hash code for the given object. The {@code t} object is assumed to be non-null.
     *
     * <p>This method has the following properties:
     * <ul>
     * <li>It is <i>consistent</i>: for any reference {@code x}, multiple invocations of
     *     {@code hash(x)} consistently return the same value provided {@code x} remains unchanged
     *     according to the definition of the equivalence. The hash need not remain consistent from
     *     one execution of an application to another execution of the same application.
     * <li>It is <i>distributable across equivalence</i>: for any references {@code x} and
     *     {@code y}, if {@code equivalent(x, y)}, then {@code hash(x) == hash(y)}. It is <i>not</i>
     *     necessary that the hash be distributable across <i>inequivalence</i>.
     *     If {@code equivalence(x, y)} is false, {@code hash(x) == hash(y)} may still be true.
     * </ul>
     *
     * <p>This method is called by {@link #nullableHash(Object)}.
     *
     * @param t the object to compute hash code for
     * @return a hash code for the given object
     */
    public abstract int hash(T t);

    /**
     * {@inheritDoc}
     *
     * <p>This method is made {@code abstract} to force the Equivalence implementation to override
     * it. This is needed because, for example, {@link ObjObjMap}'s equality depends on the equality
     * of key and value {@code Equivalence} objects, configured for a map.
     */
    @Override
    public abstract boolean equals(Object o);

    /**
     * {@inheritDoc}
     *
     * <p>This method is made {@code abstract} to force the Equivalence implementation to override
     * it. This is needed because {@link #equals(Object)} is also needed to be overridden.
     */
    @Override
    public abstract int hashCode();
}
