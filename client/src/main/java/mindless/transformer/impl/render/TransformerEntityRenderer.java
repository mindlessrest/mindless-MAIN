package mindless.transformer.impl.render;

import mindless.event.LightmapUpdateEvent;
import mindless.helper.RotationHelper;
import mindless.module.ModuleManager;
import mindless.module.impl.render.Freelook;
import mindless.module.impl.render.SexyESP;
import mindless.runtime.LunarEventBridge;
import mindless.utility.ScaledResolutionCache;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@CTransformer(EntityRenderer.class)
public class TransformerEntityRenderer {
    @CShadow
    private int[] lightmapColors;
    @CShadow
    private float fogColorRed;
    @CShadow
    private float fogColorGreen;
    @CShadow
    private float fogColorBlue;
    @CShadow
    private float farPlaneDistance;
    @CInline
    @CInject(method = "updateCameraAndRender", target = @CTarget("HEAD"))
    private void onRenderTickStart(float partialTicks, long nanoTime, InjectionCallback ci) {
        LunarEventBridge.postRenderTick(TickEvent.Phase.START, partialTicks);
    }

    @CInline
    @CInject(method = "updateCameraAndRender", target = @CTarget("RETURN"))
    private void onRenderTickEnd(float partialTicks, long nanoTime, InjectionCallback ci) {
        LunarEventBridge.postRenderTick(TickEvent.Phase.END, partialTicks);
    }

    @CInline
    @CInject(method = "renderWorldPass",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;setupCameraTransform(FI)V",
                    shift = CTarget.Shift.AFTER))
    private void captureWorldProjection(int pass, float partialTicks, long finishTimeNano,
                                        InjectionCallback ci) {
        SexyESP.captureForWorldProjection(ScaledResolutionCache.get().getScaleFactor());
    }

    @CInline
    @CInject(method = "renderWorldPass",
            target = @CTarget(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand:Z",
                    shift = CTarget.Shift.BEFORE))
    private void onRenderWorldLast(int pass, float partialTicks, long finishTimeNano,
                                   InjectionCallback ci) {
        LunarEventBridge.postRenderWorld(partialTicks);
    }

    @CInline
    @CInject(method = "getMouseOver", target = @CTarget("HEAD"))
    private void applyServerRotationToMouseOver(float partialTicks, InjectionCallback ci) {
        RotationHelper rotations = RotationHelper.get();
        if (rotations.swappedForMouseOver) return;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null || !rotations.isActive()) return;
        Float yaw = rotations.getServerYaw();
        Float pitch = rotations.getServerPitch();
        if (yaw == null || yaw.isNaN() || pitch == null || pitch.isNaN()) return;
        rotations.beginSwap(view, yaw, pitch, true);
        rotations.swappedForMouseOver = true;
    }

    @CInline
    @CInject(method = "getMouseOver",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/profiler/Profiler;endSection()V",
                    shift = CTarget.Shift.BEFORE))
    private void applyPiercingMouseOver(float partialTicks, InjectionCallback ci) {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()
                && !ModuleManager.bedAura.isPrioritizingKillAura()) {
            ModuleManager.bedAura.modifyMouseOverFromGetMouseOver(partialTicks);
            return;
        }
        if (ModuleManager.killAura != null && ModuleManager.killAura.shouldOverrideMouseOver()) {
            ModuleManager.killAura.modifyMouseOverFromGetMouseOver(partialTicks);
            return;
        }
        if (ModuleManager.piercing != null && ModuleManager.piercing.shouldOverrideMouseOver()) {
            ModuleManager.piercing.modifyMouseOverFromGetMouseOver(partialTicks);
        }
        if (ModuleManager.ghostHand != null) {
            ModuleManager.ghostHand.overrideMouseOver(partialTicks);
        }
    }

    @CInline
    @CInject(method = "getMouseOver", target = @CTarget("RETURN"))
    private void restoreClientRotationAfterMouseOver(float partialTicks, InjectionCallback ci) {
        RotationHelper rotations = RotationHelper.get();
        if (!rotations.swappedForMouseOver) return;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view != null) rotations.endSwap(view);
        rotations.swappedForMouseOver = false;
    }

    @CInline
    @CRedirect(method = "hurtCameraEffect",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"))
    public void injectNoHurtCam(float angle, float x, float y, float z) {
        if (ModuleManager.noHurtCam != null && ModuleManager.noHurtCam.isEnabled()) {
            angle = (float) (angle / 14 * ModuleManager.noHurtCam.multiplier.getInput());
        }
        GlStateManager.rotate(angle, x, y, z);
    }

    @CInline
    @CRedirect(method = "orientCamera",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/util/Vec3;distanceTo(Lnet/minecraft/util/Vec3;)D"))
    public double injectNoCameraClip(Vec3 raytrace, Vec3 original) {
        if (ModuleManager.noCameraClip != null && ModuleManager.noCameraClip.isEnabled()) {
            return ModuleManager.extendCamera != null && ModuleManager.extendCamera.isEnabled()
                    ? ModuleManager.extendCamera.distance.getInput() : 4;
        }
        return raytrace.distanceTo(original);
    }

    @CInline
    @CInject(method = "updateLightmap",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V",
                    shift = CTarget.Shift.BEFORE))
    private void onUpdateLightmap(float partialTicks, InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new LightmapUpdateEvent(lightmapColors));
    }

    @CInline
    @CInject(method = "updateFogColor", target = @CTarget("RETURN"))
    private void applyAtmosphereFogColor(float partialTicks, InjectionCallback ci) {
        if (ModuleManager.weather == null || !ModuleManager.weather.isEnabled()
                || !ModuleManager.weather.customFog.isToggled()) return;
        int red = ModuleManager.weather.fogColor.getRed();
        int green = ModuleManager.weather.fogColor.getGreen();
        int blue = ModuleManager.weather.fogColor.getBlue();
        fogColorRed = red / 255.0F;
        fogColorGreen = green / 255.0F;
        fogColorBlue = blue / 255.0F;
        GlStateManager.clearColor(fogColorRed, fogColorGreen, fogColorBlue, 0.0F);
    }

    @CInline
    @CInject(method = "setupFog", target = @CTarget("RETURN"))
    private void applyAtmosphereFogDistance(int startCoords, float partialTicks,
                                            InjectionCallback ci) {
        if (ModuleManager.weather == null || !ModuleManager.weather.isEnabled()
                || !ModuleManager.weather.customFog.isToggled()) return;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null || view.isInsideOfMaterial(Material.water)
                || view.isInsideOfMaterial(Material.lava)) return;
        float start = (float) Math.min(ModuleManager.weather.fogStart.getInput(),
                ModuleManager.weather.fogEnd.getInput() - 1.0) / 100.0F;
        float end = (float) Math.max(ModuleManager.weather.fogEnd.getInput(),
                ModuleManager.weather.fogStart.getInput() + 1.0) / 100.0F;
        org.lwjgl.opengl.GL11.glFogi(org.lwjgl.opengl.GL11.GL_FOG_MODE,
                org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_START,
                farPlaneDistance * Math.max(0.0F, start));
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_END,
                farPlaneDistance * Math.min(1.0F, end));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Freelook camera rotation overrides
    // Redirect ALL field accesses to rotation fields in orientCamera
    // ─────────────────────────────────────────────────────────────────────────────

    @CInline
    @CRedirect(method = "orientCamera",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationYaw:F"))
    private float freelookRotationYaw(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraYaw;
        }
        return entity.rotationYaw;
    }

    @CInline
    @CRedirect(method = "orientCamera",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationYaw:F"))
    private float freelookPrevRotationYaw(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraYaw;
        }
        return entity.prevRotationYaw;
    }

    @CInline
    @CRedirect(method = "orientCamera",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationPitch:F"))
    private float freelookRotationPitch(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraPitch;
        }
        return entity.rotationPitch;
    }

    @CInline
    @CRedirect(method = "orientCamera",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationPitch:F"))
    private float freelookPrevRotationPitch(Entity entity) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled) {
            return Freelook.cameraPitch;
        }
        return entity.prevRotationPitch;
    }

    @CInline
    @CRedirect(method = "updateCameraAndRender",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;inGameHasFocus:Z"))
    private boolean freelookMouse(Minecraft mc) {
        return Freelook.overrideMouse(mc);
    }
}
