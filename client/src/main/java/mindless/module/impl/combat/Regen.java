package mindless.module.impl.combat;

import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Regen extends Module {
    private final SliderSetting speed;
    private final SliderSetting health;

    public Regen() {
        super("Regen", category.combat);
        this.registerSetting(speed = new SliderSetting("Packets/tick", 50, 1, 100, 1));
        this.registerSetting(health = new SliderSetting("Health trigger", 18, 1, 20, 1));
    }

    @Override
    public String getInfo() {
        return (int) speed.getInput() + " pkt";
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (!Utils.nullCheck()) return;
        if (mc.thePlayer.getHealth() <= 0) return;
        if (mc.thePlayer.getHealth() >= health.getInput()) return;
        if (mc.thePlayer.getFoodStats().getFoodLevel() < 1) return;
        if (!mc.thePlayer.onGround) return;

        int packets = (int) speed.getInput();
        for (int i = 0; i < packets; i++) {
            mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer(mc.thePlayer.onGround));
        }
    }
}
