package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.runtime.ItemRendererState;
import keystrokesmod.runtime.LunarEventBridge;
import keystrokesmod.script.ScriptDefaults;
import keystrokesmod.script.model.Simulation;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.ScaffoldBlockCount;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.Timer;
import net.minecraft.block.Block;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Auto-bridge implementation ported from scaffold2.java to this client's
 * Forge events, rotation pipeline, inventory, and block placement APIs.
 */
public class Scaffold extends Module {
    private static final float SENSITIVITY_QUANTUM = 0.03404715F;
    private static final double MAX_REACH_SQ = 20.25D;
    private static final double[] PROJECTION_MULTIPLIERS = {0.5D, 1.0D, 1.5D, 2.0D, 2.5D, 3.0D, 3.5D};
    private static final String[] REPLACEABLE_BLOCKS = {
            "air", "water", "flowing_water", "lava", "flowing_lava", "fire", "tallgrass",
            "deadbush", "snow_layer", "double_plant", "vine"
    };
    private static final String[] UNPLACEABLE_EXACT = {
            "snow_layer", "web", "sapling", "daylight_detector", "beacon", "banner",
            "end_portal_frame", "end_portal", "lever", "stone_button", "wooden_button", "skull",
            "cactus", "double_plant", "waterlily", "carpet", "tripwire_hook", "tallgrass",
            "yellow_flower", "red_flower", "flower_pot", "sign", "ladder", "torch", "redstone_torch",
            "unlit_redstone_torch", "gravel", "clay", "sand", "soul_sand", "chest", "trapped_chest",
            "ender_chest", "furnace", "lit_furnace", "jukebox", "enchanting_table", "dropper",
            "dispenser", "hopper", "anvil", "noteblock", "crafting_table", "mob_spawner", "brewing_stand", "bed"
    };
    private static final String[] UNPLACEABLE_CONTAINS = {
            "stairs", "slab", "fence", "pane", "rail", "door", "torch", "pumpkin", "flower",
            "sapling", "banner", "button", "skull", "web", "carpet", "cactus", "sign", "mushroom"
    };
    private static final EnumFacing[] SUPPORT_FACES = {
            EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    private final ButtonSetting moveFix;
    private final ButtonSetting strictRaycast;
    private final ButtonSetting swing;
    private final ButtonSetting safeWalk;
    public final ButtonSetting showBlockCount;
    private final ButtonSetting debugLog;
    private final SliderSetting mode;
    private final SliderSetting rotationSpeed;
    private final SliderSetting legitSneakDelay;
    private final SliderSetting sprint;
    private final SliderSetting itemSwitch;

    public final Map<BlockPos, Timer> highlight = new HashMap<>();
    public final java.util.concurrent.atomic.AtomicInteger lastSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
    public boolean hasSwapped;
    public boolean moduleEnabled;

    private ScaffoldBlockCount scaffoldBlockCount;
    private int silentReturnSlot = -1;
    private int blockCount = -1;
    private int countedSlot = -1;
    private int rotationTick;
    private int startY;
    private boolean keepYLocked;
    private boolean forcedSneak;
    private boolean rotationInitialized;
    private boolean canRotate;
    private boolean legitSneaking;
    private long legitUnsneakAt;
    private long legitRandomAt;
    private float legitYawOffset;
    private float legitPitchOffset;
    private float legitYawSide;
    private float currentYaw;
    private float currentPitch;
    private long lastRotationUpdate;
    private PrintWriter debugWriter;
    private int debugTick;
    private BlockPos lastPlacedTarget;

    public Scaffold() {
        super("Scaffold", category.player);
        registerSetting(new DescriptionSetting("Auto-bridge"));
        registerSetting(moveFix = new ButtonSetting("Move fix", true));
        registerSetting(safeWalk = new ButtonSetting("Safe walk", true));
        registerSetting(swing = new ButtonSetting("Swing", true));
        registerSetting(showBlockCount = new ButtonSetting("Block counter", true));
        registerSetting(debugLog = new ButtonSetting("Debug log", true));
        registerSetting(mode = new SliderSetting("Mode", 0, new String[]{"GodBridge", "Telly", "Legit"}));
        registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 100, 1, 100, 1));
        registerSetting(legitSneakDelay = new SliderSetting("Legit sneak delay", "ms", 50, 50, 300, 5));
        registerSetting(sprint = new SliderSetting("Sprint", 0, new String[]{"None", "Vanilla"}));
        registerSetting(itemSwitch = new SliderSetting("Item switch", 2, new String[]{"None", "Silent", "Hotbar", "HoldBlocks"}));
        registerSetting(strictRaycast = new ButtonSetting("Strict raycast", false));
        this.alwaysOn = true;
    }

    @Override
    public void onEnable() {
        if (ModuleManager.testScaffold != null && ModuleManager.testScaffold.isEnabled()) {
            ModuleManager.testScaffold.disable();
            Utils.sendMessage("&eTestScaffold disabled &7(Scaffold took over)");
        }
        lastSlot.set(mc.thePlayer == null ? -1 : mc.thePlayer.inventory.currentItem);
        silentReturnSlot = -1;
        blockCount = -1;
        countedSlot = -1;
        rotationTick = 3;
        startY = 256;
        keepYLocked = false;
        forcedSneak = false;
        rotationInitialized = false;
        canRotate = false;
        currentYaw = 0.0F;
        currentPitch = 85.0F;
        lastRotationUpdate = 0L;
        legitRandomAt = 0L;
        legitYawOffset = 0.0F;
        legitPitchOffset = 0.0F;
        legitYawSide = Math.random() < 0.5D ? -1.0F : 1.0F;
        legitSneaking = false;
        legitUnsneakAt = 0L;
        hasSwapped = false;
        moduleEnabled = true;
        debugTick = 0;
        lastPlacedTarget = null;
        openDebugLog();
        LunarEventBridge.registerTickListener(scaffoldBlockCount = new ScaffoldBlockCount(mc));
    }

    @Override
    public void onDisable() {
        cleanup();
        if (mc.thePlayer != null && lastSlot.get() != -1) {
            mc.thePlayer.inventory.currentItem = lastSlot.get();
        }
        lastSlot.set(-1);
        ItemRendererState.setCancelUpdate(false);
        ItemRendererState.setCancelReset(false);
        closeDebugLog();
    }

    @Override
    public void guiButtonToggled(ButtonSetting buttonSetting) {
        if (buttonSetting == debugLog) {
            if (debugLog.isToggled()) openDebugLog();
            else closeDebugLog();
        }
    }

    private void cleanup() {
        if (mc.thePlayer != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            mc.thePlayer.setSneaking(false);
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.setSprinting(false);
        }
        silentReturnSlot = -1;
        canRotate = false;
        rotationInitialized = false;
        keepYLocked = false;
        forcedSneak = false;
        legitSneaking = false;
        legitUnsneakAt = 0L;
        hasSwapped = false;
        moduleEnabled = false;
        lastPlacedTarget = null;
        disableMovementFix();
        ScriptDefaults.bridge.remove("ScaffoldRunning");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        // Source script callback intentionally leaves mouse input untouched.
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!moduleEnabled || !Utils.nullCheck()) return;
        if (rotationTick > 0) rotationTick--;
        updateSprint();
        updateKeepY();
        updateBlockCount();
        debugTick++;
        debug("PRE_UPDATE input=" + mc.thePlayer.moveForward + "," + mc.thePlayer.moveStrafing
                + " pos=" + mc.thePlayer.posX + "," + mc.thePlayer.posY + "," + mc.thePlayer.posZ
                + " ground=" + mc.thePlayer.onGround + " slot=" + mc.thePlayer.inventory.currentItem
                + " blocks=" + blockCount + " rotTick=" + rotationTick);
        if (mc.currentScreen != null) {
            setForcedSneak(false);
            canRotate = false;
            rotationInitialized = false;
            returnSilentSlot();
            ScriptDefaults.bridge.add("ScaffoldRunning", getName());
            return;
        }
        blockCount = isBlockStack(mc.thePlayer.getHeldItem()) ? mc.thePlayer.getHeldItem().stackSize : 0;
        ScriptDefaults.bridge.add("ScaffoldRunning", getName());
        if (!selectBlock()) return;
        hasSwapped = true;
        updateRotation();
        setForcedSneak(!isLegitMode() && safeWalk.isToggled() && mc.thePlayer.onGround
                && !mc.gameSettings.keyBindSprint.isKeyDown());
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent event) {
        if (!moduleEnabled || !Utils.nullCheck()) return;
        if (mc.currentScreen != null) {
            returnSilentSlot();
            return;
        }
        if (!hasSwapped) return;
        if (rotationTick <= 0 && KillAuraFree()) {
            placeBlocks();
        }
        if (silentReturnSlot != -1) {
            mc.thePlayer.inventory.currentItem = silentReturnSlot;
            silentReturnSlot = -1;
        }
    }

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!moduleEnabled || !Utils.nullCheck()) return;
        if (isHoldBlocksMode() && !hasHeldBlocks()) {
            canRotate = false;
            rotationInitialized = false;
            rotationTick = 3;
            disableMovementFix();
            return;
        }
        debug("PRE_MOTION view=" + event.getYaw() + "," + event.getPitch() + " spoof=" + currentYaw + "," + currentPitch
                + " canRotate=" + canRotate + " applied=" + (canRotate && rotationTick <= 0));
        if (!moveFix.isToggled()) disableMovementFix();
        if (canRotate && rotationTick <= 0) {
            event.setRotations(currentYaw, currentPitch);
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!moduleEnabled || !Utils.nullCheck() || mc.currentScreen != null) return;
        if (isTellyMode() && mc.thePlayer.onGround && (Math.abs(event.getForward()) > 0.01F || Math.abs(event.getStrafe()) > 0.01F)) {
            event.setJump(true);
        }
        if (isLegitMode()) {
            setForcedSneak(false);
            updateEagle(event);
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent event) {
        if (moduleEnabled && event.entity == mc.thePlayer) {
            lastSlot.set(mc.thePlayer.inventory.currentItem);
            silentReturnSlot = -1;
            blockCount = -1;
            countedSlot = -1;
            rotationInitialized = false;
            canRotate = false;
            keepYLocked = false;
            rotationTick = 3;
            legitSneaking = false;
            legitUnsneakAt = 0L;
            disableMovementFix();
            setForcedSneak(false);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!moduleEnabled || event.world != mc.theWorld) return;
        silentReturnSlot = -1;
        blockCount = -1;
        countedSlot = -1;
        rotationInitialized = false;
        canRotate = false;
        keepYLocked = false;
        legitSneaking = false;
        legitUnsneakAt = 0L;
        disableMovementFix();
        setForcedSneak(false);
        ScriptDefaults.bridge.remove("ScaffoldRunning");
    }

    private boolean KillAuraFree() {
        return keystrokesmod.module.impl.combat.KillAura.target == null;
    }

    private void updateSprint() {
        if (sprint.getInput() == 0) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            mc.thePlayer.setSprinting(false);
        }
    }

    private void updateKeepY() {
        if (!isTellyMode()) {
            keepYLocked = false;
        } else if (mc.thePlayer.onGround && !keepYLocked) {
            startY = MathHelper.floor_double(mc.thePlayer.posY);
            keepYLocked = true;
        }
    }

    private void updateBlockCount() {
        int slot = mc.thePlayer.inventory.currentItem;
        if (slot == countedSlot) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        blockCount = isBlockStack(held) ? held.stackSize : 0;
        countedSlot = slot;
    }

    private boolean selectBlock() {
        if (isHoldBlocksMode()) return hasHeldBlocks();
        if (hasHeldBlocks() && blockCount > 0) return true;
        if (itemSwitch.getInput() == 0 || itemSwitch.getInput() == 3) return false;
        if (blockCount > 0) return false;
        int slot = findBlockSlot();
        if (slot == -1) return false;
        if (mc.thePlayer.inventory.currentItem != slot) {
            if (itemSwitch.getInput() == 1) silentReturnSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
        }
        ItemStack selected = mc.thePlayer.inventory.getStackInSlot(slot);
        blockCount = selected == null ? 0 : selected.stackSize;
        countedSlot = slot;
        return true;
    }

    private int findBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        int start = blockCount == 0 ? current - 1 : current;
        for (int i = 0; i < 9; i++) {
            int slot = (start - i) % 9;
            if (slot < 0) slot += 9;
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (!isBlockStack(stack)) continue;
            return slot;
        }
        return -1;
    }

    private void updateRotation() {
        long now = System.currentTimeMillis();
        if (isLegitMode()) {
            if (legitRandomAt == 0L || now - legitRandomAt >= 75L) {
                legitRandomAt = now;
                legitYawOffset = (float) (Math.random() * 4.0D - 2.0D);
                legitPitchOffset = (float) (Math.random() * 2.0D - 1.0D);
            }
        } else {
            legitYawOffset = legitPitchOffset = 0.0F;
        }

        float targetYaw = quantize(mc.thePlayer.rotationYaw + 180.0F
                + (isLegitMode() ? legitYawSide * 45.0F + legitYawOffset : 0.0F));
        float targetPitch = isLegitMode() ? clamp(78.0F + legitPitchOffset, 70.0F, 84.0F) : 85.0F;
        canRotate = true;
        if (!rotationInitialized) {
            currentYaw = quantize(mc.thePlayer.rotationYaw);
            currentPitch = clamp(mc.thePlayer.rotationPitch, -90.0F, 90.0F);
            rotationInitialized = true;
            lastRotationUpdate = now;
            return;
        }
        if (lastRotationUpdate == now) return;
        lastRotationUpdate = now;
        float maxStep = 1.0F + (float) rotationSpeed.getInput() * 0.12F;
        currentYaw = approachAngle(currentYaw, targetYaw, maxStep);
        currentPitch = clamp(approach(currentPitch, targetPitch, maxStep), -90.0F, 90.0F);
        if (moveFix.isToggled()) enableMovementFix();
    }

    private void updateEagle(PrePlayerInputEvent event) {
        if (mc.gameSettings.keyBindSneak.isKeyDown() || !mc.thePlayer.onGround
                || event.getForward() == 0.0F && event.getStrafe() == 0.0F) {
            legitSneaking = false;
            legitUnsneakAt = 0L;
            event.setSneak(false);
            return;
        }
        Simulation simulation;
        try {
            simulation = Simulation.create();
            if (mc.thePlayer.movementInput.sneak) {
                simulation.setForward(event.getForward() / 0.3F);
                simulation.setStrafe(event.getStrafe() / 0.3F);
                simulation.setSneak(false);
            }
            else {
                simulation.setForward(event.getForward());
                simulation.setStrafe(event.getStrafe());
            }
            simulation.tick();
        }
        catch (Exception ignored) {
            legitSneaking = false;
            legitUnsneakAt = 0L;
            event.setSneak(false);
            return;
        }
        keystrokesmod.script.model.Vec3 simulatedPos = simulation.getPosition();
        boolean shouldSneak = predictedFootprintHasGap(simulatedPos);
        if (shouldSneak) {
            legitSneaking = true;
            legitUnsneakAt = 0L;
            event.setSneak(true);
        } else if (legitSneaking) {
            if (legitUnsneakAt == 0L) legitUnsneakAt = System.currentTimeMillis() + (long) legitSneakDelay.getInput();
            if (System.currentTimeMillis() < legitUnsneakAt) {
                event.setSneak(true);
            }
            else {
                legitSneaking = false;
                legitUnsneakAt = 0L;
                event.setSneak(false);
            }
        } else {
            event.setSneak(false);
        }
    }

    private boolean predictedFootprintHasGap(keystrokesmod.script.model.Vec3 predicted) {
        int floorY = MathHelper.floor_double(predicted.y - 0.01D);
        double[][] corners = {{-0.3D, -0.3D}, {0.3D, -0.3D}, {-0.3D, 0.3D}, {0.3D, 0.3D}};
        for (double[] corner : corners) {
            if (isSourceReplaceable(new BlockPos(
                    MathHelper.floor_double(predicted.x + corner[0]), floorY,
                    MathHelper.floor_double(predicted.z + corner[1])))) {
                return true;
            }
        }
        return false;
    }

    private void placeBlocks() {
        BlockPos target = new BlockPos(mc.thePlayer.posX, keepYLocked ? startY - 1 : Math.floor(mc.thePlayer.posY) - 1, mc.thePlayer.posZ);
        if (tryPlace(target)) return;
        double motionSq = mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ;
        if (motionSq <= 0.000001D) return;
        int lastX = target.getX();
        int lastZ = target.getZ();
        for (double multiplier : PROJECTION_MULTIPLIERS) {
            BlockPos projected = new BlockPos(mc.thePlayer.posX + mc.thePlayer.motionX * multiplier, target.getY(), mc.thePlayer.posZ + mc.thePlayer.motionZ * multiplier);
            if (projected.getX() == lastX && projected.getZ() == lastZ) continue;
            lastX = projected.getX();
            lastZ = projected.getZ();
            if (tryPlace(projected)) return;
        }
    }

    private boolean tryPlace(BlockPos target) {
        if (!isSourceReplaceable(target)) return false;
        if (lastPlacedTarget != null && !isSourceReplaceable(lastPlacedTarget)) {
            lastPlacedTarget = null;
        }
        if (target.equals(lastPlacedTarget)) {
            debug("PLACE_SKIP duplicate target=" + target);
            return false;
        }
        Vec3 eyes = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        Vec3 look = lookVector(currentYaw, currentPitch);
        Placement best = null;
        for (EnumFacing face : SUPPORT_FACES) {
            BlockPos support = target.offset(face.getOpposite());
            Block block = BlockUtils.getBlock(support);
            if (isSourceReplaceable(support) || BlockUtils.isInteractable(block)) continue;
            Vec3 hit = BlockUtils.getFaceCenter(support, face);
            double distance = hit.squareDistanceTo(eyes);
            if (distance > MAX_REACH_SQ) continue;
            ItemStack held = mc.thePlayer.getHeldItem();
            if (!BlockUtils.canPlaceBlockOnSide(held, support, face)) continue;
            if (strictRaycast.isToggled() && !canSee(support, face, eyes, hit)) continue;
            double dx = hit.xCoord - eyes.xCoord;
            double dy = hit.yCoord - eyes.yCoord;
            double dz = hit.zCoord - eyes.zCoord;
            double length = Math.sqrt(distance);
            double alignment = length == 0.0D ? -1.0D : (look.xCoord * dx + look.yCoord * dy + look.zCoord * dz) / length;
            double score = distance + (1.0D - alignment) * 0.25D;
            if (best == null || score < best.score) best = new Placement(support, face, hit, score);
        }
        if (best == null) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!isBlockStack(held)) return false;
        boolean placed = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, best.support, best.face, best.hit);
        debug("PLACE target=" + target + " support=" + best.support + " face=" + best.face + " hit=" + best.hit + " result=" + placed);
        if (!placed) return false;
        lastPlacedTarget = target;
        blockCount = Math.max(0, blockCount - 1);
        if (swing.isToggled()) mc.thePlayer.swingItem();
        else mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
        highlight.put(best.support.offset(best.face), null);
        return true;
    }

    private boolean canSee(BlockPos support, EnumFacing face, Vec3 eyes, Vec3 hit) {
        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(eyes, hit, false, false, false);
        return result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && support.equals(result.getBlockPos()) && face == result.sideHit;
    }

    private Vec3 lookVector(float yaw, float pitch) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
    }

    private boolean isBlockStack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return false;
        String name = blockName(((ItemBlock) stack.getItem()).getBlock());
        if (name.isEmpty()) return false;
        for (String exact : UNPLACEABLE_EXACT) {
            if (name.equals(exact)) return false;
        }
        for (String fragment : UNPLACEABLE_CONTAINS) {
            if (name.contains(fragment)) return false;
        }
        return true;
    }

    private boolean isSourceReplaceable(BlockPos pos) {
        String name = blockName(BlockUtils.getBlock(pos));
        for (String replaceable : REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) return true;
        }
        return false;
    }

    private String blockName(Block block) {
        if (block == null) return "";
        Object registryName = Block.blockRegistry.getNameForObject(block);
        if (registryName == null) return "";
        String name = registryName.toString();
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private boolean hasHeldBlocks() {
        return isBlockStack(mc.thePlayer.getHeldItem());
    }

    public boolean holdingBlocks() {
        return hasHeldBlocks() || (itemSwitch.getInput() != 0 && findBlockSlot() != -1);
    }

    public int totalBlocks() {
        int total = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isBlockStack(stack)) total += stack.stackSize;
        }
        return total;
    }

    public boolean safewalk() {
        return isEnabled() && safeWalk.isToggled();
    }

    public boolean canSafewalk() {
        return safewalk() && (!isTellyMode() || !ModuleManager.tower.canTower());
    }

    public boolean sprint() {
        return isEnabled() && sprint.getInput() > 0.0D;
    }

    public boolean stopFastPlace() {
        return isEnabled();
    }

    public boolean stopRotation() {
        return isEnabled();
    }

    public boolean blockAbove() {
        return !isSourceReplaceable(new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY + 2.0D, mc.thePlayer.posZ));
    }

    public void rotateForward() {
        currentYaw = quantize(mc.thePlayer.rotationYaw - 180.0F);
        currentPitch = 10.0F;
    }

    private boolean isTellyMode() { return ((int) mode.getInput()) == 1; }
    private boolean isLegitMode() { return ((int) mode.getInput()) == 2; }
    private boolean isHoldBlocksMode() { return ((int) itemSwitch.getInput()) == 3; }

    private void enableMovementFix() {
        if (ModuleManager.movementFix != null && !ModuleManager.movementFix.isEnabled()) ModuleManager.movementFix.enable();
    }

    private void disableMovementFix() {
        // Movement Fix is shared and enabled by ModuleManager for all silent rotations.
    }

    private void setForcedSneak(boolean enabled) {
        if (forcedSneak == enabled) return;
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.sneak = enabled;
        }
        forcedSneak = enabled;
    }

    private void returnSilentSlot() {
        if (silentReturnSlot == -1) return;
        if (mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = silentReturnSlot;
        }
        silentReturnSlot = -1;
    }

    private void openDebugLog() {
        closeDebugLog();
        if (!debugLog.isToggled()) return;
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "Mindless");
            if (!dir.exists()) dir.mkdirs();
            debugWriter = new PrintWriter(new FileWriter(new File(dir, "scaffold-debug.log"), false), true);
            debugWriter.println("=== Scaffold debug " + new java.util.Date() + " ===");
        }
        catch (Exception ignored) {
            debugWriter = null;
        }
    }

    private void closeDebugLog() {
        if (debugWriter != null) {
            debugWriter.close();
            debugWriter = null;
        }
    }

    private void debug(String message) {
        if (debugWriter != null && (debugTick % 5 == 0 || message.startsWith("PLACE"))) {
            debugWriter.println(System.currentTimeMillis() + " " + message);
        }
    }

    private static float approach(float current, float target, float step) {
        float difference = target - current;
        return Math.abs(difference) <= step ? target : current + Math.copySign(step, difference);
    }

    private static float approachAngle(float current, float target, float step) {
        return current + MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(target - current), -step, step);
    }

    private static float quantize(float angle) {
        return (float) (Math.round(angle / SENSITIVITY_QUANTUM) * SENSITIVITY_QUANTUM);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Placement {
        private final BlockPos support;
        private final EnumFacing face;
        private final Vec3 hit;
        private final double score;

        private Placement(BlockPos support, EnumFacing face, Vec3 hit, double score) {
            this.support = support;
            this.face = face;
            this.hit = hit;
            this.score = score;
        }
    }
}
