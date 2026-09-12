package mindless.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The three shapes every effect is built from, plus the state they need.
 *
 * Ground ring, camera-facing ring, camera-facing quad. Anything on the effect list is one of these
 * repeated over a lifetime, which is the whole reason they live here instead of being written out
 * again per module.
 *
 * Two pieces of state matter more than the rest. The shader program is unbound first, because
 * geometry pushed through a program that was not written for it comes out as nothing at all. And
 * the alpha test is disabled, because every one of these fades its edge out through low alpha and
 * the world pass leaves a test running that would discard exactly that falloff.
 */
public final class EffectRenderer {

    /** Segments in a full circle at the smallest radius worth drawing. */
    private static final int MIN_SEGMENTS = 12;
    private static final int MAX_SEGMENTS = 64;

    private static float rightX, rightY, rightZ;
    private static float upX, upY, upZ;

    private EffectRenderer() {
    }

    public static void begin() {
        OpenGlHelper.glUseProgram(0);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        cacheBillboardAxes();
    }

    public static void end() {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    /** Whether effects draw through terrain. Set per pass, not per shape. */
    public static void seeThrough(boolean through) {
        if (through) {
            GlStateManager.disableDepth();
        }
        else {
            GlStateManager.enableDepth();
        }
    }

    /**
     * The camera's right and up vectors, so a flat shape can be turned to face the viewer.
     *
     * Taken once per frame rather than per particle: they are identical for everything drawn in
     * the same pass, and a burst of sixty orbs would otherwise redo the same trigonometry sixty
     * times for no gain.
     */
    private static void cacheBillboardAxes() {
        RenderManager rm = Minecraft.getMinecraft().getRenderManager();
        double yaw = Math.toRadians(rm.playerViewY);
        double pitch = Math.toRadians(rm.playerViewX);
        float sinYaw = (float) Math.sin(yaw), cosYaw = (float) Math.cos(yaw);
        float sinPitch = (float) Math.sin(pitch), cosPitch = (float) Math.cos(pitch);

        rightX = cosYaw;
        rightY = 0.0f;
        rightZ = sinYaw;

        upX = -sinYaw * sinPitch;
        upY = cosPitch;
        upZ = cosYaw * sinPitch;
    }

    private static int segmentsFor(double radius) {
        int wanted = (int) Math.round(radius * 14.0);
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, wanted));
    }

    /**
     * A flat band on the ground between two radii, fading out at both edges.
     *
     * Lying in the world rather than facing the camera is the entire point: seen from eye height
     * it foreshortens into an ellipse, which is what makes it read as something that landed
     * instead of a decal stuck to the screen.
     */
    public static void groundRing(double cx, double cy, double cz,
                                  double inner, double outer,
                                  int rgb, float alpha) {
        if (outer <= inner || alpha <= 0.0f) {
            return;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        if (a <= 0) {
            return;
        }
        // A hard inner and outer edge aliases into a ring of staircases, so the band carries the
        // colour in its middle and falls to nothing at both rims.
        double mid = (inner + outer) * 0.5;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int segments = segmentsFor(outer);

        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            double t = (i / (double) segments) * Math.PI * 2.0;
            double sin = Math.sin(t), cos = Math.cos(t);
            wr.pos(cx + sin * inner, cy, cz + cos * inner).color(r, g, b, 0).endVertex();
            wr.pos(cx + sin * mid, cy, cz + cos * mid).color(r, g, b, a).endVertex();
        }
        tessellator.draw();

        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            double t = (i / (double) segments) * Math.PI * 2.0;
            double sin = Math.sin(t), cos = Math.cos(t);
            wr.pos(cx + sin * mid, cy, cz + cos * mid).color(r, g, b, a).endVertex();
            wr.pos(cx + sin * outer, cy, cz + cos * outer).color(r, g, b, 0).endVertex();
        }
        tessellator.draw();
    }

    /** The same band, turned to face the viewer. Reads as a blast rather than as ground. */
    public static void facingRing(double cx, double cy, double cz,
                                  double inner, double outer,
                                  int rgb, float alpha) {
        if (outer <= inner || alpha <= 0.0f) {
            return;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        if (a <= 0) {
            return;
        }
        double mid = (inner + outer) * 0.5;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int segments = segmentsFor(outer);

        for (int band = 0; band < 2; band++) {
            double r0 = band == 0 ? inner : mid;
            double r1 = band == 0 ? mid : outer;
            int a0 = band == 0 ? 0 : a;
            int a1 = band == 0 ? a : 0;
            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= segments; i++) {
                double t = (i / (double) segments) * Math.PI * 2.0;
                float sin = (float) Math.sin(t), cos = (float) Math.cos(t);
                float dx = rightX * sin + upX * cos;
                float dy = rightY * sin + upY * cos;
                float dz = rightZ * sin + upZ * cos;
                wr.pos(cx + dx * r0, cy + dy * r0, cz + dz * r0).color(r, g, b, a0).endVertex();
                wr.pos(cx + dx * r1, cy + dy * r1, cz + dz * r1).color(r, g, b, a1).endVertex();
            }
            tessellator.draw();
        }
    }

    /**
     * One camera-facing dot, bright in the middle and gone at the rim.
     *
     * A fan rather than a quad, so the falloff is radial. Textured point sprites would be cheaper,
     * but they would drag a texture bind into a pass that is otherwise pure geometry.
     */
    public static void spark(WorldRenderer wr, double px, double py, double pz,
                             double radius, int rgb, float alpha) {
        if (radius <= 0.0 || alpha <= 0.0f) {
            return;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        if (a <= 0) {
            return;
        }
        int segments = 8;
        for (int i = 0; i < segments; i++) {
            double t0 = (i / (double) segments) * Math.PI * 2.0;
            double t1 = ((i + 1) / (double) segments) * Math.PI * 2.0;
            float s0 = (float) Math.sin(t0), c0 = (float) Math.cos(t0);
            float s1 = (float) Math.sin(t1), c1 = (float) Math.cos(t1);
            wr.pos(px, py, pz).color(r, g, b, a).endVertex();
            wr.pos(px + (rightX * s0 + upX * c0) * radius,
                    py + (rightY * s0 + upY * c0) * radius,
                    pz + (rightZ * s0 + upZ * c0) * radius).color(r, g, b, 0).endVertex();
            wr.pos(px + (rightX * s1 + upX * c1) * radius,
                    py + (rightY * s1 + upY * c1) * radius,
                    pz + (rightZ * s1 + upZ * c1) * radius).color(r, g, b, 0).endVertex();
        }
    }

    /** Opens the shared batch sparks are written into; every spark in one effect shares it. */
    public static WorldRenderer beginSparks() {
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        return wr;
    }

    public static void endSparks() {
        Tessellator.getInstance().draw();
    }
}
