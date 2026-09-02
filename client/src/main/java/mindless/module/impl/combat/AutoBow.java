package mindless.module.impl.combat;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import net.minecraft.item.ItemBow;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoBow extends Module {

    private final ButtonSetting fullCharge;

    public AutoBow() {
        super("AutoBow", "Automatically shoots for you", category.combat, 0);
        this.registerSetting(fullCharge = new ButtonSetting("Full charge only", true));
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck()) return;
        if (!mc.thePlayer.isUsingItem()) return;
        if (!(mc.thePlayer.getItemInUse().getItem() instanceof ItemBow)) return;

        int duration = mc.thePlayer.getItemInUseDuration();
        if (fullCharge.isToggled() && duration < 20) return;

        mc.thePlayer.stopUsingItem();
        PacketUtils.sendReleasePacket();
    }
}
