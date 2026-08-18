package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
import mindless.runtime.LunarEventBridge;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;

@CTransformer(World.class)
public class TransformerWorld {
    @CInline
    @CInject(method = "spawnEntityInWorld", target = @CTarget("HEAD"))
    public void onEntityJoinWorld(Entity entityIn, InjectionCallback ci) {
        LunarEventBridge.postEntityJoin(entityIn, (World) (Object) this);
    }

    @CInline
    @CInject(method = "getThunderStrength", target = @CTarget("RETURN"), cancellable = true)
    public void setThunderStrength(InjectionCallback clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()) {
            if (ModuleManager.weather.clearWeather.isToggled()) {
                clr.setReturnValue(0.0F);
            } else if (ModuleManager.weather.lightning.getInput() > 0) {
                clr.setReturnValue((float) ModuleManager.weather.lightning.getInput());
            }
        }
    }

    @CInline
    @CInject(method = "getRainStrength", target = @CTarget("RETURN"), cancellable = true)
    public void setPrecipitationStrength(InjectionCallback clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()) {
            if (ModuleManager.weather.clearWeather.isToggled()) {
                clr.setReturnValue(0.0F);
            } else if (ModuleManager.weather.rain.isToggled()) {
                clr.setReturnValue(1.0F);
            }
        }
    }

    @CInline
    @CInject(method = "getSkyColor(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/util/Vec3;",
            target = @CTarget("RETURN"), cancellable = true)
    private void setSkyColor(Entity entity, float partialTicks, InjectionCallback ci) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.customSky.isToggled()) {
            ci.setReturnValue(new Vec3(ModuleManager.weather.skyColor.getRed() / 255.0,
                    ModuleManager.weather.skyColor.getGreen() / 255.0,
                    ModuleManager.weather.skyColor.getBlue() / 255.0));
        }
    }

    @CInline
    @CRedirect(method = {"getMoonPhase", "getCelestialAngle"},
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/world/storage/WorldInfo;getWorldTime()J"))
    private long setTimeForMoonPhase(WorldInfo worldInfo) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.customTime.isToggled()) {
            return (long) MathHelper.clamp_double((ModuleManager.weather.time.getInput() * 1000), 0, 23999);
        }
        return worldInfo.getWorldTime();
    }
}
