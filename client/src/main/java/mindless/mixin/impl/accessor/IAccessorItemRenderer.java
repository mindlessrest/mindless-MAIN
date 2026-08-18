package mindless.mixin.impl.accessor;

import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemRenderer.class)
public interface IAccessorItemRenderer {
    @Accessor("equippedProgress")
    float getEquippedProgress();

    @Accessor("prevEquippedProgress")
    float getPrevEquippedProgress();

    @Invoker("transformFirstPersonItem")
    void invokeTransformFirstPersonItem(float equipProgress, float swingProgress);
}
