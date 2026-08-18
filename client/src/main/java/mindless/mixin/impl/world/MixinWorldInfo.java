package mindless.mixin.impl.world;

import mindless.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(WorldInfo.class)
public abstract class MixinWorldInfo {

    @Inject(method = "isRaining", at = @At("RETURN"), cancellable = true)
    private void setPrecipitation(CallbackInfoReturnable<Boolean> clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()) {
            if (ModuleManager.weather.clearWeather.isToggled()) {
                clr.setReturnValue(false);
            } else if (ModuleManager.weather.rain.isToggled()) {
                clr.setReturnValue(true);
            }
        }
    }
}
