package mindless.module.impl.combat;

import mindless.Raven;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.RightClickMouseEvent;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.LagRequest;
import mindless.lag.timeout.ModuleBackedTimeout;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.event.SendPacketEvent;
import mindless.event.UseItemEvent;
import mindless.module.impl.world.AntiBot;
import mindless.utility.BlockUtils;
import mindless.utility.CombatTargeting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class Autoblock extends Module {
    private static final String[] MODES = new String[]{"Vanilla", "Lag"};
    private static final String[] UNBLOCK_OUT_OF_RANGE_MODES = new String[]{"Once", "Always"};
    private static final int UNBLOCK_ONCE = 0;
    private static final int UNBLOCK_ALWAYS = 1;

    private final SliderSetting mode;
    private final SliderSetting range;
    private final SliderSetting maxHurtTimeMs;
    private final SliderSetting maxHoldMs;
    private final SliderSetting cooldownMs;
    private final SliderSetting unblockOutOfRange;

    private final ButtonSetting requireLmb;
    private final ButtonSetting requireRmb;
    private final ButtonSetting onlyWhenDamaged;
    private final ButtonSetting ignoreTeammates;

    private final SliderSetting lagChance;
    private final SliderSetting lagMaxDuration;
    private final ButtonSetting preventDelayAttacks;
    private final ButtonSetting blockAgainImmediately;
    private final ButtonSetting forceBlockAnimation;

    private boolean isBlocking;
    private boolean manualBlock;
    private boolean targetWasInRange;
    private boolean unblockedAfterLeavingRange;
    private boolean allowingAlwaysInteraction;
    private int blockStartTick = -1;
    private long lastBlockEndTimeMs;
    private EntityPlayer currentTarget;
    private int lastSelfHurtTime;

    private boolean isLagging;
    private int lagStartTick = -1;
    private LagRequest outboundLag;

    private int tickCounter;

    public Autoblock() {
        super("Auto Block", category.combat);
        this.liteModule = true;

        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(range = new SliderSetting("Range", 4.0, 2.0, 6.0, 0.1));
        this.registerSetting(maxHurtTimeMs = new SliderSetting("Maximum hurt time", "ms", 200, 50, 500, 50));
        this.registerSetting(maxHoldMs = new SliderSetting("Maximum hold duration", "ms", 150, 50, 500, 50));
        this.registerSetting(cooldownMs = new SliderSetting("Cooldown", "ms", 0, 0, 500, 50));

        this.registerSetting(lagChance = new SliderSetting("Lag chance", "%", 100, 0, 100, 5));
        this.registerSetting(lagMaxDuration = new SliderSetting("Lag max duration", "ms", 200, 50, 500, 50));
        this.registerSetting(unblockOutOfRange = new SliderSetting("Unblock out of range", true, 0, UNBLOCK_OUT_OF_RANGE_MODES));
        this.registerSetting(preventDelayAttacks = new ButtonSetting("Prevent delaying attacks", true));
        this.registerSetting(blockAgainImmediately = new ButtonSetting("Block again immediately", true));

        this.registerSetting(forceBlockAnimation = new ButtonSetting("Force block animation", true));
        this.registerSetting(requireLmb = new ButtonSetting("Require left mouse", true));
        this.registerSetting(requireRmb = new ButtonSetting("Require right mouse", false));
        this.registerSetting(onlyWhenDamaged = new ButtonSetting("Damaged", false));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        boolean lagMode = isLagMode();
        lagChance.setVisible(lagMode, this);
        lagMaxDuration.setVisible(lagMode, this);
        preventDelayAttacks.setVisible(lagMode, this);
        blockAgainImmediately.setVisible(lagMode, this);
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        resetState(false);
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0) return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    @Override
    public void onDisable() {
        resetState(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (!Utils.nullCheck() || !Utils.holdingSword()) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) return;
        if (e.button == 1) {
            if ((int) unblockOutOfRange.getInput() == UNBLOCK_ALWAYS) {
                if (!e.buttonstate && allowingAlwaysInteraction) {
                    allowingAlwaysInteraction = false;
                    return;
                }

                if (e.buttonstate && canInteractWhileAlwaysUnblocked()) {
                    releaseLag();
                    stopBlocking(true);
                    manualBlock = false;
                    allowingAlwaysInteraction = true;
                    return;
                }
            }
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickMouse(RightClickMouseEvent e) {
        if (shouldBlockVanillaUse()) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItem(UseItemEvent e) {
        if (allowingAlwaysInteraction || shouldBlockVanillaUse()) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (!Utils.nullCheck()) {
            syncBlockAnimation();
            return;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            ReflectionUtils.setItemInUse(false);
            return;
        }
        if (mc.currentScreen != null && (isBlocking || isLagging)) {
            resetState(true);
            return;
        }
        syncBlockAnimation();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSendPacket(SendPacketEvent e) {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            releaseLag();
            return;
        }
        if (!isLagging || !preventDelayAttacks.isToggled()) return;
        if (!(e.getPacket() instanceof C02PacketUseEntity)) return;
        if (((C02PacketUseEntity) e.getPacket()).getAction() != C02PacketUseEntity.Action.ATTACK) return;

        releaseLag();
        if (blockAgainImmediately.isToggled() && Utils.holdingSword()) {
            startBlocking(tickCounter);
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck() || mc.thePlayer.isDead || mc.currentScreen != null) {
            resetState(true);
            return;
        }

        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            resetState(true);
            return;
        }

        int selfHurtTime = mc.thePlayer.hurtTime;
        boolean hurtAgain = selfHurtTime > lastSelfHurtTime;
        lastSelfHurtTime = selfHurtTime;

        if (!Utils.holdingSword()) {
            resetState(false);
            return;
        }

        if (allowingAlwaysInteraction) {
            releaseLag();
            stopBlocking(true);
            manualBlock = false;
            return;
        }

        tickCounter++;
        int currentTick = tickCounter;

        if (!isLagMode() && isLagging) {
            releaseLag();
        }

        currentTarget = CombatTargeting.findTarget(range.getInput() * range.getInput(), ignoreTeammates.isToggled());
        boolean killAuraAttacking = ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && !ModuleManager.killAura.isRequireMouseDown() && currentTarget != null;
        boolean rmbDown = Mouse.isButtonDown(1);
        boolean lmbDown = Mouse.isButtonDown(0) || killAuraAttacking;
        boolean hasTarget = currentTarget != null;
        boolean conditionsMet = hasTarget && checkConditions(lmbDown, rmbDown);
        boolean leftTargetRange = targetWasInRange && !hasTarget;
        targetWasInRange = hasTarget;
        int unblockMode = (int) unblockOutOfRange.getInput();

        if (unblockMode != UNBLOCK_ONCE || !rmbDown || hasTarget) {
            unblockedAfterLeavingRange = false;
        }

        if (unblockMode == UNBLOCK_ALWAYS && !hasTarget) {
            releaseLag();
            stopBlocking(true);
            manualBlock = false;
            return;
        }

        if (unblockMode == UNBLOCK_ONCE && rmbDown && leftTargetRange) {
            if (isLagging) releaseLag();
            stopBlocking(true);
            manualBlock = false;
            unblockedAfterLeavingRange = true;
            return;
        }

        if (hurtAgain) {
            releaseLag();
            stopBlocking(true);
            manualBlock = false;
        }

        if (!conditionsMet && rmbDown) {
            if (unblockedAfterLeavingRange) {
                return;
            }
            if (isLagging) releaseLag();
            if (!isBlocking) {
                startBlocking(currentTick);
            }
            manualBlock = true;
            return;
        }

        if (manualBlock) {
            stopBlocking(true);
            manualBlock = false;
        }

        if (isLagging) {
            int lagMaxTicks = msToTicks(lagMaxDuration.getInput());
            boolean lagExpired = lagMaxTicks > 0 && lagStartTick >= 0 && currentTick - lagStartTick >= lagMaxTicks;

            if (lagExpired || !conditionsMet) {
                releaseLag();
                if (lagExpired && blockAgainImmediately.isToggled() && conditionsMet) {
                    startBlocking(currentTick);
                }
            }
        }

        if (!conditionsMet) {
            stopBlocking(true);
            return;
        }

        if (!isBlocking && !isLagging) {
            if (shouldPredictiveBlock()) {
                startBlocking(currentTick);
            }
        }

        if (isBlocking) {
            int maxHoldTicks = msToTicks(maxHoldMs.getInput());
            boolean timeExpired = maxHoldTicks > 0 && blockStartTick >= 0 && currentTick - blockStartTick >= maxHoldTicks;
            if (timeExpired) {
                if (shouldStartLag()) {
                    startLag(currentTick);
                }
                stopBlocking(true);
            }
        }
    }

    private boolean checkConditions(boolean lmbDown, boolean rmbDown) {
        if (requireLmb.isToggled() && !lmbDown) return false;
        if (requireRmb.isToggled() && !rmbDown) return false;
        return true;
    }

    private boolean shouldPredictiveBlock() {
        int ourHurtTime = mc.thePlayer.hurtTime;
        int triggerTick = (int) Math.round(maxHurtTimeMs.getInput() / 50.0);
        triggerTick = Math.max(1, Math.min(10, triggerTick));
        return ourHurtTime == triggerTick || (!onlyWhenDamaged.isToggled() && ourHurtTime == 0);
    }

    private boolean shouldBlockVanillaUse() {
        return isEnabled() && isLagging && Utils.nullCheck() && Utils.holdingSword() && mc.currentScreen == null;
    }

    private void startBlocking(int currentTick) {
        if (!Utils.holdingSword() || isCooldownActive()) return;
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, true);
        KeyBinding.onTick(keyCode);
        isBlocking = true;
        blockStartTick = currentTick;
        syncBlockAnimation();
    }

    private void stopBlocking(boolean forceRelease) {
        if (!isBlocking && !forceRelease) return;
        boolean wasBlocking = isBlocking;
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, false);
        isBlocking = false;
        blockStartTick = -1;
        if (wasBlocking) {
            lastBlockEndTimeMs = System.currentTimeMillis();
        }
        syncBlockAnimation();
    }

    private boolean isCooldownActive() {
        double cooldown = cooldownMs.getInput();
        return cooldown > 0 && lastBlockEndTimeMs > 0
                && System.currentTimeMillis() - lastBlockEndTimeMs < cooldown;
    }

    private boolean shouldStartLag() {
        if (!isLagMode()) return false;
        double chance = lagChance.getInput();
        if (chance <= 0) return false;
        if (chance >= 100) return true;
        return Math.random() * 100 < chance;
    }

    private void startLag(int currentTick) {
        if (isLagging) return;
        int lagReferenceTick = blockStartTick >= 0 ? blockStartTick : currentTick;
        int lagMaxTicks = msToTicks(lagMaxDuration.getInput());
        if (lagMaxTicks > 0 && currentTick - lagReferenceTick >= lagMaxTicks) {
            return;
        }
        outboundLag = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(outboundLag);
        isLagging = true;
        lagStartTick = lagReferenceTick;
        syncBlockAnimation();
    }

    private void releaseLag() {
        if (!isLagging) return;
        if (outboundLag != null) {
            outboundLag.getTimeout().forceTimeOut();
            outboundLag = null;
        }
        isLagging = false;
        lagStartTick = -1;
        syncBlockAnimation();
    }

    public boolean isActive() {
        return isEnabled() && (isBlocking || isLagging);
    }

    private boolean isLagMode() {
        return mode.getInput() == 1;
    }

    private boolean canInteractWhileAlwaysUnblocked() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null) return false;

        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return BlockUtils.isInteractable(hit);
        }

        if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY || hit.entityHit == null) {
            return false;
        }

        Entity entity = hit.entityHit;
        return !(entity instanceof EntityPlayer) || AntiBot.isBot(entity);
    }

    private void syncBlockAnimation() {
        boolean killAuraAttacking = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && !ModuleManager.killAura.isRequireMouseDown()
                && currentTarget != null;
        boolean requiredMouseButtonsDown = checkConditions(
                Mouse.isButtonDown(0) || killAuraAttacking,
                Mouse.isButtonDown(1)
        );
        boolean continuousUndamagedBlock = !onlyWhenDamaged.isToggled()
                && currentTarget != null
                && !allowingAlwaysInteraction;
        boolean shouldAnimate = forceBlockAnimation.isToggled()
                && Utils.nullCheck()
                && mc.currentScreen == null
                && Utils.holdingSword()
                && requiredMouseButtonsDown
                && (continuousUndamagedBlock || isBlocking || isLagging);
        ReflectionUtils.setItemInUse(shouldAnimate);
    }

    private void resetState(boolean releaseUseKey) {
        boolean restorePhysicalUse = isBlocking
                && mc.gameSettings.keyBindUseItem.isKeyDown()
                && Mouse.isButtonDown(1)
                && mc.currentScreen == null;
        releaseLag();
        stopBlocking(releaseUseKey);
        manualBlock = false;
        targetWasInRange = false;
        unblockedAfterLeavingRange = false;
        allowingAlwaysInteraction = false;
        lastBlockEndTimeMs = 0L;
        currentTarget = null;
        lastSelfHurtTime = 0;
        syncBlockAnimation();
        if (restorePhysicalUse) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        }
    }
}
