package mindless.utility.shader;

import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

public class BlurUtils {
    private static Framebuffer stencilFrameBufferBlur = new Framebuffer(1, 1, false);
    private static Framebuffer stencilFrameBufferBloom = new Framebuffer(1, 1, false);
    private static Framebuffer sceneFrameBuffer = new Framebuffer(1, 1, false);
    private static int blurTargetFramebuffer;
    private static int blurSourceTexture;
    private static long frameSerial;
    private static long sourceFrame = Long.MIN_VALUE;
    private static int sourceFramebuffer = -1;

    /** Marks one HUD frame so panels can share the same blurred scene snapshot. */
    public static void beginFrame() {
        frameSerial++;
        if (frameSerial == 0L) frameSerial = 1L;
    }

    public static long getFrameSerial() {
        return frameSerial;
    }
    public static void prepareBlur() {
        prepareBlur(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    }

    /**
     * Same as {@link #prepareBlur()}, but only wipes the part of the shared mask the panel is
     * about to draw into. Every panel on the HUD shares one screen-sized mask buffer, so wiping
     * all of it once per panel is a full-screen clear per panel for the sake of a rectangle a
     * few hundred pixels wide.
     */
    public static void prepareBlur(float x, float y, float width, float height) {
        // Lunar renders its HUD/world through a client-owned framebuffer. The
        // vanilla Minecraft framebuffer can therefore contain an old or black
        // image. Capture whichever framebuffer is actually bound at the call
        // site, and remember it as the destination for the finished glass.
        int boundFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        blurTargetFramebuffer = boundFramebuffer;

        // Which texture the scene lives in cannot change while a single frame is being drawn,
        // and every driver query below flushes the state cache. Seven panels on the HUD meant
        // seven identical lookups a frame, and on the copy fallback seven full-screen readbacks.
        // The cached id is also checked for still being a live texture, not just for being from
        // this frame.
        //
        // When the fallback copy path is in use, blurSourceTexture is sceneFrameBuffer's
        // attachment, and that framebuffer is recreated whenever the window size changes -- which
        // deletes the texture the id refers to. Sampling a deleted id is not an error in GL, it
        // simply reads black, so the panel composited a black rectangle instead of the scene and
        // stayed that way until something happened to invalidate the cache. That is the glass
        // going dark at random. glIsTexture costs one query on a path that already makes several
        // and only runs once per panel per frame.
        if (frameSerial == 0L || sourceFrame != frameSerial || sourceFramebuffer != boundFramebuffer
                || blurSourceTexture == 0 || !GL11.glIsTexture(blurSourceTexture)) {
            resolveSourceTexture(boundFramebuffer);
            sourceFrame = frameSerial;
            sourceFramebuffer = boundFramebuffer;
        }

        stencilFrameBufferBlur = RenderUtils.createFrameBuffer(stencilFrameBufferBlur);
        if (Float.isNaN(x)) {
            clearWholeMask(stencilFrameBufferBlur);
        } else {
            clearRegion(stencilFrameBufferBlur, x, y, width, height);
        }
        stencilFrameBufferBlur.bindFramebuffer(false);
    }

    private static void resolveSourceTexture(int blurTargetFramebuffer) {
        blurSourceTexture = 0;
        if (blurTargetFramebuffer != 0) {
            int attachmentType = EXTFramebufferObject.glGetFramebufferAttachmentParameteriEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                    EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                    EXTFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE_EXT);
            if (attachmentType == GL11.GL_TEXTURE) {
                blurSourceTexture = EXTFramebufferObject.glGetFramebufferAttachmentParameteriEXT(
                        EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                        EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                        EXTFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME_EXT);
            }
        }
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (blurSourceTexture == 0) {
            sceneFrameBuffer = RenderUtils.createFrameBuffer(sceneFrameBuffer);
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, blurTargetFramebuffer);
            int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(blurTargetFramebuffer == 0
                    ? GL11.GL_BACK : EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT);
            GlStateManager.bindTexture(sceneFrameBuffer.framebufferTexture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                    Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight);
            GL11.glReadBuffer(previousReadBuffer);
            GlStateManager.bindTexture(previousTexture);
            blurSourceTexture = sceneFrameBuffer.framebufferTexture;
        }

        if (previousActiveTexture >= GL13.GL_TEXTURE0 && previousActiveTexture <= GL13.GL_TEXTURE7) {
            GlStateManager.setActiveTexture(previousActiveTexture);
        } else {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    /**
     * Wipes the whole mask, with the caller's clip out of the way.
     *
     * <p>A clear obeys the scissor test like any other write, and {@code framebufferClear} does
     * nothing about it. Anything that clips while it draws -- a scrolling list of module options
     * being the obvious one -- therefore left most of the mask holding the previous frame's
     * shape, and the composite dutifully painted the blurred scene through whatever was still
     * standing there. That is the pale rectangle that appears behind the menu while scrolling and
     * then goes away again: not a leaked shader or a lost texture, just a clear that only cleared
     * the part of the buffer the scroll clip happened to be over.
     *
     * <p>{@link #clearRegion} already took this precaution for the partial path; the whole-buffer
     * path did not, which is why it only ever showed up on panels that wipe everything.
     */
    private static void clearWholeMask(Framebuffer buffer) {
        buffer.bindFramebuffer(false);
        // GL_SCISSOR_BIT carries the enable flag and the box, so the caller's clip comes back
        // exactly as it was.
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.clearColor(buffer.framebufferColor[0], buffer.framebufferColor[1],
                buffer.framebufferColor[2], buffer.framebufferColor[3]);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        RenderUtils.popAttrib();
    }

    /** Scissored version of {@code framebufferClear}, matching its clear colour exactly. */
    private static void clearRegion(Framebuffer buffer, float x, float y, float width, float height) {
        ScaledResolution sr = ScaledResolutionCache.get();
        int scale = sr.getScaleFactor();
        // Wider than the rectangle the composite reads back, so no stale mask can survive at the
        // edge of the panel and bleed into it.
        float margin = 8.0f;
        int px = (int) Math.floor((x - margin) * scale);
        int py = (int) Math.floor((y - margin) * scale);
        int pw = (int) Math.ceil((width + margin * 2.0f) * scale);
        int ph = (int) Math.ceil((height + margin * 2.0f) * scale);
        int bottom = Minecraft.getMinecraft().displayHeight - (py + ph);

        buffer.bindFramebuffer(false);
        // glPushAttrib carries the scissor box as well as the enable bit, so a caller that had
        // its own clip set up gets it back untouched.
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT);
        // Enabling is not enough on its own: an enabled scissor keeps whatever box was already
        // set until it is replaced, so the region has to be stated outright rather than
        // intersected with a clip that has nothing to do with this buffer.
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, bottom, Math.max(0, pw), Math.max(0, ph));
        GlStateManager.clearColor(buffer.framebufferColor[0], buffer.framebufferColor[1],
                buffer.framebufferColor[2], buffer.framebufferColor[3]);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        RenderUtils.popAttrib();
    }
    public static void prepareBloom() {
        stencilFrameBufferBloom = RenderUtils.createFrameBuffer(stencilFrameBufferBloom);
        stencilFrameBufferBloom.framebufferClear();
        stencilFrameBufferBloom.bindFramebuffer(false);
    }

    public static void blurEnd(int passes, float radius) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius);
    }

    /** Glass variant that leaves the live scene visible beneath the blur. */
    public static void blurEnd(int passes, float radius, float opacity) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius, opacity);
    }

    /** Composites only the panel rectangle instead of two full-screen passes. */
    public static void blurEndRegion(int passes, float radius, float opacity,
                                     float x, float y, float width, float height) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlurRegion(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius, opacity,
                x, y, width, height);
    }

    /** Cleaner half-resolution path intended for a full-screen GUI backdrop. */
    public static void blurEndSmooth(int passes, float radius) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderSmoothBlur(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius);
    }

    public static void bloomEnd(int passes, float radius) {
        stencilFrameBufferBloom.unbindFramebuffer();
        KawaseBloom.renderBlur(stencilFrameBufferBloom.framebufferTexture, passes, radius);
    }
}
