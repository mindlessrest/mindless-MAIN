package mindless.utility.shader;

import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

/**
 * Mask-based glow for HUD text elements (watermark, array list).
 *
 * Pipeline: render text into a transparent FBO mask, blur the mask via GlowBloomShader,
 * composite the blurred glow back onto the screen. The original text is then drawn sharply on top.
 */
public final class HudGlowHelper {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Framebuffer maskBuffer;
    private static final GlowBloomShader glowShader = new GlowBloomShader();

    private HudGlowHelper() {}

    public static boolean isAvailable() {
        return glowShader.isValid();
    }

    /**
     * Begin capturing text into the mask buffer. Call this, draw text normally, then call endAndComposite.
     * The drawn text will be captured as the glow source mask.
     */
    public static void beginMask() {
        if (!isAvailable()) return;
        maskBuffer = RenderUtils.createFrameBuffer(maskBuffer, false);
        if (maskBuffer == null) return;
        maskBuffer.setFramebufferFilter(GL11.GL_LINEAR);
        maskBuffer.setFramebufferColor(0.0f, 0.0f, 0.0f, 0.0f);
        maskBuffer.framebufferClear();
        maskBuffer.bindFramebuffer(false);

        ScaledResolution sr = ScaledResolutionCache.get();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0f, 0.0f, -2000.0f);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * End mask capture, blur the mask, and composite the glow onto the main framebuffer.
     *
     * @param radius   blur radius (recommended 6-12 for text)
     * @param intensity brightness multiplier
     * @param r        glow tint red 0-255
     * @param g        glow tint green 0-255
     * @param b        glow tint blue 0-255
     */
    public static void endAndComposite(float radius, float intensity, int r, int g, int b) {
        if (!isAvailable() || maskBuffer == null) return;

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        mc.getFramebuffer().bindFramebuffer(false);
        mc.entityRenderer.setupOverlayRendering();

        glowShader.render(maskBuffer, radius, intensity, r, g, b);

        mc.getFramebuffer().bindFramebuffer(false);
        mc.entityRenderer.setupOverlayRendering();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
