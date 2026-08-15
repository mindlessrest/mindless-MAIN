package keystrokesmod.module.impl.movement;

import keystrokesmod.event.JumpEvent;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityPlayerSP;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.vecmath.Vector2d;

public class Jump45 extends Module {
    private final float INPUT_DAMPING = 0.98F;
    private final float SPRINT_JUMP_BOOST = 0.2F;
    private final float DEGREES_TO_RADIANS = (float) Math.PI / 180.0F;
    private final float TRIG_TABLE_STEP = 360.0F / 65536.0F;
    private final int GCD_SEARCH_RADIUS = 4;
    private final int JUMP_YAW_SEARCH_RADIUS = 4;

    private final ButtonSetting holdingBlocks;
    private final ButtonSetting holdingThrowable;
    private final ButtonSetting holdingRod;
    private final ButtonSetting targeting;

    private float takeoffYaw;
    private boolean rotateTakeoffPacket;

    public Jump45() {
        super("45 Jump", category.movement);
        this.registerSetting(holdingBlocks = new ButtonSetting("Holding blocks", true));
        this.registerSetting(holdingThrowable = new ButtonSetting("Holding throwable", true));
        this.registerSetting(holdingRod = new ButtonSetting("Holding rod", true));
        this.registerSetting(targeting = new ButtonSetting("Targeting", false));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onJump(JumpEvent e) {
        if (mc.thePlayer == null || mc.thePlayer.movementInput == null || e.entity != mc.thePlayer || !e.applySprint()
                || !mc.thePlayer.onGround
                || RotationHelper.get().isActive()
                || !canActivateForHeldItem()
                || targeting.isToggled() && !isTargeting()) {
            return;
        }

        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward < 0.99F || Math.abs(strafe) < 0.99F) {
            return;
        }

        RotationPlan plan = createRotationPlan(strafe);
        if (plan == null) {
            return;
        }

        takeoffYaw = plan.packetYaw;
        rotateTakeoffPacket = true;
        e.setYaw(plan.jumpYaw);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreMotion(PreMotionEvent event) {
        if (!rotateTakeoffPacket) {
            return;
        }

        event.setYaw(takeoffYaw);
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent event) {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    private RotationPlan createRotationPlan(float strafeInput) {
        float acceleration = getGroundAcceleration();
        if (!Float.isFinite(acceleration) || acceleration <= 0.0F) {
            return null;
        }

        Vector2d actualInput = getMovementAddition(
                mc.thePlayer.rotationYaw,
                strafeInput * INPUT_DAMPING,
                INPUT_DAMPING,
                acceleration
        );
        double actualInputLength = actualInput.length();
        float predictedForward = INPUT_DAMPING * acceleration;
        double predictedLength = SPRINT_JUMP_BOOST + predictedForward;
        if (actualInputLength <= 0.0D || predictedLength <= 0.0D) {
            return null;
        }

        double denominator = 2.0D * predictedLength * actualInputLength;
        double cosine = (predictedLength * predictedLength
                + actualInputLength * actualInputLength
                - SPRINT_JUMP_BOOST * SPRINT_JUMP_BOOST) / denominator;
        cosine = Math.max(-1.0D, Math.min(1.0D, cosine));

        float inputYaw = yawFromVector(actualInput, mc.thePlayer.rotationYaw);
        float correction = (float) Math.toDegrees(Math.acos(cosine));
        float idealPacketYaw = inputYaw + Math.copySign(correction, strafeInput);

        float lastReportedYaw = ((IAccessorEntityPlayerSP) mc.thePlayer).getLastReportedYaw();
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        double gcd = sensitivity * sensitivity * sensitivity * 1.2D;
        if (gcd <= 0.0D) {
            return evaluatePacketYaw(RotationHelper.unwrapYaw(idealPacketYaw, lastReportedYaw), actualInput);
        }

        float delta = MathHelper.wrapAngleTo180_float(idealPacketYaw - lastReportedYaw);
        long closestStep = Math.round(delta / gcd);
        RotationPlan best = null;
        for (int i = -GCD_SEARCH_RADIUS; i <= GCD_SEARCH_RADIUS; ++i) {
            float packetYaw = lastReportedYaw + (float) ((closestStep + i) * gcd);
            RotationPlan candidate = evaluatePacketYaw(packetYaw, actualInput);
            if (best == null || candidate.error < best.error) {
                best = candidate;
            }
        }
        return best;
    }

    private RotationPlan evaluatePacketYaw(float packetYaw, Vector2d actualInput) {
        Vector2d predicted = getMovementAddition(packetYaw, 0.0F, INPUT_DAMPING, getGroundAcceleration());
        predicted.add(getForwardVector(packetYaw, SPRINT_JUMP_BOOST));

        Vector2d requiredJump = new Vector2d(predicted.x - actualInput.x, predicted.y - actualInput.y);
        float baseJumpYaw = yawFromVector(requiredJump, packetYaw);
        RotationPlan best = null;

        for (int i = -JUMP_YAW_SEARCH_RADIUS; i <= JUMP_YAW_SEARCH_RADIUS; ++i) {
            float jumpYaw = baseJumpYaw + i * TRIG_TABLE_STEP;
            Vector2d actual = new Vector2d(actualInput.x, actualInput.y);
            actual.add(getForwardVector(jumpYaw, SPRINT_JUMP_BOOST));
            double error = distance(actual, predicted);
            if (best == null || error < best.error) {
                best = new RotationPlan(packetYaw, jumpYaw, error);
            }
        }
        return best;
    }

    private boolean canActivateForHeldItem() {
        boolean hasHeldItemFilter = holdingBlocks.isToggled()
                || holdingRod.isToggled()
                || holdingThrowable.isToggled();
        if (!hasHeldItemFilter) {
            return true;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return false;
        }

        Item item = held.getItem();
        return (holdingBlocks.isToggled() && item instanceof ItemBlock)
                || (holdingRod.isToggled() && item instanceof ItemFishingRod)
                || (holdingThrowable.isToggled() && (item instanceof ItemSnowball
                || item instanceof ItemEgg
                || item instanceof ItemEnderPearl
                || item == Items.experience_bottle
                || item instanceof ItemPotion && ItemPotion.isSplash(held.getMetadata())));
    }

    private boolean isTargeting() {
        if (KillAura.target != null) {
            return true;
        }

        return mc.gameSettings.keyBindAttack.isKeyDown()
                && mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit != null;
    }

    private float getGroundAcceleration() {
        BlockPos underPlayer = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        Block block = mc.theWorld.getBlockState(underPlayer).getBlock();
        float friction = block.slipperiness * 0.91F;
        return mc.thePlayer.getAIMoveSpeed()
                * (0.16277136F / (friction * friction * friction));
    }

    private Vector2d getMovementAddition(float yaw, float strafe, float forward, float acceleration) {
        float length = strafe * strafe + forward * forward;
        if (length < 1.0E-4F) {
            return new Vector2d(0.0D, 0.0D);
        }

        length = MathHelper.sqrt_float(length);
        if (length < 1.0F) {
            length = 1.0F;
        }

        float scale = acceleration / length;
        strafe *= scale;
        forward *= scale;
        float sin = MathHelper.sin(yaw * DEGREES_TO_RADIANS);
        float cos = MathHelper.cos(yaw * DEGREES_TO_RADIANS);
        return new Vector2d(strafe * cos - forward * sin, forward * cos + strafe * sin);
    }

    private Vector2d getForwardVector(float yaw, float magnitude) {
        float radians = yaw * DEGREES_TO_RADIANS;
        return new Vector2d(
                -MathHelper.sin(radians) * magnitude,
                MathHelper.cos(radians) * magnitude
        );
    }

    private float yawFromVector(Vector2d vector, float referenceYaw) {
        float yaw = (float) Math.toDegrees(Math.atan2(-vector.x, vector.y));
        return RotationHelper.unwrapYaw(yaw, referenceYaw);
    }

    private void reset() {
        rotateTakeoffPacket = false;
        takeoffYaw = 0.0F;
    }

    private double distance(Vector2d self, Vector2d other) {
        double deltaX = self.x - other.x;
        double deltaZ = self.y - other.y;
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    private static final class RotationPlan {
        private final float packetYaw;
        private final float jumpYaw;
        private final double error;

        private RotationPlan(float packetYaw, float jumpYaw, double error) {
            this.packetYaw = packetYaw;
            this.jumpYaw = jumpYaw;
            this.error = error;
        }
    }
}
