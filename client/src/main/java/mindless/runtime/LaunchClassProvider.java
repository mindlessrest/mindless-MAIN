package mindless.runtime;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import net.lenni0451.classtransform.utils.tree.IClassProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
public final class LaunchClassProvider implements IClassProvider {
    private final ClassLoader loader;
    private final Map<String, byte[]> cache = new HashMap<>();

    public LaunchClassProvider(ClassLoader loader) {
        this.loader = loader;
    }
private static final String[] SYNTHESIZE_FROM_LOADED_CLASS = {
            "net.minecraft.",
            "net.minecraftforge.",
            "net.optifine.",
            "optifine.",
    };

    private static boolean preferLoadedClass(String name) {
        for (String prefix : SYNTHESIZE_FROM_LOADED_CLASS) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public byte[] getClass(String name) throws ClassNotFoundException {
        byte[] cached;
        synchronized (cache) { cached = cache.get(name); }
        if (cached != null) return cached;

        String internal = name.replace('.', '/');
        String resource = internal + ".class";
        if (!preferLoadedClass(name)) {
            InputStream stream = loader.getResourceAsStream(resource);
            if (stream != null) {
                try (InputStream managed = stream) {
                    byte[] bytes = readAll(managed);
                    synchronized (cache) { cache.put(name, bytes); }
                    return bytes;
                } catch (Throwable ignored) {}
            }
        }
        Class<?> klass;
        try {
            klass = Class.forName(name, false, loader);
        } catch (ClassNotFoundException cnf) {
            try {
                klass = Class.forName(name, false,
                        LaunchClassProvider.class.getClassLoader());
            } catch (ClassNotFoundException cnf2) {
                InputStream stream = loader.getResourceAsStream(resource);
                if (stream != null) {
                    try (InputStream managed = stream) {
                        byte[] bytes = readAll(managed);
                        synchronized (cache) { cache.put(name, bytes); }
                        return bytes;
                    } catch (Throwable ignored) {}
                }
                throw cnf;
            }
        }
        byte[] stub = synthesizeStub(klass);
        synchronized (cache) { cache.put(name, stub); }
        return stub;
    }

    @Override
    public Map<String, Supplier<byte[]>> getAllClasses() {
        return new HashMap<>();
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(stream.available(), 1024));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static byte[] synthesizeStub(Class<?> klass) {
        ClassWriter writer = new ClassWriter(0);
        String internal = klass.getName().replace('.', '/');
        Class<?> superClass = klass.getSuperclass();
        String superInternal = superClass == null ? "java/lang/Object"
                : superClass.getName().replace('.', '/');
        Class<?>[] ifaces = klass.getInterfaces();
        String[] interfaceInternals = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            interfaceInternals[i] = ifaces[i].getName().replace('.', '/');
        }
        int access = klass.getModifiers() & 0xFFFF;
        if (klass.isInterface()) access |= Opcodes.ACC_INTERFACE;
        writer.visit(Opcodes.V1_8, access, internal, null, superInternal, interfaceInternals);
        boolean fieldsWritten = emitFields(writer, klass, true);
        if (!fieldsWritten) emitFields(writer, klass, false);
        boolean methodsWritten = emitMethods(writer, klass, true);
        if (!methodsWritten) emitMethods(writer, klass, false);

        writer.visitEnd();
        return writer.toByteArray();
    }
private static boolean emitFields(ClassWriter writer, Class<?> klass, boolean declaredOnly) {
        java.lang.reflect.Field[] fields;
        try {
            fields = declaredOnly ? klass.getDeclaredFields() : klass.getFields();
        } catch (Throwable unresolvable) {
            return false;
        }
        for (java.lang.reflect.Field field : fields) {
            try {
                writer.visitField(field.getModifiers() & 0xFFFF, field.getName(),
                        descriptorOf(field.getType()), null, null).visitEnd();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }
private static boolean emitMethods(ClassWriter writer, Class<?> klass, boolean declaredOnly) {
        try {
            for (java.lang.reflect.Constructor<?> constructor : klass.getDeclaredConstructors()) {
                writer.visitMethod(constructor.getModifiers() & 0xFFFF, "<init>",
                        methodDescriptor(constructor.getParameterTypes(), void.class),
                        null, null).visitEnd();
            }
        } catch (Throwable ignored) {
        }

        java.lang.reflect.Method[] methods;
        try {
            methods = declaredOnly ? klass.getDeclaredMethods() : klass.getMethods();
        } catch (Throwable unresolvable) {
            return false;
        }
        for (java.lang.reflect.Method method : methods) {
            try {
                writer.visitMethod(method.getModifiers() & 0xFFFF, method.getName(),
                        methodDescriptor(method.getParameterTypes(), method.getReturnType()),
                        null, null).visitEnd();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static String descriptorOf(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + descriptorOf(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String methodDescriptor(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(descriptorOf(p));
        return sb.append(')').append(descriptorOf(ret)).toString();
    }
}
