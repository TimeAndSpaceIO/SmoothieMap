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

import java.util.concurrent.atomic.AtomicReferenceArray;

import static net.openhft.smoothie.CompilerUtils.CACHED_COMPILER;

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
        StringBuilder sb = new StringBuilder();
        String pkg = Segment.class.getPackage().getName();
        sb.append("package " + pkg + ";\n");
        String className = "Segment" + allocationCapacity;
        sb.append("class " + className + " extends " + Segment.class.getSimpleName() + " {\n");
        for (int i = 1; i < allocationCapacity; i++) {
            sb.append("Object k" + i + ", v" + i + ";\n");
        }
        sb.append("}");
        try {
            c = CACHED_COMPILER.loadFromJava(cl, pkg + "." + className, sb.toString());
            classCache.set(allocationCapacity, c);
            return c;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private SegmentClassGenerator() {
    }
}
