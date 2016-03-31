/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.smoothie;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.objectweb.asm.Opcodes.*;

final class SegmentClassGenerator {

    private static final AtomicReferenceArray<Class<Segment>> classCache =
            new AtomicReferenceArray<>(SmoothieMap.MAX_ALLOC_CAPACITY + 1);

    @SuppressWarnings("unchecked")
    public static <T extends Segment> Class<T> acquireClass(int allocationCapacity) {
        Class c;
        if ((c = classCache.get(allocationCapacity)) != null)
            return (Class<T>) c;
        return (Class<T>) (Class) generateClass(allocationCapacity);
    }

    private static synchronized Class<Segment> generateClass(int allocationCapacity) {
        Class<Segment> c;
        if ((c = classCache.get(allocationCapacity)) != null)
            return c;

        ClassLoader cl = BytecodeGen.getClassLoader(Segment.class);
        String className = Segment.class.getName() + allocationCapacity;
        try {
            c = (Class<Segment>) cl.loadClass(className);
        } catch (ClassNotFoundException ignored) {
            byte[] classBytes = classBytes(Segment.class.getName(), allocationCapacity);
            c = CompilerUtils.defineClass(cl, className, classBytes);
        }
        classCache.set(allocationCapacity, c);
        return c;
    }

    private static byte[] classBytes(String segmentClassName, int allocationCapacity) {
        ClassWriter cw = new ClassWriter(0);
        String segmentClassNameWithSlashes = segmentClassName.replace('.', '/');
        cw.visit(52, ACC_PUBLIC + ACC_SUPER, segmentClassNameWithSlashes + allocationCapacity,
                null, segmentClassNameWithSlashes, null);

        for (int i = 1; i < allocationCapacity; i++) {
            FieldVisitor fv;
            fv = cw.visitField(0, "k" + i, "Ljava/lang/Object;", null, null);
            fv.visitEnd();
            fv = cw.visitField(0, "v" + i, "Ljava/lang/Object;", null, null);
            fv.visitEnd();
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, segmentClassNameWithSlashes, "<init>", "()V",
                    false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();

        return cw.toByteArray();
    }

    private SegmentClassGenerator() {
    }
}
