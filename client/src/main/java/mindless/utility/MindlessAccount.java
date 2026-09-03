package mindless.utility;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The name the client was signed in with.
 *
 * The loader owns authentication and the injected client never sees the token, so the name arrives
 * out of band. It is looked up in the order the loader is most likely to have set it, and re-read
 * periodically so signing in while the game is already running is picked up without a restart.
 *
 * auth.dat is deliberately not consulted: it is DPAPI-encrypted and holds the password.
 */
public final class MindlessAccount {

    private static final long RECHECK_INTERVAL_MS = 15_000L;
    private static final int MAX_NAME_LENGTH = 32;

    private static volatile String resolved;
    private static volatile long resolvedAt;

    private MindlessAccount() {}

    /** The signed-in name, or null when the loader has not published one. */
    public static String username() {
        long now = System.currentTimeMillis();
        String cached = resolved;
        if (cached != null && now - resolvedAt < RECHECK_INTERVAL_MS) {
            return cached.isEmpty() ? null : cached;
        }

        String found = resolve();
        resolved = found == null ? "" : found;
        resolvedAt = now;
        return found;
    }

    /** Never null: the signed-in name, else the Minecraft name, else a placeholder. */
    public static String displayName() {
        String name = username();
        if (name != null) {
            return name;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getSession() != null) {
            String profile = mc.getSession().getUsername();
            if (profile != null && !profile.isEmpty()) {
                return profile;
            }
        }
        return "guest";
    }

    /** True when the name came from the loader rather than the Minecraft session. */
    public static boolean isAuthenticated() {
        return username() != null;
    }

    private static String resolve() {
        String property = sanitise(System.getProperty("mindless.username"));
        if (property != null) {
            return property;
        }

        String environment = sanitise(System.getenv("MINDLESS_USERNAME"));
        if (environment != null) {
            return environment;
        }

        return sanitise(readSessionFile());
    }

    private static String readSessionFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) {
            return null;
        }

        File file = new File(new File(appData, "Mindless"), "session.txt");
        if (!file.isFile() || file.length() == 0L || file.length() > 512L) {
            return null;
        }

        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * Keeps the name to a single printable line.
     *
     * It ends up in a rendered string, so a stray newline or control character from a
     * hand-edited file must not be able to break the layout.
     */
    private static String sanitise(String raw) {
        if (raw == null) {
            return null;
        }

        int end = raw.length();
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character == '\n' || character == '\r' || character == '\0') {
                end = i;
                break;
            }
        }

        String trimmed = raw.substring(0, end).trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        StringBuilder cleaned = new StringBuilder(Math.min(trimmed.length(), MAX_NAME_LENGTH));
        for (int i = 0; i < trimmed.length() && cleaned.length() < MAX_NAME_LENGTH; i++) {
            char character = trimmed.charAt(i);
            if (character >= ' ' && character != 127 && character != '§') {
                cleaned.append(character);
            }
        }

        return cleaned.length() == 0 ? null : cleaned.toString();
    }
}
