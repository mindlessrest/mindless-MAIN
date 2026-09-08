package mindless.module.impl.player;

import java.awt.Color;
import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.RightClickDelayTickEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
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
    /**
     * Where to look while bridging.
     *
     * All four are expressed against the movement yaw -- the direction you are actually
     * travelling -- rather than where the camera points, because that is what stays stable while
     * strafing. Back looks straight back along it, Diagonal picks whichever of the two rear
     * quarters faces the block being bridged from, Normal tries those three in order and takes
     * the first that can actually reach, and Offset aims at a point pushed into the face instead
     * of at its centre.
     */
    private static final String[] ROTATION_MODES = new String[]{"Back", "Normal", "Offset", "Diagonal"};
    /** How long the outline takes to fall away once the target moves on. */
    private static final long TARGET_FADE_MS = 220L;
    private static final int ROT_BACK = 0;
    private static final int ROT_NORMAL = 1;
    private static final int ROT_OFFSET = 2;
    private static final int ROT_DIAGONAL = 3;

    private static final ItemBlock PLACEHOLDER = new ItemBlock(Blocks.tnt);
    private final SliderSetting rotationSpeed;
    private final SliderSetting sprint;
    private final ButtonSetting keepY;
    private final ButtonSetting eagle;
    private final SliderSetting eagleSafety;
    private final ButtonSetting switchBack;

    private BlockPos previewPos;
    private EnumFacing previewFace;
    private BlockPos queuedPos;
    private EnumFacing queuedFace;
    private Vec3 queuedVec;
    private boolean placeQueued;
    /** The block the last placement went against, reused while it stays usable. */
    private BlockPos lastPlacedAgainst;
    /** Ticks spent off the ground, and how many blocks the current jump still owes. */
    private int airTicks;
    private int blocksSinceJump;
    private int jumpBlockTarget;
    /** When the last target stopped being the target, for the outline to fade from. */
    private BlockPos fadingPos;
    private long fadingSince;

    private float lastYaw, lastPitch;
    private boolean lastRotsValid;

    private ButtonSetting downPlace;
    private SliderSetting rotationMode;
    private ButtonSetting aimCheck;
    private ButtonSetting strictAimCheck;
    private SliderSetting offsetAmount;
    private ButtonSetting swing;
    private SliderSetting straightAirDelay;
    private SliderSetting diagonalAirDelay;
    private SliderSetting straightJumpBlocks;
    private SliderSetting diagonalJumpBlocks;
    private ButtonSetting showTarget;
    private ButtonSetting targetFadeOut;
    private ButtonSetting targetShade;
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
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 180, 1, 360, 1));
        this.registerSetting(sprint = new SliderSetting("Sprint", 0, new String[]{"Off", "Legit", "Watchdog"}));
        this.registerSetting(keepY = new ButtonSetting("Keep Y", false));
        this.registerSetting(eagle = new ButtonSetting("Eagle", false));
        this.registerSetting(eagleSafety = new SliderSetting("Eagle safety", " tick", 1, 1, 3, 0.1));
        this.registerSetting(switchBack = new ButtonSetting("Switch back", true));
        this.registerSetting(downPlace = new ButtonSetting("Down place", true));
        this.registerSetting(rotationMode = new SliderSetting("Rotation", ROT_DIAGONAL, ROTATION_MODES));
        this.registerSetting(offsetAmount = new SliderSetting("Offset", 0.15, 0.0, 1.0, 0.01));
        this.registerSetting(aimCheck = new ButtonSetting("Aim check", true));
        this.registerSetting(strictAimCheck = new ButtonSetting("Strict aim check", true));
        this.registerSetting(swing = new ButtonSetting("Swing", true));
        this.registerSetting(straightAirDelay = new SliderSetting("Straight air delay", " tick", 1, 0, 4, 1));
        this.registerSetting(diagonalAirDelay = new SliderSetting("Diagonal air delay", " tick", 1, 0, 4, 1));
        this.registerSetting(straightJumpBlocks = new SliderSetting("Straight jump blocks", 0, 0, 3, 1));
        this.registerSetting(diagonalJumpBlocks = new SliderSetting("Diagonal jump blocks", 0, 0, 3, 1));
        this.registerSetting(showTarget = new ButtonSetting("Show target", true));
        this.registerSetting(targetFadeOut = new ButtonSetting("Target fade out", true));
        this.registerSetting(targetShade = new ButtonSetting("Target shade", false));
    }

    @Override
    public void onEnable() {
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
        restorePreviousSlot();
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

        BlockData best = findBestPlacement();
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

        // The chosen mode decides where to look; the speed cap then walks the head there so it
        // does not snap. Falling still forces the aim down at the face, since a bridge that
        // misses while you are already off the edge is the one that actually costs you.
        float[] target = placementRotations(best.pos, best.face);
        float[] rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                target[0], willFall ? target[1] : Math.max(target[1], 82f));

        float[] fixed = RotationUtils.fixRotation(rots[0], rots[1],
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

        e.setYaw(fixed[0]);
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
        if (!Utils.nullCheck()) return;

        // Jump bridging. While off the ground a placement waits out the air delay, unless the
        // jump still owes blocks, in which case it goes immediately -- that pair is what lets a
        // jump place its blocks up front and then hold off, instead of spraying every tick.
        boolean diagonal = isMovingDiagonally();
        if (mc.thePlayer.onGround) {
            airTicks = 0;
            blocksSinceJump = 0;
            jumpBlockTarget = (int) (diagonal ? diagonalJumpBlocks.getInput() : straightJumpBlocks.getInput());
        }
        else {
            airTicks++;
        }
        int airDelay = (int) (diagonal ? diagonalAirDelay.getInput() : straightAirDelay.getInput());
        boolean airAllows = mc.thePlayer.onGround
                || airTicks >= airDelay
                || blocksSinceJump < jumpBlockTarget;

        boolean placed = false;
        if (airAllows && placeQueued && queuedPos != null && queuedFace != null && queuedVec != null) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() instanceof ItemBlock) {
                if (!keepY.isToggled() || queuedFace != EnumFacing.UP) {
                    mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                            queuedPos, queuedFace, queuedVec);
                    if (swing.isToggled()) {
                        mc.thePlayer.swingItem();
                    }
                    lastPlacedAgainst = queuedPos;
                    if (!mc.thePlayer.onGround) {
                        blocksSinceJump++;
                    }
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
        if (!showTarget.isToggled()) {
            fadingPos = null;
            return;
        }

        // Remember the block the moment it stops being the target, so the outline can fall away
        // from where it was rather than blinking off as the search moves on.
        if (previewPos != null) {
            if (!previewPos.equals(fadingPos)) {
                fadingPos = previewPos;
            }
            fadingSince = System.currentTimeMillis();
        }

        BlockPos drawAt = previewPos != null ? previewPos : fadingPos;
        if (drawAt == null) {
            return;
        }

        float strength = 1.0f;
        if (previewPos == null) {
            if (!targetFadeOut.isToggled()) {
                fadingPos = null;
                return;
            }
            long elapsed = System.currentTimeMillis() - fadingSince;
            strength = 1.0f - elapsed / (float) TARGET_FADE_MS;
            if (strength <= 0.0f) {
                fadingPos = null;
                return;
            }
        }

        int alpha = Math.max(0, Math.min(255, Math.round(0x40 * strength)));
        int color = (alpha << 24) | 0x00AAFF;
        RenderUtils.renderBlock(drawAt, color, true, targetShade.isToggled());
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
        return this.isEnabled() && (int) sprint.getInput() != 0
                && sprintScafActive && sprintScafSprinting;
    }

    private void setShiftOverride(boolean shift) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),
                shift || Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()));
    }

    /**
     * Find the block to place against.
     *
     * Three things this does that the previous search did not.
     *
     * It keeps the last block. A search that re-picks from scratch every rotation event will
     * swap between two equally good candidates on consecutive ticks, and the rotation chases
     * the swap; reusing the previous target while it is still adjacent and still placeable is
     * what stops that.
     *
     * It scans four layers down rather than one, so stepping out over a gap still finds
     * something to build from instead of failing until the ground comes back.
     *
     * And it only raytraces the handful of candidates worth checking. The old search raytraced
     * inside the candidate loop -- nine by nine positions across two layers, five faces each,
     * up to eight hundred raytraces per rotation event. Candidates are cheap to reject on
     * geometry alone, so they are filtered and sorted first and only the closest few are traced.
     */
    private BlockData findBestPlacement() {
        EntityPlayerSP player = mc.thePlayer;
        float baseYaw = getBaseYaw();
        BlockPos below = new BlockPos(player.posX, player.posY - 1.0, player.posZ);

        // Already standing on something: nothing to do.
        if (!isReplaceable(below)) {
            return null;
        }

        boolean tower = downPlace.isToggled() && !keepY.isToggled();

        java.util.List<BlockPos> candidates = new java.util.ArrayList<BlockPos>();
        collectCandidates(candidates, below, -4, 0, false);

        boolean upward = false;
        if (candidates.isEmpty()) {
            if (!tower) {
                return null;
            }
            // Nothing underneath. Look upward for something to build off, refusing any face
            // whose block would be placed inside the player.
            collectCandidates(candidates, below, 1, 6, true);
            if (candidates.isEmpty()) {
                return null;
            }
            upward = true;
        }

        // Keep the previous block while it is still next to us and still has a free face.
        if (!upward && lastPlacedAgainst != null) {
            double distance = below.distanceSq(lastPlacedAgainst.getX() + 0.5,
                    lastPlacedAgainst.getY() + 0.5, lastPlacedAgainst.getZ() + 0.5);
            if (distance < 2.0) {
                EnumFacing face = chooseFace(lastPlacedAgainst, below, false);
                if (face != null && canAim(lastPlacedAgainst, face, baseYaw)) {
                    return new BlockData(lastPlacedAgainst, face);
                }
            }
        }

        final double centerX = below.getX() + 0.5;
        final double centerY = below.getY() + 0.5;
        final double centerZ = below.getZ() + 0.5;
        java.util.Collections.sort(candidates, new java.util.Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                return Double.compare(a.distanceSq(centerX, centerY, centerZ),
                        b.distanceSq(centerX, centerY, centerZ));
            }
        });

        // Only the nearest few are worth a raytrace; past that the angle is hopeless anyway.
        int examined = Math.min(candidates.size(), 6);
        for (int i = 0; i < examined; i++) {
            BlockPos pos = candidates.get(i);
            EnumFacing face = chooseFace(pos, below, upward);
            if (face == null) {
                continue;
            }
            if (canAim(pos, face, baseYaw)) {
                return new BlockData(pos, face);
            }
        }
        return null;
    }

    /**
     * Solid blocks in the box that have at least one face open to air.
     *
     * @param skipIntersecting refuse a face whose air side overlaps the player, which is what
     *                         keeps the upward pass from trying to place a block inside us.
     */
    private void collectCandidates(java.util.List<BlockPos> into, BlockPos origin,
                                   int fromY, int toY, boolean skipIntersecting) {
        for (int x = -4; x <= 4; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (isReplaceable(pos)) {
                        continue;
                    }
                    IBlockState state = mc.theWorld.getBlockState(pos);
                    if (!state.getBlock().isFullCube()) {
                        continue;
                    }
                    for (EnumFacing facing : EnumFacing.VALUES) {
                        BlockPos neighbour = pos.offset(facing);
                        if (!isReplaceable(neighbour)) {
                            continue;
                        }
                        if (skipIntersecting && intersectsPlayer(neighbour)) {
                            continue;
                        }
                        into.add(pos);
                        break;
                    }
                }
            }
        }
    }

    /** The open face of a block that sits closest to where we want to stand. */
    private EnumFacing chooseFace(BlockPos pos, BlockPos target, boolean upward) {
        EnumFacing best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (!upward && facing == EnumFacing.DOWN) {
                continue;
            }
            BlockPos neighbour = pos.offset(facing);
            if (!isReplaceable(neighbour)) {
                continue;
            }
            if (upward && intersectsPlayer(neighbour)) {
                continue;
            }
            if (!PLACEHOLDER.canPlaceBlockOnSide(mc.theWorld, pos, facing,
                    mc.thePlayer, mc.thePlayer.getHeldItem())) {
                continue;
            }
            double distance = neighbour.distanceSq(target.getX() + 0.5,
                    target.getY() + 0.5, target.getZ() + 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = facing;
            }
        }
        return best;
    }

    /** Whether the face can actually be hit from where we are looking. */
    private boolean canAim(BlockPos pos, EnumFacing face, float baseYaw) {
        float[] rots = getRotationsForFace(pos, face, baseYaw);
        if (rots == null) {
            rots = getFreeRotationsForFace(pos, face);
        }
        if (rots == null) {
            return false;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = Utils.getLookVec(rots[0], rots[1]);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(pos)
                && hit.sideHit == face;
    }

    private boolean isReplaceable(BlockPos pos) {
        net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.air || block.getMaterial().isReplaceable();
    }

    private boolean intersectsPlayer(BlockPos pos) {
        return new net.minecraft.util.AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0)
                .intersectsWith(mc.thePlayer.getEntityBoundingBox());
    }

    /**
     * The direction of travel.
     *
     * getBaseYaw is that direction turned around -- the yaw you look back along while bridging --
     * so the movement yaw is simply the opposite of it, and every rotation mode is written
     * against this rather than against the camera.
     */
    /** Both an axis and a strafe held, which is what makes a bridge diagonal. */
    private boolean isMovingDiagonally() {
        boolean axis = mc.gameSettings.keyBindForward.isKeyDown() || mc.gameSettings.keyBindBack.isKeyDown();
        boolean strafe = mc.gameSettings.keyBindLeft.isKeyDown() || mc.gameSettings.keyBindRight.isKeyDown();
        return axis && strafe;
    }

    private float movementYaw() {
        return getBaseYaw() - 180.0f;
    }

    /** Whether looking this way actually reaches the face we mean to place against. */
    private boolean aimHits(BlockPos pos, EnumFacing face, float yaw, float pitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = Utils.getLookVec(yaw, pitch);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        if (!hit.getBlockPos().equals(pos)) {
            return false;
        }
        // Without strict checking any face of the right block will do, which keeps the looser
        // rotations usable on blocks the exact face cannot be reached on.
        return !strictAimCheck.isToggled() || hit.sideHit == face;
    }

    /** Yaw and pitch to a point pushed into the face rather than to its centre. */
    private float[] offsetRotations(BlockPos pos, EnumFacing face, double offset) {
        double x = pos.getX() + 0.5 + face.getFrontOffsetX() * (0.5 - offset);
        double y = pos.getY() + 0.5 + face.getFrontOffsetY() * (0.5 - offset);
        double z = pos.getZ() + 0.5 + face.getFrontOffsetZ() * (0.5 - offset);

        double dx = x - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = z - mc.thePlayer.posZ;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90f, 90f)};
    }

    /**
     * The rotation for a placement, by mode.
     *
     * Each mode falls back to looking straight at the face when its preferred angle cannot reach,
     * so a mode never costs a placement -- it only changes where the head points when there is a
     * choice.
     */
    private float[] placementRotations(BlockPos pos, EnumFacing face) {
        float[] direct = getFreeRotationsForFace(pos, face);
        if (!aimCheck.isToggled()) {
            return direct;
        }

        float moveYaw = movementYaw();
        float pitch = direct[1];
        float back = moveYaw - 180.0f;
        float left = moveYaw - 135.0f;
        float right = moveYaw + 135.0f;
        float yaw;

        switch ((int) rotationMode.getInput()) {
            case ROT_NORMAL: {
                yaw = direct[0];
                if (aimHits(pos, face, back, pitch)) {
                    yaw = back;
                }
                else if (aimHits(pos, face, left, pitch)) {
                    yaw = left;
                }
                else if (aimHits(pos, face, right, pitch)) {
                    yaw = right;
                }
                break;
            }
            case ROT_OFFSET: {
                float[] offset = offsetRotations(pos, face, offsetAmount.getInput());
                yaw = offset[0];
                pitch = offset[1];
                if (strictAimCheck.isToggled() && !aimHits(pos, face, yaw, pitch)) {
                    yaw = direct[0];
                    pitch = direct[1];
                }
                break;
            }
            case ROT_DIAGONAL: {
                boolean diagonal = aimHits(pos, face, left, pitch) || aimHits(pos, face, right, pitch);
                boolean straight = aimHits(pos, face, back, pitch);
                if (!diagonal && !straight) {
                    yaw = direct[0];
                }
                else if (!diagonal) {
                    yaw = back;
                }
                else {
                    // Take the rear quarter that faces the block being bridged from, so the head
                    // turns into the bridge rather than away from it.
                    BlockPos below = new BlockPos(Math.floor(mc.thePlayer.posX),
                            Math.floor(mc.thePlayer.posY) - 1.0, Math.floor(mc.thePlayer.posZ));
                    double dx = below.getX() + 0.5 - mc.thePlayer.posX;
                    double dz = below.getZ() + 0.5 - mc.thePlayer.posZ;
                    float toBelow = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    float delta = MathHelper.wrapAngleTo180_float(toBelow - moveYaw);
                    float picked = delta > 0.0f ? right : left;
                    if (strictAimCheck.isToggled() && !aimHits(pos, face, picked, pitch)) {
                        picked = delta > 0.0f ? left : right;
                    }
                    yaw = picked;
                }
                break;
            }
            case ROT_BACK:
            default: {
                boolean diagonal = aimHits(pos, face, left, pitch) || aimHits(pos, face, right, pitch);
                boolean straight = aimHits(pos, face, back, pitch);
                yaw = (!straight && (strictAimCheck.isToggled() || !diagonal)) ? direct[0] : back;
                break;
            }
        }
        return new float[]{yaw, pitch};
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
