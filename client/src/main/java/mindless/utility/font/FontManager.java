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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class FontManager {
    private static final String MINECRAFT = "Minecraft";
    private static final String RESOURCE_ROOT = "/assets/mindless/fonts/";
    /** Large enough that HUD font-size drags + clickgui + nametags do not evict active renderers every frame. */
    private static final int MAX_CACHED_RENDERERS = 512;
    private static final float DEFAULT_HUD_FONT_SIZE = 10.0f;
    // Render GUI glyphs slightly above their final display size, then let the
    // existing UI scale them down. This produces cleaner curves and baselines
    // than enlarging a tiny 10-12px glyph atlas, with no per-frame font work.
    private static final float DEFAULT_CLICK_GUI_HEADER_HEIGHT = 11.0f;
    private static final float DEFAULT_CLICK_GUI_SETTING_HEIGHT = 13.0f;
    // Small font rasterised at exactly 9px — used at scale 1.0 so the atlas
    // is never downsampled and glyphs stay crisp without bilinear blur.
    private static final float DEFAULT_CLICK_GUI_SMALL_HEIGHT = 9.0f;
    private static final float DEFAULT_NAMETAG_FONT_SIZE = 9.0f;
    /** Nametags are magnified in world space after they are drawn; see getNametagRenderer. */
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
            // Appended rather than inserted. Every font picker in the client is a slider over this
            // array and profiles store the chosen index, so putting a new family anywhere but the
            // end would silently move everyone onto a different font.
            //
            // Both faces are subset to Latin plus the symbols the UI actually draws -- Hypixel's
            // stars, the ESP heart, arrows, box-drawing. Shipped whole they are 1.9MB each against
            // 76KB here, and nothing in the client asks for the 6800 glyphs that were dropped.
            new BundledFont("Google Sans", "GoogleSans-Regular.ttf"),
            new BundledFont("Google Sans Medium", "GoogleSans-Medium.ttf")
    };
    private static final String[] HUD_FONT_OPTIONS = buildHudFontOptions();
    private static final Map<String, BundledFont> BUNDLED_FONT_MAP = buildBundledFontMap();
    private static final Map<String, Font> BASE_FONT_CACHE = new ConcurrentHashMap<String, Font>();
    private static final Map<String, RavenFontRenderer> FONT_CACHE = new LinkedHashMap<String, RavenFontRenderer>(16, 0.75f, true);

    private FontManager() {
    }

    public static String[] getHudFontOptions() {
        return HUD_FONT_OPTIONS.clone();
    }

    public static RavenFontRenderer getHudRenderer(String family, float scale) {
        float safeScale = Math.max(0.5f, Math.min(2.0f, scale));
        return getRenderer(family, DEFAULT_HUD_FONT_SIZE * safeScale);
    }

    public static RavenFontRenderer getClickGuiHeaderRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_HEADER_HEIGHT);
    }

    public static RavenFontRenderer getClickGuiSettingRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_SETTING_HEIGHT);
    }

    public static RavenFontRenderer getClickGuiSmallRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_SMALL_HEIGHT);
    }

    /**
     * Nametags are the one place text is magnified after it is drawn.
     *
     * <p>Every other renderer is rasterised at roughly the size it ends up on screen. A nametag is
     * drawn at a fixed nine units and then scaled into world space, so how many pixels a glyph
     * actually covers depends on how far away the player is standing -- close up it is two or
     * three times what the atlas holds, and a magnified atlas is a blurry one. The boost gives it
     * that headroom. Metrics are divided back down by the same factor, so nothing moves.
     */
    public static RavenFontRenderer getNametagRenderer(String family) {
        float fontSize = DEFAULT_NAMETAG_FONT_SIZE;
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(fontSize);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(fontSize);
        }

        String key = family + "#nametag#" + quantizeForCacheKey(fontSize) + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
                Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
                if (baseFont == null) {
                    return getMinecraftRenderer(fontSize);
                }

                return new GlyphFontRenderer(baseFont.deriveFont(fontSize), true, NAMETAG_ATLAS_BOOST);
            }
        });
    }

    private static RavenFontRenderer getRenderer(String family, float fontSize) {
        float safeFontSize = Math.max(1.0f, fontSize);
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeFontSize);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(safeFontSize);
        }

        String key = family + "#" + quantizeForCacheKey(safeFontSize) + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
            Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
            if (baseFont == null) {
                return getMinecraftRenderer(safeFontSize);
            }

            return new GlyphFontRenderer(baseFont.deriveFont(safeFontSize), true);
            }
        });
    }

    /**
     * Renderer rasterised at an exact pixel height, for callers that draw at scale 1.0.
     * Drawing a 13px atlas at 0.63x, or an 11px atlas at 1.33x, is what makes GUI text mushy;
     * asking for the height actually needed keeps every glyph on its native grid.
     */
    public static RavenFontRenderer getClickGuiRenderer(String family, float pixelHeight) {
        return getRendererForPixelHeight(family, pixelHeight);
    }

    private static RavenFontRenderer getRendererForPixelHeight(String family, float targetHeight) {
        float safeTargetHeight = Math.max(1.0f, targetHeight);
        BundledFont bundledFont;

        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        bundledFont = BUNDLED_FONT_MAP.get(family);
        if (bundledFont == null) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        String key = family + "#height#" + quantizeForCacheKey(safeTargetHeight) + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
            Font baseFont = BASE_FONT_CACHE.computeIfAbsent(bundledFont.fileName, FontManager::loadBaseFont);
            if (baseFont == null) {
                return getMinecraftRenderer(safeTargetHeight);
            }

            return createHeightMatchedRenderer(baseFont, safeTargetHeight);
            }
        });
    }

    private static RavenFontRenderer createHeightMatchedRenderer(Font baseFont, float targetHeight) {
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

    private static RavenFontRenderer getMinecraftRenderer(float fontSize) {
        float vanillaHeight = Math.max(1.0f, Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT);
        float scale = Math.max(0.5f, Math.min(2.0f, fontSize / vanillaHeight));
        String key = MINECRAFT + "#" + quantizeForCacheKey(scale);
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
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

    /** Fewer unique keys when sliders move smoothly; avoids LRU evicting live glyph textures every frame. */
    private static float quantizeForCacheKey(float value) {
        return Math.round(value * 100.0f) / 100.0f;
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

    private static synchronized RavenFontRenderer getCachedRenderer(String key, Supplier<RavenFontRenderer> rendererSupplier) {
        RavenFontRenderer renderer = FONT_CACHE.get(key);
        if (renderer != null) {
            return renderer;
        }

        renderer = rendererSupplier.get();
        FONT_CACHE.put(key, renderer);
        trimFontCache();
        return renderer;
    }

    private static void trimFontCache() {
        while (FONT_CACHE.size() > MAX_CACHED_RENDERERS) {
            Iterator<Map.Entry<String, RavenFontRenderer>> iterator = FONT_CACHE.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }

            Map.Entry<String, RavenFontRenderer> eldestEntry = iterator.next();
            iterator.remove();
            if (eldestEntry.getValue() != null) {
                eldestEntry.getValue().destroy();
            }
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
