package mindless.module.impl.player;

import mindless.Mindless;
import mindless.event.PreUpdateEvent;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.awt.*;
import java.util.Set;

public class Blink extends Module {
    private static final String[] MODE_LABELS = {"Inbound", "Outbound", "Both"};

    private SliderSetting mode;
    private SliderSetting disableAfterMs;
    private SliderSetting releasePacketEvery;
    private ButtonSetting maxDuration;
    private ButtonSetting disableOnAttack;
    private ButtonSetting initialPosition;

    private Vec3 pos;
    private final int color = new Color(0, 255, 0, 120).getRGB();
    private int blinkTicks;
    private long enableTime;
    private DelayLease delayLease;
    private int appliedMode = -1;

    public Blink() {
        super("Blink", "Holds packets, then releases them at once.", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 1, MODE_LABELS));
        this.registerSetting(maxDuration = new ButtonSetting("Max duration", false));
        this.registerSetting(disableAfterMs = new SliderSetting("Disable after", "ms", 500.0, 50.0, 20000.0, 50.0));
        this.registerSetting(releasePacketEvery = new SliderSetting("Release packet every", " tick", true, -1.0, 1.0, 20.0, 1.0));
        this.registerSetting(new DescriptionSetting("Disable on"));
        this.registerSetting(disableOnAttack = new ButtonSetting("Attack", false));
        this.registerSetting(initialPosition = new ButtonSetting("Show initial position", true));
    }

    @Override
    public void guiUpdate() {
        disableAfterMs.setVisible(maxDuration.isToggled(), this);
    }

    @Override
    public void onEnable() {
        if (!Utils.nullCheck()) return;
        pos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        blinkTicks = 0;
        enableTime = System.currentTimeMillis();
        rebindLease();
    }

    private void rebindLease() {
        if (delayLease != null) delayLease.release();
        appliedMode = (int) mode.getInput();
        delayLease = Mindless.packetDelayService.acquire(DelayRequest.fixedWindow(
                "Blink", lagDirectionsForMode(), mindless.lag.api.InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
    }

    private Set<EnumLagDirection> lagDirectionsForMode() {
        switch ((int) mode.getInput()) {
            case 0: return EnumLagDirection.ONLY_INBOUND;
            case 2: return EnumLagDirection.BIDIRECTIONAL;
            default: return EnumLagDirection.ONLY_OUTBOUND;
        }
    }

    public boolean delaysInboundPackets() {
        int m = (int) mode.getInput();
        return m == 0 || m == 2;
    }

    @Override
    public void onDisable() {
        if (delayLease != null) {
            delayLease.release();
            delayLease = null;
        }
        appliedMode = -1;
    }

    @Override
    public String getInfo() { return String.valueOf(blinkTicks); }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        ++blinkTicks;
        if (delayLease == null || appliedMode != (int) mode.getInput()) rebindLease();
        if (maxDuration.isToggled() && System.currentTimeMillis() - enableTime >= (int) disableAfterMs.getInput()) {
            this.disable(); return;
        }
        int releaseInterval = (int) releasePacketEvery.getInput();
        if (releaseInterval <= 0 || blinkTicks % releaseInterval != 0) return;
        Set<EnumLagDirection> directions = lagDirectionsForMode();
        if (delayLease == null) return;
        if (directions.contains(EnumLagDirection.INBOUND)) delayLease.releaseNext(EnumLagDirection.INBOUND);
        if (directions.contains(EnumLagDirection.OUTBOUND)) delayLease.releaseNext(EnumLagDirection.OUTBOUND);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || !disableOnAttack.isToggled() || !Utils.nullCheck()) return;
        if (event.entityPlayer != mc.thePlayer) return;
        this.disable();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || pos == null || !initialPosition.isToggled()) return;
        RenderUtils.drawPlayerBoundingBox(pos, color);
    }
}
