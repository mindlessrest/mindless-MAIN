package keystrokesmod.mixin.impl.world;

import keystrokesmod.module.ModuleManager;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(World.class)
public class MixinWorld {
    @Shadow
    @Final
    public boolean isRemote;

    @Inject(method = "getThunderStrength", at = @At("RETURN"), cancellable = true)
    public void setThunderStrength(CallbackInfoReturnable<Float> clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()) {
            if (ModuleManager.weather.clearWeather.isToggled()) {
                clr.setReturnValue(0.0F);
            } else if (ModuleManager.weather.lightning.getInput() > 0) {
                clr.setReturnValue((float) ModuleManager.weather.lightning.getInput());
            }
        }
    }

    @Inject(method = "getRainStrength", at = @At("RETURN"), cancellable = true)
    public void setPrecipitationStrength(CallbackInfoReturnable<Float> clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()) {
            if (ModuleManager.weather.clearWeather.isToggled()) {
                clr.setReturnValue(0.0F);
            } else if (ModuleManager.weather.rain.isToggled()) {
                clr.setReturnValue(1F);
            }
        }
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void setSkyColor(CallbackInfoReturnable<Vec3> cir) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.customSky.isToggled()) {
            cir.setReturnValue(new Vec3(ModuleManager.weather.skyColor.getRed() / 255.0,
                    ModuleManager.weather.skyColor.getGreen() / 255.0,
                    ModuleManager.weather.skyColor.getBlue() / 255.0));
        }
    }

    @Redirect(method = {"getMoonPhase", "getCelestialAngle"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/storage/WorldInfo;getWorldTime()J"))
    private long setTimeForMoonPhase(WorldInfo worldInfo) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.customTime.isToggled()) {
            return (long) MathHelper.clamp_double((ModuleManager.weather.time.getInput() * 1000), 0, 23999);
        }
        return worldInfo.getWorldTime();
    }
}
