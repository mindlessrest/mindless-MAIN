package keystrokesmod.accountmanager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches 32x32 player head avatars from mineskin.eu asynchronously.
 * Call {@link #get(String)} from the render thread — returns null while the
 * texture is loading and the cached ResourceLocation once ready.
 */
public final class PlayerHeadCache {
    private static final ConcurrentHashMap<String, ResourceLocation> CACHE   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean>          PENDING = new ConcurrentHashMap<>();

    private PlayerHeadCache() {}

    /** @return cached head texture, or null if still loading / username is blank */
    public static ResourceLocation get(String username) {
        if (username == null || username.isEmpty()) return null;
        ResourceLocation loc = CACHE.get(username);
        if (loc != null) return loc;
        fetch(username);
        return null;
    }

    /** Evict a username so its head will be re-fetched on the next {@link #get} call. */
    public static void invalidate(String username) {
        if (username == null) return;
        CACHE.remove(username);
    }

    private static void fetch(String username) {
        if (CACHE.containsKey(username)) return;
        if (PENDING.putIfAbsent(username, Boolean.TRUE) != null) return;

        new Thread(() -> {
            try {
                URL url = new URL("https://mineskin.eu/avatar/" + username + "/32.png");
                BufferedImage img = ImageIO.read(url);
                if (img == null) {
                    PENDING.remove(username);
                    return;
                }
                Minecraft mc = Minecraft.getMinecraft();
                mc.addScheduledTask(() -> {
                    try {
                        ResourceLocation registered = mc.getTextureManager()
                                .getDynamicTextureLocation("raven_head_" + username.toLowerCase(),
                                        new DynamicTexture(img));
                        CACHE.put(username, registered);
                    } catch (Exception ignored) {
                    } finally {
                        PENDING.remove(username);
                    }
                });
            } catch (Exception ignored) {
                PENDING.remove(username);
            }
        }, "raven-head-" + username).start();
    }
}
