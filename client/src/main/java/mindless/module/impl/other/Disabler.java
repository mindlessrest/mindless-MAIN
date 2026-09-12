package mindless.module.impl.other;

import mindless.event.AttackEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0CPacketInput;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.Queue;

public class Disabler extends Module {
    private final ButtonSetting aac1910;
    private final ButtonSetting grimSpectate;
    private final ButtonSetting miniblox;
    private final ButtonSetting spigotSpam;
    private final ButtonSetting vanillaSpeed;
    private final ButtonSetting verusCombat;
    private final ButtonSetting verusExperimental;
    private final ButtonSetting verusNoAction;
    private final ButtonSetting verusVoidTp;
    private final ButtonSetting verusScaffoldG;
    private final ButtonSetting vulcanScaffold;
    private final ButtonSetting waitUntilCombat;
    private final ButtonSetting waitForGround;
    private final SliderSetting vanillaPacketType;
    private final SliderSetting vanillaDistance;
    private final SliderSetting voidTpDelay;
    private final TextSetting spamPrefix;
    private final Queue<Packet<?>> grimPackets = new ArrayDeque<>();
    private boolean grimDelay;
    private boolean transaction;
    private boolean combatOccurred;
    private int cancelTeleports;
    private long lastVoidTp;
    private WorldClient lastWorld;

    public Disabler() {
        super("Disabler", "Uses protocol quirks to bypass selected anti-cheat checks.", category.other);
        this.registerSetting(aac1910 = new ButtonSetting("AAC 1.9.10", false));
        this.registerSetting(grimSpectate = new ButtonSetting("Grim Spectate", false));
        this.registerSetting(miniblox = new ButtonSetting("Miniblox", false));
        this.registerSetting(spigotSpam = new ButtonSetting("Spigot Spam", false));
        this.registerSetting(spamPrefix = new TextSetting("Spam prefix", "/skill", "/skill", 32));
        this.registerSetting(vanillaSpeed = new ButtonSetting("Vanilla Speed", false));
        this.registerSetting(vanillaPacketType = new SliderSetting("Vanilla packet", 0,
                new String[]{"Ground", "Position"}));
        this.registerSetting(vanillaDistance = new SliderSetting("Distance per packet", 10, 1, 20, 1));
        this.registerSetting(verusCombat = new ButtonSetting("Verus Combat", false));
        this.registerSetting(waitUntilCombat = new ButtonSetting("Wait until combat", true));
        this.registerSetting(verusExperimental = new ButtonSetting("Verus Experimental", false));
        this.registerSetting(verusNoAction = new ButtonSetting("Verus no sprint action", false));
        this.registerSetting(verusVoidTp = new ButtonSetting("Verus void TP", false));
        this.registerSetting(voidTpDelay = new SliderSetting("Void TP delay", " ms", 1000, 0, 30000, 50));
        this.registerSetting(waitForGround = new ButtonSetting("Void TP on ground", true));
        this.registerSetting(verusScaffoldG = new ButtonSetting("Verus Scaffold G", false));
        this.registerSetting(vulcanScaffold = new ButtonSetting("Vulcan Scaffold", false));
    }

    @Override
    public String getInfo() {
        int enabled = 0;
        for (ButtonSetting setting : new ButtonSetting[]{aac1910, grimSpectate, miniblox, spigotSpam,
                vanillaSpeed, verusCombat, verusExperimental, verusScaffoldG, vulcanScaffold}) {
            if (setting.isToggled()) enabled++;
        }
        return enabled + " mode" + (enabled == 1 ? "" : "s");
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        releaseGrimPackets();
        resetState();
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (!Utils.nullCheck()) return;
        Packet<?> packet = event.getPacket();

        if (spigotSpam.isToggled() && packet instanceof C01PacketChatMessage) {
            String prefix = spamPrefix.getText().trim();
            if (!prefix.isEmpty()) {
                PacketUtils.sendPacketNoEvent(new C01PacketChatMessage(
                        prefix + " " + ((C01PacketChatMessage) packet).getMessage()));
                event.setCanceled(true);
                return;
            }
        }

        if (verusNoAction.isToggled() && verusExperimental.isToggled()
                && packet instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) packet).getAction();
            if (action == C0BPacketEntityAction.Action.START_SPRINTING
                    || action == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                event.setCanceled(true);
                return;
            }
        }

        if (verusScaffoldG.isToggled() && packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement) packet;
            int direction = placement.getPlacedBlockDirection();
            if (direction >= 0 && direction <= 5) {
                PacketUtils.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(
                        placement.getPosition(), 6 + direction * 7, placement.getStack(),
                        placement.getPlacedBlockOffsetX(), placement.getPlacedBlockOffsetY(),
                        placement.getPlacedBlockOffsetZ()));
                event.setCanceled(true);
                return;
            }
        }

        if (verusExperimental.isToggled() && packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement) packet;
            int direction = placement.getPlacedBlockDirection();
            if (direction >= 0 && direction <= 5) {
                PacketUtils.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(
                        placement.getPosition(), direction, placement.getStack(), 0.5F, 0.5F, 0.5F));
                event.setCanceled(true);
                return;
            }
        }

        if (aac1910.isToggled() && packet instanceof C03PacketPlayer) {
            sendInput();
            PacketUtils.sendPacketNoEvent(copyMovement((C03PacketPlayer) packet,
                    ((C03PacketPlayer) packet).getPositionX(),
                    ((C03PacketPlayer) packet).getPositionY() + 7.0E-9D,
                    ((C03PacketPlayer) packet).getPositionZ()));
            event.setCanceled(true);
            return;
        }

        if (verusVoidTp.isToggled() && verusExperimental.isToggled()
                && packet instanceof C03PacketPlayer && mc.thePlayer.ticksExisted > 20
                && mc.thePlayer.posY > -64.0D
                && (!waitForGround.isToggled() || mc.thePlayer.onGround)
                && System.currentTimeMillis() - lastVoidTp >= (long) voidTpDelay.getInput()) {
            lastVoidTp = System.currentTimeMillis();
            PacketUtils.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                    mc.thePlayer.posX, -48.0D, mc.thePlayer.posZ,
                    mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, true));
            PacketUtils.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                    mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ,
                    mc.thePlayer.prevRotationYaw, mc.thePlayer.prevRotationPitch, false));
            PacketUtils.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                    mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                    mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
            cancelTeleports = 2;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!Utils.nullCheck()) return;
        Packet<?> packet = event.getPacket();

        if (verusVoidTp.isToggled() && verusExperimental.isToggled()
                && packet instanceof S08PacketPlayerPosLook && cancelTeleports > 0) {
            cancelTeleports--;
            event.setCanceled(true);
            return;
        }

        if (grimSpectate.isToggled()) {
            if (mc.thePlayer.ticksExisted < 20) {
                grimPackets.clear();
                grimDelay = false;
            } else if (packet instanceof S08PacketPlayerPosLook) {
                if (mc.thePlayer.capabilities.isFlying && !grimDelay) {
                    grimDelay = true;
                } else if (grimDelay) {
                    grimPackets.add(packet);
                    event.setCanceled(true);
                    return;
                }
            } else if (grimDelay && packet instanceof S32PacketConfirmTransaction) {
                grimPackets.add(packet);
                event.setCanceled(true);
                PacketUtils.sendPacketNoEvent(new C0FPacketConfirmTransaction(0, (short) 0, false));
                return;
            }
        }

        if (verusCombat.isToggled() && packet instanceof S32PacketConfirmTransaction) {
            if (mc.thePlayer.ticksExisted <= 20) {
                combatOccurred = false;
                return;
            }
            if (!waitUntilCombat.isToggled() || combatOccurred) {
                event.setCanceled(true);
                PacketUtils.sendPacketNoEvent(new C0FPacketConfirmTransaction(
                        transaction ? 1 : -1, (short) (transaction ? -1 : 1), transaction));
                transaction = !transaction;
                combatOccurred = false;
            }
        }
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        combatOccurred = true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !Utils.nullCheck()) return;
        if (mc.theWorld != lastWorld) {
            resetState();
            lastWorld = mc.theWorld;
        }

        if (miniblox.isToggled()) sendInput();

        if (vanillaSpeed.isToggled()) {
            double dx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
            double dy = mc.thePlayer.posY - mc.thePlayer.prevPosY;
            double dz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
            int packets = (int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / vanillaDistance.getInput());
            for (int i = 0; i < packets; i++) {
                if ((int) vanillaPacketType.getInput() == 0) {
                    PacketUtils.sendPacketNoEvent(new C03PacketPlayer(mc.thePlayer.onGround));
                } else {
                    PacketUtils.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(
                            mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ,
                            mc.thePlayer.onGround));
                }
            }
        }

        if (vulcanScaffold.isToggled() && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava() && !mc.thePlayer.isDead
                && !mc.thePlayer.isOnLadder() && !mc.thePlayer.capabilities.isFlying) {
            PacketUtils.sendPacketNoEvent(new C0BPacketEntityAction(
                    mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
            PacketUtils.sendPacketNoEvent(new C0BPacketEntityAction(
                    mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
            if (mc.thePlayer.ticksExisted % 9 == 0 && mc.thePlayer.onGround) {
                PacketUtils.sendPacketNoEvent(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.STOP_SNEAKING));
            }
        }
    }

    private void sendInput() {
        PacketUtils.sendPacketNoEvent(new C0CPacketInput(
                mc.thePlayer.moveStrafing, mc.thePlayer.moveForward,
                mc.gameSettings.keyBindJump.isKeyDown(), mc.thePlayer.isSneaking()));
    }

    private C03PacketPlayer copyMovement(C03PacketPlayer packet, double x, double y, double z) {
        if (packet.isMoving() && packet.getRotating()) {
            return new C03PacketPlayer.C06PacketPlayerPosLook(
                    x, y, z, packet.getYaw(), packet.getPitch(), packet.isOnGround());
        }
        if (packet.isMoving()) {
            return new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, packet.isOnGround());
        }
        if (packet.getRotating()) {
            return new C03PacketPlayer.C05PacketPlayerLook(
                    packet.getYaw(), packet.getPitch(), packet.isOnGround());
        }
        return new C03PacketPlayer(packet.isOnGround());
    }

    private void releaseGrimPackets() {
        while (!grimPackets.isEmpty()) {
            PacketUtils.receivePacketNoEvent(grimPackets.poll());
        }
    }

    private void resetState() {
        grimPackets.clear();
        grimDelay = false;
        transaction = false;
        combatOccurred = false;
        cancelTeleports = 0;
        lastVoidTp = 0L;
        lastWorld = mc == null ? null : mc.theWorld;
    }
}
