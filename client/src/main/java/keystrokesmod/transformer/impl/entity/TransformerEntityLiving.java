package keystrokesmod.transformer.impl.entity;

import keystrokesmod.runtime.LunarEventBridge;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;

/** Supplies Forge's attack-target event in the direct Lunar runtime. */
@CTransformer(EntityLiving.class)
public class TransformerEntityLiving {
    @CInline
    @CInject(method = "setAttackTarget", target = @CTarget("HEAD"))
    private void setAttackTarget(EntityLivingBase target, InjectionCallback callback) {
        if (LunarEventBridge.isDirectLunar()) {
            MinecraftForge.EVENT_BUS.post(new LivingSetAttackTargetEvent(
                    (EntityLivingBase) (Object) this, target));
        }
    }
}
