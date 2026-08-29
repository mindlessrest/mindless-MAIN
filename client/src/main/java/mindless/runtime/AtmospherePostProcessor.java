package mindless.runtime;

import mindless.module.impl.world.Weather;
import mindless.utility.RenderUtils;
import mindless.utility.shader.ShaderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public final class AtmospherePostProcessor {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Framebuffer scene;
    private static ShaderUtils shader;
    private static boolean failed;

    private AtmospherePostProcessor() {}

    public static void render(Weather atmosphere) {
        if (failed || atmosphere == null || !atmosphere.postProcessing.isToggled()
                || mc.theWorld == null || mc.getFramebuffer() == null) return;

        try {
            scene = RenderUtils.createFrameBuffer(scene, false);
            // No clear: the quad below covers the whole buffer with blending off, so every pixel
            // is written regardless of what was there. Clearing first was a second full-screen
            // write every frame for a buffer that is completely overwritten a line later.
            scene.bindFramebuffer(false);
            mc.entityRenderer.setupOverlayRendering();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(mc.getFramebuffer().framebufferTexture);
            ShaderUtils.drawQuads();

            mc.getFramebuffer().bindFramebuffer(false);
            mc.entityRenderer.setupOverlayRendering();
            if (shader == null) {
                shader = new ShaderUtils("mindless:shaders/atmosphere_grade.frag");
            }
            shader.init();
            shader.setUniformi("textureIn", 0);
            shader.setUniformf("texelSize", 1.0F / mc.displayWidth, 1.0F / mc.displayHeight);
            shader.setUniformf("tintColor", atmosphere.gradeTint.getRed() / 255.0F,
                    atmosphere.gradeTint.getGreen() / 255.0F,
                    atmosphere.gradeTint.getBlue() / 255.0F);
            shader.setUniformf("tintStrength", (float) atmosphere.tintStrength.getInput());
            shader.setUniformf("exposure", (float) atmosphere.exposure.getInput());
            shader.setUniformf("contrast", (float) atmosphere.contrast.getInput());
            shader.setUniformf("saturation", (float) atmosphere.worldSaturation.getInput());
            shader.setUniformf("shadowDepth", (float) atmosphere.shadowDepth.getInput());
            shader.setUniformf("bloomStrength", atmosphere.selectiveBloom.isToggled()
                    ? (float) atmosphere.bloomStrength.getInput() : 0.0F);
            shader.setUniformf("bloomThreshold", (float) atmosphere.bloomThreshold.getInput());
            shader.setUniformf("vignette", (float) atmosphere.vignette.getInput());
            GlStateManager.bindTexture(scene.framebufferTexture);
            ShaderUtils.drawQuads();
            shader.unload();
        } catch (Throwable error) {
            failed = true;
            if (shader != null) shader.unload();
        } finally {
            mc.getFramebuffer().bindFramebuffer(false);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
    }

    public static void release() {
        if (scene != null) {
            scene.deleteFramebuffer();
            scene = null;
        }
        if (shader != null) {
            shader.unload();
            GL20.glDeleteProgram(shader.programID);
            shader = null;
        }
        failed = false;
    }
}
