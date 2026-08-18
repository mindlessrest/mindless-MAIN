package mindless.transformer.impl.render;

import mindless.runtime.RenderGlobalState;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;

@CTransformer(RenderGlobal.class)
public class TransformerRenderGlobal {
    @CShadow
    private Minecraft mc;

    @CInline
    @CRedirect(method = "isRenderEntityOutlines",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isSpectator()Z",
                    optional = true))
    private boolean forceIsSpectator(EntityPlayerSP instance) {
        return RenderGlobalState.shouldRenderOutlines() || instance.isSpectator();
    }

    @CInline
    @CRedirect(method = "isRenderEntityOutlines",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/settings/KeyBinding;isKeyDown()Z",
                    optional = true))
    private boolean forceIsKeyDown(KeyBinding instance) {
        return RenderGlobalState.shouldRenderOutlines() || instance.isKeyDown();
    }

    @CInline
    @CRedirect(method = "renderEntities",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;shouldRenderInPass(I)Z",
                    ordinal = 1,
                    optional = true))
    private boolean forceShouldRenderInPass(Entity instance, int pass) {
        return pass == 1 || instance.shouldRenderInPass(pass);
    }

    @CInline
    @CRedirect(method = "renderEntities",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/optifine/reflect/Reflector;callBoolean(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;[Ljava/lang/Object;)Z",
                    ordinal = 1,
                    optional = true))
    private boolean forceShouldRenderInPassOptiFine(Object instance, Object reflectorMethod, Object[] parameters) {
        int pass = ((Integer) parameters[0]).intValue();
        return pass == 1 || ((Entity) instance).shouldRenderInPass(pass);
    }

    @CInline
    @CRedirect(method = "renderEntities",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;isInRangeToRender3d(DDD)Z",
                    ordinal = 1))
    private boolean forceIsInRangeToRender(Entity instance, double x, double y, double z) {
        return instance.isInRangeToRender3d(x, y, z)
                && RenderGlobalState.isOutlineActive(instance, this.mc);
    }
}
