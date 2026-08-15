package keystrokesmod.runtime;

// The imports use the ORIGINAL org.objectweb.asm package: shadow's relocate
// rewrites them at package time to keystrokesmod.deps.org.objectweb.asm, but
// javac needs the source imports pre-relocation.
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import net.lenni0451.classtransform.utils.tree.IClassProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Class byte provider tuned for the Minecraft 1.8.9 LaunchClassLoader.
 *
 * BasicClassProvider only tries {@code loader.getResourceAsStream(name)}.
 * The LaunchClassLoader deliberately hides several package roots
 * ({@code net.minecraft.*}, {@code net.minecraftforge.*}, {@code net.optifine.*})
 * from its resource lookup even when the classes are perfectly loadable —
 * their bytecode is materialised through the transformer chain, never as
 * a discoverable resource. ASM's stack-frame computation then throws
 * "Class input stream is null" the moment it needs to walk a super-class
 * hierarchy for one of those classes.
 *
 * The fallback here loads the {@link Class} object through the same
 * ClassLoader and synthesises a minimal ASM stub — same modifiers,
 * super class, and interfaces, no field or method bodies. That is all
 * ASM's {@code getCommonSuperClass} needs to converge; the actual method
 * bodies are never read by the framework.
 */
public final class LaunchClassProvider implements IClassProvider {
    private final ClassLoader loader;
    private final Map<String, byte[]> cache = new HashMap<>();

    public LaunchClassProvider(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public byte[] getClass(String name) throws ClassNotFoundException {
        byte[] cached;
        synchronized (cache) { cached = cache.get(name); }
        if (cached != null) return cached;

        String internal = name.replace('.', '/');
        String resource = internal + ".class";

        // 1. Try the ordinary resource path.
        InputStream stream = loader.getResourceAsStream(resource);
        if (stream != null) {
            try (InputStream managed = stream) {
                byte[] bytes = readAll(managed);
                synchronized (cache) { cache.put(name, bytes); }
                return bytes;
            } catch (Throwable ignored) {}
        }

        // 2. Fall back to loading the Class and building a minimal ASM stub.
        Class<?> klass;
        try {
            klass = Class.forName(name, false, loader);
        } catch (ClassNotFoundException cnf) {
            try {
                klass = Class.forName(name, false,
                        LaunchClassProvider.class.getClassLoader());
            } catch (ClassNotFoundException cnf2) {
                throw cnf;
            }
        }
        byte[] stub = synthesizeStub(klass);
        synchronized (cache) { cache.put(name, stub); }
        return stub;
    }

    @Override
    public Map<String, Supplier<byte[]>> getAllClasses() {
        // Enumerating classes is impossible under this loader. Callers that
        // need it can walk JVMTI; ClassTransform's typical path only calls
        // getClass(String) during frame computation.
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

        // Emit declared fields (name + descriptor) so ClassTransform's
        // InfoFiller can locate @CShadow targets. No initializers/attributes.
        try {
            for (java.lang.reflect.Field f : klass.getDeclaredFields()) {
                writer.visitField(f.getModifiers() & 0xFFFF,
                        f.getName(), descriptorOf(f.getType()), null, null).visitEnd();
            }
        } catch (Throwable ignored) {}

        // Emit declared methods (name + descriptor) so InfoFiller can resolve
        // @COverride / @CInject / @CShadow method targets. Bodies remain empty.
        try {
            for (java.lang.reflect.Constructor<?> c : klass.getDeclaredConstructors()) {
                writer.visitMethod(c.getModifiers() & 0xFFFF,
                        "<init>", methodDescriptor(c.getParameterTypes(), void.class),
                        null, null).visitEnd();
            }
        } catch (Throwable ignored) {}
        try {
            for (java.lang.reflect.Method m : klass.getDeclaredMethods()) {
                writer.visitMethod(m.getModifiers() & 0xFFFF,
                        m.getName(),
                        methodDescriptor(m.getParameterTypes(), m.getReturnType()),
                        null, null).visitEnd();
            }
        } catch (Throwable ignored) {}

        writer.visitEnd();
        return writer.toByteArray();
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
