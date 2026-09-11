package mindless.module.impl.player;

import mindless.Mindless;
import mindless.event.GameTickEvent;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Set;

public class FakeLag extends Module {
    private static final String[] MODE_LABELS = {"Inbound", "Outbound", "Both"};
    private final SliderSetting mode;
    private final SliderSetting packetDelaySlider;
    private int appliedMode = -1;
    private long appliedDelayMs = -1;
    private DelayLease activeLease;

    public FakeLag() {
        super("Fake Lag", "Adds delay to the packets you send.", category.combat);
        this.registerSetting(mode = new SliderSetting("Mode", 1, MODE_LABELS));
        this.registerSetting(packetDelaySlider = new SliderSetting("Packet delay", "ms", 0.0, 0.0, 1500.0, 20.0));
    }

    @Override
    public String getInfo() { return (int) packetDelaySlider.getInput() + "ms"; }

    @Override
    public void guiUpdate() {
        if (!isEnabled()) return;
        if (packetDelaySlider.getInput() <= 0) { disable(); return; }
        int m = (int) mode.getInput();
        long d = (long) packetDelaySlider.getInput();
        if (m != appliedMode || d != appliedDelayMs) {
            appliedMode = m; appliedDelayMs = d; rebindLease();
        }
    }

    private void rebindLease() {
        if (activeLease != null) activeLease.release();
        activeLease = Mindless.packetDelayService.acquire(DelayRequest.perPacketMillis(
                "Fake Lag", lagDirectionsForMode(), appliedDelayMs));
    }

    private Set<EnumLagDirection> lagDirectionsForMode() {
        switch ((int) mode.getInput()) {
            case 0: return EnumLagDirection.ONLY_INBOUND;
            case 2: return EnumLagDirection.BIDIRECTIONAL;
            default: return EnumLagDirection.ONLY_OUTBOUND;
        }
    }

    @Override
    public void onEnable() {
        if (mc.isSingleplayer()) { Utils.sendMessage("&cFake lag cannot be enabled in singleplayer."); this.disable(); return; }
        if (ModuleManager.blink != null && ModuleManager.blink.isEnabled()) { Utils.sendMessage("&cCannot use fake lag with blink!"); this.disable(); return; }
        appliedMode = (int) mode.getInput();
        appliedDelayMs = (long) packetDelaySlider.getInput();
        rebindLease();
    }

    @Override
    public void onDisable() {
        if (activeLease != null) { activeLease.release(); activeLease = null; }
        appliedMode = -1; appliedDelayMs = -1;
    }

    @SubscribeEvent
    public void onGameTick(GameTickEvent e) {
        if (!isEnabled()) return;
        if (!Utils.nullCheck() || mc.theWorld == null) { this.disable(); return; }
        Mindless.packetDelayService.drainExpired();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.theWorld == null && isEnabled()) this.disable();
    }

}
