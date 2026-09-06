package mindless.accountmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.MicrosoftAuth;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Persistent, opt-in skin preset. No account credentials are stored here. */
public final class AutoSkinSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DIRECTORY = new File(Minecraft.getMinecraft().mcDataDir, "mindless/account-manager");
    private static final File CONFIG = new File(DIRECTORY, "auto-skin.json");
    private static final File SKIN = new File(DIRECTORY, "preset-skin.png");

    private static boolean loaded;
    private static boolean enabled;
    private static boolean slim;

    private AutoSkinSettings() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!CONFIG.isFile()) return;
        try (FileReader reader = new FileReader(CONFIG)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            enabled = json.has("enabled") && json.get("enabled").getAsBoolean() && SKIN.isFile();
            slim = json.has("slim") && json.get("slim").getAsBoolean();
        } catch (Exception error) {
            enabled = false;
            System.err.println("[Mindless] Could not load auto-skin settings: " + error.getMessage());
        }
    }

    public static synchronized boolean isEnabled() {
        load();
        return enabled && SKIN.isFile();
    }

    public static synchronized boolean isSlim() {
        load();
        return slim;
    }

    public static synchronized boolean hasPreset() {
        load();
        return SKIN.isFile();
    }

    public static synchronized void setEnabled(boolean value) {
        load();
        enabled = value && SKIN.isFile();
        save();
    }

    public static synchronized void setSlim(boolean value) {
        load();
        slim = value;
        save();
    }

    public static synchronized void setPreset(File source) throws IOException {
        BufferedImage image = ImageIO.read(source);
        if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IOException("Skin must be a 64x64 or 64x32 PNG");
        }
        if (!DIRECTORY.exists() && !DIRECTORY.mkdirs()) {
            throw new IOException("Could not create the Mindless skin folder");
        }
        Files.copy(source.toPath(), SKIN.toPath(), StandardCopyOption.REPLACE_EXISTING);
        save();
    }

    public static CompletableFuture<Boolean> applyIfEnabled(Account account, Executor executor) {
        final byte[] bytes;
        final String variant;
        synchronized (AutoSkinSettings.class) {
            load();
            if (!enabled || !SKIN.isFile() || account == null || account.getAccessToken() == null
                    || account.getAccessToken().isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            try {
                bytes = Files.readAllBytes(SKIN.toPath());
            } catch (IOException error) {
                return CompletableFuture.completedFuture(false);
            }
            variant = slim ? "slim" : "classic";
        }
        return MicrosoftAuth.changeSkin(account.getAccessToken(), bytes, variant, executor)
                .thenApply(ignored -> true)
                .exceptionally(error -> {
                    System.err.println("[Mindless] Auto skin failed: " + rootMessage(error));
                    return false;
                });
    }

    private static synchronized void save() {
        if (!DIRECTORY.exists() && !DIRECTORY.mkdirs()) return;
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("slim", slim);
        try (FileWriter writer = new FileWriter(CONFIG)) {
            GSON.toJson(json, writer);
        } catch (IOException error) {
            System.err.println("[Mindless] Could not save auto-skin settings: " + error.getMessage());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
