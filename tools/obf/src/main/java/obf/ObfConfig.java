package obf;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ObfConfig {
    public String input;
    public String output;
    public List<String> includes = new ArrayList<>(Collections.singletonList("mindless/"));
    public List<String> excludes = new ArrayList<>(Arrays.asList(
            "java/",
            "javax/",
            "sun/",
            "net/minecraft/",
            "net/minecraftforge/",
            "org/spongepowered/",
            "org/objectweb/asm/",
            "net/lenni0451/",
            "com/google/",
            "org/apache/",
            "org/slf4j/",
            "mindless/runtime/",
            "mindless/mixin/",
            "mindless/transformer/",
            "mindless/script/"
    ));
    public List<String> transforms = new ArrayList<>();
    public RenamerConfig renamer = new RenamerConfig();
    public StringObfConfig stringObf = new StringObfConfig();

    public static class RenamerConfig {
        public String packagePrefix = "com/moonsworth/lunar/";
        public String alphabet = "COHRI";
        public int minLength = 15;
        public int maxLength = 35;
        public boolean renameClasses = true;
        public boolean renameMethods = true;
        public boolean renameFields = true;
    }

    public static class StringObfConfig {
        public boolean enabled = true;
    }

    public static ObfConfig load(String path) throws IOException {
        String json = Files.readString(Path.of(path));
        ObfConfig config = new Gson().fromJson(json, ObfConfig.class);
        if (config.transforms.isEmpty()) {
            config.transforms.add("renamer");
        }
        return config;
    }

    public static ObfConfig defaults(String input, String output) {
        ObfConfig c = new ObfConfig();
        c.input = input;
        c.output = output;
        c.transforms.add("renamer");
        return c;
    }

    public void save(String path) throws IOException {
        String json = new Gson().newBuilder().setPrettyPrinting().create().toJson(this);
        Files.writeString(Path.of(path), json);
    }
}
