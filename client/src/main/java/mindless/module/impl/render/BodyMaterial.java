package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import mindless.utility.RenderUtils;
import mindless.utility.shader.GlowShader;
import mindless.utility.shader.OutlineShader;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Solid CS-style player body material, optionally applied to armor. */
public class BodyMaterial extends Module {
    public static BodyMaterial instance;
    public static boolean renderingGlowPass;
    private static int activeMaterialPasses;

    private final SliderSetting material;
    private final ColorSetting color;
    private final ButtonSetting rainbow;
    private final ButtonSetting armor;
    private final ButtonSetting throughWalls;
    private final ButtonSetting renderSelf;
    private final ButtonSetting ignoreBots;
    private final ButtonSetting showInvisible;
    private final SliderSetting maxDistance;

    private Framebuffer glowFramebuffer;
    private final GlowShader glowShader = new GlowShader();
    private final OutlineShader outlineShader = new OutlineShader();

    public BodyMaterial() {
        super("Body Material", category.render, 0);
        registerSetting(material = new SliderSetting("Material", 0,
                new String[]{"Shaded", "Flat", "Glow"}));
        registerSetting(color = new ColorSetting("Color", 45, 145, 255, 210));
        registerSetting(rainbow = new ButtonSetting("Rainbow", false));
        registerSetting(armor = new ButtonSetting("Body, armor & items", false));
        registerSetting(throughWalls = new ButtonSetting("Through walls", false));
        registerSetting(renderSelf = new ButtonSetting("Render self", false));
        registerSetting(ignoreBots = new ButtonSetting("Ignore bots", true));
        registerSetting(showInvisible = new ButtonSetting("Show invisible", false));
        registerSetting(maxDistance = new SliderSetting("Max distance", 128.0, 16.0, 256.0, 8.0));
    }

    @Override
    public void onEnable() {
        instance = this;
    }

    @Override
    public void onDisable() {
        instance = null;
        renderingGlowPass = false;
        activeMaterialPasses = 0;
        if (glowFramebuffer != null) {
            glowFramebuffer.deleteFramebuffer();
            glowFramebuffer = null;
        }
    }

    public static boolean begin(EntityLivingBase entity, boolean armorPass) {
        BodyMaterial mod = instance;
        if (mod == null || !mod.isEnabled() || !mod.shouldRender(entity, armorPass)) {
            return false;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        activeMaterialPasses++;
        mod.applyMaterialState();
        return true;
    }

    public static void reapply() {
        BodyMaterial mod = instance;
        if (mod != null && mod.isEnabled() && activeMaterialPasses > 0 && !renderingGlowPass) {
            mod.applyMaterialState();
        }
    }

    private void applyMaterialState() {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();

        int argb = resolveColor();
        float alpha = ((argb >>> 24) & 0xFF) / 255.0f;
        float red = ((argb >> 16) & 0xFF) / 255.0f;
        float green = ((argb >> 8) & 0xFF) / 255.0f;
        float blue = (argb & 0xFF) / 255.0f;

        int mode = (int) material.getInput();
        if (mode == 0) {
            GlStateManager.enableLighting();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            GlStateManager.disableLighting();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        if (throughWalls.isToggled()) {
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
        }

        GlStateManager.color(red, green, blue, alpha);
    }

    public static void end(boolean active) {
        if (!active) return;
        GL11.glPopAttrib();
        activeMaterialPasses = Math.max(0, activeMaterialPasses - 1);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private boolean shouldRender(EntityLivingBase entity, boolean armorPass) {
        if (renderingGlowPass) return false;
        return isTarget(entity, armorPass);
    }

    private boolean isTarget(EntityLivingBase entity, boolean armorPass) {
        if (!(entity instanceof EntityPlayer)) return false;
        if (PlayerESP.renderingOutlinePass) return false;
        if (armorPass && !armor.isToggled()) return false;
        if (entity.isInvisible() && !showInvisible.isToggled()) return false;
        if (entity == mc.thePlayer) {
            return renderSelf.isToggled() && mc.gameSettings.thirdPersonView != 0 && mc.currentScreen == null;
        }
        return !ignoreBots.isToggled() || !AntiBot.isBot(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderGlow(RenderWorldLastEvent event) {
        if ((int) material.getInput() != 2 || !Utils.nullCheck() || mc.theWorld == null) return;
        if (!glowShader.isValid() || !outlineShader.isValid()) return;

        glowFramebuffer = RenderUtils.createFrameBuffer(glowFramebuffer, false);
        if (glowFramebuffer == null) return;

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        glowFramebuffer.bindFramebuffer(false);
        AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, event.partialTicks, 0);
        boolean shadows = mc.gameSettings.entityShadows;
        mc.gameSettings.entityShadows = false;
        renderingGlowPass = true;

        glowShader.use();
        int glowColor = resolveColor();
        glowShader.setColor((glowColor >> 16) & 0xFF, (glowColor >> 8) & 0xFF,
                glowColor & 0xFF, Math.max(180, (glowColor >>> 24) & 0xFF));
        double maxDistanceSq = maxDistance.getInput() * maxDistance.getInput();
        for (Entity entity : mc.theWorld.playerEntities) {
            if (!(entity instanceof EntityPlayer) || !isTarget((EntityPlayer) entity, false)) continue;
            if (mc.getRenderViewEntity().getDistanceSqToEntity(entity) > maxDistanceSq) continue;
            boolean invisible = entity.isInvisible();
            if (showInvisible.isToggled()) entity.setInvisible(false);
            mc.getRenderManager().renderEntityStatic(entity, event.partialTicks, true);
            entity.setInvisible(invisible);
        }
        glowShader.stop();
        renderingGlowPass = false;

        mc.gameSettings.entityShadows = shadows;
        mc.entityRenderer.disableLightmap();
        mc.entityRenderer.setupOverlayRendering();
        mc.getFramebuffer().bindFramebuffer(false);
        outlineShader.use();
        RenderUtils.drawFramebufferFullscreen(glowFramebuffer);
        outlineShader.stop();
        glowFramebuffer.framebufferClear();
        mc.getFramebuffer().bindFramebuffer(false);
        GlStateManager.popAttrib();
        GlStateManager.popMatrix();
    }

    private int resolveColor() {
        if (rainbow.isToggled()) {
            int rgb = Utils.getChroma(2L, 0L);
            return (color.getAlpha() << 24) | (rgb & 0xFFFFFF);
        }
        return color.getColor();
    }

    @Override
    public String getInfo() {
        return material.getOptions()[(int) material.getInput()];
    }
}
