package mindless.utility;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class MindlessAccount {
    private static final long RECHECK_INTERVAL_MS = 15_000L;
    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_PROFILE_BYTES = 8_192;
    private static final long MAX_AVATAR_BYTES = 2_097_152L;

    private static volatile String resolved;
    private static volatile long resolvedAt;
    private static Profile profile;
    private static long profileAt;
    private static long avatarSignature = Long.MIN_VALUE;
    private static ResourceLocation avatarLocation;

    private MindlessAccount() {}

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

    public static String displayName() {
        String name = username();
        if (name != null) return name;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getSession() != null) {
            String sessionName = sanitise(mc.getSession().getUsername(), MAX_NAME_LENGTH, false);
            if (sessionName != null) return sessionName;
        }
        return "guest";
    }

    public static boolean isAuthenticated() {
        return username() != null;
    }

    public static Profile profile() {
        long now = System.currentTimeMillis();
        if (profile != null && now - profileAt < RECHECK_INTERVAL_MS) return profile;
        profileAt = now;
        profile = readProfile();
        refreshAvatar();
        return profile;
    }

    private static Profile readProfile() {
        String username = displayName();
        File file = localFile("profile.json");
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_PROFILE_BYTES) {
            return new Profile(username, null, null, null, avatarLocation);
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) return new Profile(username, null, null, null, avatarLocation);
            JsonObject object = parsed.getAsJsonObject();
            String loaderName = field(object, "username", MAX_NAME_LENGTH, false);
            String display = field(object, "discord_display_name", 64, false);
            String discordName = field(object, "discord_username", 64, false);
            String id = field(object, "discord_id", 32, true);
            return new Profile(loaderName != null ? loaderName : username, display, discordName, id, avatarLocation);
        } catch (Throwable ignored) {
            return new Profile(username, null, null, null, avatarLocation);
        }
    }

    private static void refreshAvatar() {
        File file = localFile("avatar.png");
        long signature = file != null && file.isFile() ? file.lastModified() ^ (file.length() << 13) : -1L;
        if (signature == avatarSignature) {
            if (profile != null) profile.avatar = avatarLocation;
            return;
        }
        avatarSignature = signature;
        Minecraft mc = Minecraft.getMinecraft();
        if (avatarLocation != null) {
            mc.getTextureManager().deleteTexture(avatarLocation);
            avatarLocation = null;
        }
        if (file != null && file.isFile() && file.length() > 0L && file.length() <= MAX_AVATAR_BYTES) {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image != null && image.getWidth() > 0 && image.getHeight() > 0
                        && image.getWidth() <= 1024 && image.getHeight() <= 1024) {
                    avatarLocation = mc.getTextureManager().getDynamicTextureLocation(
                            "mindless_account_avatar", new DynamicTexture(image));
                }
            } catch (Throwable ignored) {
                avatarLocation = null;
            }
        }
        if (profile != null) profile.avatar = avatarLocation;
    }

    private static String field(JsonObject object, String name, int max, boolean digitsOnly) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        return sanitise(value.getAsString(), max, digitsOnly);
    }

    private static String resolve() {
        String property = sanitise(System.getProperty("mindless.username"), MAX_NAME_LENGTH, false);
        if (property != null) return property;
        String environment = sanitise(System.getenv("MINDLESS_USERNAME"), MAX_NAME_LENGTH, false);
        if (environment != null) return environment;
        return sanitise(readSessionFile(), MAX_NAME_LENGTH, false);
    }

    private static String readSessionFile() {
        File file = localFile("session.txt");
        if (file == null || !file.isFile() || file.length() == 0L || file.length() > 512L) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File localFile(String name) {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return new File(new File(appData, "Mindless"), name);
    }

    private static String sanitise(String raw, int max, boolean digitsOnly) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        StringBuilder cleaned = new StringBuilder(Math.min(trimmed.length(), max));
        for (int i = 0; i < trimmed.length() && cleaned.length() < max; i++) {
            char character = trimmed.charAt(i);
            if (digitsOnly) {
                if (character >= '0' && character <= '9') cleaned.append(character);
            } else if (character >= ' ' && character != 127 && character != '\u00A7') {
                cleaned.append(character);
            }
        }
        return cleaned.length() == 0 ? null : cleaned.toString();
    }

    public static final class Profile {
        private final String username;
        private final String discordDisplayName;
        private final String discordUsername;
        private final String discordId;
        private ResourceLocation avatar;

        private Profile(String username, String discordDisplayName, String discordUsername,
                        String discordId, ResourceLocation avatar) {
            this.username = username;
            this.discordDisplayName = discordDisplayName;
            this.discordUsername = discordUsername;
            this.discordId = discordId;
            this.avatar = avatar;
        }

        public String username() { return username; }
        public String discordDisplayName() { return discordDisplayName; }
        public String discordUsername() { return discordUsername; }
        public String discordId() { return discordId; }
        public ResourceLocation avatar() { return avatar; }
    }
}
