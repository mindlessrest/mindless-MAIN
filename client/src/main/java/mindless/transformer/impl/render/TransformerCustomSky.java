package mindless.transformer.impl.render;

import mindless.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.util.MathHelper;
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
