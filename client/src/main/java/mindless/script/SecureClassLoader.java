package mindless.script;


import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class loader for compiled scripts.
 *
 * The previous version passed a class if <em>any</em> of four conditions held, one of which was a
 * prefix match on "java.lang" and another a prefix match on "mindless". That admitted
 * java.lang.reflect, java.lang.invoke and every class in the client -- including
 * mindless.accountmanager, which holds Microsoft refresh and access tokens. A shared script could
 * read them. It also blocked java.lang.System outright, which broke any script calling
 * currentTimeMillis.
 *
 * This is an allowlist by exact class name for everything outside the scripting API. Scripts reach
 * mindless.script and nothing else in the client.
 *
 * <h2>What this does and does not buy</h2>
 * A class loader gates resolution <em>by name</em>. That covers the direct route and the
 * reflective one, because MethodHandles.Lookup#findClass and Class#forName both resolve through
 * the loader of the calling class, which is this one. It does not and cannot cover a reference
 * obtained from an object the scripting API already handed out. Keeping java.lang.Class and
 * java.lang.reflect off the list is what closes that second route, so neither should be added.
 *
 * Lambdas force a compromise. javac compiles them to invokedynamic against LambdaMetafactory, and
 * the JVM resolves that bootstrap through the defining loader of the class that contains the
 * lambda -- this loader. The six java.lang.invoke types below are exactly what the real scripts
 * reference and cannot be withheld without breaking every lambda. MethodHandles.lookup() is
 * therefore reachable from script code; findClass still comes back here, so it buys a script
 * nothing this list does not already allow.
 */
public class SecureClassLoader extends URLClassLoader {

    private final Map<String, byte[]> inMemoryClasses;

    /** Package prefixes a script may use freely. The scripting API, and nothing else. */
    private static final List<String> ALLOWED_PACKAGES = Arrays.asList(
            "mindless.script."
    );

    /**
     * Exact classes outside those packages that scripts may load.
     *
     * By name rather than by package on purpose: "java.lang" as a prefix admits java.lang.reflect
     * and java.lang.invoke, and "java.util" admits java.util.concurrent thread pools and
     * java.util.zip.
     */
    private static final Set<String> ALLOWED_CLASSES = new HashSet<String>(Arrays.asList(
            // java.lang value and utility types
            "java.lang.Object", "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer",
            "java.lang.CharSequence", "java.lang.Comparable", "java.lang.Iterable", "java.lang.Cloneable",
            "java.lang.Number", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.lang.Math", "java.lang.StrictMath", "java.lang.Enum", "java.lang.Runnable",
            "java.lang.Void", "java.lang.Record",

            // Needed by any script that measures time. Was on the block list, which is why
            // TellyScaffold died on its first System.currentTimeMillis call.
            "java.lang.System",

            // The lambda bootstrap. Exactly the six the compiled scripts reference.
            "java.lang.invoke.LambdaMetafactory", "java.lang.invoke.CallSite",
            "java.lang.invoke.MethodHandle", "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles$Lookup", "java.lang.invoke.MethodType",

            // Collections and the rest of the everyday toolkit.
            "java.util.Collection", "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.Queue", "java.util.Deque", "java.util.ArrayDeque",
            "java.util.Collections", "java.util.Arrays", "java.util.Objects", "java.util.Optional",
            "java.util.Iterator", "java.util.ListIterator", "java.util.Comparator",
            "java.util.Random", "java.util.UUID", "java.util.Locale", "java.util.Map$Entry",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicBoolean", "java.util.concurrent.atomic.AtomicReference",
            "java.util.regex.Pattern", "java.util.regex.Matcher",
            "java.util.function.Function", "java.util.function.BiFunction",
            "java.util.function.Predicate", "java.util.function.BiPredicate",
            "java.util.function.Supplier", "java.util.function.Consumer",
            "java.util.function.BiConsumer", "java.util.function.UnaryOperator",
            "java.util.function.IntFunction", "java.util.function.ToIntFunction",
            "java.util.function.ToDoubleFunction", "java.util.function.ToLongFunction",
            "java.util.stream.Stream", "java.util.stream.IntStream", "java.util.stream.DoubleStream",
            "java.util.stream.LongStream", "java.util.stream.Collectors",

            "java.awt.Color",

            // Throwables. Without these the loader refused any script containing a catch block,
            // which silently dropped most of the scripts folder. None of them reach anything the
            // list does not already allow: getClass() is inherited from Object and is not gated by
            // a class loader, and java.lang.Class stays off the list either way.
            "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException",
            "java.lang.Error", "java.lang.AutoCloseable", "java.lang.StackTraceElement",
            "java.lang.ArithmeticException", "java.lang.ArrayIndexOutOfBoundsException",
            "java.lang.ClassCastException", "java.lang.IllegalArgumentException",
            "java.lang.IllegalStateException", "java.lang.IndexOutOfBoundsException",
            "java.lang.InterruptedException", "java.lang.NegativeArraySizeException",
            "java.lang.NullPointerException", "java.lang.NumberFormatException",
            "java.lang.StringIndexOutOfBoundsException",
            "java.lang.UnsupportedOperationException",
            "java.util.ConcurrentModificationException", "java.util.NoSuchElementException"
    ));

    /**
     * Runtime-owned superclasses used by the JDK's generated reflection accessors.
     *
     * After a Method has been invoked repeatedly, Java replaces the slow native accessor with a
     * generated class whose defining loader delegates these exact implementation classes through
     * the script loader. Blocking them makes a healthy script fail after roughly fifteen event
     * callbacks with NoClassDefFoundError. They are not scripting API and source code cannot use
     * this non-exported package; this narrow bridge only lets the JVM finish its own linkage.
     */
    private static final Set<String> JVM_REFLECTION_BRIDGE_CLASSES = new HashSet<String>(Arrays.asList(
            "jdk.internal.reflect.MagicAccessorImpl",
            "jdk.internal.reflect.MethodAccessor",
            "jdk.internal.reflect.MethodAccessorImpl",
            "jdk.internal.reflect.ConstructorAccessor",
            "jdk.internal.reflect.ConstructorAccessorImpl",
            "jdk.internal.reflect.SerializationConstructorAccessorImpl",
            // Java 8 names for the same VM-owned bridge classes. The Forge test/runtime toolchain
            // still uses these even when the launcher itself runs a newer JDK.
            "sun.reflect.MagicAccessorImpl",
            "sun.reflect.MethodAccessor",
            "sun.reflect.MethodAccessorImpl",
            "sun.reflect.ConstructorAccessor",
            "sun.reflect.ConstructorAccessorImpl",
            "sun.reflect.SerializationConstructorAccessorImpl"
    ));

    /**
     * Kept as a second gate. The allowlist above already excludes all of these; naming them means a
     * careless addition to the allowlist cannot quietly re-open one.
     */
    private static final List<String> BLOCKED_CLASSES = Arrays.asList(
            "java.lang.Runtime",
            "java.lang.Process",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessHandle",
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.SecurityManager",
            "java.lang.Compiler",
            "java.lang.Package",
            "java.lang.Module",
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "java.lang.reflect.Constructor",
            "java.lang.reflect.Proxy",
            "java.lang.reflect.AccessibleObject",
            "java.awt.Desktop",
            "java.awt.Robot",
            "java.awt.Toolkit"
    );

    /** Set when a load was refused, so the caller can tell a sandbox rejection from a link error. */
    private volatile String rejectedClass;

    public SecureClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.inMemoryClasses = null;
    }

    public SecureClassLoader(Map<String, byte[]> inMemoryClasses, ClassLoader parent) {
        super(new URL[0], parent);
        this.inMemoryClasses = inMemoryClasses;
    }

    public String getRejectedClass() {
        return rejectedClass;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isClassSafe(name)) {
            rejectedClass = name;
            throw new ClassNotFoundException("Blocked by the script sandbox: " + name);
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (inMemoryClasses != null) {
            byte[] bytes = inMemoryClasses.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        return super.findClass(name);
    }

    private boolean isClassSafe(String rawName) {
        if (rawName == null) {
            return false;
        }
        // Array types resolve by their component. The JVM usually builds these itself, but
        // Class.forName and some checkcast paths hand the descriptor straight to the loader, and
        // neither this check nor the one before it would have matched "[Ljava.lang.String;".
        String name = componentOf(rawName);
        if (name == null) {
            return false;
        }
        if (matchesBlockedClassOrNested(name)) {
            return false;
        }

        if (PRIMITIVE_ARRAY.equals(name)) {
            return true;
        }
        if (JVM_REFLECTION_BRIDGE_CLASSES.contains(name)) {
            return true;
        }
        // The script's own classes, and anything it declares as an inner class of them.
        if (name.startsWith("sc_")) {
            return true;
        }
        if (ALLOWED_CLASSES.contains(name)) {
            return true;
        }
        for (int i = 0; i < ALLOWED_PACKAGES.size(); i++) {
            if (name.startsWith(ALLOWED_PACKAGES.get(i))) {
                return true;
            }
        }
        // Throwables, so a script can catch and declare them. Narrowed to java.lang: the old check
        // accepted any name ending in "Exception" from anywhere at all.
        if (name.startsWith("java.lang.") && (name.endsWith("Exception") || name.endsWith("Error"))) {
            return true;
        }
        return ALLOWED_CLASSES.contains(outerOf(name));
    }

    /**
     * Unwraps an array descriptor to the class its elements hold, or returns the name unchanged.
     * Primitive arrays carry no class to check, so they are waved through as the empty name, which
     * the allowlist accepts via the primitive short-circuit in the caller.
     */
    private static String componentOf(String name) {
        int depth = 0;
        while (depth < name.length() && name.charAt(depth) == '[') {
            depth++;
        }
        if (depth == 0) {
            return name;
        }
        String element = name.substring(depth);
        if (element.startsWith("L") && element.endsWith(";")) {
            return element.substring(1, element.length() - 1);
        }
        // B C D F I J S Z -- a primitive array, nothing to gate.
        return element.length() == 1 ? PRIMITIVE_ARRAY : null;
    }

    /** Sentinel for primitive arrays, which carry no class name to check. */
    private static final String PRIMITIVE_ARRAY = " primitive";

    /** Nested types are allowed exactly when their outer type is. */
    private static String outerOf(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static boolean matchesBlockedClassOrNested(String name) {
        for (String blocked : BLOCKED_CLASSES) {
            if (name.equals(blocked) || name.startsWith(blocked + "$")) {
                return true;
            }
        }
        return false;
    }
}
