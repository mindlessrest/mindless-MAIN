package mindless.module.impl.combat;

import mindless.Mindless;
import mindless.event.AttackEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.RightClickMouseEvent;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.LagRequest;
import mindless.lag.timeout.ModuleBackedTimeout;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.event.SendPacketEvent;
import mindless.event.UseItemEvent;
import mindless.module.impl.world.TargetFilter;
import mindless.utility.AttackPacketTimingTracker;
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
    private static final String[] MODES = new String[]{"Vanilla", "Predict", "Manual", "Lag"};
    private static final String[] UNBLOCK_OUT_OF_RANGE_MODES = new String[]{"Once", "Always"};
    private static final int MODE_VANILLA = 0;
    private static final int MODE_PREDICT = 1;
    private static final int MODE_MANUAL = 2;
    private static final int MODE_LAG = 3;
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

    private final SliderSetting lagChance;
    private final SliderSetting lagMaxDuration;
    private final ButtonSetting preventDelayAttacks;
    private final ButtonSetting blockAgainImmediately;
    private final ButtonSetting forceBlockAnimation;
    private final SliderSetting predictEarlyWindow;
    private final ButtonSetting predictIncludePing;
    private final SliderSetting predictHoldAfter;
    private final SliderSetting manualChance;

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
    private static final int DAMAGE_INTERVAL_CAPACITY = 8;
    private static final int PREDICTION_SAMPLE_COUNT = 3;
    private static final long MIN_DAMAGE_INTERVAL_MS = 250L;
    private static final long MAX_DAMAGE_INTERVAL_MS = 1500L;
    private final long[] damageIntervals = new long[DAMAGE_INTERVAL_CAPACITY];
    private int damageIntervalCount;
    private int nextDamageIntervalIndex;
    private long lastDamageTimeMs;
    private boolean damageObserved;
    private boolean predictBlocking;
    private boolean predictHoldStarted;
    private long predictHoldUntil;
    private long manualReleaseTime;

    public Autoblock() {
        super("Auto Block", "Blocks your sword right before a hit lands.", category.combat);
        this.liteModule = true;

        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(range = new SliderSetting("Range", 4.0, 2.0, 6.0, 0.1));
        this.registerSetting(maxHurtTimeMs = new SliderSetting("Maximum hurt time", "ms", 200, 50, 500, 50));
        this.registerSetting(maxHoldMs = new SliderSetting("Maximum hold duration", "ms", 150, 50, 500, 50));
        this.registerSetting(cooldownMs = new SliderSetting("Cooldown", "ms", 0, 0, 500, 50));

        this.registerSetting(predictEarlyWindow = new SliderSetting("Early window", "ms", 100, 0, 500, 10));
        this.registerSetting(predictIncludePing = new ButtonSetting("Include ping", true));
        this.registerSetting(predictHoldAfter = new SliderSetting("Hold after", "ticks", 2, 0, 10, 1));

        this.registerSetting(manualChance = new SliderSetting("Chance", "%", 80, 0, 100, 5));

        this.registerSetting(lagChance = new SliderSetting("Lag chance", "%", 100, 0, 100, 5));
        this.registerSetting(lagMaxDuration = new SliderSetting("Lag max duration", "ms", 200, 50, 500, 50));
        this.registerSetting(unblockOutOfRange = new SliderSetting("Unblock out of range", true, 0, UNBLOCK_OUT_OF_RANGE_MODES));
        this.registerSetting(preventDelayAttacks = new ButtonSetting("Prevent delaying attacks", true));
        this.registerSetting(blockAgainImmediately = new ButtonSetting("Block again immediately", true));

        this.registerSetting(forceBlockAnimation = new ButtonSetting("Force block animation", true));
        this.registerSetting(requireLmb = new ButtonSetting("Require left mouse", true));
        this.registerSetting(requireRmb = new ButtonSetting("Require right mouse", false));
        this.registerSetting(onlyWhenDamaged = new ButtonSetting("Damaged", false));
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        int m = (int) mode.getInput();
        boolean lagMode = m == MODE_LAG;
        boolean predictMode = m == MODE_PREDICT;
        boolean manualMode = m == MODE_MANUAL;
        lagChance.setVisible(lagMode, this);
        lagMaxDuration.setVisible(lagMode, this);
        preventDelayAttacks.setVisible(lagMode, this);
        blockAgainImmediately.setVisible(lagMode, this);

        predictEarlyWindow.setVisible(predictMode, this);
        predictIncludePing.setVisible(predictMode, this);
        predictHoldAfter.setVisible(predictMode, this);

        manualChance.setVisible(manualMode, this);

        maxHurtTimeMs.setVisible(m == MODE_VANILLA, this);
        maxHoldMs.setVisible(m == MODE_VANILLA || lagMode, this);
        onlyWhenDamaged.setVisible(m == MODE_VANILLA, this);
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        resetState(false);
        resetPredictState();
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0) return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    @Override
    public void onDisable() {
        resetState(true);
        resetPredictState();
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
        if (e.button == 0 && e.buttonstate && (int) mode.getInput() == MODE_MANUAL) {
            if (currentTarget != null && Utils.holdingSword()) {
                double chance = manualChance.getInput();
                if (chance >= 100 || Math.random() * 100 < chance) {
                    startBlocking(tickCounter);
                    manualReleaseTime = System.currentTimeMillis() + 50L;
                }
            }
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
    public void onPreAttack(PreAttackEvent e) {
    }

    @SubscribeEvent
    public void onAttack(AttackEvent e) {
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
        int currentMode = (int) mode.getInput();

        if (currentMode != MODE_LAG && isLagging) {
            releaseLag();
        }

        currentTarget = CombatTargeting.findTarget(range.getInput() * range.getInput());
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
        if (hurtAgain && currentMode == MODE_PREDICT) {
            recordDamageInterval();
            setPredictBlocking(false);
        }

        if (hurtAgain && currentMode != MODE_PREDICT) {
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
        if (currentMode == MODE_PREDICT) {
            tickPredict(conditionsMet);
            return;
        }

        if (currentMode == MODE_MANUAL) {
            tickManual(conditionsMet);
            return;
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

    private void tickPredict(boolean conditionsMet) {
        if (!conditionsMet) {
            setPredictBlocking(false);
            stopBlocking(true);
            return;
        }

        int hurtResistantTime = mc.thePlayer.hurtResistantTime;

        if (damageObserved && hasStableDamagePattern()) {
            long now = System.currentTimeMillis();
            long avgInterval = getAverageDamageInterval();
            long earlyWindow = getEarlyWindowMs();
            long holdWindow = (long) predictHoldAfter.getInput() * 50L;
            long expectedDamage = lastDamageTimeMs + avgInterval;
            while (expectedDamage + holdWindow < now) {
                expectedDamage += avgInterval;
            }

            boolean insideWindow = now >= expectedDamage - earlyWindow && now <= expectedDamage + holdWindow;
            boolean canTakeDamage = hurtResistantTime <= 10 + getEarlyWindowTicks();

            if (insideWindow && canTakeDamage) {
                if (!isBlocking) startBlocking(tickCounter);
            } else {
                releaseAfterHold();
            }
        } else {
            int earlyTicks = getEarlyWindowTicks();
            if (hurtResistantTime > 0 && hurtResistantTime <= 10 + earlyTicks) {
                if (!isBlocking) startBlocking(tickCounter);
            } else if (hurtResistantTime == 0 && isBlocking) {
                releaseAfterHold();
            } else if (hurtResistantTime > 10 + earlyTicks && isBlocking) {
                releaseAfterHold();
            }
        }
    }

    private void releaseAfterHold() {
        int holdTicks = (int) predictHoldAfter.getInput();
        if (holdTicks <= 0) {
            stopBlocking(true);
            return;
        }
        long now = System.currentTimeMillis();
        if (!predictHoldStarted) {
            predictHoldStarted = true;
            predictHoldUntil = now + holdTicks * 50L;
        }
        if (now >= predictHoldUntil) {
            stopBlocking(true);
            predictHoldStarted = false;
        }
    }

    private void recordDamageInterval() {
        long now = System.currentTimeMillis();
        damageObserved = true;
        predictHoldStarted = false;
        predictHoldUntil = 0L;
        if (lastDamageTimeMs > 0L) {
            long interval = now - lastDamageTimeMs;
            if (interval >= MIN_DAMAGE_INTERVAL_MS && interval <= MAX_DAMAGE_INTERVAL_MS) {
                damageIntervals[nextDamageIntervalIndex] = interval;
                nextDamageIntervalIndex = (nextDamageIntervalIndex + 1) % DAMAGE_INTERVAL_CAPACITY;
                if (damageIntervalCount < DAMAGE_INTERVAL_CAPACITY) {
                    damageIntervalCount++;
                }
            } else {
                damageIntervalCount = 0;
                nextDamageIntervalIndex = 0;
            }
        }
        lastDamageTimeMs = now;
    }

    private long getAverageDamageInterval() {
        int samples = Math.min(damageIntervalCount, PREDICTION_SAMPLE_COUNT);
        if (samples <= 0) return 0L;
        long total = 0L;
        for (int i = 0; i < samples; i++) {
            int idx = nextDamageIntervalIndex - 1 - i;
            if (idx < 0) idx += DAMAGE_INTERVAL_CAPACITY;
            total += damageIntervals[idx];
        }
        return total / samples;
    }

    private long getEarlyWindowMs() {
        long window = (long) predictEarlyWindow.getInput();
        if (predictIncludePing.isToggled()) {
            long hitDelay = AttackPacketTimingTracker.INSTANCE.getAverageHitDelay();
            window += (hitDelay > 0L) ? hitDelay : (Utils.getPing() * 2L);
        }
        return window + 50L;
    }

    private int getEarlyWindowTicks() {
        return (int) Math.ceil(getEarlyWindowMs() / 50.0);
    }

    private boolean hasStableDamagePattern() {
        return damageIntervalCount >= PREDICTION_SAMPLE_COUNT && lastDamageTimeMs > 0L;
    }

    private void setPredictBlocking(boolean blocking) {
        predictBlocking = blocking;
        if (!blocking && isBlocking) {
            stopBlocking(true);
        }
    }

    private void resetPredictState() {
        damageObserved = false;
        damageIntervalCount = 0;
        nextDamageIntervalIndex = 0;
        lastDamageTimeMs = 0L;
        predictBlocking = false;
        predictHoldStarted = false;
        predictHoldUntil = 0L;
        manualReleaseTime = 0L;
    }

    private void tickManual(boolean conditionsMet) {
        if (!conditionsMet) {
            stopBlocking(true);
            return;
        }
        if (isBlocking && manualReleaseTime > 0 && System.currentTimeMillis() >= manualReleaseTime) {
            stopBlocking(true);
            manualReleaseTime = 0L;
        }
    }


    private void sendBlock() {
        if (!Utils.holdingSword()) return;
        mc.thePlayer.sendQueue.addToSendQueue(
                new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
    }

    private void sendUnblock() {
        mc.thePlayer.sendQueue.addToSendQueue(
                new net.minecraft.network.play.client.C07PacketPlayerDigging(
                        net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        net.minecraft.util.BlockPos.ORIGIN, net.minecraft.util.EnumFacing.DOWN));
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
        if ((int) mode.getInput() != MODE_LAG) return false;
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
        Mindless.lagHandler.requestLag(outboundLag);
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
        return !(entity instanceof EntityPlayer) || TargetFilter.shouldFilter(entity);
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
                && !allowingAlwaysInteraction
                && (int) mode.getInput() == MODE_VANILLA;
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
