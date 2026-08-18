package mindless.script.model;

import mindless.script.ScriptDefaults;
import mindless.utility.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Image {
    private static final int MAX_CACHED_IMAGES = 64;
    private static final ConcurrentHashMap<String, BufferedImage> imageCache = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Image> textureReferenceQueue = new ReferenceQueue<>();
    private static final Set<TextureReference> textureReferences = new HashSet<>();
    public String url;
    public BufferedImage bufferedImage;
    public int height;
    public int width;
    public int textureId;
    public boolean cached;

    public Image(String url, boolean cached) {
        this.textureId = -1;
        this.url = url;
        this.cached = cached;
        final BufferedImage cachedImage = cached ? imageCache.get(url) : null;
        if (cachedImage == null) {
            ScriptDefaults.client.async(() -> {
                BufferedImage newImage = NetworkUtils.getImageFromURL(url);
                if (newImage != null) {
                    this.bufferedImage = newImage;
                    this.height = newImage.getHeight();
                    this.width = newImage.getWidth();
                    if (cached) {
                        cacheImage(url, newImage);
                    }
                }
            });
        }
        else {
            this.bufferedImage = cachedImage;
            this.height = cachedImage.getHeight();
            this.width = cachedImage.getWidth();
        }
    }

    public float[] getDimensions() {
        final int scaleFactor = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        return new float[] { this.width / scaleFactor, this.height / scaleFactor };
    }

    public boolean isLoaded() {
        return this.bufferedImage != null;
    }

    public static void clearCache() {
        imageCache.clear();
        synchronized (textureReferences) {
            for (TextureReference reference : textureReferences) {
                Image image = reference.get();
                if (image != null && image.textureId == reference.textureId) {
                    image.textureId = -1;
                }
                deleteTexture(reference);
            }
            textureReferences.clear();
            while (textureReferenceQueue.poll() != null) {
                // Drain references whose textures were deleted above.
            }
        }
    }

    private static void cacheImage(String url, BufferedImage image) {
        if (!imageCache.containsKey(url) && imageCache.size() >= MAX_CACHED_IMAGES) {
            java.util.Iterator<String> iterator = imageCache.keySet().iterator();
            if (iterator.hasNext()) imageCache.remove(iterator.next());
        }
        imageCache.put(url, image);
    }

    public static void trackTexture(Image image, int textureId) {
        if (image == null || textureId <= 0) return;
        synchronized (textureReferences) {
            releaseCollectedTexturesLocked();
            textureReferences.add(new TextureReference(image, textureId));
        }
    }

    public static void releaseCollectedTextures() {
        synchronized (textureReferences) {
            releaseCollectedTexturesLocked();
        }
    }

    private static void releaseCollectedTexturesLocked() {
        TextureReference reference;
        while ((reference = (TextureReference) textureReferenceQueue.poll()) != null) {
            deleteTexture(reference);
            textureReferences.remove(reference);
        }
    }

    private static void deleteTexture(TextureReference reference) {
        if (reference.deleted) return;
        GL11.glDeleteTextures(reference.textureId);
        reference.deleted = true;
    }

    private static final class TextureReference extends WeakReference<Image> {
        private final int textureId;
        private boolean deleted;

        private TextureReference(Image image, int textureId) {
            super(image, textureReferenceQueue);
            this.textureId = textureId;
        }
    }

    @Override
    public String toString() {
        return "Image(" + this.height + "," + this.width + "," + this.url + ")";
    }
}
