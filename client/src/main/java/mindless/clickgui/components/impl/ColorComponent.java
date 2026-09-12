package mindless.clickgui.components.impl;

import mindless.Mindless;
import mindless.clickgui.components.Component;
import mindless.module.impl.client.Gui;
import mindless.module.setting.impl.ColorSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Timer;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public class ColorComponent extends Component {
    public ColorSetting colorSetting;
    private ModuleComponent moduleComponent;
    public float o;
    public float x;
    private float y;
    public float xOffset;
    public boolean expanded;
    private int dragMode;
    private float cachedHue;
    private float cachedSat;
    private float cachedBri;

    private Timer smoothTimer;
    private float animationProgress;
    private float animationStartProgress;
    private float animationTargetProgress;
    private static final float ANIMATION_DURATION = 250f;

    private static final float LABEL_HEIGHT = 12f;
    private static final float SQUARE_SIZE = 62f;
    private static final float BAR_WIDTH = 7f;
    private static final float HUE_GAP = 6f;
    private static final float ALPHA_GAP = 5f;
    private static final float BLACK_BRI_EPSILON = 0.001f;
    private static final float GREY_SAT_EPSILON = 0.001f;
    private static final float SQUARE_TOP_PAD = 3f;
    private static final float SWATCH_GAP = 5f;
    private static final float SWATCH_SIZE = 8f;
    private static final float SWATCH_SPACING = 2f;
    private static final float HEX_GAP = 4f;
    private static final float HEX_HEIGHT = 7f;
    private static final float BOTTOM_PAD = 3f;
    private static final float PREVIEW_BOX_SIZE = 5f;

    private static final int BORDER = 0xFF23252C;
    private static final int PANEL = 0xFF1A1C21;
private static final int[] HUE_CORNERS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
    };

    private static final int[] SWATCHES = {
            0xFFFFFFFF, 0xFF9AA0AC, 0xFF2B2F36, 0xFFE8455F,
            0xFFF5A623, 0xFF4BD07E, 0xFF3FA9F5, 0xFFA94DFF
    };

    public ColorComponent(ColorSetting colorSetting, ModuleComponent moduleComponent, float o) {
        this.colorSetting = colorSetting;
        this.moduleComponent = moduleComponent;
        this.o = o;
        this.animationProgress = 0f;
        this.animationStartProgress = 0f;
        this.animationTargetProgress = 0f;
    }

    public float getExpandedHeight() {
        return LABEL_HEIGHT + SQUARE_TOP_PAD + SQUARE_SIZE
                + SWATCH_GAP + SWATCH_SIZE + HEX_GAP + HEX_HEIGHT + BOTTOM_PAD;
    }

    public float getAnimationProgress() {
        if (smoothTimer != null) {
            if (System.currentTimeMillis() - smoothTimer.last >= ANIMATION_DURATION + 30) {
                smoothTimer = null;
                animationProgress = animationTargetProgress;
                animationStartProgress = animationTargetProgress;
            } else {
                animationProgress = smoothTimer.getValueFloat(animationStartProgress, animationTargetProgress, 1);
                if (animationProgress == animationTargetProgress) {
                    smoothTimer = null;
                    animationStartProgress = animationTargetProgress;
                }
            }
        }
        return animationProgress;
    }

    @Override
    public void render() {
        float cx = moduleComponent.categoryComponent.getX();
        float cy = moduleComponent.categoryComponent.getY();
        float cw = moduleComponent.categoryComponent.getWidth();

        float boxX = cx + 4 + (xOffset / 2);
        float boxY = cy + o + 3f;
        RenderUtils.drawRect(boxX - 0.5, boxY - 0.5,
                boxX + PREVIEW_BOX_SIZE + 0.5, boxY + PREVIEW_BOX_SIZE + 0.5, 0xFF3C3C46);
        if (colorSetting.hasAlpha()) {
            int checkSize = 2;
            for (float px = boxX; px < boxX + PREVIEW_BOX_SIZE; px += checkSize) {
                for (float py = boxY; py < boxY + PREVIEW_BOX_SIZE; py += checkSize) {
                    int col = ((int) ((px - boxX) / checkSize) + (int) ((py - boxY) / checkSize)) % 2 == 0
                            ? 0xFF666666 : 0xFF999999;
                    RenderUtils.drawRect(px, py,
                            Math.min(px + checkSize, boxX + PREVIEW_BOX_SIZE),
                            Math.min(py + checkSize, boxY + PREVIEW_BOX_SIZE), col);
                }
            }
        }
        RenderUtils.drawRect(boxX, boxY,
                boxX + PREVIEW_BOX_SIZE, boxY + PREVIEW_BOX_SIZE,
                colorSetting.getColor());

        MindlessFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        float textOffset = renderer.getStringWidth("[+]  ");
        renderer.drawString(
                colorSetting.getName(),
                (cx + 4) * 2 + xOffset + textOffset,
                (cy + o + 4) * 2,
                -1,
                true
        );
        GL11.glPopMatrix();

        float progress = getAnimationProgress();
        if (progress <= 0f) return;

        float scrollOffset = moduleComponent.categoryComponent.moduleY - cy;
        float contentTopScreen = cy + o + LABEL_HEIGHT + scrollOffset;
        float revealH = (getExpandedHeight() - LABEL_HEIGHT) * progress;
        RenderUtils.scissorPushGui(cx, contentTopScreen, cw, revealH);
        renderPickerContent(cx, cy);
        RenderUtils.scissorPop();
    }

    private void renderPickerContent(float cx, float cy) {
        float areaLeft = cx + 4 + (xOffset / 2);
        float sqTop = cy + o + LABEL_HEIGHT + SQUARE_TOP_PAD;
        float sqRight = areaLeft + SQUARE_SIZE;
        float sqBottom = sqTop + SQUARE_SIZE;

        float bri = (dragMode != 0) ? cachedBri : colorSetting.getBrightness();
        float satFromSetting = (dragMode != 0) ? cachedSat : colorSetting.getSaturation();
        boolean isBlack = bri < BLACK_BRI_EPSILON;
        boolean isGrey = satFromSetting < GREY_SAT_EPSILON;
        if (dragMode == 0 && !isBlack) {
            cachedBri = bri;
            cachedSat = colorSetting.getSaturation();
            if (!isGrey) {
                cachedHue = colorSetting.getHue();
            }
        }
        boolean useCachedHue = dragMode != 0 || isBlack || isGrey;
        float hue = useCachedHue ? cachedHue / 360f : colorSetting.getHue() / 360f;
        float sat = (dragMode != 0 || isBlack) ? cachedSat : satFromSetting;
        int hueRGB = Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000;
        border(areaLeft, sqTop, sqRight, sqBottom);
        RenderUtils.drawRect(areaLeft, sqTop, sqRight, sqBottom, hueRGB);
        RenderUtils.drawHorizontalGradientRect(areaLeft, sqTop, sqRight, sqBottom,
                0xFFFFFFFF, 0x00FFFFFF);
        RenderUtils.drawVerticalGradientRect(areaLeft, sqTop, sqRight, sqBottom,
                0x00000000, 0xFF000000);
        float indX = areaLeft + sat * SQUARE_SIZE;
        float indY = sqTop + (1f - bri) * SQUARE_SIZE;
        ring(indX, indY, 4.1f, 1.0f, 0x66000000);
        ring(indX, indY, 3.2f, 1.4f, 0xFFFFFFFF);
        float hueLeft = sqRight + HUE_GAP;
        float hueRight = hueLeft + BAR_WIDTH;
        border(hueLeft, sqTop, hueRight, sqBottom);
        float segment = SQUARE_SIZE / 6f;
        for (int i = 0; i < 6; i++) {
            RenderUtils.drawVerticalGradientRect(hueLeft, sqTop + i * segment,
                    hueRight, sqTop + (i + 1) * segment, HUE_CORNERS[i], HUE_CORNERS[i + 1]);
        }
        knob(hueLeft, hueRight, sqTop + Math.max(0f, Math.min(1f, hue)) * SQUARE_SIZE,
                Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000);
        if (colorSetting.hasAlpha()) {
            float alphaLeft = hueRight + ALPHA_GAP;
            float alphaRight = alphaLeft + BAR_WIDTH;
            border(alphaLeft, sqTop, alphaRight, sqBottom);
            checkerboard(alphaLeft, sqTop, alphaRight, sqBottom, 3);
            int rgb = colorSetting.getRGB();
            RenderUtils.drawVerticalGradientRect(alphaLeft, sqTop, alphaRight, sqBottom,
                    rgb & 0x00FFFFFF, rgb | 0xFF000000);
            float alphaFrac = colorSetting.getAlpha() / 255f;
            knob(alphaLeft, alphaRight, sqTop + alphaFrac * SQUARE_SIZE,
                    (Math.round(alphaFrac * 255) << 24) | (rgb & 0xFFFFFF));
        }
        float swatchTop = sqBottom + SWATCH_GAP;
        for (int i = 0; i < SWATCHES.length; i++) {
            float left = areaLeft + i * (SWATCH_SIZE + SWATCH_SPACING);
            RenderUtils.drawRect(left - 0.5f, swatchTop - 0.5f,
                    left + SWATCH_SIZE + 0.5f, swatchTop + SWATCH_SIZE + 0.5f, BORDER);
            RenderUtils.drawRect(left, swatchTop, left + SWATCH_SIZE, swatchTop + SWATCH_SIZE,
                    SWATCHES[i]);
        }
        MindlessFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        String hex = String.format("#%06X", colorSetting.getRGB() & 0xFFFFFF);
        if (colorSetting.hasAlpha()) {
            hex = hex + "  " + Math.round(colorSetting.getAlpha() / 2.55f) + "%";
        }
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        renderer.drawString(hex, areaLeft * 2f, (swatchTop + SWATCH_SIZE + HEX_GAP) * 2f,
                0xFF9AA0AC, false);
        GL11.glPopMatrix();
    }
private static void border(float left, float top, float right, float bottom) {
        RenderUtils.drawRect(left - 1, top - 1, right + 1, bottom + 1, BORDER);
        RenderUtils.drawRect(left, top, right, bottom, PANEL);
    }

    private static void checkerboard(float left, float top, float right, float bottom, int cell) {
        for (float px = left; px < right; px += cell) {
            for (float py = top; py < bottom; py += cell) {
                int col = ((int) ((px - left) / cell) + (int) ((py - top) / cell)) % 2 == 0
                        ? 0xFF5A5C63 : 0xFF8B8E96;
                RenderUtils.drawRect(px, py, Math.min(px + cell, right), Math.min(py + cell, bottom), col);
            }
        }
    }
private static void knob(float left, float right, float centerY, int color) {
        float x1 = left - 1.5f, x2 = right + 1.5f;
        float y1 = centerY - 2.0f, y2 = centerY + 2.0f;
        RoundedUtils.drawRound(x1 - 0.6f, y1 - 0.6f, (x2 - x1) + 1.2f, (y2 - y1) + 1.2f,
                2.6f, new Color(0, 0, 0, 150));
        RoundedUtils.drawRound(x1, y1, x2 - x1, y2 - y1, 2.0f, new Color(0xFFFFFFFF, true));
        RoundedUtils.drawRound(x1 + 1.1f, y1 + 1.1f, (x2 - x1) - 2.2f, (y2 - y1) - 2.2f,
                1.0f, new Color(color, true));
    }
private static void ring(float cx, float cy, float radius, float thickness, int color) {
        float inner = radius - thickness * 0.5f;
        float outer = radius + thickness * 0.5f;
        ringBand(cx, cy, inner, 1f, outer, 1f, color);
        ringBand(cx, cy, Math.max(0f, inner - 0.6f), 0f, inner, 1f, color);
        ringBand(cx, cy, outer, 1f, outer + 0.6f, 0f, color);
    }

    private static void ringBand(float cx, float cy, float r0, float a0, float r1, float a1, int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int alpha = (color >>> 24) & 0xFF;
        int c0 = Math.round(alpha * a0), c1 = Math.round(alpha * a1);
        if (c0 <= 0 && c1 <= 0) {
            return;
        }
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
        for (int i = 0; i <= 28; i++) {
            double t = Math.PI * 2 * i / 28.0;
            double sin = Math.sin(t), cos = Math.cos(t);
            worldRenderer.pos(cx + sin * r1, cy - cos * r1, 0.0D).color(r, g, b, c1).endVertex();
            worldRenderer.pos(cx + sin * r0, cy - cos * r0, 0.0D).color(r, g, b, c0).endVertex();
        }
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);

    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        this.y = moduleComponent.categoryComponent.getModuleY() + this.o;
        this.x = moduleComponent.categoryComponent.getX();

        if (dragMode == 0 || getAnimationProgress() < 1f) return;

        float areaLeft = this.x + 4 + (xOffset / 2);
        float sqTop = this.y + LABEL_HEIGHT + SQUARE_TOP_PAD;
        float sqRight = areaLeft + SQUARE_SIZE;
        float sqBottom = sqTop + SQUARE_SIZE;
        float hueLeft = sqRight + HUE_GAP;
        float hueRight = hueLeft + BAR_WIDTH;

        if (dragMode == 1) {
            cachedSat = Math.max(0, Math.min(1, (mouseX - areaLeft) / SQUARE_SIZE));
            cachedBri = Math.max(0, Math.min(1, 1f - (mouseY - sqTop) / SQUARE_SIZE));
            colorSetting.setFromHSB(cachedHue, cachedSat, cachedBri);
            markUnsaved();
        } else if (dragMode == 2) {
            cachedHue = Math.max(0, Math.min(360, (mouseY - sqTop) / SQUARE_SIZE * 360f));
            colorSetting.setFromHSB(cachedHue, cachedSat, cachedBri);
            markUnsaved();
        } else if (dragMode == 3 && colorSetting.hasAlpha()) {
            float a = Math.max(0, Math.min(1, (mouseY - sqTop) / SQUARE_SIZE));
            colorSetting.setAlpha((int) (a * 255));
            markUnsaved();
        }
    }

    @Override
    public boolean onClick(int mouseX, int mouseY, int button) {
        if (!moduleComponent.isOpened || !moduleComponent.isVisible(this)) {
            return false;
        }

        float cw = moduleComponent.categoryComponent.getWidth();

        if (mouseX > this.x && mouseX < this.x + cw
                && mouseY > this.y && mouseY < this.y + LABEL_HEIGHT) {
            if (button == 0 || button == 1) {
                float currentProgress = getAnimationProgress();
                this.animationStartProgress = currentProgress;
                this.expanded = !this.expanded;
                this.animationTargetProgress = this.expanded ? 1f : 0f;
                (this.smoothTimer = new Timer(ANIMATION_DURATION)).start();
                moduleComponent.updateSettingPositions();
                return true;
            }
        }

        if (button != 0) return false;
        if (getAnimationProgress() < 1f) return false;

        float areaLeft = this.x + 4 + (xOffset / 2);
        float sqTop = this.y + LABEL_HEIGHT + SQUARE_TOP_PAD;
        float sqRight = areaLeft + SQUARE_SIZE;
        float sqBottom = sqTop + SQUARE_SIZE;
        float hueLeft = sqRight + HUE_GAP;
        float hueRight = hueLeft + BAR_WIDTH;

        if (mouseX >= areaLeft && mouseX <= sqRight
                && mouseY >= sqTop && mouseY <= sqBottom) {
            cacheHSB();
            dragMode = 1;
            return true;
        }

        if (mouseX >= hueLeft - 2 && mouseX <= hueRight + 2
                && mouseY >= sqTop && mouseY <= sqBottom) {
            cacheHSB();
            dragMode = 2;
            return true;
        }

        if (colorSetting.hasAlpha()) {
            float alphaLeft = hueRight + ALPHA_GAP;
            float alphaRight = alphaLeft + BAR_WIDTH;
            if (mouseX >= alphaLeft - 2 && mouseX <= alphaRight + 2
                    && mouseY >= sqTop && mouseY <= sqBottom) {
                cacheHSB();
                dragMode = 3;
                return true;
            }
        }

        float swatchTop = sqBottom + SWATCH_GAP;
        if (mouseY >= swatchTop && mouseY <= swatchTop + SWATCH_SIZE) {
            for (int i = 0; i < SWATCHES.length; i++) {
                float left = areaLeft + i * (SWATCH_SIZE + SWATCH_SPACING);
                if (mouseX >= left && mouseX <= left + SWATCH_SIZE) {
                    int swatch = SWATCHES[i];
                    colorSetting.setColor((swatch >> 16) & 0xFF, (swatch >> 8) & 0xFF, swatch & 0xFF);
                    cacheHSB();
                    markUnsaved();
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        dragMode = 0;
    }

    @Override
    public void onGuiClosed() {
        dragMode = 0;
        smoothTimer = null;
        animationProgress = expanded ? 1f : 0f;
        animationStartProgress = animationProgress;
        animationTargetProgress = animationProgress;
    }

    @Override
    public void updateHeight(float n) {
        this.o = n;
    }

    @Override
    public float getOffset() {
        return this.o;
    }

    @Override
    public boolean isBaseVisible() {
        return colorSetting.visible;
    }

    public void restoreExpandedState(boolean expanded) {
        this.expanded = expanded;
        this.smoothTimer = null;
        this.animationProgress = expanded ? 1f : 0f;
        this.animationStartProgress = this.animationProgress;
        this.animationTargetProgress = this.animationProgress;
    }

    private void cacheHSB() {
        float bri = colorSetting.getBrightness();
        float sat = colorSetting.getSaturation();
        cachedBri = bri;
        if (bri >= BLACK_BRI_EPSILON) {
            cachedSat = sat;
            if (sat >= GREY_SAT_EPSILON) {
                cachedHue = colorSetting.getHue();
            }
        }
    }

    private void markUnsaved() {
        if (Mindless.currentProfile != null) {
            Mindless.currentProfile.getModule().saved = false;
        }
    }
}
