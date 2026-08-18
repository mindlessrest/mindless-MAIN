package mindless.transformer.impl.client;

import mindless.event.AttackEvent;
import mindless.event.UseItemEvent;
import mindless.module.impl.player.DelayRemover;
import mindless.utility.Utils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

@CTransformer(PlayerControllerMP.class)
public class TransformerPlayerControllerMP {
    @CShadow
    private int blockHitDelay;

    @CInline
    @CInject(method = {"clickBlock", "onPlayerDamageBlock"}, target = @CTarget("HEAD"))
    private void removeBreakDelayBeforeMiningAction(BlockPos pos, EnumFacing side, InjectionCallback cir) {
        if (DelayRemover.shouldRemoveBreakDelay()) this.blockHitDelay = 0;
    }

    @CInline
    @CInject(method = {"clickBlock", "onPlayerDamageBlock"}, target = @CTarget("RETURN"))
    private void removeBreakDelayAfterMiningAction(BlockPos pos, EnumFacing side, InjectionCallback cir) {
        if (DelayRemover.shouldRemoveBreakDelay()) this.blockHitDelay = 0;
    }

    @CInline
    @CInject(method = "onPlayerRightClick", target = @CTarget("HEAD"), cancellable = true)
    private void suppressCompetingPlacement(EntityPlayerSP player, WorldClient world,
                                             ItemStack stack, BlockPos pos, EnumFacing side,
                                             Vec3 hitVec, InjectionCallback cir) {
        if (stack != null && stack.getItem() instanceof ItemBlock
                && (Utils.shouldSuppressManualClicksForModulePlacementTick()
                || Utils.shouldBlockNonAutoPlaceBlockPlacementForCurrentTick())) {
            cir.setReturnValue(false);
        }
    }

    @CInline
    @CInject(method = "onPlayerRightClick", target = @CTarget("RETURN"))
    private void markModulePlacement(EntityPlayerSP player, WorldClient world,
                                     ItemStack stack, BlockPos pos, EnumFacing side,
                                     Vec3 hitVec, InjectionCallback cir) {
        if (stack != null && stack.getItem() instanceof ItemBlock
                && Boolean.TRUE.equals(cir.getReturnValue())) {
            Utils.markBlockPlacementSuppressionForCurrentTick();
        }
    }

    @CInline
    @CInject(method = "sendUseItem", target = @CTarget("HEAD"))
    public void injectUseItemEvent(EntityPlayer p1, World p2, ItemStack p3, InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new UseItemEvent(p3));
    }

    @CInline
    @CInject(method = "attackEntity", target = @CTarget("HEAD"), cancellable = true)
    private void injectAttackEntity(EntityPlayer playerIn, Entity targetEntity, InjectionCallback ci) {
        AttackEvent event = new AttackEvent(targetEntity, playerIn, true);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.setCancelled(true);
    }
}
