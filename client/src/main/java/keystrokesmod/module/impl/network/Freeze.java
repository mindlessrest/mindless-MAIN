package keystrokesmod.module.impl.network;

import keystrokesmod.Raven;
import keystrokesmod.event.GameTickEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.lag.api.EnumLagDirection;
import keystrokesmod.lag.api.LagRequest;
import keystrokesmod.lag.timeout.ModuleBackedTimeout;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.player.Blink;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.*;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Freeze extends Module {
    private final SliderSetting autoDisable;
    public final ButtonSetting activateOnHit;
    public final ButtonSetting disableOnBedBreak;
    public final ButtonSetting disableOnInteract;
    public final ButtonSetting showFreezeTicks;
    public final ButtonSetting showKnockbackDirection;
    public final ButtonSetting updateChatMessages;
    public final ButtonSetting updateBlockChanges;
    public final ButtonSetting updatePlayerHealth;
    public final ButtonSetting updatePlayerPositions;
    public final ButtonSetting updateScoreboard;
    public final ButtonSetting updateSoundEffects;

    private LagRequest inboundLagRequest;
    private boolean armed;
    private long freezeStartedAt;

    public Freeze() {
        super("Freeze", category.network);
        this.registerSetting(new DescriptionSetting("Freeze inbound packets."));
        this.registerSetting(autoDisable = new SliderSetting("Auto-disable", " second", true, 2.0, 0.0, 10.0, 0.2));
        this.registerSetting(activateOnHit = new ButtonSetting("Activate on hit", false));
        this.registerSetting(disableOnBedBreak = new ButtonSetting("Disable on bed break", false));
        this.registerSetting(disableOnInteract = new ButtonSetting("Disable on interact", false));
        this.registerSetting(showFreezeTicks = new ButtonSetting("Show freeze ticks", true));
        this.registerSetting(showKnockbackDirection = new ButtonSetting("Show knockback direction", true));
        this.registerSetting(updateChatMessages = new ButtonSetting("Update chat messages", true));
        this.registerSetting(updateBlockChanges = new ButtonSetting("Update block changes", true));
        this.registerSetting(updatePlayerHealth = new ButtonSetting("Update player health", true));
        this.registerSetting(updatePlayerPositions = new ButtonSetting("Update player positions", true));
        this.registerSetting(updateScoreboard = new ButtonSetting("Update scoreboard", true));
        this.registerSetting(updateSoundEffects = new ButtonSetting("Update sound effects", true));
    }

    @Override
    public void onEnable() {
        if (blinksInbound()) {
            Utils.sendMessage("&cConflicted modules, disable blink or use outbound-only.");
            disable();
            return;
        }
        inboundLagRequest = null;
        freezeStartedAt = 0L;
        armed = activateOnHit.isToggled();
        if (!armed) startInboundLag();
    }

    @Override
    public void onDisable() {
        flushInboundLagAndClear();
        armed = false;
        freezeStartedAt = 0L;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacketHigh(ReceivePacketEvent e) {
        if (!isEnabled() || e.isCanceled() || e.getPacket() == null) return;
        Packet<?> packet = e.getPacket();

        if (packet instanceof S40PacketDisconnect || packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn) {
            disable(); return;
        }
        if (armed && isSelfVelocityPacket(packet)) { startInboundLag(); return; }
        if (!isInboundSessionActive()) return;
        if (disableOnBedBreak.isToggled() && isBedBreak(packet)) { disable(); return; }
        if (shouldUpdateImmediately(packet)) {
            PacketUtils.receivePacketNoEvent(packet);
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacketHigh(SendPacketEvent e) {
        if (!isEnabled() || !isInboundSessionActive() || !disableOnInteract.isToggled() || e.isCanceled() || e.getPacket() == null) return;
        Packet<?> packet = e.getPacket();
        if (packet instanceof C02PacketUseEntity || packet instanceof C07PacketPlayerDigging || packet instanceof C08PacketPlayerBlockPlacement) disable();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGameTick(GameTickEvent e) {
        if (!isEnabled()) return;
        if (!Utils.nullCheck() || mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) { disable(); return; }
        if (!isInboundSessionActive()) return;
        long max = Math.round(autoDisable.getInput() * 1000.0D);
        if (max > 0L && System.currentTimeMillis() - freezeStartedAt >= max) disable();
    }

    private void startInboundLag() {
        if (isInboundSessionActive()) return;
        inboundLagRequest = new LagRequest(EnumLagDirection.ONLY_INBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(inboundLagRequest);
        freezeStartedAt = System.currentTimeMillis();
        armed = false;
    }

    private boolean isInboundSessionActive() {
        return inboundLagRequest != null && !inboundLagRequest.getTimeout().isTimedOut();
    }

    private void flushInboundLagAndClear() {
        if (inboundLagRequest != null) {
            inboundLagRequest.getTimeout().forceTimeOut();
            inboundLagRequest = null;
        }
    }

    private boolean isSelfVelocityPacket(Packet<?> packet) {
        if (!(packet instanceof S12PacketEntityVelocity) || mc.thePlayer == null) return false;
        return ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId();
    }

    private boolean shouldUpdateImmediately(Packet<?> packet) {
        if (packet instanceof S00PacketKeepAlive || packet instanceof S32PacketConfirmTransaction || packet instanceof S3FPacketCustomPayload) return true;
        if (updateChatMessages.isToggled() && packet instanceof S02PacketChat) return true;
        if (updateBlockChanges.isToggled() && isBlockUpdatePacket(packet)) return true;
        if (updatePlayerHealth.isToggled() && isHealthUpdatePacket(packet)) return true;
        if (updatePlayerPositions.isToggled() && isOtherPlayerPositionPacket(packet)) return true;
        if (updateScoreboard.isToggled() && isScoreboardPacket(packet)) return true;
        return updateSoundEffects.isToggled() && isSoundPacket(packet);
    }

    private boolean isBlockUpdatePacket(Packet<?> packet) {
        return packet instanceof S21PacketChunkData || packet instanceof S22PacketMultiBlockChange
                || packet instanceof S23PacketBlockChange || packet instanceof S24PacketBlockAction
                || packet instanceof S25PacketBlockBreakAnim || packet instanceof S26PacketMapChunkBulk
                || packet instanceof S35PacketUpdateTileEntity;
    }

    private boolean isHealthUpdatePacket(Packet<?> packet) {
        return packet instanceof S06PacketUpdateHealth || packet instanceof S19PacketEntityStatus;
    }

    private boolean isScoreboardPacket(Packet<?> packet) {
        return packet instanceof S3BPacketScoreboardObjective || packet instanceof S3CPacketUpdateScore
                || packet instanceof S3DPacketDisplayScoreboard || packet instanceof S3EPacketTeams;
    }

    private boolean isSoundPacket(Packet<?> packet) {
        return packet instanceof S28PacketEffect || packet instanceof S29PacketSoundEffect;
    }

    private boolean isOtherPlayerPositionPacket(Packet<?> packet) {
        if (mc.theWorld == null || mc.thePlayer == null) return false;
        if (packet instanceof S0CPacketSpawnPlayer) return ((S0CPacketSpawnPlayer) packet).getEntityID() != mc.thePlayer.getEntityId();
        Entity entity;
        if (packet instanceof S14PacketEntity) {
            entity = ((S14PacketEntity) packet).getEntity(mc.theWorld);
            return entity instanceof EntityPlayer && entity != mc.thePlayer;
        }
        if (packet instanceof S18PacketEntityTeleport) {
            entity = mc.theWorld.getEntityByID(((S18PacketEntityTeleport) packet).getEntityId());
            return entity instanceof EntityPlayer && entity != mc.thePlayer;
        }
        if (packet instanceof S19PacketEntityHeadLook) {
            entity = ((S19PacketEntityHeadLook) packet).getEntity(mc.theWorld);
            return entity instanceof EntityPlayer && entity != mc.thePlayer;
        }
        return false;
    }

    private boolean isBedBreak(Packet<?> packet) {
        if (mc.theWorld == null) return false;
        if (packet instanceof S23PacketBlockChange) {
            S23PacketBlockChange bc = (S23PacketBlockChange) packet;
            return mc.theWorld.getBlockState(bc.getBlockPosition()).getBlock() == Blocks.bed && bc.getBlockState().getBlock() != Blocks.bed;
        }
        if (packet instanceof S22PacketMultiBlockChange) {
            for (S22PacketMultiBlockChange.BlockUpdateData u : ((S22PacketMultiBlockChange) packet).getChangedBlocks()) {
                if (mc.theWorld.getBlockState(u.getPos()).getBlock() == Blocks.bed && u.getBlockState().getBlock() != Blocks.bed) return true;
            }
        }
        return false;
    }

    private static boolean blinksInbound() {
        Blink blink = ModuleManager.blink;
        return blink != null && blink.isEnabled() && blink.delaysInboundPackets();
    }

    @Override
    public String getInfo() {
        if (!showFreezeTicks.isToggled() || !isInboundSessionActive()) return "";
        return String.valueOf(Math.max(0L, System.currentTimeMillis() - freezeStartedAt) / 50L);
    }
}
