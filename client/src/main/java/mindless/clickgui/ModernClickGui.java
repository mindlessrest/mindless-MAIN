package mindless.clickgui;

import mindless.Mindless;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.clickgui.components.impl.ModuleComponent;
import mindless.module.Module;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.*;
import mindless.module.impl.client.Gui;
import mindless.utility.BlockSearchIndex;
import mindless.utility.ItemSearchIndex;
import mindless.utility.PlayerRelationsManager;
import mindless.utility.PotionSearchIndex;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.profile.Manager;
import mindless.utility.profile.Profile;
import mindless.utility.profile.ProfileModule;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
public final class ModernClickGui extends ClickGui {
    private static final String LOGO_RESOURCE = "/assets/mindless/textures/gui/logo.png";
    private static final String FALLBACK_FONT_REGULAR = "Sf-Regular";
    private static final String FALLBACK_FONT_BOLD = "Sf-Bold";
    private static final float LOGO_DRAW_W = 30f;
    private static final float LOGO_DRAW_H = 20f;
private static final int LOGO_RASTER_W = (int) LOGO_DRAW_W * 4;
    private static final int LOGO_RASTER_H = (int) LOGO_DRAW_H * 4;
    private static final float CATEGORY_ROW_HEIGHT = 21f;
    private static final float CATEGORY_ROW_STEP = 22f;
    private static final float MODULE_ROW_HEIGHT = 26f;
    private static final float MODULE_ROW_STEP = 30f;
    private static final float DROPDOWN_MIN_W = 78f;
    private static final float DROPDOWN_MAX_W = 156f;
private static final float DROPDOWN_LABEL_GAP = 10f;
private static final float CONTROL_MIN_W = 92f;
private static final float VALUE_MIN_W = 16f;
private static final float VALUE_MAX_W = 52f;
private static final float VALUE_GAP = 8f;
private static final float LABEL_GAP = 10f;
private static final float[] LABEL_SCALES = {.75f, .70f, .64f, .58f};
private static final float SETTING_GAP = 4f;
private static final int MAX_SEGMENTS = 3;
private static final float SEGMENT_GAP = 4f;
private static final float CONTROL_HEIGHT = 22f;

    private static int ACCENT = argb(255, 159, 143, 210);
    private static int ACCENT_SOFT = argb(42, 159, 143, 210);
    private static int ACCENT_FOREGROUND = argb(255, 25, 22, 31);
    private static int GOLD = ACCENT;
    private static int GOLD_SOFT = ACCENT_SOFT;
    private static final int DEFAULT_PANEL = argb(232, 13, 16, 18);
    private static final int DEFAULT_PANEL_ALT = argb(236, 15, 18, 20);
    private static final int DEFAULT_ROW = argb(224, 24, 27, 28);
    private static final int DEFAULT_ROW_HOVER = argb(236, 31, 34, 35);
    private static final int DEFAULT_CONTROL = argb(118, 7, 9, 10);
    private static final int DEFAULT_CONTROL_HOVER = argb(150, 28, 30, 31);
    private static final int DEFAULT_BORDER = argb(52, 210, 210, 204);
    private static final int DEFAULT_DIVIDER = argb(65, 210, 210, 204);

    private static int PANEL = DEFAULT_PANEL;
    private static int PANEL_ALT = DEFAULT_PANEL_ALT;
    private static int ROW = DEFAULT_ROW;
    private static int ROW_HOVER = DEFAULT_ROW_HOVER;
    private static int CONTROL = DEFAULT_CONTROL;
    private static int CONTROL_HOVER = DEFAULT_CONTROL_HOVER;
    private static int BORDER = DEFAULT_BORDER;
    private static int DIVIDER = DEFAULT_DIVIDER;
    private static int DROPDOWN_BG = DEFAULT_PANEL_ALT;
    private static int DROPDOWN_BORDER = DEFAULT_BORDER;
    private static int DROPDOWN_SELECTED = argb(80, 159, 143, 210);
private static int surfaceSeed = -1;
    private static int TEXT = argb(255, 235, 234, 230);
    private static int MUTED = argb(255, 157, 158, 156);
    private static int DIM = argb(255, 105, 108, 108);
    private static final int DANGER = argb(255, 219, 104, 100);
    private static final float TEXT_SCALE = .92f;
private float sliderValueWidth = VALUE_MIN_W;
    private float settingLabelScale = LABEL_SCALES[0];
private float settingControlLeft = Float.NaN;
    private Module.category selectedCategory = Module.category.combat;
    private Module selectedModule;
    private float moduleScroll;
    private float settingScroll;
    private float moduleScrollTarget;
    private float settingScrollTarget;
    private String search = "";
    private boolean searchFocused;
    private int searchCaret;
    private int searchSelectionAnchor;
    private float searchEditAnimation = 1f;
    private TextSetting activeText;
    private Setting activeList;
    private String listDraft = "";
private final TextEditor editor = new TextEditor();
    private Setting suggestionSetting;
    private String suggestionQuery = "";
    private List<Suggestion> suggestionCache = Collections.emptyList();
    private Object binding;
    private SliderSetting draggingSlider;
    private SliderSetting editingSliderValue;
    private String sliderEditDraft = "";
    private int sliderEditCaret;
    private int sliderEditAnchor;
    private SliderSetting openDropdown;
private float dropdownAnchorY = 0f;
private float dropdownWidth = DROPDOWN_MAX_W;
private float dropdownScroll = 0f;
    private float dropdownScrollTarget = 0f;
private float dropdownViewH = 0f;
    private float dropdownFullH = 0f;
private float dropdownRowTop = 0f;
    private boolean dropdownFlipped = false;
private void layoutDropdown() {
        if (openDropdown == null || openDropdown.getOptions() == null) {
            dropdownViewH = 0f;
            dropdownFullH = 0f;
            dropdownFlipped = false;
            return;
        }
        float fullH = openDropdown.getOptions().length * 21f + 4f;
        float panelTop = baseY + 8f;
        float panelBottom = baseY + panelH - 4f;
        float controlBottom = dropdownAnchorY + 30f;
        float controlTop = dropdownAnchorY + 6f;
        float roomBelow = panelBottom - controlBottom;
        float roomAbove = controlTop - panelTop;
        boolean flip = roomBelow < Math.min(fullH, 63f) && roomAbove > roomBelow;
        float viewH = Math.max(23f, Math.min(fullH, flip ? roomAbove : roomBelow));
        dropdownFullH = fullH;
        dropdownViewH = viewH;
        dropdownFlipped = flip;
        dropdownRowTop = flip ? controlTop - viewH : controlBottom;
    }
    private ColorSetting openColor;
    private int draggingScrollbar;
    private float scrollbarDragOffset;
    private ModuleSnapshot moduleSnapshot;
    private int colorDrag;
    private String commandDraft = "";
    private boolean commandFocused;

    private float baseX;
    private float baseY;
    private float panelH;
    private float sideW;
    private float centerW;
    private float detailW;
    private float centerX, detailX;
    private float settingsContentHeight;
    private float modulesContentHeight;
private float detailPanelOpen = 0f;
private Module lastRenderedModule = null;
private float detailContentReveal = 0f;
private float guiOpenProgress = 0f;
private boolean guiClosing = false;
private float aboutOpenProgress = 0f;
    private final Rect sliderRect = new Rect();
    private final Rect colorSB = new Rect();
    private final Rect colorHue = new Rect();
    private final Rect colorAlpha = new Rect();
    private final Map<Object, Float> hoverAnimation = new IdentityHashMap<Object, Float>();
    private final Map<Object, Float> selectedAnimation = new IdentityHashMap<Object, Float>();
    private final Map<Object, Float> toggleAnimation = new IdentityHashMap<Object, Float>();
    private final Map<Object, Float> dropdownAnimation = new IdentityHashMap<Object, Float>();
    private final Map<Object, Float> controlAnimation = new IdentityHashMap<Object, Float>();
    private final Map<SliderSetting, Object> sliderValueAnimationKeys = new IdentityHashMap<SliderSetting, Object>();
    private final Map<SliderSetting, Float> sliderProgressAnimation = new IdentityHashMap<SliderSetting, Float>();
    private final Object searchAnimationKey = new Object();
    private final Manager profileManagerModule = new Manager();
private static float guiDragOffsetX = 0f;
    private static float guiDragOffsetY = 0f;

    public static float getDragOffsetX() {
        return guiDragOffsetX;
    }

    public static float getDragOffsetY() {
        return guiDragOffsetY;
    }

    public static void setDragOffset(float x, float y) {
        guiDragOffsetX = x;
        guiDragOffsetY = y;
    }
    private boolean draggingGui = false;
    private float dragStartMouseX, dragStartMouseY;
    private float dragStartOffsetX, dragStartOffsetY;
    private boolean draggingMascot = false;
    private float mascotDragOffsetX, mascotDragOffsetY;
    private float mascotDragStartMouseX, mascotDragStartMouseY;
    private float mascotDragStartOffsetX, mascotDragStartOffsetY;
    private float mascotX, mascotY, mascotDrawW, mascotDrawH;
    private long lastFrameNanos = System.nanoTime();
    private float frameDelta = 1f / 60f;
    private float resetHover;
    private float saveHover;
    private boolean uiTextureLoadAttempted;
    private ResourceLocation logoTexture;
    private ResourceLocation mascotTextureCat;
    private ResourceLocation mascotTextureMindless;
    private final Map<Module.category, ResourceLocation> categoryIcons = new IdentityHashMap<Module.category, ResourceLocation>();

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        if (selectedModule != null && !modulesFor(selectedCategory).contains(selectedModule)) {
            selectModule(null);
        } else if (selectedModule != null && (moduleSnapshot == null || moduleSnapshot.module != selectedModule)) {
            moduleSnapshot = new ModuleSnapshot(selectedModule);
        }
        detailPanelOpen = selectedModule != null ? 1f : 0f;
        detailContentReveal = selectedModule != null ? 1f : 0f;
        lastRenderedModule = selectedModule;
        guiClosing = false;
        guiOpenProgress = 0f;
        clampScrolls();
    }

    @Override
    public void refreshAfterProfileLoad() {
        float previousProgress = guiOpenProgress;
        boolean wasVisible = mc != null && mc.currentScreen == this;
        super.refreshAfterProfileLoad();
        if (wasVisible) {
            guiOpenProgress = Math.max(previousProgress, .999f);
            guiClosing = false;
        }
    }

    @Override
    public void enforceHorizontalProfileLayout() {
        float previousProgress = guiOpenProgress;
        boolean wasVisible = mc != null && mc.currentScreen == this;
        super.enforceHorizontalProfileLayout();
        if (wasVisible) {
            guiOpenProgress = Math.max(previousProgress, .999f);
            guiClosing = false;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        RenderUtils.syncGlStateFromDriver();
        updateAnimationClock();
        updateThemePalette();
        double renderScale = getActiveRenderScale();
        int mx = (int) Math.floor(mouseX / renderScale);
        int my = (int) Math.floor(mouseY / renderScale);
        guiOpenProgress = ease(guiOpenProgress, guiClosing ? 0f : 1f, guiClosing ? 60f : 22f);
        if (guiClosing && guiOpenProgress < 0.01f) {
            mc.displayGuiScreen(null);
            return;
        }
        detailPanelOpen = ease(detailPanelOpen, selectedModule != null ? 1f : 0f, 14f);
        if (lastRenderedModule != selectedModule) {
            lastRenderedModule = selectedModule;
            detailContentReveal = 0f;
        }
        if (selectedModule != null) {
            detailContentReveal = ease(detailContentReveal, 1f, 18f);
        }

        computeLayout();
        drawBackdrop(renderScale);
        drawDashboardShadows((float) renderScale);
        GlStateManager.pushMatrix();
        GlStateManager.scale(renderScale, renderScale, 1.0D);
        syncSelectedModule();
        updateSmoothScroll();
        drawMascot();
        drawPanels();
        drawSidebar(mx, my);
        drawModulePanel(mx, my);
        drawSettingsPanel(mx, my);
        updateDragging(mx, my);
        clampScrolls();

        GlStateManager.popMatrix();
    }

    @Override
    public void onGuiClosed() {
        guiClosing = false;
    }

    private void computeLayout() {
        float gap = 9f;
        float totalW = Math.min(700f, width - 18f);
        panelH = Math.max(326f, Math.min(356f, height - 18f));
        sideW = Math.max(104f, totalW * .16f);
        float detailWFull = Math.max(238f, totalW * .35f);
        float t = detailPanelOpen;
        float openEased = t * t * (3f - 2f * t);
        detailW = detailWFull * openEased;
        float usedByDetail = detailW > 1f ? detailW + gap : 0f;
        centerW = totalW - sideW - gap - usedByDetail;
        baseX = snapToPixel(Math.max(5f, (width - totalW) / 2f + guiDragOffsetX));
        baseY = snapToPixel(Math.max(6f, (height - panelH) / 2f + guiDragOffsetY));
        centerX = snapToPixel(baseX + sideW + gap);
        detailX = snapToPixel(centerX + centerW + gap);
    }
private float pixelScale() {
        float scale = (float) getActiveRenderScale()
                * Math.max(1, ScaledResolutionCache.get().getScaleFactor());
        return scale > 0.01f ? scale : 1f;
    }

    private float snapToPixel(float value) {
        float scale = pixelScale();
        return Math.round(value * scale) / scale;
    }

    private void drawPanels() {
        panelSurface(baseX, baseY, baseX + sideW, baseY + panelH, PANEL);
        panelSurface(centerX, baseY, centerX + centerW, baseY + panelH, PANEL);
        if (detailW > 2f) {
            panelSurface(detailX, baseY, detailX + detailW, baseY + panelH, PANEL_ALT);
        }
    }

    private void drawMascot() {
        if (Gui.mascot == null) return;

        int input = (int) Gui.mascot.getInput();
        if (input != 0 && input != 1) return;

        ensureUiTextures();

        ResourceLocation targetTexture = (input == 0) ? mascotTextureMindless : mascotTextureCat;
        if (targetTexture == null) return;

        float mascotH = panelH * 0.75f;
        float mascotW = mascotH;
        float mx = width - mascotW - 50f + mascotDragOffsetX;
        float my = height - mascotH - 50f + mascotDragOffsetY;

        // Keep a grabbable sliver on screen however far she is dragged.
        float margin = Math.min(40f, mascotW * 0.5f);
        mx = Math.max(-mascotW + margin, Math.min(width - margin, mx));
        my = Math.max(-mascotH + margin, Math.min(height - margin, my));

        mascotX = mx;
        mascotY = my;
        mascotDrawW = mascotW;
        mascotDrawH = mascotH;

        drawTextureRegion(targetTexture, mx, my, mascotW, mascotH,
                0, 0, 1, 1, 1, 1, 1f, 1f, 1f, 1f);
    }

    /**
     * She is drawn behind the panels, so she only takes a click that missed all of them.
     */
    private boolean beginMascotDrag(int mx, int my) {
        if (mascotDrawW <= 0f || mascotDrawH <= 0f) return false;
        if (Gui.mascot == null) return false;
        int input = (int) Gui.mascot.getInput();
        if (input != 0 && input != 1) return false;
        if (insideDashboard(mx, my)) return false;
        if (!inside(mx, my, mascotX, mascotY, mascotX + mascotDrawW, mascotY + mascotDrawH)) return false;

        draggingMascot = true;
        mascotDragStartMouseX = mx;
        mascotDragStartMouseY = my;
        mascotDragStartOffsetX = mascotDragOffsetX;
        mascotDragStartOffsetY = mascotDragOffsetY;
        return true;
    }

    private boolean insideDashboard(int mx, int my) {
        if (inside(mx, my, baseX, baseY, baseX + sideW, baseY + panelH)) return true;
        if (inside(mx, my, centerX, baseY, centerX + centerW, baseY + panelH)) return true;
        return detailW > 2f && inside(mx, my, detailX, baseY, detailX + detailW, baseY + panelH);
    }

    private void drawDashboardShadows(float renderScale) {
        drawPanelShadow(baseX, baseY, sideW, panelH, renderScale);
        drawPanelShadow(centerX, baseY, centerW, panelH, renderScale);
        if (detailW > 2f) {
            drawPanelShadow(detailX, baseY, detailW, panelH, renderScale);
        }
    }

    private void drawBackdrop(double renderScale) {
        int backdropWidth = (int) Math.ceil(width * renderScale);
        int backdropHeight = (int) Math.ceil(height * renderScale);

        float t = guiOpenProgress;
        float eased = t * t * (3f - 2f * t);
        float configured = Gui.backgroundBlur == null ? 0f : (float) Gui.backgroundBlur.getInput();
        float blurRadius = 1.65f + configured * .012f;
        BlurUtils.prepareBlur();
        GlStateManager.pushMatrix();
        GlStateManager.scale(renderScale, renderScale, 1.0D);
        rounded(baseX, baseY, baseX + sideW, baseY + panelH, 7f, 0xFFFFFFFF);
        rounded(centerX, baseY, centerX + centerW, baseY + panelH, 7f, 0xFFFFFFFF);
        if (detailW > 2f) {
            rounded(detailX, baseY, detailX + detailW, baseY + panelH, 7f, 0xFFFFFFFF);
        }
        GlStateManager.popMatrix();
        BlurUtils.blurEnd(2, blurRadius, eased * .9f);
        int overlayAlpha = (int)(128 * eased);
        net.minecraft.client.gui.Gui.drawRect(0, 0, backdropWidth, backdropHeight, argb(overlayAlpha, 2, 4, 5));
    }

    private void drawSidebar(int mx, int my) {
        float headerH = 48f;
        ensureUiTextures();
        if (logoTexture != null) {
            drawTextureRegion(logoTexture, baseX + 13, baseY + 12, LOGO_DRAW_W, LOGO_DRAW_H,
                    0, 0, 1, 1, 1, 1,
                    ((ACCENT >> 16) & 255) / 255f, ((ACCENT >> 8) & 255) / 255f,
                    (ACCENT & 255) / 255f, 1f);
            drawTextVCentered("Mindless", baseX + 48, baseY + 8, baseY + 39, ACCENT, .96f, true);
        } else {
            drawText("Mindless", baseX + 16, baseY + 18, ACCENT, 1.02f, true);
        }
        line(baseX + 10, baseY + headerH, baseX + sideW - 10, baseY + headerH, DIVIDER);

        float y = baseY + 55f;
        for (Module.category category : Module.category.values()) {
            if (isPinnedCategory(category)) continue;
            y = drawCategory(category, y, mx, my);
        }
        y += 2f;
        line(baseX + 10, y, baseX + sideW - 10, y, DIVIDER);
        y += 5f;
        y = drawCategory(Module.category.profiles, y, mx, my);
        y = drawCategory(Module.category.scripts, y, mx, my);
        y = drawCategory(Module.category.theme, y, mx, my);
    }
private static boolean isPinnedCategory(Module.category category) {
        return category == Module.category.profiles
                || category == Module.category.scripts
                || category == Module.category.theme;
    }

    private float drawCategory(Module.category category, float y, int mx, int my) {
        float h = CATEGORY_ROW_HEIGHT;
        boolean active = category == selectedCategory;
        boolean hover = inside(mx, my, baseX + 7, y, baseX + sideW - 7, y + h);
        float hp = animate(hoverAnimation, category, hover ? 1f : 0f, 16f);
        float sp = animate(selectedAnimation, category, active ? 1f : 0f, 18f);
        float surface = Math.max(hp * .55f, sp);
        if (surface > .01f) rounded(baseX + 7, y, baseX + sideW - 7, y + h, 5f,
                withAlpha(ACCENT, (int) (surface * 44)));
        if (sp > .01f) {
            float barTop = y + 3 + (1f - sp) * 4f;
            float barBot = y + h - 3 - (1f - sp) * 4f;
            roundedCorners(baseX + 7, barTop, baseX + 9.5f, barBot,
                    0f, 1.25f, 1.25f, 0f, withAlpha(ACCENT, (int) (255 * sp)));
        }
        int foreground = mixColor(MUTED, ACCENT, sp * .85f + hp * .25f);
        drawCategoryIcon(category, baseX + 21, y + h / 2f, foreground);
        drawTextVCentered(categoryName(category), baseX + 34, y, y + h,
                mixColor(MUTED, TEXT, Math.max(hp * .38f, sp * .9f)), .76f, sp > .65f);
        return y + CATEGORY_ROW_STEP;
    }

    private void drawModulePanel(int mx, int my) {
        if (selectedCategory == Module.category.theme && search.trim().isEmpty()) {
            drawThemePanel(mx, my);
            return;
        }
        List<Module> modules = filteredModules();
        Module scriptManager = stickyScriptManager() ? detachScriptManager(modules) : null;
        drawText(search.trim().isEmpty() ? categoryName(selectedCategory) : "Search results",
                centerX + 18, baseY + 18, TEXT, 1.45f, true);
        drawText(modules.size() + (modules.size() == 1 ? " module" : " modules"), centerX + 18, baseY + 40, MUTED, .82f, false);

        float searchW = Math.min(170f, centerW * .43f);
        float sx = centerX + centerW - searchW - 16f;
        boolean searchHover = inside(mx, my, sx, baseY + 15, sx + searchW, baseY + 39);
        float searchState = animate(hoverAnimation, searchAnimationKey, searchFocused ? 1f : searchHover ? .55f : 0f, 16f);
        rounded(sx, baseY + 15, sx + searchW, baseY + 39, 12f, mixColor(CONTROL, CONTROL_HOVER, searchState));
        drawSearchText(sx, baseY + 15, searchW, baseY + 39);
        drawSearchGlyph(sx + searchW - 15, baseY + 27, searchFocused ? ACCENT : MUTED);
        line(centerX + 17, baseY + 55, centerX + centerW - 17, baseY + 55, withAlpha(DIVIDER, 46));
        float top = baseY + 61f;
        float bottom = baseY + panelH - 12f;
        if (scriptManager != null) {
            drawModuleRow(scriptManager, top, mx, my);
            top += MODULE_ROW_STEP;
        }
        scissor(centerX + 8, top, centerX + centerW - 8, bottom, true);
        float y = top + moduleScroll;
        for (Module module : modules) {
            drawModuleRow(module, y, mx, my);
            y += MODULE_ROW_STEP;
        }
        modulesContentHeight = modules.size() * MODULE_ROW_STEP;
        scissor(0, 0, 0, 0, false);
        drawScrollbar(centerX + centerW - 7, top, bottom, moduleScroll, modulesContentHeight);
    }
    private static final int THEME_COLUMNS = 3;
    private static final float THEME_GAP = 9f;
    private static final float THEME_SIDE_PAD = 18f;
    private static final float THEME_CARD_H = 78f;
private static final float THEME_SWATCH_H = 47f;
private static final float THEME_CARD_RADIUS = 10f;

    private float themeCardWidth() {
        float usable = centerW - THEME_SIDE_PAD * 2f - THEME_GAP * (THEME_COLUMNS - 1);
        return Math.max(48f, usable / THEME_COLUMNS);
    }

    private float themeCardX(int column) {
        return centerX + THEME_SIDE_PAD + column * (themeCardWidth() + THEME_GAP);
    }
private void drawThemePanel(int mx, int my) {
        drawText("Theme", centerX + 18, baseY + 18, TEXT, 1.45f, true);
        int count = mindless.module.impl.theme.ThemeManager.themeCount();
        drawText(count + " themes", centerX + 18, baseY + 40, MUTED, .82f, false);

        float searchW = Math.min(170f, centerW * .43f);
        float sx = centerX + centerW - searchW - 16f;
        boolean searchHover = inside(mx, my, sx, baseY + 15, sx + searchW, baseY + 39);
        float searchState = animate(hoverAnimation, searchAnimationKey, searchFocused ? 1f : searchHover ? .55f : 0f, 16f);
        rounded(sx, baseY + 15, sx + searchW, baseY + 39, 12f, mixColor(CONTROL, CONTROL_HOVER, searchState));
        drawSearchText(sx, baseY + 15, searchW, baseY + 39);
        drawSearchGlyph(sx + searchW - 15, baseY + 27, searchFocused ? ACCENT : MUTED);
        line(centerX + 17, baseY + 55, centerX + centerW - 17, baseY + 55, withAlpha(DIVIDER, 46));

        float top = baseY + 61f;
        float bottom = baseY + panelH - 12f;
        scissor(centerX + 8, top, centerX + centerW - 8, bottom, true);

        float step = THEME_CARD_H + THEME_GAP;
        int rows = (count + THEME_COLUMNS - 1) / THEME_COLUMNS;
        for (int i = 0; i < count; i++) {
            float y = top + 6f + moduleScroll + (i / THEME_COLUMNS) * step;
            if (y + THEME_CARD_H < top || y > bottom) continue;
            drawThemeCard(i, themeCardX(i % THEME_COLUMNS), y, mx, my);
        }
        modulesContentHeight = rows * step + 16f;
        scissor(0, 0, 0, 0, false);
        drawScrollbar(centerX + centerW - 7, top, bottom, moduleScroll, modulesContentHeight);
    }

    private void drawThemeCard(int index, float x1, float y, int mx, int my) {
        float w = themeCardWidth();
        float x2 = x1 + w;
        float y2 = y + THEME_CARD_H;
        float splitY = y + THEME_SWATCH_H;
        boolean selected = mindless.module.impl.theme.ThemeManager.selectedIndex() == index;
        boolean hover = inside(mx, my, x1, y, x2, y2);

        Object key = themeCardKey(index);
        float hp = animate(hoverAnimation, key, hover ? 1f : 0f, 16f);
        float sp = animate(selectedAnimation, key, selected ? 1f : 0f, 14f);

        java.awt.Color fromC = mindless.module.impl.theme.ThemeManager.themeGradFrom(index);
        java.awt.Color toC = mindless.module.impl.theme.ThemeManager.themeGradTo(index);
        java.awt.Color accent = mindless.module.impl.theme.ThemeManager.themeAccent(index);
        int from = argb(255, fromC.getRed(), fromC.getGreen(), fromC.getBlue());
        int to = argb(255, toC.getRed(), toC.getGreen(), toC.getBlue());
        int cardColor = mixColor(ROW, ROW_HOVER, Math.max(hp * .85f, sp * .6f));
        if (sp > .01f || hp > .01f) {
            outline(x1, y, x2, y2, THEME_CARD_RADIUS,
                    withAlpha(ACCENT, (int) (215 * sp + 75 * hp * (1f - sp))));
        }
        rounded(x1, y, x2, y2, THEME_CARD_RADIUS, cardColor);
        float r = radius(THEME_CARD_RADIUS, w, THEME_CARD_H);
        gradientRoundedCorners(x1, y, x2, splitY, r, r, 0f, 0f, to, from, to, from);
        resetTextRenderState();

        if (selected) drawCheck(x2 - 13f, y + 13f, argb(255, accent.getRed(), accent.getGreen(), accent.getBlue()));

        float textLeft = x1 + 9f;
        float nameMax = w - 18f;
        drawTextVCentered(trim(mindless.module.impl.theme.ThemeManager.themeName(index), nameMax, .80f, true), textLeft,
                splitY + 2f, splitY + 17f, selected ? TEXT : mixColor(MUTED, TEXT, .5f + hp * .5f), .80f, true);
        drawTextVCentered(selected ? "Active" : "Right click to edit", textLeft, splitY + 15f, y2 - 3f,
                selected ? withAlpha(ACCENT, 235) : withAlpha(DIM, (int) (150 + 80 * hp)), .60f, false);
    }

    private void drawCheck(float cx, float cy, int color) {
        circle(cx, cy, 6.5f, withAlpha(color, 210));
        segments(argb(255, 20, 20, 24),
                cx - 2.8f, cy, cx - 0.9f, cy + 2.2f,
                cx - 0.9f, cy + 2.2f, cx + 3f, cy - 2.4f);
    }

    private final Map<Integer, Object> themeCardKeys = new java.util.HashMap<Integer, Object>();
    private Object themeCardKey(int index) {
        Object k = themeCardKeys.get(index);
        if (k == null) { k = new Object(); themeCardKeys.put(index, k); }
        return k;
    }
private boolean clickThemePanel(int mx, int my, int mouseButton) {
        if (selectedCategory != Module.category.theme || !search.trim().isEmpty()) return false;
        float top = baseY + 61f;
        float bottom = baseY + panelH - 12f;
        if (!inside(mx, my, centerX + 8, top, centerX + centerW - 8, bottom)) return false;

        float w = themeCardWidth();
        float step = THEME_CARD_H + THEME_GAP;
        int count = mindless.module.impl.theme.ThemeManager.themeCount();
        for (int i = 0; i < count; i++) {
            float x1 = themeCardX(i % THEME_COLUMNS);
            float y = top + 6f + moduleScroll + (i / THEME_COLUMNS) * step;
            if (inside(mx, my, x1, y, x1 + w, y + THEME_CARD_H)) {
                mindless.module.impl.theme.ThemeManager.select(i);
                if (mouseButton == 1 && mindless.module.ModuleManager.themeManager != null) {
                    openModule(mindless.module.ModuleManager.themeManager);
                }
                return true;
            }
        }
        return true; // clicks in the picker never fall through to module handling
    }


    private void drawModuleRow(Module module, float y, int mx, int my) {
        float x1 = centerX + 14, x2 = centerX + centerW - 14;
        boolean scriptManager = module instanceof mindless.script.Manager;
        // The manager rows open a screen, they are not toggles. They sit permanently enabled, so
        // the enabled-row treatment lit them up as though every script were running.
        boolean manager = module instanceof Manager || scriptManager;
        boolean selected = module == selectedModule;
        boolean hover = inside(mx, my, x1, y, x2, y + MODULE_ROW_HEIGHT);
        float hp = animate(hoverAnimation, module, hover ? 1f : 0f, 17f);
        float sp = animate(selectedAnimation, module, selected ? 1f : 0f, 19f);
        int rowColor = mixColor(ROW, ROW_HOVER, hp);
        if (module.isEnabled() && !manager) rowColor = mixColor(withAlpha(TEXT, 34), withAlpha(TEXT, 48), hp);
        rowColor = mixColor(rowColor, withAlpha(ACCENT, 55), sp);
        if (scriptManager) rowColor = mixColor(rowColor, withAlpha(ACCENT, 62), .42f);
        rounded(x1, y, x2, y + MODULE_ROW_HEIGHT, 5f, rowColor);
        if (scriptManager) outline(x1, y, x2, y + MODULE_ROW_HEIGHT, 5f, withAlpha(ACCENT, 68));
        if (sp > .01f) {
            float barTop = y + 5 + (1f - sp) * 4f;
            float barBot = y + MODULE_ROW_HEIGHT - 5 - (1f - sp) * 4f;
            roundedCorners(x1, barTop, x1 + 2.5f, barBot,
                    0f, 1.25f, 1.25f, 0f, withAlpha(ACCENT, (int) (255 * sp)));
        }
        boolean profile = module instanceof ProfileModule;
        boolean enabled = module.isEnabled() && !manager;
        float actionX = x2 - 88;
        float availableTextWidth = Math.max(42f, x2 - 70f - x1);
        drawText(trim(module.getName(), availableTextWidth, .73f, true),
                x1 + 10, y + 4.5f, enabled ? TEXT : mixColor(MUTED, TEXT, Math.max(hp * .5f, sp)), .73f, enabled || sp > .5f);
        drawSmallText(trimSmall(moduleDescription(module), availableTextWidth),
                x1 + 10, y + 16f, mixColor(argb(255, 132, 134, 133), MUTED,
                        Math.max(hp * .42f, sp * .62f)));

        if (profile) {
            boolean active = module.isEnabled();
            boolean unsaved = active && !((ProfileModule) module).saved;
            drawCenteredV(active ? (unsaved ? "Unsaved" : "Active") : "Load",
                    actionX - 4, actionX + 34, y, y + MODULE_ROW_HEIGHT,
                    active ? GOLD : MUTED, unsaved ? .55f : .62f, active);
        } else if (manager) {
            String action = module instanceof mindless.script.Manager ? "Manage" : "Create";
            drawCenteredV(action, actionX - 8, actionX + 38, y, y + MODULE_ROW_HEIGHT, MUTED, .6f, false);
        }
        if (!manager) {
            boolean editingBind = binding == module;
            if (!profile) {
                String bindText = editingBind ? "..." : module.getKeycode() == 0 ? "None" : keyName(module.getKeycode());
                float bindX = x2 - 52;
                float bindScale = textWidth(bindText, .61f, false) > 34f ? .52f : .61f;
                drawCenteredV(bindText, bindX, x2 - 14, y, y + MODULE_ROW_HEIGHT,
                        editingBind ? GOLD : DIM, bindScale, false);
            } else if (editingBind || module.getKeycode() != 0) {
                String bindText = editingBind ? "..." : keyName(module.getKeycode());
                float bindX = x2 - 52;
                float bindScale = textWidth(bindText, .61f, false) > 34f ? .52f : .61f;
                drawCenteredV(bindText, bindX, x2 - 14, y, y + MODULE_ROW_HEIGHT,
                        editingBind ? GOLD : DIM, bindScale, false);
            }
        }
        drawTextVCentered(">", x2 - 8, y, y + MODULE_ROW_HEIGHT, selected ? GOLD : withAlpha(DIM, (int)(80 + 175 * hp)), .72f, false);
    }

    private void drawSettingsPanel(int mx, int my) {
        if (detailW < 4f || selectedModule == null) return;
        float reveal = detailContentReveal;
        float contentAlpha = reveal;
        float slideOffset = (1f - reveal) * 18f;
        scissor(detailX, baseY, detailX + detailW, baseY + panelH, true);
        int headerAlpha = (int) (255 * contentAlpha);
        float headerCenterY = baseY + 28.5f;
        GL11.glPushMatrix();
        GL11.glTranslatef(slideOffset, 0f, 0f);
        circle(detailX + 25, headerCenterY, 12.5f, withAlpha(GOLD_SOFT, (int) (headerAlpha * .55f)));
        circleOutline(detailX + 25, headerCenterY, 12.5f, withAlpha(GOLD, (int) (headerAlpha * .8f)));
        drawCategoryIcon(selectedModule.moduleCategory(), detailX + 25, headerCenterY,
                withAlpha(GOLD, headerAlpha));
        drawTwoLineTextVCentered(
                trim(selectedModule.getName(), detailW - 82, .98f, true), "Settings",
                detailX + 42, baseY + 10, baseY + 47,
                withAlpha(TEXT, headerAlpha), withAlpha(MUTED, headerAlpha),
                .98f, .67f, 2.5f, 1f);
        segments(withAlpha(DIM, headerAlpha),
                detailX + detailW - 23, headerCenterY - 4, detailX + detailW - 15, headerCenterY + 4,
                detailX + detailW - 15, headerCenterY - 4, detailX + detailW - 23, headerCenterY + 4);
        line(detailX + 12, baseY + 51, detailX + detailW - 12, baseY + 51,
                withAlpha(DIVIDER, headerAlpha));

        measureSettingColumns();

        float top = baseY + 59f, bottom = baseY + panelH - 12f;
        scissor(detailX + 8, top, detailX + detailW - 8, bottom, true);
        float y = top + settingScroll;
        GroupSetting currentGroup = null;
        boolean firstRow = true;
        for (Setting setting : selectedModule.getSettings()) {
            if (!setting.visible) continue;
            if (setting instanceof GroupSetting) currentGroup = (GroupSetting) setting;
            GroupSetting owner = groupOf(setting);
            if (owner != null && !owner.isOpened()) continue;
            float h = settingHeight(setting);
            if (y + h >= top - 4 && y <= bottom + 4) {
                drawSetting(setting, y, h, mx, my, contentAlpha, firstRow);
            }
            firstRow = false;
            if (setting == openDropdown) dropdownAnchorY = y;
            y += h + SETTING_GAP;
        }
        settingsContentHeight = y - (top + settingScroll);
        scissor(detailX + 8, top, detailX + detailW - 8, bottom, true);
        drawScrollbar(detailX + detailW - 7, top, bottom, settingScroll, settingsContentHeight);
        scissor(0, 0, 0, 0, false);

        GL11.glPopMatrix();
        scissor(0, 0, 0, 0, false);
        drawDropdownOverlay(mx, my);
    }

    private float settingsLeft() { return detailX + 15; }

    private float settingsRight() { return detailX + detailW - 15; }
private float controlLeft() {
        if (Float.isNaN(settingControlLeft)) return settingsLeft() + settingsRight() * 0f + 100f;
        return settingControlLeft;
    }
private float sliderTrackRight() { return settingsRight() - 4f; }
private float sliderValueLeft() {
        return controlLeft() - VALUE_GAP - sliderValueWidth;
    }
private void measureSettingColumns() {
        float rowWidth = settingsRight() - settingsLeft();

        float value = 0f;
        boolean hasNumbers = false;
        if (selectedModule != null) {
            for (Setting setting : selectedModule.getSettings()) {
                if (!isMeasurable(setting) || !(setting instanceof SliderSetting)) continue;
                SliderSetting slider = (SliderSetting) setting;
                if (slider.isString) continue;
                hasNumbers = true;
                value = Math.max(value, textWidth(sliderValue(slider), .72f, false));
            }
        }
        sliderValueWidth = hasNumbers ? Math.max(VALUE_MIN_W, Math.min(VALUE_MAX_W, value)) : 0f;
        float valueSpace = hasNumbers ? sliderValueWidth + VALUE_GAP : 0f;

        float budget = rowWidth - LABEL_GAP - valueSpace - CONTROL_MIN_W;
        float label = 0f;
        float scale = LABEL_SCALES[LABEL_SCALES.length - 1];
        for (float candidate : LABEL_SCALES) {
            label = widestLabel(candidate);
            scale = candidate;
            if (label <= budget) break;
        }

        settingLabelScale = scale;
float settingLabelWidth = Math.max(24f, Math.min(label, budget));
        settingControlLeft = settingsLeft() + settingLabelWidth + LABEL_GAP + valueSpace;
    }

    private float widestLabel(float scale) {
        if (selectedModule == null) return 0f;
        float widest = 0f;
        for (Setting setting : selectedModule.getSettings()) {
            if (!isMeasurable(setting)) continue;
            if (setting instanceof DescriptionSetting || setting instanceof GroupSetting) continue;
            if (setting instanceof TextSetting || isList(setting)) continue;
            widest = Math.max(widest, textWidth(setting.getName(), scale, false));
        }
        return widest;
    }

    private boolean isMeasurable(Setting setting) {
        if (setting == null || !setting.visible) return false;
        GroupSetting owner = groupOf(setting);
        return owner == null || owner.isOpened();
    }
/**
     * A setting's name, made a little smaller before it is cut short.
     *
     * Pinning the controls to a shared column leaves the label a fixed width, and plenty of
     * settings here are named things like "Multipoint Horizontal". Dropping a step in size buys
     * roughly a fifth more characters, which is usually the difference between a name you can
     * read and one that ends in an ellipsis.
     */
    private void drawSettingLabel(String name, float x, float y1, float y2, float maxWidth,
                                  int color) {
        drawTextVCentered(trim(name, maxWidth, settingLabelScale, false), x, y1, y2, color,
                settingLabelScale, false);
    }

    private float[] segmentLayout(SliderSetting slider) {
        String[] options = slider.getOptions();
        if (options == null || options.length < 2 || options.length > MAX_SEGMENTS) return null;

        float left = controlLeft();
        float available = settingsRight() - left;
        float widest = 0f;
        for (String option : options) widest = Math.max(widest, textWidth(option, .66f, false));
        if ((widest + 24f) * options.length + SEGMENT_GAP * (options.length - 1) > available) {
            return null;
        }
        float width = (available - SEGMENT_GAP * (options.length - 1)) / options.length;
        return new float[]{left, width};
    }

    private float segmentX(float[] layout, int index) {
        return layout[0] + index * (layout[1] + SEGMENT_GAP);
    }
private static int opaque(int color) { return color | 0xFF000000; }
private int fa(int color, float alpha) {
        int a = (int) (((color >>> 24) & 255) * alpha);
        return withAlpha(color, a);
    }
private void drawDropdownOverlay(int mx, int my) {
        if (openDropdown == null || openDropdown.getOptions() == null) {
            animationValue(dropdownAnimation, new Object(), 0f);
            return;
        }
        float open = animationValue(dropdownAnimation, openDropdown, 0f);
        if (open < 0.01f) return;

        float x2 = detailX + detailW - 15;
        float dw = overlayWidth();
        float dx1 = x2 - dw, dx2 = x2;
        layoutDropdown();
        float rowTop = dropdownRowTop;
        int n = openDropdown.getOptions().length;
        float viewH = dropdownViewH;
        float fullH = dropdownFullH;
        clampDropdownScroll();
        dropdownScroll += (dropdownScrollTarget - dropdownScroll) * .32f;
        if (Math.abs(dropdownScrollTarget - dropdownScroll) < .08f) dropdownScroll = dropdownScrollTarget;
        float clipTop = dropdownFlipped ? rowTop + viewH - open * viewH : rowTop;
        float clipBottom = dropdownFlipped ? rowTop + viewH : rowTop + open * viewH;
        if (clipBottom <= clipTop) return;

        scissor(detailX + 4f, clipTop, detailX + detailW - 4f, clipBottom, true);
        RoundedUtils.drawRoundShadow(dx1 - 1, rowTop, dx2 - dx1 + 2, viewH, 5f, 6f, argb((int)(80 * open), 0, 0, 0));
        int bgAlpha = (int)(255 * open);
        net.minecraft.client.gui.Gui.drawRect((int) dx1, (int) rowTop, (int) dx2, (int)(rowTop + viewH),
                fa(DROPDOWN_BG, open));
        outline(dx1, rowTop, dx2, rowTop + viewH, 5f, fa(DROPDOWN_BORDER, open));
        resetTextRenderState();
        float oy = rowTop + 2f + dropdownScroll;
        for (int i = 0; i < n; i++) {
            if (oy + 19 < rowTop || oy > rowTop + viewH) { oy += 21f; continue; }
            boolean sel = (int) openDropdown.getInput() == i;
            boolean hov = inside(mx, my, dx1 + 2, Math.max(oy, rowTop), dx2 - 2, Math.min(oy + 19, rowTop + viewH));
            Object rowKey = getDropdownRowKey(openDropdown, i);
            float rowHp = animate(hoverAnimation, rowKey, hov ? 1f : 0f, 16f);
            if (sel) {
                net.minecraft.client.gui.Gui.drawRect((int)(dx1 + 2), (int) oy, (int)(dx2 - 2), (int)(oy + 19),
                        fa(DROPDOWN_SELECTED, open));
            } else if (rowHp > 0.01f) {
                net.minecraft.client.gui.Gui.drawRect((int)(dx1 + 2), (int) oy, (int)(dx2 - 2), (int)(oy + 19),
                        fa(DROPDOWN_SELECTED, rowHp * open * .38f));
            }
            int textColor = sel ? TEXT : mixColor(MUTED, TEXT, rowHp);
            resetTextRenderState();
            drawTextVCentered(trim(openDropdown.getOptions()[i], dw - 20f, .68f, sel),
                    dx1 + 10, oy, oy + 19,
                    withAlpha(textColor, (int)(255 * open)),
                    .68f, sel);
            oy += 21f;
        }
        if (fullH > viewH + .5f) {
            float track = viewH - 8f;
            float thumb = Math.max(14f, track * (viewH / fullH));
            float progress = dropdownFullH == dropdownViewH ? 0f
                    : (-dropdownScroll) / (dropdownFullH - dropdownViewH);
            float ty = rowTop + 4f + (track - thumb) * clamp01(progress);
            rounded(dx2 - 4.5f, ty, dx2 - 2.5f, ty + thumb, 1f, withAlpha(ACCENT, (int)(120 * open)));
        }

        resetTextRenderState();
        scissor(0, 0, 0, 0, false);
    }
private void closeDropdownState() {
        dropdownScroll = dropdownScrollTarget = 0f;
        dropdownViewH = dropdownFullH = 0f;
    }
private void clampDropdownScroll() {
        float min = Math.min(0f, dropdownViewH - dropdownFullH);
        dropdownScrollTarget = Math.max(min, Math.min(0f, dropdownScrollTarget));
        dropdownScroll = Math.max(min, Math.min(0f, dropdownScroll));
    }
private boolean overDropdown(int mx, int my) {
        if (openDropdown == null || openDropdown.getOptions() == null) return false;
        float x2 = detailX + detailW - 15;
        return inside(mx, my, x2 - overlayWidth(), dropdownRowTop, x2, dropdownRowTop + dropdownViewH);
    }
private float overlayWidth() {
        if (openDropdown == null || openDropdown.getOptions() == null) return dropdownWidth;
        float widest = 0f;
        for (String option : openDropdown.getOptions()) {
            widest = Math.max(widest, textWidth(option, .68f, false));
        }
        return Math.max(dropdownWidth, Math.min(detailW - 34f, widest + 26f));
    }

    private final Map<Integer, Object> dropdownRowKeys = new java.util.HashMap<Integer, Object>();
    private Object getDropdownRowKey(SliderSetting slider, int index) {
        int key = System.identityHashCode(slider) * 1000 + index;
        Object k = dropdownRowKeys.get(key);
        if (k == null) { k = new Object(); dropdownRowKeys.put(key, k); }
        return k;
    }

    private void drawSetting(Setting setting, float y, float h, int mx, int my, float alpha,
                             boolean first) {
        float x1 = detailX + 15, x2 = detailX + detailW - 15;
        if (setting instanceof DescriptionSetting) {
            if (!first) line(x1, y + 6, x2, y + 6, fa(DIVIDER, alpha));
            drawTextVCentered(((DescriptionSetting) setting).getDesc(), x1 + 2, y + 9, y + h,
                    fa(MUTED, alpha), .72f, false);
        } else if (setting instanceof GroupSetting) {
            GroupSetting group = (GroupSetting) setting;
            rounded(x1, y, x2, y + h, 5f, fa(ROW, alpha));
            drawTextVCentered(group.getName(), x1 + 10, y, y + h, fa(TEXT, alpha), .8f, true);
            drawTextVCentered(group.isOpened() ? "-" : "+", x2 - 15, y, y + h, fa(group.isOpened() ? GOLD : MUTED, alpha), .85f, true);
        } else if (setting instanceof ButtonSetting) {
            ButtonSetting button = (ButtonSetting) setting;
            drawSettingLabel(button.getName(), x1 + 2, y, y + h, x2 - 38f - x1, fa(TEXT, alpha));
            if (button.isMethodButton) {
                float hp = animate(hoverAnimation, button, inside(mx, my, x1, y, x2, y + h) ? 1f : 0f, 17f);
                String action = methodActionLabel(button.getName());
                int baseColor = action.equals("Remove") ? DANGER : GOLD;
                float actionWidth = textWidth(action, .64f, true);
                drawTextVCentered(action, x2 - actionWidth, y, y + h, fa(mixColor(baseColor, TEXT, hp * .35f), alpha), .64f, true);
            } else drawToggle(x2 - 30, y + 8, button.isToggled(), button);
        } else if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) {
                String label = slider.getName();
                float labelLeft = x1 + 2;
                float[] segments = segmentLayout(slider);
                if (segments != null) {
                    drawSettingLabel(label, labelLeft, y, y + h,
                            segments[0] - labelLeft - DROPDOWN_LABEL_GAP, fa(TEXT, alpha));
                    drawSegments(slider, segments, y, h, mx, my, alpha);
                    return;
                }
                float available = x2 - labelLeft - textWidth(label, .75f, false) - DROPDOWN_LABEL_GAP;
                float dropW = Math.max(DROPDOWN_MIN_W, Math.min(x2 - controlLeft(), available));
                float dx1 = x2 - dropW, dx2 = x2;
                if (openDropdown == slider) dropdownWidth = dropW;

                drawSettingLabel(label, labelLeft, y, y + h,
                        dx1 - labelLeft - DROPDOWN_LABEL_GAP, fa(TEXT, alpha));

                float open = animate(dropdownAnimation, slider, openDropdown == slider ? 1f : 0f, 20f);
                boolean over = inside(mx, my, dx1, y + 3, dx2, y + 27);
                float hp = animate(hoverAnimation, slider, over ? 1f : 0f, 16f);
                outline(dx1, y + 3, dx2, y + 27, 4f, fa(mixColor(BORDER, GOLD, open), alpha));
                rounded(dx1, y + 3, dx2, y + 27, 4f, fa(opaque(
                        mixColor(CONTROL, CONTROL_HOVER, Math.max(hp * .65f, open * .7f))), alpha));
                resetTextRenderState();
                drawTextVCentered(trim(sliderValue(slider), dropW - 26f, .68f, false), dx1 + 8, y + 3, y + 27,
                        fa(mixColor(MUTED, TEXT, Math.max(open, hp * .6f)), alpha), .68f, false);
                drawChevron(dx2 - 10, y + 15, open, fa(mixColor(MUTED, GOLD, open), alpha));
                return;
            }
            float valueLeft = sliderValueLeft();
            float trackLeft = controlLeft();
            drawSettingLabel(slider.getName(), x1 + 2, y, y + h, valueLeft - x1 - 12,
                    fa(TEXT, alpha));

            boolean editingValue = editingSliderValue == slider;
            float valueBoxRight = trackLeft - 8;
            boolean valueHover = inside(mx, my, valueLeft - 5, y + 5, valueBoxRight, y + h - 5);
            float valueState = animate(controlAnimation, sliderValueAnimationKey(slider), editingValue ? 1f : valueHover ? .55f : 0f, 18f);
            if (valueState > .01f) rounded(valueLeft - 5, y + 5, valueBoxRight, y + h - 5, 4f,
                    fa(mixColor(withAlpha(CONTROL, 0), CONTROL_HOVER, valueState), alpha));
            String displayedValue = editingValue ? sliderEditDraft : sliderValue(slider);
            float valueRoom = valueBoxRight - valueLeft - 4;
            float valueScale = textWidth(displayedValue, .72f, false) > valueRoom ? .62f : .72f;
            float valueX = valueLeft;
            if (editingValue && sliderHasSelection()) {
                int from = Math.min(sliderEditCaret, sliderEditAnchor);
                int to = Math.max(sliderEditCaret, sliderEditAnchor);
                float left = valueX + textWidth(sliderEditDraft.substring(0, from), valueScale, false);
                float right = valueX + textWidth(sliderEditDraft.substring(0, to), valueScale, false);
                rounded(left - 1, y + 8, right + 1, y + h - 8, 2f, fa(withAlpha(ACCENT, 74), alpha));
            }
            drawTextVCentered(displayedValue, valueX, y, y + h, fa(TEXT, alpha), valueScale, false);
            if (editingValue && blink()) {
                float caretX = valueX + textWidth(sliderEditDraft.substring(0, sliderEditCaret), valueScale, false);
                rounded(caretX, y + 8, caretX + .8f, y + h - 8, .4f, fa(ACCENT, alpha));
            }
            float bx1 = trackLeft, bx2 = sliderTrackRight(), by = y + h / 2f - 1.5f;
            float hp = animate(controlAnimation, slider, inside(mx, my, bx1 - 5, y + 3, bx2 + 3, y + h - 3) ? 1f : 0f, 18f);
            rounded(bx1, by, bx2, by + 3, 1.5f,
                    fa(opaque(mixColor(argb(255, 47, 50, 51), argb(255, 68, 71, 72), hp)), alpha));
            float targetProgress = (float) ((slider.getInput() - slider.getMin()) / Math.max(.00001, slider.getMax() - slider.getMin()));
            targetProgress = clamp01(targetProgress);
            Float animProg = sliderProgressAnimation.get(slider);
            if (animProg == null) animProg = targetProgress;
            animProg = animProg + (targetProgress - animProg) * (1f - (float) Math.exp(-28f * frameDelta));
            if (Math.abs(targetProgress - animProg) < 0.0008f) animProg = targetProgress;
            sliderProgressAnimation.put(slider, animProg);
            float px = bx1 + (bx2 - bx1) * animProg;
            rounded(bx1, by, px, by + 3, 1.5f, fa(mixColor(GOLD, TEXT, hp * .18f), alpha));
            float thumbR = 4.1f + hp * .7f;
            circle(px, by + 1.5f, thumbR, fa(mixColor(GOLD, TEXT, hp * .24f), alpha));
            if (draggingSlider == slider) sliderRect.set(bx1, y, bx2, y + h);
        } else if (setting instanceof KeySetting) {
            KeySetting key = (KeySetting) setting;
            String value = binding == key ? "Press a key" : keyName(key.getKey());
            float chipLeft = keyChipLeft(key);
            drawSettingLabel(key.getName(), x1 + 2, y, y + h, chipLeft - x1 - 12,
                    fa(TEXT, alpha));
            float top = y + (h - CONTROL_HEIGHT) / 2f, bottom = top + CONTROL_HEIGHT;
            float focus = animate(controlAnimation, key, binding == key ? 1f : inside(mx, my, chipLeft, top, x2, bottom) ? .55f : 0f, 17f);
            outline(chipLeft, top, x2, bottom, 4f, fa(mixColor(BORDER, GOLD, focus), alpha));
            rounded(chipLeft, top, x2, bottom, 4f,
                    fa(opaque(mixColor(CONTROL, CONTROL_HOVER, focus)), alpha));
            resetTextRenderState();
            drawCenteredV(value, chipLeft, x2, top, bottom, fa(binding == key ? GOLD : MUTED, alpha), .66f, false);
        } else if (setting instanceof ColorSetting) {
            drawColor((ColorSetting) setting, y, h, mx, my, alpha);
        } else if (setting instanceof TextSetting) {
            TextSetting text = (TextSetting) setting;
            drawInputSetting(text, text.getName(), text.getText(), text.getPlaceholder(), y, h, mx, my, alpha);
        } else if (isList(setting)) {
            drawListSetting(setting, y, h, mx, my, alpha);
        }
    }
private float keyChipLeft(KeySetting key) {
        String value = binding == key ? "Press a key" : keyName(key.getKey());
        float width = Math.max(34f, textWidth(value, .66f, false) + 18f);
        return Math.min(controlLeft(), settingsRight() - width);
    }

    private void drawSegments(SliderSetting slider, float[] layout, float y, float h,
                              int mx, int my, float alpha) {
        String[] options = slider.getOptions();
        int selected = (int) slider.getInput();
        float top = y + (h - CONTROL_HEIGHT) / 2f, bottom = top + CONTROL_HEIGHT;

        for (int i = 0; i < options.length; i++) {
            float sx1 = segmentX(layout, i), sx2 = sx1 + layout[1];
            boolean on = i == selected;
            float hover = !on && inside(mx, my, sx1, top, sx2, bottom) ? 1f : 0f;
            outline(sx1, top, sx2, bottom, 4f, fa(on ? GOLD : BORDER, alpha));
            rounded(sx1, top, sx2, bottom, 4f, fa(opaque(on
                    ? mixColor(ROW, GOLD, .17f)
                    : mixColor(ROW, ROW_HOVER, hover)), alpha));
            resetTextRenderState();
            drawCenteredV(trim(options[i], layout[1] - 16f, .66f, false), sx1, sx2, top, bottom,
                    fa(on ? GOLD : mixColor(MUTED, TEXT, hover * .5f), alpha), .66f, on);
        }
    }

    private void drawColor(ColorSetting color, float y, float h, int mx, int my, float alpha) {
        float x1 = detailX + 15, x2 = detailX + detailW - 15;
        float controlTop = y + (Math.min(h, 32f) - 18f) / 2f;
        drawTextVCentered(color.getName(), x1 + 2, y, y + Math.min(h, 32f), fa(TEXT, alpha), .76f, false);
        outline(x2 - 38, controlTop, x2, controlTop + 18, 4f, fa(BORDER, alpha));
        rounded(x2 - 38, controlTop, x2, controlTop + 18, 4f, fa(color.getColor(), alpha));
        if (openColor != color) return;

        float py = y + 34, pickerX = x1 + 9, pickerW = x2 - x1 - 18;
        colorSB.set(pickerX, py, pickerX + pickerW - 17, py + 48);
        colorHue.set(colorSB.x2 + 5, py, colorSB.x2 + 12, py + 48);

        drawSaturationBrightnessField(color, alpha);
        drawHueStrip(alpha);
        float sbX = colorSB.x1 + color.getSaturation() * colorSB.w();
        float sbY = colorSB.y1 + (1f - color.getBrightness()) * colorSB.h();
        circleOutline(sbX, sbY, 4.2f, fa(0xFF000000, alpha));
        circleOutline(sbX, sbY, 3.4f, fa(0xFFFFFFFF, alpha));

        float hueY = colorHue.y1 + color.getHue() / 360f * colorHue.h();
        rounded(colorHue.x1 - 2.5f, hueY - 2f, colorHue.x2 + 2.5f, hueY + 2f, 2f, fa(0xFFFFFFFF, alpha));
        rounded(colorHue.x1 - 1.5f, hueY - 1f, colorHue.x2 + 1.5f, hueY + 1f, 1f,
                fa(0xFF000000 | Color.HSBtoRGB(color.getHue() / 360f, 1f, 1f), alpha));

        if (color.hasAlpha()) {
            colorAlpha.set(pickerX, py + 55, pickerX + pickerW, py + 61);
            drawAlphaStrip(color, alpha);
            float ax = colorAlpha.x1 + color.getAlpha() / 255f * colorAlpha.w();
            rounded(ax - 2f, colorAlpha.y1 - 2.5f, ax + 2f, colorAlpha.y2 + 2.5f, 2f, fa(0xFFFFFFFF, alpha));
            rounded(ax - 1f, colorAlpha.y1 - 1.5f, ax + 1f, colorAlpha.y2 + 1.5f, 1f, fa(0xFF000000, alpha));
        }
    }
private void drawSaturationBrightnessField(ColorSetting color, float alpha) {
        int hueRgb = 0xFF000000 | Color.HSBtoRGB(color.getHue() / 360f, 1f, 1f);
        int black = 0xFF000000;
        RoundedUtils.drawGradientRound(colorSB.x1, colorSB.y1, colorSB.w(), colorSB.h(), 3f,
                fa(black, alpha), fa(0xFFFFFFFF, alpha), fa(black, alpha), fa(hueRgb, alpha));
        outline(colorSB.x1, colorSB.y1, colorSB.x2, colorSB.y2, 3f, fa(BORDER, alpha));
    }
private void drawHueStrip(float alpha) {
        float step = colorHue.h() / 6f;
        for (int i = 0; i < 6; i++) {
            int top = 0xFF000000 | Color.HSBtoRGB(i / 6f, 1f, 1f);
            int bottom = 0xFF000000 | Color.HSBtoRGB((i + 1) / 6f, 1f, 1f);
            RenderUtils.drawVerticalGradientRect(colorHue.x1, colorHue.y1 + i * step,
                    colorHue.x2, colorHue.y1 + (i + 1) * step, fa(top, alpha), fa(bottom, alpha));
        }
        outline(colorHue.x1, colorHue.y1, colorHue.x2, colorHue.y2, 2f, fa(BORDER, alpha));
    }
private void drawAlphaStrip(ColorSetting color, float alpha) {
        int squares = (int) Math.ceil(colorAlpha.w() / 4f);
        for (int i = 0; i < squares; i++) {
            float sx = colorAlpha.x1 + i * 4f;
            float sw = Math.min(4f, colorAlpha.x2 - sx);
            for (int row = 0; row < 2; row++) {
                boolean light = ((i + row) & 1) == 0;
                float sy = colorAlpha.y1 + row * colorAlpha.h() * 0.5f;
                net.minecraft.client.gui.Gui.drawRect((int) sx, (int) sy,
                        (int) Math.ceil(sx + sw), (int) Math.ceil(sy + colorAlpha.h() * 0.5f),
                        fa(light ? 0xFF6E6E76 : 0xFF3A3A42, alpha));
            }
        }
        RenderUtils.drawHorizontalGradientRect(colorAlpha.x1, colorAlpha.y1, colorAlpha.x2, colorAlpha.y2,
                withAlpha(color.getRGB(), 0), fa(0xFF000000 | color.getRGB(), alpha));
        outline(colorAlpha.x1, colorAlpha.y1, colorAlpha.x2, colorAlpha.y2, 2f, fa(BORDER, alpha));
    }

    private void drawInputSetting(TextSetting setting, String name, String value, String placeholder, float y, float h, int mx, int my, float alpha) {
        float x1 = detailX + 15, x2 = detailX + detailW - 15;
        drawText(name, x1 + 10, y + 7, fa(TEXT, alpha), .72f, false);
        float iy = y + 20;
        float focus = animate(controlAnimation, setting, activeText == setting ? 1f : inside(mx, my, x1 + 8, iy, x2 - 8, y + h - 7) ? .5f : 0f, 17f);
        outline(x1 + 8, iy, x2 - 8, y + h - 7, 4f, fa(mixColor(BORDER, GOLD, focus), alpha));
        rounded(x1 + 8, iy, x2 - 8, y + h - 7, 4f,
                fa(opaque(mixColor(CONTROL, CONTROL_HOVER, focus)), alpha));
        resetTextRenderState();
        float available = x2 - x1 - 28;
        if (activeText == setting) {
            drawEditable(x1 + 14, iy, y + h - 7, available, .69f, fa(TEXT, alpha), alpha);
        } else {
            String shown = value.isEmpty() ? placeholder : value;
            drawTextVCentered(trim(shown, available, .69f, false), x1 + 14, iy, y + h - 7,
                    fa(value.isEmpty() ? DIM : TEXT, alpha), .69f, false);
        }
    }

    private void drawListSetting(Setting setting, float y, float h, int mx, int my, float alpha) {
        float x1 = detailX + 15, x2 = detailX + detailW - 15;
        rounded(x1, y, x2, y + h, 5f, fa(ROW, alpha));
        drawText(setting.getName(), x1 + 10, y + 8, fa(TEXT, alpha), .74f, false);
        float iy = y + 22;
        float focus = animate(controlAnimation, setting, activeList == setting ? 1f : inside(mx, my, x1 + 8, iy, x2 - 34, iy + 21) ? .5f : 0f, 17f);
        outline(x1 + 8, iy, x2 - 34, iy + 21, 4f, fa(mixColor(BORDER, GOLD, focus), alpha));
        rounded(x1 + 8, iy, x2 - 34, iy + 21, 4f,
                fa(opaque(mixColor(CONTROL, CONTROL_HOVER, focus)), alpha));
        resetTextRenderState();
        if (activeList == setting) {
            drawEditable(x1 + 14, iy, iy + 21, x2 - x1 - 66, .66f, fa(TEXT, alpha), alpha);
        } else {
            drawTextVCentered(trim(listPlaceholder(setting), x2 - x1 - 66, .66f, false), x1 + 14, iy, iy + 21,
                    fa(DIM, alpha), .66f, false);
        }
        rounded(x2 - 29, iy, x2 - 8, iy + 21, 4f, fa(GOLD_SOFT, alpha));
        drawCenteredV("+", x2 - 29, x2 - 8, iy, iy + 21, fa(GOLD, alpha), .8f, true);
        float ey = iy + 27;
        List<Suggestion> suggestions = suggestionsFor(setting);
        for (Suggestion suggestion : suggestions) {
            rounded(x1 + 8, ey, x2 - 8, ey + 19, 3f, fa(withAlpha(ACCENT, 92), alpha));
            drawTextVCentered(trim(suggestion.label, x2 - x1 - 38, .64f, false), x1 + 14, ey, ey + 19, fa(TEXT, alpha), .64f, false);
            drawTextVCentered("+", x2 - 19, ey, ey + 19, fa(GOLD, alpha), .7f, true);
            ey += 21;
        }
        List<String> entries = listEntries(setting);
        for (int row = 0; row < entries.size(); row++) {
            String entry = entries.get(row);
            rounded(x1 + 8, ey, x2 - 8, ey + 20, 4f, fa(ROW, alpha));
            drawTextVCentered(trim(entry, x2 - x1 - 70, .65f, false), x1 + 14, ey, ey + 20, fa(MUTED, alpha), .65f, false);
            if (setting instanceof InventoryItemListSetting) {
                int slot = ((InventoryItemListSetting) setting).getAssignedSlot(row);
                drawTextVCentered("<", x2 - 91, ey, ey + 20, fa(MUTED, alpha), .62f, true);
                drawTextVCentered(">", x2 - 79, ey, ey + 20, fa(MUTED, alpha), .62f, true);
                drawTextVCentered("Slot " + slot, x2 - 53, ey, ey + 20, fa(GOLD, alpha), .62f, false);
            }
            drawTextVCentered("x", x2 - 18, ey, ey + 20, fa(DANGER, alpha), .65f, true);
            ey += 23;
        }
    }
private void drawEditable(float x, float y1, float y2, float available, float scale,
                              int color, float alpha) {
        String text = editor.getText();
        int caret = Math.max(0, Math.min(text.length(), editor.getCaret()));

        int start = 0;
        while (start < caret && textWidth(text.substring(start, caret), scale, false) > available) start++;
        int end = start;
        while (end < text.length() && textWidth(text.substring(start, end + 1), scale, false) <= available) end++;

        if (editor.hasSelection()) {
            int from = Math.max(start, editor.selectionStart());
            int to = Math.min(end, editor.selectionEnd());
            if (to > from) {
                float sx = x + textWidth(text.substring(start, from), scale, false);
                float ex = x + textWidth(text.substring(start, to), scale, false);
                rounded(sx, y1 + 4, ex, y2 - 4, 1f, fa(withAlpha(ACCENT, 95), alpha));
            }
        }

        drawTextVCentered(text.substring(start, end), x, y1, y2, color, scale, false);

        if (blink()) {
            float cx = x + textWidth(text.substring(start, caret), scale, false);
            rounded(cx, y1 + 4, cx + 1, y2 - 4, 0f, fa(TEXT, alpha));
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int mx = mouseX, my = mouseY;
        computeLayout();
        if (binding != null) {
            int value = mouseButton < 0 ? 0 : 1000 + mouseButton;
            if (binding instanceof Module) ((Module) binding).setBind(value);
            else if (binding instanceof KeySetting) ((KeySetting) binding).setKey(value);
            binding = null;
            return;
        }
        if (editingSliderValue != null) finishSliderValueEdit(true);
        if (mouseButton != 0 && mouseButton != 1) return;
        if (mouseButton == 0 && beginMascotDrag(mx, my)) return;
        if (mouseButton == 0 && inside(mx, my, baseX + 10, baseY + 8, baseX + sideW - 10, baseY + 40)) {
            draggingGui = true;
            dragStartMouseX = mx;
            dragStartMouseY = my;
            dragStartOffsetX = guiDragOffsetX;
            dragStartOffsetY = guiDragOffsetY;
            return;
        }

        if (mouseButton == 0 && beginScrollbarDrag(mx, my)) return;
        float searchW = Math.min(170f, centerW * .43f);
        float searchX = centerX + centerW - searchW - 16f;
        searchFocused = inside(mx, my, searchX, baseY + 15, searchX + searchW, baseY + 39);
        if (searchFocused) {
            setSearchCaretFromMouse(mx, searchX + 11f, searchW - 35f, isShiftKeyDown());
            activeText = null;
            activeList = null;
            return;
        }

        float cy = baseY + 55f;
        for (Module.category category : Module.category.values()) {
            if (isPinnedCategory(category)) continue;
            if (inside(mx, my, baseX + 7, cy, baseX + sideW - 7, cy + CATEGORY_ROW_HEIGHT)) { selectCategory(category); return; }
            cy += CATEGORY_ROW_STEP;
        }
        cy += 7f;
        if (inside(mx, my, baseX + 7, cy, baseX + sideW - 7, cy + CATEGORY_ROW_HEIGHT)) { selectCategory(Module.category.profiles); return; }
        cy += CATEGORY_ROW_STEP;
        if (inside(mx, my, baseX + 7, cy, baseX + sideW - 7, cy + CATEGORY_ROW_HEIGHT)) { selectCategory(Module.category.scripts); return; }
        cy += CATEGORY_ROW_STEP;
        if (inside(mx, my, baseX + 7, cy, baseX + sideW - 7, cy + CATEGORY_ROW_HEIGHT)) { selectCategory(Module.category.theme); return; }
        if (clickThemePanel(mx, my, mouseButton)) return;


        float top = baseY + 61f;
        List<Module> modules = filteredModules();
        Module scriptManager = stickyScriptManager() ? detachScriptManager(modules) : null;
        if (scriptManager != null) {
            if (inside(mx, my, centerX + 14, top, centerX + centerW - 14, top + MODULE_ROW_HEIGHT)) {
                openModule(scriptManager);
                return;
            }
            top += MODULE_ROW_STEP;
        }
        float y = top + moduleScroll;
        for (Module module : modules) {
            if (inside(mx, my, centerX + 14, y, centerX + centerW - 14, y + MODULE_ROW_HEIGHT)) {
                float x2 = centerX + centerW - 14;
                if (module instanceof ProfileModule) {
                    if (mouseButton == 1) {
                        openModule(module);
                    } else if (mx >= x2 - 55 && mx <= x2 - 16) {
                        binding = module;
                    } else if (mx >= x2 - 14) {
                        openModule(module);
                    } else {
                        activateProfile((ProfileModule) module);
                    }
                } else if (module instanceof Manager || module instanceof mindless.script.Manager) {
                    openModule(module);
                } else if (mx >= x2 - 55 && mx <= x2 - 16) {
                    binding = module;
                } else if (mouseButton == 1) {
                    if (module == selectedModule) selectModule(null);
                    else openModule(module);
                } else {
                    if (mx >= x2 - 14) openModule(module);
                    else module.toggle();
                }
                return;
            }
            y += MODULE_ROW_STEP;
        }
        if (selectedModule != null) {
            if (inside(mx, my, detailX + detailW - 31, baseY + 13, detailX + detailW - 10, baseY + 42)) {
                selectModule(null);
                return;
            }
            if (openDropdown != null && openDropdown.getOptions() != null) {
                float x2 = detailX + detailW - 15;
                float dx1 = x2 - overlayWidth(), dx2 = x2;
                float overlayTop = dropdownRowTop;
                float overlayBot = overlayTop + dropdownViewH;
                if (inside(mx, my, dx1, overlayTop, dx2, overlayBot)) {
                    int index = (int)((my - (overlayTop + 2f + dropdownScroll)) / 21f);
                    if (index >= 0 && index < openDropdown.getOptions().length) {
                        openDropdown.setValueWithEvent(index);
                        if (selectedModule != null) selectedModule.onSlide(openDropdown);
                    }
                    openDropdown = null;
                    closeDropdownState();
                    return;
                }
                openDropdown = null;
                closeDropdownState();
                return;
            }
            clickSetting(mx, my, mouseButton);
        }
    }

    private void clickSetting(int mx, int my, int button) {
        float top = baseY + 59f, bottom = baseY + panelH - 12f;
        if (!inside(mx, my, detailX + 8, top, detailX + detailW - 8, bottom)) return;
        float y = top + settingScroll;
        for (Setting setting : selectedModule.getSettings()) {
            if (!setting.visible) continue;
            GroupSetting owner = groupOf(setting);
            if (owner != null && !owner.isOpened()) continue;
            float h = settingHeight(setting);
            if (inside(mx, my, detailX + 15, y, detailX + detailW - 15, y + h)) {
                handleSettingClick(setting, mx, my, y, h, button);
                return;
            }
            y += h + SETTING_GAP;
        }
    }

    private void handleSettingClick(Setting setting, int mx, int my, float y, float h, int button) {
        float x1 = detailX + 15, x2 = detailX + detailW - 15;
        activeText = null;
        if (setting instanceof GroupSetting) {
            GroupSetting group = (GroupSetting) setting;
            group.setOpened(!group.isOpened());
        } else if (setting instanceof ButtonSetting) {
            ButtonSetting b = (ButtonSetting) setting;
            if (b.isMethodButton) b.runMethod();
            else { b.toggle(); selectedModule.guiButtonToggled(b); }
        } else if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            float bx1 = controlLeft(), bx2 = sliderTrackRight();
            if (slider.isString) {
                float[] segments = segmentLayout(slider);
                if (segments != null) {
                    String[] options = slider.getOptions();
                    for (int i = 0; i < options.length; i++) {
                        float sx1 = segmentX(segments, i);
                        if (mx < sx1 || mx > sx1 + segments[1]) continue;
                        slider.setValueWithEvent(i);
                        selectedModule.onSlide(slider);
                        return;
                    }
                    return;
                }
                openDropdown = openDropdown == slider ? null : slider;
                closeDropdownState(); // a freshly opened list always starts at the top
            } else if (button == 0 && inside(mx, my, sliderValueLeft() - 5, y + 5, bx1 - 8, y + h - 5)) {
                beginSliderValueEdit(slider);
            } else if (button == 1 && slider.canBeDisabled) {
                slider.setValueRawWithEvent(slider.getInput() == -1 ? slider.getMin() : -1);
                selectedModule.onSlide(slider);
            } else if (mx >= bx1 - 5) {
                draggingSlider = slider;
                sliderRect.set(bx1, y, bx2, y + h);
                setSliderFromMouse(slider, mx, bx1, bx2);
            }
        } else if (setting instanceof KeySetting) {
            binding = setting;
        } else if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            if (openColor != color) openColor = color; else if (my < y + 32) openColor = null;
            if (openColor == color && my >= y + 34) {
                if (colorSB.contains(mx, my)) colorDrag = 1;
                else if (colorHue.contains(mx, my)) colorDrag = 2;
                else if (color.hasAlpha() && colorAlpha.contains(mx, my)) colorDrag = 3;
                updateColor(mx, my);
            }
        } else if (setting instanceof TextSetting) {
            activeText = (TextSetting) setting;
            editor.reset(activeText.getText());
            activeList = null;
        } else if (isList(setting)) {
            float iy = y + 22;
            if (inside(mx, my, x1 + 8, iy, x2 - 34, iy + 21)) { activeList = setting; listDraft = ""; editor.reset(""); clearSuggestions(); return; }
            if (inside(mx, my, x2 - 29, iy, x2 - 8, iy + 21)) { addListEntry(setting); return; }
            float ey = iy + 27;
            for (Suggestion suggestion : suggestionsFor(setting)) {
                if (inside(mx, my, x1 + 8, ey, x2 - 8, ey + 19)) {
                    addSuggestedEntry(setting, suggestion.value);
                    return;
                }
                ey += 21;
            }
            List<String> rows = new ArrayList<String>(listEntries(setting));
            for (int row = 0; row < rows.size(); row++) {
                String entry = rows.get(row);
                boolean inventoryList = setting instanceof InventoryItemListSetting;
                if (inside(mx, my, x2 - 28, ey, x2 - 8, ey + 20)) {
                    if (inventoryList) ((InventoryItemListSetting) setting).removeItem(row);
                    else removeListEntry(setting, entry);
                    return;
                }
                if (inventoryList && inside(mx, my, x2 - 66, ey, x2 - 29, ey + 20)) {
                    InventoryItemListSetting inventory = (InventoryItemListSetting) setting;
                    int slot = inventory.getAssignedSlot(row);
                    inventory.setAssignedSlot(row, slot >= 9 ? 1 : slot + 1);
                    return;
                }
                if (inventoryList && inside(mx, my, x2 - 98, ey, x2 - 84, ey + 20)) {
                    ((InventoryItemListSetting) setting).moveItem(row, row - 1);
                    return;
                }
                if (inventoryList && inside(mx, my, x2 - 84, ey, x2 - 68, ey + 20)) {
                    ((InventoryItemListSetting) setting).moveItem(row, row + 1);
                    return;
                }
                ey += 23;
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        draggingSlider = null;
        colorDrag = 0;
        draggingScrollbar = 0;
        draggingGui = false;
        draggingMascot = false;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
        if (!searchFocused || button != 0) return;
        computeLayout();
        float searchW = Math.min(170f, centerW * .43f);
        float searchX = centerX + centerW - searchW - 16f;
        setSearchCaretFromMouse(mouseX, searchX + 11f, searchW - 35f, true);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) return;
        computeLayout();
        int mx = (int) Math.floor(Mouse.getEventX() * width / (double) mc.displayWidth);
        int my = (int) Math.floor(height - Mouse.getEventY() * height / (double) mc.displayHeight - 1);
        float speed = Gui.scrollSpeed == null ? 28f : (float) Math.max(8d, Math.min(90d, Gui.scrollSpeed.getInput()));
        float amount = wheel > 0 ? speed : -speed;
        if (overDropdown(mx, my)) {
            dropdownScrollTarget += amount;
            clampDropdownScroll();
            return;
        }
        if (inside(mx, my, centerX + 8, baseY + 61, centerX + centerW - 8, baseY + panelH - 12)) moduleScrollTarget += amount;
        else if (inside(mx, my, detailX + 8, baseY + 59, detailX + detailW - 8, baseY + panelH - 12)) settingScrollTarget += amount;
        clampScrolls();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (binding != null) {
            int value = keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_DELETE ? 0 : keyCode;
            if (binding instanceof Module) ((Module) binding).setBind(value);
            else if (binding instanceof KeySetting) ((KeySetting) binding).setKey(value);
            binding = null;
            return;
        }
        Module guiModule = Module.getModule(Gui.class);
        if (guiModule != null && keyCode == guiModule.getKeycode()) {
            closeDashboard();
            return;
        }
        if (keyCode == Keyboard.KEY_F && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
            searchFocused = true;
            searchCaret = searchSelectionAnchor = search.length();
            activeText = null;
            activeList = null;
            return;
        }
        if (editingSliderValue != null) {
            editSliderValue(typedChar, keyCode);
            return;
        }
        if (searchFocused) {
            editSearch(typedChar, keyCode);
            return;
        }
        if (activeText != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) { activeText = null; return; }
            if (keyCode == Keyboard.KEY_RETURN) { activeText.submit(); activeText = null; return; }
            if (editor.keyTyped(typedChar, keyCode, activeText.getMaxLength())) activeText.setText(editor.getText());
            return;
        }
        if (activeList != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) { activeList = null; listDraft = ""; editor.reset(""); return; }
            if (keyCode == Keyboard.KEY_RETURN) { addListEntry(activeList); return; }
            if (editor.keyTyped(typedChar, keyCode, listMaxLength(activeList))) listDraft = editor.getText();
            clearSuggestions();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (openDropdown != null) {
                closeDropdownState();
                return;
            }
            if (selectedModule != null) {
                selectModule(null);
                return;
            }
            if (!search.isEmpty()) {
                search = "";
                searchCaret = searchSelectionAnchor = 0;
                return;
            }
            closeDashboard();
        }
    }

    private void closeDashboard() {
        guiClosing = true;
    }

    private void updateDragging(int mx, int my) {
        if (!Mouse.isButtonDown(0)) {
            draggingSlider = null; colorDrag = 0; draggingScrollbar = 0;
            draggingGui = false; draggingMascot = false;
            return;
        }
        if (draggingGui) {
            guiDragOffsetX = dragStartOffsetX + (mx - dragStartMouseX);
            guiDragOffsetY = dragStartOffsetY + (my - dragStartMouseY);
        }
        if (draggingMascot) {
            mascotDragOffsetX = mascotDragStartOffsetX + (mx - mascotDragStartMouseX);
            mascotDragOffsetY = mascotDragStartOffsetY + (my - mascotDragStartMouseY);
        }
        if (draggingScrollbar != 0) updateScrollbarDrag(my);
        if (draggingSlider != null) setSliderFromMouse(draggingSlider, mx, sliderRect.x1, sliderRect.x2);
        if (openColor != null && colorDrag != 0) updateColor(mx, my);
    }

    private boolean beginScrollbarDrag(int mx, int my) {
        if (beginScrollbarDrag(1, mx, my, centerX + centerW - 7, moduleScrollTop(),
                baseY + panelH - 12, moduleScroll, modulesContentHeight)) return true;
        return beginScrollbarDrag(2, mx, my, detailX + detailW - 7, baseY + 59,
                baseY + panelH - 12, settingScroll, settingsContentHeight);
    }

    private boolean beginScrollbarDrag(int id, int mx, int my, float x, float top, float bottom,
                                       float scroll, float content) {
        float viewport = bottom - top;
        if (content <= viewport || content <= 0 || !inside(mx, my, x - 5, top, x + 7, bottom)) return false;
        float thumb = Math.max(18, viewport * viewport / content);
        float progress = clamp01(-scroll / Math.max(1, content - viewport));
        float thumbTop = top + (viewport - thumb) * progress;
        draggingScrollbar = id;
        scrollbarDragOffset = inside(mx, my, x - 5, thumbTop, x + 7, thumbTop + thumb)
                ? my - thumbTop : thumb / 2f;
        updateScrollbarDrag(my);
        return true;
    }

    private void updateScrollbarDrag(int mouseY) {
        boolean modules = draggingScrollbar == 1;
        float top = modules ? moduleScrollTop() : baseY + 59f;
        float bottom = baseY + panelH - (modules ? 12f : 52f);
        float content = modules ? modulesContentHeight : settingsContentHeight;
        float viewport = bottom - top;
        if (content <= viewport || content <= 0) return;
        float thumb = Math.max(18, viewport * viewport / content);
        float thumbTop = Math.max(top, Math.min(bottom - thumb, mouseY - scrollbarDragOffset));
        float progress = clamp01((thumbTop - top) / Math.max(1, viewport - thumb));
        float value = -progress * Math.max(0, content - viewport);
        if (modules) moduleScroll = moduleScrollTarget = value;
        else settingScroll = settingScrollTarget = value;
    }

    private void updateColor(int mx, int my) {
        if (openColor == null) return;
        if (colorDrag == 1) openColor.setFromHSB(openColor.getHue(), clamp01((mx - colorSB.x1) / colorSB.w()), 1f - clamp01((my - colorSB.y1) / colorSB.h()));
        else if (colorDrag == 2) openColor.setHue(clamp01((my - colorHue.y1) / colorHue.h()) * 360f);
        else if (colorDrag == 3) openColor.setAlpha((int) (clamp01((mx - colorAlpha.x1) / colorAlpha.w()) * 255));
    }

    private void setSliderFromMouse(SliderSetting slider, float mouse, float x1, float x2) {
        double value = slider.getMin() + clamp01((mouse - x1) / (x2 - x1)) * (slider.getMax() - slider.getMin());
        slider.setValueWithEvent(value);
        if (selectedModule != null) selectedModule.onSlide(slider);
    }

    private List<Module> modulesFor(Module.category category) {
        if (category == Module.category.profiles) {
            List<Module> profiles = new ArrayList<Module>();
            profiles.add(profileManagerModule);
            if (Mindless.profileManager != null && Mindless.profileManager.profiles != null) {
                for (Profile profile : Mindless.profileManager.profiles) profiles.add(profile.getModule());
            }
            return profiles;
        }
        if (categories != null) for (CategoryComponent c : categories) if (c.category == category) {
            List<Module> result = new ArrayList<Module>();
            for (ModuleComponent component : c.getModules()) {
                if (component.mod != null && Gui.shouldShowModule(component.mod)) result.add(component.mod);
            }
            pinManagerToTop(result);
            return result;
        }
        if (Mindless.getModuleManager() == null) return Collections.<Module>emptyList();
        List<Module> visible = new ArrayList<Module>();
        for (Module mod : Mindless.getModuleManager().inCategory(category)) {
            if (Gui.shouldShowModule(mod)) visible.add(mod);
        }
        pinManagerToTop(visible);
        return visible;
    }

    private static void pinManagerToTop(List<Module> list) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i) instanceof mindless.script.Manager) {
                Module manager = list.remove(i);
                list.add(0, manager);
                return;
            }
        }
    }

    private boolean stickyScriptManager() {
        return selectedCategory == Module.category.scripts && search.trim().isEmpty();
    }

    private static Module detachScriptManager(List<Module> modules) {
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i) instanceof mindless.script.Manager) {
                return modules.remove(i);
            }
        }
        return null;
    }

    private float moduleScrollTop() {
        return baseY + 61f + (stickyScriptManager() ? MODULE_ROW_STEP : 0f);
    }
@Override
    public void resetPositions() {
        super.resetPositions();
        moduleScroll = moduleScrollTarget = 0f;
        settingScroll = settingScrollTarget = 0f;
        dropdownScroll = dropdownScrollTarget = 0f;
        closeDropdownState();
    }

    private void activateProfile(ProfileModule module) {
        if (module == null || Mindless.profileManager == null) return;
        module.toggle();
        Profile active = Mindless.currentProfile;
        if (active != null && active.getName().equalsIgnoreCase(module.getName())) {
            selectedModule = active.getModule();
            moduleSnapshot = new ModuleSnapshot(selectedModule);
        }
    }

    private void updateProfile(ProfileModule module) {
        if (module == null || Mindless.profileManager == null) return;
        Profile profile = Mindless.profileManager.getProfile(module.getName());
        if (profile == null) return;
        Mindless.profileManager.saveProfile(profile);
        profile.getModule().saved = true;
        moduleSnapshot = new ModuleSnapshot(profile.getModule());
        mindless.utility.Utils.sendMessage("&7Updated profile: &b" + profile.getName());
    }

    private List<Module> filteredModules() {
        List<Module> result = new ArrayList<Module>();
        String query = search.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            result.addAll(modulesFor(selectedCategory));
        } else {
            Set<Module> seen = Collections.newSetFromMap(new IdentityHashMap<Module, Boolean>());
            for (Module.category category : Module.category.values()) {
                for (Module module : modulesFor(category)) {
                    if (module != null && seen.add(module)
                            && (module.getName().toLowerCase(Locale.ROOT).contains(query)
                            || categoryName(module.moduleCategory()).toLowerCase(Locale.ROOT).contains(query))) {
                        result.add(module);
                    }
                }
            }
        }
        Collections.sort(result, new Comparator<Module>() {
            public int compare(Module a, Module b) {
                boolean am = a instanceof Manager;
                boolean bm = b instanceof Manager;
                if (am != bm) return am ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return result;
    }

    private void selectCategory(Module.category category) {
        selectedCategory = category;
        search = "";
        searchFocused = false;
        moduleScroll = moduleScrollTarget = settingScroll = settingScrollTarget = 0;
        selectModule(null);
    }

    private void syncSelectedModule() {
        if (selectedModule == null || modulesFor(selectedCategory).contains(selectedModule)) return;
        selectModule(null);
    }

    private void openModule(Module module) {
        if (module == null) return;
        if (!search.trim().isEmpty()) {
            selectedCategory = module.moduleCategory();
            search = "";
            searchFocused = false;
            moduleScroll = moduleScrollTarget = 0;
        }
        selectModule(module);
    }

    private void selectModule(Module module) {
        selectedModule = module;
        moduleSnapshot = module == null ? null : new ModuleSnapshot(module);
        settingScroll = settingScrollTarget = 0;
        activeText = null;
        activeList = null;
        draggingSlider = null;
        openDropdown = null;
        closeDropdownState();
        openColor = null;
        binding = null;
    }

    private GroupSetting groupOf(Setting setting) {
        if (setting instanceof SliderSetting) return ((SliderSetting) setting).groupSetting;
        if (setting instanceof ButtonSetting) return ((ButtonSetting) setting).group;
        if (setting instanceof ColorSetting) return ((ColorSetting) setting).groupSetting;
        if (setting instanceof KeySetting) return ((KeySetting) setting).group;
        if (setting instanceof TextSetting) return ((TextSetting) setting).group;
        if (setting instanceof StringListSetting) return ((StringListSetting) setting).group;
        if (setting instanceof PlayerListSetting) return ((PlayerListSetting) setting).group;
        if (setting instanceof BlockListSetting) return ((BlockListSetting) setting).group;
        return null;
    }

    private float settingHeight(Setting setting) {
        if (setting instanceof DescriptionSetting) return 31;
        if (setting instanceof GroupSetting) return 31;
        if (setting instanceof ButtonSetting && ((ButtonSetting) setting).isMethodButton) return 25;
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (!slider.isString) return 32;
            return 32; // dropdown draws as floating overlay, not in-flow
        }
        if (setting instanceof ColorSetting) return openColor == setting ? (((ColorSetting) setting).hasAlpha() ? 102 : 91) : 32;
        if (setting instanceof TextSetting) return 52;
        if (isList(setting)) return 52 + suggestionsFor(setting).size() * 21 + listEntries(setting).size() * 23;
        return 32;
    }

    private boolean isList(Setting setting) {
        return setting instanceof BlockListSetting || setting instanceof PotionListSetting || setting instanceof PlayerListSetting || setting instanceof StringListSetting;
    }

    private List<String> listEntries(Setting setting) {
        if (setting instanceof PlayerListSetting) {
            List<String> out = new ArrayList<String>();
            for (PlayerRelationsManager.PlayerEntry e : ((PlayerListSetting) setting).getEntries()) out.add(e.getDisplayName());
            return out;
        }
        if (setting instanceof StringListSetting) return new ArrayList<String>(((StringListSetting) setting).getEntries());
        if (setting instanceof PotionListSetting) return new ArrayList<String>(((PotionListSetting) setting).getPotions());
        if (setting instanceof ItemListSetting) return new ArrayList<String>(((ItemListSetting) setting).getItems());
        if (setting instanceof BlockListSetting) return new ArrayList<String>(((BlockListSetting) setting).getBlocks());
        return Collections.emptyList();
    }

    private String listPlaceholder(Setting setting) {
        if (setting instanceof PlayerListSetting) return ((PlayerListSetting) setting).getPlaceholder();
        if (setting instanceof StringListSetting) return ((StringListSetting) setting).getPlaceholder();
        if (setting instanceof PotionListSetting) return "Potion id...";
        if (setting instanceof ItemListSetting) return "Item id...";
        return "Block id...";
    }

    private int listMaxLength(Setting setting) {
        if (setting instanceof PlayerListSetting) return ((PlayerListSetting) setting).getMaxLength();
        if (setting instanceof StringListSetting) return ((StringListSetting) setting).getMaxLength();
        return 96;
    }

    private void addListEntry(Setting setting) {
        String value = listDraft.trim();
        if (value.isEmpty()) return;
        if (setting instanceof PlayerListSetting) ((PlayerListSetting) setting).addPlayer(value);
        else if (setting instanceof StringListSetting) ((StringListSetting) setting).addEntry(value);
        else if (setting instanceof PotionListSetting) ((PotionListSetting) setting).addPotion(value);
        else if (setting instanceof ItemListSetting) ((ItemListSetting) setting).addItem(value);
        else if (setting instanceof BlockListSetting) ((BlockListSetting) setting).addBlock(value);
        listDraft = "";
        editor.reset("");
        clearSuggestions();
    }

    private void addSuggestedEntry(Setting setting, String value) {
        listDraft = value == null ? "" : value;
        editor.reset(listDraft);
        addListEntry(setting);
    }

    private void removeListEntry(Setting setting, String value) {
        if (setting instanceof PlayerListSetting) ((PlayerListSetting) setting).removePlayer(value);
        else if (setting instanceof StringListSetting) ((StringListSetting) setting).removeEntry(value);
        else if (setting instanceof PotionListSetting) ((PotionListSetting) setting).removePotion(value);
        else if (setting instanceof ItemListSetting) ((ItemListSetting) setting).removeItem(value);
        else if (setting instanceof BlockListSetting) ((BlockListSetting) setting).removeBlock(value);
    }

    private List<Suggestion> suggestionsFor(Setting setting) {
        if (activeList != setting || listDraft.trim().isEmpty()) return Collections.emptyList();
        if (suggestionSetting == setting && suggestionQuery.equals(listDraft)) return suggestionCache;
        List<Suggestion> out = new ArrayList<Suggestion>();
        if (setting instanceof PotionListSetting) {
            for (PotionSearchIndex.PotionEntry entry : PotionSearchIndex.search(listDraft, (PotionListSetting) setting)) {
                out.add(new Suggestion(entry.displayName, entry.key));
                if (out.size() == 4) break;
            }
        } else if (setting instanceof ItemListSetting) {
            for (ItemSearchIndex.GroupedItemResult result : ItemSearchIndex.searchGrouped(listDraft, (ItemListSetting) setting)) {
                if (result.isSingleVariant()) {
                    ItemSearchIndex.ItemEntry entry = result.variants.get(0);
                    out.add(new Suggestion(entry.displayName, entry.storageId));
                } else out.add(new Suggestion(result.getGroupLabel(), result.getAllSelectionStorageId()));
                if (out.size() == 4) break;
            }
        } else if (setting instanceof BlockListSetting) {
            for (BlockSearchIndex.GroupedBlockResult result : BlockSearchIndex.searchGrouped(listDraft, (BlockListSetting) setting)) {
                if (result.isSingleVariant()) {
                    BlockSearchIndex.BlockEntry entry = result.variants.get(0);
                    out.add(new Suggestion(entry.displayName, entry.storageId));
                } else out.add(new Suggestion(result.getGroupLabel(), result.registryId + ":*"));
                if (out.size() == 4) break;
            }
        }
        suggestionSetting = setting;
        suggestionQuery = listDraft;
        suggestionCache = out;
        return out;
    }

    private void clearSuggestions() {
        suggestionSetting = null;
        suggestionQuery = "";
        suggestionCache = Collections.emptyList();
    }

    private String sliderValue(SliderSetting slider) {
        if (slider.isString && slider.getOptions() != null) {
            int i = Math.max(0, Math.min(slider.getOptions().length - 1, (int) slider.getInput()));
            return slider.getOptions()[i];
        }
        if (slider.canBeDisabled && slider.getInput() == -1) return "Disabled";
        String value = Math.abs(slider.getInput() - Math.rint(slider.getInput())) < .0001 ? String.valueOf((int) slider.getInput()) : String.valueOf(slider.getInput());
        return value + slider.getSuffix();
    }

    private void updateSmoothScroll() {
        moduleScroll += (moduleScrollTarget - moduleScroll) * .28f;
        settingScroll += (settingScrollTarget - settingScroll) * .28f;
        if (Math.abs(moduleScrollTarget - moduleScroll) < .08f) moduleScroll = moduleScrollTarget;
        if (Math.abs(settingScrollTarget - settingScroll) < .08f) settingScroll = settingScrollTarget;
    }

    private void clampScrolls() {
        float moduleViewport = Math.max(1, baseY + panelH - 12f - moduleScrollTop());
        float settingViewport = Math.max(1, panelH - 71);
        moduleScrollTarget = Math.min(0, Math.max(moduleViewport - modulesContentHeight, moduleScrollTarget));
        settingScrollTarget = Math.min(0, Math.max(settingViewport - settingsContentHeight, settingScrollTarget));
    }

    private void drawScrollbar(float x, float top, float bottom, float scroll, float content) {
        float viewport = bottom - top;
        if (content <= viewport || content <= 0) return;
        float thumb = Math.max(18, viewport * viewport / content);
        float progress = clamp01(-scroll / Math.max(1, content - viewport));
        float y = top + (viewport - thumb) * progress;
        rounded(x, top + 3, x + 2f, bottom - 3, 1f, argb(20, 105, 108, 108));
        rounded(x - .25f, y, x + 2.25f, y + thumb, 1.25f, withAlpha(ACCENT, 170));
    }

    private void panelSurface(float x1, float y1, float x2, float y2, int color) {
        rounded(x1, y1, x2, y2, 7.5f, color);
    }

    private void drawPanelShadow(float x, float y, float width, float height, float renderScale) {
        RoundedUtils.drawRoundShadow(x * renderScale, y * renderScale,
                width * renderScale, height * renderScale,
                7.5f * renderScale, 6f * renderScale, argb(108, 0, 0, 0));
    }
private static float radius(float radius, float w, float h) {
        float scaled = radius * mindless.module.impl.theme.ThemeManager.roundingScale();
        float limit = Math.min(Math.abs(w), Math.abs(h)) * .5f;
        return Math.max(.5f, Math.min(scaled, Math.max(.5f, limit)));
    }
private static String uiFontFamily(boolean bold) {
        String family = Gui.getSelectedFontName();
        if (family == null || family.isEmpty() || "Minecraft".equalsIgnoreCase(family)) {
            return bold ? FALLBACK_FONT_BOLD : FALLBACK_FONT_REGULAR;
        }
        if (!bold) return family;
        return FALLBACK_FONT_REGULAR.equals(family) ? FALLBACK_FONT_BOLD : family;
    }

    private void outline(float x1, float y1, float x2, float y2, float radius, int color) {
        float b = 1f;
        float w = (x2 - x1) + b * 2f, h = (y2 - y1) + b * 2f;
        RoundedUtils.drawRound(x1 - b, y1 - b, w, h, radius(radius + b, w, h), color);
    }

    private void rounded(float x1, float y1, float x2, float y2, float radius, int color) {
        RoundedUtils.drawRound(x1, y1, x2 - x1, y2 - y1, radius(radius, x2 - x1, y2 - y1), color);
    }
private void roundedCorners(float x1, float y1, float x2, float y2,
                                float topLeft, float topRight, float bottomRight, float bottomLeft,
                                int color) {
        float w = x2 - x1, h = y2 - y1;
        RoundedUtils.drawRoundCorners(x1, y1, w, h,
                corner(topLeft, w, h), corner(topRight, w, h),
                corner(bottomRight, w, h), corner(bottomLeft, w, h), color);
    }
private void gradientRoundedCorners(float x1, float y1, float x2, float y2,
                                        float topLeft, float topRight,
                                        float bottomRight, float bottomLeft,
                                        int blColor, int tlColor, int brColor, int trColor) {
        float w = x2 - x1, h = y2 - y1;
        RoundedUtils.drawGradientRoundCorners(x1, y1, w, h,
                corner(topLeft, w, h), corner(topRight, w, h),
                corner(bottomRight, w, h), corner(bottomLeft, w, h),
                blColor, tlColor, brColor, trColor);
    }
private static float corner(float radius, float w, float h) {
        if (radius <= 0f) return 0f;
        return radius(radius, w, h);
    }
    private void line(float x1, float y1, float x2, float y2, int color) {
        float scale = pixelScale();
        float top = Math.round(Math.min(y1, y2) * scale) / scale;
        RenderUtils.drawRect(x1, top, x2, top + 1f / scale, color);
    }

    private void drawToggle(float x, float y, boolean on, Object animationKey) {
        float progress = animate(toggleAnimation, animationKey, on ? 1f : 0f, 20f);
        rounded(x, y, x + 30, y + 16, 8, mixColor(argb(255, 54, 57, 58), GOLD, progress));
        circle(x + 8 + 14 * progress, y + 8, 5.2f,
                mixColor(argb(255, 175, 177, 175), ACCENT_FOREGROUND, progress));
    }

    private void drawChevron(float x, float y, float open, int color) {
        float direction = 1f - open * 2f;
        segments(color, x - 3, y - 1.5f * direction, x, y + 1.5f * direction,
                x, y + 1.5f * direction, x + 3, y - 1.5f * direction);
    }

    private void circle(float cx, float cy, float r, int color) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, ((color >>> 24) & 255) / 255f);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 20; i++) { double a = Math.PI * 2 * i / 20; GL11.glVertex2d(cx + Math.cos(a) * r, cy + Math.sin(a) * r); }
        GL11.glEnd();
        GlStateManager.enableTexture2D();
    }

    private void drawSearchGlyph(float x, float y, int color) {
        circleOutline(x - 1.7f, y - 1.7f, 3.4f, color);
        segments(color, x + .8f, y + .8f, x + 4.2f, y + 4.2f);
    }

    private void drawSearchText(float x, float y1, float width, float y2) {
        float textX = x + 11f;
        float available = width - 35f;
        if (search.isEmpty() && !searchFocused) {
            drawTextVCentered("Search modules...", textX, y1, y2, DIM, .75f, false);
            return;
        }

        clampSearchCaret();
        searchEditAnimation = ease(searchEditAnimation, 1f, 24f);
        int start = searchViewStart(available);
        int end = searchViewEnd(start, available);
        String visible = search.substring(start, end);
        float offsetY = (1f - searchEditAnimation) * 1.35f;
        int textAlpha = (int) (185 + 70 * searchEditAnimation);

        scissor(textX - 1, y1 + 3, textX + available + 1, y2 - 3, true);
        if (hasSearchSelection()) {
            int selectionStart = Math.max(start, Math.min(searchCaret, searchSelectionAnchor));
            int selectionEnd = Math.min(end, Math.max(searchCaret, searchSelectionAnchor));
            if (selectionEnd > selectionStart) {
                float left = textX + textWidth(search.substring(start, selectionStart), .75f, false);
                float right = textX + textWidth(search.substring(start, selectionEnd), .75f, false);
                rounded(left - .5f, y1 + 5f, right + .5f, y2 - 5f, 2f, withAlpha(ACCENT, 78));
            }
        }
        drawTextVCentered(visible, textX, y1 + offsetY, y2 + offsetY,
                withAlpha(search.isEmpty() ? DIM : TEXT, textAlpha), .75f, false);
        if (searchFocused && blink()) {
            int caretInView = Math.max(start, Math.min(end, searchCaret));
            float caretX = textX + textWidth(search.substring(start, caretInView), .75f, false);
            rounded(caretX, y1 + 6f, caretX + .8f, y2 - 6f, .4f, withAlpha(ACCENT, 230));
        }
        scissor(0, 0, 0, 0, false);
    }

    private int searchViewStart(float available) {
        if (!searchFocused) return 0;
        int start = 0;
        while (start < searchCaret
                && textWidth(search.substring(start, searchCaret), .75f, false) > available) start++;
        return start;
    }

    private int searchViewEnd(int start, float available) {
        int end = start;
        while (end < search.length()
                && textWidth(search.substring(start, end + 1), .75f, false) <= available) end++;
        return end;
    }

    private void drawCategoryIcon(Module.category category, float x, float y, int color) {
        ResourceLocation icon = categoryIcons.get(category);
        if (icon != null) {
            float size = 12f;
            float r = ((color >> 16) & 255) / 255f;
            float g = ((color >> 8) & 255) / 255f;
            float b = (color & 255) / 255f;
            float a = ((color >>> 24) & 255) / 255f;
            drawTextureRegion(icon, x - size / 2f, y - size / 2f, size, size,
                    0, 0, 32, 32, 32, 32, r, g, b, a);
            return;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(.82f, .82f, 1f);
        x = 0f; y = 0f;
        switch (category) {
            case combat:
                segments(color,
                    x, y - 6.5f, x, y - 3.2f,
                    x, y + 3.2f, x, y + 6.5f,
                    x - 6.5f, y, x - 3.2f, y,
                    x + 3.2f, y, x + 6.5f, y);
                circle(x, y, 1.2f, color);
                circleOutline(x, y, 3.0f, color);
                break;
            case movement:
                segments(color,
                    x - 5.5f, y + 5.5f, x + 5.5f, y - 5.5f,
                    x + 5.5f, y - 5.5f, x + 5.5f, y + 1.5f,
                    x + 5.5f, y - 5.5f, x - 1.5f, y - 5.5f,
                    x + 5.5f, y - 5.5f, x - 1.5f, y + 1.5f);
                break;
            case player:
                circleOutline(x, y - 3.8f, 2.4f, color);
                segments(color,
                    x - 4.5f, y + 6f, x - 3.6f, y + 1.8f,
                    x - 3.6f, y + 1.8f, x - .8f, y + .6f,
                    x - .8f, y + .6f, x + .8f, y + .6f,
                    x + .8f, y + .6f, x + 3.6f, y + 1.8f,
                    x + 3.6f, y + 1.8f, x + 4.5f, y + 6f);
                break;
            case world:
                circleOutline(x, y, 6f, color);
                segments(color,
                    x - 6f, y, x + 6f, y,
                    x, y - 6f, x, y + 6f);
                segments(color,
                    x - 5.2f, y - 3f, x + 5.2f, y - 3f,
                    x - 5.2f, y + 3f, x + 5.2f, y + 3f);
                break;
            case render:
                lineBox(x - 5.8f, y - 5f, x + 5.8f, y + 2.8f, color);
                segments(color,
                    x, y + 2.8f, x, y + 5.5f,
                    x - 3f, y + 5.5f, x + 3f, y + 5.5f);
                break;
            case other:
                circle(x - 4.5f, y, 1.4f, color);
                circle(x, y, 1.4f, color);
                circle(x + 4.5f, y, 1.4f, color);
                break;
            case client:
                segments(color,
                    x - 6f, y - 4f, x + 6f, y - 4f,
                    x - 6f, y, x + 6f, y,
                    x - 6f, y + 4f, x + 6f, y + 4f);
                circle(x + 1.5f, y - 4f, 1.3f, color);
                circle(x - 2f, y, 1.3f, color);
                circle(x + 2.5f, y + 4f, 1.3f, color);
                break;
            case profiles:
                lineBox(x - 5.8f, y - 5f, x + 5.8f, y + 5f, color);
                circleOutline(x - 2.5f, y - 1f, 1.6f, color);
                segments(color,
                    x - 4.5f, y + 2.8f, x - .5f, y + 2.8f,
                    x + 1f, y - 1.8f, x + 4.5f, y - 1.8f,
                    x + 1f, y + .6f, x + 4.5f, y + .6f,
                    x + 1f, y + 2.8f, x + 4.5f, y + 2.8f);
                break;
            case theme:
                circleOutline(x, y, 6f, color);
                circleOutline(x + 2.6f, y + 2.6f, 1.6f, color);
                circle(x - 3.2f, y - 1.2f, 1.25f, color);
                circle(x - 0.4f, y - 3.6f, 1.25f, color);
                circle(x + 2.8f, y - 2.4f, 1.25f, color);
                break;
            case bedwars:
                segments(color,
                    x - 6.5f, y + 2.5f, x - 6.5f, y - 4.5f,
                    x - 6.5f, y - 0.5f, x + 6.5f, y - 0.5f,
                    x - 6.5f, y + 2.5f, x + 6.5f, y + 2.5f,
                    x + 6.5f, y - 0.5f, x + 6.5f, y + 2.5f,
                    x - 5f, y + 2.5f, x - 5f, y + 4.5f,
                    x + 5f, y + 2.5f, x + 5f, y + 4.5f);
                lineBox(x - 5.5f, y - 2.5f, x - 2.5f, y - 0.5f, color);
                break;
            case scripts:
                segments(color,
                    x - 5f, y - 3.5f, x - 2f, y,
                    x - 2f, y, x - 5f, y + 3.5f,
                    x + 5f, y - 3.5f, x + 2f, y,
                    x + 2f, y, x + 5f, y + 3.5f,
                    x - 1.5f, y + 4.5f, x + 1.5f, y - 4.5f);
                break;
            default:
                circleOutline(x, y, 3.5f, color);
        }
        GL11.glPopMatrix();
    }

    private void lineBox(float x1, float y1, float x2, float y2, int color) {
        segments(color, x1, y1, x2, y1, x2, y1, x2, y2,
                x2, y2, x1, y2, x1, y2, x1, y1);
    }

    private void segments(int color, float... points) {
        if (points == null || points.length < 4) return;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlStateManager.color(((color >> 16)&255)/255f, ((color>>8)&255)/255f, (color&255)/255f, ((color>>>24)&255)/255f);
        GL11.glLineWidth(1.15f);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i + 3 < points.length; i += 4) {
            GL11.glVertex2f(points[i], points[i + 1]);
            GL11.glVertex2f(points[i + 2], points[i + 3]);
        }
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableTexture2D();
    }

    private void circleOutline(float cx, float cy, float r, int color) {
        GlStateManager.disableTexture2D(); GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GlStateManager.color(((color >> 16)&255)/255f, ((color>>8)&255)/255f, (color&255)/255f, ((color>>>24)&255)/255f);
        GL11.glLineWidth(1.2f); GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i=0;i<18;i++){double a=Math.PI*2*i/18;GL11.glVertex2d(cx+Math.cos(a)*r,cy+Math.sin(a)*r);} GL11.glEnd(); GlStateManager.enableTexture2D();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void ensureUiTextures() {
        if (uiTextureLoadAttempted) return;
        uiTextureLoadAttempted = true;
        logoTexture = loadBundledTexture("mindless_modern_logo", LOGO_RESOURCE, true,
                LOGO_RASTER_W, LOGO_RASTER_H);
        mascotTextureCat = loadBundledTexture("mindless_mascot_0", "/assets/mindless/textures/gui/mascot_0.png", true);
        mascotTextureMindless = loadBundledTexture("mindless_mascot_1", "/assets/mindless/textures/gui/mascot_1.png", true);
        for (Module.category cat : Module.category.values()) {
            String iconName = categoryIconName(cat);
            String path = "/assets/mindless/textures/gui/icons/" + iconName + ".png";
            ResourceLocation loc = loadBundledTexture("mindless_icon_" + iconName, path, true);
            if (loc != null) categoryIcons.put(cat, loc);
        }
    }

    private String categoryIconName(Module.category cat) {
        switch (cat) {
            case client: return "settings";
            default: return cat.name();
        }
    }

    private ResourceLocation loadBundledTexture(String name, String path, boolean smooth) {
        return loadBundledTexture(name, path, smooth, 0, 0);
    }
private ResourceLocation loadBundledTexture(String name, String path, boolean smooth,
                                                int rasterW, int rasterH) {
        try (InputStream stream = ModernClickGui.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            BufferedImage image = ImageIO.read(stream);
            if (image == null) return null;
            if (rasterW > 0 && rasterH > 0) image = rasterize(image, rasterW, rasterH);
            DynamicTexture texture = new DynamicTexture(image);
            texture.setBlurMipmap(smooth, false);
            return mc.getTextureManager().getDynamicTextureLocation(name, texture);
        } catch (Exception ignored) {
            return null;
        }
    }
private static BufferedImage rasterize(BufferedImage source, int targetW, int targetH) {
        int w = source.getWidth(), h = source.getHeight();
        if (w <= targetW && h <= targetH) return source;
        BufferedImage image = source;
        while (w / 2 > targetW && h / 2 > targetH) {
            w /= 2;
            h /= 2;
            image = scaleTo(image, w, h);
        }
        return w == targetW && h == targetH ? image : scaleTo(image, targetW, targetH);
    }
private static BufferedImage scaleTo(BufferedImage source, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, w, h, null);
        graphics.dispose();
        return scaled;
    }

    private void drawTextureRegion(ResourceLocation texture, float x, float y, float w, float h,
                                   float u, float v, float regionW, float regionH, float textureW, float textureH,
                                   float red, float green, float blue, float alpha) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(red, green, blue, alpha);
        mc.getTextureManager().bindTexture(texture);
        float u1 = u / textureW, v1 = v / textureH;
        float u2 = (u + regionW) / textureW, v2 = (v + regionH) / textureH;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u1, v2); GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u2, v2); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u2, v1); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }
private static final float BASE_TEXT_PX = 13f;
private MindlessFontRenderer scaledFont(float scale, boolean bold) {
        float px = Math.max(6f, Math.round(BASE_TEXT_PX * scale * TEXT_SCALE));
        return FontManager.getClickGuiRenderer(uiFontFamily(bold), px);
    }

    private MindlessFontRenderer uiFont(boolean bold) {
        return scaledFont(1f, bold);
    }
private MindlessFontRenderer uiSmallFont() {
        return FontManager.getClickGuiSmallRenderer(uiFontFamily(false));
    }
private void drawSmallText(String text, float x, float y, int color) {
        MindlessFontRenderer renderer = uiSmallFont();
        GL11.glPushMatrix(); GL11.glTranslatef(x, y, 0);
        renderer.drawString(text == null ? "" : text, 0, 0, color, false);
        GL11.glPopMatrix();
    }

    private float smallTextWidth(String text) {
        return uiSmallFont().getStringWidth(text == null ? "" : text);
    }
private String trimSmall(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        MindlessFontRenderer font = uiSmallFont();
        if (font.getStringWidth(text) <= maxWidth) return text;
        String ellipsis = "..";
        float ellipsisW = font.getStringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.getStringWidth(sb.toString() + text.charAt(i)) + ellipsisW > maxWidth) {
                if (sb.length() == 0) break;
                sb.append(ellipsis);
                return sb.toString();
            }
            sb.append(text.charAt(i));
        }
        if (sb.length() > 0) return sb.toString();
        return fitWithoutEllipsis(font, text, maxWidth);
    }
private static String fitWithoutEllipsis(MindlessFontRenderer font, String text, float maxWidth) {
        int length = text.length();
        while (length > 1 && font.getStringWidth(text.substring(0, length)) > maxWidth) length--;
        return text.substring(0, length);
    }

    private void drawText(String text, float x, float y, int color, float scale, boolean bold) {
        MindlessFontRenderer renderer = scaledFont(scale, bold);
        double rs = getActiveRenderScale();
        if (rs <= 0) rs = 1;
        float sx = (float) (Math.round(x * rs) / rs);
        float sy = (float) (Math.round(y * rs) / rs);
        GL11.glPushMatrix(); GL11.glTranslatef(sx, sy, 0);
        renderer.drawString(text == null ? "" : text, 0, 0, color, false); GL11.glPopMatrix();
    }

    private void resetTextRenderState() {
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float textWidth(String text, float scale, boolean bold) { return scaledFont(scale, bold).getStringWidth(text == null ? "" : text); }
    private void drawCentered(String text, float x1, float x2, float y, int color, float scale, boolean bold) { drawText(text, (x1 + x2 - textWidth(text, scale, bold)) / 2f, y, color, scale, bold); }
    private void drawTextVCentered(String text, float x, float y1, float y2, int color, float scale, boolean bold) {
        float height = scaledFont(scale, bold).getFontHeight();
        drawText(text, x, y1 + (y2 - y1 - height) / 2f, color, scale, bold);
    }
    private void drawCenteredV(String text, float x1, float x2, float y1, float y2, int color, float scale, boolean bold) {
        drawTextVCentered(text, (x1 + x2 - textWidth(text, scale, bold)) / 2f, y1, y2, color, scale, bold);
    }
    private void drawTwoLineTextVCentered(String title, String subtitle, float x, float y1, float y2,
                                          int titleColor, int subtitleColor, float titleScale,
                                          float subtitleScale, float gap, float verticalOffset) {
        float titleHeight = scaledFont(titleScale, true).getFontHeight();
        float subtitleHeight = scaledFont(subtitleScale, false).getFontHeight();
        float blockHeight = titleHeight + gap + subtitleHeight;
        float top = y1 + (y2 - y1 - blockHeight) / 2f + verticalOffset;
        drawText(title, x, top, titleColor, titleScale, true);
        drawText(subtitle, x, top + titleHeight + gap, subtitleColor, subtitleScale, false);
    }
private String trim(String text, float maxWidth, float scale, boolean bold) {
        if (text == null) return "";
        MindlessFontRenderer f = scaledFont(scale, bold);
        if (f.getStringWidth(text) <= maxWidth) return text;
        String end = "..."; int i = text.length();
        while (i > 0 && f.getStringWidth(text.substring(0, i) + end) > maxWidth) i--;
        if (i > 0) return text.substring(0, i) + end;
        return fitWithoutEllipsis(f, text, maxWidth);
    }

    private void scissor(float x1, float y1, float x2, float y2, boolean enable) {
        if (!enable) { GL11.glDisable(GL11.GL_SCISSOR_TEST); return; }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        RenderUtils.scissor(x1, y1, x2 - x1, y2 - y1);
    }

    private void beginSliderValueEdit(SliderSetting slider) {
        editingSliderValue = slider;
        double input = slider.getInput();
        if (slider.canBeDisabled && input == -1) input = slider.getMin();
        sliderEditDraft = Math.abs(input - Math.rint(input)) < .0001
                ? String.valueOf((long) Math.rint(input)) : String.valueOf(input);
        sliderEditAnchor = 0;
        sliderEditCaret = sliderEditDraft.length();
        draggingSlider = null;
    }

    private void editSliderValue(char typed, int key) {
        clampSliderEditSelection();
        boolean control = isCtrlKeyDown();
        boolean shift = isShiftKeyDown();
        if (key == Keyboard.KEY_ESCAPE) { finishSliderValueEdit(false); return; }
        if (key == Keyboard.KEY_RETURN) { finishSliderValueEdit(true); return; }
        if (control && key == Keyboard.KEY_A) {
            sliderEditAnchor = 0;
            sliderEditCaret = sliderEditDraft.length();
            return;
        }
        if (control && key == Keyboard.KEY_C) {
            copySliderSelection();
            return;
        }
        if (control && key == Keyboard.KEY_X) {
            copySliderSelection();
            deleteSliderSelection();
            return;
        }
        if (control && key == Keyboard.KEY_V) {
            replaceSliderSelection(sanitizeNumericText(getClipboardString()));
            return;
        }
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT) {
            int target;
            if (!shift && sliderHasSelection()) target = key == Keyboard.KEY_LEFT
                    ? Math.min(sliderEditCaret, sliderEditAnchor) : Math.max(sliderEditCaret, sliderEditAnchor);
            else target = sliderEditCaret + (key == Keyboard.KEY_LEFT ? -1 : 1);
            sliderEditCaret = Math.max(0, Math.min(sliderEditDraft.length(), target));
            if (!shift) sliderEditAnchor = sliderEditCaret;
            return;
        }
        if (key == Keyboard.KEY_HOME || key == Keyboard.KEY_END) {
            sliderEditCaret = key == Keyboard.KEY_HOME ? 0 : sliderEditDraft.length();
            if (!shift) sliderEditAnchor = sliderEditCaret;
            return;
        }
        if (key == Keyboard.KEY_BACK) {
            if (!deleteSliderSelection() && sliderEditCaret > 0) {
                sliderEditDraft = sliderEditDraft.substring(0, sliderEditCaret - 1) + sliderEditDraft.substring(sliderEditCaret);
                sliderEditCaret--;
                sliderEditAnchor = sliderEditCaret;
            }
            return;
        }
        if (key == Keyboard.KEY_DELETE) {
            if (!deleteSliderSelection() && sliderEditCaret < sliderEditDraft.length()) {
                sliderEditDraft = sliderEditDraft.substring(0, sliderEditCaret) + sliderEditDraft.substring(sliderEditCaret + 1);
                sliderEditAnchor = sliderEditCaret;
            }
            return;
        }
        if ((typed >= '0' && typed <= '9') || typed == '-' || typed == '+' || typed == '.' || typed == 'e' || typed == 'E') {
            replaceSliderSelection(String.valueOf(typed));
        }
    }

    private void finishSliderValueEdit(boolean apply) {
        SliderSetting slider = editingSliderValue;
        if (slider == null) return;
        if (apply) {
            try {
                double value = Double.parseDouble(sliderEditDraft.trim());
                if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                    slider.setValueUnclampedWithEvent(value);
                    if (selectedModule != null) selectedModule.onSlide(slider);
                }
            } catch (NumberFormatException ignored) { }
        }
        editingSliderValue = null;
        sliderEditDraft = "";
        sliderEditCaret = sliderEditAnchor = 0;
    }

    private boolean sliderHasSelection() {
        return sliderEditCaret != sliderEditAnchor;
    }

    private boolean deleteSliderSelection() {
        if (!sliderHasSelection()) return false;
        int from = Math.min(sliderEditCaret, sliderEditAnchor);
        int to = Math.max(sliderEditCaret, sliderEditAnchor);
        sliderEditDraft = sliderEditDraft.substring(0, from) + sliderEditDraft.substring(to);
        sliderEditCaret = sliderEditAnchor = from;
        return true;
    }

    private void replaceSliderSelection(String replacement) {
        if (replacement == null) replacement = "";
        int from = Math.min(sliderEditCaret, sliderEditAnchor);
        int to = Math.max(sliderEditCaret, sliderEditAnchor);
        int room = Math.max(0, 20 - (sliderEditDraft.length() - (to - from)));
        if (replacement.length() > room) replacement = replacement.substring(0, room);
        sliderEditDraft = sliderEditDraft.substring(0, from) + replacement + sliderEditDraft.substring(to);
        sliderEditCaret = sliderEditAnchor = from + replacement.length();
    }

    private void copySliderSelection() {
        if (!sliderHasSelection()) return;
        int from = Math.min(sliderEditCaret, sliderEditAnchor);
        int to = Math.max(sliderEditCaret, sliderEditAnchor);
        setClipboardString(sliderEditDraft.substring(from, to));
    }

    private String sanitizeNumericText(String value) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') clean.append(c);
        }
        return clean.toString();
    }

    private void clampSliderEditSelection() {
        sliderEditCaret = Math.max(0, Math.min(sliderEditDraft.length(), sliderEditCaret));
        sliderEditAnchor = Math.max(0, Math.min(sliderEditDraft.length(), sliderEditAnchor));
    }

    private void editSearch(char typed, int key) {
        clampSearchCaret();
        boolean control = isCtrlKeyDown();
        boolean shift = isShiftKeyDown();
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) {
            searchFocused = false;
            searchSelectionAnchor = searchCaret;
            return;
        }
        if (control && key == Keyboard.KEY_A) {
            searchSelectionAnchor = 0;
            searchCaret = search.length();
            return;
        }
        if (control && key == Keyboard.KEY_C) {
            copySearchSelection();
            return;
        }
        if (control && key == Keyboard.KEY_X) {
            copySearchSelection();
            if (deleteSearchSelection()) markSearchEdited();
            return;
        }
        if (control && key == Keyboard.KEY_V) {
            String clipboard = sanitizeSearchText(getClipboardString());
            replaceSearchSelection(clipboard);
            return;
        }
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT) {
            int target;
            if (!shift && hasSearchSelection()) {
                target = key == Keyboard.KEY_LEFT
                        ? Math.min(searchCaret, searchSelectionAnchor)
                        : Math.max(searchCaret, searchSelectionAnchor);
            } else if (key == Keyboard.KEY_LEFT) {
                target = control ? previousSearchWord(searchCaret) : searchCaret - 1;
            } else {
                target = control ? nextSearchWord(searchCaret) : searchCaret + 1;
            }
            moveSearchCaret(target, shift);
            return;
        }
        if (key == Keyboard.KEY_HOME || key == Keyboard.KEY_END) {
            moveSearchCaret(key == Keyboard.KEY_HOME ? 0 : search.length(), shift);
            return;
        }
        if (key == Keyboard.KEY_BACK) {
            if (deleteSearchSelection()) markSearchEdited();
            else if (searchCaret > 0) {
                int from = control ? previousSearchWord(searchCaret) : searchCaret - 1;
                search = search.substring(0, from) + search.substring(searchCaret);
                searchCaret = searchSelectionAnchor = from;
                markSearchEdited();
            }
            return;
        }
        if (key == Keyboard.KEY_DELETE) {
            if (deleteSearchSelection()) markSearchEdited();
            else if (searchCaret < search.length()) {
                int to = control ? nextSearchWord(searchCaret) : searchCaret + 1;
                search = search.substring(0, searchCaret) + search.substring(to);
                searchSelectionAnchor = searchCaret;
                markSearchEdited();
            }
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typed)) replaceSearchSelection(String.valueOf(typed));
    }

    private void setSearchCaretFromMouse(float mouseX, float textX, float available, boolean extend) {
        clampSearchCaret();
        int start = searchViewStart(available);
        int end = searchViewEnd(start, available);
        int best = start;
        float local = Math.max(0f, mouseX - textX);
        for (int i = start; i <= end; i++) {
            float position = textWidth(search.substring(start, i), .75f, false);
            if (position <= local) best = i;
            else {
                float previous = textWidth(search.substring(start, Math.max(start, i - 1)), .75f, false);
                if (local - previous > (position - previous) / 2f) best = i;
                break;
            }
        }
        moveSearchCaret(best, extend);
    }

    private void moveSearchCaret(int target, boolean extend) {
        searchCaret = Math.max(0, Math.min(search.length(), target));
        if (!extend) searchSelectionAnchor = searchCaret;
    }

    private boolean hasSearchSelection() {
        return searchCaret != searchSelectionAnchor;
    }

    private boolean deleteSearchSelection() {
        if (!hasSearchSelection()) return false;
        int from = Math.min(searchCaret, searchSelectionAnchor);
        int to = Math.max(searchCaret, searchSelectionAnchor);
        search = search.substring(0, from) + search.substring(to);
        searchCaret = searchSelectionAnchor = from;
        return true;
    }

    private void replaceSearchSelection(String replacement) {
        if (replacement == null) replacement = "";
        int from = Math.min(searchCaret, searchSelectionAnchor);
        int to = Math.max(searchCaret, searchSelectionAnchor);
        int room = Math.max(0, 64 - (search.length() - (to - from)));
        if (replacement.length() > room) replacement = replacement.substring(0, room);
        search = search.substring(0, from) + replacement + search.substring(to);
        searchCaret = searchSelectionAnchor = from + replacement.length();
        markSearchEdited();
    }

    private String sanitizeSearchText(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(c)) clean.append(c);
        }
        return clean.toString();
    }

    private void copySearchSelection() {
        if (!hasSearchSelection()) return;
        int from = Math.min(searchCaret, searchSelectionAnchor);
        int to = Math.max(searchCaret, searchSelectionAnchor);
        setClipboardString(search.substring(from, to));
    }

    private int previousSearchWord(int index) {
        int i = Math.max(0, Math.min(search.length(), index));
        while (i > 0 && Character.isWhitespace(search.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(search.charAt(i - 1))) i--;
        return i;
    }

    private int nextSearchWord(int index) {
        int i = Math.max(0, Math.min(search.length(), index));
        while (i < search.length() && !Character.isWhitespace(search.charAt(i))) i++;
        while (i < search.length() && Character.isWhitespace(search.charAt(i))) i++;
        return i;
    }

    private void clampSearchCaret() {
        searchCaret = Math.max(0, Math.min(search.length(), searchCaret));
        searchSelectionAnchor = Math.max(0, Math.min(search.length(), searchSelectionAnchor));
    }

    private void markSearchEdited() {
        searchEditAnimation = 0f;
        moduleScroll = moduleScrollTarget = 0f;
    }

    private String moduleDescription(Module module) {
        if (module instanceof ProfileModule) return module.isEnabled() ? "Currently active configuration." : "Click the row or Load to apply this configuration.";
        if (module instanceof mindless.script.Manager) return "Create, reload, and organize scripts.";
        String name = module.getName().toLowerCase(Locale.ROOT);
        if (module.script != null) return module.script.error ? "Script failed to compile." : "Loaded Mindless script module.";

        return module.getDescription();
    }

    private String methodActionLabel(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalized.contains("remove") || normalized.contains("delete")) return "Remove";
        if (normalized.contains("update") || normalized.contains("save")) return "Update";
        if (normalized.contains("reload")) return "Reload";
        if (normalized.contains("create")) return "Create";
        if (normalized.contains("open")) return "Open";
        return "Run";
    }

    private String categoryName(Module.category category) {
        switch (category) {
            case client: return "Settings";
            default: String n = category.name(); return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
    }

    private String categoryMark(Module.category category) {
        switch (category) {
            case combat: return "C"; case movement: return "M"; case player: return "P"; case render: return "R";
            case world: return "W"; case other: return "O";
            case client: return "S"; case theme: return "T";
            case profiles: return "P"; case scripts: return "<";
            case bedwars: return "B";
            default: return "*";
        }
    }

    private String keyName(int key) {
        if (key == 0) return "NONE";
        if (key >= 1000) return "M" + (key - 999);
        String name = Keyboard.getKeyName(key);
        return name == null ? "NONE" : name;
    }

    private boolean blink() { return (System.currentTimeMillis() / 500L & 1L) == 0L; }
    private boolean inside(float x, float y, float x1, float y1, float x2, float y2) { return x >= x1 && x <= x2 && y >= y1 && y <= y2; }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int argb(int a, int r, int g, int b) { return ((a&255)<<24)|((r&255)<<16)|((g&255)<<8)|(b&255); }

    private void updateThemePalette() {
        mindless.module.impl.theme.ThemeManager.poll();
        int next = Gui.themeColor == null ? argb(255, 159, 143, 210)
                : 0xFF000000 | Gui.themeColor.getRGB();
        int nextText = Gui.themeTextColor == null ? argb(255, 235, 234, 230)
                : 0xFF000000 | Gui.themeTextColor.getRGB();

        if (next != ACCENT || nextText != TEXT) {
            ACCENT = GOLD = next;
            ACCENT_SOFT = GOLD_SOFT = withAlpha(next, 42);
            TEXT = nextText;
            MUTED = mixColor(nextText, argb(255, 95, 98, 98), .56f);
            DIM = mixColor(nextText, argb(255, 70, 74, 74), .79f);
            int red = (next >> 16) & 255;
            int green = (next >> 8) & 255;
            int blue = next & 255;
            double luminance = red * .2126 + green * .7152 + blue * .0722;
            ACCENT_FOREGROUND = luminance >= 145.0
                    ? argb(255, 25, 22, 31)
                    : argb(255, 244, 242, 248);
        }

        updateSurfacePalette();
    }
private void updateSurfacePalette() {
        if (mindless.module.impl.theme.ThemeManager.colorsOverridden()) {
            surfaceSeed = -3;
            PANEL = mindless.module.impl.theme.ThemeManager.panel();
            PANEL_ALT = mindless.module.impl.theme.ThemeManager.panelAlt();
            ROW = mindless.module.impl.theme.ThemeManager.row();
            ROW_HOVER = mindless.module.impl.theme.ThemeManager.rowHover();
            CONTROL = mindless.module.impl.theme.ThemeManager.control();
            CONTROL_HOVER = mindless.module.impl.theme.ThemeManager.controlHover();
            BORDER = mindless.module.impl.theme.ThemeManager.border();
            DIVIDER = mindless.module.impl.theme.ThemeManager.divider();
            DROPDOWN_BG = mindless.module.impl.theme.ThemeManager.dropdown();
            DROPDOWN_BORDER = mindless.module.impl.theme.ThemeManager.dropdownBorder();
            DROPDOWN_SELECTED = mindless.module.impl.theme.ThemeManager.dropdownSelected();
            return;
        }

        if (!mindless.module.impl.theme.ThemeManager.surfacesEnabled()) {
            if (surfaceSeed == -1) return;
            surfaceSeed = -1;
            PANEL = DEFAULT_PANEL;
            PANEL_ALT = DEFAULT_PANEL_ALT;
            ROW = DEFAULT_ROW;
            ROW_HOVER = DEFAULT_ROW_HOVER;
            CONTROL = DEFAULT_CONTROL;
            CONTROL_HOVER = DEFAULT_CONTROL_HOVER;
            BORDER = DEFAULT_BORDER;
            DIVIDER = DEFAULT_DIVIDER;
            DROPDOWN_BG = DEFAULT_PANEL_ALT;
            DROPDOWN_BORDER = DEFAULT_BORDER;
            DROPDOWN_SELECTED = withAlpha(ACCENT, 80);
            return;
        }

        java.awt.Color base = mindless.module.impl.theme.ThemeManager.surface();
        int seed = (base.getRGB() & 0xFFFFFF) * 31 + (ACCENT & 0xFFFFFF);
        if (seed == surfaceSeed) return;
        surfaceSeed = seed;

        int r = base.getRed(), g = base.getGreen(), b = base.getBlue();
        PANEL = argb(232, r, g, b);
        PANEL_ALT = shade(236, r, g, b, 1.18f);
        ROW = shade(224, r, g, b, 2.05f);
        ROW_HOVER = shade(236, r, g, b, 2.70f);
        CONTROL = shade(118, r, g, b, .55f);
        CONTROL_HOVER = shade(150, r, g, b, 2.40f);
        BORDER = withAlpha(mixColor(argb(255, 210, 210, 204), ACCENT, .55f), 62);
        DIVIDER = withAlpha(mixColor(argb(255, 210, 210, 204), ACCENT, .40f), 50);
        DROPDOWN_BG = PANEL_ALT;
        DROPDOWN_BORDER = BORDER;
        DROPDOWN_SELECTED = withAlpha(ACCENT, 80);
    }
private static int shade(int alpha, int r, int g, int b, float factor) {
        return argb(Math.min(255, alpha),
                Math.min(255, Math.round(r * factor) + 3),
                Math.min(255, Math.round(g * factor) + 3),
                Math.min(255, Math.round(b * factor) + 3));
    }

    private void updateAnimationClock() {
        long now = System.nanoTime();
        frameDelta = Math.max(1f / 240f, Math.min(.05f, (now - lastFrameNanos) / 1_000_000_000f));
        lastFrameNanos = now;
    }

    private float ease(float current, float target, float speed) {
        float factor = 1f - (float) Math.exp(-speed * frameDelta);
        float value = current + (target - current) * factor;
        return Math.abs(target - value) < .001f ? target : value;
    }

    private float animate(Map<Object, Float> values, Object key, float target, float speed) {
        Float current = values.get(key);
        if (current == null) {
            values.put(key, target);
            return target;
        }
        float value = ease(current, target, speed);
        values.put(key, value);
        return value;
    }

    private float animationValue(Map<Object, Float> values, Object key, float fallback) {
        Float value = values.get(key);
        return value == null ? fallback : value;
    }

    private Object sliderValueAnimationKey(SliderSetting slider) {
        Object key = sliderValueAnimationKeys.get(slider);
        if (key == null) {
            key = new Object();
            sliderValueAnimationKeys.put(slider, key);
        }
        return key;
    }

    private static int withAlpha(int color, int alpha) {
        return ((Math.max(0, Math.min(255, alpha)) & 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int mixColor(int from, int to, float progress) {
        float p = clamp01(progress);
        int a = (int) (((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * p);
        int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * p);
        int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * p);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * p);
        return argb(a, r, g, b);
    }
private static final class ModuleSnapshot {
        final Module module;
        final boolean enabled;
        final int bind;
        final Map<Setting, Object> values = new IdentityHashMap<Setting, Object>();

        ModuleSnapshot(Module module) {
            this.module = module;
            this.enabled = module.isEnabled();
            this.bind = module.getKeycode();
            for (Setting setting : module.getSettings()) {
                if (setting instanceof SliderSetting) values.put(setting, ((SliderSetting) setting).getInput());
                else if (setting instanceof ButtonSetting && !((ButtonSetting) setting).isMethodButton) values.put(setting, ((ButtonSetting) setting).isToggled());
                else if (setting instanceof ColorSetting) values.put(setting, ((ColorSetting) setting).getColor());
                else if (setting instanceof KeySetting) values.put(setting, ((KeySetting) setting).getKey());
                else if (setting instanceof TextSetting) values.put(setting, ((TextSetting) setting).getText());
            }
        }

        void restore() {
            module.setEnabled(enabled);
            module.setBind(bind);
            for (Map.Entry<Setting, Object> entry : values.entrySet()) {
                Setting setting = entry.getKey();
                Object value = entry.getValue();
                if (setting instanceof SliderSetting) ((SliderSetting) setting).setValueWithEvent((Double) value);
                else if (setting instanceof ButtonSetting) ((ButtonSetting) setting).setEnabled((Boolean) value);
                else if (setting instanceof ColorSetting) {
                    int color = (Integer) value;
                    ((ColorSetting) setting).setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, (color >>> 24) & 255);
                } else if (setting instanceof KeySetting) ((KeySetting) setting).setKey((Integer) value);
                else if (setting instanceof TextSetting) ((TextSetting) setting).setText((String) value);
            }
        }
    }

    private static final class Rect {
        float x1,y1,x2,y2;
        void set(float a,float b,float c,float d){x1=a;y1=b;x2=c;y2=d;}
        float w(){return Math.max(.001f,x2-x1);} float h(){return Math.max(.001f,y2-y1);}
        boolean contains(float x,float y){return x>=x1&&x<=x2&&y>=y1&&y<=y2;}
    }

    private static final class Suggestion {
        final String label;
        final String value;
        Suggestion(String label, String value) { this.label = label == null ? "" : label; this.value = value == null ? "" : value; }
    }
}
