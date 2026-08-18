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
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class KawaseBlur {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MASK_TEXTURE_INDEX = 1;
    private static final int MASK_TEXTURE_UNIT = GL13.GL_TEXTURE1;
    public static ShaderUtils kawaseDown = new ShaderUtils("kawaseDown");
    public static ShaderUtils kawaseUp = new ShaderUtils("kawaseUp");
    public static Framebuffer framebuffer = new Framebuffer(1, 1, false);
    private static int currentIterations;
    private static int smoothCurrentIterations;
    private static long regularPreparedFrame = Long.MIN_VALUE;
    private static long smoothPreparedFrame = Long.MIN_VALUE;
    private static int regularPreparedIterations = -1;
    private static int smoothPreparedIterations = -1;
    private static int regularPreparedOffsetBits;
    private static int smoothPreparedOffsetBits;
    private static int regularPreparedSourceTexture = -1;
    private static int smoothPreparedSourceTexture = -1;

    private static final List<Framebuffer> framebufferList = new ArrayList<>();
    private static final List<Framebuffer> smoothFramebufferList = new ArrayList<>();

    private static void initFrameBuffers(List<Framebuffer> buffers, int iterations, int downsampleFactor) {
        for (Framebuffer buffer : buffers) {
            buffer.deleteFramebuffer();
        }
        buffers.clear();

        Framebuffer fullSize = RenderUtils.createFrameBuffer(null);
        buffers.add(fullSize);
        if (buffers == framebufferList) {
            framebuffer = fullSize;
            regularPreparedFrame = Long.MIN_VALUE;
        } else {
            smoothPreparedFrame = Long.MIN_VALUE;
        }

        for (int i = 1; i <= iterations; i++) {
            Framebuffer currentBuffer = new Framebuffer(
                    Math.max(1, (int) (mc.displayWidth / Math.pow(downsampleFactor, i))),
                    Math.max(1, (int) (mc.displayHeight / Math.pow(downsampleFactor, i))), false);
            currentBuffer.setFramebufferFilter(GL_LINEAR);
            GlStateManager.bindTexture(currentBuffer.framebufferTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
            GlStateManager.bindTexture(0);

            buffers.add(currentBuffer);
        }
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
        if (currentIterations != iterations || framebufferList.isEmpty()
                || framebufferList.get(0).framebufferWidth != mc.displayWidth
                || framebufferList.get(0).framebufferHeight != mc.displayHeight) {
            initFrameBuffers(framebufferList, iterations, 3);
            currentIterations = iterations;
        }
        renderBlur(stencilFrameBufferTexture, sourceTexture, targetFramebuffer,
                iterations, offset, compositeOpacity, framebufferList);
    }

    public static void renderBlurRegion(int stencilFrameBufferTexture, int sourceTexture,
                                        int targetFramebuffer, int iterations, float offset,
                                        float compositeOpacity, float x, float y,
                                        float width, float height) {
        if (currentIterations != iterations || framebufferList.isEmpty()
                || framebufferList.get(0).framebufferWidth != mc.displayWidth
                || framebufferList.get(0).framebufferHeight != mc.displayHeight) {
            initFrameBuffers(framebufferList, iterations, 3);
            currentIterations = iterations;
        }
        prepareBlurredTexture(sourceTexture, iterations, offset, framebufferList);
        compositeRegion(stencilFrameBufferTexture, targetFramebuffer, offset,
                compositeOpacity, x, y, width, height, framebufferList);
    }

    public static void renderSmoothBlur(int stencilFrameBufferTexture, int iterations, float offset) {
        renderSmoothBlur(stencilFrameBufferTexture, mc.getFramebuffer().framebufferTexture,
                mc.getFramebuffer().framebufferObject, iterations, offset);
    }

    public static void renderSmoothBlur(int stencilFrameBufferTexture, int sourceTexture,
                                        int targetFramebuffer, int iterations, float offset) {
        if (smoothCurrentIterations != iterations || smoothFramebufferList.isEmpty()
                || smoothFramebufferList.get(0).framebufferWidth != mc.displayWidth
                || smoothFramebufferList.get(0).framebufferHeight != mc.displayHeight) {
            initFrameBuffers(smoothFramebufferList, iterations, 2);
            smoothCurrentIterations = iterations;
        }
        renderBlur(stencilFrameBufferTexture, sourceTexture, targetFramebuffer,
                iterations, offset, 1.0F, smoothFramebufferList);
    }

    private static void renderBlur(int stencilFrameBufferTexture, int sourceTexture,
                                   int targetFramebuffer, int iterations, float offset,
                                   float compositeOpacity,
                                   List<Framebuffer> buffers) {
        prepareBlurredTexture(sourceTexture, iterations, offset, buffers);

        Framebuffer lastBuffer = buffers.get(0);
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
                stencilFrameBufferTexture, buffers.get(1).framebufferTexture);
        try {
            ShaderUtils.drawQuads();
        } finally {
            kawaseUp.unload();
            previousTextures.restore();
        }

        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, targetFramebuffer);
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(buffers.get(0).framebufferTexture);
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
        GL11.glPopAttrib();
        RenderUtils.resetColor();
    }

    private static void prepareBlurredTexture(int sourceTexture, int iterations, float offset,
                                              List<Framebuffer> buffers) {
        long frame = BlurUtils.getFrameSerial();
        int offsetBits = Float.floatToIntBits(offset);
        boolean smooth = buffers == smoothFramebufferList;
        boolean cached = frame != 0L && (smooth
                ? smoothPreparedFrame == frame
                    && smoothPreparedIterations == iterations
                    && smoothPreparedOffsetBits == offsetBits
                    && smoothPreparedSourceTexture == sourceTexture
                : regularPreparedFrame == frame
                    && regularPreparedIterations == iterations
                    && regularPreparedOffsetBits == offsetBits
                    && regularPreparedSourceTexture == sourceTexture);
        if (cached) return;

        renderFBO(buffers.get(1), sourceTexture, kawaseDown, offset);
        for (int i = 1; i < iterations; i++) {
            renderFBO(buffers.get(i + 1), buffers.get(i).framebufferTexture, kawaseDown, offset);
        }
        for (int i = iterations; i > 1; i--) {
            renderFBO(buffers.get(i - 1), buffers.get(i).framebufferTexture, kawaseUp, offset);
        }

        if (smooth) {
            smoothPreparedFrame = frame;
            smoothPreparedIterations = iterations;
            smoothPreparedOffsetBits = offsetBits;
            smoothPreparedSourceTexture = sourceTexture;
        } else {
            regularPreparedFrame = frame;
            regularPreparedIterations = iterations;
            regularPreparedOffsetBits = offsetBits;
            regularPreparedSourceTexture = sourceTexture;
        }
    }

    private static void compositeRegion(int stencilTexture, int targetFramebuffer, float offset,
                                        float opacity, float x, float y, float width, float height,
                                        List<Framebuffer> buffers) {
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
                stencilTexture, buffers.get(1).framebufferTexture);
        try {
            ShaderUtils.drawQuads(x - 2F, y - 2F, width + 4F, height + 4F);
        } finally {
            kawaseUp.unload();
            previousTextures.restore();
        }
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
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
