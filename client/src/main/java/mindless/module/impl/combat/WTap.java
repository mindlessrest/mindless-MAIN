package mindless.module.impl.combat;

import mindless.module.Module;
import mindless.module.impl.world.TargetFilter;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class WTap extends Module {
    private static final long CONFIRMATION_WINDOW_MS = 350L;

    private SliderSetting delayBetweenReset;
    private SliderSetting delayUntilReset;
    private SliderSetting chance;
    private ButtonSetting playersOnly;

    private long pendingResetAtMs;
    private long lastResetStartMs;
    private boolean waitingForSprintRestart;
    private boolean wasSprinting;
    private int armedTargetId = -1;
    private long armedAtMs;
    private int watchedTargetId = -1;
    private int previousTargetHurt = -1;
    private int previousTargetResistance = -1;

    public static boolean stopSprint = false;

    public WTap() {
        super("Sprint Reset", "Taps back after a confirmed hit for full knockback.", category.combat);
        this.liteModule = true;
        this.registerSetting(chance = new SliderSetting("Chance", "%", 100, 0, 100, 1));
        this.registerSetting(delayBetweenReset = new SliderSetting("Delay between reset", "ms", 300, 0, 1000, 10));
        this.registerSetting(delayUntilReset = new SliderSetting("Delay until reset", "ms", 150, 0, 1000, 10));
        this.registerSetting(playersOnly = new ButtonSetting("Players only", true));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        clearState();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck() || mc.thePlayer.isDead) {
            clearState();
            return;
        }

        long now = System.currentTimeMillis();
        boolean sprintingNow = mc.thePlayer.isSprinting();

        if (waitingForSprintRestart && sprintingNow && !wasSprinting) {
            lastResetStartMs = now;
            waitingForSprintRestart = false;
        }

        observeTargetConfirmation(now);

        if (pendingResetAtMs > 0L && now >= pendingResetAtMs) {
            stopSprint = true;
            pendingResetAtMs = 0L;
            waitingForSprintRestart = true;
        }

        wasSprinting = sprintingNow;
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!Utils.nullCheck() || event.entityPlayer != mc.thePlayer || !mc.thePlayer.isSprinting()) {
            return;
        }
        if (!isEligibleTarget(event.target) || pendingResetAtMs > 0L || armedTargetId >= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastResetStartMs > 0L && now - lastResetStartMs < (long) delayBetweenReset.getInput()) {
            return;
        }
        if (chance.getInput() != 100.0D && Math.random() >= chance.getInput() / 100.0D) {
            return;
        }

        EntityLivingBase target = (EntityLivingBase) event.target;
        armedTargetId = target.getEntityId();
        armedAtMs = now;
        if (watchedTargetId != armedTargetId) {
            watchedTargetId = armedTargetId;
            previousTargetHurt = target.hurtTime;
            previousTargetResistance = target.hurtResistantTime;
        }
    }

    @Override
    public void onDisable() {
        clearState();
    }

    private boolean isEligibleTarget(Entity target) {
        if (chance.getInput() == 0 || !(target instanceof EntityLivingBase)) {
            return false;
        }
        if (playersOnly.isToggled()) {
            if (!(target instanceof EntityPlayer) || TargetFilter.shouldFilter(target)) {
                return false;
            }
        }
        return ((EntityLivingBase) target).deathTime == 0;
    }

    private void observeTargetConfirmation(long now) {
        if (armedTargetId < 0) {
            return;
        }
        if (now - armedAtMs > CONFIRMATION_WINDOW_MS) {
            clearArmedTarget();
            return;
        }
        Entity entity = mc.theWorld.getEntityByID(armedTargetId);
        if (!(entity instanceof EntityLivingBase) || !entity.isEntityAlive()) {
            clearArmedTarget();
            return;
        }

        EntityLivingBase target = (EntityLivingBase) entity;
        int hurt = target.hurtTime;
        int resistance = target.hurtResistantTime;
        if (watchedTargetId != armedTargetId) {
            watchedTargetId = armedTargetId;
            previousTargetHurt = hurt;
            previousTargetResistance = resistance;
            return;
        }

        boolean hurtEdge = previousTargetHurt >= 0 && hurt > previousTargetHurt;
        boolean resistanceEdge = previousTargetResistance >= 0 && resistance > previousTargetResistance;
        boolean hurtPeak = previousTargetHurt < 10 && hurt >= 10;
        previousTargetHurt = hurt;
        previousTargetResistance = resistance;
        if (!hurtEdge && !resistanceEdge && !hurtPeak) {
            return;
        }

        clearArmedTarget();
        if (mc.thePlayer.isSprinting()) {
            pendingResetAtMs = now + (long) delayUntilReset.getInput();
        }
    }

    private void clearState() {
        pendingResetAtMs = 0L;
        lastResetStartMs = 0L;
        waitingForSprintRestart = false;
        wasSprinting = false;
        clearArmedTarget();
        watchedTargetId = -1;
        previousTargetHurt = -1;
        previousTargetResistance = -1;
        stopSprint = false;
    }

    private void clearArmedTarget() {
        armedTargetId = -1;
        armedAtMs = 0L;
    }
}
