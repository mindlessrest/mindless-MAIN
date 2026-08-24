package mindless.clickgui;

import mindless.Raven;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.clickgui.components.impl.ModuleComponent;
import mindless.module.Module;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.*;
import mindless.module.impl.client.CommandLine;
import mindless.module.impl.client.Gui;
import mindless.utility.CommandHandler;
import mindless.utility.BlockSearchIndex;
import mindless.utility.ItemSearchIndex;
import mindless.utility.PlayerRelationsManager;
import mindless.utility.PotionSearchIndex;
import mindless.utility.RenderUtils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
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

import java.awt.Color;
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

/**
 * Mindless' registry-driven three-panel ClickGUI. The legacy components remain
 * alive behind this screen because profiles, scripts and setting visibility use
 * them as their compatibility index; this class only replaces presentation.
 */
public final class ModernClickGui extends ClickGui {
    private static final String LOGO_RESOURCE = "/assets/mindless/textures/gui/logo.png";
    private static final String FALLBACK_FONT_REGULAR = "Sf-Regular";
    private static final String FALLBACK_FONT_BOLD = "Sf-Bold";
    private static final int LOGO_TEXTURE_WIDTH = 1536;
    private static final int LOGO_TEXTURE_HEIGHT = 1024;
    /** Cloud is hidden from the sidebar; flip to re-expose it. */
    private static final boolean SHOW_CLOUD_TAB = false;
    private static final float CATEGORY_ROW_HEIGHT = 21f;
    private static final float CATEGORY_ROW_STEP = 22f;
    private static final float MODULE_ROW_HEIGHT = 26f;
    private static final float MODULE_ROW_STEP = 30f;
    private static final float DROPDOWN_MIN_W = 78f;
    private static final float DROPDOWN_MAX_W = 156f;
    /** Gap kept between a setting's label and its dropdown. */
    private static final float DROPDOWN_LABEL_GAP = 10f;
    /**
     * Left edge of the control column, as a fraction of the row's width.
     *
     * Every control in the panel starts here -- slider tracks, dropdowns, segmented pickers,
     * keybind chips -- so they read as one column down the right of the panel rather than each
     * sitting wherever its own label happened to end.
     */
    private static final float CONTROL_COLUMN = .42f;
    /** Where a slider's number sits, as a fraction of the row's width. */
    private static final float VALUE_COLUMN = .30f;
    /** Vertical gap between setting rows. Shared by the draw and the hit-test walk. */
    private static final float SETTING_GAP = 4f;
    /** Most options a string setting may have before it is a dropdown rather than segments. */
    private static final int MAX_SEGMENTS = 3;
    /** Gap between the buttons of a segmented picker. */
    private static final float SEGMENT_GAP = 4f;
    /** Height of a control inside its row. */
    private static final float CONTROL_HEIGHT = 22f;

    private static int ACCENT = argb(255, 159, 143, 210);
    private static int ACCENT_SOFT = argb(42, 159, 143, 210);
    private static int ACCENT_FOREGROUND = argb(255, 25, 22, 31);
    // Compatibility aliases keep the setting renderer concise while the visual
    // palette itself is now lavender rather than the previous gold theme.
    private static int GOLD = ACCENT;
    private static int GOLD_SOFT = ACCENT_SOFT;
    // Surfaces are no longer constants: a theme may repaint the whole chrome, not just the
    // accent and text. DEFAULT_* keeps the stock look so surface theming can be switched off.
    private static final int DEFAULT_PANEL = argb(232, 13, 16, 18);
    private static final int DEFAULT_PANEL_ALT = argb(236, 15, 18, 20);
    private static final int DEFAULT_ROW = argb(224, 24, 27, 28);
    private static final int DEFAULT_ROW_HOVER = argb(236, 31, 34, 35);
    private static final int DEFAULT_CONTROL = argb(118, 7, 9, 10);
    private static final int DEFAULT_CONTROL_HOVER = argb(150, 28, 30, 31);
    private static final int DEFAULT_BORDER = argb(52, 210, 210, 204);
    private static final int DEFAULT_DIVIDER = argb(45, 210, 210, 204);

    private static int PANEL = DEFAULT_PANEL;
    private static int PANEL_ALT = DEFAULT_PANEL_ALT;
    private static int ROW = DEFAULT_ROW;
    private static int ROW_HOVER = DEFAULT_ROW_HOVER;
    private static int CONTROL = DEFAULT_CONTROL;
    private static int CONTROL_HOVER = DEFAULT_CONTROL_HOVER;
    private static int BORDER = DEFAULT_BORDER;
    private static int DIVIDER = DEFAULT_DIVIDER;
    // The dropdown gets its own three so it is not welded to the panel and the accent.
    private static int DROPDOWN_BG = DEFAULT_PANEL_ALT;
    private static int DROPDOWN_BORDER = DEFAULT_BORDER;
    private static int DROPDOWN_SELECTED = argb(80, 159, 143, 210);
    /** What the surface palette was last derived from; -1 means "currently stock". */
    private static int surfaceSeed = -1;
    private static int TEXT = argb(255, 235, 234, 230);
    private static int MUTED = argb(255, 157, 158, 156);
    private static int DIM = argb(255, 105, 108, 108);
    private static final int DANGER = argb(255, 219, 104, 100);
    private static final float TEXT_SCALE = .92f;

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
    /** Screen Y of the open dropdown's trigger row, used for overlay positioning. */
    private float dropdownAnchorY = 0f;
    /** Width of the open dropdown's control, so the overlay lines up with its trigger. */
    private float dropdownWidth = DROPDOWN_MAX_W;
    /** Scroll inside the open dropdown, for option lists taller than the panel. */
    private float dropdownScroll = 0f;
    private float dropdownScrollTarget = 0f;
    /** Visible height of the open dropdown, recomputed each frame; 0 when closed. */
    private float dropdownViewH = 0f;
    private float dropdownFullH = 0f;
    private ColorSetting openColor;
    private int draggingScrollbar;
    private float scrollbarDragOffset;
    private ModuleSnapshot moduleSnapshot;
    private int colorDrag;
    private String commandDraft = "";
    private boolean commandFocused;

    private float baseX, baseY, panelH, sideW, centerW, detailW, gap;
    private float centerX, detailX;
    private float settingsContentHeight;
    private float modulesContentHeight;
    /** 0 = detail panel fully collapsed, 1 = fully expanded. */
    private float detailPanelOpen = 0f;
    /** Tracks which module was last rendered so we can detect module changes for slide-in. */
    private Module lastRenderedModule = null;
    /** Per-module content reveal progress (0 = just switched, slides in to 1). */
    private float detailContentReveal = 0f;
    /** GUI open/close scale+fade progress. 0 = closed, 1 = fully open. */
    private float guiOpenProgress = 0f;
    /** Set to true when the GUI is in the process of closing so we animate out. */
    private boolean guiClosing = false;
    /** About/info dropdown state. */
    private boolean aboutOpen = false;
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
    private long lastFrameNanos = System.nanoTime();
    private float frameDelta = 1f / 60f;
    private float resetHover;
    private float saveHover;
    private boolean uiTextureLoadAttempted;
    private ResourceLocation logoTexture;
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
        // Snap animation state immediately on (re-)open so the panel doesn't
        // slide in from zero every time the GUI is toggled with a module active.
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
        updateAnimationClock();
        updateThemePalette();
        double renderScale = getActiveRenderScale();
        int mx = (int) Math.floor(mouseX / renderScale);
        int my = (int) Math.floor(mouseY / renderScale);

        // GUI open animation: ease to 1 on open; close nearly instantly (3-4 frames)
        // so the GUI doesn't feel "sticky" when dismissed.
        guiOpenProgress = ease(guiOpenProgress, guiClosing ? 0f : 1f, guiClosing ? 60f : 22f);
        if (guiClosing && guiOpenProgress < 0.01f) {
            mc.displayGuiScreen(null);
            return;
        }

        // Animate the detail panel open/close.
        detailPanelOpen = ease(detailPanelOpen, selectedModule != null ? 1f : 0f, 14f);

        // Detect module change and reset content reveal so it slides in fresh.
        if (lastRenderedModule != selectedModule) {
            lastRenderedModule = selectedModule;
            detailContentReveal = 0f;
        }
        if (selectedModule != null) {
            detailContentReveal = ease(detailContentReveal, 1f, 18f);
        }

        // About window animation
        aboutOpenProgress = ease(aboutOpenProgress, aboutOpen ? 1f : 0f, 22f);

        computeLayout();
        drawBackdrop(renderScale);
        drawDashboardShadows((float) renderScale);
        GlStateManager.pushMatrix();
        GlStateManager.scale(renderScale, renderScale, 1.0D);
        syncSelectedModule();
        updateSmoothScroll();

        // Screen-space rounded shaders cannot safely be matrix-scaled. Animate
        // detail width and content reveal without deforming panel geometry.
        drawPanels();
        drawSidebar(mx, my);
        drawModulePanel(mx, my);
        drawSettingsPanel(mx, my);
        drawCommandPalette(mx, my);
        drawAboutWindow(mx, my);
        updateDragging(mx, my);
        clampScrolls();

        GlStateManager.popMatrix();
    }

    @Override
    public void onGuiClosed() {
        guiClosing = false;
    }

    private void computeLayout() {
        gap = 9f;
        float totalW = Math.min(700f, width - 18f);
        panelH = Math.max(326f, Math.min(356f, height - 18f));
        sideW = Math.max(104f, totalW * .16f);
        float detailWFull = Math.max(205f, totalW * .305f);
        // detail panel width animates via smoothstep
        float t = detailPanelOpen;
        float openEased = t * t * (3f - 2f * t);
        detailW = detailWFull * openEased;
        // center panel always occupies remaining space; detail + gap only counted when visible
        float usedByDetail = detailW > 1f ? detailW + gap : 0f;
        centerW = totalW - sideW - gap - usedByDetail;
        baseX = Math.max(5f, (width - totalW) / 2f);
        baseY = Math.max(6f, (height - panelH) / 2f);
        centerX = baseX + sideW + gap;
        detailX = centerX + centerW + gap;
    }

    private void drawPanels() {
        panelSurface(baseX, baseY, baseX + sideW, baseY + panelH, PANEL);
        panelSurface(centerX, baseY, centerX + centerW, baseY + panelH, PANEL);
        if (detailW > 2f) {
            panelSurface(detailX, baseY, detailX + detailW, baseY + panelH, PANEL_ALT);
        }
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

        // Blur always runs at full strength — scaling radius toward 0 breaks Kawase.
        // Fade is handled entirely by the dark overlay on top.
        float configured = Gui.backgroundBlur == null ? 0f : (float) Gui.backgroundBlur.getInput();
        float blurRadius = 1.65f + configured * .012f;
        // Build one live mask from the panels' current animated bounds.  The old
        // full-screen mask made an expanding detail panel move over a stationary
        // blurred image, which looked like a frozen copy of the world.  Keeping
        // this as one shared pass avoids multiplying the shader cost per panel.
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

        // Dark tint fades with progress
        int overlayAlpha = (int)(128 * eased);
        net.minecraft.client.gui.Gui.drawRect(0, 0, backdropWidth, backdropHeight, argb(overlayAlpha, 2, 4, 5));
    }

    private void drawSidebar(int mx, int my) {
        float headerH = 48f;
        ensureUiTextures();
        if (logoTexture != null) {
            drawTextureRegion(logoTexture, baseX + 13, baseY + 12, 30, 20, 0, 0,
                    LOGO_TEXTURE_WIDTH, LOGO_TEXTURE_HEIGHT, LOGO_TEXTURE_WIDTH, LOGO_TEXTURE_HEIGHT,
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

    /** Categories drawn below the divider in a fixed order rather than in enum order. */
    private static boolean isPinnedCategory(Module.category category) {
        return category == Module.category.profiles
                || category == Module.category.scripts
                || category == Module.category.theme
                || category == Module.category.cloud;
    }

    private float drawCategory(Module.category category, float y, int mx, int my) {
        float h = CATEGORY_ROW_HEIGHT;
        boolean active = category == selectedCategory;
        boolean hover = inside(mx, my, baseX + 7, y, baseX + sideW - 7, y + h);
        float hp = animate(hoverAnimation, category, hover ? 1f : 0f, 16f);
        float sp = animate(selectedAnimation, category, active ? 1f : 0f, 18f);
        // Background pill
        float surface = Math.max(hp * .55f, sp);
        if (surface > .01f) rounded(baseX + 7, y, baseX + sideW - 7, y + h, 5f,
                withAlpha(ACCENT, (int) (surface * 44)));
        // Left accent bar
        if (sp > .01f) {
            float barTop = y + 3 + (1f - sp) * 4f;
            float barBot = y + h - 3 - (1f - sp) * 4f;
            rounded(baseX + 7, barTop, baseX + 9.5f, barBot, 1.25f, withAlpha(ACCENT, (int) (255 * sp)));
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

        // A single even rule. There used to be a short bright accent segment over the first
        // 33px of it, which just read as the line being thicker on the left than the right.
        line(centerX + 17, baseY + 55, centerX + centerW - 17, baseY + 55, withAlpha(DIVIDER, 46));
        float top = baseY + 61f;
        float bottom = baseY + panelH - 12f;
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

    // --- Theme Panel ---
    private static final int THEME_COLUMNS = 3;
    private static final float THEME_GAP = 9f;
    private static final float THEME_SIDE_PAD = 18f;
    private static final float THEME_CARD_H = 78f;
    /** Height of the full-bleed swatch across the top of the card. */
    private static final float THEME_SWATCH_H = 47f;
    /** Same nominal radius the rest of the chrome uses, so it tracks the Rounding setting. */
    private static final float THEME_CARD_RADIUS = 10f;

    private float themeCardWidth() {
        float usable = centerW - THEME_SIDE_PAD * 2f - THEME_GAP * (THEME_COLUMNS - 1);
        return Math.max(48f, usable / THEME_COLUMNS);
    }

    private float themeCardX(int column) {
        return centerX + THEME_SIDE_PAD + column * (themeCardWidth() + THEME_GAP);
    }

    /**
     * The theme category is a picker, not a module list, so it gets a grid of swatch cards:
     * a colour panel on top and a dark name bar beneath. Left click applies, right click
     * applies and opens the Theme Manager's settings.
     */
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

        // A single even rule. There used to be a short bright accent segment over the first
        // 33px of it, which just read as the line being thicker on the left than the right.
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

        // One surface: the card supplies its own background and all four corners, and the
        // swatch sits on it full-bleed. No second panel colour underneath the label, which is
        // what made it read as two stacked overlays.
        int cardColor = mixColor(ROW, ROW_HOVER, Math.max(hp * .85f, sp * .6f));
        if (sp > .01f || hp > .01f) {
            outline(x1, y, x2, y2, THEME_CARD_RADIUS,
                    withAlpha(ACCENT, (int) (215 * sp + 75 * hp * (1f - sp))));
        }
        rounded(x1, y, x2, y2, THEME_CARD_RADIUS, cardColor);

        // Corner radius of the swatch, clamped against the swatch's own height.
        float r = radius(THEME_CARD_RADIUS, w, THEME_SWATCH_H);

        // Drawn by the rounded-rect shader, which evaluates the corner as a signed distance
        // field and interpolates the colour per fragment.
        //
        // The previous version used RenderUtils.drawRoundedGradientRect, which builds a
        // GL_POLYGON and lets OpenGL Gouraud-shade it. GL_POLYGON is triangulated as a fan from
        // its first vertex, so the colour is interpolated across triangles rather than across
        // the shape -- that is the diagonal streak across each swatch, and it got worse as the
        // radius grew because the fan got wider. A fragment shader has no triangulation to show.
        RoundedUtils.drawGradientRound(x1, y, x2 - x1, splitY - y, r, to, from, to, from);

        // The helper rounds all four corners; this edge is interior, against the label area, so
        // the lower two are squared off. The seam colour is sampled at exactly the same point on
        // the ramp, so the join is continuous rather than a step.
        if (r > .5f) {
            float h = Math.max(1f, splitY - y);
            RenderUtils.drawVerticalGradientRect(x1, splitY - r, x2, splitY,
                    mixColor(from, to, (h - r) / h), to);
        }
        // drawGradientRound leaves the alpha limit and blend state it set up, so reset before
        // any text goes down.
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

    /** Returns true when the click was consumed by the theme picker. */
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
                    // Right click: jump to the manager's settings to tune this theme.
                    openModule(mindless.module.ModuleManager.themeManager);
                }
                return true;
            }
        }
        return true; // clicks in the picker never fall through to module handling
    }


    private void drawModuleRow(Module module, float y, int mx, int my) {
        float x1 = centerX + 14, x2 = centerX + centerW - 14;
        boolean selected = module == selectedModule;
        boolean hover = inside(mx, my, x1, y, x2, y + MODULE_ROW_HEIGHT);
        float hp = animate(hoverAnimation, module, hover ? 1f : 0f, 17f);
        float sp = animate(selectedAnimation, module, selected ? 1f : 0f, 19f);
        int rowColor = mixColor(ROW, ROW_HOVER, hp);
        rowColor = mixColor(rowColor, withAlpha(ACCENT, 55), sp);
        rounded(x1, y, x2, y + MODULE_ROW_HEIGHT, 5f, rowColor);
        // Left accent bar — slides in from top when selected
        if (sp > .01f) {
            float barTop = y + 5 + (1f - sp) * 4f;
            float barBot = y + MODULE_ROW_HEIGHT - 5 - (1f - sp) * 4f;
            rounded(x1, barTop, x1 + 2.5f, barBot, 1.25f, withAlpha(ACCENT, (int) (255 * sp)));
        }
        float toggleX = x2 - 88;
        float availableTextWidth = Math.max(42f, toggleX - x1 - 18f);
        drawText(trim(module.getName(), availableTextWidth, .73f, true),
                x1 + 10, y + 4.5f, mixColor(MUTED, TEXT, Math.max(hp * .5f, sp)), .73f, sp > .5f);
        // Description uses a 9px font rendered at scale 1.0 — no GL downscaling so
        // glyphs stay crisp. The 13px setting renderer scaled to 0.60 was bilinearly
        // blurred; this is the correct approach for small readable text.
        drawSmallText(trimSmall(moduleDescription(module), availableTextWidth),
                x1 + 10, y + 16f, mixColor(argb(255, 132, 134, 133), MUTED,
                        Math.max(hp * .42f, sp * .62f)));

        if (module instanceof ProfileModule) {
            boolean active = module.isEnabled();
            drawCenteredV(active ? "Active" : "Load", toggleX - 4, toggleX + 34, y, y + MODULE_ROW_HEIGHT,
                    active ? GOLD : MUTED, .62f, active);
        } else if (module instanceof Manager) {
            drawCenteredV("Create", toggleX - 4, toggleX + 34, y, y + MODULE_ROW_HEIGHT, MUTED, .6f, false);
        } else {
            drawToggle(toggleX, y + 5, module.isEnabled(), module);
        }
        float bindX = x2 - 52;
        if (!(module instanceof Manager)) {
            String bindText = binding == module ? "..." : module.getKeycode() == 0 ? "None" : keyName(module.getKeycode());
            float bindScale = textWidth(bindText, .61f, false) > 34f ? .52f : .61f;
            drawCenteredV(bindText, bindX, x2 - 14, y, y + MODULE_ROW_HEIGHT,
                    binding == module ? GOLD : DIM, bindScale, false);
        }
        drawTextVCentered(">", x2 - 8, y, y + MODULE_ROW_HEIGHT, selected ? GOLD : withAlpha(DIM, (int)(80 + 175 * hp)), .72f, false);
    }

    private void drawSettingsPanel(int mx, int my) {
        // Don't draw anything when panel is fully collapsed or no module selected.
        if (detailW < 4f || selectedModule == null) return;

        // Content alpha and horizontal slide driven by detailContentReveal.
        // Slide: content starts 18px to the right and moves to 0.
        float reveal = detailContentReveal;
        float contentAlpha = reveal;
        float slideOffset = (1f - reveal) * 18f;

        // Clip everything to the visible panel width so content doesn't bleed
        // during the expand animation.
        scissor(detailX, baseY, detailX + detailW, baseY + panelH, true);

        // Header — fades in with content
        int headerAlpha = (int) (255 * contentAlpha);
        float headerCenterY = baseY + 28.5f;
        GL11.glPushMatrix();
        GL11.glTranslatef(slideOffset, 0f, 0f);
        // The category glyph sits in a ring, which gives the header something to be built
        // around instead of a mark floating next to the name.
        circle(detailX + 25, headerCenterY, 12.5f, withAlpha(GOLD_SOFT, (int) (headerAlpha * .55f)));
        circleOutline(detailX + 25, headerCenterY, 12.5f, withAlpha(GOLD, (int) (headerAlpha * .8f)));
        drawCategoryIcon(selectedModule.moduleCategory(), detailX + 25, headerCenterY,
                withAlpha(GOLD, headerAlpha));
        drawTwoLineTextVCentered(
                trim(selectedModule.getName(), detailW - 82, .98f, true), "Settings",
                detailX + 42, baseY + 10, baseY + 47,
                withAlpha(TEXT, headerAlpha), withAlpha(MUTED, headerAlpha),
                .98f, .67f, 2.5f, 1f);
        // Close X button
        segments(withAlpha(DIM, headerAlpha),
                detailX + detailW - 23, headerCenterY - 4, detailX + detailW - 15, headerCenterY + 4,
                detailX + detailW - 15, headerCenterY - 4, detailX + detailW - 23, headerCenterY + 4);
        line(detailX + 12, baseY + 51, detailX + detailW - 12, baseY + 51,
                withAlpha(DIVIDER, headerAlpha));

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
            // Track where the open dropdown's row sits in screen space
            if (setting == openDropdown) dropdownAnchorY = y;
            y += h + SETTING_GAP;
        }
        settingsContentHeight = y - (top + settingScroll);
        scissor(detailX + 8, top, detailX + detailW - 8, bottom, true);
        drawScrollbar(detailX + detailW - 7, top, bottom, settingScroll, settingsContentHeight);
        scissor(0, 0, 0, 0, false);

        GL11.glPopMatrix();
        scissor(0, 0, 0, 0, false);

        // Draw dropdown overlay on top of everything (no scissor, no scroll offset)
        drawDropdownOverlay(mx, my);
    }

    private float settingsLeft() { return detailX + 15; }

    private float settingsRight() { return detailX + detailW - 15; }

    /** Left edge of the shared control column. */
    private float controlLeft() {
        float left = settingsLeft();
        return left + (settingsRight() - left) * CONTROL_COLUMN;
    }

    /**
     * Right edge of a slider's track, pulled in by the radius of its thumb.
     *
     * The thumb is drawn centred on the track's end, so a track running to the full width would
     * put half a thumb past where every other control stops.
     */
    private float sliderTrackRight() { return settingsRight() - 4f; }

    /** Left edge of a numeric slider's value, which sits between the label and the track. */
    private float sliderValueLeft() {
        float left = settingsLeft();
        return left + (settingsRight() - left) * VALUE_COLUMN;
    }

    /**
     * Where the buttons of a segmented picker go, as {left edge, button width}, or null when this
     * setting should stay a dropdown.
     *
     * A short handful of short options reads better laid out than hidden behind a menu -- you can
     * see what the alternatives are and switch with one click instead of two. Anything longer
     * than that does not fit across the column, so it keeps the dropdown.
     */
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
        float scale = textWidth(name, .75f, false) > maxWidth ? .66f : .75f;
        drawTextVCentered(trim(name, maxWidth, scale, false), x, y1, y2, color, scale, false);
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

    /**
     * The same colour with its alpha forced to full.
     *
     * outline() works by laying a slightly larger rect of the border colour down first and
     * letting the fill cover its middle, so only a one pixel rim survives. That only holds while
     * the fill is opaque. Every control surface here is defined translucent so it can sit over
     * the panel, and over an outline that turns the whole control into a wash of the border
     * colour -- which for a selected control is the accent, so it comes out a solid accent block
     * with its label invisible on top of it.
     */
    private static int opaque(int color) { return color | 0xFF000000; }

    /** Scales the alpha channel of a packed ARGB color by [0,1]. */
    private int fa(int color, float alpha) {
        int a = (int) (((color >>> 24) & 255) * alpha);
        return withAlpha(color, a);
    }

    /**
     * Draws the open dropdown options as a floating overlay, on top of the settings
     * panel and not affected by the scroll scissor. Animates via a slide-down
     * scissor reveal (same feel as the detail panel expand).
     */
    private void drawDropdownOverlay(int mx, int my) {
        if (openDropdown == null || openDropdown.getOptions() == null) {
            // Still animate closed if needed
            animationValue(dropdownAnimation, new Object(), 0f);
            return;
        }
        float open = animationValue(dropdownAnimation, openDropdown, 0f);
        if (open < 0.01f) return;

        float x2 = detailX + detailW - 15;
        // One source of truth, shared with the click and hover hit-boxes.
        float dw = overlayWidth();
        float dx1 = x2 - dw, dx2 = x2;
        float rowTop = dropdownAnchorY + 30f; // just below the control
        int n = openDropdown.getOptions().length;
        float fullH = n * 21f + 4f;

        // A long option list used to simply run off the bottom of the panel with no way to
        // reach the hidden entries. Cap the box at the panel and scroll inside it instead.
        float panelBottom = baseY + panelH - 4f;
        float viewH = Math.max(23f, Math.min(fullH, panelBottom - rowTop));
        dropdownFullH = fullH;
        dropdownViewH = viewH;
        clampDropdownScroll();
        dropdownScroll += (dropdownScrollTarget - dropdownScroll) * .32f;
        if (Math.abs(dropdownScrollTarget - dropdownScroll) < .08f) dropdownScroll = dropdownScrollTarget;

        // Slide-down: reveal from top using scissor height = open * viewH
        float clipTop = rowTop;
        float clipBottom = Math.min(panelBottom, rowTop + open * viewH);
        if (clipBottom <= clipTop) return;

        scissor(detailX + 4f, clipTop, detailX + detailW - 4f, clipBottom, true);

        // Shadow behind dropdown
        RoundedUtils.drawRoundShadow(dx1 - 1, rowTop, dx2 - dx1 + 2, viewH, 5f, 6f, argb((int)(80 * open), 0, 0, 0));

        // Keep dropdown surface aligned with rest of the themed ClickGUI palette.
        int bgAlpha = (int)(255 * open);
        net.minecraft.client.gui.Gui.drawRect((int) dx1, (int) rowTop, (int) dx2, (int)(rowTop + viewH),
                fa(DROPDOWN_BG, open));
        outline(dx1, rowTop, dx2, rowTop + viewH, 5f, fa(DROPDOWN_BORDER, open));
        resetTextRenderState();

        // Options
        float oy = rowTop + 2f + dropdownScroll;
        for (int i = 0; i < n; i++) {
            if (oy + 19 < rowTop || oy > rowTop + viewH) { oy += 21f; continue; }
            boolean sel = (int) openDropdown.getInput() == i;
            boolean hov = inside(mx, my, dx1 + 2, Math.max(oy, rowTop), dx2 - 2, Math.min(oy + 19, rowTop + viewH));
            Object rowKey = getDropdownRowKey(openDropdown, i);
            float rowHp = animate(hoverAnimation, rowKey, hov ? 1f : 0f, 16f);
            // Selected row: solid accent bg; hover row: subtle tint
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

        // Scroll indicator, only while the list actually overflows
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

    /** Clears everything cached about an open dropdown's box. */
    private void closeDropdownState() {
        dropdownScroll = dropdownScrollTarget = 0f;
        dropdownViewH = dropdownFullH = 0f;
    }

    /** Keeps the dropdown scroll inside its content, and pins it at 0 when nothing overflows. */
    private void clampDropdownScroll() {
        float min = Math.min(0f, dropdownViewH - dropdownFullH);
        dropdownScrollTarget = Math.max(min, Math.min(0f, dropdownScrollTarget));
        dropdownScroll = Math.max(min, Math.min(0f, dropdownScroll));
    }

    /** True when the pointer is over the open dropdown's box. */
    private boolean overDropdown(int mx, int my) {
        if (openDropdown == null || openDropdown.getOptions() == null) return false;
        float x2 = detailX + detailW - 15;
        float top = dropdownAnchorY + 30f;
        return inside(mx, my, x2 - overlayWidth(), top, x2, top + dropdownViewH);
    }

    /** Overlay width: the trigger's width, widened to fit the longest option, capped to the panel. */
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
            // A quiet heading with the rule above it, rather than an accent-coloured line of
            // text underlined in the accent again. It is a label for what follows, not a thing
            // to look at, and reading it as one makes the settings under it the loudest part of
            // the panel. The first heading skips the rule, since the panel header drew one.
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
            drawTextVCentered(trim(button.getName(), detailW - 80, .74f, false), x1 + 2, y, y + h, fa(TEXT, alpha), .74f, false);
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

                // A short set of short options is laid out rather than hidden behind a menu: you
                // can see the alternatives and switch in one click instead of two.
                float[] segments = segmentLayout(slider);
                if (segments != null) {
                    drawSettingLabel(label, labelLeft, y, y + h,
                            segments[0] - labelLeft - DROPDOWN_LABEL_GAP, fa(TEXT, alpha));
                    drawSegments(slider, segments, y, h, mx, my, alpha);
                    return;
                }

                // Width follows the label rather than being a fixed 128px, so a long setting
                // name no longer runs underneath the control. It reaches for the shared control
                // column first and only gives ground back when the name needs the room.
                float available = x2 - labelLeft - textWidth(label, .75f, false) - DROPDOWN_LABEL_GAP;
                float dropW = Math.max(DROPDOWN_MIN_W, Math.min(x2 - controlLeft(), available));
                float dx1 = x2 - dropW, dx2 = x2;
                if (openDropdown == slider) dropdownWidth = dropW;

                drawSettingLabel(label, labelLeft, y, y + h,
                        dx1 - labelLeft - DROPDOWN_LABEL_GAP, fa(TEXT, alpha));

                float open = animate(dropdownAnimation, slider, openDropdown == slider ? 1f : 0f, 20f);
                boolean over = inside(mx, my, dx1, y + 3, dx2, y + 27);
                float hp = animate(hoverAnimation, slider, over ? 1f : 0f, 16f);
                // outline() paints a filled rect one pixel larger and is meant to sit BEHIND the
                // fill. It was being drawn after it, so the control became a solid block of the
                // border colour -- which is the accent while open, hiding the selected option.
                outline(dx1, y + 3, dx2, y + 27, 4f, fa(mixColor(BORDER, GOLD, open), alpha));
                rounded(dx1, y + 3, dx2, y + 27, 4f, fa(opaque(
                        mixColor(CONTROL, CONTROL_HOVER, Math.max(hp * .65f, open * .7f))), alpha));
                resetTextRenderState();
                drawTextVCentered(trim(sliderValue(slider), dropW - 26f, .68f, false), dx1 + 8, y + 3, y + 27,
                        fa(mixColor(MUTED, TEXT, Math.max(open, hp * .6f)), alpha), .68f, false);
                drawChevron(dx2 - 10, y + 15, open, fa(mixColor(MUTED, GOLD, open), alpha));
                // Options drawn as floating overlay in drawDropdownOverlay()
                return;
            }
            // Label, value and track on one row: name on the left, the number in its own
            // column, the track filling the control column to the right edge. The track used to
            // sit on a second line under the label, which made a slider twice the height of
            // every other row and broke the rhythm of the panel.
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
            drawTextVCentered(editingValue ? displayedValue : trim(displayedValue, valueRoom, valueScale, false),
                    valueX, y, y + h, fa(TEXT, alpha), valueScale, false);
            if (editingValue && blink()) {
                float caretX = valueX + textWidth(sliderEditDraft.substring(0, sliderEditCaret), valueScale, false);
                rounded(caretX, y + 8, caretX + .8f, y + h - 8, .4f, fa(ACCENT, alpha));
            }
            float bx1 = trackLeft, bx2 = sliderTrackRight(), by = y + h / 2f - 1.5f;
            float hp = animate(controlAnimation, slider, inside(mx, my, bx1 - 5, y + 3, bx2 + 3, y + h - 3) ? 1f : 0f, 18f);
            // Track bg. Opaque, and light enough to read as a groove the fill runs along --
            // at 120 alpha over a near-black panel the unfilled part was all but invisible, so a
            // slider near its maximum looked like a plain bar with nothing left to give.
            rounded(bx1, by, bx2, by + 3, 1.5f,
                    fa(opaque(mixColor(argb(255, 47, 50, 51), argb(255, 68, 71, 72), hp)), alpha));
            // Animate fill progress toward real value
            float targetProgress = (float) ((slider.getInput() - slider.getMin()) / Math.max(.00001, slider.getMax() - slider.getMin()));
            targetProgress = clamp01(targetProgress);
            Float animProg = sliderProgressAnimation.get(slider);
            if (animProg == null) animProg = targetProgress;
            animProg = animProg + (targetProgress - animProg) * (1f - (float) Math.exp(-28f * frameDelta));
            if (Math.abs(targetProgress - animProg) < 0.0008f) animProg = targetProgress;
            sliderProgressAnimation.put(slider, animProg);
            float px = bx1 + (bx2 - bx1) * animProg;
            // Fill
            rounded(bx1, by, px, by + 3, 1.5f, fa(mixColor(GOLD, TEXT, hp * .18f), alpha));
            // Thumb — grows slightly on hover, follows animated position
            float thumbR = 4.1f + hp * .7f;
            circle(px, by + 1.5f, thumbR, fa(mixColor(GOLD, TEXT, hp * .24f), alpha));
            if (draggingSlider == slider) sliderRect.set(bx1, y, bx2, y + h);
        } else if (setting instanceof KeySetting) {
            KeySetting key = (KeySetting) setting;
            String value = binding == key ? "Press a key" : keyName(key.getKey());
            // A chip sized to the key rather than a fixed 76px slab. A bind is one or two
            // characters nearly always, and a box five times wider than its contents reads as an
            // empty field waiting to be filled in.
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

    /**
     * Left edge of a keybind's chip: wide enough for what it says, never past the control column.
     */
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
            // outline() lays a slightly larger rect down first and the fill covers its middle,
            // which is what leaves a one pixel border. Drawing it after would bury the fill, and
            // a fill that is not opaque lets it through -- see opaque().
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
        for (int sx = 0; sx < 12; sx++) for (int sy = 0; sy < 8; sy++) {
            float sat = sx / 11f, bri = 1f - sy / 7f;
            int rgb = Color.HSBtoRGB(color.getHue() / 360f, sat, bri) | 0xFF000000;
            float ax = colorSB.x1 + colorSB.w() * sx / 12f, ay = colorSB.y1 + colorSB.h() * sy / 8f;
            net.minecraft.client.gui.Gui.drawRect((int) ax, (int) ay, (int) Math.ceil(ax + colorSB.w() / 12f), (int) Math.ceil(ay + colorSB.h() / 8f), rgb);
        }
        colorHue.set(colorSB.x2 + 5, py, colorSB.x2 + 12, py + 48);
        for (int i = 0; i < 24; i++) {
            int rgb = Color.HSBtoRGB(i / 24f, 1f, 1f) | 0xFF000000;
            net.minecraft.client.gui.Gui.drawRect((int) colorHue.x1, (int) (py + i * 2), (int) colorHue.x2, (int) (py + i * 2 + 2), rgb);
        }
        circle(colorSB.x1 + color.getSaturation() * colorSB.w(), colorSB.y1 + (1f - color.getBrightness()) * colorSB.h(), 2.5f, TEXT);
        net.minecraft.client.gui.Gui.drawRect((int) colorHue.x1 - 1, (int) (colorHue.y1 + color.getHue() / 360f * colorHue.h()) - 1, (int) colorHue.x2 + 1, (int) (colorHue.y1 + color.getHue() / 360f * colorHue.h()) + 1, TEXT);
        if (color.hasAlpha()) {
            colorAlpha.set(pickerX, py + 55, pickerX + pickerW, py + 61);
            for (int i = 0; i < 16; i++) {
                int a = (int) (255f * i / 15f);
                net.minecraft.client.gui.Gui.drawRect((int) (colorAlpha.x1 + colorAlpha.w() * i / 16f), (int) colorAlpha.y1, (int) Math.ceil(colorAlpha.x1 + colorAlpha.w() * (i + 1) / 16f), (int) colorAlpha.y2, (a << 24) | color.getRGB());
            }
        }
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
        String shown = value.isEmpty() && activeText != setting ? placeholder : value + (activeText == setting && blink() ? "|" : "");
        drawTextVCentered(trim(shown, x2 - x1 - 28, .69f, false), x1 + 14, iy, y + h - 7,
                fa(value.isEmpty() ? DIM : TEXT, alpha), .69f, false);
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
        String shown = activeList == setting ? listDraft + (blink() ? "|" : "") : listPlaceholder(setting);
        drawTextVCentered(trim(shown, x2 - x1 - 66, .66f, false), x1 + 14, iy, iy + 21,
                fa(activeList == setting && !listDraft.isEmpty() ? TEXT : DIM, alpha), .66f, false);
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
        for (String entry : listEntries(setting)) {
            rounded(x1 + 8, ey, x2 - 8, ey + 20, 4f, fa(ROW, alpha));
            drawTextVCentered(trim(entry, x2 - x1 - 70, .65f, false), x1 + 14, ey, ey + 20, fa(MUTED, alpha), .65f, false);
            if (setting instanceof InventoryItemListSetting) {
                Integer slot = ((InventoryItemListSetting) setting).getAssignedSlot(entry);
                drawTextVCentered("<", x2 - 91, ey, ey + 20, fa(MUTED, alpha), .62f, true);
                drawTextVCentered(">", x2 - 79, ey, ey + 20, fa(MUTED, alpha), .62f, true);
                drawTextVCentered("Slot " + slot, x2 - 53, ey, ey + 20, fa(GOLD, alpha), .62f, false);
            }
            drawTextVCentered("x", x2 - 18, ey, ey + 20, fa(DANGER, alpha), .65f, true);
            ey += 23;
        }
    }

    private void drawCommandPalette(int mx, int my) {
        if (!CommandLine.opened) return;
        float w = Math.min(390, width - 30), x = (width - w) / 2f, y = baseY + panelH - 47;
        panelSurface(x, y, x + w, y + 35, argb(245, 10, 12, 14));
        drawTextVCentered(">", x + 11, y, y + 35, GOLD, .85f, true);
        String shown = commandDraft + (commandFocused && blink() ? "|" : "");
        drawTextVCentered(trim(shown.isEmpty() ? "Type a command..." : shown, w - 48, .73f, false), x + 26, y, y + 35,
                shown.isEmpty() ? DIM : TEXT, .73f, false);
    }

    private void drawAboutWindow(int mx, int my) {
        if (aboutOpenProgress < 0.01f) return;

        float t = aboutOpenProgress;
        float eased = t * t * (3f - 2f * t);

        float ax = baseX + 7f;
        float aw = sideW - 14f;
        float ay = baseY + 53f; // sits just below the divider line

        boolean isInjection = mindless.runtime.LunarEventBridge.isDirectLunar();
        String[] keys = { "Version", "Build", "Mode" };
        String[] vals = {
            mindless.utility.BuildInfo.getVersion(),
            mindless.utility.BuildInfo.getBuild(),
            isInjection ? "Injection" : "Mod"
        };

        float rowH = 26f;
        float fullH = keys.length * rowH + 8f;
        float revealH = eased * fullH;

        // Clip slide-down
        scissor(ax, ay, ax + aw, ay + revealH, true);

        int aa = (int)(255 * eased);

        // Panel — slightly lighter than the sidebar so it pops
        rounded(ax, ay, ax + aw, ay + fullH, 5f, withAlpha(argb(255, 26, 29, 32), aa));

        float ry = ay + 4f;
        for (int i = 0; i < keys.length; i++) {
            drawTextVCentered(keys[i], ax + 10f, ry, ry + rowH, withAlpha(MUTED, aa), .68f, false);
            drawTextVCentered(vals[i], ax + aw - 10f - textWidth(vals[i], .68f, true),
                    ry, ry + rowH, withAlpha(TEXT, aa), .68f, true);
            if (i < keys.length - 1) {
                line(ax + 8f, ry + rowH, ax + aw - 8f, ry + rowH, withAlpha(DIVIDER, aa));
            }
            ry += rowH;
        }

        scissor(0, 0, 0, 0, false);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // GuiScreen converts click events with this screen's logical width/height.
        // Unlike drawScreen's coordinates these are already in our GUI space.
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

        if (CommandLine.opened) {
            commandFocused = true;
            return;
        }

        // About dropdown interactions
        if (aboutOpen && aboutOpenProgress > 0.05f) {
            float aw = sideW - 14f, fullH = 72f;
            float ax = baseX + 7f, ay = baseY + 48f;
            // Clicks inside close it
            if (inside(mx, my, ax, ay, ax + aw, ay + fullH)) return;
        }

        // Mindless logo/title area (no action)
        if (inside(mx, my, baseX + 10, baseY + 8, baseX + sideW - 10, baseY + 40)) {
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
        // Theme picker click handling
        if (clickThemePanel(mx, my, mouseButton)) return;


        float top = baseY + 61f;
        float y = top + moduleScroll;
        for (Module module : filteredModules()) {
            if (inside(mx, my, centerX + 14, y, centerX + centerW - 14, y + MODULE_ROW_HEIGHT)) {
                float x2 = centerX + centerW - 14;
                if (module instanceof ProfileModule) {
                    if (mouseButton == 1) {
                        // Right-click = open settings panel (save / delete / upload)
                        openModule(module);
                    } else if (mx >= x2 - 55 && mx <= x2 - 16) {
                        binding = module;
                    } else if (mx >= x2 - 14) {
                        // > arrow = open settings panel (rename / delete)
                        openModule(module);
                    } else {
                        activateProfile((ProfileModule) module);
                    }
                } else if (module instanceof Manager) {
                    openModule(module);
                } else if (mx >= x2 - 55 && mx <= x2 - 16) {
                    // bind zone — both buttons
                    binding = module;
                } else if (mouseButton == 1) {
                    // right click: open settings, or close them if already open
                    if (module == selectedModule) selectModule(null);
                    else openModule(module);
                } else {
                    // left click → toggle, also open settings if clicking the > arrow
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
            // Dropdown overlay eats clicks first
            if (openDropdown != null && openDropdown.getOptions() != null) {
                float x2 = detailX + detailW - 15;
                float dx1 = x2 - overlayWidth(), dx2 = x2;
                float overlayTop = dropdownAnchorY + 30f;
                // Hit-test the visible box, not the full list: rows clipped off the bottom
                // are not on screen and must not swallow clicks meant for the panel.
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
                // Click outside overlay → close it and consume the click
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
                // toggle open/close — actual option selection handled in mouseClicked overlay block
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
            activeList = null;
        } else if (isList(setting)) {
            float iy = y + 22;
            if (inside(mx, my, x1 + 8, iy, x2 - 34, iy + 21)) { activeList = setting; listDraft = ""; clearSuggestions(); return; }
            if (inside(mx, my, x2 - 29, iy, x2 - 8, iy + 21)) { addListEntry(setting); return; }
            float ey = iy + 27;
            for (Suggestion suggestion : suggestionsFor(setting)) {
                if (inside(mx, my, x1 + 8, ey, x2 - 8, ey + 19)) {
                    addSuggestedEntry(setting, suggestion.value);
                    return;
                }
                ey += 21;
            }
            for (String entry : new ArrayList<String>(listEntries(setting))) {
                if (inside(mx, my, x2 - 28, ey, x2 - 8, ey + 20)) { removeListEntry(setting, entry); return; }
                if (setting instanceof InventoryItemListSetting && inside(mx, my, x2 - 66, ey, x2 - 29, ey + 20)) {
                    InventoryItemListSetting inventory = (InventoryItemListSetting) setting;
                    int slot = inventory.getAssignedSlot(entry);
                    inventory.setAssignedSlot(entry, slot >= 9 ? 1 : slot + 1);
                    return;
                }
                if (setting instanceof InventoryItemListSetting && inside(mx, my, x2 - 98, ey, x2 - 84, ey + 20)) {
                    InventoryItemListSetting inventory = (InventoryItemListSetting) setting;
                    inventory.moveItem(entry, Math.max(0, inventory.getItems().indexOf(entry) - 1));
                    return;
                }
                if (setting instanceof InventoryItemListSetting && inside(mx, my, x2 - 84, ey, x2 - 68, ey + 20)) {
                    InventoryItemListSetting inventory = (InventoryItemListSetting) setting;
                    inventory.moveItem(entry, Math.min(inventory.getItems().size() - 1, inventory.getItems().indexOf(entry) + 1));
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
        // Capture the event delta first. ClickGui's legacy wheel handler calls
        // Mouse.getDWheel(), which consumes it before this dashboard can scroll.
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) return;
        computeLayout();
        int mx = (int) Math.floor(Mouse.getEventX() * width / (double) mc.displayWidth);
        int my = (int) Math.floor(height - Mouse.getEventY() * height / (double) mc.displayHeight - 1);
        float speed = Gui.scrollSpeed == null ? 28f : (float) Math.max(8d, Math.min(90d, Gui.scrollSpeed.getInput()));
        float amount = wheel > 0 ? speed : -speed;
        // An open dropdown takes the wheel first, otherwise the panel behind it scrolls out
        // from under the options and the hidden entries stay unreachable.
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
        if (editingSliderValue != null) {
            editSliderValue(typedChar, keyCode);
            return;
        }
        if (CommandLine.opened) {
            if (keyCode == Keyboard.KEY_ESCAPE) { CommandLine.opened = false; CommandLine.closed = false; commandFocused = false; return; }
            if (keyCode == Keyboard.KEY_RETURN && !commandDraft.trim().isEmpty()) { CommandHandler.runCommand(commandDraft); commandDraft = ""; return; }
            commandDraft = editString(commandDraft, typedChar, keyCode, 256);
            return;
        }
        if (searchFocused) {
            editSearch(typedChar, keyCode);
            return;
        }
        if (activeText != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) { activeText = null; return; }
            if (keyCode == Keyboard.KEY_RETURN) { activeText.submit(); activeText = null; return; }
            activeText.setText(editString(activeText.getText(), typedChar, keyCode, activeText.getMaxLength()));
            return;
        }
        if (activeList != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) { activeList = null; listDraft = ""; return; }
            if (keyCode == Keyboard.KEY_RETURN) { addListEntry(activeList); return; }
            listDraft = editString(listDraft, typedChar, keyCode, listMaxLength(activeList));
            clearSuggestions();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) { closeDashboard(); return; }
    }

    private void closeDashboard() {
        // Use the close animation instead of immediately calling displayGuiScreen(null).
        // Immediate close races with Module.onKeyBind() in the same tick: the module
        // fires toggle() → onEnable() → sees mc.currentScreen == null → re-opens.
        // With guiClosing=true the screen stays alive during the animation, so
        // onEnable()'s guard (mc.currentScreen != Raven.clickGui) prevents the re-open.
        guiClosing = true;
    }

    private void updateDragging(int mx, int my) {
        if (!Mouse.isButtonDown(0)) { draggingSlider = null; colorDrag = 0; draggingScrollbar = 0; return; }
        if (draggingScrollbar != 0) updateScrollbarDrag(my);
        if (draggingSlider != null) setSliderFromMouse(draggingSlider, mx, sliderRect.x1, sliderRect.x2);
        if (openColor != null && colorDrag != 0) updateColor(mx, my);
    }

    private boolean beginScrollbarDrag(int mx, int my) {
        if (beginScrollbarDrag(1, mx, my, centerX + centerW - 7, baseY + 61,
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
        float top = baseY + (modules ? 57f : 59f);
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
            if (Raven.profileManager != null && Raven.profileManager.profiles != null) {
                for (Profile profile : Raven.profileManager.profiles) profiles.add(profile.getModule());
            }
            return profiles;
        }
        if (categories != null) for (CategoryComponent c : categories) if (c.category == category) {
            List<Module> result = new ArrayList<Module>();
            for (ModuleComponent component : c.getModules()) {
                if (component.mod != null && Gui.shouldShowModule(component.mod)) result.add(component.mod);
            }
            return result;
        }
        if (Raven.getModuleManager() == null) return Collections.<Module>emptyList();
        List<Module> visible = new ArrayList<Module>();
        for (Module mod : Raven.getModuleManager().inCategory(category)) {
            if (Gui.shouldShowModule(mod)) visible.add(mod);
        }
        return visible;
    }

    /**
     * Also clears the modern panel's own view state.
     *
     * The inherited implementation repositions category components, which this layout does not
     * read: it centres itself on the screen and cannot be dragged. Scroll offsets are the only
     * part of the view the user can get stuck, so they are what the button resets here.
     */
    @Override
    public void resetPositions() {
        super.resetPositions();
        moduleScroll = moduleScrollTarget = 0f;
        settingScroll = settingScrollTarget = 0f;
        dropdownScroll = dropdownScrollTarget = 0f;
        closeDropdownState();
    }

    private void activateProfile(ProfileModule module) {
        if (module == null || Raven.profileManager == null) return;
        module.toggle();
        Profile active = Raven.currentProfile;
        if (active != null && active.getName().equalsIgnoreCase(module.getName())) {
            selectedModule = active.getModule();
            moduleSnapshot = new ModuleSnapshot(selectedModule);
        }
    }

    private void updateProfile(ProfileModule module) {
        if (module == null || Raven.profileManager == null) return;
        Profile profile = Raven.profileManager.getProfile(module.getName());
        if (profile == null) return;
        Raven.profileManager.saveProfile(profile);
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
        Collections.sort(result, new Comparator<Module>() { public int compare(Module a, Module b) { return a.getName().compareToIgnoreCase(b.getName()); }});
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
        // Section headings carry their own space above the rule that separates them.
        if (setting instanceof DescriptionSetting) return 31;
        if (setting instanceof GroupSetting) return 31;
        // Action-only rows do not need the height of a full toggle/control.
        // Keeping them compact makes utility pages such as Scripts read as a
        // single grouped menu instead of a set of disconnected labels.
        if (setting instanceof ButtonSetting && ((ButtonSetting) setting).isMethodButton) return 25;
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            // Label, number and track share one row now, so a slider is no taller than
            // anything else and the panel keeps an even rhythm down the page.
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
        clearSuggestions();
    }

    private void addSuggestedEntry(Setting setting, String value) {
        listDraft = value == null ? "" : value;
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
        float moduleViewport = Math.max(1, panelH - 73);
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
        // A single fill produces cleaner antialiased corners than the previous
        // fill-plus-offset outline pairing, which exposed a faint second radius.
        rounded(x1, y1, x2, y2, 7.5f, color);
    }

    private void drawPanelShadow(float x, float y, float width, float height, float renderScale) {
        RoundedUtils.drawRoundShadow(x * renderScale, y * renderScale,
                width * renderScale, height * renderScale,
                7.5f * renderScale, 6f * renderScale, argb(108, 0, 0, 0));
    }

    /**
     * Every corner in this screen goes through here or {@link #rounded}, so the theme's rounding
     * percentage only has to be applied in these two places. Clamped to half the shorter side so
     * a large value cannot invert the geometry.
     */
    private static float radius(float radius, float w, float h) {
        float scaled = radius * mindless.module.impl.theme.ThemeManager.roundingScale();
        float limit = Math.min(Math.abs(w), Math.abs(h)) * .5f;
        // Never hand the shader an exact zero: its signed distance field degenerates there and
        // the quad it expands by a pixel bleeds over whatever was drawn next to it.
        return Math.max(.5f, Math.min(scaled, Math.max(.5f, limit)));
    }

    /**
     * Font family for this screen. The two family names used to be compile-time constants, so
     * the Font setting changed nothing here -- it is read live now. Only the SF family ships a
     * separate bold face; every other family reuses itself for headers.
     */
    private static String uiFontFamily(boolean bold) {
        String family = Gui.getSelectedFontName();
        // "Minecraft" is the bitmap font; it cannot be rasterised at arbitrary heights and looks
        // like a different UI entirely here, so this screen always uses a real typeface.
        if (family == null || family.isEmpty() || "Minecraft".equalsIgnoreCase(family)) {
            return bold ? FALLBACK_FONT_BOLD : FALLBACK_FONT_REGULAR;
        }
        if (!bold) return family;
        return FALLBACK_FONT_REGULAR.equals(family) ? FALLBACK_FONT_BOLD : family;
    }

    private void outline(float x1, float y1, float x2, float y2, float radius, int color) {
        // Draw a 1px larger rect in the border color behind the fill.
        // Avoids the roundRectOutline shader's transparent-fill blending artifact.
        float b = 1f;
        float w = (x2 - x1) + b * 2f, h = (y2 - y1) + b * 2f;
        RoundedUtils.drawRound(x1 - b, y1 - b, w, h, radius(radius + b, w, h), color);
    }

    private void rounded(float x1, float y1, float x2, float y2, float radius, int color) {
        RoundedUtils.drawRound(x1, y1, x2 - x1, y2 - y1, radius(radius, x2 - x1, y2 - y1), color);
    }
    private void line(float x1, float y1, float x2, float y2, int color) {
        // Fractional geometry stays visually one physical pixel after the GUI
        // scale is applied; Gui.drawRect rounded every divider up too heavily.
        RenderUtils.drawRect(x1, y1, x2, Math.max(y1 + .5f, y2), color);
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
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, ((color >>> 24) & 255) / 255f);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 20; i++) { double a = Math.PI * 2 * i / 20; GL11.glVertex2d(cx + Math.cos(a) * r, cy + Math.sin(a) * r); }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
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
        // Use PNG icon if loaded, tinted with the supplied color.
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
        // Vector fallback
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(.82f, .82f, 1f);
        x = 0f; y = 0f;
        switch (category) {
            case combat:
                // Crosshair with center dot
                segments(color,
                    x, y - 6.5f, x, y - 3.2f,
                    x, y + 3.2f, x, y + 6.5f,
                    x - 6.5f, y, x - 3.2f, y,
                    x + 3.2f, y, x + 6.5f, y);
                circle(x, y, 1.2f, color);
                circleOutline(x, y, 3.0f, color);
                break;
            case movement:
                // Arrow pointing top-right (paper plane / speed arrow)
                segments(color,
                    x - 5.5f, y + 5.5f, x + 5.5f, y - 5.5f,
                    x + 5.5f, y - 5.5f, x + 5.5f, y + 1.5f,
                    x + 5.5f, y - 5.5f, x - 1.5f, y - 5.5f,
                    x + 5.5f, y - 5.5f, x - 1.5f, y + 1.5f);
                break;
            case player:
                // Person silhouette: circle head + body arc
                circleOutline(x, y - 3.8f, 2.4f, color);
                segments(color,
                    x - 4.5f, y + 6f, x - 3.6f, y + 1.8f,
                    x - 3.6f, y + 1.8f, x - .8f, y + .6f,
                    x - .8f, y + .6f, x + .8f, y + .6f,
                    x + .8f, y + .6f, x + 3.6f, y + 1.8f,
                    x + 3.6f, y + 1.8f, x + 4.5f, y + 6f);
                break;
            case world:
                // Globe: circle + latitude/longitude lines
                circleOutline(x, y, 6f, color);
                segments(color,
                    x - 6f, y, x + 6f, y,
                    x, y - 6f, x, y + 6f);
                // Horizontal arc hints (just two shorter horizontal lines)
                segments(color,
                    x - 5.2f, y - 3f, x + 5.2f, y - 3f,
                    x - 5.2f, y + 3f, x + 5.2f, y + 3f);
                break;
            case render:
                // Monitor: rounded rect + stand
                lineBox(x - 5.8f, y - 5f, x + 5.8f, y + 2.8f, color);
                segments(color,
                    x, y + 2.8f, x, y + 5.5f,
                    x - 3f, y + 5.5f, x + 3f, y + 5.5f);
                break;
            case other:
                // Three horizontal dots
                circle(x - 4.5f, y, 1.4f, color);
                circle(x, y, 1.4f, color);
                circle(x + 4.5f, y, 1.4f, color);
                break;
            case client:
                // Sliders / settings lines
                segments(color,
                    x - 6f, y - 4f, x + 6f, y - 4f,
                    x - 6f, y, x + 6f, y,
                    x - 6f, y + 4f, x + 6f, y + 4f);
                // Notch indicators
                circle(x + 1.5f, y - 4f, 1.3f, color);
                circle(x - 2f, y, 1.3f, color);
                circle(x + 2.5f, y + 4f, 1.3f, color);
                break;
            case profiles:
                // ID card / bookmark
                lineBox(x - 5.8f, y - 5f, x + 5.8f, y + 5f, color);
                circleOutline(x - 2.5f, y - 1f, 1.6f, color);
                segments(color,
                    x - 4.5f, y + 2.8f, x - .5f, y + 2.8f,
                    x + 1f, y - 1.8f, x + 4.5f, y - 1.8f,
                    x + 1f, y + .6f, x + 4.5f, y + .6f,
                    x + 1f, y + 2.8f, x + 4.5f, y + 2.8f);
                break;
            case theme:
                // Paint palette: rounded body with a thumb hole and three paint wells
                circleOutline(x, y, 6f, color);
                circleOutline(x + 2.6f, y + 2.6f, 1.6f, color);
                circle(x - 3.2f, y - 1.2f, 1.25f, color);
                circle(x - 0.4f, y - 3.6f, 1.25f, color);
                circle(x + 2.8f, y - 2.4f, 1.25f, color);
                break;
            case bedwars:
                // Bed from the side: headboard, mattress, pillow, two legs.
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
                // Code brackets </>
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
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glColor4f(((color >> 16)&255)/255f, ((color>>8)&255)/255f, (color&255)/255f, ((color>>>24)&255)/255f);
        GL11.glLineWidth(1.15f);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i + 3 < points.length; i += 4) {
            GL11.glVertex2f(points[i], points[i + 1]);
            GL11.glVertex2f(points[i + 2], points[i + 3]);
        }
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void circleOutline(float cx, float cy, float r, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(((color >> 16)&255)/255f, ((color>>8)&255)/255f, (color&255)/255f, ((color>>>24)&255)/255f);
        GL11.glLineWidth(1.2f); GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i=0;i<18;i++){double a=Math.PI*2*i/18;GL11.glVertex2d(cx+Math.cos(a)*r,cy+Math.sin(a)*r);} GL11.glEnd(); GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void ensureUiTextures() {
        if (uiTextureLoadAttempted) return;
        uiTextureLoadAttempted = true;
        logoTexture = loadBundledTexture("mindless_modern_logo", LOGO_RESOURCE, true);
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
        try (InputStream stream = ModernClickGui.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            BufferedImage image = ImageIO.read(stream);
            if (image == null) return null;
            DynamicTexture texture = new DynamicTexture(image);
            texture.setBlurMipmap(smooth, false);
            return mc.getTextureManager().getDynamicTextureLocation(name, texture);
        } catch (Exception ignored) {
            return null;
        }
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

    /** Nominal body size; every other scale is expressed as a multiple of this. */
    private static final float BASE_TEXT_PX = 13f;

    /**
     * Renderer rasterised at the exact height this text will occupy, so it can be drawn at
     * scale 1.0. Previously headers came from an 11px atlas drawn at 1.45x and body text from a
     * 13px atlas drawn at 0.63x -- both resampled, which is what made the GUI look soft.
     */
    private RavenFontRenderer scaledFont(float scale, boolean bold) {
        float px = Math.max(6f, Math.round(BASE_TEXT_PX * scale * TEXT_SCALE));
        return FontManager.getClickGuiRenderer(uiFontFamily(bold), px);
    }

    private RavenFontRenderer uiFont(boolean bold) {
        return scaledFont(1f, bold);
    }

    /** Returns the 9px renderer rasterised at its natural size — always rendered at scale 1.0
     *  so the atlas is never downsampled and glyphs stay crisp. */
    private RavenFontRenderer uiSmallFont() {
        return FontManager.getClickGuiSmallRenderer(uiFontFamily(false));
    }

    /** Draws text using the 9px small renderer at scale 1.0 — no GL downscaling, no blur. */
    private void drawSmallText(String text, float x, float y, int color) {
        RavenFontRenderer renderer = uiSmallFont();
        GL11.glPushMatrix(); GL11.glTranslatef(x, y, 0);
        renderer.drawString(text == null ? "" : text, 0, 0, color, false);
        GL11.glPopMatrix();
    }

    private float smallTextWidth(String text) {
        return uiSmallFont().getStringWidth(text == null ? "" : text);
    }

    private String trimSmall(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        RavenFontRenderer font = uiSmallFont();
        if (font.getStringWidth(text) <= maxWidth) return text;
        String ellipsis = "..";
        float ellipsisW = font.getStringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.getStringWidth(sb.toString() + text.charAt(i)) + ellipsisW > maxWidth) {
                sb.append(ellipsis);
                return sb.toString();
            }
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    private void drawText(String text, float x, float y, int color, float scale, boolean bold) {
        RavenFontRenderer renderer = scaledFont(scale, bold);
        // Snap to the physical pixel grid. A fractional translate samples the glyph atlas
        // between texels, which smears every edge; this is the other half of the crispness fix.
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
        RavenFontRenderer f = scaledFont(scale, bold);
        if (f.getStringWidth(text) <= maxWidth) return text;
        String end = "..."; int i = text.length();
        while (i > 0 && f.getStringWidth(text.substring(0, i) + end) > maxWidth) i--;
        return text.substring(0, i) + end;
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

    private String editString(String current, char typed, int key, int max) {
        if (key == Keyboard.KEY_BACK && !current.isEmpty()) return current.substring(0, current.length() - 1);
        if (isCtrlKeyDown() && key == Keyboard.KEY_V) {
            String next = current + getClipboardString();
            return next.substring(0, Math.min(max, next.length()));
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typed) && current.length() < max) return current + typed;
        return current;
    }

    private String moduleDescription(Module module) {
        if (module instanceof ProfileModule) return module.isEnabled() ? "Currently active configuration." : "Click the row or Load to apply this configuration.";
        if (module instanceof Manager) return "Create or reload Mindless profiles.";
        String name = module.getName().toLowerCase(Locale.ROOT);
        if (module.script != null) return module.script.error ? "Script failed to compile." : "Loaded Mindless script module.";
        switch (name) {
            case "aim assist": return "Smoothly guides your aim toward valid targets.";
            case "antiknockback": case "velocity": return "Controls how much incoming knockback you receive.";
            case "auto clicker": return "Clicks the attack button at a configurable rate.";
            case "auto block": return "Automatically blocks around combat actions.";
            case "clickassist": return "Adds configurable assisted clicks to your input.";
            case "displace": return "Adjusts movement to influence received knockback.";
            case "hit select": return "Times attacks around movement and hurt windows.";
            case "hitboxes": return "Expands target hitboxes on the client side.";
            case "jump reset": return "Times jumps to reduce incoming horizontal knockback.";
            case "kill aura": return "Automatically attacks nearby valid targets.";
            case "knockback delay": return "Delays selected knockback handling behavior.";
            case "piercing": return "Improves targeting through nearby entity obstruction.";
            case "raw input": return "Uses raw mouse input for more direct camera movement.";
            case "reach": return "Adjusts the client attack interaction distance.";
            case "reduce": return "Reduces selected combat motion effects.";
            case "rodaimbot": return "Guides fishing-rod throws toward a target.";
            case "tpaura": return "Moves to and attacks targets using configured rules.";
            case "wtap": return "Resets sprint around hits to improve knockback.";

            case "45 jump": return "Helps perform consistent diagonal forty-five-degree jumps.";
            case "bhop": return "Automates repeated movement jumps for speed.";
            case "boost": return "Applies a configurable movement speed boost.";
            case "dolphin": return "Automates upward swimming movement in water.";
            case "fly": return "Provides configurable client flight movement.";
            case "instant stop": return "Stops horizontal movement as soon as input ends.";
            case "invmove": return "Allows movement while inventory screens are open.";
            case "keep sprint": return "Keeps sprint active through combat actions.";
            case "long jump": return "Extends the distance of movement jumps.";
            case "movement fix": return "Keeps movement aligned with modified rotations.";
            case "noslow": return "Reduces slowdown while using or holding items.";
            case "null move": return "Suppresses selected movement inputs or motion.";
            case "speed": return "Increases movement speed using the selected mode.";
            case "sprint": return "Automatically starts and maintains sprinting.";
            case "stasis": return "Holds the player in a controlled movement state.";
            case "stop motion": return "Freezes selected client movement updates.";
            case "teleport": return "Moves the player using configurable teleport behavior.";
            case "timer": return "Changes the client game timer speed.";
            case "vclip": return "Moves the player vertically by a chosen distance.";

            case "antiafk": return "Produces activity to prevent idle detection.";
            case "anti fireball": return "Detects and reacts to nearby incoming fireballs.";
            case "auto jump": return "Automatically jumps while moving.";
            case "auto swap": return "Switches to a configured item when needed.";
            case "auto tool": return "Selects the best hotbar tool for a block.";
            case "autoblockin": return "Automatically places blocks around your position.";
            case "bedaura": return "Finds and interacts with nearby beds automatically.";
            case "block in": return "Places blocks to enclose a nearby target.";
            case "bridge assist": return "Helps place blocks consistently while bridging.";
            case "clutch": return "Places a block to help recover from a fall.";
            case "delay remover": return "Removes selected vanilla interaction delays.";
            case "fast mine": return "Speeds up client block-breaking behavior.";
            case "fast place": return "Reduces the delay between block placements.";
            case "freecam": return "Detaches the camera for free client-side movement.";
            case "ghost hand": return "Interacts with selected blocks through obstructions.";
            case "hide window": return "Quickly hides or minimizes the game window.";
            case "invmanager": return "Sorts and manages inventory items automatically.";
            case "nofall": return "Prevents or reduces normal fall-damage handling.";
            case "norotate": return "Blocks server-forced camera rotations.";
            case "safewalk": return "Prevents walking over block edges.";
            case "water bucket": return "Automatically attempts a water-bucket landing.";

            case "anti debuff": return "Reduces selected negative visual potion effects.";
            case "anti shuffle": return "Keeps scoreboard lines from visually shuffling.";
            case "arrows": return "Shows directional indicators for relevant targets.";
            case "bedesp": return "Highlights beds through the world.";
            case "blockesp": return "Highlights configured block types in the world.";
            case "block overlay": return "Customizes the outline and fill of selected blocks.";
            case "body material": return "Applies a custom material effect to player models.";
            case "breakprogress": return "Shows progress for blocks currently being broken.";
            case "chams": return "Renders entities visibly through world geometry.";
            case "chestesp": return "Highlights nearby storage containers.";
            case "damage tags": return "Displays floating damage values after hits.";
            case "damage tint": return "Customizes the screen tint shown when hurt.";
            case "extendcamera": return "Extends and configures third-person camera distance.";
            case "extra bobbing": return "Adds configurable movement bobbing to the view.";
            case "fall view": return "Adjusts the camera presentation while falling.";
            case "free look": return "Lets the camera rotate without changing movement direction.";
            case "fullbright": return "Keeps the world clearly lit in dark areas.";
            case "hit effects": return "Adds configurable visual feedback when you hit.";
            case "hold look": return "Temporarily controls free-looking while a key is held.";
            case "hud": return "Controls the enabled-module overlay and HUD styling.";
            case "indicators": return "Displays compact combat and status indicators.";
            case "itemesp": return "Highlights dropped items in the world.";
            case "item physics": return "Gives dropped items more natural visual rotation.";
            case "mobesp": return "Highlights configured non-player entities.";
            case "motion blur": return "Blends recent frames for a smoother motion effect.";
            case "nametags": return "Replaces player nametags with clearer information.";
            case "nocameraclip": return "Prevents third-person camera clipping into blocks.";
            case "nohurtcam": return "Removes the camera shake shown when hurt.";
            case "notifications": return "Shows compact alerts for module and client events.";
            case "player esp": return "Outlines players with health, armor and name details.";
            case "potion hud": return "Displays active potion effects on the HUD.";
            case "radar": return "Shows nearby entities on a compact HUD radar.";
            case "saturation": return "Displays your current saturation level.";
            case "slow": return "Slows the visible first-person swing animation.";
            case "sword animation": return "Customizes first-person sword block animations.";
            case "targethud": return "Displays information about your current combat target.";
            case "tnt timer": return "Shows the remaining fuse time above primed TNT.";
            case "tracers": return "Draws directional lines toward selected entities.";
            case "trajectories": return "Previews the path of projectiles before release.";
            case "xray": return "Hides ordinary blocks to reveal selected ores or blocks.";
            case "always block": return "Shows a client-side blocking pose while attacking.";

            case "anti bot": return "Filters likely server NPCs from combat and ESP targets.";
            case "ambience": return "Customizes world time, sky and atmosphere colors.";
            case "atmosphere": return "Overrides selected weather and environmental visuals.";
            case "particles": return "Adds configurable ambient particles to the world.";

            case "auto requeue": return "Automatically queues another match after a game.";
            case "auto who": return "Runs player-list checks automatically in supported games.";
            case "arena stats": return "Displays useful arena and opponent statistics.";
            case "bed wars": return "Provides Bed Wars overlays and match information.";
            case "bridge info": return "Tracks useful bridging speed and movement information.";
            case "duels stats": return "Displays statistics for duel opponents.";
            case "murder mystery": return "Adds role and match assistance for Murder Mystery.";
            case "shophelper": return "Speeds up common purchases in supported shops.";
            case "sky wars": return "Provides Sky Wars match information and helpers.";
            case "speed builders": return "Adds round assistance for Speed Builders.";
            case "sumo fences": return "Highlights useful arena boundaries in Sumo.";
            case "woolwars": return "Provides Wool Wars match information and helpers.";

            case "anticheat": return "Displays detected server anticheat information.";
            case "chat bypass": return "Transforms outgoing chat to bypass basic filters.";
            case "debug": return "Shows diagnostic information for troubleshooting.";
            case "fake chat": return "Displays a local-only custom chat message.";
            case "latency alerts": return "Warns when connection latency or packet loss spikes.";
            case "name hider": return "Replaces identifying names in rendered client text.";
            case "view packets": return "Displays selected incoming and outgoing packets.";

            case "backtrack": return "Delays target updates to retain recent positions.";
            case "blink": return "Queues movement packets before releasing them together.";
            case "fake lag": return "Simulates configurable network delay on the client.";
            case "freeze": return "Temporarily holds selected network or movement updates.";
            case "lag range": return "Controls attack timing using delayed target positions.";
            case "limbo": return "Controls selected connection packets and timeout behavior.";

            case "capes": return "Selects and renders a custom Mindless cape.";
            case "flame trail": return "Leaves a decorative particle trail behind you.";
            case "hit effect": return "Plays configurable effects and sounds on confirmed hits.";
            case "slyport": return "Adds a playful configurable movement effect.";
            case "spin": return "Applies a client-side spinning visual effect.";

            case "chat commands": return "Enables Mindless commands through game chat.";
            case "command line": return "Provides the in-game Mindless command interface.";
            case "gui": return "Customizes the ClickGUI appearance, scale and blur.";
            case "relationships": return "Manages friends, enemies and player relationships.";
            case "settings": return "Controls global Mindless client preferences.";
            case "spotify info": return "Displays current music, progress and synchronized lyrics.";
            default:
                switch (module.moduleCategory()) {
                    case combat: return "Adjusts combat input, targeting or hit behavior.";
                    case movement: return "Changes client movement behavior.";
                    case player: return "Automates or assists a player action.";
                    case render: return "Customizes a client-side visual effect.";
                    case world: return "Changes how the world is displayed or handled.";
                    case other: return "Provides an additional client utility.";
                    case client: return "Configures a Mindless client feature.";
                    case theme: return "Controls a Mindless client theme or colour scheme.";
                    default: return "Mindless module with configurable behavior.";
                }
        }
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
        // Push a pending theme selection into Gui's colour settings before reading them, so
        // picking a theme repaints on the same frame instead of on the next GUI open.
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

        // Runs unconditionally: surfaces derive from the accent, so they must settle even on
        // the frames where the accent itself did not move.
        updateSurfacePalette();
    }

    /**
     * Repaints panels, rows, controls and borders from the theme's surface colour, so a theme
     * changes the whole GUI rather than only the accent and the text. Reverts to the stock
     * palette when surface theming is off.
     */
    private void updateSurfacePalette() {
        // Full manual control wins over anything derived: every surface is taken verbatim.
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
        // Edges lean toward the accent so the chrome reads as part of the theme.
        BORDER = withAlpha(mixColor(argb(255, 210, 210, 204), ACCENT, .55f), 62);
        DIVIDER = withAlpha(mixColor(argb(255, 210, 210, 204), ACCENT, .40f), 50);
        DROPDOWN_BG = PANEL_ALT;
        DROPDOWN_BORDER = BORDER;
        DROPDOWN_SELECTED = withAlpha(ACCENT, 80);
    }

    /** Scales a surface colour's brightness, clamped, and never quite to black. */
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

    /** A lightweight edit checkpoint for the selected module's direct controls. */
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
