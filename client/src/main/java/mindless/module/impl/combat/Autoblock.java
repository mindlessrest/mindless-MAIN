package mindless.module.impl.combat;

import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.Random;

/**
 * Sword blocking that survives attacking.
 *
 * A vanilla client cannot swing while blocking, because blocking is the right mouse button being
 * held. Blocking with packets instead frees the swing, but leaves the server holding a record of
 * the item being used, and an attack arriving in the middle of that is the thing worth hiding.
 *
 * Blockhit is the mode that deals with it. Immediately before an attack goes out it bounces the
 * held item to a neighbouring slot and straight back: changing the held item is what clears the
 * server's item-in-use state, so the swing that follows is not an attack made while blocking, and
 * the block is re-established behind it. Vanilla instead releases the block properly and
 * re-establishes it, which is honest and slower. Legit just holds the button and cannot attack --
 * it is here for servers where the other two are not worth the risk.
 *
 * Smart unblock exists because a permanent block is itself a pattern: it drops the block for a
 * few ticks now and then, at a configurable rate, so the timing is not identical on every swing.
 */
public class Autoblock extends Module {
    private static final String[] MODES = new String[]{"Blockhit", "Vanilla", "Legit"};
    private static final int MODE_BLOCKHIT = 0;
    private static final int MODE_VANILLA = 1;
    private static final int MODE_LEGIT = 2;

    private final SliderSetting mode;
    private final SliderSetting range;
    private final SliderSetting fov;

    private final GroupSetting conditionGroup;
    private final ButtonSetting requireLeftClick;
    private final ButtonSetting requireRightClick;
    private final ButtonSetting requireKillAura;
    private final ButtonSetting onlyPlayers;

    private final GroupSetting unblockGroup;
    private final ButtonSetting smartUnblock;
    private final SliderSetting smartUnblockChance;
    private final SliderSetting smartUnblockTicks;

    private final ButtonSetting visualBlocking;
    private final SliderSetting cooldown;

    private final Random random = new Random();

    /** Whether we believe the server currently sees us blocking. */
    private boolean blocking;
    /** Set after an attack broke the block, so it goes back up on the next tick. */
    private boolean reblockPending;
    /** Ticks left of a deliberate gap in the block. */
    private int unblockTicksLeft;
    private long lastBlockEndMs;
    private EntityLivingBase target;

    /**
     * Guards the packets this module sends itself.
     *
     * Everything sent through the send queue comes back through SendPacketEvent, and the swap is
     * emitted from inside that handler, so without this the first attack would recurse.
     */
    private boolean injecting;

    public Autoblock() {
        super("Autoblock", "Blocks with your sword without giving up the swing.", category.combat, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_BLOCKHIT, MODES));
        this.registerSetting(range = new SliderSetting("Range", 4.0, 2.0, 6.0, 0.1));
        this.registerSetting(fov = new SliderSetting("FOV", "°", 180.0, 30.0, 360.0, 5.0));

        this.registerSetting(conditionGroup = new GroupSetting("Conditions"));
        this.registerSetting(requireLeftClick = new ButtonSetting(conditionGroup, "Require left click", true));
        this.registerSetting(requireRightClick = new ButtonSetting(conditionGroup, "Require right click", false));
        this.registerSetting(requireKillAura = new ButtonSetting(conditionGroup, "Require KillAura", false));
        this.registerSetting(onlyPlayers = new ButtonSetting(conditionGroup, "Players only", true));

        this.registerSetting(unblockGroup = new GroupSetting("Smart unblock"));
        this.registerSetting(smartUnblock = new ButtonSetting(unblockGroup, "Smart unblock", false));
        this.registerSetting(smartUnblockChance = new SliderSetting(unblockGroup, "Unblock chance", "%", 25.0, 0.0, 100.0, 5.0));
        this.registerSetting(smartUnblockTicks = new SliderSetting(unblockGroup, "Unblock ticks", 2.0, 1.0, 10.0, 1.0));

        this.registerSetting(visualBlocking = new ButtonSetting("Visual blocking", true));
        this.registerSetting(cooldown = new SliderSetting("Cooldown", "ms", 0.0, 0.0, 500.0, 25.0));
    }

    @Override
    public void guiUpdate() {
        int current = (int) mode.getInput();
        boolean packetModes = current != MODE_LEGIT;
        if (smartUnblock != null) smartUnblock.setVisible(packetModes, this);
        boolean smart = packetModes && smartUnblock != null && smartUnblock.isToggled();
        if (smartUnblockChance != null) smartUnblockChance.setVisible(smart, this);
        if (smartUnblockTicks != null) smartUnblockTicks.setVisible(smart, this);
        if (visualBlocking != null) visualBlocking.setVisible(packetModes, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()].toLowerCase();
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        if (blocking) {
            sendUnblock();
        }
        setUseKey(false);
        ReflectionUtils.setItemInUse(false);
        reset();
    }

    private void reset() {
        blocking = false;
        reblockPending = false;
        unblockTicksLeft = 0;
        lastBlockEndMs = 0L;
        target = null;
    }

    /** LagRange asks whether a block is currently standing. */
    public boolean isActive() {
        return isEnabled() && blocking;
    }

    private int currentMode() {
        return (int) mode.getInput();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            if (blocking) {
                sendUnblock();
            }
            setUseKey(false);
            ReflectionUtils.setItemInUse(false);
            return;
        }

        target = findTarget();
        boolean wanted = shouldBlock();

        if (currentMode() == MODE_LEGIT) {
            // No packets at all: the button is genuinely held, which means no swing while it is.
            setUseKey(wanted);
            blocking = wanted && Utils.holdingSword();
            return;
        }

        if (unblockTicksLeft > 0) {
            unblockTicksLeft--;
            ReflectionUtils.setItemInUse(false);
            return;
        }

        if (!wanted) {
            if (blocking) {
                sendUnblock();
            }
            ReflectionUtils.setItemInUse(false);
            return;
        }

        if (!blocking && !isCoolingDown()) {
            sendBlock();
        }
        else if (reblockPending && !isCoolingDown()) {
            sendBlock();
        }

        ReflectionUtils.setItemInUse(visualBlocking.isToggled() && blocking);
    }

    /**
     * Break the block around an outgoing attack.
     *
     * The event fires before the packet leaves, so whatever is written here lands ahead of the
     * attack, and the block goes back up on the following tick.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSendPacket(SendPacketEvent event) {
        if (injecting || !Utils.nullCheck() || !blocking) {
            return;
        }
        if (currentMode() == MODE_LEGIT) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity attack = (C02PacketUseEntity) event.getPacket();
        if (attack.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }

        injecting = true;
        try {
            if (currentMode() == MODE_BLOCKHIT) {
                // Bounce the held item off the sword and straight back. The round trip is what
                // clears the server's item-in-use record; the slot we land on does not matter,
                // only that it is a different one.
                int held = mc.thePlayer.inventory.currentItem;
                send(new C09PacketHeldItemChange(neighbourSlot(held)));
                send(new C09PacketHeldItemChange(held));
            }
            else {
                send(unblockPacket());
            }
        }
        finally {
            injecting = false;
        }

        blocking = false;
        lastBlockEndMs = System.currentTimeMillis();

        if (smartUnblock.isToggled() && random.nextDouble() * 100.0 < smartUnblockChance.getInput()) {
            // Leave the block down for a moment rather than snapping it back on every swing.
            unblockTicksLeft = (int) smartUnblockTicks.getInput();
            reblockPending = false;
        }
        else {
            reblockPending = true;
        }
    }

    private boolean shouldBlock() {
        if (!Utils.holdingSword()) {
            return false;
        }
        if (target == null) {
            return false;
        }
        boolean auraAttacking = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && ModuleManager.killAura.getHudTarget() != null;
        if (requireKillAura.isToggled() && !auraAttacking) {
            return false;
        }
        // The aura swinging on its own counts as the left button being down; otherwise the
        // module would refuse to block for exactly the setup it exists to support.
        if (requireLeftClick.isToggled() && !Mouse.isButtonDown(0) && !auraAttacking) {
            return false;
        }
        if (requireRightClick.isToggled() && !Mouse.isButtonDown(1)) {
            return false;
        }
        return true;
    }

    private EntityLivingBase findTarget() {
        double reach = range.getInput();
        EntityLivingBase found = CombatTargeting.findTarget(reach * reach);
        if (found == null) {
            return null;
        }
        if (onlyPlayers.isToggled() && !(found instanceof EntityPlayer)) {
            return null;
        }
        double allowed = fov.getInput();
        if (allowed < 360.0 && !Utils.inFov((float) allowed, found)) {
            return null;
        }
        return found;
    }

    private boolean isCoolingDown() {
        double wait = cooldown.getInput();
        return wait > 0.0 && lastBlockEndMs > 0L
                && System.currentTimeMillis() - lastBlockEndMs < wait;
    }

    /** Any slot other than the one held; the round trip is what matters, not the destination. */
    private int neighbourSlot(int held) {
        return held == 0 ? 1 : held - 1;
    }

    private void sendBlock() {
        if (!Utils.holdingSword()) {
            return;
        }
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

    private void sendUnblock() {
        injecting = true;
        try {
            send(unblockPacket());
        }
        finally {
            injecting = false;
        }
        blocking = false;
        reblockPending = false;
        lastBlockEndMs = System.currentTimeMillis();
    }

    private C07PacketPlayerDigging unblockPacket() {
        return new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN);
    }

    private void send(net.minecraft.network.Packet<?> packet) {
        mc.thePlayer.sendQueue.addToSendQueue(packet);
    }

    private void setUseKey(boolean down) {
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, down);
        if (down) {
            KeyBinding.onTick(keyCode);
        }
    }
}
