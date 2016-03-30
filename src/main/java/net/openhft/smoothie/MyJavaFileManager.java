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

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.*;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

@SuppressWarnings("RefusedBequest")
class MyJavaFileManager implements JavaFileManager {

    private static void addFileObjects(Map<String, Set<JavaFileObject>> fileObjects, Class<?> c) {
        fileObjects.compute(c.getPackage().getName(), (p, objects) -> {
            if (objects == null)
                objects = new HashSet<>();
            objects.add(classFileObject(c));
            return objects;
        });

        Type[] interfaces = c.getGenericInterfaces();
        for (Type superInterface : interfaces) {
            Class rawInterface = rawInterface(superInterface);
            addFileObjects(fileObjects, rawInterface);
        }
    }

    private static Class rawInterface(Type superInterface) {
        if (superInterface instanceof Class) {
            return (Class) superInterface;
        } else {
            if (superInterface instanceof ParameterizedType) {
                return (Class) ((ParameterizedType) superInterface).getRawType();
            } else {
                throw new AssertionError("Super interface should be a raw interface or" +
                        "a parameterized interface");
            }
        }
    }

    private static JavaFileObject classFileObject(Class<?> c) {
        try {
            String className = c.getName();
            int lastDotIndex = className.lastIndexOf('.');
            URI uri = c.getResource(className.substring(lastDotIndex + 1) + ".class").toURI();
            return new SimpleURIClassObject(uri, c);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Map<String, Set<JavaFileObject>> dependencyFileObjects = new HashMap<>();
    static {
        Collections.singletonList(
                Segment.class
        ).forEach(c -> addFileObjects(dependencyFileObjects, c));
    }

    private final StandardJavaFileManager fileManager;
    private final Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();

    MyJavaFileManager(StandardJavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    public ClassLoader getClassLoader(Location location) {
        return fileManager.getClassLoader(location);
    }

    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<Kind> kinds, boolean recurse)
            throws IOException {
        Iterable<JavaFileObject> delegateFileObjects =
                fileManager.list(location, packageName, kinds, recurse);
        Collection<JavaFileObject> packageFileObjects;
        if ((packageFileObjects = dependencyFileObjects.get(packageName)) != null) {
            packageFileObjects = new ArrayList<>(packageFileObjects);
            delegateFileObjects.forEach(packageFileObjects::add);
            return packageFileObjects;
        } else {
            return delegateFileObjects;
        }
    }

    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof SimpleURIClassObject) {
            return ((SimpleURIClassObject) file).c.getName();
        } else {
            return fileManager.inferBinaryName(location, file);
        }
    }

    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    public boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    public boolean hasLocation(Location location) {
        return fileManager.hasLocation(location);
    }

    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind)
            throws IOException {
        if (location == StandardLocation.CLASS_OUTPUT && buffers.containsKey(className) &&
                kind == Kind.CLASS) {
            final byte[] bytes = buffers.get(className).toByteArray();
            return new SimpleJavaFileObject(URI.create(className), kind) {
                @NotNull
                public InputStream openInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
        return fileManager.getJavaFileForInput(location, className, kind);
    }

    @NotNull
    public JavaFileObject getJavaFileForOutput(
            Location location, final String className, Kind kind, FileObject sibling)
            throws IOException {
        return new SimpleJavaFileObject(URI.create(className), kind) {
            @NotNull
            public OutputStream openOutputStream() {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(className, baos);
                return baos;
            }
        };
    }

    public FileObject getFileForInput(
            Location location, String packageName, String relativeName) throws IOException {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    public FileObject getFileForOutput(
            Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    public void flush() throws IOException {
        // Do nothing
    }

    public void close() throws IOException {
        fileManager.close();
    }

    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    @NotNull
    public Map<String, byte[]> getAllBuffers() {
        Map<String, byte[]> ret = new LinkedHashMap<>(buffers.size() * 2);
        for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return ret;
    }
}
