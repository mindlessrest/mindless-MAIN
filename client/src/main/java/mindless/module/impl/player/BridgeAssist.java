package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.SendPacketEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.script.model.SimulatedPlayer;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

public class BridgeAssist extends Module {
    /** DOWN is never useful: the block would land above the support, on top of the player. */
    private static final EnumFacing[] FACES = {
            EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private static final double GRID_INSET = 0.05;
    private static final double GRID_STEP = 0.25;
    private static final int GRID_N = (int) Math.round(1.0 / GRID_STEP);
    /** Cap on how many (support, face) pairs get the full ray-cast sweep in one acquisition. */
    /**
     * Ticks the last aim is held after the target disappears.
     *
     * Bridging spends most of its ticks with nothing to place -- the square underfoot is still
     * solid until the player steps off it. Dropping the aim on those ticks made the rotation
     * snap back and forth and never settle, so it is held briefly instead, which is what keeps
     * the view pointed down the way a scaffold does.
     */
    private static final int AIM_HOLD_TICKS = 12;

    private final SliderSetting edgeOffset;
    private final SliderSetting unsneakDelayMin;
    private final SliderSetting unsneakDelayMax;
    private final SliderSetting sneakOnJump;
    private final ButtonSetting sneakKeyPressed;
    private final ButtonSetting holdingBlocks;
    private final ButtonSetting lookingDown;
    private final ButtonSetting notMovingForward;

    private final ButtonSetting prePlace;
    private final ButtonSetting silentRotation;
    private final ButtonSetting debug;
    private final SliderSetting rotationSpeed;

    private boolean sneakingFromModule;
    private boolean placed;
    private boolean forceRelease;
    private int sneakJumpDelayTicks = -1;
    private int sneakJumpStartTick = -1;
    private int unsneakDelayTicks = -1;
    private int unsneakStartTick = -1;
    private boolean placeQueued;
    private BlockPos placeAtBlock;
    private EnumFacing placeSide;
    private Vec3 placeHitVec;
    private int previousSlot = -1;

    // Retained aim. Held across ticks so the smoothed rotation has a stable goal to converge on.
    private boolean hasAim;
    private float aimYaw, aimPitch;
    private BlockPos targetSupport;
    private EnumFacing targetSide;
    private BlockPos targetCell;
    /** Y of the bridge deck, latched while airborne so a fall does not drag the target down. */
    private int bridgeY = Integer.MIN_VALUE;
    private int aimHold;

    /** Last reported trace stage, so an unchanged state does not spam chat every tick. */
    private String lastStage = "";
    private long lastStageAt;
    private java.io.BufferedWriter logWriter;

    public BridgeAssist() {
        super("Bridge Assist", category.player);

        this.registerSetting(prePlace = new ButtonSetting("Pre place", false));
        this.registerSetting(silentRotation = new ButtonSetting("Silent rotation", false));
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 27, 1, 30, 1));
        this.registerSetting(debug = new ButtonSetting("Debug", false));

        GroupSetting sneakingGroup = new GroupSetting("Sneaking");
        this.registerSetting(sneakingGroup);
        this.registerSetting(edgeOffset = new SliderSetting(sneakingGroup, "Edge offset", " blocks", 0, 0, 0.3, 0.01));
        this.registerSetting(unsneakDelayMin = new SliderSetting(sneakingGroup, "Sneak delay min", "ms", 50, 50, 300, 5));
        this.registerSetting(unsneakDelayMax = new SliderSetting(sneakingGroup, "Sneak delay max", "ms", 100, 50, 300, 5));
        this.registerSetting(sneakOnJump = new SliderSetting(sneakingGroup, "Sneak on jump", "ms", 0, 0, 500, 5));

        GroupSetting conditionsGroup = new GroupSetting("Conditions");
        this.registerSetting(conditionsGroup);
        this.registerSetting(sneakKeyPressed = new ButtonSetting(conditionsGroup, "Sneak key pressed", false));
        this.registerSetting(holdingBlocks = new ButtonSetting(conditionsGroup, "Holding blocks", false));
        this.registerSetting(lookingDown = new ButtonSetting(conditionsGroup, "Looking down", false));
        this.registerSetting(notMovingForward = new ButtonSetting(conditionsGroup, "Not moving forward", false));

        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        double offset = edgeOffset.getInput();
        return offset == Math.rint(offset) ? Integer.toString((int) offset) : Double.toString(Utils.round(offset, 2));
    }

    @Override
    public void onDisable() {
        sneakingFromModule = false;
        resetUnsneak();
        placeQueued = false;
        clearAim();
        bridgeY = Integer.MIN_VALUE;
        aimHold = 0;
        lastStage = "";
        closeLog();
        restoreSlot();
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;

        boolean manualSneak = isManualSneak();
        boolean requireSneak = sneakKeyPressed.isToggled();

        if (manualSneak && !requireSneak) {
            resetUnsneak();
            return;
        }

        if (requireSneak && (!manualSneak || (e.getForward() == 0 && e.getStrafe() == 0))) {
            if (!manualSneak) resetUnsneak();
            repressSneak(e);
            return;
        }

        if (notMovingForward.isToggled() && e.getForward() > 0) {
            clearSneak(e);
            return;
        }
        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70) {
            clearSneak(e);
            return;
        }
        if (holdingBlocks.isToggled()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !(held.getItem() instanceof ItemBlock)) {
                clearSneak(e);
                return;
            }
        }

        if (e.isJump() && mc.thePlayer.onGround && (e.getForward() != 0 || e.getStrafe() != 0) && sneakOnJump.getInput() > 0) {
            if (!requireSneak || forceRelease) {
                sneakJumpStartTick = mc.thePlayer.ticksExisted;
                double raw = sneakOnJump.getInput() / 50.0;
                int base = (int) raw;
                sneakJumpDelayTicks = base + (Math.random() < (raw - base) ? 1 : 0);
                pressSneak(e, true);
                return;
            }
        }

        SimulatedPlayer sim = SimulatedPlayer.fromClientPlayer(mc.thePlayer.movementInput);
        sim.movementInput.sneak = false;
        sim.tick();

        double offset = computeEdgeOffset(sim.getEntityBoundingBox());

        if (Double.isNaN(offset)) {
            if (e.isJump() && (sneakOnJump.getInput() <= 0 || (e.getForward() == 0 && e.getStrafe() == 0))) {
                if (sneakingFromModule) tryReleaseSneak(e, true);
            } else if (mc.thePlayer.onGround) {
                pressSneak(e, true);
            } else if (sneakingFromModule) {
                tryReleaseSneak(e, true);
            }
            return;
        }

        if (offset > edgeOffset.getInput()) {
            pressSneak(e, true);
        } else if (sneakingFromModule) {
            tryReleaseSneak(e, true);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement c08 = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (c08.getPlacedBlockDirection() != 255 && sneakingFromModule && sneakKeyPressed.isToggled()) {
                placed = true;
            }
        }
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        // Hard gate: with Silent rotation off this handler does nothing at all -- no target
        // search, no slot change, and above all no setYaw/setPitch. Bridge Assist's own sneak
        // behaviour is untouched by any of the code below.
        if (!silentRotation.isToggled()) {
            placeQueued = false;
            clearAim();
            bridgeY = Integer.MIN_VALUE;
            restoreSlot();
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            stage("held by BedAura");
            return;
        }

        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70f) {
            stage("blocked by 'Looking down' condition (pitch " + Math.round(mc.thePlayer.rotationPitch) + ")");
            return;
        }
        if (notMovingForward.isToggled() && mc.thePlayer.movementInput.moveForward > 0f) {
            stage("blocked by 'Not moving forward' condition");
            return;
        }

        // Resolve the stack first without committing to it, so the slot only changes once a
        // target is actually reachable with it.
        int slot = blockSlot();
        if (slot == -1) {
            stage("no placeable block in the hotbar");
            clearAim();
            restoreSlot();
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getStackInSlot(slot);

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        double reach = mc.playerController.getBlockReachDistance();

        // Exactly one cell is ever in play: the deck square the player is walking onto. Scanning
        // a radius and picking by distance filled the neighbours too, which is what produced the
        // two- and three-wide strips.
        BlockPos wanted = wantedCell();
        if (wanted != null) {
            aimHold = AIM_HOLD_TICKS;
            // Only the target is dropped when the wanted cell moves on, never the aim itself.
            if (targetCell != null && !targetCell.equals(wanted)) invalidateTarget();

            if (aimStillValid(held, reach) || acquireAim(wanted, held, reach, baseYaw, basePitch)) {
                selectBlockSlot(slot);
                float[] sm = RotationUtils.smoothRotation(baseYaw, basePitch, aimYaw, aimPitch, speed(), 20f);

                MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, sm[0], sm[1]);
                if (mop != null && mop.getBlockPos().equals(targetSupport) && mop.sideHit == targetSide) {
                    placeAtBlock = mop.getBlockPos();
                    placeSide = mop.sideHit;
                    placeHitVec = mop.hitVec;
                    placeQueued = true;
                    stage("aligned on " + targetSupport.getX() + "," + targetSupport.getY() + ","
                            + targetSupport.getZ() + " " + targetSide + " -> queued");
                } else {
                    stage("rotating toward target (yaw " + Math.round(sm[0]) + ", pitch " + Math.round(sm[1])
                            + "; want " + Math.round(aimYaw) + "/" + Math.round(aimPitch) + ")");
                }

                e.setYaw(sm[0]);
                e.setPitch(sm[1]);
                return;
            }

            stage("target " + wanted.getX() + "," + wanted.getY() + "," + wanted.getZ()
                    + " but no rotation reaches it (all support faces occluded or out of reach)");
            // Fall through to the hold below rather than releasing: a single tick where no
            // rotation resolves is not a reason to hand the view back.
        }

        // Nothing to place this tick. Bridging spends most of its ticks here -- the square
        // underfoot is solid again the instant a block lands -- so the aim is held rather than
        // released. Releasing it was what made the view snap back to the real rotation between
        // every single placement and then get yanked down again on the next.
        if (hasAim && aimHold > 0) {
            aimHold--;
            stage("holding aim (" + aimHold + " ticks left)");
            float[] hold = RotationUtils.smoothRotation(baseYaw, basePitch, aimYaw, aimPitch, speed(), 20f);
            e.setYaw(hold[0]);
            e.setPitch(hold[1]);
            return;
        }

        double feet = mc.thePlayer.getEntityBoundingBox().minY;
        stage((bridgeY >= feet ? "released: fallen past the deck" : "released: idle on the deck")
                + " (deckY " + bridgeY + ", feetY " + String.format("%.2f", feet) + ")");
        clearAim();
        restoreSlot();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!placeQueued) return;
        placeQueued = false;
        if (!Utils.nullCheck() || mc.currentScreen != null || !silentRotation.isToggled()
                || placeAtBlock == null || placeSide == null || placeHitVec == null) {
            return;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            stage("place skipped: held item is not a block");
            return;
        }
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, placeAtBlock, placeSide, placeHitVec)) {
            mc.thePlayer.swingItem();
            // Retire the target so the next one is picked up, but hold the aim: the view should
            // stay looking down through the whole bridge, not reset after each block.
            invalidateTarget();
            aimHold = AIM_HOLD_TICKS;
            stage("placed at " + placeAtBlock.getX() + "," + placeAtBlock.getY() + "," + placeAtBlock.getZ()
                    + " " + placeSide);
        } else {
            stage("place REJECTED by the controller at " + placeAtBlock.getX() + ","
                    + placeAtBlock.getY() + "," + placeAtBlock.getZ() + " " + placeSide);
        }
    }

    private void pressSneak(PrePlayerInputEvent e, boolean resetDelay) {
        e.setSneak(true);
        sneakingFromModule = true;
        if (resetDelay) unsneakStartTick = -1;
        repressSneak(e);
    }

    private void tryReleaseSneak(PrePlayerInputEvent e, boolean resetDelay) {
        if (!mc.thePlayer.onGround) {
            pressSneak(e, false);
            return;
        }

        int existed = mc.thePlayer.ticksExisted;
        if (unsneakStartTick == -1 && sneakJumpStartTick == -1) {
            unsneakStartTick = existed;
            double fromMs = Math.min(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            double toMs = Math.max(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            if (toMs <= fromMs) toMs = fromMs + 1;
            unsneakDelayTicks = Math.max(1, (int) Math.floor((fromMs + Math.random() * (toMs - fromMs)) / 50.0));
        }

        if (sneakJumpStartTick != -1 && existed - sneakJumpStartTick < sneakJumpDelayTicks) {
            pressSneak(e, false);
            return;
        }
        if (unsneakStartTick != -1 && existed - unsneakStartTick < unsneakDelayTicks) {
            pressSneak(e, false);
            return;
        }

        releaseSneak(e, resetDelay);
    }

    private void releaseSneak(PrePlayerInputEvent e, boolean resetDelay) {
        if (!sneakKeyPressed.isToggled()) {
            e.setSneak(false);
        } else if (sneakingFromModule && isManualSneak() && (placed || !mc.thePlayer.onGround)) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            e.setSneak(false);
            forceRelease = true;
        } else if (forceRelease) {
            e.setSneak(false);
        }

        sneakingFromModule = false;
        placed = false;
        if (resetDelay) resetUnsneak();
    }

    private void repressSneak(PrePlayerInputEvent e) {
        if (forceRelease && isManualSneak()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            e.setSneak(true);
        }
        forceRelease = false;
    }

    private void clearSneak(PrePlayerInputEvent e) {
        sneakingFromModule = false;
        resetUnsneak();
        if (sneakKeyPressed.isToggled()) repressSneak(e);
    }

    private void resetUnsneak() {
        unsneakStartTick = -1;
        sneakJumpStartTick = -1;
        sneakJumpDelayTicks = -1;
        unsneakDelayTicks = -1;
    }

    private boolean isManualSneak() {
        return Utils.isBindDown(mc.gameSettings.keyBindSneak);
    }

    private double computeEdgeOffset(AxisAlignedBB simBox) {
        AxisAlignedBB groundCheck = new AxisAlignedBB(
                simBox.minX, simBox.minY - 0.01, simBox.minZ,
                simBox.maxX, simBox.minY, simBox.maxZ
        );

        List<AxisAlignedBB> groundBoxes = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, groundCheck);
        if (groundBoxes.isEmpty()) return Double.NaN;

        double feetX = (simBox.minX + simBox.maxX) / 2.0;
        double feetZ = (simBox.minZ + simBox.maxZ) / 2.0;

        double minDist = Double.MAX_VALUE;
        for (AxisAlignedBB box : groundBoxes) {
            double closestX = Math.max(box.minX, Math.min(feetX, box.maxX));
            double closestZ = Math.max(box.minZ, Math.min(feetZ, box.maxZ));
            double dx = Math.abs(feetX - closestX);
            double dz = Math.abs(feetZ - closestZ);
            double dist = Math.max(dx, dz);
            minDist = Math.min(minDist, dist);
        }

        return minDist;
    }

    /** True while the retained aim still resolves to the same placement. */
    private boolean aimStillValid(ItemStack held, double reach) {
        if (!hasAim || targetSupport == null || targetSide == null || targetCell == null) return false;
        if (BlockUtils.replaceable(targetSupport)) return false;
        if (!BlockUtils.replaceable(targetCell)) return false;
        if (!BlockUtils.canPlaceBlockOnSide(held, targetSupport, targetSide)) return false;

        MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, aimYaw, aimPitch);
        return mop != null
                && mop.getBlockPos().equals(targetSupport)
                && mop.sideHit == targetSide;
    }

    /**
     * The one deck square to fill this tick.
     *
     * Priority is the square directly under the player -- if that is open they are already over
     * the gap -- then one and two ticks of movement ahead of them. Nothing to either side is ever
     * considered, so the bridge stays a single line in the direction of travel.
     */
    private BlockPos wantedCell() {
        double feetY = mc.thePlayer.getEntityBoundingBox().minY;

        // Latch the deck height while standing, so stepping off does not walk the target
        // downward with the player as they start to fall.
        if (mc.thePlayer.onGround || bridgeY == Integer.MIN_VALUE) {
            bridgeY = MathHelper.floor_double(feetY) - 1;
        }
        if (bridgeY >= feetY) return null;

        // Exactly the square under the player, which is what the reference scaffolds target.
        //
        // This used to also look one and two cells further along the walk line, and that was
        // the reason nothing worked: the only support for a cell ahead is the block the player
        // is currently standing on, and from on top of a block its own side faces are hidden by
        // the block itself. So the aim search failed on every tick spent on solid ground, the
        // aim was cleared, and the rotation never got anywhere. A block only becomes placeable
        // once the player is over the gap and their eye clears the support's column -- which is
        // exactly the square underfoot.
        BlockPos under = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX), bridgeY,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtils.replaceable(under) || !hasSupport(under)) return null;
        return under;
    }

    /** Whether any neighbouring block could be clicked to fill this cell. */
    private boolean hasSupport(BlockPos cell) {
        // Only the faces acquireAim can actually use. Counting a block above the cell as support
        // would mark cells targetable that the aim search then always rejects.
        for (EnumFacing side : FACES) {
            if (!BlockUtils.replaceable(cell.offset(side.getOpposite()))) return true;
        }
        return false;
    }

    /**
     * Finds an aim that provably fills {@code cell}.
     *
     * A block lands at {@code hitBlock.offset(sideHit)}, so the ray has to strike the exact face
     * of a neighbouring block that points at the cell. Aiming at a face's centre point is not
     * enough on its own -- from on top of a block its own side faces are occluded by the block
     * itself -- so every candidate is ray-cast first and only a verified hit is accepted.
     */
    private boolean acquireAim(BlockPos cell, ItemStack held, double reach, float baseYaw, float basePitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        double reachSq = reach * reach;

        List<Candidate> candidates = new ArrayList<>(FACES.length);
        for (EnumFacing side : FACES) {
            // side is the face of the support we must hit; the support sits opposite the cell.
            BlockPos support = cell.offset(side.getOpposite());
            if (BlockUtils.replaceable(support)) continue;
            if (!BlockUtils.canPlaceBlockOnSide(held, support, side)) continue;

            double dist = eye.squareDistanceTo(new Vec3(
                    support.getX() + 0.5, support.getY() + 0.5, support.getZ() + 0.5));
            if (dist > reachSq) continue;
            candidates.add(new Candidate(support, side, cell, dist));
        }
        if (candidates.isEmpty()) return false;

        candidates.sort((a, b) -> Double.compare(a.dist, b.dist));
        for (Candidate c : candidates) {
            if (aimAtFace(c, eye, reach, baseYaw, basePitch)) return true;
        }
        return false;
    }

    /** Sweeps aim points across one support face, cheapest turn first, and keeps the first hit. */
    private boolean aimAtFace(Candidate c, Vec3 eye, double reach, float baseYaw, float basePitch) {
        double bx = c.support.getX(), by = c.support.getY(), bz = c.support.getZ();
        List<float[]> aims = new ArrayList<>((GRID_N + 1) * (GRID_N + 1));

        for (int row = 0; row <= GRID_N; row++) {
            double v = Math.min(1.0, row * GRID_STEP);
            for (int col = 0; col <= GRID_N; col++) {
                double u = Math.min(1.0, col * GRID_STEP);
                double px, py, pz;
                switch (c.face.getAxis()) {
                    case Y:
                        px = bx + u;
                        pz = bz + v;
                        py = by + (c.face == EnumFacing.UP ? 1 - GRID_INSET : GRID_INSET);
                        break;
                    case X:
                        py = by + u;
                        pz = bz + v;
                        px = bx + (c.face == EnumFacing.EAST ? 1 - GRID_INSET : GRID_INSET);
                        break;
                    default:
                        px = bx + u;
                        py = by + v;
                        pz = bz + (c.face == EnumFacing.SOUTH ? 1 - GRID_INSET : GRID_INSET);
                        break;
                }
                float[] rot = RotationUtils.getRotationsFromEye(eye, px, py, pz);
                float cost = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - baseYaw))
                        + Math.abs(rot[1] - basePitch);
                aims.add(new float[] { rot[0], RotationUtils.clampPitch(rot[1]), cost });
            }
        }
        aims.sort((a, b) -> Float.compare(a[2], b[2]));

        for (float[] rot : aims) {
            MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, rot[0], rot[1]);
            if (mop == null) continue;
            if (!mop.getBlockPos().equals(c.support) || mop.sideHit != c.face) continue;

            aimYaw = rot[0];
            aimPitch = rot[1];
            targetSupport = c.support;
            targetSide = c.face;
            targetCell = c.cell;
            hasAim = true;
            return true;
        }
        return false;
    }

    /**
     * Reports where the silent-rotation pipeline got to this tick.
     *
     * Only fires when the stage text changes, or every two seconds while it is unchanged, so a
     * steady state reads as one line rather than twenty a second. Every early return in
     * onClientRotation names itself, so the first thing that stops working is visible directly
     * instead of having to be inferred from the module doing nothing.
     */
    private void stage(String text) {
        if (debug == null || !debug.isToggled()) return;
        long now = System.currentTimeMillis();
        if (text.equals(lastStage) && now - lastStageAt < 2000L) return;
        lastStage = text;
        lastStageAt = now;
        Utils.sendMessage("&7[BridgeAssist] &b" + text);
        writeLog(text);
    }

    /**
     * Mirrors the trace into logs/mindless-bridgeassist-debug.log next to Minecraft's own logs.
     *
     * The writer is opened once and kept, rather than reopened per line: this runs on the game
     * thread, and open-append-close per entry is exactly the kind of synchronous file I/O that
     * costs frames. Rate limiting upstream keeps this to roughly a line a second.
     */
    private void writeLog(String text) {
        try {
            if (logWriter == null) {
                java.io.File dir = new java.io.File(mc.mcDataDir, "logs");
                if (!dir.exists() && !dir.mkdirs()) return;
                java.io.File file = new java.io.File(dir, "mindless-bridgeassist-debug.log");
                logWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(file, true), java.nio.charset.StandardCharsets.UTF_8));
                logWriter.write("--- session start " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date()) + " ---");
                logWriter.newLine();
            }
            logWriter.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
                    + "  " + text);
            logWriter.newLine();
            logWriter.flush();
        } catch (Throwable ignored) {
            logWriter = null;
        }
    }

    private void closeLog() {
        if (logWriter == null) return;
        try {
            logWriter.flush();
            logWriter.close();
        } catch (Throwable ignored) {
        }
        logWriter = null;
    }

    /**
     * Rotation speed for the silent aim.
     *
     * This has to be fast, and that is not a style choice. A block is only placeable in the
     * couple of ticks after the player steps off the edge and before they have fallen into the
     * cell -- the debug trace showed exactly two. At the old speed of 15 the aim needed three
     * ticks to cover the ~100 degrees involved, so it never arrived before the target was gone,
     * and the player just walked off and fell. 27 covers that in a single tick.
     */
    private int speed() {
        return rotationSpeed == null ? 27 : (int) rotationSpeed.getInput();
    }

    /**
     * Drops the placement target but keeps the aim.
     *
     * The two used to be cleared together, so finishing a placement also released the rotation
     * and the view flicked back to wherever the player was really looking for a tick.
     */
    private void invalidateTarget() {
        targetSupport = null;
        targetSide = null;
        targetCell = null;
    }

    private void clearAim() {
        hasAim = false;
        targetSupport = null;
        targetSide = null;
        targetCell = null;
    }

    /** Hotbar slot holding a placeable block: the held one if it already is, else the first found. */
    private int blockSlot() {
        ItemStack current = mc.thePlayer.getHeldItem();
        if (current != null && current.stackSize > 0 && current.getItem() instanceof ItemBlock) {
            return mc.thePlayer.inventory.currentItem;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) return i;
        }
        return -1;
    }

    private void selectBlockSlot(int slot) {
        if (mc.thePlayer.inventory.currentItem == slot) return;
        if (previousSlot == -1) previousSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
    }

    private static class Candidate {
        final BlockPos support;
        final EnumFacing face;
        final BlockPos cell;
        final double dist;
        Candidate(BlockPos support, EnumFacing face, BlockPos cell, double dist) {
            this.support = support;
            this.face = face;
            this.cell = cell;
            this.dist = dist;
        }
    }
}
