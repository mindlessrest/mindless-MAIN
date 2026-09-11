package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreMotionEvent;
import mindless.event.PostMotionEvent;
import mindless.event.PreUpdateEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.rotation.RotationSource;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class WaterBucket extends Module {
    public ButtonSetting pickupWater;
    public ButtonSetting silentAim;
    public ButtonSetting switchToItem;

    private enum Stage { IDLE, PLACE, WAIT_WATER, PICKUP, WAIT_PICKUP }

    private Stage stage = Stage.IDLE;
    private PlacementLease placementLease;
    private Object player;
    private Object world;
    private BlockPos waterPos;
    private int bucketSlot = -1;
    private int attemptTick;
    private long lastPlace;
    private PreMotionEvent finalMotion;

    public WaterBucket() {
        super("Water Bucket", "Drops water to break your fall, then takes it.", category.player);
        this.liteModule = true;
        this.registerSetting(pickupWater = new ButtonSetting("Pickup water", true));
        this.registerSetting(silentAim = new ButtonSetting("Silent aim", true));
        this.registerSetting(switchToItem = new ButtonSetting("Switch to item", true));
    }

    @Override
    public void onDisable() {
        resetExecution();
        lastPlace = 0L;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        updateExecution();
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        if (!updateExecution()) return;
        if (stage == Stage.IDLE) {
            if (!fallCheck() || System.currentTimeMillis() - lastPlace < 500L) return;
            int slot = isItem(mc.thePlayer.getHeldItem(), Items.water_bucket)
                    ? mc.thePlayer.inventory.currentItem : switchToItem.isToggled() ? getWaterBucketSlot() : -1;
            if (slot < 0) return;
            MovingObjectPosition hit = ray(mc.thePlayer.rotationYaw,
                    silentAim.isToggled() ? 90.0F : mc.thePlayer.rotationPitch, false);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                    || hit.sideHit != EnumFacing.UP) return;
            long tick = Utils.getBaseClientTick();
            PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.WATER_BUCKET,
                    mc.thePlayer, mc.theWorld, tick + 40L);
            placementLease = PlacementCoordinator.get().acquire(this, PlacementCoordinator.Priority.WATER_BUCKET,
                    mc.thePlayer, mc.theWorld, tick);
            if (placementLease == null) return;
            player = mc.thePlayer;
            world = mc.theWorld;
            bucketSlot = slot;
            waterPos = hit.getBlockPos().up();
            attemptTick = mc.thePlayer.ticksExisted;
            stage = Stage.PLACE;
            placementLease.claimHotbar(PlacementRuntime.hotbar(), bucketSlot);
        }
        if (silentAim.isToggled() && waterPos != null) {
            float[] rotations = RotationUtils.getRotationsToPoint(waterPos.getX() + 0.5,
                    waterPos.getY() + 0.5, waterPos.getZ() + 0.5,
                    mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            if (stage == Stage.PLACE || stage == Stage.WAIT_WATER) {
                rotations[0] = mc.thePlayer.rotationYaw;
                rotations[1] = 90.0F;
            }
            event.requestRotation(RotationSource.WATER_BUCKET, rotations[0], rotations[1]);
        }
    }

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        finalMotion = event;
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent event) {
        PreMotionEvent motion = finalMotion;
        finalMotion = null;
        if (!updateExecution() || motion == null || (stage != Stage.PLACE && stage != Stage.PICKUP)) return;
        if (silentAim.isToggled() && (motion.getYawSource() != RotationSource.WATER_BUCKET
                || motion.getPitchSource() != RotationSource.WATER_BUCKET)) {
            resetExecution();
            return;
        }
        final boolean pickup = stage == Stage.PICKUP;
        MovingObjectPosition hit = ray(motion.getYaw(), motion.getPitch(), pickup);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !(pickup ? waterPos.equals(hit.getBlockPos()) && isWaterSource()
                : hit.sideHit == EnumFacing.UP && waterPos.equals(hit.getBlockPos().up()) && fallCheck())) return;
        if (placementLease.tryControllerAction(Utils.getBaseClientTick(), new PlacementLease.ControllerAction() {
            @Override
            public boolean run() {
                return useCurrentItem(pickup ? Items.bucket : Items.water_bucket);
            }
        })) {
            stage = pickup ? Stage.WAIT_PICKUP : Stage.WAIT_WATER;
            attemptTick = mc.thePlayer.ticksExisted;
            if (!pickup) lastPlace = System.currentTimeMillis();
        }
    }

    private boolean updateExecution() {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.isGamePaused()
                || mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode
                || (ModuleManager.bedAura != null && ModuleManager.bedAura.controlsInteractions()
                && !ModuleManager.bedAura.shouldYieldInteractionTo(RotationSource.WATER_BUCKET))) {
            resetExecution();
            return false;
        }
        if (stage == Stage.IDLE) return true;
        if (player != mc.thePlayer || world != mc.theWorld || placementLease == null || !placementLease.isActive()
                || mc.thePlayer.inventory.currentItem != bucketSlot || mc.thePlayer.ticksExisted < attemptTick
                || mc.thePlayer.ticksExisted - attemptTick > 40) {
            resetExecution();
            return false;
        }
        if (stage == Stage.WAIT_WATER && isItem(mc.thePlayer.getHeldItem(), Items.bucket) && isWaterSource()) {
            if (!pickupWater.isToggled()) {
                resetExecution();
                return false;
            }
            if (System.currentTimeMillis() - lastPlace >= 150L) stage = Stage.PICKUP;
        } else if (stage == Stage.WAIT_PICKUP && isItem(mc.thePlayer.getHeldItem(), Items.water_bucket)
                && !isWaterSource()) {
            resetExecution();
            return false;
        }
        return true;
    }

    private MovingObjectPosition ray(float yaw, float pitch, boolean liquids) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtils.getVectorForRotation(pitch, yaw);
        double reach = Math.min(4.5D, mc.playerController.getBlockReachDistance());
        return mc.theWorld.rayTraceBlocks(eye, eye.addVector(look.xCoord * reach,
                look.yCoord * reach, look.zCoord * reach), liquids, false, false);
    }

    private boolean isWaterSource() {
        if (waterPos == null) return false;
        IBlockState state = mc.theWorld.getBlockState(waterPos);
        return state.getBlock().getMaterial() == Material.water
                && state.getBlock() instanceof BlockLiquid && state.getValue(BlockLiquid.LEVEL) == 0;
    }

    private boolean useCurrentItem(Item expectedItem) {
        if (!Utils.nullCheck() || mc.getNetHandler() == null || !PlacementRuntime.isHotbarSynchronized()
                || !isItem(mc.thePlayer.getHeldItem(), expectedItem)
                || mc.thePlayer.inventory.currentItem != bucketSlot
                || AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController) != bucketSlot) return false;
        C08PacketPlayerBlockPlacement packet = new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem());
        mc.getNetHandler().addToSendQueue(packet);
        return CombatPacketState.wasAccepted(packet);
    }

    private int getWaterBucketSlot() {
        for (int slot = 0; slot < InventoryPlayer.getHotbarSize(); ++slot) {
            if (isItem(mc.thePlayer.inventory.getStackInSlot(slot), Items.water_bucket)) return slot;
        }
        return -1;
    }

    private boolean isItem(ItemStack stack, Item item) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == item;
    }

    private boolean fallCheck() {
        return !mc.thePlayer.onGround && mc.thePlayer.fallDistance >= 3.3;
    }

    private void resetExecution() {
        PlacementCoordinator.get().cancel(this);
        if (placementLease != null) placementLease.release();
        placementLease = null;
        stage = Stage.IDLE;
        player = null;
        world = null;
        waterPos = null;
        bucketSlot = -1;
        finalMotion = null;
        RotationHelper.get().release(RotationSource.WATER_BUCKET);
    }
}
