package mindless.module.impl.movement;

import mindless.clickgui.ClickGui;
import mindless.event.JumpEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.concurrent.ConcurrentLinkedQueue;

public class InvMove extends Module {
    private static final int INVENTORY_MODE_LEGIT = 1;
    private static final int INVENTORY_MODE_LEGIT_SLOW = 2;
    private static final int INVENTORY_MODE_BLINK = 3;
    private static final int INVENTORY_MODE_CLOSE = 4;
    private static final int LEGIT_RELEASE_TICKS = 1;
    private static final int LEGIT_SLOW_RELEASE_TICKS = 5;

    public SliderSetting inventory;
    private SliderSetting chestAndOthers;
    private SliderSetting motion;
    private ButtonSetting modifyMotionPost;
    private ButtonSetting slowWhenNecessary;
    private ButtonSetting allowJumping;
    private ButtonSetting allowSprinting;
    private ButtonSetting allowRotating;

    public int ticks;
    public boolean setMotion;
    private int movementReleaseTicks;
    private int legitCloseReleaseTicks;
    private boolean legitInventorySession;
    private boolean restoreMovementAfterLegitClose;
    private Packet legitClosePacket;

    private final ConcurrentLinkedQueue<Packet> blinkedPackets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Packet> legitPackets = new ConcurrentLinkedQueue<>();

    private final String[] INVENTORY_MODES = new String[] { "Vanilla", "Legit", "Legit (Slow)", "Blink", "Close" };
    private final String[] CHEST_AND_OTHER_MODES = new String[] { "Vanilla", "Blink" };

    public InvMove() {
        super("InvMove", category.movement);
        this.registerSetting(inventory = new SliderSetting("Inventory", true, 0, INVENTORY_MODES));
        this.registerSetting(chestAndOthers = new SliderSetting("Chest & others", true, 0, CHEST_AND_OTHER_MODES));
        this.registerSetting(motion = new SliderSetting("Motion", "x", 1, 0.05, 1, 0.01));
        this.registerSetting(modifyMotionPost = new ButtonSetting("Modify motion after click", false));
        this.registerSetting(slowWhenNecessary = new ButtonSetting("Slow motion when necessary", false));
        this.registerSetting(allowJumping = new ButtonSetting("Allow jumping", true));
        this.registerSetting(allowRotating = new ButtonSetting("Allow rotating", true));
        this.registerSetting(allowSprinting = new ButtonSetting("Allow sprinting", true));
    }

    @Override
    public void onDisable() {
        reset();
        releasePackets();
        releaseLegitPackets();
        releaseLegitClosePacket();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!guiCheck()) {
            reset();
            return;
        }
        if (!(mc.currentScreen instanceof ClickGui)) {
            if (setMotion) {
                if (++ticks == 10) {
                    ticks = 0;
                    setMotion = false;
                }
            }

            if (setMotion && (motion.getInput() != 1 || (slowWhenNecessary.isToggled()))) {
                final int speedAmplifier = Utils.getSpeedAmplifier();
                double slowedMotion = 0.65;
                switch (speedAmplifier) {
                    case 1:
                        slowedMotion = 0.615;
                        break;
                    case 2:
                        slowedMotion = 0.3;
                        break;
                }
                Utils.setSpeed(Utils.getHorizontalSpeed() * (slowWhenNecessary.isToggled() ? slowedMotion : motion.getInput()));
            }
        }
        else {
            reset();
        }

        releaseLegitPackets();
        boolean releasedClose = false;
        if (legitClosePacket != null) {
            if (legitCloseReleaseTicks > 0) {
                --legitCloseReleaseTicks;
            } else {
                releasedClose = releaseLegitClosePacket();
            }
        }
        if (releasedClose && restoreMovementAfterLegitClose) {
            restoreMovementAfterLegitClose = false;
            if (mc.currentScreen == null) {
                restoreMovementKeys();
            }
        }

        boolean releaseMovement = shouldReleaseMovement();
        if (releaseMovement) {
            releaseMovementKeys();
            --movementReleaseTicks;
        }
        else {
            restoreMovementKeys();
        }
        boolean foodLvlMet = (float)mc.thePlayer.getFoodStats().getFoodLevel() > 6.0F || mc.thePlayer.capabilities.allowFlying;
        if (!releaseMovement && ((Utils.isBindDown(mc.gameSettings.keyBindSprint) || ModuleManager.sprint.isEnabled()) && mc.thePlayer.movementInput.moveForward >= 0.8F && foodLvlMet && !mc.thePlayer.isSprinting()) && allowSprinting.isToggled()) {
            mc.thePlayer.setSprinting(true);
        }
        if (releaseMovement || !allowSprinting.isToggled()) {
            mc.thePlayer.setSprinting(false);
        }
        if (allowRotating.isToggled()) {
            if (Keyboard.isKeyDown(208) && mc.thePlayer.rotationPitch < 90.0F) {
                mc.thePlayer.rotationPitch += 6.0F;
            }
            if (Keyboard.isKeyDown(200) && mc.thePlayer.rotationPitch > -90.0F) {
                mc.thePlayer.rotationPitch -= 6.0F;
            }
            if (Keyboard.isKeyDown(205)) {
                mc.thePlayer.rotationYaw += 6.0F;
            }
            if (Keyboard.isKeyDown(203)) {
                mc.thePlayer.rotationYaw -= 6.0F;
            }
        }
    }

    @SubscribeEvent
    public void onJump(JumpEvent e) {
        if (!allowJumping.isToggled() && mc.currentScreen != null && !(mc.currentScreen instanceof ClickGui)) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C0EPacketClickWindow) {
            boolean inventoryManagerClick = ModuleManager.invManager != null && ModuleManager.invManager.isSendingInventoryClick();
            if (isLegitInventoryMode() && mc.currentScreen instanceof GuiInventory) {
                legitInventorySession = true;
                movementReleaseTicks = Math.max(movementReleaseTicks, getMovementReleaseTicks());
                releaseMovementKeys();
                legitPackets.add(e.getPacket());
                e.setCanceled(true);
            }
            if (modifyMotionPost.isToggled() || (slowWhenNecessary.isToggled() && !canBlink())) {
                setMotion = true;
                ticks = 0;
            }
            if (!inventoryManagerClick && canBlink()) {
                blinkedPackets.add(e.getPacket());
                e.setCanceled(true);
            }
        }
        else if (e.getPacket() instanceof C0DPacketCloseWindow) {
            boolean pendingLegitClick = !legitPackets.isEmpty();
            boolean legitInventoryClose = isLegitInventoryMode() && (mc.currentScreen instanceof GuiInventory || legitInventorySession);
            if (legitInventoryClose || pendingLegitClick || legitClosePacket != null) {
                if (pendingLegitClick) {
                    movementReleaseTicks = Math.max(movementReleaseTicks, getMovementReleaseTicks());
                    releaseMovementKeys();
                } else {
                    releaseCloseActionKeys();
                }
                restoreMovementAfterLegitClose = true;
                if (legitClosePacket == null) {
                    legitClosePacket = e.getPacket();
                    legitCloseReleaseTicks = pendingLegitClick ? 1 : 0;
                }
                e.setCanceled(true);
            } else {
                legitInventorySession = false;
                if (canBlink()) {
                    if (inventory.getInput() == INVENTORY_MODE_CLOSE) {
                        PacketUtils.sendPacketNoEvent(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
                    }
                    releasePackets();
                }
            }
        }
    }

    private void reset() {
        ticks = 0;
        setMotion = false;
        movementReleaseTicks = 0;
    }

    private boolean guiCheck() {
        if (mc.currentScreen == null) {
            return false;
        }
        if (Settings.inInventory()) {
            if (inventory.getInput() == -1) {
                return false;
            }
        }
        else if ((chestAndOthers.getInput() == -1 && !(mc.currentScreen instanceof ClickGui)) || (mc.currentScreen instanceof GuiChat)) {
            return false;
        }
        return true;
    }

    private boolean canBlink() {
        if (mc.currentScreen == null && inventory.getInput() != INVENTORY_MODE_CLOSE) {
            return false;
        }
        else if ((mc.currentScreen instanceof GuiInventory && inventory.getInput() == INVENTORY_MODE_BLINK) || (inventory.getInput() == INVENTORY_MODE_CLOSE && mc.currentScreen == null)) {
            return true;
        }
        else if (chestAndOthers.getInput() == 1 && !(mc.currentScreen instanceof ClickGui) && !(mc.currentScreen instanceof GuiChat)) {
            return true;
        }
        return false;
    }

    private boolean isLegitInventoryMode() {
        return inventory.getInput() == INVENTORY_MODE_LEGIT || inventory.getInput() == INVENTORY_MODE_LEGIT_SLOW;
    }

    private int getMovementReleaseTicks() {
        return inventory.getInput() == INVENTORY_MODE_LEGIT_SLOW ? LEGIT_SLOW_RELEASE_TICKS : LEGIT_RELEASE_TICKS;
    }

    private boolean shouldReleaseMovement() {
        return mc.currentScreen instanceof GuiInventory
            && isLegitInventoryMode()
            && (movementReleaseTicks > 0 || mc.thePlayer.inventory.getItemStack() != null);
    }

    private void restoreMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindForward));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindBack));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindRight));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindLeft));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), Utils.jumpDown());
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindSneak));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindSprint));
    }

    private void releaseMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            mc.thePlayer.movementInput.jump = false;
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.setSprinting(false);
        }
    }

    private void releaseCloseActionKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindForward));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindBack));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindRight));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindLeft));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), Utils.jumpDown());
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.setSprinting(false);
        }
    }

    private void releasePackets() {
        synchronized (blinkedPackets) {
            for (Packet packet : blinkedPackets) {
                PacketUtils.sendPacketNoEvent(packet);
            }
        }
        blinkedPackets.clear();
    }

    private void releaseLegitPackets() {
        Packet packet;
        while ((packet = legitPackets.poll()) != null) {
            PacketUtils.sendPacketNoEvent(packet);
        }
    }

    private boolean releaseLegitClosePacket() {
        Packet packet = legitClosePacket;
        if (packet == null) {
            return false;
        }
        legitClosePacket = null;
        legitCloseReleaseTicks = 0;
        legitInventorySession = false;
        PacketUtils.sendPacketNoEvent(packet);
        return true;
    }
}
