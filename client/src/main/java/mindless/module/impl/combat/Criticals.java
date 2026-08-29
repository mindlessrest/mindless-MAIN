package mindless.module.impl.combat;

import mindless.event.AttackEvent;
import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Criticals extends Module {
    private static final String[] MODES = {"Packet", "Mini Jump"};
    private final SliderSetting mode;

    public Criticals() {
        super("Criticals", category.combat);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @SubscribeEvent
    public void onAttack(AttackEvent e) {
        if (!Utils.nullCheck()) return;
        if (!(e.target instanceof EntityLivingBase)) return;
        if (!mc.thePlayer.onGround) return;
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) return;
        if (mc.thePlayer.isOnLadder() || mc.thePlayer.isRiding()) return;

        switch ((int) mode.getInput()) {
            case 0: // Packet
                double x = mc.thePlayer.posX;
                double y = mc.thePlayer.posY;
                double z = mc.thePlayer.posZ;
                mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.0625, z, false));
                mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.1E-5, z, false));
                mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                break;
            case 1: // Mini Jump
                mc.thePlayer.motionY = 0.1;
                mc.thePlayer.fallDistance = 0.1f;
                mc.thePlayer.onGround = false;
                break;
        }
    }
}
