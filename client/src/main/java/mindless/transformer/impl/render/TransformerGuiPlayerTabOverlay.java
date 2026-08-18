package mindless.transformer.impl.render;

import mindless.module.ModuleManager;
import mindless.module.impl.other.NameHider;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;

@CTransformer(GuiPlayerTabOverlay.class)
public class TransformerGuiPlayerTabOverlay {
    @CInline
    @CInject(method = "getPlayerName", target = @CTarget("RETURN"), cancellable = true)
    private void nameHider$hideTabName(NetworkPlayerInfo networkPlayerInfoIn, InjectionCallback ci) {
        if (ModuleManager.nameHider != null && ModuleManager.nameHider.isEnabled()) {
            ci.setReturnValue(NameHider.getTabName(networkPlayerInfoIn, (String) ci.getReturnValue()));
        }
    }
}
