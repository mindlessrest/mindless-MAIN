package mindless.utility.shader;

import mindless.utility.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.EXTFramebufferObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

public class KawaseBlur {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MASK_TEXTURE_INDEX = 1;
    private static final int MASK_TEXTURE_UNIT = GL13.GL_TEXTURE1;
    public static ShaderUtils kawaseDown = new ShaderUtils("kawaseDown");
    public static ShaderUtils kawaseUp = new ShaderUtils("kawaseUp");

    /**
     * One downsample chain per blur strength.
     *
     * <p>There used to be a single chain and an int remembering which strength it was built for.
     * Any panel asking for a different strength tore the whole chain down and allocated a new
     * one, full-resolution buffer included. On a HUD with panels at three different strengths
     * that is several full-screen texture allocations and frees <em>every frame</em>, which costs
     * far more than the blur it was there to serve. Keeping a chain per strength means each is
     * built once and then simply reused.
     */
    private static final Map<Integer, Pyramid> pyramids = new HashMap<>();

    /** Full-resolution scratch for the whole-screen composite path, shared by every chain. */
    private static Framebuffer compositeBuffer;

    private static int builtWidth;
    private static int builtHeight;

    /** A downsample chain: index 1 is half-ish size, index n is the smallest. */
    private static final class Pyramid {
        private final int iterations;
        private final List<Framebuffer> levels = new ArrayList<>();
        private long preparedFrame = Long.MIN_VALUE;
        private int preparedOffsetBits;
        private int preparedSourceTexture = -1;

        private Pyramid(int iterations, int downsampleFactor) {
            this.iterations = iterations;
            this.levels.add(null); // index 0 is the full-size slot, which lives in compositeBuffer
            for (int i = 1; i <= iterations; i++) {
                Framebuffer level = new Framebuffer(
                        Math.max(1, (int) (mc.displayWidth / Math.pow(downsampleFactor, i))),
                        Math.max(1, (int) (mc.displayHeight / Math.pow(downsampleFactor, i))), false);
                level.setFramebufferFilter(GL_LINEAR);
                GlStateManager.bindTexture(level.framebufferTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
                GlStateManager.bindTexture(0);
                this.levels.add(level);
            }
        }

        private Framebuffer level(int index) {
            return this.levels.get(index);
        }

        private void delete() {
            for (int i = 1; i < this.levels.size(); i++) {
                this.levels.get(i).deleteFramebuffer();
            }
            this.levels.clear();
        }
    }

    /** Fetches the chain for this strength, building it only the first time it is asked for. */
    private static Pyramid pyramid(int iterations, int downsampleFactor) {
        if (builtWidth != mc.displayWidth || builtHeight != mc.displayHeight) {
            for (Pyramid stale : pyramids.values()) {
                stale.delete();
            }
            pyramids.clear();
            if (compositeBuffer != null) {
                compositeBuffer.deleteFramebuffer();
                compositeBuffer = null;
            }
            builtWidth = mc.displayWidth;
            builtHeight = mc.displayHeight;
        }

        int key = iterations * 8 + downsampleFactor;
        Pyramid pyramid = pyramids.get(key);
        if (pyramid == null) {
            pyramid = new Pyramid(iterations, downsampleFactor);
            pyramids.put(key, pyramid);
        }
        return pyramid;
    }

    private static Framebuffer compositeBuffer() {
        if (compositeBuffer == null) {
            compositeBuffer = RenderUtils.createFrameBuffer(null);
        }
        return compositeBuffer;
    }

    public static void renderBlur(int stencilFrameBufferTexture, int iterations, float offset) {
        renderBlur(stencilFrameBufferTexture, mc.getFramebuffer().framebufferTexture,
                mc.getFramebuffer().framebufferObject, iterations, offset);
    }

    public static void renderBlur(int stencilFrameBufferTexture, int sourceTexture,
                                  int targetFramebuffer, int iterations, float offset) {
        renderBlur(stencilFrameBufferTexture, sourceTexture, targetFramebuffer,
                iterations, offset, 1.0F);
    }

    public static void renderBlur(int stencilFrameBufferTexture, int sourceTexture,
                                  int targetFramebuffer, int iterations, float offset,
                                  float compositeOpacity) {
        renderBlur(stencilFrameBufferTexture, sourceTexture, targetFramebuffer,
                iterations, offset, compositeOpacity, pyramid(iterations, 3));
    }

    public static void renderBlurRegion(int stencilFrameBufferTexture, int sourceTexture,
                                        int targetFramebuffer, int iterations, float offset,
                                        float compositeOpacity, float x, float y,
                                        float width, float height) {
        Pyramid pyramid = pyramid(iterations, 3);
        prepareBlurredTexture(sourceTexture, offset, pyramid);
        compositeRegion(stencilFrameBufferTexture, targetFramebuffer, offset,
                compositeOpacity, x, y, width, height, pyramid);
    }

    public static void renderSmoothBlur(int stencilFrameBufferTexture, int iterations, float offset) {
        renderSmoothBlur(stencilFrameBufferTexture, mc.getFramebuffer().framebufferTexture,
                mc.getFramebuffer().framebufferObject, iterations, offset);
    }

    public static void renderSmoothBlur(int stencilFrameBufferTexture, int sourceTexture,
                                        int targetFramebuffer, int iterations, float offset) {
        renderBlur(stencilFrameBufferTexture, sourceTexture, targetFramebuffer,
                iterations, offset, 1.0F, pyramid(iterations, 2));
    }

    private static void renderBlur(int stencilFrameBufferTexture, int sourceTexture,
                                   int targetFramebuffer, int iterations, float offset,
                                   float compositeOpacity, Pyramid pyramid) {
        prepareBlurredTexture(sourceTexture, offset, pyramid);

        Framebuffer lastBuffer = compositeBuffer();
        lastBuffer.framebufferClear();
        lastBuffer.bindFramebuffer(false);

        kawaseUp.init();
        kawaseUp.setUniformf("offset", offset, offset);
        kawaseUp.setUniformi("inTexture", 0);
        kawaseUp.setUniformi("check", 1);
        kawaseUp.setUniformi("textureToCheck", MASK_TEXTURE_INDEX);
        kawaseUp.setUniformf("opacity", 1.0F);
        kawaseUp.setUniformf("halfpixel", 1.0f / lastBuffer.framebufferWidth, 1.0f / lastBuffer.framebufferHeight);
        kawaseUp.setUniformf("iResolution", lastBuffer.framebufferWidth, lastBuffer.framebufferHeight);
        TextureBindings previousTextures = bindCompositeTextures(
                stencilFrameBufferTexture, pyramid.level(1).framebufferTexture);
        try {
            ShaderUtils.drawQuads();
        } finally {
            kawaseUp.unload();
            previousTextures.restore();
        }

        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, targetFramebuffer);
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(lastBuffer.framebufferTexture);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glColor4f(1F, 1F, 1F, Math.max(0F, Math.min(1F, compositeOpacity)));
        ShaderUtils.drawQuads();
        GlStateManager.bindTexture(0);
        GL11.glDepthMask(true);
        RenderUtils.popAttrib();
        RenderUtils.resetColor();
    }

    private static void prepareBlurredTexture(int sourceTexture, float offset, Pyramid pyramid) {
        long frame = BlurUtils.getFrameSerial();
        int offsetBits = Float.floatToIntBits(offset);
        if (frame != 0L
                && pyramid.preparedFrame == frame
                && pyramid.preparedOffsetBits == offsetBits
                && pyramid.preparedSourceTexture == sourceTexture) {
            return;
        }

        int iterations = pyramid.iterations;
        renderFBO(pyramid.level(1), sourceTexture, kawaseDown, offset);
        for (int i = 1; i < iterations; i++) {
            renderFBO(pyramid.level(i + 1), pyramid.level(i).framebufferTexture, kawaseDown, offset);
        }
        for (int i = iterations; i > 1; i--) {
            renderFBO(pyramid.level(i - 1), pyramid.level(i).framebufferTexture, kawaseUp, offset);
        }

        pyramid.preparedFrame = frame;
        pyramid.preparedOffsetBits = offsetBits;
        pyramid.preparedSourceTexture = sourceTexture;
    }

    private static void compositeRegion(int stencilTexture, int targetFramebuffer, float offset,
                                        float opacity, float x, float y, float width, float height,
                                        Pyramid pyramid) {
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, targetFramebuffer);
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        kawaseUp.init();
        kawaseUp.setUniformf("offset", offset, offset);
        kawaseUp.setUniformi("inTexture", 0);
        kawaseUp.setUniformi("check", 1);
        kawaseUp.setUniformi("textureToCheck", MASK_TEXTURE_INDEX);
        kawaseUp.setUniformf("opacity", Math.max(0F, Math.min(1F, opacity)));
        kawaseUp.setUniformf("halfpixel", 1.0F / mc.displayWidth, 1.0F / mc.displayHeight);
        kawaseUp.setUniformf("iResolution", mc.displayWidth, mc.displayHeight);
        TextureBindings previousTextures = bindCompositeTextures(
                stencilTexture, pyramid.level(1).framebufferTexture);
        try {
            ShaderUtils.drawQuads(x - 2F, y - 2F, width + 4F, height + 4F);
        } finally {
            kawaseUp.unload();
            previousTextures.restore();
        }
        GL11.glDepthMask(true);
        RenderUtils.popAttrib();
        RenderUtils.resetColor();
    }

    private static void renderFBO(Framebuffer framebuffer, int framebufferTexture, ShaderUtils shader, float offset) {
        framebuffer.framebufferClear();
        framebuffer.bindFramebuffer(false);
        shader.init();
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(framebufferTexture);
        shader.setUniformf("offset", offset, offset);
        shader.setUniformi("inTexture", 0);
        shader.setUniformi("check", 0);
        if (shader == kawaseUp) shader.setUniformf("opacity", 1.0F);
        shader.setUniformf("halfpixel", 1.0f / framebuffer.framebufferWidth, 1.0f / framebuffer.framebufferHeight);
        shader.setUniformf("iResolution", framebuffer.framebufferWidth, framebuffer.framebufferHeight);
        ShaderUtils.drawQuads();
        shader.unload();
    }

    /**
     * Binds the source and mask without escaping Minecraft 1.8's eight cached
     * texture units.  Unit 16 is not a valid fragment-sampler index on every
     * Lunar OpenGL profile and leaves the following font/menu draws broken.
     */
    private static TextureBindings bindCompositeTextures(int maskTexture, int sourceTexture) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GlStateManager.setActiveTexture(MASK_TEXTURE_UNIT);
        int previousMaskTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.bindTexture(maskTexture);

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(sourceTexture);
        return new TextureBindings(previousActiveTexture, previousTexture0, previousMaskTexture);
    }

    private static final class TextureBindings {
        private final int activeTexture;
        private final int texture0;
        private final int maskTexture;

        private TextureBindings(int activeTexture, int texture0, int maskTexture) {
            this.activeTexture = activeTexture;
            this.texture0 = texture0;
            this.maskTexture = maskTexture;
        }

        private void restore() {
            GlStateManager.setActiveTexture(MASK_TEXTURE_UNIT);
            GlStateManager.bindTexture(maskTexture);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(texture0);

            // Minecraft 1.8 only caches units 0-7. Keep an unexpected external
            // active unit from indexing outside that cache on the next bind.
            if (activeTexture >= GL13.GL_TEXTURE0 && activeTexture <= GL13.GL_TEXTURE7) {
                GlStateManager.setActiveTexture(activeTexture);
            } else {
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            }
        }
    }
}
