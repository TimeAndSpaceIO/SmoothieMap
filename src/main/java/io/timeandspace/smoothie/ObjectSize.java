package io.timeandspace.smoothie;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.HashMap;
import java.util.stream.Stream;

import static io.timeandspace.smoothie.UnsafeUtils.U;
import static io.timeandspace.smoothie.Utils.verifyThat;

public final class ObjectSize {

    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final long OBJECT_HEADER_SIZE;
    private static final @Nullable Field HASH_MAP_TABLE_FIELD;
    private static final ClassValue<Long> CLASS_SIZES = new ClassValue<Long>() {
        @SuppressWarnings("UnnecessaryBoxing")
        @Override
        protected Long computeValue(Class<?> type) {
            return Long.valueOf(internalClassSizeInBytes(type));
        }
    };

    static {
        OBJECT_HEADER_SIZE = UnsafeUtils.minInstanceFieldOffset(ClassWithOneByteField.class);

        HASH_MAP_TABLE_FIELD = Stream
                .of(HashMap.class.getDeclaredFields())
                .filter(f -> f.getType().isArray())
                .findFirst()
                .orElse(null);
        if (HASH_MAP_TABLE_FIELD != null) {
            HASH_MAP_TABLE_FIELD.setAccessible(true);
        }
    }

    static long objectSizeInBytes(@Nullable Object obj) {
        if (obj == null) {
            return 0;
        }
        Class<?> objClass = obj.getClass();
        if (objClass.isArray()) {
            return arraySizeInBytes(objClass, Array.getLength(obj));
        }
        return classSizeInBytes(objClass);
    }

    static long classSizeInBytes(Class<?> objClass) {
        return CLASS_SIZES.get(objClass);
    }

    private static long internalClassSizeInBytes(Class<?> objClass) {
        verifyThat(!objClass.isArray());
        Field[] lastSetOfDeclaredFields = lastSetOfDeclaredFields(objClass);
        // U::objectFieldOffset triggers forbidden-apis of Objects.requireNonNull() for some reason
        //noinspection Convert2MethodRef
        @Nullable Field lastField = Stream
                .of(lastSetOfDeclaredFields)
                .max(Comparator.comparingLong(f -> U.objectFieldOffset(f)))
                .orElse(null);
        if (lastField == null) { // There are no declared fields.
            return OBJECT_HEADER_SIZE;
        }
        long lastFieldOffset = U.objectFieldOffset(lastField);
        Class<?> lastFieldType = lastField.getType();
        int lastFieldSize = U.arrayIndexScale(arrayClassByElementClass(lastFieldType));
        // TODO There should also be round up to the object alignment
        return lastFieldOffset + (long) lastFieldSize;
    }

    static long arraySizeInBytes(Class<?> arrayClass, int length) {
        long baseOffset = (long) U.arrayBaseOffset(arrayClass);
        long scale = (long) U.arrayIndexScale(arrayClass);
        // TODO There should also be round up to the object alignment
        return baseOffset + scale * (long) length;
    }

    public static long hashMapSizeInBytes(HashMap<?, ?> map) {
        if (HASH_MAP_TABLE_FIELD == null) {
            throw new RuntimeException("Not found a table field in HashMap class");
        }
        try {
            Object table = HASH_MAP_TABLE_FIELD.get(map);
            long entrySizeInBytes = 0;
            if (map.size() > 0) {
                entrySizeInBytes = objectSizeInBytes(map.entrySet().iterator().next());
            }
            return objectSizeInBytes(map) + objectSizeInBytes(table) + entrySizeInBytes *
                    ((long) map.size());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field[] lastSetOfDeclaredFields(Class<?> objClass) {
        while (objClass != Object.class) {
            Field[] lastSetOfDeclaredFields = Stream
                    .of(objClass.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .toArray(Field[]::new);
            if (lastSetOfDeclaredFields.length > 0) {
                return lastSetOfDeclaredFields;
            }
            objClass = objClass.getSuperclass();
        }
        return EMPTY_FIELDS;
    }

    private static Class<?> arrayClassByElementClass(Class<?> elementClass) {
        return Array.newInstance(elementClass, 0).getClass();
    }

    private static class ClassWithOneByteField {
        @SuppressWarnings("unused")
        byte field;
    }

    private ObjectSize() {}
}
