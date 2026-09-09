package mindless.accountmanager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full skin textures, as opposed to the 32px head crops PlayerHeadCache holds.
 *
 * A body preview needs the whole sheet: the head cache stores an avatar render, which has no
 * arms, legs or second layer in it to draw.
 *
 * Legacy 64x32 skins are padded to 64x64 with the limbs mirrored into the slots a modern skin
 * would use, so one set of model UVs works for both instead of the preview needing two.
 */
public final class PlayerSkinCache {
    private static final ConcurrentHashMap<String, ResourceLocation> CACHE = new ConcurrentHashMap<String, ResourceLocation>();
    private static final ConcurrentHashMap<String, Boolean> PENDING = new ConcurrentHashMap<String, Boolean>();
    private static final ConcurrentHashMap<String, Boolean> SLIM = new ConcurrentHashMap<String, Boolean>();

    private PlayerSkinCache() {
    }

    public static ResourceLocation get(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        ResourceLocation loc = CACHE.get(username);
        if (loc != null) {
            return loc;
        }
        fetch(username);
        return null;
    }

    /** Whether the fetched sheet looks like a slim (Alex) skin. */
    public static boolean isSlim(String username) {
        if (username == null) {
            return false;
        }
        Boolean slim = SLIM.get(username);
        return slim != null && slim.booleanValue();
    }

    public static void invalidate(String username) {
        if (username == null) {
            return;
        }
        CACHE.remove(username);
        SLIM.remove(username);
    }

    private static void fetch(final String username) {
        if (CACHE.containsKey(username)) {
            return;
        }
        if (PENDING.putIfAbsent(username, Boolean.TRUE) != null) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("https://mineskin.eu/skin/" + username + ".png");
                    BufferedImage raw = ImageIO.read(url);
                    if (raw == null) {
                        PENDING.remove(username);
                        return;
                    }
                    final BufferedImage sheet = normalise(raw);
                    final boolean slim = detectSlim(sheet);
                    final Minecraft mc = Minecraft.getMinecraft();
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ResourceLocation registered = mc.getTextureManager()
                                        .getDynamicTextureLocation("mindless_skin_" + username.toLowerCase(),
                                                new DynamicTexture(sheet));
                                CACHE.put(username, registered);
                                SLIM.put(username, Boolean.valueOf(slim));
                            }
                            catch (Exception ignored) {
                            }
                            finally {
                                PENDING.remove(username);
                            }
                        }
                    });
                }
                catch (Exception ignored) {
                    PENDING.remove(username);
                }
            }
        }, "mindless-skin-" + username).start();
    }

    /** Pad a 64x32 sheet up to 64x64, mirroring the single arm and leg into the second set. */
    private static BufferedImage normalise(BufferedImage source) {
        if (source.getHeight() >= 64) {
            return source;
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(source, 0, 0, null);
        copyMirrored(g, source, 4, 16, 4, 4, 20, 48);
        copyMirrored(g, source, 8, 16, 4, 4, 24, 48);
        copyMirrored(g, source, 0, 20, 4, 12, 24, 52);
        copyMirrored(g, source, 4, 20, 4, 12, 20, 52);
        copyMirrored(g, source, 8, 20, 4, 12, 16, 52);
        copyMirrored(g, source, 12, 20, 4, 12, 28, 52);
        copyMirrored(g, source, 44, 16, 4, 4, 36, 48);
        copyMirrored(g, source, 48, 16, 4, 4, 40, 48);
        copyMirrored(g, source, 40, 20, 4, 12, 40, 52);
        copyMirrored(g, source, 44, 20, 4, 12, 36, 52);
        copyMirrored(g, source, 48, 20, 4, 12, 32, 52);
        copyMirrored(g, source, 52, 20, 4, 12, 44, 52);
        g.dispose();
        return out;
    }

    private static void copyMirrored(java.awt.Graphics2D g, BufferedImage source,
                                     int sx, int sy, int w, int h, int dx, int dy) {
        g.drawImage(source, dx + w, dy, dx, dy + h, sx, sy, sx + w, sy + h, null);
    }

    /**
     * Slim skins leave the two right-hand columns of the arm box empty.
     *
     * Checked on the pixels rather than trusting a name lookup, because the sheet is all we
     * fetched and a wrong guess shows as a visibly broken arm.
     */
    private static boolean detectSlim(BufferedImage sheet) {
        if (sheet.getWidth() < 64 || sheet.getHeight() < 64) {
            return false;
        }
        for (int y = 20; y < 32; y++) {
            for (int x = 54; x < 56; x++) {
                if ((sheet.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
