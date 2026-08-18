package mindless.runtime;

import mindless.Raven;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Forge 1.8.9 constructs each subscribed event type while registering a
 * listener. A missing public no-argument constructor makes that handler vanish
 * from the bus after only a log warning.
 */
public class EventHandlerContractTest {
    @Test
    public void everyMindlessSubscriberUsesRegistrableEventTypes() throws Exception {
        URI rootUri = Raven.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path root = Paths.get(rootUri);
        Path packageRoot = root.resolve("mindless");
        List<String> failures = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(packageRoot)) {
            paths.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                    .forEach(path -> inspect(root, path, failures));
        }
        Assert.assertTrue("Unregistrable @SubscribeEvent handlers:\n"
                + String.join("\n", failures), failures.isEmpty());
    }

    private static void inspect(Path root, Path classFile, List<String> failures) {
        String relative = root.relativize(classFile).toString();
        String className = relative.substring(0, relative.length() - 6)
                .replace('\\', '.').replace('/', '.');
        try {
            Class<?> owner = Class.forName(className, false,
                    EventHandlerContractTest.class.getClassLoader());
            if (owner.isAnonymousClass() || owner.isLocalClass()
                    || Modifier.isPrivate(owner.getModifiers())) return;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(SubscribeEvent.class)) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !Event.class.isAssignableFrom(parameters[0])) {
                    failures.add(owner.getName() + "#" + method.getName()
                            + " has an invalid event signature");
                    continue;
                }
                if (!parameters[0].getName().startsWith("mindless.event.")) {
                    continue;
                }
                try {
                    parameters[0].getConstructor();
                } catch (NoSuchMethodException missing) {
                    failures.add(owner.getName() + "#" + method.getName() + " -> "
                            + parameters[0].getName() + " has no public zero-argument constructor");
                }
            }
        } catch (Throwable loadFailure) {
            failures.add(className + " could not be inspected: " + loadFailure);
        }
    }
}
