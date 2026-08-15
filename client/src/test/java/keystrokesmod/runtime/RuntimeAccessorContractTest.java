package keystrokesmod.runtime;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/** Prevents code used by native injection from reintroducing Mixin-only casts. */
public class RuntimeAccessorContractTest {
    @Test
    public void productionCodeDoesNotCastToMixinAccessors() throws Exception {
        Path root = Paths.get("src", "main", "java", "keystrokesmod");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("mixin"))
                    .filter(path -> !path.endsWith("AccessorBridge.java"))
                    .forEach(path -> {
                        try {
                            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            if (source.matches("(?s).*\\(\\s*(?:IAccessor|IMixin|ISaturation)[A-Za-z0-9_]*\\s*\\).*")) {
                                violations.add(path.toString());
                            }
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    });
        }
        assertTrue("Mixin-only interface casts are illegal after JVMTI class loading: " + violations,
                violations.isEmpty());
    }
}
