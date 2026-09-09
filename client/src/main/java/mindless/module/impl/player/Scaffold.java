package mindless.module.impl.player;

import java.awt.Color;
import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.SendPacketEvent;
import mindless.event.RightClickDelayTickEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.Setting;
import mindless.utility.RenderUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import mindless.utility.shader.RoundedUtils;
import mindless.module.impl.render.HUD;
import mindless.utility.font.FontManager;
import mindless.utility.font.ModuleFont;
import mindless.utility.font.MindlessFontRenderer;
import org.lwjgl.opengl.GL20;
import java.awt.Color;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

public class Scaffold extends Module {
    private static final ItemBlock PLACEHOLDER = new ItemBlock(Blocks.tnt);
    private final SliderSetting mode;
    private final SliderSetting rotationSpeed;
    private final SliderSetting sprint;
    private final ButtonSetting keepY;
    private ButtonSetting keepYOnRightClick;
    private ButtonSetting keepYAutoJump;
    private SliderSetting keepYJumpChance;
    private final ButtonSetting eagle;
    private final SliderSetting eagleSafety;
    private final ButtonSetting switchBack;
    private final TestScaffold telly = new TestScaffold();
    private int activeMode = -1;

    private BlockPos previewPos;
    private EnumFacing previewFace;
    private BlockPos queuedPos;
    private EnumFacing queuedFace;
    private Vec3 queuedVec;
    private boolean placeQueued;

    /**
     * The Y the bridge is being held at, or MIN_VALUE when not holding one.
     *
     * This is the whole of Keep Y. Without it the search always looks one block under your
     * feet, so walking off a ledge simply builds downward with you. Locking the level and
     * searching from there is what makes the bridge stay flat and what turns a jump into a
     * telly rather than a step down.
     */
    private int keepYLevel = Integer.MIN_VALUE;
    /** Whether the last tick was airborne under our own jump, for re-locking on landing. */
    private boolean keepYJumping;
    /** Rolled once per jump so the chance is per hop rather than per tick. */
    private boolean keepYJumpRolled;
    private boolean keepYJumpAllowed = true;
    private boolean keepYJumpHeld;
    private final java.util.Random keepYRandom = new java.util.Random();

    private float lastYaw, lastPitch;
    private boolean lastRotsValid;

    private boolean eagleActive;

    /** Whether Sprint Scaf Mode currently owns the sprint key, and what it last asked for. */
    private boolean sprintScafActive;
    private boolean sprintScafSprinting;

    private int wdBlocksPlaced;
    private float wdOverrideYaw = Float.NaN;
    private float wdOverrideSpeed = Float.NaN;

private int previousSlot = -1;

    public Scaffold() {
        super("Scaffold", "Bridges by placing blocks under your feet.", category.player);
        this.closetModule = true;
        this.registerSetting(mode = new SliderSetting("Mode", 0, new String[]{"Normal", "Telly"}));
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 180, 1, 360, 1));
        this.registerSetting(sprint = new SliderSetting("Sprint", 0, new String[]{"Off", "Legit", "Watchdog"}));
        this.registerSetting(keepY = new ButtonSetting("Keep Y", false));
        this.registerSetting(keepYOnRightClick = new ButtonSetting("Keep Y on right click", false));
        this.registerSetting(keepYAutoJump = new ButtonSetting("Keep Y auto jump", true));
        this.registerSetting(keepYJumpChance = new SliderSetting("Keep Y jump chance", "%", 100.0, 0.0, 100.0, 5.0));
        this.registerSetting(eagle = new ButtonSetting("Eagle", false));
        this.registerSetting(eagleSafety = new SliderSetting("Eagle safety", " tick", 1, 1, 3, 0.1));
        this.registerSetting(switchBack = new ButtonSetting("Switch back", true));
        for (Setting setting : telly.getSettings()) {
            this.registerSetting(setting);
        }
    }

    @Override
    public void guiUpdate() {
        boolean normal = !isTellyMode();
        rotationSpeed.setVisible(normal, this);
        sprint.setVisible(normal, this);
        keepY.setVisible(normal, this);
        keepYOnRightClick.setVisible(normal, this);
        keepYAutoJump.setVisible(normal, this);
        keepYJumpChance.setVisible(normal, this);
        eagle.setVisible(normal, this);
        eagleSafety.setVisible(normal, this);
        switchBack.setVisible(normal, this);
        for (Setting setting : telly.getSettings()) {
            setting.setVisible(!normal, this);
        }
        syncMode();
    }

    @Override
    public void onEnable() {
        activeMode = isTellyMode() ? 1 : 0;
        if (activeMode == 1) {
            telly.onEnable();
            return;
        }
        enableNormal();
    }

    private void enableNormal() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        sprintScafActive = false;
        sprintScafSprinting = false;
        previousSlot = Utils.nullCheck() ? mc.thePlayer.inventory.currentItem : -1;
        previewPos = null;
        previewFace = null;
        queuedPos = null;
        queuedFace = null;
        queuedVec = null;
        placeQueued = false;
        lastRotsValid = false;
        eagleActive = false;
        wdBlocksPlaced = 0;
        wdOverrideYaw = Float.NaN;
        wdOverrideSpeed = Float.NaN;
    }

    @Override
    public void onDisable() {
        if (activeMode == 1) {
            telly.onDisable();
        } else {
            disableNormal();
        }
        activeMode = -1;
    }

    private void disableNormal() {
        previewPos = null;
        previewFace = null;
        queuedPos = null;
        queuedFace = null;
        queuedVec = null;
        placeQueued = false;
        lastRotsValid = false;
        if (eagleActive) {
            setShiftOverride(false);
            eagleActive = false;
        }
        releaseSprintScaffold();
        // The hop presses the jump key; leaving it held here is how a module gets
        // blamed for the player bouncing after it was switched off.
        releaseKeepYJump();
        keepYLevel = Integer.MIN_VALUE;
        keepYJumping = false;
        keepYJumpRolled = false;
        restorePreviousSlot();
    }

    private boolean isTellyMode() {
        return (int) mode.getInput() == 1;
    }

    @Override
    public String getInfo() {
        return isTellyMode() ? "Telly" : "Normal";
    }

    private void syncMode() {
        if (!isEnabled()) {
            return;
        }
        int selected = isTellyMode() ? 1 : 0;
        if (selected == activeMode) {
            return;
        }
        if (activeMode == 1) {
            telly.onDisable();
        } else if (activeMode == 0) {
            disableNormal();
        }
        activeMode = selected;
        if (activeMode == 1) {
            telly.onEnable();
        } else {
            enableNormal();
        }
    }
private void restorePreviousSlot() {
        int slot = previousSlot;
        previousSlot = -1;

        if (!switchBack.isToggled() || slot < 0 || slot > 8 || !Utils.nullCheck()) {
            return;
        }
        if (mc.thePlayer.inventory.currentItem == slot) {
            return;
        }

        mc.thePlayer.inventory.currentItem = slot;
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        syncMode();
        if (isTellyMode()) return;
        if (!Utils.nullCheck()) return;

        float baseYaw = getBaseYaw();

        if (!Float.isNaN(wdOverrideYaw) && (int) sprint.getInput() == 2) {
            baseYaw = wdOverrideYaw;
            float curYaw = lastRotsValid ? lastYaw : RotationUtils.serverRotations[0];
            float diff = Math.abs(MathHelper.wrapAngleTo180_float(wdOverrideYaw - curYaw));
            if (diff < 5f) {
                wdOverrideYaw = Float.NaN;
                wdOverrideSpeed = Float.NaN;
            }
        }

        updateKeepYLevel();
        BlockData best = findBestPlacement();
        updateKeepYJump(best != null);
        boolean willFall = Utils.isEdgeOfBlock() && mc.thePlayer.motionY < 0.3;

        if (best == null) {
            previewPos = null;
            previewFace = null;
            float[] rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                    lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                    baseYaw, 82f);
            rots = RotationUtils.fixRotation(rots[0], rots[1],
                    RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
            e.setYaw(rots[0]);
            e.setPitch(rots[1]);
            lastYaw = rots[0];
            lastPitch = rots[1];
            lastRotsValid = true;
            return;
        }

        previewPos = best.pos;
        previewFace = best.face;

        Item item = getBlockItem();
        if (item == null) return;

        float[] rots;
        if (willFall) {
            float[] solved = getRotationsForFace(best.pos, best.face, baseYaw);
            if (solved != null) {
                rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                        lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                        baseYaw, solved[1]);
                rots[0] = baseYaw;
            } else {
                rots = getFreeRotationsForFace(best.pos, best.face);
                rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                        lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                        rots[0], rots[1]);
            }
        } else {
            rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                    lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                    baseYaw, 82f);
        }

        float[] fixed = RotationUtils.fixRotation(rots[0], rots[1],
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

        e.setYaw(willFall ? (getRotationsForFace(best.pos, best.face, baseYaw) != null ? baseYaw : fixed[0]) : fixed[0]);
        e.setPitch(fixed[1]);

        lastYaw = e.yaw != null ? e.yaw : fixed[0];
        lastPitch = e.pitch != null ? e.pitch : fixed[1];
        lastRotsValid = true;

        float useYaw = e.yaw != null ? e.yaw : fixed[0];
        float usePitch = e.pitch != null ? e.pitch : fixed[1];

        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = Utils.getLookVec(useYaw, usePitch);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);

        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(best.pos) && mop.sideHit == best.face) {
            queuedPos = best.pos;
            queuedFace = best.face;
            queuedVec = computeHitVec(best.pos, best.face);
            placeQueued = true;
        } else {
            placeQueued = false;
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        syncMode();
        if (isTellyMode()) {
            telly.onPreUpdate(e);
            return;
        }
        if (!Utils.nullCheck()) return;

        boolean placed = false;
        if (placeQueued && queuedPos != null && queuedFace != null && queuedVec != null) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() instanceof ItemBlock) {
                if (!keepYActive() || queuedFace != EnumFacing.UP) {
                    mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                            queuedPos, queuedFace, queuedVec);
                    mc.thePlayer.swingItem();
                    placed = true;
                }
            }
        }
        placeQueued = false;

        updateEagle(placed);

        int sprintMode = (int) sprint.getInput();
        if (sprintMode != 0) {
            updateSprintScaffold(placed);
            return;
        }
        if (sprintScafActive) {
            releaseSprintScaffold();
        }
    }

    @SubscribeEvent
    public void onRightClickDelay(RightClickDelayTickEvent e) {
        syncMode();
        if (isTellyMode()) return;
        if (!Utils.nullCheck() || !mc.inGameHasFocus) return;
        if (!Utils.isBindDown(mc.gameSettings.keyBindUseItem)) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;
        try {
            java.lang.reflect.Field f = net.minecraft.client.Minecraft.class.getDeclaredField("field_71467_ac");
            f.setAccessible(true);
            f.setInt(mc, 0);
        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        syncMode();
        if (isTellyMode()) {
            telly.onRenderWorld(e);
            return;
        }
        if (previewPos == null) return;
        int color = 0x4000AAFF;
        RenderUtils.renderBlock(previewPos, color, true, false);
    }

    private void updateEagle(boolean placedThisTick) {
        if (!eagle.isToggled()) {
            if (eagleActive) {
                setShiftOverride(false);
                eagleActive = false;
            }
            return;
        }

        boolean shouldSneak = false;
        if (mc.thePlayer.onGround) {
            if (isOverEdge((int) eagleSafety.getInput()) && !placedThisTick) {
                shouldSneak = true;
            }
        }

        if (!shouldSneak && mc.thePlayer.onGround) {
            BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
            if (mc.theWorld.isAirBlock(below)) {
                shouldSneak = true;
            }
        }

        if (shouldSneak != eagleActive) {
            setShiftOverride(shouldSneak);
            eagleActive = shouldSneak;
        }
    }

    /**
     * Whether the ground runs out within the next {@code lookahead} ticks of current motion.
     *
     * Shared by Eagle and Sprint Scaf Mode so the two agree on where the edge is; a sprint guard
     * that disagreed with the sneak guard would sneak and sprint over the same gap.
     */
    private boolean isOverEdge(int lookahead) {
        for (int i = 0; i <= lookahead; i++) {
            BlockPos checkPos = new BlockPos(
                    mc.thePlayer.posX + mc.thePlayer.motionX * i,
                    mc.thePlayer.posY - 1 + mc.thePlayer.motionY * i,
                    mc.thePlayer.posZ + mc.thePlayer.motionZ * i
            );
            if (mc.theWorld.isAirBlock(checkPos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sprint control tied to the placement state rather than to the tick clock.
     *
     * The Sprint slider's Legit mode pushes the sprint key down on every tick that did not
     * place, and never lifts it; Watchdog toggles it from the yaw difference alone. Neither
     * consults whether there is anywhere to stand, so outrunning the placement drops you.
     *
     * This asks one question instead: is it safe to be moving at sprint speed right now, given
     * what Scaffold is about to do? The answer only changes on real transitions, so the key and
     * the sprint packets change only then too.
     */
    private void updateSprintScaffold(boolean placedThisTick) {
        boolean safe = isSprintSafe(placedThisTick);

        // The key has to be re-asserted every tick. Minecraft rebuilds KeyBinding state from
        // physical input each frame, so a single setKeyBindState is erased before it can do
        // anything -- which is why the Sprint module sets it unconditionally on every onUpdate.
        // Change-gating this call was what made the mode appear to do nothing at all.
        sprintScafActive = true;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), safe);

        // The sprint flag itself is what generates packets, so only that is change-gated.
        if (mc.thePlayer.isSprinting() != safe) {
            mc.thePlayer.setSprinting(safe);
        }
        sprintScafSprinting = safe;
    }

    private boolean isSprintSafe(boolean placedThisTick) {
        EntityPlayerSP player = mc.thePlayer;

        // Scaffold is normally used while walking backwards. Requiring positive forward input
        // silently disabled the entire mode, because backwards input is negative. The dedicated
        // player movement hook below lets a safe Scaffold-owned sprint survive vanilla's forward-
        // only cancellation rule; still require real directional input so idle packets are never
        // emitted.
        if (player.movementInput == null
                || (Math.abs(player.movementInput.moveForward) < 0.01F
                && Math.abs(player.movementInput.moveStrafe) < 0.01F)) return false;
        if (player.isCollidedHorizontally) return false;
        if (player.getFoodStats().getFoodLevel() <= 6) return false;
        // Eagle is deliberately slowing the player at an edge; do not fight it.
        if (eagleActive || player.isSneaking()) return false;

        // Airborne, a miss cannot be corrected before landing, so require ground ahead.
        if (!player.onGround && isOverEdge(1)) return false;

        // The placement pipeline is healthy when a block went down this tick, one is armed for
        // the next, or a target has at least been found. With none of those, sprinting into a
        // gap is what walks the player off the bridge.
        boolean placementReady = placedThisTick || placeQueued || previewPos != null;
        if (!placementReady && isOverEdge(Math.max(1, (int) eagleSafety.getInput()))) {
            return false;
        }

        // Same guard Watchdog mode uses: while the server still believes we are facing far from
        // where we are, it will not honour the sprint anyway.
        float serverYaw = RotationUtils.serverRotations[0];
        float diff = Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw)
                - MathHelper.wrapAngleTo180_float(serverYaw));
        return diff <= 90.0F;
    }

    /** Hand the sprint key back to the player's own input. */
    private void releaseSprintScaffold() {
        if (!sprintScafActive) return;
        boolean wasSprinting = sprintScafSprinting;
        sprintScafActive = false;
        sprintScafSprinting = false;
        if (mc.gameSettings != null) {
            boolean physicalSprint = Keyboard.isKeyDown(mc.gameSettings.keyBindSprint.getKeyCode());
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),
                    physicalSprint);

            // End the Scaffold-owned sprint when nothing else owns sprinting. setSprinting changes
            // the local state; EntityPlayerSP's normal walking update emits the matching C0B stop
            // packet once, while the regular Sprint module or a held sprint key can take over
            // without an unnecessary stop/start pair.
            boolean regularSprintOwnsState = ModuleManager.sprint != null
                    && ModuleManager.sprint.isEnabled();
            if (wasSprinting && !physicalSprint && !regularSprintOwnsState
                    && mc.thePlayer != null && mc.thePlayer.isSprinting()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    /**
     * Used by the local-player movement hook to distinguish a deliberate, placement-safe
     * backwards Scaffold sprint from ordinary backwards movement.
     */
    public boolean isSprintScaffoldSprinting() {
        return this.isEnabled() && !isTellyMode() && (int) sprint.getInput() != 0
                && sprintScafActive && sprintScafSprinting;
    }

    /** True only while Scaffold currently owns or has a valid upcoming placement. */
    public boolean isActivelyScaffolding() {
        return this.isEnabled() && (isTellyMode()
                ? telly.isActivelyScaffolding()
                : placeQueued || queuedPos != null || previewPos != null);
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onTellyPlayerInput(PrePlayerInputEvent event) {
        syncMode();
        if (isTellyMode()) telly.onPrePlayerInput(event);
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onTellyPacketSent(SendPacketEvent event) {
        syncMode();
        if (isTellyMode()) telly.onPacketSent(event);
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onTellyMouse(net.minecraftforge.client.event.MouseEvent event) {
        syncMode();
        if (isTellyMode()) telly.onMouse(event);
    }

    private void setShiftOverride(boolean shift) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),
                shift || Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()));
    }

    /** Whether Keep Y should be governing placement right now. */
    private boolean keepYActive() {
        if (keepY == null || !keepY.isToggled()) {
            return false;
        }
        // Narrowed to while the button is held, so a bridge can still be dropped down
        // deliberately without leaving the mode.
        return keepYOnRightClick == null || !keepYOnRightClick.isToggled()
                || Utils.isBindDown(mc.gameSettings.keyBindUseItem);
    }

    /**
     * Follow the level the bridge is being built at.
     *
     * Re-locked while on the ground and on the tick a jump starts, then left alone in the
     * air. That is what keeps a telly flat: the hop does not move the level it builds at, so
     * blocks still go under where you took off from rather than under where you now are.
     */
    private void updateKeepYLevel() {
        if (!keepYActive()) {
            keepYLevel = Integer.MIN_VALUE;
            keepYJumping = false;
            keepYJumpRolled = false;
            releaseKeepYJump();
            return;
        }
        boolean jumpDown = Utils.isBindDown(mc.gameSettings.keyBindJump);
        if (jumpDown) {
            keepYLevel = (int) Math.floor(mc.thePlayer.posY) - 1;
            keepYJumping = true;
        }
        else if (keepYJumping || mc.thePlayer.onGround || keepYLevel == Integer.MIN_VALUE) {
            keepYLevel = (int) Math.floor(mc.thePlayer.posY) - 1;
            keepYJumping = false;
        }
        if (mc.thePlayer.onGround) {
            keepYJumpRolled = false;
        }
    }

    /**
     * Hop when there is nothing to build against at the locked level.
     *
     * Reaching the far side of a gap means getting the eyes high enough to see the face, and
     * on flat ground that is a jump. Only while actually moving -- jumping on the spot places
     * nothing and just looks odd -- and the chance is rolled once per hop rather than per
     * tick, so a partial setting thins out how many jumps happen instead of stuttering one.
     */
    private void updateKeepYJump(boolean hasTarget) {
        if (!keepYActive() || keepYAutoJump == null || !keepYAutoJump.isToggled()) {
            releaseKeepYJump();
            return;
        }
        boolean moving = mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
        if (hasTarget || !moving || !mc.thePlayer.onGround) {
            releaseKeepYJump();
            return;
        }
        if (!keepYJumpRolled) {
            keepYJumpRolled = true;
            double chance = keepYJumpChance == null ? 100.0 : keepYJumpChance.getInput();
            keepYJumpAllowed = chance >= 100.0 || keepYRandom.nextDouble() * 100.0 < chance;
        }
        if (!keepYJumpAllowed) {
            releaseKeepYJump();
            return;
        }
        int key = mc.gameSettings.keyBindJump.getKeyCode();
        net.minecraft.client.settings.KeyBinding.setKeyBindState(key, true);
        net.minecraft.client.settings.KeyBinding.onTick(key);
        keepYJumpHeld = true;
    }

    private void releaseKeepYJump() {
        if (!keepYJumpHeld) {
            return;
        }
        keepYJumpHeld = false;
        // Only let go of what we pressed: a jump the player is holding themselves stays held.
        int key = mc.gameSettings.keyBindJump.getKeyCode();
        if (!org.lwjgl.input.Keyboard.isKeyDown(key)) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(key, false);
        }
    }

    private BlockData findBestPlacement() {
        EntityPlayerSP player = mc.thePlayer;
        float baseYaw = getBaseYaw();
        BlockPos playerPos = new BlockPos(player);
        // Keep Y searches from the level it locked, not from under your feet, which is the
        // difference between a flat bridge and one that follows you down a drop.
        BlockPos scanY = keepYActive() && keepYLevel != Integer.MIN_VALUE
                ? new BlockPos(playerPos.getX(), keepYLevel, playerPos.getZ())
                : playerPos.down();

        double targetX = player.posX + player.motionX;
        double targetZ = player.posZ + player.motionZ;
        double targetY = scanY.getY() + 0.5;

        double existingScore = Double.MAX_VALUE;
        BlockData best = null;
        double bestScore = Double.MAX_VALUE;

        boolean tower = !player.onGround && !keepYActive();
        int lowestLayer = tower ? -1 : 0;

        for (int layer = 0; layer >= lowestLayer; layer--) {
            BlockPos layerPos = scanY.add(0, layer, 0);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = layerPos.add(x, 0, z);
                    IBlockState state = mc.theWorld.getBlockState(pos);

                    if (state.getBlock() == Blocks.air) continue;
                    if (!state.getBlock().isFullCube()) continue;

                    double exDx = (pos.getX() + 0.5) - targetX;
                    double exDz = (pos.getZ() + 0.5) - targetZ;
                    double exDy = (pos.getY() + 0.5) - targetY;
                    double exScore = exDx * exDx + exDz * exDz + exDy * exDy * 0.25;
                    if (exScore < existingScore) existingScore = exScore;

                    java.util.List<EnumFacing> facings = new java.util.ArrayList<>();
                    facings.add(EnumFacing.NORTH);
                    facings.add(EnumFacing.SOUTH);
                    facings.add(EnumFacing.EAST);
                    facings.add(EnumFacing.WEST);
                    if (tower) facings.add(EnumFacing.UP);

                    for (EnumFacing facing : facings) {
                        if (!PLACEHOLDER.canPlaceBlockOnSide(mc.theWorld, pos, facing, mc.thePlayer, mc.thePlayer.getHeldItem()))
                            continue;

                        BlockPos neighbor = pos.offset(facing);
                        IBlockState neighborState = mc.theWorld.getBlockState(neighbor);
                        if (neighborState.getBlock() != Blocks.air) continue;

                        double nbX = neighbor.getX() + 0.5;
                        double nbY = neighbor.getY() + 0.5;
                        double nbZ = neighbor.getZ() + 0.5;
                        double dx = nbX - targetX;
                        double dz = nbZ - targetZ;
                        double dy = nbY - targetY;
                        double score = dx * dx + dz * dz + dy * dy * 0.25;

                        if (score >= bestScore) continue;

                        float[] rots = getRotationsForFace(pos, facing, baseYaw);
                        if (rots == null) rots = getFreeRotationsForFace(pos, facing);

                        Vec3 eye = player.getPositionEyes(1f);
                        Vec3 look = Utils.getLookVec(rots[0], rots[1]);
                        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
                        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);

                        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) continue;
                        if (!hit.getBlockPos().equals(pos)) continue;
                        if (hit.sideHit != facing) continue;

                        bestScore = score;
                        best = new BlockData(pos, facing);
                    }
                }
            }
        }

        if (best != null && existingScore <= bestScore) return null;
        return best;
    }

    private float getBaseYaw() {
        if (((int) sprint.getInput()) != 0) return mc.thePlayer.rotationYaw;

        int forward = 0, strafe = 0;
        if (mc.gameSettings.keyBindForward.isKeyDown()) forward++;
        if (mc.gameSettings.keyBindBack.isKeyDown()) forward--;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) strafe++;
        if (mc.gameSettings.keyBindRight.isKeyDown()) strafe--;

        float offset;
        if (forward > 0) {
            offset = strafe == 0 ? 180f : (strafe > 0 ? 135f : -135f);
        } else if (forward < 0) {
            offset = strafe == 0 ? 0f : (strafe > 0 ? 45f : -45f);
        } else {
            offset = strafe == 0 ? 180f : (strafe > 0 ? 90f : -90f);
        }
        return mc.thePlayer.rotationYaw + offset;
    }

    private float[] getRotationsForFace(BlockPos pos, EnumFacing facing, float lockedYaw) {
        EntityPlayerSP player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        float yawRad = (float) Math.toRadians(lockedYaw);
        double hx = -Math.sin(yawRad);
        double hz = Math.cos(yawRad);

        double bx0 = pos.getX(), bx1 = bx0 + 1.0;
        double by0 = pos.getY(), by1 = by0 + 1.0;
        double bz0 = pos.getZ(), bz1 = bz0 + 1.0;

        float currentPitch = RotationUtils.serverRotations[1];
        float bestPitch = Float.MAX_VALUE;
        float bestDiff = Float.MAX_VALUE;

        switch (facing) {
            case UP: {
                float p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, bx0 + 0.5, by1, bz0 + 0.5);
                if (!Float.isNaN(p)) {
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                    if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                }
                for (double cx : new double[]{bx0 + 0.1, bx1 - 0.1}) {
                    for (double cz : new double[]{bz0 + 0.1, bz1 - 0.1}) {
                        p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, cx, by1, cz);
                        if (!Float.isNaN(p)) {
                            float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                            if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                        }
                    }
                }
                break;
            }
            case DOWN: {
                float p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, bx0 + 0.5, by0, bz0 + 0.5);
                if (!Float.isNaN(p)) {
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                    if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                }
                break;
            }
            case NORTH: {
                float[] cands = pitchesToHitZPlane(eyeX, eyeY, eyeZ, hx, hz, bz0, bx0, bx1, by0, by1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case SOUTH: {
                float[] cands = pitchesToHitZPlane(eyeX, eyeY, eyeZ, hx, hz, bz1, bx0, bx1, by0, by1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case WEST: {
                float[] cands = pitchesToHitXPlane(eyeX, eyeY, eyeZ, hx, hz, bx0, by0, by1, bz0, bz1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case EAST: {
                float[] cands = pitchesToHitXPlane(eyeX, eyeY, eyeZ, hx, hz, bx1, by0, by1, bz0, bz1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            default: return null;
        }

        if (bestPitch == Float.MAX_VALUE) return null;
        bestPitch = MathHelper.clamp_float(bestPitch, -90f, 90f);

        float[] last = {lastRotsValid ? lastYaw : lockedYaw,
                lastRotsValid ? lastPitch : currentPitch};
        float[] target = {lockedYaw, bestPitch};
        float[] fixed = RotationUtils.fixRotation(target[0], target[1], last[0], last[1]);
        fixed[0] = lockedYaw;
        return fixed;
    }

    private float[] getFreeRotationsForFace(BlockPos pos, EnumFacing facing) {
        EntityPlayerSP player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;

        double faceCX = pos.getX() + 0.5 + facing.getFrontOffsetX() * 0.5;
        double faceCY = pos.getY() + 0.5 + facing.getFrontOffsetY() * 0.5;
        double faceCZ = pos.getZ() + 0.5 + facing.getFrontOffsetZ() * 0.5;

        double dx = faceCX - eyeX;
        double dy = faceCY - eyeY;
        double dz = faceCZ - eyeZ;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        pitch = MathHelper.clamp_float(pitch, -90f, 90f);

        return RotationUtils.fixRotation(yaw, pitch,
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
    }

    private float[] applySpeedCap(float curYaw, float curPitch, float targetYaw, float targetPitch) {
        float maxStep = !Float.isNaN(wdOverrideSpeed) && (int) sprint.getInput() == 2
                ? wdOverrideSpeed : (float) rotationSpeed.getInput();
        if (maxStep <= 1) return new float[]{targetYaw, targetPitch};
        if (maxStep >= 360) return new float[]{targetYaw, targetPitch};

        float dy = MathHelper.wrapAngleTo180_float(targetYaw - curYaw);
        float dp = targetPitch - curPitch;
        float dist = (float) Math.sqrt(dy * dy + dp * dp);
        if (dist <= maxStep) return new float[]{targetYaw, targetPitch};

        float scale = maxStep / dist;
        return new float[]{curYaw + dy * scale, curPitch + dp * scale};
    }

    private static float pitchToHitPoint(double eyeX, double eyeY, double eyeZ,
                                         double hx, double hz,
                                         double tx, double ty, double tz) {
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double tCosp;
        if (Math.abs(hx) > Math.abs(hz)) {
            if (Math.abs(hx) < 1e-6) return Float.NaN;
            tCosp = dx / hx;
        } else {
            if (Math.abs(hz) < 1e-6) return Float.NaN;
            tCosp = dz / hz;
        }
        if (tCosp <= 0) return Float.NaN;
        double tanPitch = -dy / tCosp;
        return (float) Math.toDegrees(Math.atan(tanPitch));
    }

    private static float[] pitchesToHitZPlane(double eyeX, double eyeY, double eyeZ,
                                              double hx, double hz, double faceZ,
                                              double xMin, double xMax,
                                              double yMin, double yMax) {
        if (Math.abs(hz) < 1e-6) return new float[0];
        double tCosp = (faceZ - eyeZ) / hz;
        if (tCosp <= 0) return new float[0];

        double[] sampleY = {yMin + 0.1, yMin + (yMax - yMin) * 0.3, (yMin + yMax) * 0.5,
                yMin + (yMax - yMin) * 0.7, yMax - 0.1};
        float[] results = new float[sampleY.length];
        int count = 0;
        for (double sy : sampleY) {
            double dy = sy - eyeY;
            double hitX = eyeX + hx * tCosp;
            if (hitX < xMin || hitX > xMax) continue;
            double tanPitch = -dy / tCosp;
            results[count++] = (float) Math.toDegrees(Math.atan(tanPitch));
        }
        float[] trimmed = new float[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return trimmed;
    }

    private static float[] pitchesToHitXPlane(double eyeX, double eyeY, double eyeZ,
                                              double hx, double hz, double faceX,
                                              double yMin, double yMax,
                                              double zMin, double zMax) {
        if (Math.abs(hx) < 1e-6) return new float[0];
        double tCosp = (faceX - eyeX) / hx;
        if (tCosp <= 0) return new float[0];

        double[] sampleY = {yMin + 0.1, yMin + (yMax - yMin) * 0.3, (yMin + yMax) * 0.5,
                yMin + (yMax - yMin) * 0.7, yMax - 0.1};
        float[] results = new float[sampleY.length];
        int count = 0;
        for (double sy : sampleY) {
            double dy = sy - eyeY;
            double hitZ = eyeZ + hz * tCosp;
            if (hitZ < zMin || hitZ > zMax) continue;
            double tanPitch = -dy / tCosp;
            results[count++] = (float) Math.toDegrees(Math.atan(tanPitch));
        }
        float[] trimmed = new float[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return trimmed;
    }

    private Item getBlockItem() {
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || held.stackSize <= 1) {
            int slot = getBestBlockSlot();
            if (slot != -1) mc.thePlayer.inventory.currentItem = slot;
        }
        held = mc.thePlayer.inventory.getCurrentItem();
        return held != null ? held.getItem() : null;
    }

    private int getBestBlockSlot() {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 1) {
                if (stack.stackSize > bestCount) {
                    bestCount = stack.stackSize;
                    best = i;
                }
            }
        }
        if (best == -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
                if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                    best = i;
                    break;
                }
            }
        }
        return best;
    }

    private static Vec3 computeHitVec(BlockPos pos, EnumFacing face) {
        return new Vec3(
                pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5
        );
    }

    private static class BlockData {
        final BlockPos pos;
        final EnumFacing face;
        BlockData(BlockPos pos, EnumFacing face) { this.pos = pos; this.face = face; }
    }

}
