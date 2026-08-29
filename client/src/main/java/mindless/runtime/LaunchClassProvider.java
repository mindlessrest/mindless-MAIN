package mindless.runtime;

// The imports use the ORIGINAL org.objectweb.asm package: shadow's relocate
// rewrites them at package time to mindless.deps.org.objectweb.asm, but
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

    /**
     * Packages whose class files on the classpath are not the classes the JVM is running.
     *
     * <p>The game's own classes are assembled at launch -- remapped, patched, and in places left
     * under their obfuscated names -- and the loader materialises them through that chain. What
     * {@code getResourceAsStream} finds under the same path is whatever copy happens to be on the
     * classpath, which can be a different build with a different hierarchy and different member
     * names. Answering hierarchy questions from that copy is how a class ends up described as
     * having no {@code motionX}, and how frame computation lands on types the verifier then
     * rejects. The loaded {@link Class} is not a guess: it is the one in use.
     */
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

        // 1. Try the ordinary resource path, except where it is known to answer for a different
        //    build of the class than the one the process actually loaded.
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

        // 2. Fall back to loading the Class and building a minimal ASM stub.
        Class<?> klass;
        try {
            klass = Class.forName(name, false, loader);
        } catch (ClassNotFoundException cnf) {
            try {
                klass = Class.forName(name, false,
                        LaunchClassProvider.class.getClassLoader());
            } catch (ClassNotFoundException cnf2) {
                // The game's classes are hidden as resources, so this is the only route to them;
                // when it fails for one of those, reading the classpath copy is better than
                // nothing even though it may describe a different build.
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
        //
        // Enumerating members resolves every type in their signatures, so one unresolvable type
        // anywhere in the class throws and takes the whole list with it. That used to be silent,
        // and a class described with no members at all reads downstream as a class whose members
        // have all been removed. The public view is a poorer answer than the declared one but a
        // far better answer than none.
        boolean fieldsWritten = emitFields(writer, klass, true);
        if (!fieldsWritten) emitFields(writer, klass, false);

        // Emit declared methods (name + descriptor) so InfoFiller can resolve
        // @COverride / @CInject / @CShadow method targets. Bodies remain empty.
        boolean methodsWritten = emitMethods(writer, klass, true);
        if (!methodsWritten) emitMethods(writer, klass, false);

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** @return whether the member list could be read at all */
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

    /** @return whether the member list could be read at all */
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
