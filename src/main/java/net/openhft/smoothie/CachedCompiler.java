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

import org.jetbrains.annotations.NotNull;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.*;

@SuppressWarnings("StaticNonFinalField")
class CachedCompiler {
    private static final Map<ClassLoader, Map<String, Class>> loadedClassesMap =
            new WeakHashMap<>();

    private static final List<String> options =
            Arrays.asList("-XDenableSunApiLintControl", "-Xlint:-sunapi");

    private final Map<String, JavaFileObject> javaFileObjects = new HashMap<>();
    private boolean errors;

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode) {
        Iterable<? extends JavaFileObject> compilationUnits;
        javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
        compilationUnits = javaFileObjects.values();

        MyJavaFileManager fileManager =
                new MyJavaFileManager(CompilerUtils.s_standardJavaFileManager);
        errors = false;
        CompilerUtils.s_compiler.getTask(null, fileManager, diagnostic -> {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors = true;
                System.err.println(diagnostic);
            }
        }, options, null, compilationUnits).call();
        Map<String, byte[]> result = fileManager.getAllBuffers();
        if (errors) {
            // compilation error, so we want to exclude this file from future compilation passes
            javaFileObjects.remove(className);
        }
        return result;
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader, @NotNull String className,
            @NotNull String javaCode) throws ClassNotFoundException {
        Class clazz = null;
        Map<String, Class> loadedClasses;
        synchronized (loadedClassesMap) {
            loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null)
                loadedClassesMap.put(classLoader, loadedClasses = new LinkedHashMap<>());
            else
                clazz = loadedClasses.get(className);
        }
        if (clazz != null)
            return clazz;
        for (Map.Entry<String, byte[]> entry : compileFromJava(className, javaCode).entrySet()) {
            String className2 = entry.getKey();
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue;
            }
            byte[] bytes = entry.getValue();
            Class clazz2 = CompilerUtils.defineClass(classLoader, className2, bytes);
            synchronized (loadedClassesMap) {
                loadedClasses.put(className2, clazz2);
            }
        }
        synchronized (loadedClassesMap) {
            loadedClasses.put(className, clazz = classLoader.loadClass(className));
        }
        return clazz;
    }
}
