package mindless.utility.font;

import mindless.utility.ScaledResolutionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class FontManager {
    private static final String MINECRAFT = "Minecraft";
    private static final String RESOURCE_ROOT = "/assets/mindless/fonts/";
private static final int MAX_CACHED_RENDERERS = 512;
    private static final float DEFAULT_HUD_FONT_SIZE = 10.0f;
    private static final float DEFAULT_CLICK_GUI_HEADER_HEIGHT = 11.0f;
    private static final float DEFAULT_CLICK_GUI_SETTING_HEIGHT = 13.0f;
    private static final float DEFAULT_CLICK_GUI_SMALL_HEIGHT = 9.0f;
    private static final float DEFAULT_NAMETAG_FONT_SIZE = 9.0f;
private static final float NAMETAG_ATLAS_BOOST = 2.5f;
    private static final BundledFont[] BUNDLED_FONTS = {
            new BundledFont("Sf-Regular", "Sf-Regular.ttf"),
            new BundledFont("Inter", "Inter.ttf"),
            new BundledFont("Arial", "Arial.ttf"),
            new BundledFont("Sf-Bold", "Sf-Bold.ttf"),
            new BundledFont("Sf-Ui", "Sf-Ui.ttf"),
            new BundledFont("Poppins", "Poppins-Regular.ttf"),
            new BundledFont("Lato", "Lato-Regular.ttf"),
            new BundledFont("Varela Round", "VarelaRound-Regular.ttf"),
            new BundledFont("Titillium Web", "TitilliumWeb-Regular.ttf"),
            new BundledFont("JetBrains Mono", "JetBrainsMono-Regular.ttf"),
            new BundledFont("Comfortaa", "Comfortaa-Light.ttf"),
            new BundledFont("Google Sans", "GoogleSans-Regular.ttf"),
            new BundledFont("Google Sans Medium", "GoogleSans-Medium.ttf")
    };
    private static final String[] HUD_FONT_OPTIONS = buildHudFontOptions();
    private static final Map<String, BundledFont> BUNDLED_FONT_MAP = buildBundledFontMap();
    private static final Map<String, Font> BASE_FONT_CACHE = new ConcurrentHashMap<String, Font>();
    private static final Map<String, MindlessFontRenderer> FONT_CACHE = new LinkedHashMap<String, MindlessFontRenderer>(16, 0.75f, true);
    /** Evicted renderers awaiting teardown, oldest first. Insertion-ordered on purpose. */
    private static final Map<String, MindlessFontRenderer> RETIRED_RENDERERS = new LinkedHashMap<String, MindlessFontRenderer>();
    private static final Map<String, Long> RETIRED_AT = new HashMap<String, Long>();
    private static final long RETIRE_GRACE_NANOS = 10L * 1_000_000_000L;
    private static final int MAX_RETIRED_RENDERERS = 64;

    private FontManager() {
    }

    public static String[] getHudFontOptions() {
        return HUD_FONT_OPTIONS.clone();
    }

    public static MindlessFontRenderer getHudRenderer(String family, float scale) {
        float safeScale = snapFontScale(Math.max(0.5f, Math.min(2.0f, scale)));
        return getRenderer(family, DEFAULT_HUD_FONT_SIZE * safeScale);
    }

    /**
     * A HUD renderer that may go past the usual 2x ceiling.
     *
     * Overlays that scale themselves used to rasterise at the capped size and then stretch the
     * result with a modelview scale, which is what made large text look soft. Asking for the
     * glyphs at the size they will actually occupy keeps them sharp instead.
     *
     * Only pass a size that comes from a discrete setting. snapFontScale quantises to 0.05, so a
     * slider's worth of values costs a bounded handful of cache entries; a value that changes
     * every frame would miss the cache constantly and rebuild a 256-glyph atlas each time.
     */
    public static MindlessFontRenderer getLargeHudRenderer(String family, float scale) {
        float safeScale = snapFontScale(Math.max(0.5f, Math.min(4.0f, scale)));
        return getRenderer(family, DEFAULT_HUD_FONT_SIZE * safeScale);
    }

    public static MindlessFontRenderer getClickGuiHeaderRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_HEADER_HEIGHT);
    }

    public static MindlessFontRenderer getClickGuiSettingRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_SETTING_HEIGHT);
    }

    public static MindlessFontRenderer getClickGuiSmallRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_SMALL_HEIGHT);
    }
public static MindlessFontRenderer getNametagRenderer(String family) {
        float fontSize = DEFAULT_NAMETAG_FONT_SIZE;
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(fontSize);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(fontSize);
        }

        String key = family + "#nametag#" + fontSize + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<MindlessFontRenderer>() {
            @Override
            public MindlessFontRenderer get() {
                Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
                if (baseFont == null) {
                    return getMinecraftRenderer(fontSize);
                }

                return new GlyphFontRenderer(baseFont.deriveFont(fontSize), true, NAMETAG_ATLAS_BOOST);
            }
        });
    }

    private static MindlessFontRenderer getRenderer(String family, float fontSize) {
        float safeFontSize = snapFontSize(Math.max(1.0f, fontSize));
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeFontSize);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(safeFontSize);
        }

        String key = family + "#" + safeFontSize + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<MindlessFontRenderer>() {
            @Override
            public MindlessFontRenderer get() {
            Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
            if (baseFont == null) {
                return getMinecraftRenderer(safeFontSize);
            }

            return new GlyphFontRenderer(baseFont.deriveFont(safeFontSize), true);
            }
        });
    }
public static MindlessFontRenderer getClickGuiRenderer(String family, float pixelHeight) {
        return getRendererForPixelHeight(family, pixelHeight);
    }

    private static MindlessFontRenderer getRendererForPixelHeight(String family, float targetHeight) {
        float safeTargetHeight = snapFontSize(Math.max(1.0f, targetHeight));
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        String key = family + "#height#" + safeTargetHeight + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<MindlessFontRenderer>() {
            @Override
            public MindlessFontRenderer get() {
            Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
            if (baseFont == null) {
                return getMinecraftRenderer(safeTargetHeight);
            }

            return createHeightMatchedRenderer(baseFont, safeTargetHeight);
            }
        });
    }

    private static MindlessFontRenderer createHeightMatchedRenderer(Font baseFont, float targetHeight) {
        float derivedSize = targetHeight;
        GlyphFontRenderer renderer = new GlyphFontRenderer(baseFont.deriveFont(derivedSize), true);

        for (int i = 0; i < 2; i++) {
            float measuredHeight = Math.max(1.0f, renderer.getFontHeight());
            float difference = Math.abs(measuredHeight - targetHeight);
            if (difference <= 0.5f) {
                break;
            }

            GlyphFontRenderer previousRenderer = renderer;
            derivedSize = Math.max(1.0f, derivedSize * (targetHeight / measuredHeight));
            renderer = new GlyphFontRenderer(baseFont.deriveFont(derivedSize), true);
            previousRenderer.destroy();
        }

        return renderer;
    }

    private static MindlessFontRenderer getMinecraftRenderer(float fontSize) {
        float vanillaHeight = Math.max(1.0f, Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT);
        float scale = snapFontScale(Math.max(0.5f, Math.min(2.0f, fontSize / vanillaHeight)));
        String key = MINECRAFT + "#" + scale;
        return getCachedRenderer(key, new Supplier<MindlessFontRenderer>() {
            @Override
            public MindlessFontRenderer get() {
                return new MinecraftFontAdapter(Minecraft.getMinecraft().fontRendererObj, scale);
            }
        });
    }

    public static boolean isMinecraftFont(String family) {
        return family == null || MINECRAFT.equalsIgnoreCase(family);
    }

    private static String[] buildHudFontOptions() {
        String[] options = new String[BUNDLED_FONTS.length + 1];
        options[0] = MINECRAFT;

        for (int i = 0; i < BUNDLED_FONTS.length; i++) {
            options[i + 1] = BUNDLED_FONTS[i].displayName;
        }

        return options;
    }

    private static Map<String, BundledFont> buildBundledFontMap() {
        LinkedHashMap<String, BundledFont> fontMap = new LinkedHashMap<String, BundledFont>();

        for (BundledFont bundledFont : BUNDLED_FONTS) {
            fontMap.put(bundledFont.displayName, bundledFont);
        }

        return fontMap;
    }

    private static Font loadBaseFont(String fileName) {
        byte[] fontData = readFontData(fileName);
        if (fontData == null) {
            return null;
        }

        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(fontData));
        }
        catch (Exception ignored) {
            try {
                return Font.createFont(Font.TYPE1_FONT, new ByteArrayInputStream(fontData));
            }
            catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static int getUiScale() {
        try {
            return Math.max(1, ScaledResolutionCache.get().getScaleFactor());
        }
        catch (Exception ignored) {
            return 1;
        }
    }
/**
     * Snaps a font size to a quarter of a pixel.
     *
     * Every distinct size is a separate GlyphFontRenderer: the whole alphabet rasterised through
     * Graphics2D and uploaded as its own atlas texture. Keying at a hundredth of a pixel meant an
     * animated caller -- a widget easing its size, anything driven by an eased scale -- asked for a
     * size nothing had built yet on almost every frame, so it built one, and the 512-entry LRU
     * evicted and destroyed somebody else's to make room. A quarter pixel is below what a 6-20px
     * face resolves, and it collapses that range to a few dozen renderers that stay warm.
     */
    private static float snapFontSize(float value) {
        return Math.round(value * 4.0f) / 4.0f;
    }

    /** Scales multiply a base size, so they need finer steps than a size does. */
    private static float snapFontScale(float value) {
        return Math.round(value * 20.0f) / 20.0f;
    }

    private static byte[] readFontData(String fileName) {
        try (InputStream inputStream = FontManager.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (inputStream == null) {
                return null;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;

            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }

            return outputStream.toByteArray();
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static synchronized MindlessFontRenderer getCachedRenderer(String key, Supplier<MindlessFontRenderer> rendererSupplier) {
        MindlessFontRenderer renderer = FONT_CACHE.get(key);
        if (renderer != null) {
            return renderer;
        }

        // Evicted but not yet torn down: take it back rather than rasterising a fresh atlas for a
        // font we already have. This is also what stops an overlay that is drawn every other
        // frame from paying for a rebuild each time it comes back.
        MindlessFontRenderer reprieved = RETIRED_RENDERERS.remove(key);
        if (reprieved != null) {
            FONT_CACHE.put(key, reprieved);
            sweepRetiredRenderers();
            return reprieved;
        }

        renderer = rendererSupplier.get();
        FONT_CACHE.put(key, renderer);
        trimFontCache();
        sweepRetiredRenderers();
        return renderer;
    }

    /**
     * Evicting a renderer used to destroy it on the spot, which deletes its glyph atlas texture.
     * Anything still holding that renderer -- SpotifyMiniPlayerRenderer keeps one in a static
     * field across frames -- was then drawing against a deleted texture.
     *
     * Eviction now only retires: the renderer leaves the live cache but keeps its atlas for a
     * grace period. If it is asked for again in that window it comes straight back, and only a
     * renderer nobody has wanted for the whole period is actually destroyed. Holders get a
     * bounded window rather than a promise, which is the most a cache can offer without every
     * caller releasing explicitly, and it is far longer than any frame.
     */
    private static void trimFontCache() {
        while (FONT_CACHE.size() > MAX_CACHED_RENDERERS) {
            Iterator<Map.Entry<String, MindlessFontRenderer>> iterator = FONT_CACHE.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }

            Map.Entry<String, MindlessFontRenderer> eldestEntry = iterator.next();
            String eldestKey = eldestEntry.getKey();
            MindlessFontRenderer eldest = eldestEntry.getValue();
            iterator.remove();
            if (eldest != null) {
                RETIRED_RENDERERS.put(eldestKey, eldest);
                RETIRED_AT.put(eldestKey, System.nanoTime());
            }
        }
    }

    /** Destroys retired renderers once the grace period has passed with no further requests. */
    private static void sweepRetiredRenderers() {
        if (RETIRED_RENDERERS.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<String, MindlessFontRenderer>> iterator = RETIRED_RENDERERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, MindlessFontRenderer> entry = iterator.next();
            Long retiredAt = RETIRED_AT.get(entry.getKey());
            boolean expired = retiredAt == null || now - retiredAt > RETIRE_GRACE_NANOS;
            // A hard ceiling as well, so a pathological run of misses cannot pile up atlases
            // faster than the grace period clears them.
            if (expired || RETIRED_RENDERERS.size() > MAX_RETIRED_RENDERERS) {
                RETIRED_AT.remove(entry.getKey());
                iterator.remove();
                if (entry.getValue() != null) {
                    entry.getValue().destroy();
                }
                continue;
            }
            // Insertion-ordered, so the first unexpired entry means the rest are younger still.
            break;
        }
    }

    public static void preWarmFonts() {
        Thread t = new Thread(() -> {
            for (BundledFont font : BUNDLED_FONTS) {
                try {
                    BASE_FONT_CACHE.computeIfAbsent(font.fileName, FontManager::loadBaseFont);
                } catch (Exception ignored) {}
            }
        }, "FontPreWarm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final class BundledFont {
        private final String displayName;
        private final String fileName;

        private BundledFont(String displayName, String fileName) {
            this.displayName = displayName;
            this.fileName = fileName;
        }
    }
}
