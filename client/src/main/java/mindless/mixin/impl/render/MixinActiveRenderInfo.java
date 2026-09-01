package mindless.mixin.impl.render;

import mindless.module.ModuleManager;
import mindless.module.impl.render.Freelook;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(ActiveRenderInfo.class)
public class MixinActiveRenderInfo {
    
    @Inject(method = "updateRenderInfo", at = @At("RETURN"))
    private static void onUpdateRenderInfoReturn(CallbackInfo ci) {
    }
}
