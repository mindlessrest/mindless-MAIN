package mindless.module.impl.movement;

import mindless.event.PreUpdateEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.mixin.impl.accessor.IAccessorMinecraft;
import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Stasis extends Module {
    private SliderSetting pulse;
    private SliderSetting pulseDelay;

    private boolean allowNextC03;
    private int defaultTicks;
    private int pulseTicks;
    private float originalTimerSpeed;
    private boolean controlsTimer;
    private boolean pulseActive;
    private boolean motionStored;
    private boolean damageSuspended;
    private double storedMotionX;
    private double storedMotionY;
    private double storedMotionZ;

    public Stasis() {
        super("Stasis", category.movement);
        this.registerSetting(pulse = new SliderSetting("Pulse", " tick", "\u00a7cNever", 60.0, 0.0, 200.0, 2.0));
        this.registerSetting(pulseDelay = new SliderSetting("Pulse delay", " tick", 10.0, 1.0, 150.0, 1.0));
    }

    @Override
    public void guiUpdate() {
        pulseDelay.setVisible(pulse.getInput() > 0.0D, this);
    }

    @Override
    public void onEnable() {
        allowNextC03 = false;
        defaultTicks = 0;
        pulseTicks = 0;
        originalTimerSpeed = getTimerSpeed();
        controlsTimer = false;
        pulseActive = false;
        motionStored = false;
        damageSuspended = false;

        if (Utils.nullCheck()) {
            storeCurrentMotion();
        }
    }

    @Override
    public void onDisable() {
        restoreTimerSpeed();

        if (Utils.nullCheck() && !pulseActive && motionStored) {
            restoreStoredMotion();
        }

        defaultTicks = 0;
        pulseTicks = 0;
        pulseActive = false;
        motionStored = false;
        damageSuspended = false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }

        if (!Utils.nullCheck()) {
            restoreTimerSpeed();
            defaultTicks = 0;
            pulseTicks = 0;
            pulseActive = false;
            motionStored = false;
            damageSuspended = false;
            return;
        }

        if (mc.thePlayer.hurtTime != 0) {
            restoreTimerSpeed();
            defaultTicks = 0;
            pulseTicks = 0;
            pulseActive = false;
            motionStored = false;
            damageSuspended = true;
            return;
        }

        if (damageSuspended) {
            damageSuspended = false;
            if (!motionStored) {
                storeCurrentMotion();
            }
        }

        int defaultDuration = (int) Math.round(pulse.getInput());
        if (defaultDuration <= 0) {
            if (pulseActive) {
                storeCurrentMotion();
                pulseActive = false;
            }

            restoreTimerSpeed();
            defaultTicks = 0;
            pulseTicks = 0;
            return;
        }

        controlsTimer = true;
        int pulseDuration = Math.max(1, (int) Math.round(pulseDelay.getInput()));

        if (pulseActive) {
            pulseTicks++;
            if (pulseTicks >= pulseDuration) {
                finishPulse();
            }
            else {
                setPulseTimerSpeed(pulseTicks + 1, pulseDuration);
            }
            return;
        }

        if (defaultTicks < defaultDuration) {
            setTimerSpeed(originalTimerSpeed);
            defaultTicks++;
            return;
        }

        startPulse(pulseDuration);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreUpdate(PreUpdateEvent e) {
        if (!shouldFreezeLocalMovement()) {
            return;
        }

        if (!motionStored) {
            storeCurrentMotion();
        }
        else {
            // Frozen ticks do not run local movement physics. Keep the exact
            // velocity that the next server-visible movement tick starts with.
            restoreStoredMotion();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent e) {
        if (e.entityLiving == mc.thePlayer && shouldFreezeLocalMovement()) {
            // Preserve velocity by preventing local movement physics from
            // consuming it while the pulse is closed.
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (!(e.getPacket() instanceof C03PacketPlayer)) {
            return;
        }

        C03PacketPlayer packet = (C03PacketPlayer) e.getPacket();
        if (allowNextC03) {
            allowNextC03 = false;
            return;
        }
        if (!shouldFreezeLocalMovement()) {
            return;
        }

        // Keep the server's item-use clock alive without releasing the frozen position.
        // A plain C03 (or C05 look packet) carries no coordinates, unlike C04/C06.
        if (mc.thePlayer.isUsingItem() && !packet.isMoving()) {
            return;
        }

        if (!(packet instanceof C03PacketPlayer.C05PacketPlayerLook)) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.getPacket() instanceof S08PacketPlayerPosLook) {
            allowNextC03 = true;
        }
    }

    private float getTimerSpeed() {
        return ((IAccessorMinecraft) mc).getTimer().timerSpeed;
    }

    private void setTimerSpeed(float speed) {
        ((IAccessorMinecraft) mc).getTimer().timerSpeed = speed;
    }

    private void restoreTimerSpeed() {
        if (!controlsTimer) {
            return;
        }

        setTimerSpeed(originalTimerSpeed);
        controlsTimer = false;
    }

    private void startPulse(int pulseDuration) {
        pulseActive = true;
        pulseTicks = 0;

        if (!motionStored) {
            storeCurrentMotion();
        }
        restoreStoredMotion();
        setPulseTimerSpeed(1, pulseDuration);
    }

    private void finishPulse() {
        storeCurrentMotion();
        pulseActive = false;
        defaultTicks = 0;
        pulseTicks = 0;
        setTimerSpeed(originalTimerSpeed);
    }

    private void setPulseTimerSpeed(int pulseStep, int pulseDuration) {
        int peakTick = (pulseDuration + 1) / 2;
        double progress;
        if (pulseStep <= peakTick) {
            progress = (double) pulseStep / peakTick;
        }
        else {
            progress = (double) (pulseDuration - pulseStep) / (pulseDuration - peakTick);
        }

        float heartbeat = (float) Math.sin(Math.PI * 0.5D * progress);
        setTimerSpeed(originalTimerSpeed + (1.0F - originalTimerSpeed) * heartbeat);
    }

    public boolean shouldFreezeLocalMovement() {
        return isEnabled()
                && Utils.nullCheck()
                && mc.thePlayer.hurtTime == 0
                && !pulseActive;
    }

    private void storeCurrentMotion() {
        if (!Utils.nullCheck()) {
            return;
        }

        storedMotionX = mc.thePlayer.motionX;
        storedMotionY = mc.thePlayer.motionY;
        storedMotionZ = mc.thePlayer.motionZ;
        motionStored = true;
    }

    private void restoreStoredMotion() {
        if (!Utils.nullCheck() || !motionStored) {
            return;
        }

        mc.thePlayer.motionX = storedMotionX;
        mc.thePlayer.motionY = storedMotionY;
        mc.thePlayer.motionZ = storedMotionZ;
    }
}
