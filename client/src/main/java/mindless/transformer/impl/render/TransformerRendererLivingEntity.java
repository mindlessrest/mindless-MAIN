package mindless.transformer.impl.render;

import mindless.module.ModuleManager;
import mindless.module.impl.render.DamageTint;
import mindless.module.impl.render.MobESP;
import mindless.module.impl.other.NameHider;
import mindless.module.impl.render.Nametags;
import mindless.module.impl.render.SexyESP;
import mindless.module.impl.render.SexyESP;
import mindless.module.impl.render.Slow;
import mindless.module.impl.world.AntiBot;
import mindless.runtime.RendererLivingEntityState;
import mindless.utility.Utils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;

import java.awt.*;

@CTransformer(RendererLivingEntity.class)
public abstract class TransformerRendererLivingEntity {
    @CShadow
    protected boolean renderOutlines;

    @CShadow
    protected abstract void unsetBrightness();

    @CInline
    @CInject(method = "getSwingProgress", target = @CTarget("HEAD"), cancellable = true)
    private void slow$useLocalVisualSwing(EntityLivingBase entity, float partialTicks, InjectionCallback ci) {
        if (entity == Minecraft.getMinecraft().thePlayer && entity instanceof AbstractClientPlayer) {
            float vanilla = entity.getSwingProgress(partialTicks);
            ci.setReturnValue(Slow.getVisualSwingProgress((AbstractClientPlayer) entity, vanilla));
        }
    }

    @CInline
    @CRedirect(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;setScoreTeamColor(Lnet/minecraft/entity/EntityLivingBase;)Z", optional = true))
    private boolean setOutlineColor(RendererLivingEntity instance, EntityLivingBase entityLivingBaseIn) {
        int i = 16777215;
        boolean drawOutline = RendererLivingEntityState.shouldRender() && ((entityLivingBaseIn != Minecraft.getMinecraft().thePlayer && !AntiBot.isBot(entityLivingBaseIn)) || (entityLivingBaseIn == Minecraft.getMinecraft().thePlayer && ModuleManager.sexyESP.isRenderSelf()));

        if (!drawOutline || ModuleManager.sexyESP.isTeamColor()) {
            if (entityLivingBaseIn instanceof EntityPlayer) {
                ScorePlayerTeam scoreplayerteam = (ScorePlayerTeam) entityLivingBaseIn.getTeam();

                if (scoreplayerteam != null) {
                    String s = FontRenderer.getFormatFromString(scoreplayerteam.getColorPrefix());

                    if (s.length() >= 2) {
                        i = instance.getFontRendererFromRenderManager().getColorCode(s.charAt(1));
                    }
                }
            }
        }
        else if (ModuleManager.sexyESP.isRainbow()) {
            i = Utils.getChroma(2L, 0L);
        }
        else {
            i = ModuleManager.sexyESP.getColorRGB();
        }

        if (drawOutline && ModuleManager.sexyESP.isRedOnDamage() && entityLivingBaseIn.hurtTime != 0) {
            i = Color.RED.getRGB();
        }

        if (drawOutline) {
            return false;
        }

        float f1 = (float)(i >> 16 & 255) / 255.0F;
        float f2 = (float)(i >> 8 & 255) / 255.0F;
        float f = (float)(i & 255) / 255.0F;
        GlStateManager.disableLighting();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.color(f1, f2, f, 1.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        return true;
    }

    @CInline
    @CInject(method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z", target = @CTarget("HEAD"), cancellable = true)
    private void suppressNameDuringOutlinePass(EntityLivingBase entity, InjectionCallback ci) {
        if (SexyESP.renderingOutlinePass || MobESP.renderingOutlinePass) {
            ci.setReturnValue(false);
            return;
        }
        if (SexyESP.replacesStandaloneNametags() || Nametags.shouldHideVanillaFor(entity)) {
            ci.setReturnValue(false);
        }
    }

    @CInline
    @CInject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", target = @CTarget("HEAD"))
    private void mobEsp$chamsPre(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        if (!(entity instanceof EntityPlayer)) {
            MobESP.onRenderMobPre(entity);
        }
    }

    @CInline
    @CInject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", target = @CTarget("RETURN"))
    private void mobEsp$chamsPost(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        if (!(entity instanceof EntityPlayer)) {
            MobESP.onRenderMobPost();
        }
    }

    @CInline
    @CInject(method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V", target = @CTarget("HEAD"))
    private void nameHider$captureRenderEntity(EntityLivingBase entity, double x, double y, double z, InjectionCallback ci) {
        RendererLivingEntityState.nameHiderRenderNameEntity = entity;
    }

    @CInline
    @CInject(method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V", target = @CTarget("RETURN"))
    private void nameHider$clearRenderEntity(EntityLivingBase entity, double x, double y, double z, InjectionCallback ci) {
        RendererLivingEntityState.nameHiderRenderNameEntity = null;
    }
}
