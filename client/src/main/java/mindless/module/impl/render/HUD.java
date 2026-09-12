package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.HudEditor;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.font.GlyphBatch;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Theme;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
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
    public static ButtonSetting NoSpaces;
    private static ButtonSetting drawBackground;
    private static SliderSetting backgroundMode;
    private static ButtonSetting roundedBackground;
    private static SliderSetting cornerRadius;
    private static SliderSetting stepRounding;
    private static ButtonSetting rowSeparators;
    private static ColorSetting separatorColor;
    private static SliderSetting backgroundOpacity;
    private static ButtonSetting backgroundBlur;
    private static SliderSetting blurStrength;
    private static SliderSetting blurPasses;
    private static SliderSetting blurOpacity;
    private static ColorSetting backgroundTint;
    private static ButtonSetting backgroundBorder;
    private static ColorSetting borderColor;
    private static ButtonSetting textShadow;
    private static SliderSetting shadowStyle;
    private static SliderSetting shadowOpacity;
    private static SliderSetting lineSpacing;
    private static ButtonSetting alignRight;
    private static ButtonSetting lowercase;
    public static ButtonSetting showInfo;
    public static SliderSetting scoreboardPosX;
    public static SliderSetting scoreboardPosY;
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
private static final float[][] SOFT_SHADOW_TAPS = {
            { 0.55f, 0.55f, 0.30f },
            { 1.00f, 1.00f, 0.50f },
            { 1.45f, 1.45f, 0.26f }
    };
    private static final String[] INFO_SEPARATORS = new String[] { "Space", "Brackets", "Dash" };
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
        super("Array List", "Shows enabled modules on screen.", Module.category.render);
        this.registerSetting(new DescriptionSetting("Array list"));
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
        this.registerSetting(alignRight = new ButtonSetting("Align right", false));
        this.registerSetting(alphabeticalSort = new ButtonSetting("Alphabetical sort", false));
        this.registerSetting(lineSpacing = new SliderSetting("Line spacing", 0.0, -2.0, 8.0, 0.5));
        this.registerSetting(drawBackground = new ButtonSetting("Draw background", false));
        this.registerSetting(backgroundMode = new SliderSetting("Background mode", 0, BACKGROUND_MODES));
        this.registerSetting(roundedBackground = new ButtonSetting("Rounded background", false));
        this.registerSetting(cornerRadius = new SliderSetting("Corner radius", 4.0, 0.0, 20.0, 0.5));
        this.registerSetting(stepRounding = new SliderSetting("Step rounding", "%", 100.0, 0.0, 100.0, 5.0));
        this.registerSetting(rowSeparators = new ButtonSetting("Row separators", true));
        this.registerSetting(separatorColor = new ColorSetting("Separator color", 255, 255, 255, 38));
        this.registerSetting(backgroundOpacity = new SliderSetting("Background opacity", 43.0, 0.0, 100.0, 1.0));
        this.registerSetting(backgroundBlur = new ButtonSetting("Background blur", false));
        this.registerSetting(blurStrength = new SliderSetting("Blur strength", 4.0, 0.5, 16.0, 0.5));
        this.registerSetting(blurPasses = new SliderSetting("Blur passes", 2, 1, 5, 1));
        this.registerSetting(blurOpacity = new SliderSetting("Blur opacity", "%", 85, 10, 100, 1));
        this.registerSetting(backgroundTint = new ColorSetting("Background tint", 0, 0, 0));
        this.registerSetting(backgroundBorder = new ButtonSetting("Background border", false));
        this.registerSetting(borderColor = new ColorSetting("Border color", 255, 255, 255, 40));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(shadowStyle = new SliderSetting("Shadow style", 0, SHADOW_STYLES));
        this.registerSetting(shadowOpacity = new SliderSetting("Shadow opacity", 100.0, 5.0, 100.0, 5.0));
        this.registerSetting(lowercase = new ButtonSetting("Lowercase", false));
        this.registerSetting(NoSpaces = new ButtonSetting("No spaces", false));
        this.registerSetting(showInfo = new ButtonSetting("Show module info", true));
        this.registerSetting(infoSeparator = new SliderSetting("Info separator", 0, INFO_SEPARATORS));
        this.registerSetting(infoMatchName = new ButtonSetting("Info matches name color", false));
        this.registerSetting(infoColor = new ColorSetting("Info color", 170, 170, 170));
        this.registerSetting(new DescriptionSetting("HUD layout"));
        this.registerSetting(new ButtonSetting("Edit HUD elements", () -> mc.displayGuiScreen(new HudEditor.Screen())));
        this.registerSetting(scoreboardPosX = new SliderSetting("Scoreboard position X", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(scoreboardPosY = new SliderSetting("Scoreboard position Y", 0.0, -1.0, 1.0, 0.001));
        scoreboardPosX.visible = false;
        scoreboardPosY.visible = false;
        scoreboardPosX.setValueRaw(-1.0D);
        scoreboardPosY.setValueRaw(-1.0D);
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
        boolean connected = background && backgroundMode != null && (int) backgroundMode.getInput() == 0;
        if (rowSeparators != null) {
            rowSeparators.setVisible(connected, this);
        }
        if (separatorColor != null) {
            separatorColor.setVisible(connected && rowSeparators != null && rowSeparators.isToggled(), this);
        }
        if (stepRounding != null) {
            stepRounding.setVisible(background
                    && roundedBackground != null && roundedBackground.isToggled()
                    && backgroundMode != null && (int) backgroundMode.getInput() == 0, this);
        }
        if (backgroundOpacity != null) {
            backgroundOpacity.setVisible(background && !(backgroundBlur != null && backgroundBlur.isToggled()), this);
        }
        boolean blurring = background && backgroundBlur != null && backgroundBlur.isToggled();
        if (blurStrength != null) blurStrength.setVisible(blurring, this);
        if (blurPasses != null) blurPasses.setVisible(blurring, this);
        if (blurOpacity != null) blurOpacity.setVisible(blurring, this);
        if (backgroundTint != null) backgroundTint.setVisible(background && !blurring, this);
        if (backgroundBorder != null) backgroundBorder.setVisible(background, this);
        if (borderColor != null) {
            borderColor.setVisible(background && backgroundBorder != null && backgroundBorder.isToggled(), this);
        }
        if (backgroundBlur != null) {
            backgroundBlur.setVisible(background, this);
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
    public void onDisable() {
    }

    public static boolean hasCustomScoreboardPosition() {
        return scoreboardPosX != null && scoreboardPosY != null
                && scoreboardPosX.getInput() >= 0.0D && scoreboardPosY.getInput() >= 0.0D;
    }

    public static float getScoreboardX(float width, ScaledResolution resolution, float defaultX) {
        if (!hasCustomScoreboardPosition()) return defaultX;
        return (float) (Math.max(0.0F, resolution.getScaledWidth() - width) * scoreboardPosX.getInput());
    }

    public static float getScoreboardY(float height, ScaledResolution resolution, float defaultY) {
        if (!hasCustomScoreboardPosition()) return defaultY;
        return (float) (Math.max(0.0F, resolution.getScaledHeight() - height) * scoreboardPosY.getInput());
    }

    public static void setScoreboardPosition(float x, float y, float width, float height, ScaledResolution resolution) {
        if (scoreboardPosX == null || scoreboardPosY == null || resolution == null) return;
        float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
        float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
        float clampedX = Math.max(0.0F, Math.min(maxX, x));
        float clampedY = Math.max(0.0F, Math.min(maxY, y));
        scoreboardPosX.setValueRaw(maxX <= 0.0F ? 0.0D : clampedX / maxX);
        scoreboardPosY.setValueRaw(maxY <= 0.0F ? 0.0D : clampedY / maxY);
    }

    public static void resetScoreboardPosition() {
        if (scoreboardPosX != null) scoreboardPosX.setValueRaw(-1.0D);
        if (scoreboardPosY != null) scoreboardPosY.setValueRaw(-1.0D);
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
        // Every HUD element under this draws text. Establish the contract once here rather than
        // trusting whatever the frame left behind; that inheritance is what rendered them grey.
        mindless.utility.RenderUtils.beginTextPass();
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

        MindlessFontRenderer hudFont = getHudFontRenderer();
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
        if (drawBackground.isToggled()) {
            drawArrayListBackground(collectRowWidths(hudFont),
                    posY, horizontalTextPadding, rowHeight);
        }

        try {
            for (Module module : ModuleManager.organizedModules) {
                if (!module.isEnabled() || module == this || shouldSkipModule(module)) {
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
        MindlessFontRenderer hudFont = getHudFontRenderer();
        int length = 0;

        for (Module module : ModuleManager.organizedModules) {
            if (module.isEnabled()) {
                length = Math.max(length, hudFont.getStringWidth(getHudRenderText(module)));
            }
        }

        return length;
    }

    public static float[] renderDesignerPreview() {
        MindlessFontRenderer font = getHudFontRenderer();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Module module : ModuleManager.organizedModules) {
            if (module.isEnabled() && !(module instanceof HUD) && !shouldSkipModule(module)) {
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
public static java.util.List<PickerRow> renderHidePicker(int mouseX, int mouseY) {
        MindlessFontRenderer font = getHudFontRenderer();
        java.util.List<Module> shown = new java.util.ArrayList<Module>();
        for (Module module : ModuleManager.organizedModules) {
            if (!module.isEnabled() || module instanceof HUD) continue;
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
        MindlessFontRenderer font = getHudFontRenderer();
        int width = getDesignerPreviewWidth(font);
        setAbsolutePosition(alignRight != null && alignRight.isToggled() ? left + width : left, top);
    }

    private static int getDesignerPreviewWidth(MindlessFontRenderer font) {
        int width = 0;
        for (Module module : ModuleManager.organizedModules) {
            if (module.isEnabled() && !(module instanceof HUD)
                    && !shouldSkipModule(module)) {
                width = Math.max(width, font.getStringWidth(getHudRenderText(module)));
            }
        }
        if (width == 0) {
            width = Math.max(font.getStringWidth("Kill Aura"),
                    Math.max(font.getStringWidth("Player ESP"), font.getStringWidth("Sprint")));
        }
        return width;
    }

    private static boolean shouldSkipModule(Module module) {
        return module.isHidden();
    }

    private static boolean isLastVisibleModule(Module currentModule) {
        boolean foundCurrent = false;

        for (Module module : ModuleManager.organizedModules) {
            if (!foundCurrent) {
                if (module == currentModule) {
                    foundCurrent = true;
                }
                continue;
            }

            if (module.isEnabled() && !(module instanceof HUD) && !shouldSkipModule(module)) {
                return false;
            }
        }

        return true;
    }


    public static MindlessFontRenderer getHudFontRenderer() {
        return FontManager.getHudRenderer(getSelectedFontName(), getSelectedFontScale());
    }

    public static String getHudText(Module module) {
        String moduleName = module.getNameInHud();
        if (lowercase != null && lowercase.isToggled()) {
            moduleName = moduleName.toLowerCase();
        }

        if (NoSpaces != null && NoSpaces.isToggled()) {
            // high iq
            moduleName = moduleName.replace(" ", "");
        }

        return moduleName;
    }

    // !!!
    public static String getHudRenderText(Module module) {
        return getHudText(module) + getHudInfoText(module);
    }

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
private static int getHudInfoColor(int nameColor) {
        if (infoMatchName != null && infoMatchName.isToggled()) {
            return nameColor;
        }
        if (infoColor == null) {
            return 0xFFAAAAAA;
        }
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
        int padding = getScaledHudPixels(2.0f);
        // A rounded corner eats into the row from both ends, so two pixels put the first
        // and last glyph inside the curve. Give the text enough room to clear it.
        if (roundedBackground != null && roundedBackground.isToggled()) {
            float radius = (float) (cornerRadius == null ? 4.0 : cornerRadius.getInput())
                    * mindless.module.impl.theme.ThemeManager.roundingScale();
            padding = Math.max(padding, Math.round(radius * 0.7f));
        }
        return padding;
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
private static int[] collectRowWidths(MindlessFontRenderer hudFont) {
        java.util.List<Integer> widths = new java.util.ArrayList<Integer>();
        for (Module module : ModuleManager.organizedModules) {
            if (!module.isEnabled() || module instanceof HUD || shouldSkipModule(module)) {
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

    /**
     * Blur, tint and border all trace the same silhouette.
     *
     * The blur used to be panel-only, which meant the one mode where the list has an interesting
     * outline -- Connected, with its stepped right edge -- got a flat rectangle or nothing at all.
     * BlurUtils masks by whatever you draw between prepare and end, so the stepped rows go into
     * the mask exactly as they are painted and the blur comes back cut to that shape.
     */
    private static void drawArrayListBackground(int[] widths, float top, int horizontalTextPadding, int rowHeight) {
        if (widths.length == 0) {
            return;
        }

        float[] bounds = backgroundBounds(widths, top, horizontalTextPadding, rowHeight);

        boolean blurring = backgroundBlur != null && backgroundBlur.isToggled();

        if (blurring) {
            BlurUtils.prepareBlur(bounds[0], bounds[1], bounds[2], bounds[3]);
            paintBackgroundShapes(widths, top, horizontalTextPadding, rowHeight, 0xFF000000, 0.0f);
            BlurUtils.blurEndRegion(
                    blurPasses == null ? 2 : (int) blurPasses.getInput(),
                    blurStrength == null ? 4.0f : (float) blurStrength.getInput(),
                    blurOpacity == null ? 0.85f : (float) (blurOpacity.getInput() / 100.0),
                    bounds[0] - 2.0f, bounds[1] - 2.0f, bounds[2] + 4.0f, bounds[3] + 4.0f);
        }

        // Painting the shapes one pixel proud in the border colour before the fill gives the
        // stepped edge a hairline for free, which drawing an outline per row could not do without
        // seams where two rows of different widths meet.
        if (backgroundBorder != null && backgroundBorder.isToggled() && borderColor != null) {
            paintBackgroundShapes(widths, top, horizontalTextPadding, rowHeight,
                    borderColor.getColor(), 1.0f);
        }

        // The blur *is* the background. Painting the tint over it as well just put the old black
        // panel back on top of the thing it was meant to replace, which is why turning blur on
        // looked like it did nothing.
        if (blurring) {
            paintRowSeparators(widths, top, horizontalTextPadding, rowHeight);
            return;
        }

        int alpha = getBackgroundAlpha();
        if (alpha > 0) {
            int tint = backgroundTint == null ? 0 : backgroundTint.getRGB();
            paintBackgroundShapes(widths, top, horizontalTextPadding, rowHeight,
                    (alpha << 24) | tint, 0.0f);
        }
        paintRowSeparators(widths, top, horizontalTextPadding, rowHeight);
    }

    /**
     * A hairline where two rows meet.
     *
     * Connected mode is deliberately one silhouette, which is what makes it connected, but
     * with nothing between the rows a long list reads as a single slab. The line spans only
     * the width the two neighbours share, so it never pokes out past the staircase.
     */
    private static void paintRowSeparators(int[] widths, float top, int horizontalTextPadding,
                                           int rowHeight) {
        if (rowSeparators == null || !rowSeparators.isToggled() || getBackgroundMode() != 0) {
            return;
        }
        if (widths.length < 2 || separatorColor == null) {
            return;
        }
        int color = separatorColor.getColor();
        if ((color >>> 24) == 0) {
            return;
        }
        boolean right = alignRight.isToggled();
        for (int i = 0; i + 1 < widths.length; i++) {
            float shared = Math.min(widths[i], widths[i + 1]) + horizontalTextPadding * 2f;
            float left = right ? posX + horizontalTextPadding - shared : posX - horizontalTextPadding;
            float y = top + (i + 1) * rowHeight;
            RenderUtils.drawRect(left, y - 0.5f, left + shared, y + 0.5f, color);
        }
    }

    /** {left, top, width, height} of the whole list, whichever way it is aligned. */
    private static float[] backgroundBounds(int[] widths, float top, int horizontalTextPadding, int rowHeight) {
        int maxWidth = 0;
        for (int width : widths) {
            maxWidth = Math.max(maxWidth, width);
        }
        float left = alignRight.isToggled()
                ? posX - maxWidth - horizontalTextPadding
                : posX - horizontalTextPadding;
        return new float[]{left, top, maxWidth + horizontalTextPadding * 2f, widths.length * (float) rowHeight};
    }

    /** Draws the chosen background shape in one colour; grow expands it for the border pass. */
    private static void paintBackgroundShapes(int[] widths, float top, int horizontalTextPadding,
                                              int rowHeight, int color, float grow) {
        int mode = getBackgroundMode();

        if (mode == 2) {
            float[] bounds = backgroundBounds(widths, top, horizontalTextPadding, rowHeight);
            float radius = getBackgroundRadius(Math.min(bounds[2], bounds[3]));
            paintRoundedRect(bounds[0] - grow, bounds[1] - grow,
                    bounds[2] + grow * 2f, bounds[3] + grow * 2f, radius, color);
            return;
        }

        if (mode == 1) {
            for (int i = 0; i < widths.length; i++) {
                float width = widths[i] + horizontalTextPadding * 2f;
                float left = alignRight.isToggled()
                        ? posX - widths[i] - horizontalTextPadding
                        : posX - horizontalTextPadding;
                float radius = getBackgroundRadius(Math.min(width, rowHeight));
                paintRoundedRect(left - grow, top + i * rowHeight - grow,
                        width + grow * 2f, rowHeight + grow * 2f, radius, color);
            }
            return;
        }

        boolean right = alignRight.isToggled();
        float narrowest = Float.MAX_VALUE;
        for (int i = 0; i < widths.length; i++) {
            float rowWidth = widths[i] + horizontalTextPadding * 2f;
            if (rowWidth < narrowest) narrowest = rowWidth;
        }
        if (narrowest == Float.MAX_VALUE) narrowest = rowHeight;
        // The outer corners get the full radius; the staircase steps in the middle stay
        // inside half a row, or two of them meet and eat the join between the rows.
        float radius = getOuterBackgroundRadius(rowHeight, narrowest);
        float transitionRadius = Math.min(getBackgroundStepRadius(radius), rowHeight * 0.5f);
        // The whole staircase is emitted into one batch and blended once. Drawing each row as
        // its own translucent shape meant every shared edge was blended twice, which outlined
        // each row and made the list read as separate chips instead of one connected panel.
        beginRowBatch();
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        for (int i = 0; i < widths.length; i++) {
            float width = widths[i] + horizontalTextPadding * 2f;
            float left = right ? posX - widths[i] - horizontalTextPadding : posX - horizontalTextPadding;
            float rowTop = top + i * rowHeight;
            boolean firstRow = i == 0;
            boolean lastRow = i == widths.length - 1;
            float roundedRadius = radius <= 0.0f ? 0.0f : radius + grow;
            float roundedTransition = transitionRadius <= 0.0f ? 0.0f : transitionRadius + grow;

            // A width change is one convex corner and one reflex one, never two convex ones.
            // Where the neighbour is the wider row the outline wraps around the join, and a
            // quarter disc there removes material instead of rounding it: it bites a notch out
            // of the silhouette. Rounding both sides put one of those notches at every step,
            // alternating which row it came out of, which is what made the staircase look chewed
            // rather than rounded.
            //
            // So the wider row keeps the quarter disc and the narrower row gets an inverse
            // fillet, the corner square minus the disc, filling the crook the way a connected
            // list is meant to read. Both are capped at the step itself; a fillet deeper than
            // the width change would reach past the neighbour and poke out of the shape.
            float stepAbove = firstRow ? 0.0f : widths[i] - widths[i - 1];
            float stepBelow = lastRow ? 0.0f : widths[i] - widths[i + 1];
            float raggedTop = firstRow ? roundedRadius
                    : stepAbove > 1.0f ? Math.min(roundedTransition, stepAbove) : 0.0f;
            float raggedBottom = lastRow ? roundedRadius
                    : stepBelow > 1.0f ? Math.min(roundedTransition, stepBelow) : 0.0f;
            float filletTop = stepAbove < -1.0f ? Math.min(roundedTransition, -stepAbove) : 0.0f;
            float filletBottom = stepBelow < -1.0f ? Math.min(roundedTransition, -stepBelow) : 0.0f;

            // The aligned edge is only rounded at the very top and bottom, because everything
            // between is one continuous side -- that is what keeps the list a single shape
            // rather than a stack of separate ones.
            float alignedTop = firstRow ? roundedRadius : 0.0f;
            float alignedBottom = lastRow ? roundedRadius : 0.0f;
            float topLeft = right ? raggedTop : alignedTop;
            float bottomLeft = right ? raggedBottom : alignedBottom;
            float topRight = right ? alignedTop : raggedTop;
            float bottomRight = right ? alignedBottom : raggedBottom;

            // Only the outer edges grow; growing the shared horizontal seams would draw the
            // border straight through the middle of the list.
            float growTop = firstRow ? grow : 0.0f;
            float growBottom = lastRow ? grow : 0.0f;
            float x1 = left - grow;
            float x2 = left + width + grow;
            float y1 = rowTop - growTop;
            float y2 = rowTop + rowHeight + growBottom;
            // Rows abut exactly. They must not overlap: batching removes the draw call, not the
            // blending, so a translucent row lapping half a pixel over the next one is still
            // composited twice there and draws a darker line across every seam -- which is the
            // banding this was meant to avoid. Two abutting triangles cannot leave a gap either;
            // the rasteriser fill rule gives each pixel to exactly one of them.
            fillRow(x1, y1, x2, y2, topLeft, topRight, bottomRight, bottomLeft,
                    color, firstRow, lastRow);

            // A fillet sits beyond the ragged edge but inside this row's own band, so it meets
            // the neighbour without ever lying on top of it.
            float raggedX = right ? x1 : x2;
            float side = right ? -1.0f : 1.0f;
            emitInverseCorner(wr, raggedX, y1, filletTop, side, 1.0f, color);
            emitInverseCorner(wr, raggedX, y2, filletBottom, side, -1.0f, color);
        }
        endRowBatch();
    }

    /**
     * The crook of a reflex corner: the corner square with a quarter disc taken out of it.
     *
     * Drawn as a fan about the corner point, which lies outside the arc, so the piece emitted is
     * exactly the material a convex corner would have wrongly removed. Left unfeathered on
     * purpose: the arc runs tangent to both rows at its ends, and a ramp there would fade out
     * over the neighbouring row's fill and blend it a second time.
     */
    private static void emitInverseCorner(WorldRenderer wr, float px, float py, float radius,
                                          float sx, float sy, int color) {
        if (radius <= 0.0f) {
            return;
        }
        int a = (color >>> 24) & 0xFF;
        if (a <= 0) {
            return;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        int steps = Math.max(4, Math.min(CORNER_STEPS, Math.round(radius * 3.0f)));
        float cx = px + sx * radius;
        float cy = py + sy * radius;
        for (int i = 0; i < steps; i++) {
            double t0 = Math.toRadians(90.0 * i / steps);
            double t1 = Math.toRadians(90.0 * (i + 1) / steps);
            wr.pos(px, py, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(cx - sx * radius * Math.cos(t0), cy - sy * radius * Math.sin(t0), 0.0D)
                    .color(r, g, b, a).endVertex();
            wr.pos(cx - sx * radius * Math.cos(t1), cy - sy * radius * Math.sin(t1), 0.0D)
                    .color(r, g, b, a).endVertex();
        }
    }

    private static void paintRoundedRect(float left, float top, float width, float height,
                                         float radius, int color) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        if (radius <= 0.0f) {
            RenderUtils.drawRect(left, top, left + width, top + height, color);
        }
        else {
            RoundedUtils.drawRound(left, top, width, height, radius, new Color(color, true));
        }
    }

private static void beginRowBatch() {
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        Tessellator.getInstance().getWorldRenderer()
                .begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
    }

    private static void endRowBatch() {
        Tessellator.getInstance().draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void emitQuad(WorldRenderer wr, float x1, float y1, float x2, float y2,
                                 int r, int g, int b, int a) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        wr.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();

        wr.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, 0.0D).color(r, g, b, a).endVertex();
    }

    /**
     * One quarter disc, at the three degree step the rounded rect reference uses, optionally with
     * a feathered ring outside it.
     *
     * Eight segments faceted visibly past a two pixel radius, and a hard arc edge aliases into a
     * staircase of its own, which is what made the rounded corners read as chewed rather than
     * round. The ring fades to zero alpha over CORNER_FEATHER pixels and gives the diagonal the
     * coverage ramp the axis aligned edges never need.
     *
     * Only the corners on the outside of the whole list may be feathered. A ring drawn at a width
     * change would fade out over the neighbouring row's fill, and blending it there a second time
     * draws exactly the arc shaped seam the single batch exists to avoid.
     */
    private static void emitCorner(WorldRenderer wr, float cx, float cy, float radius,
                                   float startDeg, int r, int g, int b, int a, boolean feather) {
        if (radius <= 0.0f) {
            return;
        }
        float outer = radius + CORNER_FEATHER;
        // Three degree steps at every radius is the reference's figure and costs ten thousand
        // vertex writes a frame on a long list. Stepping per pixel of arc instead holds the same
        // silhouette: a four pixel corner cannot show more than a dozen facets anyway.
        int steps = Math.max(4, Math.min(CORNER_STEPS, Math.round(radius * 3.0f)));
        for (int i = 0; i < steps; i++) {
            double t0 = Math.toRadians(startDeg + 90.0 * i / steps);
            double t1 = Math.toRadians(startDeg + 90.0 * (i + 1) / steps);
            float sin0 = (float) Math.sin(t0);
            float cos0 = (float) Math.cos(t0);
            float sin1 = (float) Math.sin(t1);
            float cos1 = (float) Math.cos(t1);

            wr.pos(cx, cy, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(cx + sin0 * radius, cy - cos0 * radius, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(cx + sin1 * radius, cy - cos1 * radius, 0.0D).color(r, g, b, a).endVertex();

            if (!feather) {
                continue;
            }
            wr.pos(cx + sin0 * radius, cy - cos0 * radius, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(cx + sin0 * outer, cy - cos0 * outer, 0.0D).color(r, g, b, 0).endVertex();
            wr.pos(cx + sin1 * outer, cy - cos1 * outer, 0.0D).color(r, g, b, 0).endVertex();

            wr.pos(cx + sin0 * radius, cy - cos0 * radius, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(cx + sin1 * outer, cy - cos1 * outer, 0.0D).color(r, g, b, 0).endVertex();
            wr.pos(cx + sin1 * radius, cy - cos1 * radius, 0.0D).color(r, g, b, a).endVertex();
        }
    }

    /**
     * One row of the connected array list, emitted into the batch opened by beginRowBatch.
     *
     * Same decomposition as a rounded rect -- three bands plus a fan per rounded corner -- but
     * every piece goes into a single buffer that is blended once at the end. That is the whole
     * point: abutting and slightly overlapping geometry inside one batch costs nothing, whereas
     * as separate draws each seam was composited twice and showed as a seam.
     */
    private static void fillRow(float x1, float y1, float x2, float y2,
                                float topLeft, float topRight, float bottomRight, float bottomLeft,
                                int color, boolean featherTop, boolean featherBottom) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        if (a <= 0) {
            return;
        }

        // Bands may not overlap. The outer radius is deliberately allowed to run a whole row
        // deep, so an end row that also carries a step corner can ask for more rounding than the
        // row is tall, and the two bands would then be composited over each other as a stripe.
        float fit = Math.min(scaleFactor(x2 - x1, topLeft + topRight),
                scaleFactor(x2 - x1, bottomLeft + bottomRight));
        fit = Math.min(fit, Math.min(scaleFactor(y2 - y1, topLeft + bottomLeft),
                scaleFactor(y2 - y1, topRight + bottomRight)));
        if (fit < 1.0f) {
            topLeft *= fit;
            topRight *= fit;
            bottomRight *= fit;
            bottomLeft *= fit;
        }

        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        float topBand = Math.max(topLeft, topRight);
        float bottomBand = Math.max(bottomLeft, bottomRight);

        if (topBand > 0.0f) {
            emitQuad(wr, x1 + topLeft, y1, x2 - topRight, y1 + topBand, r, g, b, a);
        }
        emitQuad(wr, x1, y1 + topBand, x2, y2 - bottomBand, r, g, b, a);
        if (bottomBand > 0.0f) {
            emitQuad(wr, x1 + bottomLeft, y2 - bottomBand, x2 - bottomRight, y2, r, g, b, a);
        }

        emitCorner(wr, x1 + topLeft, y1 + topLeft, topLeft, 270.0f, r, g, b, a, featherTop);
        emitCorner(wr, x2 - topRight, y1 + topRight, topRight, 0.0f, r, g, b, a, featherTop);
        emitCorner(wr, x2 - bottomRight, y2 - bottomRight, bottomRight, 90.0f, r, g, b, a, featherBottom);
        emitCorner(wr, x1 + bottomLeft, y2 - bottomLeft, bottomLeft, 180.0f, r, g, b, a, featherBottom);
    }

    /** How far a pair of corner radii has to be pulled in to fit the side they share. */
    private static float scaleFactor(float available, float requested) {
        return requested <= available || requested <= 0.0f ? 1.0f : available / requested;
    }

private static final float CORNER_FEATHER = 0.6f;
    private static final int CORNER_STEPS = 30;

    private static int getBackgroundAlpha() {
        return Math.max(0, Math.min(255,
                (int) Math.round((backgroundOpacity == null ? 43.0 : backgroundOpacity.getInput()) * 2.55)));
    }

    /** What the slider asks for, before anything geometric is allowed to cut it down. */
    private static float configuredBackgroundRadius() {
        if (roundedBackground == null || !roundedBackground.isToggled()) {
            return 0.0f;
        }
        return (float) (cornerRadius == null ? 4.0 : cornerRadius.getInput())
                * mindless.module.impl.theme.ThemeManager.roundingScale();
    }

    private static float getBackgroundRadius(float height) {
        // Half the height is a full pill. The old third of it meant a row eleven pixels
        // tall could never round by more than three, whatever the slider was set to.
        return Math.max(0.0f, Math.min(configuredBackgroundRadius(), height * 0.5f));
    }

    /**
     * The radius for the four corners on the outside of a stack of rows.
     *
     * Bounded by the whole row height rather than half of it. A corner at the very top of
     * a stack has the rows beneath it to fill in behind, so it can be as deep as the row is
     * tall; halving it is a single-row rule that was being applied where it did not belong.
     * On a list thirty rows long those four corners are the only rounding anyone can see,
     * and capping them at three pixels is why turning the slider up did nothing.
     */
    private static float getOuterBackgroundRadius(float rowHeight, float narrowestWidth) {
        float radius = configuredBackgroundRadius();
        if (radius <= 0.0f) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(radius, Math.min(rowHeight, narrowestWidth * 0.5f)));
    }

    private static float getBackgroundStepRadius(float outerRadius) {
        if (outerRadius <= 0.0f) {
            return 0.0f;
        }
        float amount = stepRounding == null ? 0.55f : (float) (stepRounding.getInput() / 100.0);
        return outerRadius * Math.max(0.0f, Math.min(1.0f, amount));
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
private static void drawHudRow(MindlessFontRenderer hudFont, Module module, float xPos, float textY, int color) {
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

    private static void drawHudText(MindlessFontRenderer hudFont, String moduleName, float xPos, float textY, int fallbackColor) {
        GlyphBatch.begin();
        try {
            drawDecoration(hudFont, moduleName, xPos, textY, fallbackColor);
            drawTextSegment(hudFont, moduleName, xPos, textY, fallbackColor, false);
        }
        finally {
            GlyphBatch.end();
        }
    }
private static void drawDecoration(MindlessFontRenderer hudFont, String text, float xPos, float textY, int color) {
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
    private static void drawTextSegment(MindlessFontRenderer hudFont, String text, float xPos, float textY,
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
