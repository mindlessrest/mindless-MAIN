package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.AntiKnockback;
import mindless.module.impl.combat.Velocity;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.HudGlowHelper;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.TextGlowUtils;
import mindless.utility.font.GlyphBatch;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Theme;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;

import java.awt.Color;

public class HUD extends Module {
    private static final String[] COLOR_MODES = new String[] { "Static", "Gradient", "Rainbow" };
    private static final String[] WAVE_AXES = new String[] { "Vertical", "Horizontal" };
    private static final String[] VERTICAL_WAVE_DIRECTIONS = new String[] { "Down", "Up" };
    private static final String[] HORIZONTAL_WAVE_DIRECTIONS = new String[] { "Left", "Right" };
    /** Horizontal wave: scales screen X (center of row) into phase; larger = faster change across X. */
    private static final double HUD_WAVE_HORIZONTAL_X_SCALE = 0.35;
    private static final long HUD_RAINBOW_PERIOD_MS = 7500L;
    private static final double HUD_WAVE_ANGLE_SCALE = 0.12;

    public static ButtonSetting useTheme;
    public static SliderSetting colorMode;
    public static ColorSetting hudColor;
    public static ColorSetting hudColor2;
    public static SliderSetting waveAxis;
    public static SliderSetting verticalWaveDirection;
    public static SliderSetting horizontalWaveDirection;
    public static SliderSetting waveSpeed;
    public static SliderSetting waveLength;
    public static SliderSetting font;
    public static SliderSetting fontSize;
    private static SliderSetting outline;
    public static ButtonSetting alphabeticalSort;
    private static ButtonSetting drawBackground;
    private static SliderSetting backgroundMode;
    private static ButtonSetting roundedBackground;
    private static SliderSetting cornerRadius;
    private static SliderSetting backgroundOpacity;
    private static ButtonSetting backgroundBlur;
    private static ButtonSetting textShadow;
    private static SliderSetting shadowStyle;
    private static SliderSetting shadowOpacity;
    private static SliderSetting lineSpacing;
    private static ButtonSetting alignRight;
    private static ButtonSetting lowercase;
    public static ButtonSetting showInfo;
    private static SliderSetting infoSeparator;
    private static ButtonSetting infoMatchName;
    private static ColorSetting infoColor;
    private static final float DEFAULT_POS_X = 5.0f;
    private static final float DEFAULT_POS_Y = 70.0f;
    public static float posX = DEFAULT_POS_X;
    public static float posY = DEFAULT_POS_Y;
    private static float relativePosX = Float.NaN;
    private static float relativePosY = Float.NaN;

    private static final String[] OUTLINE_MODES = new String[] { "None", "Full", "Side" };
    private static final String[] BACKGROUND_MODES = new String[] { "Connected", "Per line", "Panel" };
    private static final String[] SHADOW_STYLES = new String[] { "Drop", "Outline", "Soft" };
    /**
     * Offsets and weights for the soft shadow, as {x, y, weight}.
     *
     * A single offset copy is a duplicate of the text, not a shadow, and over a bright background
     * it reads as a smear. Stacking a few weighted copies over a two pixel spread gives a falloff
     * instead, which stays legible without turning into a second set of letters.
     */
    private static final float[][] SOFT_SHADOW_TAPS = {
            { 0.55f, 0.55f, 0.30f },
            { 1.00f, 1.00f, 0.50f },
            { 1.45f, 1.45f, 0.26f }
    };
    private static final String[] INFO_SEPARATORS = new String[] { "Space", "Brackets", "Dash" };
    /** Eight neighbours, so an outlined glyph is enclosed on the diagonals as well as the sides. */
    private static final int[][] OUTLINE_OFFSETS = {
            { -1, -1 }, { 0, -1 }, { 1, -1 },
            { -1,  0 },            { 1,  0 },
            { -1,  1 }, { 0,  1 }, { 1,  1 }
    };
    private static final String[] HUD_FONT_OPTIONS = FontManager.getHudFontOptions();

    private boolean isAlphabeticalSort;
    private boolean canShowInfo;
    private String lastHudFontName = "";
    private float lastHudFontScale = -1.0f;

    public HUD() {
        // Named for what it is. "HUD" said nothing about the on-screen module list, so the
        // settings that control it -- colours, sorting, alignment, position -- were sitting
        // behind a name nobody would think to open. Old profiles still resolve through the
        // legacy alias in ModuleManager.
        super("Array List", Module.category.render);
        // The theme owns the list's colours by default, which is what keeps the client looking
        // like one thing. Everything below the toggle is the list's own scheme, for when it should
        // not: the settings and the wave machinery were always here, they just had nothing
        // registered to drive them once the theme took over.
        this.registerSetting(useTheme = new ButtonSetting("Use theme", true));
        this.registerSetting(colorMode = new SliderSetting("Color mode", 0, COLOR_MODES));
        this.registerSetting(hudColor = new ColorSetting("Color", 255, 255, 255));
        this.registerSetting(hudColor2 = new ColorSetting("Color 2", 120, 170, 255));
        this.registerSetting(waveAxis = new SliderSetting("Wave axis", 0, WAVE_AXES));
        this.registerSetting(verticalWaveDirection = new SliderSetting("Wave direction", 0, VERTICAL_WAVE_DIRECTIONS));
        this.registerSetting(horizontalWaveDirection = new SliderSetting("Wave direction ", 0, HORIZONTAL_WAVE_DIRECTIONS));
        this.registerSetting(waveSpeed = new SliderSetting("Wave speed", 1.0, 0.1, 4.0, 0.1));
        this.registerSetting(waveLength = new SliderSetting("Wave length", 1.0, 0.5, 4.0, 0.1));
        this.registerSetting(font = new SliderSetting("Font", 0, HUD_FONT_OPTIONS));
        this.registerSetting(fontSize = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.1));
        this.registerSetting(outline = new SliderSetting("Outline", 0, OUTLINE_MODES));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new HudEditor.Screen())));
        this.registerSetting(alignRight = new ButtonSetting("Align right", false));
        this.registerSetting(alphabeticalSort = new ButtonSetting("Alphabetical sort", false));
        this.registerSetting(lineSpacing = new SliderSetting("Line spacing", 0.0, -2.0, 8.0, 0.5));
        this.registerSetting(drawBackground = new ButtonSetting("Draw background", false));
        // "Rounded background" used to be the only rounding control and it silently switched the
        // whole list onto one panel behind the rows, which is not what rounding a background means
        // and is why it looked broken. The shape is its own choice now.
        this.registerSetting(backgroundMode = new SliderSetting("Background mode", 0, BACKGROUND_MODES));
        this.registerSetting(roundedBackground = new ButtonSetting("Rounded background", false));
        this.registerSetting(cornerRadius = new SliderSetting("Corner radius", 4.0, 0.0, 12.0, 0.5));
        this.registerSetting(backgroundOpacity = new SliderSetting("Background opacity", 43.0, 0.0, 100.0, 1.0));
        this.registerSetting(backgroundBlur = new ButtonSetting("Background blur", false));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(shadowStyle = new SliderSetting("Shadow style", 0, SHADOW_STYLES));
        this.registerSetting(shadowOpacity = new SliderSetting("Shadow opacity", 100.0, 5.0, 100.0, 5.0));
        this.registerSetting(lowercase = new ButtonSetting("Lowercase", false));
        this.registerSetting(showInfo = new ButtonSetting("Show module info", true));
        this.registerSetting(infoSeparator = new SliderSetting("Info separator", 0, INFO_SEPARATORS));
        this.registerSetting(infoMatchName = new ButtonSetting("Info matches name color", false));
        this.registerSetting(infoColor = new ColorSetting("Info color", 170, 170, 170));
    }

    @Override
    public void guiUpdate() {
        boolean ownColors = useTheme != null && !useTheme.isToggled();
        int mode = colorMode == null ? 0 : (int) colorMode.getInput();
        if (colorMode != null) {
            colorMode.setVisible(ownColors, this);
        }
        if (hudColor != null) {
            hudColor.setVisible(ownColors && mode != 2, this);
        }
        if (hudColor2 != null) {
            hudColor2.setVisible(ownColors && mode == 1, this);
        }
        // The wave shapes the gradient phase whichever palette is in use, so it stays available
        // for a themed list too -- it just has nothing to shape when the colour is flat.
        boolean waving = mode != 0 || useTheme == null || useTheme.isToggled();
        if (waveAxis != null) {
            waveAxis.setVisible(waving, this);
        }
        if (verticalWaveDirection != null) {
            verticalWaveDirection.setVisible(waving && hudWaveIsVertical(), this);
        }
        if (horizontalWaveDirection != null) {
            horizontalWaveDirection.setVisible(waving && !hudWaveIsVertical(), this);
        }
        if (waveSpeed != null) {
            waveSpeed.setVisible(waving, this);
        }
        if (waveLength != null) {
            waveLength.setVisible(waving, this);
        }

        boolean background = drawBackground != null && drawBackground.isToggled();
        if (backgroundMode != null) {
            backgroundMode.setVisible(background, this);
        }
        if (roundedBackground != null) {
            roundedBackground.setVisible(background, this);
        }
        if (cornerRadius != null) {
            cornerRadius.setVisible(background && roundedBackground != null && roundedBackground.isToggled(), this);
        }
        if (backgroundOpacity != null) {
            backgroundOpacity.setVisible(background, this);
        }
        if (backgroundBlur != null) {
            backgroundBlur.setVisible(background && getBackgroundMode() == 2, this);
        }

        boolean shadow = textShadow != null && textShadow.isToggled();
        if (shadowStyle != null) {
            shadowStyle.setVisible(shadow, this);
        }
        if (shadowOpacity != null) {
            shadowOpacity.setVisible(shadow, this);
        }

        boolean info = showInfo != null && showInfo.isToggled();
        if (infoSeparator != null) {
            infoSeparator.setVisible(info, this);
        }
        if (infoMatchName != null) {
            infoMatchName.setVisible(info, this);
        }
        if (infoColor != null) {
            infoColor.setVisible(info && infoMatchName != null && !infoMatchName.isToggled(), this);
        }
    }

    @Override
    public void onEnable() {
        guiUpdate();
        ModuleManager.sort();
    }

    @Override
    public void guiButtonToggled(ButtonSetting buttonSetting) {
        if (buttonSetting == alphabeticalSort || buttonSetting == showInfo) {
            ModuleManager.sort();
        }
        guiUpdate();
    }

    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }

        if (isAlphabeticalSort != alphabeticalSort.isToggled()) {
            isAlphabeticalSort = alphabeticalSort.isToggled();
            ModuleManager.sort();
        }

        if (canShowInfo != showInfo.isToggled()) {
            canShowInfo = showInfo.isToggled();
            ModuleManager.sort();
        }

        String currentFontName = getSelectedFontName();
        float currentFontScale = getSelectedFontScale();
        if (!currentFontName.equals(lastHudFontName) || Float.compare(currentFontScale, lastHudFontScale) != 0) {
            lastHudFontName = currentFontName;
            lastHudFontScale = currentFontScale;
            ModuleManager.sort();
        }

        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        syncPositionToResolution();

        for (Module module : ModuleManager.organizedModules) {
            module.getInfoUpdate();
            if (Module.sort) {
                break;
            }
        }

        if (Module.sort) {
            ModuleManager.sort();
        }
        Module.sort = false;

        RavenFontRenderer hudFont = getHudFontRenderer();
        int textTopOffset = hudFont.getTextTopOffset();
        int textBottomOffset = hudFont.getTextBottomOffset();
        int horizontalTextPadding = getHudHorizontalTextPadding();
        int textTopPadding = getHudTextTopPadding();
        int textBottomPadding = getHudTextBottomPadding();
        int outlineThickness = getHudOutlineThickness();
        int rowHeight = getHudRowHeight(textTopOffset, textBottomOffset, textTopPadding, textBottomPadding);
        float yPos = posY;
        double verticalWaveAccum = 0.0;
        boolean firstVisibleRow = true;
        String previousModule = "";
        int previousModuleWidth = 0;
        double lastOutlineLeft = 0.0;
        double lastOutlineRight = 0.0;
        double lastBackgroundBottom = 0.0;
        boolean removeVelocity = ModuleManager.antiKnockback.isEnabled();

        // Mask-based glow: pre-pass renders text into FBO, blurs it, composites as glow underneath
        boolean useShaderGlow = Settings.arrayListGlow != null && Settings.arrayListGlow.isToggled()
                && HudGlowHelper.isAvailable();
        if (useShaderGlow) {
            renderArrayListGlowPass(hudFont, removeVelocity, rowHeight, horizontalTextPadding,
                    textTopOffset, textTopPadding);
        }

        // Backgrounds are drawn up front, from the same widths the loop below lays the rows out
        // with, so the two can never disagree about how wide or tall the list is.
        if (drawBackground.isToggled()) {
            drawArrayListBackground(collectRowWidths(hudFont, removeVelocity),
                    posY, horizontalTextPadding, rowHeight);
        }

        try {
            for (Module module : ModuleManager.organizedModules) {
                if (!module.isEnabled() || module == this || shouldSkipModule(module, removeVelocity)) {
                    continue;
                }

                String moduleName = getHudRenderText(module);
                int moduleWidth = hudFont.getStringWidth(moduleName);
                float xPos = posX;
                float textY = getHudTextY(yPos, textTopOffset, textTopPadding);
                double backgroundLeft = xPos - horizontalTextPadding;
                double backgroundRight = xPos + moduleWidth + horizontalTextPadding;
                double backgroundTop = yPos;
                double backgroundBottom = yPos + rowHeight;
                double outlineLeft = backgroundLeft - outlineThickness;
                double outlineRight = backgroundRight + outlineThickness;
                double outlineTop = backgroundTop - outlineThickness;

                if (alignRight.isToggled()) {
                    xPos -= moduleWidth;
                    backgroundLeft = xPos - horizontalTextPadding;
                    backgroundRight = xPos + moduleWidth + horizontalTextPadding;
                    outlineLeft = backgroundLeft - outlineThickness;
                    outlineRight = backgroundRight + outlineThickness;
                }

                double rowCenterX = (backgroundLeft + backgroundRight) * 0.5;
                double wavePhase = hudWavePhase(verticalWaveAccum, rowCenterX);
                int color = getHudColor(wavePhase);

                if (outline.getInput() == 1 && firstVisibleRow) {
                    RenderUtils.drawRect(outlineLeft, outlineTop, outlineRight, backgroundTop, color);
                }

                if (hudWaveIsVertical()) {
                    verticalWaveAccum += getVerticalWaveStep();
                }
                firstVisibleRow = false;

                if (outline.getInput() == 1 && !previousModule.isEmpty()) {
                    double difference = previousModuleWidth - moduleWidth;
                    if (alphabeticalSort.isToggled() && difference < 0) {
                        RenderUtils.drawRect(outlineLeft, outlineTop, xPos - difference + horizontalTextPadding + outlineThickness, backgroundTop, color);
                    }
                    else if (alignRight.isToggled()) {
                        RenderUtils.drawRect(xPos - difference - horizontalTextPadding - outlineThickness, outlineTop, backgroundLeft, backgroundTop, color);
                    }
                    else {
                        RenderUtils.drawRect(backgroundRight, outlineTop, xPos + difference + moduleWidth + horizontalTextPadding + outlineThickness, backgroundTop, color);
                    }
                }

                if (outline.getInput() > 0) {
                    if (alignRight.isToggled()) {
                        RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                    }
                    else {
                        RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                    }
                }

                if (outline.getInput() == 1) {
                    if (alignRight.isToggled()) {
                        RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                    }
                    else {
                        RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                    }
                }

                drawHudRow(hudFont, module, xPos, textY, color);
                previousModule = moduleName;
                previousModuleWidth = moduleWidth;
                lastOutlineLeft = outlineLeft;
                lastOutlineRight = outlineRight;
                lastBackgroundBottom = backgroundBottom;
                yPos += rowHeight;
            }
        }
        catch (Exception exception) {
            Utils.sendMessage("&cAn error occurred rendering HUD. check your logs");
            exception.printStackTrace();
        }

        if (outline.getInput() == 1 && !previousModule.isEmpty()) {
            double bottomCenterX = (lastOutlineLeft + lastOutlineRight) * 0.5;
            double bottomPhase = hudWavePhase(verticalWaveAccum, bottomCenterX);
            RenderUtils.drawRect(lastOutlineLeft, lastBackgroundBottom, lastOutlineRight, lastBackgroundBottom + outlineThickness, getHudColor(bottomPhase));
        }
    }

    public static int getLongestModule() {
        RavenFontRenderer hudFont = getHudFontRenderer();
        int length = 0;

        for (Module module : ModuleManager.organizedModules) {
            if (module.isEnabled()) {
                length = Math.max(length, hudFont.getStringWidth(getHudRenderText(module)));
            }
        }

        return length;
    }

    public static float[] renderDesignerPreview() {
        RavenFontRenderer font = getHudFontRenderer();
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean removeVelocity = ModuleManager.antiKnockback != null && ModuleManager.antiKnockback.isEnabled();
        for (Module module : ModuleManager.organizedModules) {
            if (module.isEnabled() && !(module instanceof HUD) && !shouldSkipModule(module, removeVelocity)) {
                lines.add(getHudRenderText(module));
            }
        }
        if (lines.isEmpty()) {
            lines.add("Kill Aura");
            lines.add("Player ESP");
            lines.add("Sprint");
        }
        int maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, font.getStringWidth(line));
        float rowHeight = Math.max(10.0F, font.getFontHeight() + 2.0F);
        float left = alignRight != null && alignRight.isToggled() ? posX - maxWidth : posX;
        float top = posY;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float lineX = alignRight != null && alignRight.isToggled()
                    ? posX - font.getStringWidth(line) : posX;
            int color = getHudColor(i * 45.0D);
            drawDecoration(font, line, lineX, top + i * rowHeight, color);
            font.drawString(line, lineX, top + i * rowHeight, color, false);
        }
        return new float[] { left, top, left + maxWidth, top + lines.size() * rowHeight };
    }

    /** One arraylist row, and the box the hide picker can click on to reach it. */
    public static final class PickerRow {
        public final Module module;
        public final float left, top, right, bottom;

        PickerRow(Module module, float left, float top, float right, float bottom) {
            this.module = module;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    /**
     * Draws the arraylist where it actually sits so its entries can be clicked in place.
     *
     * <p>Two things separate this from the live render. Hidden modules are drawn rather than
     * skipped -- greyed and struck through, because a hidden entry that disappears is one you can
     * never click again to bring back. And the row's box is handed back rather than just painted,
     * since the picker has to know where each line landed to hit-test it; the widths here are per
     * line, so the clickable area is the text, which is what you are aiming at.
     *
     * @return every drawn row, in draw order
     */
    public static java.util.List<PickerRow> renderHidePicker(int mouseX, int mouseY) {
        RavenFontRenderer font = getHudFontRenderer();
        java.util.List<Module> shown = new java.util.ArrayList<Module>();
        boolean removeVelocity = ModuleManager.antiKnockback != null && ModuleManager.antiKnockback.isEnabled();
        for (Module module : ModuleManager.organizedModules) {
            if (!module.isEnabled() || module instanceof HUD) continue;
            if (module == ModuleManager.commandLine) continue;
            if (module instanceof mindless.module.impl.combat.Velocity && removeVelocity) continue;
            shown.add(module);
        }

        java.util.List<PickerRow> rows = new java.util.ArrayList<PickerRow>();
        if (shown.isEmpty()) return rows;

        boolean right = alignRight != null && alignRight.isToggled();
        float rowHeight = Math.max(10.0F, font.getFontHeight() + 2.0F);
        float top = posY;

        for (int i = 0; i < shown.size(); i++) {
            Module module = shown.get(i);
            String line = getHudRenderText(module);
            float width = font.getStringWidth(line);
            float lineX = right ? posX - width : posX;
            float lineY = top + i * rowHeight;
            boolean hidden = module.isHidden();
            boolean hovered = mouseX >= lineX - 2f && mouseX <= lineX + width + 2f
                    && mouseY >= lineY - 1f && mouseY <= lineY + rowHeight - 1f;

            if (hovered) {
                RenderUtils.drawRect(lineX - 2f, lineY - 1f, lineX + width + 2f,
                        lineY + rowHeight - 1f, 0x44FFFFFF);
            }

            int color = hidden ? 0xFF6E747B : getHudColor(i * 45.0D);
            if (!hidden) drawDecoration(font, line, lineX, lineY, color);
            font.drawString(line, lineX, lineY, color, false);
            if (hidden) {
                float strike = lineY + font.getFontHeight() / 2f;
                RenderUtils.drawRect(lineX, strike, lineX + width, strike + 1f, 0xFF6E747B);
            }

            rows.add(new PickerRow(module, lineX - 2f, lineY - 1f,
                    lineX + width + 2f, lineY + rowHeight - 1f));
        }
        return rows;
    }

    public static void setDesignerTopLeft(float left, float top) {
        RavenFontRenderer font = getHudFontRenderer();
        int width = Math.max(font.getStringWidth("Kill Aura"),
                Math.max(font.getStringWidth("Player ESP"), font.getStringWidth("Music Player")));
        setAbsolutePosition(alignRight != null && alignRight.isToggled() ? left + width : left, top);
    }

    private static boolean shouldSkipModule(Module module, boolean removeVelocity) {
        if (module.isHidden()) {
            return true;
        }
        if (module == ModuleManager.commandLine) {
            return true;
        }
        return module instanceof Velocity && removeVelocity;
    }

    private static boolean isLastVisibleModule(Module currentModule, boolean removeVelocity) {
        boolean foundCurrent = false;

        for (Module module : ModuleManager.organizedModules) {
            if (!foundCurrent) {
                if (module == currentModule) {
                    foundCurrent = true;
                }
                continue;
            }

            if (module.isEnabled() && !(module instanceof HUD) && !shouldSkipModule(module, removeVelocity)) {
                return false;
            }
        }

        return true;
    }


    public static RavenFontRenderer getHudFontRenderer() {
        return FontManager.getHudRenderer(getSelectedFontName(), getSelectedFontScale());
    }

    public static String getHudText(Module module) {
        String moduleName = module instanceof AntiKnockback ? "Velocity" : module.getNameInHud();
        if (lowercase != null && lowercase.isToggled()) {
            moduleName = moduleName.toLowerCase();
        }
        return moduleName;
    }

    public static String getHudRenderText(Module module) {
        return getHudText(module) + getHudInfoText(module);
    }

    /**
     * The module's value with its separator, or "" when there is nothing to show.
     *
     * The colour code that used to be baked in here (a literal section-7) fixed the value at
     * Minecraft's grey and could not be changed, which is what stopped the list from being able to
     * do a dim name against a bright value. The two halves are drawn separately now and this
     * carries no colour at all.
     */
    private static String getHudInfoText(Module module) {
        if (showInfo == null || !showInfo.isToggled()) {
            return "";
        }
        String info = module.getInfo();
        if (info == null || info.isEmpty()) {
            return "";
        }
        if (lowercase != null && lowercase.isToggled()) {
            info = info.toLowerCase();
        }
        switch (infoSeparator == null ? 0 : (int) infoSeparator.getInput()) {
            case 1:  return " [" + info + "]";
            case 2:  return " - " + info;
            default: return " " + info;
        }
    }

    /** Colour for the value half of a row, given the colour the name was drawn in. */
    private static int getHudInfoColor(int nameColor) {
        if (infoMatchName != null && infoMatchName.isToggled()) {
            return nameColor;
        }
        if (infoColor == null) {
            return 0xFFAAAAAA;
        }
        // Carry the name's alpha across so both halves fade together.
        int alpha = (nameColor >>> 24) & 0xFF;
        return ((alpha == 0 ? 0xFF : alpha) << 24) | (infoColor.getRGB() & 0xFFFFFF);
    }

    public static String getSelectedFontName() {
        if (font == null) {
            return HUD_FONT_OPTIONS[0];
        }
        int index = (int) Math.max(0, Math.min(font.getOptions().length - 1, font.getInput()));
        return font.getOptions()[index];
    }

    public static float getSelectedFontScale() {
        if (fontSize == null) {
            return 1.0f;
        }
        return (float) fontSize.getInput();
    }

    public static float getRelativePosX() {
        syncPositionToResolution();
        return relativePosX;
    }

    public static float getRelativePosY() {
        syncPositionToResolution();
        return relativePosY;
    }

    public static void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPositionToResolution();
    }

    public static void setAbsolutePosition(float absoluteX, float absoluteY) {
        setAbsolutePosition(absoluteX, absoluteY, ScaledResolutionCache.get());
    }

    public static void resetPosition() {
        resetPosition(ScaledResolutionCache.get());
    }

    private static void syncPositionToResolution() {
        syncPositionToResolution(ScaledResolutionCache.get());
    }

    private static void syncPositionToResolution(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());

        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            relativePosX = posX / scaledWidth;
            relativePosY = posY / scaledHeight;
        }

        posX = relativePosX * scaledWidth;
        posY = relativePosY * scaledHeight;
    }

    private static void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        posX = absoluteX;
        posY = absoluteY;

        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        relativePosX = absoluteX / scaledWidth;
        relativePosY = absoluteY / scaledHeight;
    }

    private static void resetPosition(ScaledResolution resolution) {
        setAbsolutePosition(DEFAULT_POS_X, DEFAULT_POS_Y, resolution);
    }

    private static int getHudHorizontalTextPadding() {
        return getScaledHudPixels(2.0f);
    }

    private static int getHudTextTopPadding() {
        return getScaledHudPixels(2.0f);
    }

    private static int getHudTextBottomPadding() {
        return 0;
    }

    private static int getHudOutlineThickness() {
        return getScaledHudPixels(1.0f);
    }

    private static int getHudRowHeight(int textTopOffset, int textBottomOffset, int textTopPadding, int textBottomPadding) {
        int textBoxHeight = Math.max(1, textBottomOffset - textTopOffset);
        int spacing = lineSpacing == null ? 0 : (int) Math.round(lineSpacing.getInput());
        return Math.max(1, textBoxHeight + textTopPadding + textBottomPadding + spacing);
    }

    private static int getBackgroundMode() {
        return backgroundMode == null ? 0 : (int) backgroundMode.getInput();
    }

    /** Widths of every row the list will draw, in the order it will draw them. */
    private static int[] collectRowWidths(RavenFontRenderer hudFont, boolean removeVelocity) {
        java.util.List<Integer> widths = new java.util.ArrayList<Integer>();
        for (Module module : ModuleManager.organizedModules) {
            if (!module.isEnabled() || module instanceof HUD || shouldSkipModule(module, removeVelocity)) {
                continue;
            }
            widths.add(hudFont.getStringWidth(getHudRenderText(module)));
        }
        int[] result = new int[widths.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = widths.get(i);
        }
        return result;
    }

    private static void drawArrayListBackground(int[] widths, float top, int horizontalTextPadding, int rowHeight) {
        if (widths.length == 0) {
            return;
        }
        int mode = getBackgroundMode();

        if (mode == 2) {
            int maxWidth = 0;
            for (int width : widths) {
                maxWidth = Math.max(maxWidth, width);
            }
            float left = alignRight.isToggled()
                    ? posX - maxWidth - horizontalTextPadding
                    : posX - horizontalTextPadding;
            drawHudBackground(left, top, maxWidth + horizontalTextPadding * 2f, widths.length * (float) rowHeight);
            return;
        }

        if (mode == 1) {
            for (int i = 0; i < widths.length; i++) {
                float left = alignRight.isToggled()
                        ? posX - widths[i] - horizontalTextPadding
                        : posX - horizontalTextPadding;
                drawHudBackground(left, top + i * rowHeight, widths[i] + horizontalTextPadding * 2f, rowHeight);
            }
            return;
        }

        drawConnectedBackground(widths, top, horizontalTextPadding, rowHeight);
    }

    /**
     * One continuous shape behind the whole list, following its stepped edge.
     *
     * Rounding every row on all four corners leaves a notch wherever two rows meet, because each
     * one curves away from a neighbour that is flush against it. Here a corner is only rounded
     * when it is genuinely on the outside of the silhouette -- the ends of the list, and the steps
     * where a row sticks out past the one above or below it. Everything else is squared off, so
     * the rows fuse into a single outline.
     */
    private static void drawConnectedBackground(int[] widths, float top, int horizontalTextPadding, int rowHeight) {
        boolean right = alignRight.isToggled();
        int alpha = getBackgroundAlpha();
        if (alpha <= 0) {
            return;
        }
        int color = new Color(0, 0, 0, alpha).getRGB();
        float radius = getBackgroundRadius(rowHeight);

        for (int i = 0; i < widths.length; i++) {
            float width = widths[i] + horizontalTextPadding * 2f;
            float left = right ? posX - widths[i] - horizontalTextPadding : posX - horizontalTextPadding;
            float rowTop = top + i * rowHeight;

            // Only the two ends of the list are rounded. Rounding each step as well curls every
            // row's outer corner back on itself, and against rows this short the curve is most of
            // the step -- the left edge stops reading as a staircase and turns into a column of
            // scalloped tongues. Square steps between rounded ends stay one clean shape.
            boolean firstRow = i == 0;
            boolean lastRow = i == widths.length - 1;
            float top4 = firstRow ? radius : 0.0f;
            float bottom4 = lastRow ? radius : 0.0f;

            fillRow(left, rowTop, left + width, rowTop + rowHeight,
                    top4, top4, bottom4, bottom4, color);
        }
    }

    /**
     * One row of the connected background, with rounding on the corners asked for.
     *
     * Built from flat rectangles plus a quarter disc at each rounded corner, rather than from a
     * rounded-rect shader. That shader anti-aliases a whole quadrant at a time, so a row with any
     * rounded corner also got a soft ramp along the straight edge it shares with the row above or
     * below -- two ramps meeting is less than full coverage, which is the pale line that showed
     * between every row. Straight edges here are hard and land on the same coordinate as their
     * neighbour's, so the rows meet with nothing between them, and the only softened pixels in the
     * whole shape are on the curves themselves.
     */
    private static void fillRow(float x1, float y1, float x2, float y2,
                                float topLeft, float topRight, float bottomRight, float bottomLeft,
                                int color) {
        float topBand = Math.max(topLeft, topRight);
        float bottomBand = Math.max(bottomLeft, bottomRight);

        if (topBand > 0.0f) {
            RenderUtils.drawRect(x1 + topLeft, y1, x2 - topRight, y1 + topBand, color);
        }
        RenderUtils.drawRect(x1, y1 + topBand, x2, y2 - bottomBand, color);
        if (bottomBand > 0.0f) {
            RenderUtils.drawRect(x1 + bottomLeft, y2 - bottomBand, x2 - bottomRight, y2, color);
        }

        // Zero is up and the sweep runs clockwise, so each quarter starts at the axis leading into
        // its own corner.
        quarterDisc(x1 + topLeft, y1 + topLeft, topLeft, 270.0f, color);
        quarterDisc(x2 - topRight, y1 + topRight, topRight, 0.0f, color);
        quarterDisc(x2 - bottomRight, y2 - bottomRight, bottomRight, 90.0f, color);
        quarterDisc(x1 + bottomLeft, y2 - bottomLeft, bottomLeft, 180.0f, color);
    }

    /** Width of the translucent fringe on a corner curve, in GUI pixels. */
    private static final float CORNER_FEATHER = 0.6f;

    private static void quarterDisc(float cx, float cy, float radius, float startDeg, int color) {
        if (radius <= 0.0f) {
            return;
        }
        radialBand(cx, cy, 0.0f, 1.0f, radius, 1.0f, startDeg, color);
        radialBand(cx, cy, radius, 1.0f, radius + CORNER_FEATHER, 0.0f, startDeg, color);
    }

    /**
     * A ninety degree band as a triangle strip, alpha {@code a0} at {@code r0} fading to
     * {@code a1} at {@code r1}. Nothing in this path is anti-aliased by the pipeline, so the
     * fringe band is how the curve gets its soft edge.
     */
    private static void radialBand(float cx, float cy, float r0, float a0, float r1, float a1,
                                   float startDeg, int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int alpha = (color >>> 24) & 0xFF;
        int c0 = Math.round(alpha * a0), c1 = Math.round(alpha * a1);
        if (c0 <= 0 && c1 <= 0) {
            return;
        }

        // Three pieces of inherited state have to be corrected here, and every one of them is
        // invisible until geometry like this is drawn through it.
        //
        // shadeModel is the important one. The gradient helpers in RenderUtils set GL_SMOOTH,
        // draw, and set GL_FLAT back, and syncGlState leaves GL_FLAT too. Under GL_FLAT a
        // triangle takes one vertex's colour for all of it, so the alpha ramps that feather
        // every edge below collapsed into solid blocks -- the anti-aliasing was being written
        // and then thrown away by the rasteriser.
        //
        // The alpha test is Minecraft's usual GL_GREATER 0.1, which chops the tail off a fade
        // and turns the soft edge back into a hard one.
        //
        // Culling depends on winding, and winding here depends on which way a stroke runs, so a
        // shape could vanish entirely based on the direction it was drawn in.
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= 12; i++) {
            double t = Math.toRadians(startDeg + 90.0 * i / 12.0);
            double sin = Math.sin(t), cos = Math.cos(t);
            worldRenderer.pos(cx + sin * r1, cy - cos * r1, 0.0D).color(r, g, b, c1).endVertex();
            worldRenderer.pos(cx + sin * r0, cy - cos * r0, 0.0D).color(r, g, b, c0).endVertex();
        }
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();

    }

    private static int getBackgroundAlpha() {
        return Math.max(0, Math.min(255,
                (int) Math.round((backgroundOpacity == null ? 43.0 : backgroundOpacity.getInput()) * 2.55)));
    }

    private static float getBackgroundRadius(float height) {
        if (roundedBackground == null || !roundedBackground.isToggled()) {
            return 0.0f;
        }
        float radius = (float) (cornerRadius == null ? 4.0 : cornerRadius.getInput())
                * mindless.module.impl.theme.ThemeManager.roundingScale();
        // A third of the row, not half. The corner lives inside a single row -- it cannot spill
        // into the one below, because that row is a different width -- so at half the row height
        // the curve runs from the top edge to the middle and the end row stops reading as a
        // rounded corner and starts reading as a lozenge stuck on the end of a square staircase.
        return Math.max(0.0f, Math.min(radius, height * 0.34f));
    }

    /**
     * One background box, rounded or square, blurred or not.
     *
     * Both call sites go through here so a per-line background and a panel behind the whole list
     * pick up the same radius, opacity and blur rather than each carrying its own hardcoded look.
     */
    private static void drawHudBackground(float left, float top, float width, float height) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        int alpha = getBackgroundAlpha();
        if (alpha == 0 && !(backgroundBlur != null && backgroundBlur.isToggled())) {
            return;
        }
        int color = new Color(0, 0, 0, alpha).getRGB();
        float radius = getBackgroundRadius(Math.min(width, height));

        // Blur is a full-screen pass per box, so it is offered on the single panel only. Behind
        // fifteen separate rows it would be fifteen of them every frame.
        if (backgroundBlur != null && backgroundBlur.isToggled() && getBackgroundMode() == 2) {
            BlurUtils.prepareBlur(left, top, width, height);
            RoundedUtils.drawRound(left, top, width, height, radius, 0xFF000000);
            BlurUtils.blurEndRegion(1, 1.4f, 0.60f, left - 2.0f, top - 2.0f,
                    width + 4.0f, height + 4.0f);
        }
        if (alpha > 0) {
            if (radius <= 0.0f) {
                RenderUtils.drawRect(left, top, left + width, top + height, color);
            }
            else {
                RoundedUtils.drawRound(left, top, width, height, radius, new Color(color, true));
            }
        }
    }

    private static float getHudTextY(float rowTop, int textTopOffset, int textTopPadding) {
        return rowTop + textTopPadding - textTopOffset;
    }

    private static int getScaledHudPixels(float basePixels) {
        return Math.max(1, Math.round(basePixels * getSelectedFontScale()));
    }

    public static boolean shouldDrawTextShadow() {
        return textShadow == null || textShadow.isToggled();
    }

    private static boolean hudWaveIsVertical() {
        return waveAxis == null || (int) waveAxis.getInput() == 0;
    }

    public static double hudWavePhase(double verticalAccum, double rowCenterX) {
        if (hudWaveIsVertical()) {
            return verticalAccum;
        }
        return rowCenterX * (HUD_WAVE_HORIZONTAL_X_SCALE / getWaveLengthMultiplier()) * getHorizontalWaveDirectionSign();
    }

    /**
     * Draws one row as a name and a value, each in its own colour.
     *
     * <p>Held open as one batch. A row is a shadow in several passes and then two coloured
     * segments, all in the same font and all from the same glyph atlas, with nothing drawn between
     * them -- so what would otherwise be five draws is one.
     */
    private static void drawHudRow(RavenFontRenderer hudFont, Module module, float xPos, float textY, int color) {
        String name = getHudText(module);
        String info = getHudInfoText(module);
        if (info.isEmpty()) {
            drawHudText(hudFont, name, xPos, textY, color);
            return;
        }

        GlyphBatch.begin();
        try {
            drawDecoration(hudFont, name + info, xPos, textY, color);
            drawTextSegment(hudFont, name, xPos, textY, color, false);
            drawTextSegment(hudFont, info, xPos + hudFont.getStringWidth(name), textY,
                    getHudInfoColor(color), false);
        }
        finally {
            GlyphBatch.end();
        }
    }

    private static void drawHudText(RavenFontRenderer hudFont, String moduleName, float xPos, float textY, int fallbackColor) {
        GlyphBatch.begin();
        try {
            drawDecoration(hudFont, moduleName, xPos, textY, fallbackColor);
            drawTextSegment(hudFont, moduleName, xPos, textY, fallbackColor, false);
        }
        finally {
            GlyphBatch.end();
        }
    }

    /**
     * Glow and shadow for a row, drawn under the text.
     *
     * Every offset is a multiple of the font's own height rather than a fixed pixel count. At
     * scale 2 a one pixel shadow is half as far as it should be and at scale 0.6 it is nearly
     * twice; anchoring it to the glyph keeps the same shadow at every size. All three styles are
     * drawn here rather than leaning on the renderer's built-in shadow, so Shadow opacity means
     * something for all of them.
     */
    private static void drawDecoration(RavenFontRenderer hudFont, String text, float xPos, float textY, int color) {
        if (Settings.arrayListGlow != null && Settings.arrayListGlow.isToggled() && !HudGlowHelper.isAvailable()) {
            TextGlowUtils.drawGlow(hudFont, text, xPos, textY, color);
        }
        if (!shouldDrawTextShadow()) {
            return;
        }

        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        int base = getShadowAlpha();
        if (base <= 0) {
            return;
        }
        float unit = Math.max(1.0f, Math.round(hudFont.getFontHeight() * 0.1f));

        switch (shadowStyle == null ? 0 : (int) shadowStyle.getInput()) {
            case 1:
                for (int[] offset : OUTLINE_OFFSETS) {
                    hudFont.drawString(plain, xPos + offset[0] * unit, textY + offset[1] * unit,
                            base << 24, false);
                }
                break;
            case 2:
                // Three copies a fraction of a pixel apart along one diagonal. Overlapping them
                // builds a falloff, where the old taps sat up to two pixels out in three
                // directions and read as a second, blurrier set of letters.
                for (float[] tap : SOFT_SHADOW_TAPS) {
                    int alpha = Math.round(base * tap[2]);
                    if (alpha > 0) {
                        hudFont.drawString(plain, xPos + tap[0] * unit, textY + tap[1] * unit,
                                alpha << 24, false);
                    }
                }
                break;
            default:
                hudFont.drawString(plain, xPos + unit, textY + unit, base << 24, false);
                break;
        }
    }

    /**
     * Pre-pass: renders all ArrayList text into the glow mask, blurs it, composites as glow.
     * Glow follows actual glyphs rather than producing rectangular artifacts.
     */
    private static void renderArrayListGlowPass(RavenFontRenderer hudFont, boolean removeVelocity,
                                                 int rowHeight, int horizontalTextPadding,
                                                 int textTopOffset, int textTopPadding) {
        HudGlowHelper.beginMask();
        // Nothing but text goes into the mask, so the whole list is one batch. The finally is not
        // decoration: an unbalanced begin leaves the batch permanently open and no text anywhere in
        // the client would ever be flushed again.
        GlyphBatch.begin();
        try {
            float yPos = posY;
            double verticalWaveAccum = 0.0;
            for (Module module : ModuleManager.organizedModules) {
                if (!module.isEnabled() || module instanceof HUD || shouldSkipModule(module, removeVelocity)) {
                    continue;
                }
                String moduleName = getHudRenderText(module);
                int moduleWidth = hudFont.getStringWidth(moduleName);
                float xPos = posX;
                float textY = getHudTextY(yPos, textTopOffset, textTopPadding);
                if (alignRight.isToggled()) {
                    xPos -= moduleWidth;
                }
                double backgroundLeft = xPos - horizontalTextPadding;
                double backgroundRight = xPos + moduleWidth + horizontalTextPadding;
                double rowCenterX = (backgroundLeft + backgroundRight) * 0.5;
                double wavePhase = hudWavePhase(verticalWaveAccum, rowCenterX);
                int color = getHudColor(wavePhase);
                hudFont.drawString(moduleName, xPos, textY, color, false);
                if (hudWaveIsVertical()) {
                    verticalWaveAccum += getVerticalWaveStep();
                }
                yPos += rowHeight;
            }
        }
        finally {
            GlyphBatch.end();
        }
        int baseColor = getHudColor(0.0);
        int r = (baseColor >> 16) & 0xFF;
        int g = (baseColor >> 8) & 0xFF;
        int b = baseColor & 0xFF;
        HudGlowHelper.endAndComposite(6.0f, 1.0f, r, g, b);
    }

    private static void drawTextSegment(RavenFontRenderer hudFont, String text, float xPos, float textY,
                                        int color, boolean shadow) {
        if (!shouldUseHorizontalWaveText()) {
            hudFont.drawString(text, xPos, textY, color, shadow);
            return;
        }

        hudFont.drawGlyphString(text, xPos, textY, (character, xOffset, width, formattingColor) -> {
            if (formattingColor != null) {
                return formattingColor;
            }
            return getHudColor(hudWavePhase(0.0, xPos + xOffset + width * 0.5f));
        }, shadow);
    }

    private static int getShadowAlpha() {
        double percent = shadowOpacity == null ? 100.0 : shadowOpacity.getInput();
        return Math.max(0, Math.min(255, (int) Math.round(percent * 2.55)));
    }

    private static boolean shouldUseHorizontalWaveText() {
        return colorMode != null && (int) colorMode.getInput() != 0 && !hudWaveIsVertical();
    }

    private static double getVerticalWaveStep() {
        return (12.0 / getWaveLengthMultiplier()) * getVerticalWaveDirectionSign();
    }

    private static int getVerticalWaveDirectionSign() {
        return verticalWaveDirection == null || (int) verticalWaveDirection.getInput() == 0 ? -1 : 1;
    }

    private static int getHorizontalWaveDirectionSign() {
        return horizontalWaveDirection == null || (int) horizontalWaveDirection.getInput() == 0 ? -1 : 1;
    }

    /**
     * Accent color for HUD rows/outlines. Other modules can match HUD when enabled.
     */
    public static int getHudColor(double gradientOffset) {
        if (useTheme == null || useTheme.isToggled()) {
            return mindless.module.impl.theme.ThemeManager.getArrayListColor(gradientOffset);
        }

        switch (colorMode == null ? 0 : (int) colorMode.getInput()) {
            case 1:
                return getGradientWaveColor(colorOf(hudColor, Color.WHITE),
                        colorOf(hudColor2, Color.WHITE), gradientOffset);
            case 2:
                return getRainbowWaveColor(gradientOffset);
            default:
                return hudColor == null ? 0xFFFFFFFF : (hudColor.getRGB() | 0xFF000000);
        }
    }

    private static Color colorOf(ColorSetting setting, Color fallback) {
        return setting == null ? fallback : new Color(setting.getRed(), setting.getGreen(), setting.getBlue());
    }

    private static int getGradientWaveColor(java.awt.Color c1, java.awt.Color c2, double gradientOffset) {
        double animationProgress = (Math.sin(getAnimatedWaveAngle(gradientOffset)) + 1.0) * 0.5;
        return Theme.convert(c1, c2, animationProgress).getRGB() | 0xFF000000;
    }

    private static int getRainbowWaveColor(double gradientOffset) {
        double hue = getAnimatedWaveAngle(gradientOffset) / (Math.PI * 2.0);
        hue -= Math.floor(hue);
        return Color.getHSBColor((float) hue, 1.0F, 1.0F).getRGB() | 0xFF000000;
    }

    private static double getAnimatedWaveAngle(double gradientOffset) {
        return System.currentTimeMillis() / (double) HUD_RAINBOW_PERIOD_MS * (Math.PI * 2.0) * getWaveSpeedMultiplier()
                + gradientOffset * HUD_WAVE_ANGLE_SCALE;
    }

    private static double getWaveSpeedMultiplier() {
        return waveSpeed == null ? 1.0 : Math.max(0.1, waveSpeed.getInput());
    }

    private static double getWaveLengthMultiplier() {
        return waveLength == null ? 1.0 : Math.max(0.5, waveLength.getInput());
    }

}
