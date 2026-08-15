package keystrokesmod.transformer.impl.client;

import keystrokesmod.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.world.storage.WorldInfo;

@CTransformer(WorldInfo.class)
public abstract class TransformerWorldInfo {
    @CInline
    @CInject(method = "isRaining", target = @CTarget("RETURN"), cancellable = true)
    private void setPrecipitation(InjectionCallback clr) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled()
                && ModuleManager.weather.rain.isToggled()) {
            clr.setReturnValue(true);
        }
    }
}
