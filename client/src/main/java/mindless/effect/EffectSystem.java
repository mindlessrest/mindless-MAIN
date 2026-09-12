package mindless.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The one owner of every cosmetic effect in the world.
 *
 * Hit, jump, kill and trail are the same thing with different numbers: spawn some geometry, age it
 * over a few ticks, draw it. Each of them written as its own module means each of them carries its
 * own list, its own render hook and its own idea of what GL state to leave behind, which is how
 * two of them end up fighting over the alpha test. There is one list, one hook, one contract.
 *
 * The cap is a budget, not a safety net. Effects are cheap individually and a fight produces them
 * faster than they expire, so the oldest is dropped rather than letting a busy moment decide the
 * frame rate for everyone.
 */
public final class EffectSystem {

    public static final EffectSystem INSTANCE = new EffectSystem();

    private static final int MAX_LIVE = 32;

    private final List<Effect> live = new ArrayList<Effect>();

    private EffectSystem() {
    }

    public static void spawn(Effect effect) {
        if (effect == null) {
            return;
        }
        List<Effect> list = INSTANCE.live;
        synchronized (list) {
            // Dropping the oldest keeps the most recent hit visible, which is the one being
            // looked at. Refusing the new one instead makes the system stop responding exactly
            // when the most is happening.
            while (list.size() >= MAX_LIVE) {
                list.remove(0);
            }
            list.add(effect);
        }
    }

    public static void clear() {
        synchronized (INSTANCE.live) {
            INSTANCE.live.clear();
        }
    }

    public static int liveCount() {
        synchronized (INSTANCE.live) {
            return INSTANCE.live.size();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        synchronized (live) {
            if (mc.theWorld == null) {
                live.clear();
                return;
            }
            for (int i = live.size() - 1; i >= 0; i--) {
                Effect effect = live.get(i);
                effect.tick();
                if (effect.expired()) {
                    live.remove(i);
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.getRenderManager() == null) {
            return;
        }

        Effect[] snapshot;
        synchronized (live) {
            if (live.isEmpty()) {
                return;
            }
            snapshot = live.toArray(new Effect[0]);
        }

        RenderManager rm = mc.getRenderManager();
        EffectRenderer.begin();
        // World coordinates go in; the camera is at the origin during this pass, so everything is
        // drawn relative to the viewer rather than to the world origin.
        GlStateManager.translate(-rm.viewerPosX, -rm.viewerPosY, -rm.viewerPosZ);
        try {
            for (int i = 0; i < snapshot.length; i++) {
                snapshot[i].render(event.partialTicks);
            }
        }
        finally {
            EffectRenderer.end();
        }
    }
}
