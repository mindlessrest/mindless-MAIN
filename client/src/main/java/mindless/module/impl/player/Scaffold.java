package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.movement.LongJump;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.ScaffoldUtils;
import mindless.utility.SlotManager;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Yuri Scaffold port. Mindless event and packet APIs replace Yuri managers. */
public class Scaffold extends Module {
    private static final String[] MODES = {"Normal", "Telly", "Breezily", "God Bridge"};
    private static final String[] SPRINT = {"Vanilla", "Universal", "NCP", "Legit", "None"};
    private static final String[] SEARCH = {"Normal", "Ultra Safe", "Secondary"};
    private static final String[] RAYCAST = {"None", "Normal", "Strict"};
    private static final String[] SWAP = {"Client", "Server"};
    private static final String[] TOWER = {"None", "Vanilla", "Polar", "NCP"};

    private final SliderSetting mode = new SliderSetting("Mode", 0, MODES);
    private final SliderSetting sprintMode = new SliderSetting("Sprint Mode", 4, SPRINT);
    private final SliderSetting search = new SliderSetting("Search Algorithm", 0, SEARCH);
    private final SliderSetting raycast = new SliderSetting("Ray Cast", 1, RAYCAST);
    private final SliderSetting swap = new SliderSetting("Swap Mode", 0, SWAP);
    private final SliderSetting towerMode = new SliderSetting("Tower Mode", 0, TOWER);
    private final SliderSetting minRotation = new SliderSetting("Min Rotation Speed", 3, 0, 10, .5);
    private final SliderSetting maxRotation = new SliderSetting("Max Rotation Speed", 7, 0, 10, .5);
    private final SliderSetting placeDelay = new SliderSetting("Place Delay", 0, 0, 10, 1);
    private final SliderSetting expand = new SliderSetting("Expand", 0, 0, 4, 1);
    private final SliderSetting straightTicks = new SliderSetting("Telly Straight Ticks", 6, 0, 8, 1);
    private final SliderSetting diagonalTicks = new SliderSetting("Telly Diagonal Ticks", 4, 0, 8, 1);
    private final SliderSetting jumpDownTicks = new SliderSetting("Telly Jump Down Ticks", 1, 0, 8, 1);
    public final ButtonSetting safeWalk = new ButtonSetting("Safe Walk", false);
    public final ButtonSetting autoSwap = new ButtonSetting("Auto Swap", true);
    public final ButtonSetting autoJump = new ButtonSetting("Auto Jump", false);
    public final ButtonSetting keepY = new ButtonSetting("Keep Y", false);
    public final ButtonSetting showBlockCount = new ButtonSetting("Show Block Count", true);
    private final ButtonSetting sneak = new ButtonSetting("Sneak", false);
    private final ButtonSetting swing = new ButtonSetting("Swing", true);
    private final ButtonSetting moveFix = new ButtonSetting("Move Fix", true);
    private final ButtonSetting towerMove = new ButtonSetting("Tower Move", true);
    public final ButtonSetting sendPacket = new ButtonSetting("Send Packet", true);

    public final Map<BlockPos, mindless.utility.Timer> highlight = new HashMap<>();
    public final AtomicInteger lastSlot = new AtomicInteger(-1);
    public boolean hasSwapped, moduleEnabled, isEnabled, canBlockFade, fastScaffoldKeepY;
    private boolean active, queued;
    private BlockPos support;
    private EnumFacing face;
    private Vec3 hit;
    private float targetYaw, targetPitch;
    private int startY, airTicks, offGroundTicks, onGroundTicks, placed;
    private long lastPlaceTick;

    public Scaffold() {
        super("Scaffold", category.player);
        registerSetting(mode); registerSetting(sprintMode); registerSetting(search); registerSetting(raycast);
        registerSetting(swap); registerSetting(towerMode); registerSetting(minRotation); registerSetting(maxRotation);
        registerSetting(placeDelay); registerSetting(expand); registerSetting(straightTicks);
        registerSetting(diagonalTicks); registerSetting(jumpDownTicks); registerSetting(safeWalk);
        registerSetting(autoSwap); registerSetting(autoJump); registerSetting(keepY); registerSetting(showBlockCount);
        registerSetting(sneak); registerSetting(swing); registerSetting(moveFix); registerSetting(towerMove); registerSetting(sendPacket);
        alwaysOn = true;
    }

    @Override public void onEnable() {
        moduleEnabled = isEnabled = true; startY = mc.thePlayer == null ? 0 : MathHelper.floor_double(mc.thePlayer.posY);
        active = queued = false; placed = airTicks = offGroundTicks = onGroundTicks = 0; lastPlaceTick = 0;
    }

    @Override public void onDisable() {
        moduleEnabled = isEnabled = false; active = queued = false; SlotManager.swapBack();
        lastSlot.set(-1); placed = airTicks = 0; fastScaffoldKeepY = false;
        if (mc.thePlayer != null) {
            mc.thePlayer.setSprinting(false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    public String getInfo() { return MODES[(int) mode.getInput()]; }
    public boolean safewalk() { return isEnabled && safeWalk.isToggled() && mc.thePlayer != null && mc.thePlayer.onGround; }
    public boolean canSafewalk() { return isEnabled && safeWalk.isToggled(); }
    public boolean sprint() { return !isEnabled || (sendPacket.isToggled() && !holdingBlocks()); }
    public boolean stopRotation() { return active; }
    public boolean stopFastPlace() { return isEnabled; }
    public void rotateForward() {}
    public boolean onPacketSent(C0BPacketEntityAction p) { return p.getAction() != C0BPacketEntityAction.Action.START_SPRINTING || sendPacket.isToggled(); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (active) { KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false); KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false); if (e.isCancelable()) e.setCanceled(true); }
    }

    @SubscribeEvent public void onInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || !moduleEnabled) return;
        if ((autoJump.isToggled() || mode.getInput() == 1) && mc.thePlayer.onGround && Utils.isMoving() && holdingBlocks()) e.setJump(true);
        if (sneak.isToggled()) e.setSneak(true);
    }

    @SubscribeEvent public void onRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck() || !moduleEnabled || mc.currentScreen != null) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;
        int slot = blockSlot();
        if (slot < 0 || LongJump.stopModules || KillAura.target != null) { active = false; return; }
        if (autoSwap.isToggled()) SlotManager.swap(slot, (int) swap.getInput() == 1);
        updateGroundTicks();
        applySprint(); applyTower();
        BlockPos feet = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX), MathHelper.floor_double(mc.thePlayer.posY) - 1, MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtils.replaceable(feet) && mc.thePlayer.onGround) { active = false; return; }
        float yaw = e.yaw == null ? RotationUtils.serverRotations[0] : e.yaw;
        float pitch = e.pitch == null ? RotationUtils.serverRotations[1] : e.pitch;
        Target target = findTarget(yaw, pitch);
        if (target == null) { active = false; return; }
        active = hasSwapped = true; moduleEnabled = isEnabled = true; support = target.support; face = target.face;
        targetYaw = target.yaw; targetPitch = target.pitch;
        if (mode.getInput() == 1 && mc.thePlayer.onGround && Utils.isMoving()) { targetYaw = mc.thePlayer.rotationYaw; targetPitch = 68 + (float)(Math.random() * 22); }
        if (mode.getInput() == 2) { targetYaw = breezilyYaw(); targetPitch = 80; }
        float speed = (float)(minRotation.getInput() + Math.random() * (maxRotation.getInput() - minRotation.getInput()));
        float[] smooth = RotationUtils.smoothRotation(yaw, pitch, targetYaw, targetPitch, Math.max(0, (int)speed), 0);
        e.setYaw(smooth[0]); e.setPitch(smooth[1]);
        MovingObjectPosition mop = RotationUtils.rayCastBlock(mc.playerController.getBlockReachDistance(), smooth[0], smooth[1]);
        if (mop != null && mop.getBlockPos().equals(support) && mop.sideHit == face && canPlace()) { hit = mop.hitVec; queued = true; }
    }

    @SubscribeEvent public void onUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck() || !queued) return; queued = false;
        if (mc.thePlayer.ticksExisted - lastPlaceTick < (long) placeDelay.getInput()) return;
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(mc.thePlayer.inventory.currentItem);
        if (!isBlock(stack) || support == null || face == null || hit == null) return;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, support, face, hit)) {
            lastPlaceTick = mc.thePlayer.ticksExisted; placed++; highlight.put(support.offset(face), null);
            if (swing.isToggled()) mc.thePlayer.swingItem(); else mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
        }
    }

    private Target findTarget(float yaw, float pitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1); BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
        float best = Float.MAX_VALUE; Target result = null; int radius = 1 + (int)expand.getInput();
        for (int x = feet.getX()-radius; x <= feet.getX()+radius; x++) for (int z = feet.getZ()-radius; z <= feet.getZ()+radius; z++) for (int y = feet.getY(); y >= feet.getY()-1; y--) {
            BlockPos p = new BlockPos(x,y,z); if (BlockUtils.replaceable(p) || BlockUtils.isInteractable(BlockUtils.getBlock(p))) continue;
            for (EnumFacing f : EnumFacing.values()) { BlockPos placedPos = p.offset(f); if (!BlockUtils.replaceable(placedPos) || f == EnumFacing.DOWN) continue;
                float[] r = ScaffoldUtils.computeRotations(p, f, yaw, pitch, mc.playerController.getBlockReachDistance(), (int)search.getInput(), (int)raycast.getInput()==2); if (r == null) continue;
                if (keepY.isToggled() && placedPos.getY() >= startY) continue; float cost = Math.abs(MathHelper.wrapAngleTo180_float(r[0]-yaw))+Math.abs(r[1]-pitch); if (cost < best) { best=cost; result=new Target(p,f,r[0],r[1]); }
            }
        } return result;
    }

    private boolean canPlace() { return mc.thePlayer.ticksExisted - lastPlaceTick >= (long)placeDelay.getInput() && !(mode.getInput()==1 && offGroundTicks < tellyTicks()); }
    private int tellyTicks() { return isDiagonal() ? (int)diagonalTicks.getInput() : (int)straightTicks.getInput(); }
    private boolean isDiagonal() { float d=mc.thePlayer.rotationYaw%90; if(d<0)d+=90; return d>20&&d<70; }
    private float breezilyYaw() { return Math.round(mc.thePlayer.rotationYaw/90f)*90f + 35f; }
    private void updateGroundTicks() { if(mc.thePlayer.onGround){onGroundTicks++;offGroundTicks=0;}else{offGroundTicks++;onGroundTicks=0;} }
    private void applySprint() { int m=(int)sprintMode.getInput(); if(m==4||m==2){mc.thePlayer.setSprinting(false); if(m==4)KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),false);} else if(m==0)mc.thePlayer.setSprinting(Utils.isMoving()); }
    private void applyTower() { if((int)towerMode.getInput()==0||!mc.gameSettings.keyBindJump.isKeyDown()||(!towerMove.isToggled()&&!Utils.isMoving()))return; if(towerMode.getInput()==1)mc.thePlayer.motionY=.42; else if(towerMode.getInput()==3&&mc.thePlayer.posY%1<.0016){mc.thePlayer.setPosition(mc.thePlayer.posX,Math.floor(mc.thePlayer.posY),mc.thePlayer.posZ);mc.thePlayer.motionY=.41998;}}
    private int blockSlot(){int best=-1,size=0;for(int i=0;i<9;i++){ItemStack s=mc.thePlayer.inventory.getStackInSlot(i);if(isBlock(s)&&s.stackSize>size){best=i;size=s.stackSize;}}return best;}
    public boolean holdingBlocks(){return blockSlot()>=0;}
    public int totalBlocks(){int n=0;for(int i=0;i<9;i++){ItemStack s=mc.thePlayer.inventory.getStackInSlot(i);if(isBlock(s))n+=s.stackSize;}return n;}
    private static boolean isBlock(ItemStack s){return s!=null&&s.stackSize>0&&s.getItem() instanceof ItemBlock&&Utils.canBePlaced((ItemBlock)s.getItem());}
    private static final class Target { final BlockPos support; final EnumFacing face; final float yaw,pitch; Target(BlockPos s,EnumFacing f,float y,float p){support=s;face=f;yaw=y;pitch=p;} }
}
