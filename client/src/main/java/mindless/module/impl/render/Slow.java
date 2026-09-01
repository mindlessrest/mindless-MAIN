package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
public class Slow extends Module {
    private static Slow instance;
    public static boolean enabled;
    public static int durationTicks = 12;
    public static boolean syncHitEffects = true;
    public static float hitImpactPoint = 22.0F;
    public static int hitSyncMode;
    private static long visualSwingStart;
    private static boolean visualSwingActive;
    private static long visualSwingCycle;

    private final SliderSetting slowdown;
    private final ButtonSetting syncEffects;
    private final SliderSetting syncMode;
    private final SliderSetting impactPoint;

    public Slow() {
        super("Slow", category.render, 0);
        instance = this;
        registerSetting(slowdown = new SliderSetting("Slowdown", "%", 200, 100, 600, 10));
        registerSetting(syncEffects = new ButtonSetting("Sync hit effects", true));
        registerSetting(syncMode = new SliderSetting("Sync mode", 0, new String[]{"Per swing", "Every hit"}));
        registerSetting(impactPoint = new SliderSetting("Hit timing", "%", 22, 8, 50, 1));
    }

    @Override
    public void onEnable() {
        syncDuration();
        resetVisualSwing();
        enabled = true;
    }

    @Override
    public void onDisable() {
        enabled = false;
        resetVisualSwing();
    }

    @Override
    public void guiUpdate() {
        syncHitEffects = syncEffects.isToggled();
        hitSyncMode = (int) syncMode.getInput();
        hitImpactPoint = (float) impactPoint.getInput();
        if (isActive()) syncDuration();
    }

    private void syncDuration() {
        durationTicks = Math.max(6, (int) Math.round(6.0 * slowdown.getInput() / 100.0));
        syncHitEffects = syncEffects.isToggled();
        hitSyncMode = (int) syncMode.getInput();
        hitImpactPoint = (float) impactPoint.getInput();
    }
public static long getHitFeedbackCycle() {
        if (!isActive() || !syncHitEffects || hitSyncMode != 0) return -1L;
        if (!visualSwingActive) return visualSwingCycle + 1L;
        return currentVisualProgress() < impactProgress() ? visualSwingCycle : visualSwingCycle + 1L;
    }
public static boolean hasReachedHitFeedbackCycle(long cycle) {
        if (!isActive() || !syncHitEffects || hitSyncMode != 0) return true;
        if (visualSwingCycle > cycle) return true;
        return visualSwingCycle == cycle && visualSwingActive && currentVisualProgress() >= impactProgress();
    }
public static long getHitFeedbackDelayMillis() {
        if (!syncHitEffects) return 0L;

        double impact = impactProgress();
        if (isActive()) {
            long now = System.nanoTime();
            long durationNanos = Math.max(1L, durationTicks) * 50_000_000L;
            long impactNanos = (long) (durationNanos * impact);
            if (!visualSwingActive) return impactNanos / 1_000_000L;

            long elapsed = Math.max(0L, now - visualSwingStart);
            if (elapsed <= impactNanos) {
                return (impactNanos - elapsed) / 1_000_000L;
            }
            return (durationNanos - Math.min(elapsed, durationNanos) + impactNanos) / 1_000_000L;
        }

        if (Animations.isActive()) {
            int percentage = Math.max(0, Math.min(Animations.swingSpeed, 100));
            int animationTicks = (int) (6.0D + percentage / 100.0D * 14.0D);
            return (long) (animationTicks * 50.0D * impact);
        }
        return 0L;
    }
public static float getVisualSwingProgress(AbstractClientPlayer player, float vanillaProgress) {
        ItemStack held = player == null ? null : player.getHeldItem();
        if (!isActive() || held == null || !(held.getItem() instanceof ItemSword)) {
            resetVisualSwing();
            return vanillaProgress;
        }

        long now = System.nanoTime();
        if (!visualSwingActive) {
            if (!player.isSwingInProgress && vanillaProgress <= 0.0F) return vanillaProgress;
            visualSwingActive = true;
            visualSwingStart = now;
            visualSwingCycle++;
        }

        long durationNanos = Math.max(1L, durationTicks) * 50_000_000L;
        float progress = (float) ((now - visualSwingStart) / (double) durationNanos);
        if (progress < 1.0F) return Math.max(0.0F, progress);

        visualSwingActive = false;
        if (player.isSwingInProgress) {
            visualSwingActive = true;
            visualSwingStart = now;
            visualSwingCycle++;
        }
        return 0.0F;
    }

    private static double impactProgress() {
        return Math.max(0.08D, Math.min(0.50D, hitImpactPoint / 100.0D));
    }

    private static float currentVisualProgress() {
        if (!visualSwingActive) return 0.0F;
        long durationNanos = Math.max(1L, durationTicks) * 50_000_000L;
        return Math.max(0.0F, (float) ((System.nanoTime() - visualSwingStart) / (double) durationNanos));
    }

    private static void resetVisualSwing() {
        visualSwingActive = false;
        visualSwingStart = 0L;
    }

    public static boolean isActive() {
        return enabled || instance != null && instance.isEnabled();
    }

    @Override
    public String getInfo() {
        return (int) slowdown.getInput() + "%";
    }
}
