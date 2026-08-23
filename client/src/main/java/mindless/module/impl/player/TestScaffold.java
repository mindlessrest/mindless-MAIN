package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.RightClickDelayTickEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

public class TestScaffold extends Module {
    private static final ItemBlock PLACEHOLDER = new ItemBlock(Blocks.tnt);
    private static final int BPS_WINDOW_MS = 3000;
    private static final int TIMESTAMP_RING = 512;

    private final SliderSetting rotationSpeed;
    private final ButtonSetting sprint;
    private final ButtonSetting keepY;
    private final ButtonSetting eagle;
    private final SliderSetting eagleSafety;
    private final ButtonSetting showBlockCount;
    private final ButtonSetting editPosition;

    private BlockPos previewPos;
    private EnumFacing previewFace;
    private BlockPos queuedPos;
    private EnumFacing queuedFace;
    private Vec3 queuedVec;
    private boolean placeQueued;

    private float lastYaw, lastPitch;
    private boolean lastRotsValid;

    private boolean eagleActive;

    private final long[] timestamps = new long[TIMESTAMP_RING];
    private int tsHead, tsCount;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    public TestScaffold() {
        super("TestScaffold", category.player);
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 180, 1, 360, 1));
        this.registerSetting(sprint = new ButtonSetting("Sprint", false));
        this.registerSetting(keepY = new ButtonSetting("Keep Y", false));
        this.registerSetting(eagle = new ButtonSetting("Eagle", false));
        this.registerSetting(eagleSafety = new SliderSetting("Eagle safety", " tick", 1, 1, 3, 0.1));
        this.registerSetting(showBlockCount = new ButtonSetting("Show block count", false));
        this.registerSetting(editPosition = new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
    }

    @Override
    public void onEnable() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        previewPos = null;
        previewFace = null;
        queuedPos = null;
        queuedFace = null;
        queuedVec = null;
        placeQueued = false;
        lastRotsValid = false;
        eagleActive = false;
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
    }

    @Override
    public void guiUpdate() {
        editPosition.setVisible(showBlockCount.isToggled(), this);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;

        float baseYaw = getBaseYaw();
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
        if (!Utils.nullCheck()) return;

        boolean placed = false;
        if (placeQueued && queuedPos != null && queuedFace != null && queuedVec != null) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() instanceof ItemBlock) {
                if (!keepY.isToggled() || queuedFace != EnumFacing.UP) {
                    mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                            queuedPos, queuedFace, queuedVec);
                    mc.thePlayer.swingItem();
                    placed = true;
                    recordPlacement();
                }
            }
        }
        placeQueued = false;

        updateEagle(placed);

        if (!placed && sprint.isToggled()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
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
        if (previewPos == null) return;
        int color = 0x4000AAFF;
        RenderUtils.renderBlock(previewPos, color, true, false);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (!showBlockCount.isToggled()) return;
        if (mc.currentScreen != null) return;

        int blocks = getTotalBlocks();
        if (blocks <= 0) return;

        syncPosition();
        String text = blocks + " blocks";
        int color = 0xFFFFFF;
        if (blocks <= 16) color = 0xFF5555;
        else if (blocks <= 32) color = 0xFFAA00;
        else if (blocks <= 64) color = 0xFFFF55;

        float bps = computeBps();
        String bpsText = String.format("%.1f BPS", bps);

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawStringWithShadow(text, posX, posY, color);
        mc.fontRendererObj.drawStringWithShadow(bpsText, posX, posY + mc.fontRendererObj.FONT_HEIGHT + 2, 0xAAAAAA);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
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
            int lookahead = (int) eagleSafety.getInput();
            boolean edgeDetected = false;
            for (int i = 0; i <= lookahead; i++) {
                BlockPos checkPos = new BlockPos(
                        mc.thePlayer.posX + mc.thePlayer.motionX * i,
                        mc.thePlayer.posY - 1 + mc.thePlayer.motionY * i,
                        mc.thePlayer.posZ + mc.thePlayer.motionZ * i
                );
                if (mc.theWorld.isAirBlock(checkPos)) {
                    edgeDetected = true;
                    break;
                }
            }
            if (edgeDetected && !placedThisTick) {
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

    private void setShiftOverride(boolean shift) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),
                shift || Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()));
    }

    private BlockData findBestPlacement() {
        EntityPlayerSP player = mc.thePlayer;
        float baseYaw = getBaseYaw();
        BlockPos playerPos = new BlockPos(player);
        BlockPos scanY = playerPos.down();

        double targetX = player.posX + player.motionX;
        double targetZ = player.posZ + player.motionZ;
        double targetY = scanY.getY() + 0.5;

        double existingScore = Double.MAX_VALUE;
        BlockData best = null;
        double bestScore = Double.MAX_VALUE;

        boolean tower = !player.onGround && !keepY.isToggled();
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
        if (sprint.isToggled()) return mc.thePlayer.rotationYaw;

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
        float maxStep = (float) rotationSpeed.getInput();
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

    private void recordPlacement() {
        long now = System.currentTimeMillis();
        timestamps[tsHead % TIMESTAMP_RING] = now;
        tsHead++;
        tsCount = Math.min(tsCount + 1, TIMESTAMP_RING);
    }

    private float computeBps() {
        if (tsCount == 0) return 0f;
        long now = System.currentTimeMillis();
        long cutoff = now - BPS_WINDOW_MS;
        int inWindow = 0;
        int total = Math.min(tsCount, TIMESTAMP_RING);
        int start = tsHead - total;
        if (start < 0) start += TIMESTAMP_RING;
        for (int i = 0; i < total; i++) {
            int idx = (start + i) % TIMESTAMP_RING;
            if (timestamps[idx] > cutoff) inWindow++;
        }
        return inWindow / (BPS_WINDOW_MS / 1000f);
    }

    private int getTotalBlocks() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private void syncPosition() {
        syncPosition(new ScaledResolution(mc));
    }

    private void syncPosition(ScaledResolution sr) {
        int w = Math.max(1, sr.getScaledWidth());
        int h = Math.max(1, sr.getScaledHeight());
        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            if (Float.isNaN(posX) || Float.isNaN(posY)) {
                posX = w / 2f - 20;
                posY = h / 2f - 20;
            }
            relativePosX = posX / w;
            relativePosY = posY / h;
        }
        posX = relativePosX * w;
        posY = relativePosY * h;
    }

    public void setRelativePosition(float nx, float ny) {
        relativePosX = nx;
        relativePosY = ny;
        syncPosition();
    }

    public void resetPosition() {
        relativePosX = Float.NaN;
        relativePosY = Float.NaN;
        posX = Float.NaN;
        posY = Float.NaN;
        syncPosition();
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

    private class EditScreen extends GuiScreen {
        private boolean dragging;
        private float ax, ay, lax, lay;
        private int lmx, lmy;
        private GuiButtonExt resetBtn;

        @Override
        public void initGui() {
            super.initGui();
            buttonList.add(resetBtn = new GuiButtonExt(1, width - 90, height - 25, 85, 20, "Reset"));
            syncPosition(new ScaledResolution(mc));
            ax = posX;
            ay = posY;
        }

        @Override
        public void drawScreen(int mx, int my, float pt) {
            ScaledResolution sr = new ScaledResolution(mc);
            if (!dragging) { syncPosition(sr); ax = posX; ay = posY; }
            drawRect(0, 0, width, height, 0xB2000000);

            posX = ax; posY = ay;
            int blocks = getTotalBlocks();
            String text = blocks + " blocks";
            mc.fontRendererObj.drawStringWithShadow(text, posX, posY, 0xFFFFFF);
            String bps = String.format("%.1f BPS", computeBps());
            mc.fontRendererObj.drawStringWithShadow(bps, posX, posY + mc.fontRendererObj.FONT_HEIGHT + 2, 0xAAAAAA);

            try { handleInput(); } catch (IOException ignored) {}
            super.drawScreen(mx, my, pt);
        }

        @Override
        protected void mouseClickMove(int mx, int my, int btn, long time) {
            super.mouseClickMove(mx, my, btn, time);
            if (btn != 0) return;
            if (dragging) {
                ax = lax + (mx - lmx);
                ay = lay + (my - lmy);
            } else {
                int tw = mc.fontRendererObj.getStringWidth(getTotalBlocks() + " blocks");
                int th = mc.fontRendererObj.FONT_HEIGHT * 2 + 2;
                if (mx >= posX - 2 && mx <= posX + tw + 2 && my >= posY - 2 && my <= posY + th + 2) {
                    dragging = true;
                    lmx = mx; lmy = my; lax = ax; lay = ay;
                }
            }
        }

        @Override
        protected void mouseReleased(int mx, int my, int state) {
            super.mouseReleased(mx, my, state);
            if (state == 0) dragging = false;
        }

        @Override
        public void actionPerformed(GuiButton btn) {
            if (btn == resetBtn) { resetPosition(); ax = posX; ay = posY; }
        }

        @Override
        public boolean doesGuiPauseGame() { return false; }
    }
}
