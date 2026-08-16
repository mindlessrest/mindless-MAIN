package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityPlayerSP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.*;
import keystrokesmod.utility.Timer;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockTNT;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Scaffold extends Module {

    private final SliderSetting sprintScaffoldMotion;
    private final SliderSetting fastScaffoldMotion;
    public  SliderSetting rotation;
    private SliderSetting sprint;
    private SliderSetting fastScaffold;
    private SliderSetting multiPlace;
    public  ButtonSetting autoSwap;
    private ButtonSetting cancelKnockBack;
    private ButtonSetting fastOnRMB;
    public  ButtonSetting highlightBlocks;
    private ButtonSetting jumpFacingForward;
    public  ButtonSetting safeWalk;
    public  ButtonSetting showBlockCount;
    private ButtonSetting silentSwing;
    public  ButtonSetting sprintScaffoldOnSpeed;
    public  ButtonSetting sendPacket;

    private static final String[] rotationModes      = {"None","Simple","Offset A","Offset B","Snap"};
    private static final String[] sprintModes        = {"None","Vanilla","Float"};
    private static final String[] fastScaffoldModes  = {"None","Jump A","Jump B","Jump C","Keep-Y A","Keep-Y B","Keep-Y C"};
    private static final String[] multiPlaceModes    = {"Disabled","1 extra","2 extra"};

    public Map<BlockPos, Timer> highlight = new HashMap<>();

    public AtomicInteger lastSlot = new AtomicInteger(-1);

    public  boolean hasSwapped;
    private boolean hasPlaced;
    private boolean rotateForward;
    private int     onGroundTicks;
    private double  startYPos = -1;
    public  boolean fastScaffoldKeepY;
    private boolean firstKeepYPlace;
    private boolean rotatingForward;
    private int     keepYTicks;
    private boolean lowhop;
    private int     rotationDelay;
    private int     blockSlot = -1;

    public boolean canBlockFade;

    private boolean floatJumped;
    private boolean floatStarted;
    private boolean floatWasEnabled;
    private boolean floatKeepY;

    private Vec3      targetBlock;
    private PlaceData blockInfo;
    private Vec3      hitVec;
    private Vec3      lookVec;
    private float[]   blockRotations;
    private long  rotationTimeout = 250L;
    private float lastYaw2 = 0f;
    public  float yaw, pitch, blockYaw, yawOffset;
    private boolean set2;

    public  boolean moduleEnabled;
    public  boolean isEnabled;
    private boolean disabledModule;
    private boolean dontDisable, towerEdge;
    private int     disableTicks;
    private int     scaffoldTicks;

    private boolean was451, was452;
    private float   minOffset;
    private long    firstStroke, strokeDelay = 575;
    private float   yawAngle;

    public Scaffold() {
        super("Scaffold", category.player);
        this.registerSetting(sprintScaffoldMotion = new SliderSetting("Sprint scaffold motion", "x", 0.96, 0.5, 1.2, 0.01));
        this.registerSetting(fastScaffoldMotion   = new SliderSetting("Fast scaffold motion",   "x", 0.94, 0.5, 1.2, 0.01));
        this.registerSetting(rotation    = new SliderSetting("Rotation",       1, rotationModes));
        this.registerSetting(sprint      = new SliderSetting("Sprint mode",    0, sprintModes));
        this.registerSetting(fastScaffold = new SliderSetting("Fast scaffold", 0, fastScaffoldModes));
        this.registerSetting(multiPlace  = new SliderSetting("Multi-place",    0, multiPlaceModes));
        this.registerSetting(autoSwap         = new ButtonSetting("Auto swap",         true));
        this.registerSetting(cancelKnockBack  = new ButtonSetting("Cancel knockback",  false));
        this.registerSetting(fastOnRMB        = new ButtonSetting("Fast on RMB",       true));
        this.registerSetting(highlightBlocks  = new ButtonSetting("Highlight blocks",  true));
        this.registerSetting(jumpFacingForward = new ButtonSetting("Jump facing forward", false));
        this.registerSetting(safeWalk         = new ButtonSetting("Safewalk",          true));
        this.registerSetting(showBlockCount   = new ButtonSetting("Show block count",  true));
        this.registerSetting(silentSwing      = new ButtonSetting("Silent swing",      false));
        this.registerSetting(sprintScaffoldOnSpeed = new ButtonSetting("Sprint scaffold on speed", false));
        this.registerSetting(sendPacket       = new ButtonSetting("Send packet",       true));
        this.alwaysOn = true;
    }

    @Override
    public void onDisable() {
        disabledModule = true;
        moduleEnabled  = false;
    }

    @Override
    public void onEnable() {
        isEnabled     = true;
        moduleEnabled = true;
        if (!sendPacket.isToggled()) {
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        }
        lastSlot.set(-1);
    }

    // ── Cancel mouse while scaffolding ─────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (!isEnabled) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(),  false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        if (e.button >= 0) e.setCanceled(true);
    }

    // ── Rotation (PreMotionEvent) ──────────────────────────────────────────────

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent e) {
        if (!Utils.nullCheck()) return;
        onGroundTicks = !mc.thePlayer.onGround ? 0 : ++onGroundTicks;
        if (!isEnabled) return;

        if (Utils.isMoving()) scaffoldTicks++;
        else scaffoldTicks = 0;

        canBlockFade = true;
        int simpleY = (int) Math.round((e.posY % 1) * 10000);

        // ── Fast scaffold jump ─────────────────────────────────────────────────
        if (Utils.keysDown() && usingFastScaffold() && fastScaffold.getInput() >= 1
                && !LongJump.stopModules) {
            if (mc.thePlayer.onGround && Utils.isMoving()) {
                if (scaffoldTicks > 1) {
                    rotateForward();
                    mc.thePlayer.jump();
                    Utils.setSpeed(getSpeed(getSpeedLevel()) - Utils.randomizeDouble(0.0003, 0.0001));
                    if (fastScaffold.getInput() == 5 || fastScaffold.getInput() == 2 && firstKeepYPlace) {
                        lowhop = true;
                    }
                    if (startYPos == -1 || Math.abs(startYPos - e.posY) > 5) {
                        startYPos = e.posY;
                        fastScaffoldKeepY = true;
                    }
                }
            }
        } else if (fastScaffoldKeepY) {
            fastScaffoldKeepY = firstKeepYPlace = false;
            startYPos = -1;
            keepYTicks = 0;
        }

        if (lowhop) {
            switch (simpleY) {
                case 4200: mc.thePlayer.motionY = 0.39; break;
                case 1138: mc.thePlayer.motionY -= 0.13; break;
                case 2031: mc.thePlayer.motionY -= 0.2; lowhop = false; break;
            }
        }

        // ── Float sprint ───────────────────────────────────────────────────────
        if (sprint.getInput() == 2 && !usingFastScaffold() && !ModuleManager.bHop.isEnabled()
                && !LongJump.stopModules) {
            floatWasEnabled = true;
            if (!floatStarted) {
                if (onGroundTicks > 8 && mc.thePlayer.onGround) {
                    floatKeepY = true;
                    startYPos  = e.posY;
                    mc.thePlayer.jump();
                    Utils.setSpeed(Utils.getHorizontalSpeed() - 0.1);
                    floatJumped = true;
                } else if (onGroundTicks <= 8 && mc.thePlayer.onGround) {
                    floatStarted = true;
                }
                if (floatJumped && !mc.thePlayer.onGround) floatStarted = true;
            }
            if (floatStarted && mc.thePlayer.onGround) {
                floatKeepY = false;
                startYPos  = -1;
                if (moduleEnabled) {
                    e.setPosY(e.getPosY() + 1E-12F);
                    if (Utils.isMoving()) Utils.setSpeed(getFloatSpeed(getSpeedLevel()));
                }
            }
        } else if (floatWasEnabled && moduleEnabled) {
            if (floatKeepY) startYPos = -1;
            floatStarted = floatJumped = floatKeepY = floatWasEnabled = false;
        }

        // ── Block rotation target ──────────────────────────────────────────────
        if (targetBlock != null) {
            Vec3 lookAt = new Vec3(
                    targetBlock.xCoord - lookVec.xCoord,
                    targetBlock.yCoord - lookVec.yCoord,
                    targetBlock.zCoord - lookVec.zCoord);
            blockRotations = RotationUtils.getRotations(lookAt);
            targetBlock    = null;
        }

        // ── Apply rotation mode ────────────────────────────────────────────────
        switch ((int) rotation.getInput()) {
            case 1: // Simple
                e.setRotations(mc.thePlayer.rotationYaw - hardcodedYaw(), 81.150F);
                break;

            case 2: // Offset A
            case 3: { // Offset B (same structure, different pitch target)
                boolean isOffsetB = (int)rotation.getInput() == 3;
                float moveAngle    = (float) getMovementAngle();
                float relativeYaw  = mc.thePlayer.rotationYaw + moveAngle;
                float normalizedYaw = (relativeYaw % 360 + 360) % 360;
                float quad         = normalizedYaw % 90;
                float side         = MathHelper.wrapAngleTo180_float(getMotionYaw() - yaw);
                float offset       = yawAngle;
                float yawBackwards = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw) - hardcodedYaw();
                float blockYawOffset = MathHelper.wrapAngleTo180_float(yawBackwards - blockYaw);
                int   quadVal = 0;
                float targetPitch   = isOffsetB ? 78.650f : 85.0f;

                if (quad <= 5 || quad >= 85) {
                    yawAngle = 123.50f; minOffset = 16; quadVal = 1;
                } else {
                    if      (quad >= 80 || quad < 10) { yawAngle = 125.50f; minOffset = 14; quadVal = 2; }
                    else if (quad >= 65 || quad < 25) { yawAngle = 127.50f; minOffset = 10; quadVal = 3; }
                    else if (quad >= 55 || quad < 35) { yawAngle = 128.50f; minOffset = 9;  quadVal = 4; }
                    else if (quad >= 15 && quad < 45) {
                        yawAngle = 130.50f; minOffset = 8; quadVal = 5;
                        if (quad >= 38) { yawAngle = 132.50f; minOffset = 5; quadVal = 6; }
                        if (quad >= 42) { yawAngle = 138f;    minOffset = 2; quadVal = 7; }
                    } else {
                        yawAngle = 130.50f; minOffset = 8; quadVal = 5;
                        if (quad >= 45 && quad < 52) {
                            yawAngle = 132.50f; minOffset = 5; quadVal = 6;
                            if (quad < 48) { yawAngle = 138f; minOffset = 2; quadVal = 7; }
                        }
                    }
                }

                if (firstStroke > 0 && (System.currentTimeMillis() - firstStroke) > strokeDelay)
                    firstStroke = 0;

                if (blockRotations != null) {
                    blockYaw   = blockRotations[0];
                    pitch      = blockRotations[1];
                    yawOffset  = blockYawOffset;
                    if (!isOffsetB && pitch < 85.0f && Utils.getHorizontalSpeed() < 0.6)
                        pitch = 85.0f;
                    if (firstStroke == 0) strokeDelay = 300;
                } else {
                    firstStroke = System.currentTimeMillis();
                    yawOffset   = 0;
                    pitch       = targetPitch;
                    strokeDelay = 200;
                }
                minOffset = 0;

                if (!Utils.isMoving() || Utils.getHorizontalSpeed() == 0) {
                    e.setRotations(yaw, pitch); break;
                }

                float motionYaw = getMotionYaw();
                float lYaw = ((IAccessorEntityPlayerSP) mc.thePlayer).getLastReportedYaw();
                float newYaw = motionYaw - offset * Math.signum(MathHelper.wrapAngleTo180_float(motionYaw - yaw));
                yaw = applyGcd(lYaw + MathHelper.wrapAngleTo180_float(newYaw - lYaw));

                if (quadVal != 1) {
                    if (quad >= 0 && quad < 45F) {
                        if (firstStroke == 0) set2 = side < 0;
                        if (was452) firstStroke = System.currentTimeMillis();
                        was451 = true; was452 = false;
                    } else {
                        if (firstStroke == 0) set2 = side >= 0;
                        if (was451) firstStroke = System.currentTimeMillis();
                        was452 = true; was451 = false;
                    }
                }

                double minSwitch = (!Utils.scaffoldDiagonal(false)) ? 0 : 15;
                if (side >= 0) {
                    if (quadVal == 1) {
                        if (yawOffset <= -minSwitch && firstStroke == 0) { if (set2) firstStroke = System.currentTimeMillis(); set2 = false; }
                        else if (yawOffset >= minSwitch && firstStroke == 0) { if (!set2) firstStroke = System.currentTimeMillis(); set2 = true; }
                    }
                    if (set2) {
                        yawOffset = Math.max(yawOffset, -0f); yawOffset = Math.min(yawOffset, minOffset);
                        e.setRotations((yaw + offset * 2) - yawOffset, pitch); break;
                    }
                } else {
                    if (quadVal == 1) {
                        if (yawOffset >= minSwitch && firstStroke == 0) { if (set2) firstStroke = System.currentTimeMillis(); set2 = false; }
                        else if (yawOffset <= -minSwitch && firstStroke == 0) { if (!set2) firstStroke = System.currentTimeMillis(); set2 = true; }
                    }
                    if (set2) {
                        yawOffset = Math.min(yawOffset, 0f); yawOffset = Math.max(yawOffset, -minOffset);
                        e.setRotations((yaw - offset * 2) - yawOffset, pitch); break;
                    }
                }

                if (side >= 0) { yawOffset = Math.min(yawOffset, 0f); yawOffset = Math.max(yawOffset, -minOffset); }
                else           { yawOffset = Math.max(yawOffset, 0f); yawOffset = Math.min(yawOffset, minOffset);  }
                e.setRotations(yaw - yawOffset, pitch);
                set2 = false;
                break;
            }

            case 4: // Snap
                if (blockRotations != null)
                    e.setRotations(blockRotations[0], blockRotations[1]);
                else
                    e.setRotations(mc.thePlayer.rotationYaw - hardcodedYaw(), 81.150F);
                break;
        }

        // ── Jump-facing-forward ────────────────────────────────────────────────
        if (!mc.thePlayer.onGround) rotateForward = false;
        if (rotateForward && jumpFacingForward.isToggled()) {
            if (rotation.getInput() > 0) {
                if (!rotatingForward) { rotationDelay = 2; rotatingForward = true; }
                float forwardYaw = mc.thePlayer.rotationYaw - hardcodedYaw() - 180 - (float) Utils.randomizeInt(-5, 5);
                e.setYaw(forwardYaw);
                e.setPitch(10 - (float) Utils.randomizeDouble(1, 5));
            }
        } else {
            rotatingForward = false;
        }

        // ── Pitch clamp ────────────────────────────────────────────────────────
        if (e.getPitch() > 89.9F) e.setPitch(89.9F);
        lastYaw2 = mc.thePlayer.rotationYaw;
        if (rotationDelay > 0) --rotationDelay;
    }

    // ── Block placement (PreUpdateEvent) ──────────────────────────────────────

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!isEnabled) return;
        if (holdingBlocks() && setSlot()) {
            if (LongJump.stopModules) return;
            if (KillAura.target != null) return;

            hasSwapped = true;
            int mode = (int) fastScaffold.getInput();
            if (rotation.getInput() == 0 || rotationDelay == 0) {
                placeBlock(0, 0);
            }
            if (fastScaffoldKeepY) {
                ++keepYTicks;
                if ((int) mc.thePlayer.posY > (int) startYPos) {
                    switch (mode) {
                        case 1:
                            if (!firstKeepYPlace && keepYTicks == 8 || keepYTicks == 11)
                            { placeBlock(1, 0); firstKeepYPlace = true; } break;
                        case 2:
                            if (!firstKeepYPlace && keepYTicks == 8 || firstKeepYPlace && keepYTicks == 7)
                            { placeBlock(1, 0); firstKeepYPlace = true; } break;
                        case 3:
                            if (!firstKeepYPlace && keepYTicks == 7)
                            { placeBlock(1, 0); firstKeepYPlace = true; } break;
                        case 6:
                            if (!firstKeepYPlace && keepYTicks == 3) firstKeepYPlace = true;
                            // fall through
                        case 7:
                            if (!firstKeepYPlace && keepYTicks == 3)
                            { placeBlock(1, 0); firstKeepYPlace = true; } break;
                    }
                }
                if (mc.thePlayer.onGround) keepYTicks = 0;
                if ((int) mc.thePlayer.posY == (int) startYPos) firstKeepYPlace = false;
            }
            handleMotion();
        }

        // ── Disable cleanup ────────────────────────────────────────────────────
        if (disabledModule) {
            if (hasPlaced && (towerEdge || floatStarted && Utils.isMoving())) dontDisable = true;
            if (dontDisable && ++disableTicks >= 2) isEnabled = false;
            if (!dontDisable) isEnabled = false;
            if (!isEnabled) {
                disabledModule = dontDisable = false;
                disableTicks = 0;
                if (lastSlot.get() != -1) {
                    mc.thePlayer.inventory.currentItem = lastSlot.get();
                    lastSlot.set(-1);
                }
                blockSlot = -1;
                hasSwapped = hasPlaced = false;
                targetBlock = null; blockInfo = null; blockRotations = null;
                fastScaffoldKeepY = firstKeepYPlace = rotateForward = rotatingForward =
                        lowhop = floatStarted = floatJumped = floatWasEnabled = towerEdge =
                        was451 = was452 = false;
                rotationDelay = keepYTicks = scaffoldTicks = 0;
                firstStroke = 0;
                startYPos = -1;
                lookVec = null;
            }
        }
    }

    // ── Knockback cancel ───────────────────────────────────────────────────────

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (!isEnabled || !Utils.nullCheck() || !cancelKnockBack.isToggled()) return;
        if (e.getPacket() instanceof S12PacketEntityVelocity) {
            if (((S12PacketEntityVelocity) e.getPacket()).getEntityID() == mc.thePlayer.getEntityId())
                e.setCanceled(true);
        } else if (e.getPacket() instanceof S27PacketExplosion) {
            e.setCanceled(true);
        }
    }

    // ── Block counter HUD ──────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled || !showBlockCount.isToggled()) return;
        if (!Utils.nullCheck()) return;
        int count = totalBlocks();
        ScaledResolution sr = new ScaledResolution(mc);
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        String text = count + " block" + (count != 1 ? "s" : "") + " left";
        int color = count > 0 ? 0xCCFFFFFF : 0xCCFF5555;
        mc.fontRendererObj.drawString(text,
                sr.getScaledWidth() / 2f + mc.fontRendererObj.FONT_HEIGHT * 1.5f,
                sr.getScaledHeight() / 2f - mc.fontRendererObj.FONT_HEIGHT / 2f + 1f,
                color, true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @Override
    public String getInfo() {
        if (fastOnRMB.isToggled())
            return Mouse.isButtonDown(1) && Utils.tabbedIn()
                    ? getModeName((int) fastScaffold.getInput())
                    : sprintModes[(int) sprint.getInput()];
        return fastScaffold.getInput() > 0
                ? getModeName((int) fastScaffold.getInput())
                : sprintModes[(int) sprint.getInput()];
    }

    private String getModeName(int i) {
        String m = fastScaffoldModes[i];
        if (m.startsWith("Jump") || m.startsWith("Keep-Y")) return m.split(" ")[0];
        return m;
    }

    public boolean stopFastPlace() { return isEnabled(); }

    public void rotateForward() { rotateForward = true; rotatingForward = false; }

    public boolean sprint() {
        if (!isEnabled) return false;
        if (sendPacket.isToggled()) return handleFastScaffolds() > 0 || !holdingBlocks();
        mc.thePlayer.setSprinting(true);
        return true;
    }

    private int handleFastScaffolds() {
        return fastOnRMB.isToggled()
                ? (Mouse.isButtonDown(1) && Utils.tabbedIn() ? (int) fastScaffold.getInput() : (int) sprint.getInput())
                : (fastScaffold.getInput() > 0 ? (int) fastScaffold.getInput() : (int) sprint.getInput());
    }

    private boolean usingFastScaffold() {
        return fastScaffold.getInput() > 0
                && (!fastOnRMB.isToggled() || Mouse.isButtonDown(1) && Utils.tabbedIn())
                && !(sprintScaffoldOnSpeed.isToggled()
                        && (Utils.getSpeedAmplifier() == 1 || Utils.getSpeedAmplifier() == 2));
    }

    public boolean safewalk() { return isEnabled() && safeWalk.isToggled(); }
    public boolean stopRotation() { return isEnabled() && rotation.getInput() > 0; }

    // ── Placement ─────────────────────────────────────────────────────────────

    private void place(PlaceData block) {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)
                || !Utils.canBePlaced((ItemBlock) heldItem.getItem())) return;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                heldItem, block.blockPos, block.enumFacing, block.hitVec)) {
            if (silentSwing.isToggled())
                mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
            else {
                mc.thePlayer.swingItem();
                mc.getItemRenderer().resetEquippedProgress();
            }
            highlight.put(block.blockPos.offset(block.enumFacing), null);
            hasPlaced = true;
        }
    }

    private void placeBlock(int yOffset, int xOffset) {
        locateAndPlaceBlock(yOffset, xOffset);
        int input = (int) multiPlace.getInput();
        if (sprint.getInput() == 0 && mc.thePlayer.onGround) return;
        if (input >= 1) {
            locateAndPlaceBlock(yOffset, xOffset);
            if (input >= 2) locateAndPlaceBlock(yOffset, xOffset);
        }
    }

    private void locateAndPlaceBlock(int yOffset, int xOffset) {
        locateBlocks(yOffset, xOffset);
        if (blockInfo == null) return;
        place(blockInfo);
        blockInfo = null;
    }

    private void locateBlocks(int yOffset, int xOffset) {
        List<PlaceData> blocks = findBlocks(yOffset, xOffset);
        if (blocks == null) return;

        double sumX = 0, sumY = mc.thePlayer.onGround ? blocks.get(0).blockPos.getY() : 0, sumZ = 0;
        int index = 0;
        for (PlaceData pd : blocks) {
            if (index > 1 || (!Utils.isDiagonal(false) && index > 0 && mc.thePlayer.onGround)) break;
            sumX += pd.blockPos.getX();
            if (!mc.thePlayer.onGround) sumY += pd.blockPos.getY();
            sumZ += pd.blockPos.getZ();
            index++;
        }
        double avgX = sumX / index;
        double avgY = mc.thePlayer.onGround ? blocks.get(0).blockPos.getY() : sumY / index;
        double avgZ = sumZ / index;
        targetBlock = new Vec3(avgX, avgY, avgZ);

        PlaceData pd = blocks.get(0);
        int bx = pd.blockPos.getX(), by = pd.blockPos.getY(), bz = pd.blockPos.getZ();
        EnumFacing bf = pd.enumFacing;
        blockInfo = pd;

        double hitX = (bx + 0.5) + getCoord(bf.getOpposite(), "x") * 0.5;
        double hitY = (by + 0.5) + getCoord(bf.getOpposite(), "y") * 0.5;
        double hitZ = (bz + 0.5) + getCoord(bf.getOpposite(), "z") * 0.5;
        lookVec = new Vec3(
                0.5 + getCoord(bf.getOpposite(), "x") * 0.5,
                0.5 + getCoord(bf.getOpposite(), "y") * 0.5,
                0.5 + getCoord(bf.getOpposite(), "z") * 0.5);
        hitVec = new Vec3(hitX, hitY, hitZ);
        blockInfo.hitVec = hitVec;
    }

    private double getCoord(EnumFacing f, String axis) {
        switch (axis) {
            case "x": return f == EnumFacing.WEST ? -0.5 : f == EnumFacing.EAST  ? 0.5 : 0;
            case "y": return f == EnumFacing.DOWN ? -0.5 : f == EnumFacing.UP    ? 0.5 : 0;
            case "z": return f == EnumFacing.NORTH ? -0.5 : f == EnumFacing.SOUTH ? 0.5 : 0;
        }
        return 0;
    }

    private List<PlaceData> findBlocks(int yOffset, int xOffset) {
        List<PlaceData> list = new ArrayList<>();
        int x = (int) Math.floor(mc.thePlayer.posX + xOffset);
        int y = (int) Math.floor((startYPos != -1 ? startYPos : mc.thePlayer.posY) + yOffset);
        int z = (int) Math.floor(mc.thePlayer.posZ);

        if (!BlockUtils.replaceable(new BlockPos(x, y - 1, z))) return null;

        // direct adjacents
        for (EnumFacing f : EnumFacing.values()) {
            if (f != EnumFacing.UP && placeConditions(f, yOffset, xOffset)) {
                BlockPos off = new BlockPos(x, y - 1, z).offset(f);
                if (!BlockUtils.replaceable(off) && !BlockUtils.isInteractable(BlockUtils.getBlock(off)))
                    list.add(new PlaceData(off, f.getOpposite()));
            }
        }
        // second-level adjacents
        for (EnumFacing f : EnumFacing.values()) {
            if (f != EnumFacing.UP && placeConditions(f, yOffset, xOffset)) {
                BlockPos off = new BlockPos(x, y - 1, z).offset(f);
                if (BlockUtils.replaceable(off)) {
                    for (EnumFacing f2 : EnumFacing.values()) {
                        if (f2 != EnumFacing.UP && placeConditions(f2, yOffset, xOffset)) {
                            BlockPos off2 = off.offset(f2);
                            if (!BlockUtils.replaceable(off2) && !BlockUtils.isInteractable(BlockUtils.getBlock(off2)))
                                list.add(new PlaceData(off2, f2.getOpposite()));
                        }
                    }
                }
            }
        }
        // deeper down (when airborne)
        if (mc.thePlayer.motionY > -0.0784) {
            for (int dy = 2; dy <= 3; dy++) {
                for (EnumFacing f : EnumFacing.values()) {
                    if (f != EnumFacing.UP && placeConditions(f, yOffset, xOffset)) {
                        BlockPos off = new BlockPos(x, y - dy, z).offset(f);
                        if (BlockUtils.replaceable(off)) {
                            for (EnumFacing f2 : EnumFacing.values()) {
                                if (f2 != EnumFacing.UP && placeConditions(f2, yOffset, xOffset)) {
                                    BlockPos off2 = off.offset(f2);
                                    if (!BlockUtils.replaceable(off2) && !BlockUtils.isInteractable(BlockUtils.getBlock(off2)))
                                        list.add(new PlaceData(off2, f2.getOpposite()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    private boolean placeConditions(EnumFacing f, int yCondition, int xCondition) {
        if (xCondition == -1) return f == EnumFacing.EAST;
        if (yCondition ==  1) return f == EnumFacing.DOWN;
        return true;
    }

    // ── Motion ────────────────────────────────────────────────────────────────

    private void handleMotion() {
        if (!mc.thePlayer.onGround) {
            mc.thePlayer.motionX *= 0.98;
            mc.thePlayer.motionZ *= 0.98;
        } else if (usingFastScaffold() || sprint.getInput() == 2) {
            mc.thePlayer.motionX *= fastScaffoldMotion.getInput();
            mc.thePlayer.motionZ *= fastScaffoldMotion.getInput();
        } else {
            mc.thePlayer.motionX *= sprintScaffoldMotion.getInput();
            mc.thePlayer.motionZ *= sprintScaffoldMotion.getInput();
        }
    }

    // ── Slot management ───────────────────────────────────────────────────────

    public boolean holdingBlocks() {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (!autoSwap.isToggled() || getSlot() == -1) {
            if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)
                    || !Utils.canBePlaced((ItemBlock) heldItem.getItem()))
                return false;
        }
        return true;
    }

    private int getSlot() {
        int slot = -1, highestStack = -1;
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[i];
            if (s == null || !(s.getItem() instanceof ItemBlock)
                    || !Utils.canBePlaced((ItemBlock) s.getItem()) || s.stackSize <= 0) continue;
            if (Utils.getBedwarsStatus() == 2 && ((ItemBlock) s.getItem()).getBlock() instanceof BlockTNT) continue;
            if (s.stackSize > highestStack) { highestStack = s.stackSize; slot = i; }
        }
        return slot;
    }

    public boolean setSlot() {
        int slot = getSlot();
        if (slot == -1) return false;
        if (blockSlot == -1) blockSlot = slot;
        if (lastSlot.get() == -1) lastSlot.set(mc.thePlayer.inventory.currentItem);
        if (autoSwap.isToggled() && blockSlot != -1)
            mc.thePlayer.inventory.currentItem = slot;
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)
                || !Utils.canBePlaced((ItemBlock) heldItem.getItem())) {
            blockSlot = -1;
            return false;
        }
        return true;
    }

    public boolean canSafewalk() {
        return !usingFastScaffold() && isEnabled;
    }

    public int totalBlocks() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[i];
            if (s != null && s.getItem() instanceof ItemBlock
                    && Utils.canBePlaced((ItemBlock) s.getItem()) && s.stackSize > 0)
                n += s.stackSize;
        }
        return n;
    }

    public boolean onPacketSent(C0BPacketEntityAction packet) {
        return packet.getAction() != C0BPacketEntityAction.Action.START_SPRINTING || sendPacket.isToggled();
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    float applyGcd(float value) {
        float gcd = 0.2F * 0.2F * 0.2F * 8.0F;
        return (float)((double)value - (double)value % ((double)gcd * 0.15D));
    }

    float getMotionYaw() {
        return MathHelper.wrapAngleTo180_float(
                (float)Math.toDegrees(Math.atan2(mc.thePlayer.motionZ, mc.thePlayer.motionX)) - 90f);
    }

    float getAngleDifference(float from, float to) {
        float d = (to - from) % 360f;
        if (d < -180f) d += 360f;
        else if (d >= 180f) d -= 360f;
        return d;
    }

    private int getSpeedLevel() {
        for (PotionEffect e : mc.thePlayer.getActivePotionEffects())
            if (e.getEffectName().equals("potion.moveSpeed")) return e.getAmplifier() + 1;
        return 0;
    }

    private double[] speedLevels      = {0.48, 0.5, 0.52, 0.58, 0.68};
    private double[] floatSpeedLevels = {0.2,  0.22, 0.28, 0.29, 0.3};

    double getSpeed(int lvl)      { return speedLevels[Math.max(0, Math.min(speedLevels.length-1, lvl))]; }
    double getFloatSpeed(int lvl) { return floatSpeedLevels[Math.max(0, Math.min(floatSpeedLevels.length-1, lvl))]; }

    public float hardcodedYaw() {
        float y = 0f;
        float f = 0.8f;
        if      (mc.thePlayer.moveForward >= f)  { y -= 180; if (mc.thePlayer.moveStrafing >= f) y += 45; if (mc.thePlayer.moveStrafing <= -f) y -= 45; }
        else if (mc.thePlayer.moveForward == 0)  { y -= 180; if (mc.thePlayer.moveStrafing >= f) y += 90; if (mc.thePlayer.moveStrafing <= -f) y -= 90; }
        else if (mc.thePlayer.moveForward <= -f) {           if (mc.thePlayer.moveStrafing >= f) y -= 45; if (mc.thePlayer.moveStrafing <= -f) y += 45; }
        return y;
    }

    private double getMovementAngle() {
        double a = Math.toDegrees(Math.atan2(-mc.thePlayer.moveStrafing, mc.thePlayer.moveForward));
        return a == -0 ? 0 : a;
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    static class PlaceData {
        EnumFacing enumFacing;
        BlockPos   blockPos;
        Vec3       hitVec;
        PlaceData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos; this.enumFacing = enumFacing;
        }
    }
}
