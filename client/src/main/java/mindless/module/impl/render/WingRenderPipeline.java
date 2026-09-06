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
 * The rest of the module draws with depth writes off, culling off and no ordering, so every
 * surface blends over every surface behind it and the wing reads as a hologram rather than an
 * object. That is fine for the glass look but wrong for the solid one, and no amount of tuning
 * the alpha fixes it, because the problem is that nothing occludes anything.
 *
 * The solid path therefore runs two passes instead:
 *
 *   core    depth writes on, alpha test at CORE_CUTOFF, blending off. Only texels that are
 *           substantially opaque survive, and they populate the depth buffer, so a feather in
 *           front genuinely hides the one behind it whatever order they were emitted in. No
 *           sorting needed, which matters because 59 feathers a wing have no stable back to
 *           front order once the flap rotates them.
 *
 *   fringe   depth writes off, depth test kept, blending on, alpha test at FRINGE_CUTOFF. Draws
 *           the soft edges, the barb gaps and the faded lower feathers over the core. These are
 *           unsorted and can misorder against each other, but they are nearly transparent by
 *           definition so it does not read.
 *
 * Glass mode keeps the single blended pass, because there the see-through stacking is the point.
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

    private static final float CORE_CUTOFF = 0.55f;
    private static final float FRINGE_CUTOFF = 0.012f;

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

    static void beginCore(boolean throughWalls) {
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, CORE_CUTOFF);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        if (throughWalls) GlStateManager.disableDepth();
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    static void beginFringe(boolean throughWalls) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, FRINGE_CUTOFF);
        GlStateManager.enableCull();
        GlStateManager.depthMask(false);
        if (throughWalls) GlStateManager.disableDepth();
        else GlStateManager.enableDepth();
    }

    /** Single blended pass, no depth writes: the deliberate see-through look. */
    static void beginGlass(boolean throughWalls) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, FRINGE_CUTOFF);
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
