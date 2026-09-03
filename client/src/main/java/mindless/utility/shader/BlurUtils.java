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
public static void prepareBlur(float x, float y, float width, float height) {
        int boundFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        blurTargetFramebuffer = boundFramebuffer;
        if (frameSerial == 0L || sourceFrame != frameSerial || sourceFramebuffer != boundFramebuffer
                || blurSourceTexture == 0) {
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
private static void clearWholeMask(Framebuffer buffer) {
        buffer.bindFramebuffer(false);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(buffer.framebufferColor[0], buffer.framebufferColor[1],
                buffer.framebufferColor[2], buffer.framebufferColor[3]);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPopAttrib();
    }
private static void clearRegion(Framebuffer buffer, float x, float y, float width, float height) {
        ScaledResolution sr = ScaledResolutionCache.get();
        int scale = sr.getScaleFactor();
        float margin = 8.0f;
        int px = (int) Math.floor((x - margin) * scale);
        int py = (int) Math.floor((y - margin) * scale);
        int pw = (int) Math.ceil((width + margin * 2.0f) * scale);
        int ph = (int) Math.ceil((height + margin * 2.0f) * scale);
        int bottom = Minecraft.getMinecraft().displayHeight - (py + ph);

        buffer.bindFramebuffer(false);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, bottom, Math.max(0, pw), Math.max(0, ph));
        GL11.glClearColor(buffer.framebufferColor[0], buffer.framebufferColor[1],
                buffer.framebufferColor[2], buffer.framebufferColor[3]);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPopAttrib();
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
public static void blurEnd(int passes, float radius, float opacity) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius, opacity);
    }
public static void blurEndRegion(int passes, float radius, float opacity,
                                     float x, float y, float width, float height) {
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlurRegion(stencilFrameBufferBlur.framebufferTexture,
                blurSourceTexture, blurTargetFramebuffer, passes, radius, opacity,
                x, y, width, height);
    }
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
