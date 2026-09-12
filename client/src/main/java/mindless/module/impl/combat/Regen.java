package mindless.module.impl.combat;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AccessorBridge;
import mindless.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.potion.Potion;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Regen extends Module {
    private final SliderSetting health;
    private final SliderSetting speed;
    private final SliderSetting timerSpeed;
    private final SliderSetting packetType;
    private final ButtonSetting notInAir;
    private final ButtonSetting notDuringMove;
    private final ButtonSetting notDuringRegeneration;
    private final ButtonSetting doNotCauseHunger;
    private boolean timerApplied;

    public Regen() {
        super("Regen", "Regenerates your health much faster.", category.combat);
        this.registerSetting(health = new SliderSetting("Health", 16, 0, 20, 1));
        this.registerSetting(speed = new SliderSetting("Speed", 20, 1, 35, 1));
        this.registerSetting(timerSpeed = new SliderSetting("Timer", 0.5, 0.1, 10, 0.1));
        this.registerSetting(packetType = new SliderSetting("Packet type", 0,
                new String[]{"Full", "Position", "Look", "Ground"}));
        this.registerSetting(notInAir = new ButtonSetting("Not in air", true));
        this.registerSetting(notDuringMove = new ButtonSetting("Not during move", false));
        this.registerSetting(notDuringRegeneration = new ButtonSetting("Not during regeneration", false));
        this.registerSetting(doNotCauseHunger = new ButtonSetting("Do not cause hunger", false));
    }

    @Override
    public String getInfo() {
        return (int) speed.getInput() + " pkt";
    }

    @Override
    public void onDisable() {
        if (mc != null && timerApplied) AccessorBridge.Minecraft_getTimer(mc).timerSpeed = 1.0F;
        timerApplied = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !Utils.nullCheck()) return;
        if (timerApplied) {
            AccessorBridge.Minecraft_getTimer(mc).timerSpeed = 1.0F;
            timerApplied = false;
        }
        if (mc.playerController == null || !mc.playerController.gameIsSurvivalOrAdventure()) return;
        if (!mc.thePlayer.isEntityAlive() || mc.thePlayer.getHealth() > health.getInput()) return;
        if (notInAir.isToggled() && !mc.thePlayer.onGround) return;
        if (notDuringMove.isToggled() && Utils.isMoving()) return;
        if (notDuringRegeneration.isToggled() && mc.thePlayer.isPotionActive(Potion.regeneration)) return;
        if (doNotCauseHunger.isToggled() && mc.thePlayer.getFoodStats().getFoodLevel() < 20) return;

        AccessorBridge.Minecraft_getTimer(mc).timerSpeed = (float) timerSpeed.getInput();
        timerApplied = true;
        for (int i = 0; i < (int) speed.getInput(); i++) {
            mc.thePlayer.sendQueue.addToSendQueue(createPacket());
        }
    }

    private C03PacketPlayer createPacket() {
        boolean onGround = mc.thePlayer.onGround;
        switch ((int) packetType.getInput()) {
            case 1:
                return new C03PacketPlayer.C04PacketPlayerPosition(
                        mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, onGround);
            case 2:
                return new C03PacketPlayer.C05PacketPlayerLook(
                        mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, onGround);
            case 3:
                return new C03PacketPlayer(onGround);
            default:
                return new C03PacketPlayer.C06PacketPlayerPosLook(
                        mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                        mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, onGround);
        }
    }
}
