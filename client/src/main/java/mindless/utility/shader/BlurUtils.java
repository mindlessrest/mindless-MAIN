package mindless.utility.shader;

import mindless.utility.RenderUtils;
import net.minecraft.client.Minecraft;
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

    /** Marks one HUD frame so panels can share the same blurred scene snapshot. */
    public static void beginFrame() {
        frameSerial++;
        if (frameSerial == 0L) frameSerial = 1L;
    }

    public static long getFrameSerial() {
        return frameSerial;
    }
    public static void prepareBlur() {
        // Lunar renders its HUD/world through a client-owned framebuffer. The
        // vanilla Minecraft framebuffer can therefore contain an old or black
        // image. Capture whichever framebuffer is actually bound at the call
        // site, and remember it as the destination for the finished glass.
        blurTargetFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
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

        stencilFrameBufferBlur = RenderUtils.createFrameBuffer(stencilFrameBufferBlur);
        stencilFrameBufferBlur.framebufferClear();
        stencilFrameBufferBlur.bindFramebuffer(false);
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
