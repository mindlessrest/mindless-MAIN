package mindless.transformer.impl.render;

import mindless.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.util.MathHelper;

/**
 * OptiFine's CustomSky.renderSky reads world time to pick a sky texture.
 * Weather.time overrides that. We target the class by string because
 * OptiFine isn't a compile-time dep — RavenTransformerManager only
 * registers this transformer when net.optifine.CustomSky is present.
 *
 * The mixin was a @ModifyVariable("STORE"); we replicate with a @CInject
 * at HEAD that sets a ThreadLocal that CustomSky code can consult. Since
 * we can't recompile OptiFine, the cleanest we can do without OptiFine
 * source is to expose the override via {@link WeatherTimeOverride} and
 * hope OptiFine calls into vanilla WorldInfo.getWorldTime — which
 * TransformerWorld already redirects.
 *
 * In practice, TransformerWorld.setTimeForMoonPhase already redirects
 * WorldInfo.getWorldTime for the vanilla sky path. This transformer is
 * therefore a no-op today; keeping it registered leaves room to add a
 * proper @CInject once we know OptiFine's byte layout.
 */
@CTransformer(name = {"net.optifine.CustomSky"})
public class TransformerCustomSky {
    @CInline
    @CInject(method = "renderSky", target = @CTarget("HEAD"))
    private static void onRenderSkyHead(InjectionCallback ci) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.customTime.isToggled()) {
            long overridden = (long) MathHelper.clamp_double(
                    ModuleManager.weather.time.getInput() * 1000, 0, 23999);
            WeatherTimeOverride.set(overridden);
        } else {
            WeatherTimeOverride.clear();
        }
    }
}
