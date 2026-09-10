package mindless.module.impl.combat;

import mindless.Mindless;
import mindless.event.PostUpdateEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.SendPacketEvent;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.LagRequest;
import mindless.lag.timeout.ModuleBackedTimeout;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class MyauBlock extends Module {
    private static final int VANILLA = 0;
    private static final int SPOOF = 1;
    private static final int HYPIXEL = 2;
    private static final int BLINK = 3;
    private static final int INTERACT = 4;
    private static final int SWAP = 5;
    private static final int LEGIT = 6;
    private static final int FAKE = 7;
    private static final int MODERN = 8;

    private final String[] modes = new String[]{"Vanilla", "Spoof", "Hypixel", "Blink", "Interact", "Swap", "Legit", "Fake", "Modern"};
    private final SliderSetting mode;
    private final SliderSetting autoBlockCps;
    private final SliderSetting blockRange;
    private final ButtonSetting requirePress;
    private final ButtonSetting allowNoSlow;

    private boolean packetBlocking;
    private boolean visualBlocking;
    private boolean legitBlocking;
    private boolean replayingAttack;
    private C02PacketUseEntity delayedAttack;
    private int stagedTicks;
    private boolean reblockPending;
    private boolean suppressAttacksThisTick;
    private long nextAttackAt;
    private LagRequest outboundBlink;
    private int lastMode = -1;

    public MyauBlock() {
        super("MyauBlock", "OpenMyau-style staged sword autoblock.", category.combat, 0);
        this.registerSetting(mode = new SliderSetting("Mode", HYPIXEL, modes));
        this.registerSetting(autoBlockCps = new SliderSetting("AutoBlock CPS", 8.0, 1.0, 10.0, 0.5));
        this.registerSetting(blockRange = new SliderSetting("Block range", 6.0, 3.0, 8.0, 0.1));
        this.registerSetting(requirePress = new ButtonSetting("Require press", false));
        this.registerSetting(allowNoSlow = new ButtonSetting("Allow NoSlow", true));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        clearState(false);
    }

    @Override
    public void onDisable() {
        clearState(true);
    }

    @Override
    public String getInfo() {
        return modes[(int) mode.getInput()];
    }

    public boolean isOperational() {
        return isEnabled();
    }

    public boolean isActive() {
        return isEnabled() && visualBlocking;
    }

    public boolean allowsNoSlow() {
        return allowNoSlow.isToggled() && packetBlocking;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        suppressAttacksThisTick = false;
        int selected = (int) mode.getInput();
        if (lastMode != selected) {
            clearState(true);
            lastMode = selected;
        }
        if (!canBlock()) {
            clearState(true);
            return;
        }

        if (selected == FAKE) {
            if (packetBlocking) {
                stopPacketBlock();
            }
            visualBlocking = true;
            ReflectionUtils.setItemInUse(true);
            return;
        }

        if (delayedAttack != null) {
            stagedTicks++;
            if (selected == MODERN) {
                if (stagedTicks >= 2) {
                    replayDelayedAttack();
                    startPacketBlock();
                    releaseBlink();
                }
            } else if (selected == HYPIXEL || selected == LEGIT) {
                if (stagedTicks >= 1) {
                    replayDelayedAttack();
                    if (selected == LEGIT) {
                        startLegitBlock();
                    } else {
                        startPacketBlock();
                    }
                }
            }
            return;
        }

        if (selected == LEGIT) {
            startLegitBlock();
        } else if (!packetBlocking) {
            startPacketBlock();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent event) {
        if (replayingAttack || event.isCanceled() || !(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity attack = (C02PacketUseEntity) event.getPacket();
        if (attack.getAction() != C02PacketUseEntity.Action.ATTACK || !canBlock()) {
            return;
        }
        if (suppressAttacksThisTick) {
            event.setCanceled(true);
            return;
        }
        if (delayedAttack != null) {
            event.setCanceled(true);
            return;
        }

        int selected = (int) mode.getInput();
        if (selected != VANILLA && selected != FAKE && System.currentTimeMillis() < nextAttackAt) {
            event.setCanceled(true);
            return;
        }
        switch (selected) {
            case VANILLA:
            case FAKE:
                return;
            case SPOOF:
                stopPacketBlock(true);
                spoofSlot(findFallbackSlot(mc.thePlayer.inventory.currentItem));
                reblockPending = true;
                markAttackSent();
                return;
            case HYPIXEL:
                event.setCanceled(true);
                delayedAttack = attack;
                stagedTicks = 0;
                stopPacketBlock(true);
                return;
            case BLINK:
                event.setCanceled(true);
                startBlink();
                stopPacketBlock(true);
                sendAttack(attack);
                reblockPending = true;
                return;
            case INTERACT:
                stopPacketBlock(true);
                sendInteract(attack);
                reblockPending = true;
                markAttackSent();
                return;
            case SWAP:
                stopPacketBlock(true);
                int swordSlot = findSwordSlot(mc.thePlayer.inventory.currentItem);
                spoofSlot(swordSlot == -1 ? findFallbackSlot(mc.thePlayer.inventory.currentItem) : swordSlot);
                reblockPending = true;
                markAttackSent();
                return;
            case LEGIT:
                event.setCanceled(true);
                delayedAttack = attack;
                stagedTicks = 0;
                stopLegitBlock();
                return;
            case MODERN:
                event.setCanceled(true);
                delayedAttack = attack;
                stagedTicks = 0;
                startBlink();
                stopPacketBlock(true);
                return;
            default:
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        if (!reblockPending) {
            return;
        }
        reblockPending = false;
        if (canBlock()) {
            startPacketBlock();
        }
        releaseBlink();
    }

    private boolean canBlock() {
        if (!Utils.nullCheck() || mc.playerController == null || mc.currentScreen != null || mc.thePlayer.isDead || !Utils.holdingSword()) {
            return false;
        }
        if (mc.playerController.getIsHittingBlock()) {
            return false;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        }
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isActivelyScaffolding()) {
            return false;
        }
        if (requirePress.isToggled() && !Mouse.isButtonDown(1)) {
            return false;
        }
        EntityLivingBase auraTarget = KillAura.attackingEntity != null ? KillAura.attackingEntity : KillAura.target;
        if (auraTarget != null && !auraTarget.isDead && mc.thePlayer.getDistanceSqToEntity(auraTarget) <= blockRange.getInput() * blockRange.getInput()) {
            return true;
        }
        EntityPlayer target = CombatTargeting.findTarget(blockRange.getInput() * blockRange.getInput(), true);
        boolean auraActive = ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && !ModuleManager.killAura.isRequireMouseDown();
        return target != null && (Mouse.isButtonDown(0) || auraActive);
    }

    private void startPacketBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (packetBlocking || held == null || !(held.getItem() instanceof ItemSword)) {
            return;
        }
        mc.thePlayer.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(held));
        packetBlocking = true;
        visualBlocking = true;
        ReflectionUtils.setItemInUse(true);
    }

    private void stopPacketBlock() {
        stopPacketBlock(false);
    }

    private void stopPacketBlock(boolean keepVisual) {
        if (!packetBlocking || !Utils.nullCheck()) {
            return;
        }
        mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        packetBlocking = false;
        visualBlocking = keepVisual;
        ReflectionUtils.setItemInUse(keepVisual);
    }

    private void startLegitBlock() {
        if (packetBlocking) {
            return;
        }
        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, true);
        mindless.helper.MouseHelper.aR();
        KeyBinding.onTick(key);
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null) {
            mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
        }
        packetBlocking = true;
        visualBlocking = true;
        legitBlocking = true;
        ReflectionUtils.setItemInUse(true);
    }

    private void stopLegitBlock() {
        stopPacketBlock();
        if (legitBlocking && Utils.nullCheck()) {
            mc.thePlayer.stopUsingItem();
        }
        legitBlocking = false;
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
    }

    private void spoofSlot(int slot) {
        int current = mc.thePlayer.inventory.currentItem;
        if (slot < 0 || slot > 8 || slot == current) {
            return;
        }
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(current));
    }

    private int findFallbackSlot(int current) {
        for (int i = 0; i < 9; i++) {
            if (i != current && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        return Math.floorMod(current - 1, 9);
    }

    private int findSwordSlot(int current) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (i != current && stack != null && stack.getItem() instanceof ItemSword) {
                return i;
            }
        }
        return -1;
    }

    private void sendInteract(C02PacketUseEntity attack) {
        Entity target = attack.getEntityFromWorld(mc.theWorld);
        if (target == null) {
            return;
        }
        Vec3 hit = new Vec3(0.0, Math.max(0.0, target.height * 0.5), 0.0);
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(target, hit));
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(target, C02PacketUseEntity.Action.INTERACT));
    }

    private void replayDelayedAttack() {
        C02PacketUseEntity attack = delayedAttack;
        delayedAttack = null;
        stagedTicks = 0;
        if (attack != null) {
            suppressAttacksThisTick = true;
            sendAttack(attack);
        }
    }

    private void sendAttack(C02PacketUseEntity attack) {
        replayingAttack = true;
        try {
            mc.thePlayer.sendQueue.addToSendQueue(attack);
            markAttackSent();
        } finally {
            replayingAttack = false;
        }
    }

    private void startBlink() {
        if (outboundBlink != null) {
            return;
        }
        outboundBlink = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Mindless.lagHandler.requestLag(outboundBlink);
    }

    private void releaseBlink() {
        if (outboundBlink == null) {
            return;
        }
        outboundBlink.getTimeout().forceTimeOut();
        outboundBlink = null;
    }

    private void clearState(boolean releaseServerBlock) {
        delayedAttack = null;
        stagedTicks = 0;
        reblockPending = false;
        suppressAttacksThisTick = false;
        replayingAttack = false;
        nextAttackAt = 0L;
        releaseBlink();
        if (releaseServerBlock) {
            if (legitBlocking) {
                stopLegitBlock();
            } else {
                stopPacketBlock();
            }
        }
        packetBlocking = false;
        visualBlocking = false;
        legitBlocking = false;
        ReflectionUtils.setItemInUse(false);
        if (mc.gameSettings != null) {
            boolean physicalUse = Mouse.isButtonDown(1) && mc.currentScreen == null;
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), physicalUse);
            ReflectionUtils.setItemInUse(physicalUse && Utils.nullCheck() && Utils.holdingSword());
        }
    }

    private void markAttackSent() {
        nextAttackAt = System.currentTimeMillis() + Math.max(1L, Math.round(1000.0 / autoBlockCps.getInput()));
    }
}
