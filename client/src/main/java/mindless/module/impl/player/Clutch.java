package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.rotation.RotationSource;
import mindless.event.ReceivePacketEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;

public class Clutch extends Module {
    private static final String[] MODES = {"Void", "Void Hit", "Void Hit Ignore FB"};
    private static final String[] THEMES = {"Default", "Rainbow", "Aurora", "Cherry", "Cotton Candy", "Flare", "Flower", "Forest", "Frost", "Gold", "Grayscale", "Inferno", "Royal", "Sandstorm", "Sky", "Vine"};
    private static final String[] MAX_FALL_OPTIONS = {"Void only", "1 block", "2 blocks", "3 blocks", "4 blocks", "5 blocks", "6 blocks", "7 blocks", "8 blocks", "9 blocks", "10 blocks", "11 blocks", "12 blocks", "13 blocks", "14 blocks", "15 blocks", "16 blocks", "17 blocks", "18 blocks", "19 blocks", "20 blocks"};
    private static final double REACH = 4.5;
    private static final int BLOCK_DELAY = 0;
    private static final int FIREBALL_DISABLE_TICKS = 10;
    private static final double SENS_GCD = 0.03404715;
    private static final double HALF_WIDTH = 0.3;
    private static final double[][] CORNERS = {{-HALF_WIDTH, -HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH}, {-HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, HALF_WIDTH}};

    private final SliderSetting speed;
    private final SliderSetting maxDistance;
    private final SliderSetting rotationTolerance;
    private final ButtonSetting simulateFuturePosition;
    private final SliderSetting minimumFallDistance;
    private final SliderSetting theme;
    private final SliderSetting mode;
    private final SliderSetting clutchCooldown;
    private final ButtonSetting antiSlip;
    private final SliderSetting antiSlipDuration;
    private final ButtonSetting legitMode;
    private final SliderSetting aimLinger;
    private final ButtonSetting stopMode;
    private final ButtonSetting renderBlock;
    private final ButtonSetting debugLogs;

    private Object lastPlayer;
    private Object lastWorld;
    private boolean placedDuringRescue;
    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placeQueued;
    private boolean placing;
    private PlacementLease placementLease;
    private boolean autoClickerWasOn;
    private int plannedSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private boolean hasAim;
    private boolean resetting;
    private int lastPlacedX = -999;
    private int lastPlacedY = -999;
    private int lastPlacedZ = -999;
    private int clutchBlocksPlaced;
    private final ArrayList<BlockPos> currentPath = new ArrayList<>();
    private BlockPos pathHead;
    private EnumFacing pathSide;
    private boolean hasActivePath;
    private int pathFailTicks;
    private int prevHurtTime = -1;
    private int antiSlipTicks;
    private boolean antiSlipSneaking;
    private int aimLingerTicks;
    private boolean bridgeFailed;
    private int lowestBridgeY = -999;
    private long cooldownUntil;
    private int lastPlaceTick = -999;
    private int lastPlayerId = -1;
    private int lastSeenTicks = -1;
    private boolean hitTriggered;
    private long lastHitTime;
    private long lastExplosionTime;
    private long lastNearFireballTime;
    private long fireballDisableUntil;
    private long fireballHitTime;
    private boolean fireballTriggered;
    private float serverYaw;
    private float serverPitch;
    private BlockPos renderBlockPos;
    private long renderUntil;
    private float lastPlacedYawDelta = Float.NaN;
    private float queuedYawDelta = Float.NaN;
    private boolean knockbackSteering;
    private double knockbackMotionX;
    private double knockbackMotionZ;
    private int safeLandingTicks;

    public Clutch() {
        super("AutoClutch", "Places a bridge under you when a fall would exceed the configured limit.", category.player);
        this.registerSetting(theme = new SliderSetting("Theme", 0, THEMES));
        this.registerSetting(renderBlock = new ButtonSetting("Render Block", true));
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(minimumFallDistance = new SliderSetting("Max fall", 4, MAX_FALL_OPTIONS));
        this.registerSetting(clutchCooldown = new SliderSetting("Clutch Cooldown", " ticks", 20, 0, 100, 1));
        this.registerSetting(speed = new SliderSetting("Speed", 35, 0, 100, 1));
        this.registerSetting(maxDistance = new SliderSetting("Max distance", " block", 10, 0, 20, 1));
        this.registerSetting(rotationTolerance = new SliderSetting("Rotation Tolerance", "\u00B0", 35, 10, 100, 1));
        this.registerSetting(antiSlip = new ButtonSetting("Anti-slip-off", true));
        this.registerSetting(antiSlipDuration = new SliderSetting("Anti-slip duration", " ticks", 20, 5, 60, 1));
        this.registerSetting(legitMode = new ButtonSetting("Legit Mode", true));
        this.registerSetting(aimLinger = new SliderSetting("Aim Linger", " ticks", 6, 1, 20, 1));
        this.registerSetting(stopMode = new ButtonSetting("Stop mode", true));
        this.registerSetting(simulateFuturePosition = new ButtonSetting("Simulate future position", true));
        this.registerSetting(debugLogs = new ButtonSetting("Debug Logs", false));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        resetPlayerState();
    }

    private void resetPlayerState() {
        cancelInteraction();
        lastPlayer = mc.thePlayer;
        lastWorld = mc.theWorld;
        if (Utils.nullCheck()) {
            lastPlayerId = mc.thePlayer.getEntityId();
            lastSeenTicks = mc.thePlayer.ticksExisted;
            serverYaw = mc.thePlayer.rotationYaw;
            serverPitch = mc.thePlayer.rotationPitch;
            prevHurtTime = mc.thePlayer.hurtTime;
        } else {
            lastPlayerId = -1;
            lastSeenTicks = -1;
            prevHurtTime = -1;
        }
        hasAim = false;
        resetting = false;
        resetPath();
        clutchBlocksPlaced = 0;
        placedDuringRescue = false;
        antiSlipTicks = 0;
        antiSlipSneaking = false;
        aimLingerTicks = 0;
        bridgeFailed = false;
        lowestBridgeY = -999;
        cooldownUntil = 0L;
        lastPlaceTick = -999;
        hitTriggered = false;
        lastHitTime = 0L;
        lastExplosionTime = 0L;
        lastNearFireballTime = 0L;
        fireballDisableUntil = 0L;
        fireballHitTime = 0L;
        fireballTriggered = false;
        renderBlockPos = null;
        renderUntil = 0L;
        lastPlacedYawDelta = Float.NaN;
        queuedYawDelta = Float.NaN;
        stopKnockbackSteering();
        safeLandingTicks = 0;
    }

    @Override
    public void onDisable() {
        PlacementCoordinator.get().cancel(this);
        clearAim(false);
        disablePlacing(true);
        placeQueued = false;
        setAntiSlipSneaking(false);
        antiSlipTicks = 0;
        bridgeFailed = false;
        lowestBridgeY = -999;
        hitTriggered = false;
        fireballTriggered = false;
        fireballDisableUntil = 0L;
        cooldownUntil = 0L;
        lastPlaceTick = -999;
        lastPlayerId = -1;
        lastSeenTicks = -1;
        renderBlockPos = null;
        renderUntil = 0L;
        lastPlacedYawDelta = Float.NaN;
        queuedYawDelta = Float.NaN;
        stopKnockbackSteering();
        safeLandingTicks = 0;
    }

    public boolean isActiveWindow() {
        return isEnabled() && (hasAim || placeQueued || isPlacementActive());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!canInteract()) {
            cancelInteraction();
            return;
        }
        if (!isKnockbackSteeringActive()) return;

        float yaw = RotationHelper.get().getServerYaw() != null
                ? RotationHelper.get().getServerYaw() : serverYaw;
        float[] input = inputAgainstMotion(knockbackMotionX, knockbackMotionZ, yaw);
        if (input[0] == 0.0F && input[1] == 0.0F) {
            stopKnockbackSteering();
            return;
        }
        event.setForward(input[0]);
        event.setStrafe(input[1]);
        event.setJump(false);
        event.setSneak(false);
        RotationHelper.get().setServerRelativeMovementInputs(true);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!canInteract()) {
            cancelInteraction();
            return;
        }
        placeQueued = false;
        runPrePlayerInteract();

        float baseYaw = e.getBaseYaw() != null ? e.getBaseYaw() : RotationUtils.serverRotations[0];
        float basePitch = e.getBasePitch() != null ? e.getBasePitch() : RotationUtils.serverRotations[1];
        serverYaw = baseYaw;
        serverPitch = basePitch;

        if (!isPlacementActive()) return;

        if (resetting) {
            aimYaw = mc.thePlayer.rotationYaw;
            aimPitch = mc.thePlayer.rotationPitch;
            float[] smoothed = getRotationsSmoothed(baseYaw, basePitch, aimYaw, aimPitch, true);
            if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - aimYaw)) < 0.5f && Math.abs(smoothed[1] - aimPitch) < 0.5f) {
                resetting = false;
                restoreInputsAndAutoClicker();
                return;
            }
            RotationHelper.get().forceMovementFix = true;
            e.requestRotation(mindless.rotation.RotationSource.CLUTCH, smoothed[0], smoothed[1]);
            return;
        }

        if (!hasAim) return;

        float[] smoothed = getRotationsSmoothed(baseYaw, basePitch, aimYaw, aimPitch, false);
        if (placing && targetHitPos != null) smoothed = selectPlacementRotation(baseYaw, smoothed);

        if (placing && targetHitPos != null) {
            MovingObjectPosition mop = RotationUtils.rayCastBlock(REACH, smoothed[0], smoothed[1]);
            if (mop != null && targetHitPos.equals(mop.getBlockPos()) && targetSide == mop.sideHit) {
                int maxBlocks = (int) maxDistance.getInput();
                if (maxBlocks == 0 || clutchBlocksPlaced < maxBlocks) {
                    double tolerance = rotationTolerance.getInput();
                    if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - aimYaw)) <= tolerance
                            && Math.abs(smoothed[1] - aimPitch) <= tolerance) {
                        int tick = mc.thePlayer.ticksExisted;
                        if (tick - lastPlaceTick >= BLOCK_DELAY
                                && placementLease.tryControllerAction(Utils.getBaseClientTick(), new PlacementLease.ControllerAction() {
                            @Override
                            public boolean run() {
                                return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                                        mc.thePlayer.getHeldItem(), mop.getBlockPos(), mop.sideHit, mop.hitVec);
                            }
                        })) {
                            lastPlaceTick = tick;
                            onBlockPlaced(mop.getBlockPos(), mop.sideHit, Math.abs(wrapYawDelta(baseYaw, smoothed[0])));
                        } else {
                            placeAtBlock = mop.getBlockPos();
                            hitSide = mop.sideHit;
                            hitVec = mop.hitVec;
                            queuedYawDelta = Math.abs(wrapYawDelta(baseYaw, smoothed[0]));
                            placeQueued = true;
                        }
                    }
                }
            }
        }

        RotationHelper.get().forceMovementFix = true;
        e.requestRotation(mindless.rotation.RotationSource.CLUTCH, smoothed[0], smoothed[1]);
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!canInteract()) {
            cancelInteraction();
            return;
        }
        if (lastPlayer != mc.thePlayer || lastWorld != mc.theWorld || mc.thePlayer.ticksExisted < lastSeenTicks) {
            resetPlayerState();
        }
        updateDamageAndLandingState();
        updateAntiSlip();

        if (!isPlacementActive()) {
            placeQueued = false;
            return;
        }

        if (!placeQueued) return;
        if (System.currentTimeMillis() < cooldownUntil
                || ((int) mode.getInput() == 2 && (isFireballSuppressed() || fireballTriggered))) {
            placeQueued = false;
            return;
        }

        placeQueued = false;
        float placedYawDelta = queuedYawDelta;
        queuedYawDelta = Float.NaN;
        if (placeAtBlock != null && hitSide != null && hitVec != null
                && placementLease.tryControllerAction(Utils.getBaseClientTick(), new PlacementLease.ControllerAction() {
            @Override
            public boolean run() {
                return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                        mc.thePlayer.getHeldItem(), placeAtBlock, hitSide, hitVec);
            }
        })) {
            lastPlaceTick = mc.thePlayer.ticksExisted;
            onBlockPlaced(placeAtBlock, hitSide, placedYawDelta);
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.getPacket() instanceof S27PacketExplosion) {
            lastExplosionTime = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (Utils.nullCheck() && e.entity == mc.thePlayer) {
            resetPlayerState();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!renderBlock.isToggled()) return;
        int color = themeColor();
        for (BlockPos block : currentPath) RenderUtils.renderBlock(block, color, true, true);
        if (renderBlockPos != null && (hasAim || placing || aimLingerTicks > 0 || System.currentTimeMillis() < renderUntil)) {
            RenderUtils.renderBlock(renderBlockPos, color, true, true);
        }
    }

    private int themeColor() {
        int selected = (int) theme.getInput();
        long now = System.currentTimeMillis();
        if (selected == 1) {
            float hue = (now % 3000L) / 3000.0f;
            return 0xAA000000 | java.awt.Color.HSBtoRGB(hue, 0.75f, 1.0f) & 0x00FFFFFF;
        }

        int[][] colors = {
                {156, 107, 255, 156, 107, 255},
                {115, 1, 194, 23, 240, 177},
                {221, 61, 105, 224, 179, 183},
                {146, 218, 232, 237, 104, 184},
                {242, 107, 22, 228, 166, 29},
                {200, 154, 216, 172, 89, 185},
                {31, 118, 23, 96, 166, 35},
                {223, 227, 227, 188, 197, 202},
                {229, 223, 48, 218, 218, 182},
                {97, 99, 104, 231, 232, 234},
                {53, 0, 0, 192, 57, 18},
                {133, 191, 232, 29, 61, 135},
                {157, 147, 105, 245, 227, 180},
                {129, 234, 248, 21, 188, 211},
                {39, 228, 57, 154, 248, 161}
        };
        int index = Math.max(0, Math.min(colors.length - 1, selected - 1));
        int[] pair = colors[index];
        double progress = (Math.sin(now / 1200.0) + 1.0) * 0.5;
        int red = (int) (pair[0] + (pair[3] - pair[0]) * progress);
        int green = (int) (pair[1] + (pair[4] - pair[1]) * progress);
        int blue = (int) (pair[2] + (pair[5] - pair[2]) * progress);
        return 0xAA000000 | red << 16 | green << 8 | blue;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (isPlacementActive() && e.button > -1 && e.isCancelable()) {
            e.setCanceled(true);
        }
    }

    private boolean canInteract() {
        return Utils.nullCheck() && mc.currentScreen == null
                && (ModuleManager.bedAura == null || !ModuleManager.bedAura.controlsInteractions()
                || ModuleManager.bedAura.shouldYieldInteractionTo(RotationSource.CLUTCH));
    }

    private void cancelInteraction() {
        disablePlacing(true);
        clearAim(false);
        resetting = false;
        aimLingerTicks = 0;
        stopKnockbackSteering();
        setAntiSlipSneaking(false);
        antiSlipTicks = 0;
        RotationHelper.get().release(RotationSource.CLUTCH);
    }

    private void runPrePlayerInteract() {
        int currentTick = mc.thePlayer.ticksExisted;
        if (lastPlayer != mc.thePlayer || lastWorld != mc.theWorld || currentTick < lastSeenTicks) {
            resetPlayerState();
        }
        lastSeenTicks = currentTick;
        if (currentTick < lastPlaceTick) lastPlaceTick = -999;

        if (stopMode.isToggled() && bridgeFailed) {
            stopKnockbackSteering();
            clearAim(true);
            disablePlacing(false);
            return;
        }

        if (System.currentTimeMillis() < cooldownUntil || isFireballSuppressed()) {
            if (hasAim && legitMode.isToggled() && aimLingerTicks > 0) {
                aimLingerTicks--;
                if (placing) equipPlannedSlot();
                return;
            }
            stopKnockbackSteering();
            clearAim(true);
            disablePlacing(false);
            return;
        }

        int selectedMode = (int) mode.getInput();
        boolean fallingFarOrVoid = willFallFar(minimumFallDistance.getInput());
        boolean recentlyHit = hitTriggered || System.currentTimeMillis() - lastHitTime < 2000L || mc.thePlayer.hurtTime > 0;
        boolean active = fallingFarOrVoid && (selectedMode == 0 || recentlyHit);
        boolean settlingLanding = placedDuringRescue && safeLandingTicks > 0 && safeLandingTicks < 3;
        if (selectedMode == 2 && (fireballTriggered || isFireballSuppressed())) active = false;
        if (mc.currentScreen != null || !active && !settlingLanding) {
            if (hasAim && legitMode.isToggled() && aimLingerTicks > 0) {
                aimLingerTicks--;
                if (placing) equipPlannedSlot();
                return;
            }
            stopKnockbackSteering();
            clearAim(true);
            disablePlacing(false);
            return;
        }
        if (settlingLanding) {
            disablePlacing(false);
            return;
        }

        if (stopMode.isToggled() && placedDuringRescue && lowestBridgeY != -999
                && mc.thePlayer.posY < lowestBridgeY - 1.2) {
            bridgeFailed = true;
            stopKnockbackSteering();
            clearAim(true);
            disablePlacing(false);
            return;
        }

        BlockPos below = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        if (!canPlaceThrough(below)) {
            stopKnockbackSteering();
            disablePlacing(false);
            return;
        }

        int weakSlot = pickBlockSlot();
        if (weakSlot == -1) {
            stopKnockbackSteering();
            disablePlacing(false);
            return;
        }

        plannedSlot = weakSlot;
        AimResult target = clutchAim();
        if (target != null) {
            targetHitPos = target.ray.getBlockPos();
            targetSide = target.ray.sideHit;
            aimYaw = target.yaw;
            aimPitch = target.pitch;
            hasAim = true;
            resetting = false;
            renderBlockPos = target.ray.getBlockPos().offset(target.ray.sideHit);
            renderUntil = System.currentTimeMillis() + 1200L;
            if (legitMode.isToggled()) aimLingerTicks = (int) aimLinger.getInput();
        } else if (hasAim && legitMode.isToggled() && aimLingerTicks > 0) {
            aimLingerTicks--;
        } else {
            if (stopMode.isToggled() && placedDuringRescue) bridgeFailed = true;
            stopKnockbackSteering();
            clearAim(true);
            disablePlacing(false);
            return;
        }

        if (hasAim && !isPlacementActive()) enablePlacing();

        if (!isPlacementActive()) {
            return;
        }

        if (placing || resetting || hasAim) {
            placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindAttack), false);
            placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindUseItem), false);
            equipPlannedSlot();
        }
    }

    private void updateDamageAndLandingState() {
        long now = System.currentTimeMillis();
        for (Object entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityFireball && mc.thePlayer.getDistanceSqToEntity((EntityFireball) entity) < 64.0) {
                lastNearFireballTime = now;
            }
        }

        int hurtTime = mc.thePlayer.hurtTime;
        if (prevHurtTime >= 0 && hurtTime > prevHurtTime) {
            if (now - lastNearFireballTime < 1000L || now - lastExplosionTime < 1000L) {
                fireballDisableUntil = now + FIREBALL_DISABLE_TICKS * 50L;
                fireballHitTime = now;
                fireballTriggered = true;
                stopKnockbackSteering();
                clearAim(false);
                disablePlacing(true);
            } else {
                hitTriggered = true;
                lastHitTime = now;
                bridgeFailed = false;
                double horizontalMotion = Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ);
                knockbackSteering = horizontalMotion > 0.01D;
                knockbackMotionX = mc.thePlayer.motionX;
                knockbackMotionZ = mc.thePlayer.motionZ;
                if (!placedDuringRescue) {
                    resetPath();
                    clearAim(false);
                }
            }
        }
        prevHurtTime = hurtTime;

        boolean standingOnBlock = mc.thePlayer.onGround && hasSolidBelow(new Vec3(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
        safeLandingTicks = nextSafeLandingTicks(safeLandingTicks, standingOnBlock);
        if (isSafeFromKnockback()) stopKnockbackSteering();
        if (safeLandingTicks == 3 && placedDuringRescue) {
            int cooldownTicks = (int) clutchCooldown.getInput();
            cooldownUntil = now + cooldownTicks * 50L;
            if (antiSlip.isToggled()) antiSlipTicks = (int) antiSlipDuration.getInput();
            clutchBlocksPlaced = 0;
            placedDuringRescue = false;
            bridgeFailed = false;
            if (!legitMode.isToggled() || aimLingerTicks <= 0) {
                clearAim(false);
                disablePlacing(true);
            }
        }
        if (safeLandingTicks == 3) {
            hitTriggered = false;
            lastHitTime = 0L;
            fireballTriggered = false;
            if (now > fireballHitTime + 500L) fireballDisableUntil = 0L;
        }
    }

    private void updateAntiSlip() {
        if (!antiSlip.isToggled() || antiSlipTicks <= 0) {
            setAntiSlipSneaking(false);
            return;
        }

        antiSlipTicks--;
        setAntiSlipSneaking(mc.thePlayer.onGround && isAtEdge());
    }

    private boolean isAtEdge() {
        int feetY = MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1;
        for (double[] corner : CORNERS) {
            BlockPos pos = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX + corner[0]),
                    feetY,
                    MathHelper.floor_double(mc.thePlayer.posZ + corner[1])
            );
            if (canPlaceThrough(pos)) return true;
        }
        double motionX = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
        double motionZ = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
        if (Math.abs(motionX) > 0.01 || Math.abs(motionZ) > 0.01) {
            for (double[] corner : CORNERS) {
                BlockPos pos = new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX + motionX * 1.5 + corner[0]),
                        feetY,
                        MathHelper.floor_double(mc.thePlayer.posZ + motionZ * 1.5 + corner[1]));
                if (canPlaceThrough(pos)) return true;
            }
        }
        return false;
    }

    private void setAntiSlipSneaking(boolean pressed) {
        if (antiSlipSneaking == pressed) return;
        PlacementLease.InputState input = PlacementRuntime.input(mc.gameSettings.keyBindSneak);
        input.setPressed(pressed || input.isPhysicallyPressed());
        antiSlipSneaking = pressed;
    }

    private boolean isFireballSuppressed() {
        return (int) mode.getInput() == 2 && System.currentTimeMillis() < fireballDisableUntil;
    }

    private void debug(String message) {
        if (debugLogs.isToggled()) Utils.sendMessage("&7[&dR&7] &dAutoClutch&7: &f" + message);
    }

    private void onBlockPlaced(BlockPos support, EnumFacing side, float yawDelta) {
        if (!Float.isNaN(yawDelta)) lastPlacedYawDelta = yawDelta;
        placedDuringRescue = true;
        if (side != EnumFacing.UP) clutchBlocksPlaced++;
        lastPlacedX = support.getX();
        lastPlacedY = support.getY();
        lastPlacedZ = support.getZ();
        renderBlockPos = support.offset(side);
        renderUntil = System.currentTimeMillis() + 1200L;
        if (lowestBridgeY == -999 || renderBlockPos.getY() < lowestBridgeY) lowestBridgeY = renderBlockPos.getY();
        Vec3 playerPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        if (hasSolidBelow(playerPosition) || isBlockUnderPlayer(renderBlockPos, playerPosition)) {
            resetPath();
            targetHitPos = null;
            targetSide = null;
        } else {
            pathHead = renderBlockPos;
            if (!hasActivePath || pathSide == null) pathSide = side.getAxis().isHorizontal() ? side : facingDirection();
            hasActivePath = true;
            pathFailTicks = 0;
            if (!currentPath.contains(renderBlockPos)) {
                currentPath.add(renderBlockPos);
                if (currentPath.size() > 20) currentPath.remove(0);
            }
            targetHitPos = renderBlockPos;
            targetSide = pathSide;
        }
        mc.thePlayer.swingItem();
        debug("Placed block on " + side + " of " + support.getX() + ", " + support.getY() + ", " + support.getZ());

        int maxBlocks = (int) maxDistance.getInput();
        if (maxBlocks > 0 && clutchBlocksPlaced >= maxBlocks) {
            cooldownUntil = System.currentTimeMillis() + (long) clutchCooldown.getInput() * 50L;
            if (!legitMode.isToggled() || aimLingerTicks <= 0) {
                clearAim(false);
                disablePlacing(true);
            }
        }
    }

    private void enablePlacing() {
        if (isPlacementActive()) return;
        if (placing) disablePlacing(false);
        long tick = Utils.getBaseClientTick();
        PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.CLUTCH,
                mc.thePlayer, mc.theWorld, tick + 1L);
        PlacementLease lease = PlacementCoordinator.get().acquire(
                this, PlacementCoordinator.Priority.CLUTCH, mc.thePlayer, mc.theWorld, tick);
        if (lease == null) return;
        placementLease = lease;
        placing = true;
        autoClickerWasOn = ModuleManager.autoClicker != null && ModuleManager.autoClicker.isEnabled();
        if (autoClickerWasOn && ModuleManager.autoClicker != null) {
            ModuleManager.autoClicker.disable();
        }
    }

    private void disablePlacing(boolean forceRestore) {
        placeQueued = false;
        placeAtBlock = null;
        hitSide = null;
        hitVec = null;
        PlacementCoordinator.get().cancel(this);
        placing = false;
        releasePlacement();
        plannedSlot = -1;
        restoreInputsAndAutoClicker();
    }

    private void clearAim(boolean allowSnapback) {
        targetHitPos = null;
        targetSide = null;
        lastPlacedX = lastPlacedY = lastPlacedZ = -999;
        queuedYawDelta = Float.NaN;
        resetPath();
        if (allowSnapback && hasAim) resetting = true;
        hasAim = false;
    }

    private boolean isKnockbackSteeringActive() {
        return knockbackSteering && !isSafeFromKnockback() && hasAim && !resetting
                && !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && !mc.thePlayer.capabilities.isFlying;
    }

    private boolean isSafeFromKnockback() {
        return safeLandingTicks >= 3 || isDirectionReversed(
                knockbackMotionX, knockbackMotionZ, mc.thePlayer.motionX, mc.thePlayer.motionZ);
    }

    private void stopKnockbackSteering() {
        knockbackSteering = false;
        knockbackMotionX = 0.0D;
        knockbackMotionZ = 0.0D;
    }

    private void restoreInputsAndAutoClicker() {
        if (autoClickerWasOn && ModuleManager.autoClicker != null) {
            ModuleManager.autoClicker.enable();
        }
        autoClickerWasOn = false;
    }

    private boolean willFallFar(double minFall) {
        if (mc.thePlayer.onGround) return false;
        boolean overVoid = isOverVoid();
        boolean recentlyHit = hitTriggered || System.currentTimeMillis() - lastHitTime < 2000L || mc.thePlayer.hurtTime > 0;
        if (!overVoid && recentlyHit) {
            int startY = MathHelper.floor_double(mc.thePlayer.posY);
            double motionX = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
            double motionZ = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
            overVoid = isOverVoidAt(mc.thePlayer.posX, mc.thePlayer.posZ, startY)
                    || isOverVoidAt(mc.thePlayer.posX + motionX * 1.5, mc.thePlayer.posZ + motionZ * 1.5, startY);
        }
        if (minFall == 0.0) return overVoid;
        if (overVoid) return true;
        double distance = distanceToGround(mc.thePlayer.posX, mc.thePlayer.posZ, MathHelper.floor_double(mc.thePlayer.posY));
        return distance < 0.0 || distance > minFall || mc.thePlayer.fallDistance > minFall;
    }

    private boolean isOverVoid() {
        int startY = MathHelper.floor_double(mc.thePlayer.posY);
        if (!isOverVoidAt(mc.thePlayer.posX, mc.thePlayer.posZ, startY)) return false;
        for (double[] corner : CORNERS) {
            if (!isOverVoidAt(mc.thePlayer.posX + corner[0], mc.thePlayer.posZ + corner[1], startY)) return false;
        }
        return true;
    }

    private boolean isOverVoidAt(double x, double z, int startY) {
        if (startY < 0) return true;
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        for (int y = startY; y >= 0; y--) {
            if (!canPlaceThrough(new BlockPos(blockX, y, blockZ))) return false;
        }
        return true;
    }

    private double distanceToGround(double x, double z, int startY) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        for (int y = startY; y >= 0; y--) {
            if (!canPlaceThrough(new BlockPos(blockX, y, blockZ))) return mc.thePlayer.posY - (y + 1.0);
        }
        return -1.0;
    }

    private AimResult clutchAim() {
        Vec3 playerPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);

        if (hasActivePath && !currentPath.isEmpty()) {
            if (hasSolidBelow(playerPos)) {
                resetPath();
            } else {
                for (int i = currentPath.size() - 1; i >= 0; i--) {
                    BlockPos pathBlock = currentPath.get(i);
                    if (canPlaceThrough(pathBlock) || BlockUtils.dist2PointAABB(playerPos, pathBlock) > (REACH + 1.2) * (REACH + 1.2)) continue;
                    boolean underPlayer = isBlockUnderPlayer(pathBlock, playerPos);
                    ItemStack held = mc.thePlayer.inventory.mainInventory[plannedSlot];
                    AimResult result = getBestRotationsToBlock(held, pathBlock, eye, REACH, underPlayer, pathSide, playerPos);
                    if (result == null) result = getBestRotationsToBlock(held, pathBlock, eye, REACH, underPlayer, null, playerPos);
                    if (result != null) {
                        pathFailTicks = 0;
                        return result;
                    }
                }
                resetPath();
                if (stopMode.isToggled() && placedDuringRescue) {
                    bridgeFailed = true;
                    return null;
                }
            }
        }

        if (targetHitPos != null && !canPlaceThrough(targetHitPos)
                && BlockUtils.dist2PointAABB(playerPos, targetHitPos) <= (REACH + 1.2) * (REACH + 1.2)) {
            ItemStack held = mc.thePlayer.inventory.mainInventory[plannedSlot];
            AimResult result = getBestRotationsToBlock(held, targetHitPos, eye, REACH,
                    isBlockUnderPlayer(targetHitPos, playerPos), targetSide, playerPos);
            if (result != null) return result;
        }

        Vec3 futurePos = playerPos;
        if (simulateFuturePosition.isToggled() && !mc.thePlayer.onGround) {
            double motionX = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
            double motionY = mc.thePlayer.posY - mc.thePlayer.lastTickPosY - 0.08;
            double motionZ = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
            futurePos = playerPos.addVector(motionX * 3.0, motionY * 3.0, motionZ * 3.0);
        }

        int feetX = MathHelper.floor_double(playerPos.xCoord);
        int feetZ = MathHelper.floor_double(playerPos.zCoord);
        int feetY = MathHelper.floor_double(playerPos.yCoord);
        int minX = feetX - 5;
        int maxX = feetX + 5;
        int minZ = feetZ - 5;
        int maxZ = feetZ + 5;
        int maxY = feetY + 1;
        int minY = feetY - 4;

        ArrayList<BlockCandidate> candidates = new ArrayList<>();
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (canPlaceThrough(pos)) continue;

                    double currentDist = BlockUtils.dist2PointAABB(playerPos, pos);
                    if (currentDist > (REACH + 1.2) * (REACH + 1.2)) continue;
                    double futureDist = BlockUtils.dist2PointAABB(futurePos, pos);
                    double score = simulateFuturePosition.isToggled() ? (currentDist * 0.3 + futureDist * 0.7) : currentDist;
                    double horizontalDistance = Math.hypot(pos.getX() + 0.5 - playerPos.xCoord, pos.getZ() + 0.5 - playerPos.zCoord);
                    if (horizontalDistance < 1.6) score -= 15.0;
                    if (isBlockUnderPlayer(pos, playerPos)) score -= 25.0;
                    candidates.add(new BlockCandidate(score, pos));
                }
            }
        }

        candidates.sort((a, b) -> Double.compare(a.score, b.score));

        ItemStack held = plannedSlot >= 0 && plannedSlot <= 8 ? mc.thePlayer.inventory.mainInventory[plannedSlot] : null;
        for (BlockCandidate candidate : candidates) {
            boolean underPlayer = isBlockUnderPlayer(candidate.pos, playerPos);
            AimResult result = getBestRotationsToBlock(held, candidate.pos, eye, REACH, underPlayer, null, playerPos);
            if (result != null) return result;
        }

        return null;
    }

    private boolean isBlockUnderPlayer(BlockPos blockPos, Vec3 pos) {
        if (blockPos.getY() > MathHelper.floor_double(pos.yCoord + 0.2)) return false;
        for (double[] corner : CORNERS) {
            int cx = MathHelper.floor_double(pos.xCoord + corner[0]);
            int cz = MathHelper.floor_double(pos.zCoord + corner[1]);
            if (blockPos.getX() == cx && blockPos.getZ() == cz) return true;
        }
        return false;
    }

    private AimResult getBestRotationsToBlock(ItemStack held, BlockPos targetCell, Vec3 eye, double reachVal,
                                               boolean underPlayer, EnumFacing preferredSide, Vec3 playerPos) {
        double inset = 0.05;
        double step = 0.2;
        double jitter = step * 0.1;
        boolean faceSouth = Math.abs(eye.zCoord - (targetCell.getZ() + 1)) < Math.abs(eye.zCoord - targetCell.getZ());
        boolean faceEast = Math.abs(eye.xCoord - (targetCell.getX() + 1)) < Math.abs(eye.xCoord - targetCell.getX());
        float baseYaw = normYaw(serverYaw);
        float basePitch = serverPitch;
        int n = (int) Math.round(1 / step);

        ArrayList<RotationCandidate> candidates = new ArrayList<>();
        candidates.add(new RotationCandidate(0, baseYaw, basePitch));

        if (preferredSide != null) {
            for (int row = 0; row <= n; row++) {
                double v = clamp01(row * step + randomRange(-jitter, jitter));
                for (int col = 0; col <= n; col++) {
                    double u = clamp01(col * step + randomRange(-jitter, jitter));
                    float[] rotation = rotationsForFace(eye, targetCell, preferredSide, u, v, inset);
                    if (rotation != null) {
                        double cost = (Math.abs(wrapYawDelta(baseYaw, rotation[0])) + Math.abs(rotation[1] - basePitch)) * 0.8;
                        candidates.add(new RotationCandidate(cost, rotation[0], rotation[1]));
                    }
                }
            }
        }

        for (int row = 0; row <= n; row++) {
            double v = clamp01(row * step + randomRange(-jitter, jitter));
            for (int col = 0; col <= n; col++) {
                double u = clamp01(col * step + randomRange(-jitter, jitter));

                if (underPlayer) {
                    float[] rV = getRotationsWrapped(eye, targetCell.getX() + u, targetCell.getY() + 1 - inset, targetCell.getZ() + v);
                    double costV = Math.abs(wrapYawDelta(baseYaw, rV[0])) + Math.abs(rV[1] - basePitch);
                    candidates.add(new RotationCandidate(costV, rV[0], rV[1]));
                }

                float[] rZ = getRotationsWrapped(eye, targetCell.getX() + u, targetCell.getY() + v, faceSouth ? targetCell.getZ() + 1 - inset : targetCell.getZ() + inset);
                double costZ = Math.abs(wrapYawDelta(baseYaw, rZ[0])) + Math.abs(rZ[1] - basePitch);
                candidates.add(new RotationCandidate(costZ, rZ[0], rZ[1]));

                float[] rX = getRotationsWrapped(eye, faceEast ? targetCell.getX() + 1 - inset : targetCell.getX() + inset, targetCell.getY() + v, targetCell.getZ() + u);
                double costX = Math.abs(wrapYawDelta(baseYaw, rX[0])) + Math.abs(rX[1] - basePitch);
                candidates.add(new RotationCandidate(costX, rX[0], rX[1]));
            }
        }

        candidates.sort((a, b) -> Double.compare(a.cost, b.cost));

        AimResult fallback = null;
        for (RotationCandidate candidate : candidates) {
            float yaw = unwrapYaw(candidate.yaw, serverYaw);
            MovingObjectPosition ray = RotationUtils.rayCastBlock(reachVal, yaw, candidate.pitch);
            if (ray == null) continue;

            EnumFacing face = ray.sideHit;
            if (face == EnumFacing.DOWN) continue;
            if (face == EnumFacing.UP && !underPlayer) continue;
            if (!targetCell.equals(ray.getBlockPos())) continue;
            if (!BlockUtils.canPlaceBlockOnSide(held, ray.getBlockPos(), face)) continue;
            BlockPos placed = targetCell.offset(face);
            if (placed.getY() > MathHelper.floor_double(playerPos.yCoord + 0.2)) continue;
            if (!canPlaceThrough(placed) || intersectsPlayer(placed, playerPos)) continue;
            AimResult result = new AimResult(ray, yaw, candidate.pitch);
            if (face == preferredSide || isBlockUnderPlayer(placed, playerPos)) return result;
            if (fallback == null) fallback = result;
        }

        return fallback;
    }

    private float[] rotationsForFace(Vec3 eye, BlockPos block, EnumFacing face, double u, double v, double inset) {
        switch (face) {
            case NORTH: return getRotationsWrapped(eye, block.getX() + u, block.getY() + v, block.getZ() + inset);
            case SOUTH: return getRotationsWrapped(eye, block.getX() + u, block.getY() + v, block.getZ() + 1 - inset);
            case WEST: return getRotationsWrapped(eye, block.getX() + inset, block.getY() + v, block.getZ() + u);
            case EAST: return getRotationsWrapped(eye, block.getX() + 1 - inset, block.getY() + v, block.getZ() + u);
            case UP: return getRotationsWrapped(eye, block.getX() + u, block.getY() + 1 - inset, block.getZ() + v);
            default: return null;
        }
    }

    private boolean intersectsPlayer(BlockPos block, Vec3 player) {
        return player.xCoord + 0.3 > block.getX() && player.xCoord - 0.3 < block.getX() + 1.0
                && player.yCoord + 1.8 > block.getY() && player.yCoord < block.getY() + 0.99
                && player.zCoord + 0.3 > block.getZ() && player.zCoord - 0.3 < block.getZ() + 1.0;
    }

    private boolean hasSolidBelow(Vec3 position) {
        return position != null && !canPlaceThrough(new BlockPos(
                MathHelper.floor_double(position.xCoord),
                MathHelper.floor_double(position.yCoord - 0.1),
                MathHelper.floor_double(position.zCoord)));
    }

    private void resetPath() {
        hasActivePath = false;
        currentPath.clear();
        pathSide = null;
        pathHead = null;
        pathFailTicks = 0;
    }

    private EnumFacing facingDirection() {
        double motionX = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
        double motionZ = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
        if (Math.abs(motionX) > 0.05 || Math.abs(motionZ) > 0.05) {
            if (Math.abs(motionX) > Math.abs(motionZ)) return motionX > 0 ? EnumFacing.EAST : EnumFacing.WEST;
            return motionZ > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
        return EnumFacing.fromAngle(mc.thePlayer.rotationYaw);
    }

    private int pickBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        int best = -1;
        int bestScore = -1;

        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize == 0 || !(stack.getItem() instanceof ItemBlock)) continue;
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            String name = String.valueOf(Block.blockRegistry.getNameForObject(block)).toLowerCase();
            if (isUnplaceable(name)) continue;
            int score = blockScore(name);
            if (score < 0) score = 1;
            if (slot == current && score >= bestScore || score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private int blockScore(String name) {
        if (name.contains("wool") || name.contains("stone")) return 5;
        if (name.contains("clay")) return 4;
        if (name.contains("glass")) return 3;
        if (name.contains("planks") || name.contains("log")) return 2;
        if (name.contains("end_stone")) return 1;
        if (name.contains("obsidian")) return 0;
        return -1;
    }

    private boolean isUnplaceable(String name) {
        return name.contains("torch") || name.contains("sapling") || name.contains("flower")
                || name.contains("ladder") || name.contains("button") || name.contains("lever")
                || name.contains("bed") || name.contains("door") || name.contains("rail")
                || name.contains("carpet") || name.contains("web") || name.contains("sign")
                || name.contains("slab") || name.contains("stairs") || name.contains("chest")
                || name.contains("furnace") || name.contains("crafting_table") || name.contains("anvil");
    }

    private void equipPlannedSlot() {
        if (plannedSlot != -1 && placementLease != null) {
            placementLease.claimHotbar(PlacementRuntime.hotbar(), plannedSlot);
        }
    }

    private boolean isPlacementActive() {
        return placing && placementLease != null && placementLease.isActive();
    }

    private void releasePlacement() {
        if (placementLease != null) {
            placementLease.release();
            placementLease = null;
        }
    }

    private float[] getRotationsSmoothed(float currentYaw, float currentPitch, float targetYaw, float targetPitch, boolean snapback) {
        float deltaYaw = wrapYawDelta(currentYaw, targetYaw);
        float deltaPitch = targetPitch - currentPitch;
        if (Math.abs(deltaYaw) < SENS_GCD && Math.abs(deltaPitch) < SENS_GCD) {
            return new float[]{currentYaw + deltaYaw, RotationUtils.clampPitch(targetPitch)};
        }
        float speedValue = (float) speed.getInput();
        if (speedValue <= 0.0f) return new float[]{currentYaw + deltaYaw, RotationUtils.clampPitch(targetPitch)};
        float totalDelta = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (totalDelta < 0.05f) return new float[]{currentYaw + deltaYaw, RotationUtils.clampPitch(targetPitch)};
        float unitYaw = deltaYaw / totalDelta;
        float unitPitch = deltaPitch / totalDelta;
        float baseStep;
        if (snapback) {
            float progress = Math.min(1.0f, totalDelta / 45.0f);
            baseStep = speedValue * (0.35f + 0.65f * (float) Math.sin(progress * Math.PI * 0.5));
            if (totalDelta < 6.0f) baseStep = Math.min(baseStep, Math.max(0.5f, totalDelta * 0.6f));
        } else {
            baseStep = Math.max(3.0f, speedValue * (1.0f - (float) randomRange(0.0, 0.08)));
        }
        float moveYaw;
        float movePitch;
        if (totalDelta <= baseStep) {
            moveYaw = deltaYaw;
            movePitch = deltaPitch;
        } else {
            moveYaw = unitYaw * baseStep + (float) randomRange(-0.035, 0.035);
            movePitch = unitPitch * baseStep + (float) randomRange(-0.028, 0.028);
        }
        currentYaw += quantizeDelta(moveYaw);
        currentPitch += quantizeDelta(movePitch);
        if (Math.abs(wrapYawDelta(currentYaw, targetYaw)) < SENS_GCD) currentYaw += wrapYawDelta(currentYaw, targetYaw);
        if (Math.abs(targetPitch - currentPitch) < SENS_GCD) currentPitch = targetPitch;
        return new float[]{currentYaw, RotationUtils.clampPitch(currentPitch)};
    }

    private float[] selectPlacementRotation(float baseYaw, float[] rotation) {
        float yawDelta = Math.abs(wrapYawDelta(baseYaw, rotation[0]));
        if (!isRepeatedPlacementYawDelta(yawDelta, lastPlacedYawDelta)) return rotation;

        for (int step = 1; step <= 6; step++) {
            for (int direction = -1; direction <= 1; direction += 2) {
                float yaw = rotation[0] + (float) (direction * SENS_GCD * step);
                MovingObjectPosition ray = RotationUtils.rayCastBlock(REACH, yaw, rotation[1]);
                if (ray != null && targetHitPos.equals(ray.getBlockPos()) && targetSide == ray.sideHit
                        && !isRepeatedPlacementYawDelta(Math.abs(wrapYawDelta(baseYaw, yaw)), lastPlacedYawDelta)) {
                    return new float[]{yaw, rotation[1]};
                }
            }
        }
        return rotation;
    }

    private static float[] inputAgainstMotion(double motionX, double motionZ, float yaw) {
        double length = Math.hypot(motionX, motionZ);
        if (length < 0.01D) return new float[]{0.0F, 0.0F};

        double desiredX = -motionX / length;
        double desiredZ = -motionZ / length;
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        float bestForward = 0.0F;
        float bestStrafe = 0.0F;
        double bestScore = -Double.MAX_VALUE;
        for (float forward = -1.0F; forward <= 1.0F; forward++) {
            for (float strafe = -1.0F; strafe <= 1.0F; strafe++) {
                if (forward == 0.0F && strafe == 0.0F) continue;
                double moveX = strafe * cos - forward * sin;
                double moveZ = forward * cos + strafe * sin;
                double score = (moveX * desiredX + moveZ * desiredZ) / Math.sqrt(moveX * moveX + moveZ * moveZ);
                if (score > bestScore) {
                    bestScore = score;
                    bestForward = forward;
                    bestStrafe = strafe;
                }
            }
        }
        return new float[]{bestForward, bestStrafe};
    }

    private static int nextSafeLandingTicks(int previousTicks, boolean standingOnBlock) {
        return standingOnBlock ? Math.min(3, previousTicks + 1) : 0;
    }

    private static boolean isDirectionReversed(double originalX, double originalZ, double currentX, double currentZ) {
        return Math.hypot(originalX, originalZ) > 0.01D && Math.hypot(currentX, currentZ) > 0.01D
                && originalX * currentX + originalZ * currentZ < 0.0D;
    }

    private static boolean isRepeatedPlacementYawDelta(float yawDelta, float previousYawDelta) {
        return yawDelta > 2.0f && !Float.isNaN(previousYawDelta)
                && Math.abs(yawDelta - previousYawDelta) < 0.0001f;
    }

    private float quantizeDelta(float delta) {
        if (Math.abs(delta) < 0.0001f) return 0.0f;
        return (float) (Math.round(delta / SENS_GCD) * SENS_GCD);
    }

    private boolean canPlaceThrough(BlockPos pos) {
        Block block = BlockUtils.getBlock(pos);
        Material material = block.getMaterial();
        return material == Material.air || material == Material.water || material == Material.lava || block == Blocks.fire;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double randomRange(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private static float normYaw(float yaw) {
        yaw = ((yaw % 360f) + 360f) % 360f;
        return yaw > 180f ? yaw - 360f : yaw;
    }

    private static float wrapYawDelta(float base, float target) {
        return MathHelper.wrapAngleTo180_float(target - base);
    }

    private static float unwrapYaw(float yaw, float prevYaw) {
        return prevYaw + MathHelper.wrapAngleTo180_float(yaw - prevYaw);
    }

    private static float[] getRotationsWrapped(Vec3 eye, double tx, double ty, double tz) {
        double dx = tx - eye.xCoord;
        double dy = ty - eye.yCoord;
        double dz = tz - eye.zCoord;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));
        return new float[]{normYaw(yaw), RotationUtils.clampPitch(pitch)};
    }

    private static class BlockCandidate {
        final double score;
        final BlockPos pos;

        BlockCandidate(double score, BlockPos pos) {
            this.score = score;
            this.pos = pos;
        }
    }

    private static class RotationCandidate {
        final double cost;
        final float yaw;
        final float pitch;

        RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class AimResult {
        final MovingObjectPosition ray;
        final float yaw;
        final float pitch;

        AimResult(MovingObjectPosition ray, float yaw, float pitch) {
            this.ray = ray;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

}
