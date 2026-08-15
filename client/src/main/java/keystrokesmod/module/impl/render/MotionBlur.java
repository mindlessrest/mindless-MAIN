package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.shader.KawaseBlur;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class MotionBlur extends Module {
    public SliderSetting strength;
    public SliderSetting blurPasses;
    private Framebuffer prevFrameFbo;
    private double prevPosX, prevPosY, prevPosZ;
    private float prevYaw, prevPitch;
    private boolean hasFrame;

    public MotionBlur() {
        super("Motion Blur", category.render);
        this.registerSetting(strength = new SliderSetting("Strength", 0.6, 0.05, 1.0, 0.05));
        this.registerSetting(blurPasses = new SliderSetting("Blur passes", 4, 1, 8, 1));
    }

    @Override
    public void onEnable() {
        hasFrame = false;
    }

    @Override
    public void onDisable() {
        if (prevFrameFbo != null) {
            prevFrameFbo.deleteFramebuffer();
            prevFrameFbo = null;
        }
        hasFrame = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderWorldPre(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || !hasFrame || prevFrameFbo == null) return;

        float velocity = calcVelocity();
        float alpha = Math.min(velocity * 8f, (float) strength.getInput());
        if (alpha < 0.01f) return;

        prevFrameFbo = RenderUtils.createFrameBuffer(prevFrameFbo);

        int blurTex = prevFrameFbo.framebufferTexture;
        KawaseBlur.renderBlur(blurTex, (int) blurPasses.getInput(), 2.5f);

        mc.getFramebuffer().bindFramebuffer(true);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1f, 1f, 1f, alpha);

        drawFullscreenQuad(blurTex);

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldPost(RenderWorldLastEvent e) {
        if (!Utils.nullCheck()) return;

        trackCamera();

        prevFrameFbo = RenderUtils.createFrameBuffer(prevFrameFbo);
        prevFrameFbo.bindFramebuffer(false);

        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        drawFullscreenQuad(mc.getFramebuffer().framebufferTexture);

        mc.getFramebuffer().bindFramebuffer(true);
        hasFrame = true;
    }

    private void trackCamera() {
        prevPosX = mc.getRenderManager().viewerPosX;
        prevPosY = mc.getRenderManager().viewerPosY;
        prevPosZ = mc.getRenderManager().viewerPosZ;
        prevYaw = mc.getRenderManager().playerViewY;
        prevPitch = mc.getRenderManager().playerViewX;
    }

    private float calcVelocity() {
        double dx = mc.getRenderManager().viewerPosX - prevPosX;
        double dy = mc.getRenderManager().viewerPosY - prevPosY;
        double dz = mc.getRenderManager().viewerPosZ - prevPosZ;
        float dyaw = Math.abs(mc.getRenderManager().playerViewY - prevYaw);
        float dpitch = Math.abs(mc.getRenderManager().playerViewX - prevPitch);

        double posVel = Math.sqrt(dx * dx + dz * dz) * 3.0;
        double rotVel = (dyaw + dpitch) * 0.02;

        return (float) Math.min(1.0, posVel + rotVel);
    }

    private void drawFullscreenQuad(int textureId) {
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, mc.displayWidth, mc.displayHeight, 0.0, -1.0, 1.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        float w = mc.displayWidth;
        float h = mc.displayHeight;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.bindTexture(textureId);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w, 0f);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, h);
        GL11.glEnd();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.popMatrix();
    }
}
