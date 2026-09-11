package mindless.placement;

import mindless.event.PreUpdateEvent;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class PlacementLifecycle {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreUpdate(PreUpdateEvent event) {
        PlacementRuntime.retryHotbarSync();
        if (!Utils.nullCheck()) {
            PlacementCoordinator.get().clear();
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        PlacementCoordinator.get().beginTick(minecraft.thePlayer, minecraft.theWorld,
                Utils.getBaseClientTick());
    }
}
