package obf;

import obf.transform.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class Main {

    private static final Map<String, Transform> TRANSFORMS = new LinkedHashMap<>();

    static {
        register(new Renamer());
        register(new StringObf());
        register(new ControlFlow());
    }

    private static void register(Transform t) {
        TRANSFORMS.put(t.name(), t);
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("  MindlessObf v1.0");
        System.out.println("  ================");
        System.out.println();

        ObfConfig config;

        if (args.length == 1 && args[0].endsWith(".json")) {
            config = ObfConfig.load(args[0]);
        } else if (args.length >= 2) {
            config = ObfConfig.defaults(args[0], args[1]);
            if (args.length >= 3) {
                config.transforms.clear();
                for (int i = 2; i < args.length; i++) config.transforms.add(args[i]);
            }
        } else {
            System.out.println("  Usage:");
            System.out.println("    java -jar mindless-obf.jar <input.jar> <output.jar> [transforms...]");
            System.out.println("    java -jar mindless-obf.jar config.json");
            System.out.println();
            System.out.println("  Available transforms:");
            for (var t : TRANSFORMS.values()) {
                System.out.println("    - " + t.name());
            }
            System.out.println();
            System.out.println("  Generate default config:");
            System.out.println("    java -jar mindless-obf.jar <input.jar> <output.jar> --genconfig config.json");
            return;
        }

        // --genconfig support
        for (int i = 0; i < args.length; i++) {
            if ("--genconfig".equals(args[i]) && i + 1 < args.length) {
                config.save(args[i + 1]);
                System.out.println("  Config written to " + args[i + 1]);
                return;
            }
        }

        if (config.input == null || config.output == null) {
            System.out.println("  Error: input and output are required");
            return;
        }

        Path inputPath = Path.of(config.input);
        if (!Files.exists(inputPath)) {
            System.out.println("  Error: input not found: " + config.input);
            return;
        }

        System.out.println("  Input:      " + config.input);
        System.out.println("  Output:     " + config.output);
        System.out.println("  Transforms: " + config.transforms);
        System.out.println("  Excludes:   " + config.excludes);
        System.out.println();

        ObfContext ctx = new ObfContext(config);

        // Read input JAR
        System.out.println("  Reading input JAR...");
        try (JarInputStream jis = new JarInputStream(new FileInputStream(config.input))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] data = jis.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    try {
                        ClassReader cr = new ClassReader(data);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, 0);
                        ctx.classes().put(cn.name, cn);
                    } catch (Exception e) {
                        ctx.resources().put(entry.getName(), data);
                    }
                } else {
                    ctx.resources().put(entry.getName(), data);
                }
            }
        }

        System.out.println("  Loaded " + ctx.classes().size() + " classes, "
            + ctx.resources().size() + " resources");
        System.out.println();

        // Run transforms
        for (String tName : config.transforms) {
            Transform t = TRANSFORMS.get(tName);
            if (t == null) {
                System.out.println("  Unknown transform: " + tName);
                continue;
            }
            long start = System.currentTimeMillis();
            System.out.println("  Running: " + t.name());
            t.apply(ctx);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Done in " + elapsed + "ms");
            System.out.println();
        }

        // Write output JAR
        System.out.println("  Writing output JAR...");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(config.output))) {
            // Write classes
            for (Map.Entry<String, ClassNode> entry : ctx.classes().entrySet()) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                entry.getValue().accept(cw);
                byte[] bytes = cw.toByteArray();
                jos.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                jos.write(bytes);
                jos.closeEntry();
            }
            // Write resources
            for (Map.Entry<String, byte[]> entry : ctx.resources().entrySet()) {
                jos.putNextEntry(new ZipEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }

        long size = Files.size(Path.of(config.output));
        System.out.println("  Output: " + config.output + " (" + (size / 1024) + " KB)");
        System.out.println();
        System.out.println("  Done.");
    }
}
