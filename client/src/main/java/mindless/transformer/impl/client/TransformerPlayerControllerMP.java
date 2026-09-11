package mindless.transformer.impl.client;

import mindless.event.AttackEvent;
import mindless.event.UseItemEvent;
import mindless.module.impl.player.DelayRemover;
import mindless.module.ModuleManager;
import mindless.module.impl.player.BedAura;
import mindless.placement.PlacementCoordinator;
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
    @CInject(method = {"clickBlock", "onPlayerDamageBlock"}, target = @CTarget("HEAD"), cancellable = true)
    private void suppressSilentControllerMining(BlockPos pos, EnumFacing side, InjectionCallback cir) {
        BedAura bedAura = ModuleManager.bedAura;
        if (bedAura != null && bedAura.shouldSuppressControllerMining()
                || ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressClicks()) {
            cir.setReturnValue(false);
        }
    }

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
        if (PlacementCoordinator.get().shouldBlockControllerAction(player, world, Utils.getBaseClientTick())
                || !mindless.placement.PlacementRuntime.isHotbarSynchronized()
                || !PlacementCoordinator.get().isControllerAction()
                && stack != null && stack.getItem() instanceof ItemBlock
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
        if (PlacementCoordinator.get().isControllerAction() && stack != null && stack.getItem() instanceof ItemBlock
                && Boolean.TRUE.equals(cir.getReturnValue())) {
            Utils.markBlockPlacementSuppressionForCurrentTick();
        }
    }

    @CInline
    @CInject(method = "sendUseItem", target = @CTarget("HEAD"), cancellable = true)
    public void injectUseItemEvent(EntityPlayer p1, World p2, ItemStack p3, InjectionCallback ci) {
        UseItemEvent event = new UseItemEvent(p3);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.setReturnValue(false);
    }

    @CInline
    @CInject(method = "attackEntity", target = @CTarget("HEAD"), cancellable = true)
    private void injectAttackEntity(EntityPlayer playerIn, Entity targetEntity, InjectionCallback ci) {
        AttackEvent event = new AttackEvent(targetEntity, playerIn, true);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.setCancelled(true);
    }

    @CInline
    @CInject(method = "onStoppedUsingItem", target = @CTarget("HEAD"), cancellable = true)
    private void auraStopUsing(net.minecraft.entity.player.EntityPlayer player, InjectionCallback ci) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressStopUse()) { ci.setCancelled(true); }
    }
}
