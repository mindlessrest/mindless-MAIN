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

    /**
     * Every shape can be drawn solid.
     *
     * Three of them could not, so the Filled toggle silently did nothing on half the list and
     * looked broken rather than inapplicable. An open shape is now a closed one with its own
     * thickness, which is also what stops a caret at line width eight reading as a blob.
     */
    public static boolean canFill(int shape) {
        return true;
    }

    public static void draw(int shape, int rgba, float thickness, boolean filled,
                            MindlessFontRenderer font) {
        // Whatever drew last may have left a program bound; immediate mode would then run through
        // it and paint nothing.
        GL20.glUseProgram(0);

        float red = ((rgba >> 16) & 0xFF) / 255.0F;
        float green = ((rgba >> 8) & 0xFF) / 255.0F;
        float blue = (rgba & 0xFF) / 255.0F;
        // Not clamped up to opaque when it arrives at zero. It used to be, which meant an arrow
        // told to fade out at maximum distance snapped to fully solid at the moment it should
        // have disappeared.
        float alpha = ((rgba >>> 24) & 0xFF) / 255.0F;

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
                // mush. The notch at the base makes the outline concave, so a fan would bridge it.
                if (filled) {
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    GL11.glVertex2d(0.0, -9.0);
                    GL11.glVertex2d(4.0, 3.0);
                    GL11.glVertex2d(0.0, 0.0);

                    GL11.glVertex2d(0.0, -9.0);
                    GL11.glVertex2d(0.0, 0.0);
                    GL11.glVertex2d(-4.0, 3.0);
                    GL11.glEnd();
                }
                else {
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(0.0, -9.0);
                    GL11.glVertex2d(4.0, 3.0);
                    GL11.glVertex2d(0.0, 0.0);
                    GL11.glVertex2d(-4.0, 3.0);
                    GL11.glEnd();
                }
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

            case CHEVRON:
                // Was a ">" from the font, rotated. That ignored thickness, ignored fill, and drew
                // nothing at all when no font was handed in, which is most of why this mode looked
                // broken. It is geometry now, like every other shape here.
                chevron(0.0, -7.0, 5.5, 5.0, thickness, filled);
                chevron(0.0, -1.0, 5.5, 5.0, thickness, filled);
                break;

            case CARET:
            default: {
                chevron(0.0, -5.0, 7.4, 6.4, thickness, filled);
                break;
            }
        }

        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * A single arrowhead stroke, open or solid.
     *
     * Filled is not a scaled-up outline: the two arms are laid out as quads of the given
     * thickness meeting at the apex, so the join stays sharp. A line strip mitres nothing, which
     * is why the old caret grew a notch at its point as soon as the thickness passed about three.
     */
    private static void chevron(double cx, double cy, double halfWidth, double depth,
                                float thickness, boolean filled) {
        double leftX = cx - halfWidth, rightX = cx + halfWidth;
        double armY = cy + depth;
        if (!filled) {
            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glVertex2d(leftX, armY);
            GL11.glVertex2d(cx, cy);
            GL11.glVertex2d(rightX, armY);
            GL11.glEnd();
            return;
        }

        double t = Math.max(1.0, thickness);
        // Offset along the arm's normal, so a steep arrowhead does not end up thinner than a
        // shallow one at the same setting.
        double len = Math.hypot(halfWidth, depth);
        double nx = depth / len * t * .5, ny = halfWidth / len * t * .5;

        GL11.glBegin(GL11.GL_TRIANGLES);
        quad(leftX + nx, armY + ny, leftX - nx, armY - ny, cx - nx, cy - ny, cx + nx, cy + ny);
        quad(rightX - nx, armY + ny, rightX + nx, armY - ny, cx + nx, cy - ny, cx - nx, cy + ny);
        GL11.glEnd();
    }

    private static void quad(double ax, double ay, double bx, double by,
                             double cx, double cy, double dx, double dy) {
        GL11.glVertex2d(ax, ay);
        GL11.glVertex2d(bx, by);
        GL11.glVertex2d(cx, cy);
        GL11.glVertex2d(ax, ay);
        GL11.glVertex2d(cx, cy);
        GL11.glVertex2d(dx, dy);
    }
}
