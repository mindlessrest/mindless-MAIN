package mindless.transformer.impl.render;

import mindless.runtime.LunarEventBridge;
import mindless.runtime.RenderGlobalState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

@CTransformer(RenderGlobal.class)
public class TransformerRenderGlobal {
    @CShadow
    private Minecraft mc;

    /**
     * Fire the block highlight event and honour a cancel.
     *
     * This is what Forge does at the same point. Without it Block Overlay never hears about a
     * targeted block under Lunar, so none of its modes -- not even Hidden, which only cancels
     * the vanilla box -- had any effect on that launch path.
     */
    @CInline
    @CInject(method = "drawSelectionBox", target = @CTarget("HEAD"), cancellable = true)
    private void onDrawSelectionBox(EntityPlayer player, MovingObjectPosition target,
                                    int execute, float partialTicks,
                                    InjectionCallback callback) {
        if (LunarEventBridge.postDrawBlockHighlight(player, target, execute, partialTicks)) {
            callback.setCancelled(true);
        }
    }

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
        return pass == 1 || RenderGlobalState.shouldRenderInPass(instance, pass);
    }

    @CInline
    @CRedirect(method = "renderEntities",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/optifine/reflect/Reflector;callBoolean(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;[Ljava/lang/Object;)Z",
                    ordinal = 1,
                    optional = true))
    private boolean forceShouldRenderInPassOptiFine(Object instance, Object reflectorMethod, Object[] parameters) {
        int pass = parameters != null && parameters.length > 0 && parameters[0] instanceof Integer
                ? ((Integer) parameters[0]).intValue() : 0;
        return pass == 1 || RenderGlobalState.shouldRenderInPass(instance, pass);
    }

    @CInline
    @CRedirect(method = "renderEntities",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;isInRangeToRender3d(DDD)Z",
                    ordinal = 1,
                    optional = true))
    private boolean forceIsInRangeToRender(Entity instance, double x, double y, double z) {
        return instance.isInRangeToRender3d(x, y, z)
                && RenderGlobalState.isOutlineActive(instance, this.mc);
    }
}
