package mindless.mixin.impl.client;

import mindless.event.AttackEvent;
import mindless.event.UseItemEvent;
import mindless.module.ModuleManager;
import mindless.module.impl.player.BedAura;
import mindless.module.impl.player.FastMine;
import mindless.placement.PlacementCoordinator;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerControllerMP.class)
public class MixinPlayerControllerMP {

    @Shadow
    private int blockHitDelay;

    @Inject(method = {"clickBlock", "onPlayerDamageBlock"}, at = @At("HEAD"), cancellable = true)
    private void mindless$suppressSilentControllerMining(BlockPos pos, EnumFacing side,
                                                         CallbackInfoReturnable<Boolean> callbackInfo) {
        BedAura bedAura = ModuleManager.bedAura;
        if (bedAura != null && bedAura.shouldSuppressControllerMining()
                || ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressClicks()) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "onPlayerRightClick", at = @At("HEAD"), cancellable = true)
    private void mindless$gatePlacementAction(EntityPlayerSP player, WorldClient world, ItemStack stack,
                                              BlockPos pos, EnumFacing side, net.minecraft.util.Vec3 hitVec,
                                              CallbackInfoReturnable<Boolean> callbackInfo) {
        if (PlacementCoordinator.get().shouldBlockControllerAction(player, world, Utils.getBaseClientTick())
                || !mindless.placement.PlacementRuntime.isHotbarSynchronized()) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "sendUseItem(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    public void injectUseItemEvent(EntityPlayer p_sendUseItem_1_, World p_sendUseItem_2_, ItemStack p_sendUseItem_3_, CallbackInfoReturnable<Boolean> ci) {
        UseItemEvent event = new UseItemEvent(p_sendUseItem_3_);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void injectAttackEntity(EntityPlayer playerIn, Entity targetEntity, CallbackInfo callbackInfo) {
        AttackEvent event = new AttackEvent(targetEntity, playerIn, true);

        MinecraftForge.EVENT_BUS.post(event);

        if (event.isCanceled()) {
            callbackInfo.cancel();
        }
    }

    @Redirect(
        method = "onPlayerDamageBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;getPlayerRelativeBlockHardness(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;Lnet/minecraft/util/BlockPos;)F"
        )
    )
    private float fastMineScaleHardness(Block block, EntityPlayer player, World worldIn, BlockPos pos) {
        float hardness = block.getPlayerRelativeBlockHardness(player, worldIn, pos);
        FastMine fm = ModuleManager.fastMine;
        if (fm == null) {
            return hardness;
        }
        return hardness * fm.getBreakSpeedMultiplier();
    }
@Unique
    private void mindless$fastMineApplyBreakDelaySlider() {
        FastMine fm = ModuleManager.fastMine;
        if (fm == null) {
            return;
        }
        int o = fm.getBlockHitDelayOverrideOrMinusOne();
        if (o >= 0) {
            this.blockHitDelay = o;
        }
    }

    @Inject(
        method = "clickBlock",
        at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;blockHitDelay:I", ordinal = 0, shift = At.Shift.AFTER)
    )
    private void mindless$fastMineAfterClickBlockSetDelay(BlockPos loc, EnumFacing face, CallbackInfoReturnable<Boolean> cir) {
        mindless$fastMineApplyBreakDelaySlider();
    }

    @Inject(
        method = "onPlayerDamageBlock",
        at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;blockHitDelay:I", ordinal = 1, shift = At.Shift.AFTER)
    )
    private void mindless$fastMineAfterCreativeMiningSetDelay(BlockPos posBlock, EnumFacing directionFacing, CallbackInfoReturnable<Boolean> cir) {
        mindless$fastMineApplyBreakDelaySlider();
    }

    @Inject(
        method = "onPlayerDamageBlock",
        at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;blockHitDelay:I", ordinal = 2, shift = At.Shift.AFTER)
    )
    private void mindless$fastMineAfterBreakBlockSetDelay(BlockPos posBlock, EnumFacing directionFacing, CallbackInfoReturnable<Boolean> cir) {
        mindless$fastMineApplyBreakDelaySlider();
    }

    @Inject(method = "onStoppedUsingItem", at = @At("HEAD"), cancellable = true)
    private void auraStopUsing(net.minecraft.entity.player.EntityPlayer player, CallbackInfo ci) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressStopUse()) { ci.cancel(); }
    }
}
