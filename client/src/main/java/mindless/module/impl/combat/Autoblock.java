package mindless.module.impl.combat;

import mindless.event.PostUpdateEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class Autoblock extends Module {
    private static final int PREDICT = 1;
    private final String[] modes = new String[]{"Normal", "Predict"};
    private final SliderSetting mode;
    private final SliderSetting hurtTime;
    private boolean blocking;
    private boolean reblockPending;
    private static final int DAMAGE_SAMPLE_COUNT = 3;
    private final long[] damageIntervals = new long[DAMAGE_SAMPLE_COUNT];
    private int damageIntervalCount;
    private int damageIntervalIndex;
    private int previousHurtTime;
    private long lastDamageTime;

    public Autoblock() {
        super("Autoblock", "Blocks with your sword while fighting a nearby target.", category.combat, 0);
        this.registerSetting(mode = new SliderSetting("Mode", PREDICT, modes));
        this.registerSetting(hurtTime = new SliderSetting("Hurt time", " tick", 3.0, 0.0, 10.0, 1.0));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        blocking = false;
        reblockPending = false;
        resetPrediction();
        ReflectionUtils.setItemInUse(false);
    }

    @Override
    public void onDisable() {
        stopBlocking();
        resetPrediction();
    }

    public boolean isActive() {
        return isEnabled() && blocking;
    }

    public boolean isOperational() {
        return isEnabled() && (ModuleManager.myauBlock == null || !ModuleManager.myauBlock.isOperational());
    }

    public boolean allowsNoSlow() {
        return false;
    }

    @Override
    public String getInfo() {
        return modes[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        hurtTime.setVisible((int) mode.getInput() == PREDICT, this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!isOperational()) {
            resetBlocking();
            return;
        }
        updateDamagePrediction();
        if (canBlock()) {
            if (!reblockPending) {
                startBlocking();
            }
        }
        else {
            resetBlocking();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent event) {
        if (!blocking || !(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        stopBlocking();
        reblockPending = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        if (!reblockPending) {
            return;
        }
        reblockPending = false;
        if (canBlock()) {
            startBlocking();
        }
    }

    private boolean canBlock() {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.isDead || !Utils.holdingSword()) {
            return false;
        }
        boolean predict = (int) mode.getInput() == PREDICT;
        if (predict && mc.thePlayer.hurtTime > (int) hurtTime.getInput()) {
            return false;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        }
        EntityPlayer target = CombatTargeting.findTarget(16.0, true);
        boolean auraAttacking = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && !ModuleManager.killAura.isRequireMouseDown();
        return target != null
                && (Mouse.isButtonDown(0) || auraAttacking)
                && (!predict || predictsIncomingHit(target));
    }

    private boolean predictsIncomingHit(EntityPlayer target) {
        if (mc.thePlayer.getDistanceSqToEntity(target) > 13.69 || !isFacingPlayer(target)) {
            return false;
        }
        if (target.isSwingInProgress && target.swingProgressInt <= 3) {
            return true;
        }
        if (damageIntervalCount < 2 || lastDamageTime == 0L) {
            return false;
        }
        long total = 0L;
        for (int i = 0; i < damageIntervalCount; i++) {
            total += damageIntervals[i];
        }
        long expected = lastDamageTime + total / damageIntervalCount;
        long earlyWindow = Math.max(75L, Math.min(175L, Utils.getPing() + 50L));
        long now = System.currentTimeMillis();
        return now >= expected - earlyWindow && now <= expected + 100L;
    }

    private boolean isFacingPlayer(EntityPlayer target) {
        double x = mc.thePlayer.posX - target.posX;
        double z = mc.thePlayer.posZ - target.posZ;
        float yaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI) - 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - target.rotationYaw)) <= 70.0F;
    }

    private void updateDamagePrediction() {
        if (!Utils.nullCheck()) {
            resetPrediction();
            return;
        }
        int currentHurtTime = mc.thePlayer.hurtTime;
        if (currentHurtTime > previousHurtTime) {
            long now = System.currentTimeMillis();
            if (lastDamageTime != 0L) {
                long interval = now - lastDamageTime;
                if (interval >= 250L && interval <= 1500L) {
                    damageIntervals[damageIntervalIndex] = interval;
                    damageIntervalIndex = (damageIntervalIndex + 1) % DAMAGE_SAMPLE_COUNT;
                    if (damageIntervalCount < DAMAGE_SAMPLE_COUNT) {
                        damageIntervalCount++;
                    }
                }
                else {
                    damageIntervalCount = 0;
                    damageIntervalIndex = 0;
                }
            }
            lastDamageTime = now;
        }
        previousHurtTime = currentHurtTime;
    }

    private void resetPrediction() {
        damageIntervalCount = 0;
        damageIntervalIndex = 0;
        previousHurtTime = 0;
        lastDamageTime = 0L;
    }

    private void startBlocking() {
        if (blocking || !Utils.holdingSword()) {
            return;
        }
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, true);
        KeyBinding.onTick(keyCode);
        blocking = true;
        ReflectionUtils.setItemInUse(true);
    }

    private void stopBlocking() {
        if (!blocking || mc.gameSettings == null) {
            return;
        }
        boolean physicalUse = Mouse.isButtonDown(1) && mc.currentScreen == null;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), physicalUse);
        blocking = false;
        ReflectionUtils.setItemInUse(physicalUse && Utils.nullCheck() && Utils.holdingSword());
    }

    private void resetBlocking() {
        stopBlocking();
        reblockPending = false;
    }
}
