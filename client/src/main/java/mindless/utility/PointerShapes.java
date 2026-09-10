package mindless.utility;

import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * The crosshair pointer shapes, shared by Arrows and Indicators.
 *
 * The two modules each drew their own arrow from their own copy of the code with their own shape
 * list, so a projectile pointer and a player pointer never matched and only one of them ever
 * gained a new shape. They draw from here now.
 *
 * Every branch sets up and tears down its own GL state, including unbinding whatever shader
 * program the overlay before it left bound -- immediate-mode geometry drawn through someone
 * else's shader is the usual reason a shape renders as nothing at all.
 */
public final class PointerShapes {

    // Appended, never reordered: a slider stores the chosen option as its index, so
    // inserting a shape would silently change what every saved profile points at.
    public static final String[] NAMES = {"Caret", "Chevron", "Triangle", "Needle", "Diamond", "Dart"};

    public static final int CARET = 0;
    public static final int CHEVRON = 1;
    public static final int TRIANGLE = 2;
    public static final int NEEDLE = 3;
    public static final int DIAMOND = 4;
    public static final int DART = 5;

    private PointerShapes() {
    }

    /** True for the shapes whose outline width is meaningful. */
    public static boolean usesThickness(int shape, boolean filled) {
        if (shape == CHEVRON) return false;
        if (shape == TRIANGLE || shape == DIAMOND || shape == DART) return !filled;
        return true;
    }

    /** True for the shapes that can be drawn solid. */
    public static boolean canFill(int shape) {
        return shape == TRIANGLE || shape == DIAMOND || shape == DART;
    }

    public static void draw(int shape, int rgba, float thickness, boolean filled,
                            MindlessFontRenderer font) {
        // Whatever drew last may have left a program bound; immediate mode would then run through
        // it and paint nothing.
        GL20.glUseProgram(0);

        if (shape == CHEVRON) {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.pushMatrix();
            GlStateManager.rotate(-90.0f, 0.0f, 0.0f, 1.0f);
            GlStateManager.scale(1.5f, 1.5f, 1.5f);
            if (font != null) {
                font.drawString(">", -2.0f, -4.0f, rgba, false);
            }
            GlStateManager.popMatrix();
            return;
        }

        float red = ((rgba >> 16) & 0xFF) / 255.0F;
        float green = ((rgba >> 8) & 0xFF) / 255.0F;
        float blue = (rgba & 0xFF) / 255.0F;
        float alpha = ((rgba >>> 24) & 0xFF) / 255.0F;
        if (alpha <= 0.0F) {
            alpha = 1.0F;
        }

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlStateManager.color(red, green, blue, alpha);
        GL11.glLineWidth(Math.max(1.0F, thickness));

        switch (shape) {
            case TRIANGLE:
                if (filled) {
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    GL11.glVertex2d(0.0, -7.0);
                    GL11.glVertex2d(5.5, 3.5);
                    GL11.glVertex2d(-5.5, 3.5);
                    GL11.glEnd();
                }
                else {
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(0.0, -7.0);
                    GL11.glVertex2d(5.5, 3.5);
                    GL11.glVertex2d(-5.5, 3.5);
                    GL11.glEnd();
                }
                break;

            case NEEDLE:
                // A long thin spike, which stays readable at small scales where a caret turns to
                // mush. Drawn as a closed outline so the notch at the base reads.
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2d(0.0, -9.0);
                GL11.glVertex2d(4.0, 3.0);
                GL11.glVertex2d(0.0, 0.0);
                GL11.glVertex2d(-4.0, 3.0);
                GL11.glEnd();
                break;

            case DIAMOND:
                if (filled) {
                    GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                    GL11.glVertex2d(0.0, -2.0);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.0, -2.0);
                    GL11.glVertex2d(0.0, 4.0);
                    GL11.glVertex2d(-5.0, -2.0);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glEnd();
                }
                else {
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.0, -2.0);
                    GL11.glVertex2d(0.0, 4.0);
                    GL11.glVertex2d(-5.0, -2.0);
                    GL11.glEnd();
                }
                break;

            case DART:
                // A stubby arrowhead with a notched tail: wider than the needle and short
                // enough to stay one clean silhouette at the sizes these sit at, which is
                // what the long shapes lose once several of them crowd the ring.
                if (filled) {
                    // Two triangles rather than a fan. The notch makes the outline
                    // concave, and a fan would bridge straight across it.
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.5, 4.0);
                    GL11.glVertex2d(0.0, 1.0);

                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(0.0, 1.0);
                    GL11.glVertex2d(-5.5, 4.0);
                    GL11.glEnd();
                }
                else {
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.5, 4.0);
                    GL11.glVertex2d(0.0, 1.0);
                    GL11.glVertex2d(-5.5, 4.0);
                    GL11.glEnd();
                }
                break;

            case CARET:
            default: {
                double halfAngle = 0.6108652353286743;
                double size = 9.0;
                double offsetY = 5.0;
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2d(Math.sin(-halfAngle) * size, Math.cos(-halfAngle) * size - offsetY);
                GL11.glVertex2d(0.0, -offsetY);
                GL11.glVertex2d(Math.sin(halfAngle) * size, Math.cos(halfAngle) * size - offsetY);
                GL11.glEnd();
                break;
            }
        }

        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
