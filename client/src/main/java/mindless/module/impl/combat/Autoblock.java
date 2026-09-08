package mindless.module.impl.combat;

import mindless.event.PostUpdateEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.Random;

/**
 * Expo-style staged sword blocking, adapted to Mindless's Forge/JVMTI event flow.
 *
 * Expo's implementation is not a simple "block every tick" module. Its lag modes use a
 * release/attack/restore sequence, its PRE variants restore after the player's update, and its
 * APS setting controls the gap before the next block. Keeping those decisions in one state
 * machine avoids the standalone module and KillAura's legacy fallback fighting each other.
 */
public class Autoblock extends Module {
    private static final String[] MODES = new String[] {
            "Lag new", "Lag new pre", "Lag", "Lag pre", "Lag legit",
            "Lag legit pre", "Legit", "Vanilla", "None"
    };
    private static final int MODE_LAG_NEW = 0;
    private static final int MODE_LAG_NEW_PRE = 1;
    private static final int MODE_LAG = 2;
    private static final int MODE_LAG_PRE = 3;
    private static final int MODE_LAG_LEGIT = 4;
    private static final int MODE_LAG_LEGIT_PRE = 5;
    private static final int MODE_LEGIT = 6;
    private static final int MODE_VANILLA = 7;
    private static final int MODE_NONE = 8;

    private static final String[] APS_MODES = new String[] { "3 APS", "5 APS", "7 APS", "10 APS", "14 APS" };

    private final SliderSetting mode;
    private final SliderSetting apsMode;
    private final mindless.utility.combat.EntityTargets targets;

    private final GroupSetting conditionGroup;
    private final ButtonSetting requireLeftClick;
    private final ButtonSetting manualLeftClick;
    private final ButtonSetting requireRightClick;
    private final ButtonSetting requireKillAura;

    private final GroupSetting noSlowGroup;
    private final ButtonSetting allowNoSlow;
    private final ButtonSetting onlyUnblockWithoutNoSlow;
    private final ButtonSetting disableNoSlowInRange;
    private final SliderSetting noSlowDisableRange;

    private final GroupSetting unblockGroup;
    private final ButtonSetting smartUnblock;
    private final SliderSetting smartUnblockChance;
    private final SliderSetting smartUnblockTicks;

    private final ButtonSetting visualBlocking;
    private final SliderSetting cooldown;

    private final Random random = new Random();
    private boolean blocking;
    private boolean reblockPending;
    private boolean injecting;
    private boolean useKeyHeld;
    private int reblockDelayTicks;
    private volatile int hurtUnblockTicks;
    private long lastBlockEndMs;
    private EntityLivingBase target;

    public Autoblock() {
        super("Autoblock", "Blocks with your sword without giving up the swing.", category.combat, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_LAG_NEW, MODES));
        this.registerSetting(apsMode = new SliderSetting("APS mode", 3, APS_MODES));

        this.registerSetting(conditionGroup = new GroupSetting("Conditions"));
        this.registerSetting(requireLeftClick = new ButtonSetting(conditionGroup, "Require left click", true));
        this.registerSetting(manualLeftClick = new ButtonSetting(conditionGroup, "Manual left click", false));
        this.registerSetting(requireRightClick = new ButtonSetting(conditionGroup, "Require right click", false));
        this.registerSetting(requireKillAura = new ButtonSetting(conditionGroup, "Require KillAura", true));

        this.registerSetting(noSlowGroup = new GroupSetting("No Slow"));
        this.registerSetting(allowNoSlow = new ButtonSetting(noSlowGroup, "Allow No Slow", true));
        this.registerSetting(onlyUnblockWithoutNoSlow = new ButtonSetting(noSlowGroup, "Only unblock without No Slow", true));
        this.registerSetting(disableNoSlowInRange = new ButtonSetting(noSlowGroup, "Disable No Slow in range", true));
        this.registerSetting(noSlowDisableRange = new SliderSetting(noSlowGroup, "No Slow disable range", " block", 3.5, 0.0, 8.0, 0.05));

        this.targets = new mindless.utility.combat.EntityTargets(this, "Targets", 5.0, 1.0, 8.0);

        this.registerSetting(unblockGroup = new GroupSetting("Smart unblock"));
        this.registerSetting(smartUnblock = new ButtonSetting(unblockGroup, "Smart unblock", false));
        this.registerSetting(smartUnblockChance = new SliderSetting(unblockGroup, "Unblock chance", "%", 100.0, 0.0, 100.0, 5.0));
        this.registerSetting(smartUnblockTicks = new SliderSetting(unblockGroup, "Unblock ticks", 8.0, 0.0, 15.0, 1.0));

        this.registerSetting(visualBlocking = new ButtonSetting("Visual blocking", true));
        this.registerSetting(cooldown = new SliderSetting("Cooldown", "ms", 0.0, 0.0, 500.0, 25.0));
    }

    @Override
    public void guiUpdate() {
        int current = currentMode();
        boolean packetMode = current != MODE_LEGIT && current != MODE_NONE;
        apsMode.setVisible(packetMode, this);
        smartUnblock.setVisible(packetMode, this);
        boolean smart = packetMode && smartUnblock.isToggled();
        smartUnblockChance.setVisible(smart, this);
        smartUnblockTicks.setVisible(smart, this);
        visualBlocking.setVisible(packetMode, this);
    }

    @Override
    public String getInfo() {
        return MODES[Math.max(0, Math.min(MODES.length - 1, currentMode()))];
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        stopBlocking(true);
        resetState();
    }

    public boolean isActive() {
        return isEnabled() && blocking;
    }

    /** Whether this module, rather than KillAura's small legacy fallback, owns blocking. */
    public boolean isOperational() {
        return isEnabled() && currentMode() != MODE_NONE;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            stopBlocking(true);
            target = null;
            return;
        }

        target = targets.findNearest();
        int current = currentMode();

        if (current == MODE_LEGIT) {
            if (blocking) stopBlocking(false);
            boolean wanted = shouldBlock();
            setUseKey(wanted);
            blocking = wanted && Utils.holdingSword();
            ReflectionUtils.setItemInUse(blocking);
            return;
        }

        // Changing away from Legit must end its real held-key block before packet mode takes
        // ownership; otherwise the first packet-mode tick inherits a stale blocking=true.
        if (useKeyHeld) stopBlocking(true);

        if (hurtUnblockTicks > 0) {
            hurtUnblockTicks--;
            if (blocking) stopBlocking(false);
            return;
        }

        boolean wanted = current != MODE_NONE && shouldBlock();
        if (!wanted) {
            stopBlocking(false);
            return;
        }

        if (reblockPending && reblockDelayTicks > 0) {
            reblockDelayTicks--;
        }
        if ((!blocking && !reblockPending || reblockPending && reblockDelayTicks <= 0)
                && !isCoolingDown()) {
            sendBlock();
        }
        syncVisualBlocking();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        if (!Utils.nullCheck() || !reblockPending || !isPreMode() || hurtUnblockTicks > 0) {
            return;
        }
        if (shouldBlock() && !isCoolingDown()) {
            reblockDelayTicks = 0;
            sendBlock();
            syncVisualBlocking();
        }
    }

    /** Expo's smart-unblock is a reaction to the local player's hurt status, not every swing. */
    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!smartUnblock.isToggled() || !(event.getPacket() instanceof S19PacketEntityStatus)
                || !Utils.nullCheck()) {
            return;
        }
        S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
        Entity entity = status.getEntity(mc.theWorld);
        if (entity instanceof EntityPlayerSP && status.getOpCode() == 2
                && random.nextDouble() * 100.0 < smartUnblockChance.getInput()) {
            hurtUnblockTicks = Math.max(0, (int) smartUnblockTicks.getInput());
        }
    }

    /**
     * Build the unblock/attack/restore sequence around the real outgoing attack packet. This is
     * the reliable synchronization point in Mindless; guessing from a tick or an animation is
     * what made the earlier three-mode port feel incomplete and occasionally miss the swing.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSendPacket(SendPacketEvent event) {
        if (injecting || !blocking || !(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity attack = (C02PacketUseEntity) event.getPacket();
        if (attack.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }

        int current = currentMode();
        if (current == MODE_NONE || current == MODE_LEGIT) {
            return;
        }

        // With a live NoSlow block and this option enabled, preserve the block across the swing.
        if (!shouldReleaseForAttack()) {
            return;
        }

        event.setCanceled(true);
        injecting = true;
        try {
            if (current == MODE_LAG_NEW || current == MODE_LAG_NEW_PRE) {
                int held = mc.thePlayer.inventory.currentItem;
                send(new C09PacketHeldItemChange(neighbourSlot(held)));
                clearClientBlockVisual();
                send(attack);
                send(new C09PacketHeldItemChange(held));
            }
            else {
                send(unblockPacket());
                clearClientBlockVisual();
                if (current == MODE_LAG_LEGIT || current == MODE_LAG_LEGIT_PRE) {
                    mc.thePlayer.stopUsingItem();
                }
                send(attack);
            }
        }
        finally {
            injecting = false;
        }

        blocking = false;
        reblockPending = true;
        reblockDelayTicks = isPreMode() ? 0 : cadenceTicks();
        lastBlockEndMs = System.currentTimeMillis();
    }

    private boolean shouldBlock() {
        if (!Utils.holdingSword() || target == null) {
            return false;
        }
        boolean auraAttacking = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && ModuleManager.killAura.getHudTarget() != null;
        if (requireKillAura.isToggled() && !auraAttacking) {
            return false;
        }
        boolean pressed = Mouse.isButtonDown(0) || (!manualLeftClick.isToggled() && auraAttacking);
        if (requireLeftClick.isToggled() && !pressed) {
            return false;
        }
        return !requireRightClick.isToggled() || Mouse.isButtonDown(1);
    }

    public boolean allowsNoSlow() {
        if (!isEnabled() || !allowNoSlow.isToggled() || ModuleManager.noSlow == null
                || !ModuleManager.noSlow.isEnabled()) {
            return false;
        }
        if (disableNoSlowInRange.isToggled() && target != null && Utils.nullCheck()
                && mc.thePlayer.getDistanceToEntity(target) <= noSlowDisableRange.getInput()) {
            return false;
        }
        return true;
    }

    private boolean shouldReleaseForAttack() {
        return !onlyUnblockWithoutNoSlow.isToggled() || !allowsNoSlow();
    }

    private int currentMode() {
        return (int) mode.getInput();
    }

    private boolean isPreMode() {
        int current = currentMode();
        return current == MODE_LAG_NEW_PRE || current == MODE_LAG_PRE
                || current == MODE_LAG_LEGIT_PRE;
    }

    private int cadenceTicks() {
        switch ((int) apsMode.getInput()) {
            case 0: return 3;
            case 1: return 2;
            default: return 1;
        }
    }

    private boolean isCoolingDown() {
        double wait = cooldown.getInput();
        return wait > 0.0 && lastBlockEndMs > 0L
                && System.currentTimeMillis() - lastBlockEndMs < wait;
    }

    private int neighbourSlot(int held) {
        return held == 0 ? 1 : held - 1;
    }

    private void sendBlock() {
        if (!Utils.holdingSword()) return;
        injecting = true;
        try {
            send(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        }
        finally {
            injecting = false;
        }
        blocking = true;
        reblockPending = false;
    }

    private void stopBlocking(boolean releaseKey) {
        if (blocking && Utils.nullCheck()) {
            injecting = true;
            try {
                send(unblockPacket());
            }
            finally {
                injecting = false;
            }
            lastBlockEndMs = System.currentTimeMillis();
        }
        blocking = false;
        reblockPending = false;
        reblockDelayTicks = 0;
        if (releaseKey || useKeyHeld) setUseKey(false);
        clearClientBlockVisual();
    }

    private void clearClientBlockVisual() {
        ReflectionUtils.setItemInUse(false);
    }

    private void syncVisualBlocking() {
        ReflectionUtils.setItemInUse(visualBlocking.isToggled() && blocking);
    }

    private C07PacketPlayerDigging unblockPacket() {
        return new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN);
    }

    private void send(Packet<?> packet) {
        if (Utils.nullCheck()) mc.thePlayer.sendQueue.addToSendQueue(packet);
    }

    private void setUseKey(boolean down) {
        if (useKeyHeld == down || mc.gameSettings == null) return;
        useKeyHeld = down;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
    }

    private void resetState() {
        blocking = false;
        reblockPending = false;
        injecting = false;
        useKeyHeld = false;
        reblockDelayTicks = 0;
        hurtUnblockTicks = 0;
        lastBlockEndMs = 0L;
        target = null;
    }
}
