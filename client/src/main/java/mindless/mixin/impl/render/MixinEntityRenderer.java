package mindless.mixin.impl.render;

import mindless.event.LightmapUpdateEvent;
import mindless.event.PostMouseSelectionEvent;
import mindless.helper.RotationHelper;
import mindless.module.ModuleManager;
import mindless.module.impl.render.Freelook;
import mindless.mixin.impl.accessor.IAccessorEntityRenderer;
import mindless.mixin.interfaces.ISaturationRenderer;
import mindless.runtime.SaturationShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer implements ISaturationRenderer {

    @Shadow
    private ShaderGroup theShaderGroup;

    @Inject(method = "isShaderActive", at = @At("HEAD"), cancellable = true)
    private void onIsShaderActive(CallbackInfoReturnable<Boolean> cir) {
        if (SaturationShaderRuntime.get((EntityRenderer) (Object) this) != null && OpenGlHelper.shadersSupported) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getShaderGroup", at = @At("HEAD"), cancellable = true)
    private void onGetShaderGroup(CallbackInfoReturnable<ShaderGroup> cir) {
        ShaderGroup shader = SaturationShaderRuntime.get((EntityRenderer) (Object) this);
        if (shader != null && OpenGlHelper.shadersSupported && theShaderGroup == null) {
            cir.setReturnValue(shader);
        }
    }

    @Inject(method = "updateShaderGroupSize", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;createBindEntityOutlineFbs(II)V"))
    private void onUpdateShaderGroupSize(int width, int height, CallbackInfo ci) {
        ShaderGroup shader = SaturationShaderRuntime.get((EntityRenderer) (Object) this);
        if (shader != null) {
            shader.createBindFramebuffers(width, height);
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntityOutlineFramebuffer()V", shift = At.Shift.AFTER))
    private void onRenderSaturation(float partialTicks, long nanoTime, CallbackInfo ci) {
        ShaderGroup shader = SaturationShaderRuntime.get((EntityRenderer) (Object) this);
        if (shader != null) {
            final int previousFramebuffer = GL11.glGetInteger(
                    EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
            GlStateManager.matrixMode(5890);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.loadIdentity();
                shader.loadShaderGroup(partialTicks);
            } finally {
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(5888);
                EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
                GL11.glViewport(0, 0, Minecraft.getMinecraft().displayWidth,
                        Minecraft.getMinecraft().displayHeight);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    @Override
    public ShaderGroup mindless$getSaturationShader() {
        return SaturationShaderRuntime.get((EntityRenderer) (Object) this);
    }

    @Override
    public void mindless$setSaturationShader(ShaderGroup shader) {
        SaturationShaderRuntime.set((EntityRenderer) (Object) this, shader);
    }

    @Redirect(method = "hurtCameraEffect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"))
    public void injectNoHurtCam(float angle, float x, float y, float z) {
        if (ModuleManager.noHurtCam != null && ModuleManager.noHurtCam.isEnabled()) {
            angle = (float) (angle / 14 * ModuleManager.noHurtCam.multiplier.getInput());
        }
        GlStateManager.rotate(angle, x, y, z);
    }

    @Redirect(method = "orientCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Vec3;distanceTo(Lnet/minecraft/util/Vec3;)D"))
    public double injectNoCameraClip(Vec3 raytrace, Vec3 original) {
        if (ModuleManager.noCameraClip != null && ModuleManager.noCameraClip.isEnabled()) {
            return ModuleManager.extendCamera != null && ModuleManager.extendCamera.isEnabled() ? ModuleManager.extendCamera.distance.getInput() : 4;
        }
        return raytrace.distanceTo(original);
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationYaw:F"))
    private float freelookRotationYaw(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraYaw;
        }
        return entity.rotationYaw;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationYaw:F"))
    private float freelookPrevRotationYaw(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraYaw;
        }
        return entity.prevRotationYaw;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationPitch:F"))
    private float freelookRotationPitch(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraPitch;
        }
        return entity.rotationPitch;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationPitch:F"))
    private float freelookPrevRotationPitch(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraPitch;
        }
        return entity.prevRotationPitch;
    }

    @Redirect(method = "updateCameraAndRender", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;inGameHasFocus:Z"))
    private boolean freelookOverrideMouse(Minecraft mc) {
        return Freelook.overrideMouse(mc);
    }

    @Redirect(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean redirectSetupFog(EntityLivingBase entity, Potion potion) {
        if (entity == Minecraft.getMinecraft().thePlayer && ModuleManager.antiDebuff != null && ModuleManager.antiDebuff.canRemoveBlindness(potion)) {
            return false;
        }

        return entity.isPotionActive(potion);
    }

    @Redirect(method = "updateFogColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean redirectFogColor(EntityLivingBase entity, Potion potion) {
        if (entity == Minecraft.getMinecraft().thePlayer && ModuleManager.antiDebuff != null && ModuleManager.antiDebuff.canRemoveBlindness(potion)) {
            return false;
        }
        return entity.isPotionActive(potion);
    }

    @Redirect(method = "setupCameraTransform", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean redirectSetupCameraTransform(EntityPlayerSP entity, Potion potion) {
        if (entity == Minecraft.getMinecraft().thePlayer && ModuleManager.antiDebuff != null && ModuleManager.antiDebuff.canRemoveNausea(potion)) {
            return false;
        }
        return entity.isPotionActive(potion);
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V", shift = At.Shift.AFTER))
    private void onRenderWorld(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PostMouseSelectionEvent());
    }

    @Inject(method = "getMouseOver", at = @At("HEAD"))
    private void onGetMouseOverHead(float partialTicks, CallbackInfo ci) {
        RotationHelper rh = RotationHelper.get();
        if (rh.swappedForMouseOver) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view != null && rh.isActive()) {
            Float yaw = rh.getServerYaw();
            Float pitch = rh.getServerPitch();
            if (yaw != null && !yaw.isNaN() && pitch != null && !pitch.isNaN()) {
                rh.beginSwap(view, yaw, pitch, true);
                rh.swappedForMouseOver = true;
            }
        }
    }

    @Inject(method = "getMouseOver", at = @At(value = "INVOKE", target = "Lnet/minecraft/profiler/Profiler;endSection()V", shift = At.Shift.BEFORE))
    private void onGetMouseOverBeforeEndSection(float partialTicks, CallbackInfo ci) {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            ModuleManager.bedAura.modifyMouseOverFromGetMouseOver(partialTicks);
            return;
        }

        if (ModuleManager.ghostHand != null && ModuleManager.ghostHand.shouldOverrideMouseOver()) {
            ModuleManager.ghostHand.modifyMouseOverFromGetMouseOver(partialTicks);
            return;
        }
        if (ModuleManager.piercing != null && ModuleManager.piercing.shouldOverrideMouseOver()) {
            ModuleManager.piercing.modifyMouseOverFromGetMouseOver(partialTicks);
        }
    }

    @Inject(method = "getMouseOver", at = @At("RETURN"))
    private void onGetMouseOverReturn(float partialTicks, CallbackInfo ci) {
        RotationHelper rh = RotationHelper.get();
        if (rh.swappedForMouseOver) {
            Entity view = Minecraft.getMinecraft().getRenderViewEntity();
            if (view != null) {
                rh.endSwap(view);
            }
            rh.swappedForMouseOver = false;
        }
    }
    @Inject(method = "updateLightmap", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V", shift = At.Shift.BEFORE))
    private void onUpdateLightmap(float partialTicks, CallbackInfo ci) {
        IAccessorEntityRenderer accessor = (IAccessorEntityRenderer) (Object) this;
        int[] colors = accessor.getLightmapColors();
        MinecraftForge.EVENT_BUS.post(new LightmapUpdateEvent(colors));
    }
}
