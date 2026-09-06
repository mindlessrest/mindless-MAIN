package mindless.module.impl.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Render state and textures for the feathered wing.
 *
 * The module's shared state draws with depth writes off, culling off and no ordering, so every
 * surface blends over every surface behind it and the wing reads as a hologram rather than an
 * object. Opacity was never the problem: nothing occludes anything.
 *
 * The fix has to keep two things apart that are easy to conflate.
 *
 *   coverage      where a feather physically is. Comes from the texture alpha alone. Decides
 *                 what occludes what.
 *   transparency  how much you see through it. Comes from the vertical fade, the tip fade and
 *                 the palette alpha. Decides how it blends.
 *
 * A single alpha value cannot carry both, because the fixed-function alpha test sees
 * texture * vertex. Fold the fade into vertex alpha and a faded feather stops passing the test,
 * stops writing depth, and falls back to unordered blending -- so the deeper the gradient the
 * more hologram you get, which is exactly backwards.
 *
 * So depth is laid down separately, before any colour:
 *
 *   depth pre-pass  colour writes masked off, depth writes on, alpha test against the texture
 *                   alpha only (vertex alpha forced opaque). Establishes which feather is in
 *                   front, independently of how transparent it is going to be drawn. No sorting
 *                   needed, which matters because 59 feathers a wing have no stable back to
 *                   front order once the flap rotates them.
 *
 *   colour pass     colour writes on, depth writes off, depth test LEQUAL, blending on, real
 *                   faded alpha. Anything behind a nearer feather fails the depth test and never
 *                   contributes, so the stacking is gone, while what remains blends at its true
 *                   gradient value.
 *
 * Glass mode skips the pre-pass entirely; there the see-through stacking is the intended look.
 *
 * Textures are read straight off the classpath rather than through a ResourceLocation lookup.
 * Lunar does not mount the client's assets into the resource manager, so a lookup there resolves
 * to the missing-texture checkerboard; reading the stream works on both launch paths. One failed
 * attempt is enough, the flag stops it being retried once per frame forever.
 */
final class WingRenderPipeline {

    static final int PART_PRIMARY = 0;
    static final int PART_SECONDARY = 1;
    static final int PART_COVERT_GREATER = 2;
    static final int PART_COVERT_MARGINAL = 3;
    static final int PART_COUNT = 4;

    private static final String[] PART_RESOURCES = {
            "/assets/mindless/textures/wings/feather_primary.png",
            "/assets/mindless/textures/wings/feather_secondary.png",
            "/assets/mindless/textures/wings/feather_covert_greater.png",
            "/assets/mindless/textures/wings/feather_covert_marginal.png"
    };

    private static final String[] PART_NAMES = {
            "mindless_feather_primary",
            "mindless_feather_secondary",
            "mindless_feather_covert_greater",
            "mindless_feather_covert_marginal"
    };

    /** Texture-alpha level at which a feather counts as solid enough to occlude. */
    private static final float COVERAGE_CUTOFF = 0.08f;
    /** Discards fully empty texels so the blend does not pay for them. */
    private static final float COLOUR_CUTOFF = 0.004f;

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[PART_COUNT];
    private static final boolean[] ATTEMPTED = new boolean[PART_COUNT];

    private WingRenderPipeline() {
    }

    /**
     * Texture for one feather row, or null if it could not be read.
     *
     * Each row has its own file rather than a cell in an atlas. On a shared sheet the bilinear
     * filter and the mip chain sample across the cell boundary, so a primary picks up
     * marginal-covert down along its root edge. Separate textures cannot bleed into each other.
     * Clamping is on for the same reason at the outer edges.
     */
    static ResourceLocation texture(int part) {
        if (part < 0 || part >= PART_COUNT) return null;
        if (TEXTURES[part] == null && !ATTEMPTED[part]) {
            ATTEMPTED[part] = true;
            InputStream stream = null;
            try {
                stream = WingRenderPipeline.class.getResourceAsStream(PART_RESOURCES[part]);
                if (stream != null) {
                    BufferedImage image = ImageIO.read(stream);
                    if (image != null) {
                        DynamicTexture dynamic = new DynamicTexture(image);
                        dynamic.setBlurMipmap(true, true);
                        TEXTURES[part] = Minecraft.getMinecraft().getTextureManager()
                                .getDynamicTextureLocation(PART_NAMES[part], dynamic);
                    }
                }
            }
            catch (Exception unreadable) {
                TEXTURES[part] = null;
            }
            finally {
                if (stream != null) {
                    try {
                        stream.close();
                    }
                    catch (Exception ignored) {
                    }
                }
            }
        }
        return TEXTURES[part];
    }

    static boolean texturesReady() {
        for (int part = 0; part < PART_COUNT; part++) {
            if (texture(part) == null) return false;
        }
        return true;
    }

    static void beginDepthPrepass() {
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, COVERAGE_CUTOFF);
        // The right wing is produced by mirroring X, which reverses winding.
        // Feather sheets are intentionally two-sided, so culling either loses
        // one complete wing or requires a second reversed index buffer.
        GlStateManager.disableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(false, false, false, false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    static void beginColour(boolean throughWalls) {
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, COLOUR_CUTOFF);
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls) GlStateManager.disableDepth();
        else {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
        }
    }

    /** Single blended pass, no depth writes: the deliberate see-through look. */
    static void beginGlass(boolean throughWalls) {
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, COLOUR_CUTOFF);
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls) GlStateManager.disableDepth();
        else GlStateManager.enableDepth();
    }

    static void bind(int part) {
        ResourceLocation location = texture(part);
        if (location != null) Minecraft.getMinecraft().getTextureManager().bindTexture(location);
    }

    static void end() {
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
