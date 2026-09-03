package mindless.utility.shader;

import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.awt.*;

import static org.lwjgl.opengl.GL11.*;

public class RoundedUtils {
    public static ShaderUtils roundedShader = new ShaderUtils("roundedRect");
    public static ShaderUtils roundedOutlineShader = new ShaderUtils("roundRectOutline");
    private static final ShaderUtils roundedShadowShader = new ShaderUtils("roundedShadow");
    private static final ShaderUtils roundedTexturedShader = new ShaderUtils("roundRectTexture");
    private static final ShaderUtils roundedGradientShader = new ShaderUtils("roundedRectGradient");
    private static final ShaderUtils roundedRectRiseShader = new ShaderUtils("roundedRectRise");
    private static final ShaderUtils roundedCornersShader = new ShaderUtils("roundedRectCorners");
    private static final ShaderUtils roundedGradientCornersShader = new ShaderUtils("roundedRectGradientCorners");
public static void drawRoundCorners(float x, float y, float width, float height,
                                        float topLeft, float topRight,
                                        float bottomRight, float bottomLeft, int color) {
        RenderUtils.resetColor();
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_ALPHA_TEST);
        glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        roundedCornersShader.init();
        setupRoundedRectUniforms(x, y, width, height, 0f, roundedCornersShader);
        setupCornerRadii(roundedCornersShader, topLeft, topRight, bottomRight, bottomLeft);
        roundedCornersShader.setUniformf("color", getRed(color), getGreen(color), getBlue(color), getAlpha(color));

        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedCornersShader.unload();
        glDepthMask(true);
        glPopAttrib();
    }
public static void drawGradientRoundCorners(float x, float y, float width, float height,
                                                float topLeft, float topRight,
                                                float bottomRight, float bottomLeft,
                                                int blColor, int tlColor, int brColor, int trColor) {
        RenderUtils.setAlphaLimit(0);
        RenderUtils.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        roundedGradientCornersShader.init();
        setupRoundedRectUniforms(x, y, width, height, 0f, roundedGradientCornersShader);
        setupCornerRadii(roundedGradientCornersShader, topLeft, topRight, bottomRight, bottomLeft);
        roundedGradientCornersShader.setUniformf("color1", getRed(tlColor), getGreen(tlColor), getBlue(tlColor), getAlpha(tlColor));
        roundedGradientCornersShader.setUniformf("color2", getRed(blColor), getGreen(blColor), getBlue(blColor), getAlpha(blColor));
        roundedGradientCornersShader.setUniformf("color3", getRed(trColor), getGreen(trColor), getBlue(trColor), getAlpha(trColor));
        roundedGradientCornersShader.setUniformf("color4", getRed(brColor), getGreen(brColor), getBlue(brColor), getAlpha(brColor));

        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedGradientCornersShader.unload();
        GlStateManager.disableBlend();
    }
private static void setupCornerRadii(ShaderUtils shader, float topLeft, float topRight,
                                         float bottomRight, float bottomLeft) {
        float scale = ScaledResolutionCache.get().getScaleFactor();
        shader.setUniformf("radii", topLeft * scale, topRight * scale,
                bottomRight * scale, bottomLeft * scale);
    }

    public static void drawRound(float x, float y, float width, float height, float radius, Color color) {
        drawRound(x, y, width, height, radius, false, color);
    }

    public static void drawGradientHorizontal(float x, float y, float width, float height, float radius, Color left, Color right) {
        drawGradientRound(x, y, width, height, radius, left, left, right, right);
    }

    public static void drawGradientVertical(float x, float y, float width, float height, float radius, Color top, Color bottom) {
        drawGradientRound(x, y, width, height, radius, bottom, top, bottom, top);
    }
public static void drawLiquidGlass(float x, float y, float width, float height,
                                       float radius, int fillColor) {
        Color top = new Color(220, 235, 255, 14);
        Color bottom = new Color(0, 0, 0, 8);

        RenderUtils.resetColor();
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_ALPHA_TEST);
        glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        roundedGradientShader.init();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedGradientShader);
        roundedGradientShader.setUniformf("color1", top.getRed() / 255F, top.getGreen() / 255F,
                top.getBlue() / 255F, top.getAlpha() / 255F);
        roundedGradientShader.setUniformf("color2", bottom.getRed() / 255F, bottom.getGreen() / 255F,
                bottom.getBlue() / 255F, bottom.getAlpha() / 255F);
        roundedGradientShader.setUniformf("color3", top.getRed() / 255F, top.getGreen() / 255F,
                top.getBlue() / 255F, top.getAlpha() / 255F);
        roundedGradientShader.setUniformf("color4", bottom.getRed() / 255F, bottom.getGreen() / 255F,
                bottom.getBlue() / 255F, bottom.getAlpha() / 255F);
        ShaderUtils.drawQuads(x - 1F, y - 1F, width + 2F, height + 2F);
        roundedGradientShader.unload();
        glDepthMask(true);
        glPopAttrib();

        float inset = .7F;
        drawRound(x + inset, y + inset, width - inset * 2F, height - inset * 2F,
                Math.max(0F, radius - inset), fillColor);
    }

    public static void drawGradientCornerLR(float x, float y, float width, float height, float radius, Color topLeft, Color bottomRight) {
        Color mixedColor = RenderUtils.interpolateColorC(topLeft, bottomRight, .5f);
        drawGradientRound(x, y, width, height, radius, mixedColor, topLeft, bottomRight, mixedColor);
    }

    public static void drawGradientCornerRL(float x, float y, float width, float height, float radius, Color bottomLeft, Color topRight) {
        Color mixedColor = RenderUtils.interpolateColorC(topRight, bottomLeft, .5f);
        drawGradientRound(x, y, width, height, radius, bottomLeft, mixedColor, mixedColor, topRight);
    }

    public static void drawRound(float x, float y, float width, float height, float radius, int color) {
        drawRound(x, y, width, height, radius, false, color);
    }

    public static void drawRound(float x, float y, float width, float height, float radius, boolean blur, int color) {
        RenderUtils.resetColor();
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_ALPHA_TEST);
        glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        roundedShader.init();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedShader);
        roundedShader.setUniformi("blur", blur ? 1 : 0);
        roundedShader.setUniformf("color", getRed(color), getGreen(color), getBlue(color), getAlpha(color));

        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedShader.unload();
        glDepthMask(true);
        glPopAttrib();
    }

    public static void drawGradientRound(float x, float y, float width, float height, float radius, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {
        RenderUtils.setAlphaLimit(0);
        RenderUtils.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        roundedGradientShader.init();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedGradientShader);
        roundedGradientShader.setUniformf("color1", topLeft.getRed() / 255f, topLeft.getGreen() / 255f, topLeft.getBlue() / 255f, topLeft.getAlpha() / 255f);
        roundedGradientShader.setUniformf("color2", bottomLeft.getRed() / 255f, bottomLeft.getGreen() / 255f, bottomLeft.getBlue() / 255f, bottomLeft.getAlpha() / 255f);
        roundedGradientShader.setUniformf("color3", topRight.getRed() / 255f, topRight.getGreen() / 255f, topRight.getBlue() / 255f, topRight.getAlpha() / 255f);
        roundedGradientShader.setUniformf("color4", bottomRight.getRed() / 255f, bottomRight.getGreen() / 255f, bottomRight.getBlue() / 255f, bottomRight.getAlpha() / 255f);
        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedGradientShader.unload();
        GlStateManager.disableBlend();
    }

    public static void drawGradientRound(float x, float y, float width, float height, float radius, int bottomLeft, int topLeft, int bottomRight, int topRight) {
        RenderUtils.setAlphaLimit(0);
        RenderUtils.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        roundedGradientShader.init();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedGradientShader);

        roundedGradientShader.setUniformf("color1", getRed(topLeft), getGreen(topLeft), getBlue(topLeft), getAlpha(topLeft));
        roundedGradientShader.setUniformf("color2", getRed(bottomLeft), getGreen(bottomLeft), getBlue(bottomLeft), getAlpha(bottomLeft));
        roundedGradientShader.setUniformf("color3", getRed(topRight), getGreen(topRight), getBlue(topRight), getAlpha(topRight));
        roundedGradientShader.setUniformf("color4", getRed(bottomRight), getGreen(bottomRight), getBlue(bottomRight), getAlpha(bottomRight));

        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedGradientShader.unload();
        GlStateManager.disableBlend();
    }

    public static void drawRound(float x, float y, float width, float height, float radius, boolean blur, Color color) {
        RenderUtils.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        RenderUtils.setAlphaLimit(0);
        roundedShader.init();

        setupRoundedRectUniforms(x, y, width, height, radius, roundedShader);
        roundedShader.setUniformi("blur", blur ? 1 : 0);
        roundedShader.setUniformf("color", color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f);

        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedShader.unload();
        GlStateManager.disableBlend();
    }


    public static void drawRoundOutline(float x, float y, float width, float height, float radius, float outlineThickness, Color color, Color outlineColor) {
        RenderUtils.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        RenderUtils.setAlphaLimit(0);
        roundedOutlineShader.init();

        ScaledResolution sr = ScaledResolutionCache.get();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedOutlineShader);
        roundedOutlineShader.setUniformf("outlineThickness", outlineThickness * sr.getScaleFactor());
        roundedOutlineShader.setUniformf("color", color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f);
        roundedOutlineShader.setUniformf("outlineColor", outlineColor.getRed() / 255f, outlineColor.getGreen() / 255f, outlineColor.getBlue() / 255f, outlineColor.getAlpha() / 255f);


        ShaderUtils.drawQuads(x - (2 + outlineThickness), y - (2 + outlineThickness), width + (4 + outlineThickness * 2), height + (4 + outlineThickness * 2));
        roundedOutlineShader.unload();
        GlStateManager.disableBlend();
    }

    public static void drawRoundShadow(float x, float y, float width, float height,
                                       float radius, float softness, int color) {
        RenderUtils.resetColor();
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_ALPHA_TEST);
        glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        ScaledResolution sr = ScaledResolutionCache.get();
        roundedShadowShader.init();
        setupRoundedRectUniforms(x, y, width, height, radius, roundedShadowShader);
        roundedShadowShader.setUniformf("softness", softness * sr.getScaleFactor());
        roundedShadowShader.setUniformf("color", getRed(color), getGreen(color), getBlue(color), getAlpha(color));
        float expansion = softness * 3.5f + 2.0f;
        roundedShadowShader.setUniformf("cutoff", expansion * sr.getScaleFactor());
        ShaderUtils.drawQuads(x - expansion, y - expansion,
                width + expansion * 2.0f, height + expansion * 2.0f);
        roundedShadowShader.unload();
        glDepthMask(true);
        // Deliberately a bare pop. Routing this through RenderUtils.popAttrib re-syncs
        // GlStateManager from the driver, and the GL_ENABLE_BIT branch of that sync rewrites the
        // lighting state -- which left every 3D block item in the inventory unlit and invisible
        // while flat items still drew. This runs every frame from the HUD, so it stays cheap and
        // stays out of the cache's way.
        glPopAttrib();
    }


    public static void drawRoundTextured(float x, float y, float width, float height, float radius, float alpha) {
        RenderUtils.resetColor();
        RenderUtils.setAlphaLimit(0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        roundedTexturedShader.init();
        roundedTexturedShader.setUniformi("textureIn", 0);
        setupRoundedRectUniforms(x, y, width, height, radius, roundedTexturedShader);
        roundedTexturedShader.setUniformf("alpha", alpha);
        ShaderUtils.drawQuads(x - 1, y - 1, width + 2, height + 2);
        roundedTexturedShader.unload();
        GlStateManager.disableBlend();
    }

    private static void setupRoundedRectUniforms(float x, float y, float width, float height, float radius, ShaderUtils roundedTexturedShader) {
        ScaledResolution sr = ScaledResolutionCache.get();
        roundedTexturedShader.setUniformf("location", x * sr.getScaleFactor(),
                (Minecraft.getMinecraft().displayHeight - (height * sr.getScaleFactor())) - (y * sr.getScaleFactor()));
        roundedTexturedShader.setUniformf("rectSize", width * sr.getScaleFactor(), height * sr.getScaleFactor());
        roundedTexturedShader.setUniformf("radius", radius * sr.getScaleFactor());
    }

    public static void drawRoundedRectRise(final float x, final float y, final float width, final float height, final float radius, final int color, boolean leftTop, boolean rightTop, boolean rightBottom, boolean leftBottom) {
        GL11.glPushMatrix();
        GlStateManager.pushAttrib();
        final int programId = roundedRectRiseShader.programID;
        GL20.glUseProgram(programId);
        roundedRectRiseShader.setUniformf("u_size", width, height);
        roundedRectRiseShader.setUniformf("u_radius", radius);
        roundedRectRiseShader.setUniformf("u_color", getRed(color), getGreen(color), getBlue(color), getAlpha(color));
        roundedRectRiseShader.setUniformf("u_edges", leftTop ? 1.0F : 0.0F, rightTop ? 1.0F : 0.0F, rightBottom ? 1.0F : 0.0F, leftBottom ? 1.0F : 0.0F);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        ShaderUtils.drawQuads(x, y, width, height);
        GlStateManager.disableBlend();
        GL20.glUseProgram(0);
        GlStateManager.popAttrib();
        GL11.glPopMatrix();
    }

    public static void drawRoundedRectRise(final double x, final double y, final double width, final double height, final double radius, final int color) {
        drawRoundedRectRise((float) x, (float) y, (float) width, (float) height, (float) radius, color, true, true, true, true);
    }

    private static float getRed(int color) {
        return (color >> 16 & 0xFF) / 255.0F;
    }

    private static float getGreen(int color) {
        return (color >> 8 & 0xFF) / 255.0F;
    }

    private static float getBlue(int color) {
        return (color & 0xFF) / 255.0F;
    }

    private static float getAlpha(int color) {
        return (color >> 24 & 0xFF) / 255.0F;
    }
}
