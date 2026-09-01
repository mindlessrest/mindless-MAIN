package mindless.mixin.impl.render;

import mindless.module.ModuleManager;
import mindless.module.impl.render.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import org.lwjgl.util.vector.Vector3f;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@SideOnly(Side.CLIENT)
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Shadow
    @Final
    private Minecraft mc;

    @Unique
    private boolean shouldRender() {
        return ModuleManager.sexyESP != null && ModuleManager.sexyESP != null && ModuleManager.sexyESP.isEnabled() && ModuleManager.sexyESP.isGlowEnabled();
    }

    @Unique
    private boolean mindless$isFreelookActive() {
        return ModuleManager.freelook != null && ModuleManager.freelook.isEnabled() && Freelook.perspectiveToggled;
    }

    @Redirect(method = "setupTerrain", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationPitch:F"))
    private float mindless$setupTerrainRotationPitch(Entity entity) {
        if (entity == mc.getRenderViewEntity() && mindless$isFreelookActive()) {
            return Freelook.cameraPitch;
        }
        return entity.rotationPitch;
    }

    @Redirect(method = "setupTerrain", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationYaw:F"))
    private float mindless$setupTerrainRotationYaw(Entity entity) {
        if (entity == mc.getRenderViewEntity() && mindless$isFreelookActive()) {
            return Freelook.cameraYaw;
        }
        return entity.rotationYaw;
    }

    @Redirect(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;getViewVector(Lnet/minecraft/entity/Entity;D)Lorg/lwjgl/util/vector/Vector3f;"
            )
    )
    private Vector3f mindless$setupTerrainViewVector(RenderGlobal renderGlobal, Entity entityIn, double partialTicks) {
        float pitch;
        float yaw;
        if (entityIn == mc.getRenderViewEntity() && mindless$isFreelookActive()) {
            pitch = Freelook.cameraPitch;
            yaw = Freelook.cameraYaw;
        } else {
            pitch = (float) ((double) entityIn.prevRotationPitch + (double) (entityIn.rotationPitch - entityIn.prevRotationPitch) * partialTicks);
            yaw = (float) ((double) entityIn.prevRotationYaw + (double) (entityIn.rotationYaw - entityIn.prevRotationYaw) * partialTicks);
        }
        if (mc.gameSettings.thirdPersonView == 2) {
            pitch += 180.0F;
        }
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        return new Vector3f(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }
}