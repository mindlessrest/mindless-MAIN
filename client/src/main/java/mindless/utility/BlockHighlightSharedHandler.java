package mindless.utility;

import mindless.event.ReceivePacketEvent;
import mindless.module.ModuleManager;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class BlockHighlightSharedHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SWEEP_INTERVAL = 20;

    private int sweepTicks;

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        SharedBlockHighlightCache.get().handleReceivePacket(e);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }
        SharedBlockHighlightCache cache = SharedBlockHighlightCache.get();
        if (!cache.anyConsumerActive()) {
            return;
        }
        int budget = 0;
        int radius = 0;
        if (ModuleManager.blockESP != null) {
            budget = Math.max(budget, ModuleManager.blockESP.getScanSpeedBudget());
            radius = Math.max(radius, ModuleManager.blockESP.getScanRadiusChunks());
        }
        if (ModuleManager.bedESP != null) {
            budget = Math.max(budget, ModuleManager.bedESP.getScanSpeedBudget());
            radius = Math.max(radius, ModuleManager.bedESP.getScanRadiusChunks());
        }
        cache.resetRequestedRadius();
        cache.requestScanRadius(radius);
        if (budget > 0) {
            if (++sweepTicks >= SWEEP_INTERVAL) {
                sweepTicks = 0;
                cache.sweepMissedChunks();
            }
            cache.tickScan(budget);
        }
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (e.entity != mc.thePlayer) {
            return;
        }
        SharedBlockHighlightCache cache = SharedBlockHighlightCache.get();
        if (!cache.anyConsumerActive()) {
            return;
        }
        sweepTicks = 0;
        cache.clear();
        cache.enqueueLoadedChunks();
    }
}
