package mindless.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import mindless.event.CancelableMouseEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import mindless.utility.RenderUtils;
import org.lwjgl.input.Mouse;
public final class LunarEventBridge {
    private static final boolean DIRECT_LUNAR = Boolean.parseBoolean(
            System.getProperty("mindless.embeddedForge", "false"));
    /*
     * Lunar and Mindless share the embedded Forge classes. Posting our synthetic
     * compatibility events on MinecraftForge.EVENT_BUS therefore also invokes
     * Lunar's own Forge-style listeners. A JFR capture showed Lunar's world
     * renderer consuming roughly half of the sampled client-thread time from
     * inside postRenderWorld(). Keep natural/custom Forge events on the shared
     * bus, but deliver events synthesized by Mindless on a private bus that only
     * Mindless listeners join.
     */
    private static final EventBus SYNTHETIC_EVENT_BUS = new EventBus();

    private LunarEventBridge() {}

    public static boolean isDirectLunar() {
        return DIRECT_LUNAR;
    }

    private static EventBus syntheticBus() {
        return DIRECT_LUNAR ? SYNTHETIC_EVENT_BUS : MinecraftForge.EVENT_BUS;
    }

    public static void postClientTick(TickEvent.Phase phase) {
        if (!DIRECT_LUNAR) return;
        SYNTHETIC_EVENT_BUS.post(new TickEvent.ClientTickEvent(phase));
    }

    public static void postRenderTick(TickEvent.Phase phase, float partialTicks) {
        if (!DIRECT_LUNAR) return;
        // Bracket the whole synthetic pass so anything a module changes and fails to restore is
        // reported against this boundary rather than surfacing later as an unexplained symptom.
        mindless.utility.Diagnostics.sectionBegin("render tick " + phase);
        try {
            SYNTHETIC_EVENT_BUS.post(new TickEvent.RenderTickEvent(phase, partialTicks));
        }
        finally {
            mindless.utility.Diagnostics.sectionEnd();
            if (phase == TickEvent.Phase.END) {
                RenderUtils.restoreGuiTextState();
            }
        }
    }

    public static void postRenderWorld(float partialTicks) {
        if (!DIRECT_LUNAR) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.renderGlobal == null) return;
        mindless.utility.Diagnostics.sectionBegin("render world last");
        try {
            SYNTHETIC_EVENT_BUS.post(
                    new RenderWorldLastEvent(minecraft.renderGlobal, partialTicks));
        }
        finally {
            mindless.utility.Diagnostics.sectionEnd();
            RenderUtils.restoreGuiTextState();
        }
    }
public static boolean nextMouseEvent() {
        if (!DIRECT_LUNAR) return Mouse.next();
        while (Mouse.next()) {
            if (!SYNTHETIC_EVENT_BUS.post(new CancelableMouseEvent())) return true;
        }
        return false;
    }
public static boolean postChat(S02PacketChat packet) {
        if (!DIRECT_LUNAR || packet == null) return false;
        ClientChatReceivedEvent event = new ClientChatReceivedEvent(
                packet.getType(), packet.getChatComponent());
        return SYNTHETIC_EVENT_BUS.post(event);
    }

    public static void postEntityJoin(Entity entity, World world) {
        if (!DIRECT_LUNAR || entity == null || world == null) return;
        SYNTHETIC_EVENT_BUS.post(new EntityJoinWorldEvent(entity, world));
    }
public static void onLivingJump(EntityLivingBase entity) {
        if (entity == null) return;
        syntheticBus().post(new LivingEvent.LivingJumpEvent(entity));
    }
public static boolean onPlayerAttackTarget(EntityPlayer player, Entity target) {
        if (player == null || target == null
                || syntheticBus().post(new AttackEntityEvent(player, target))) {
            return false;
        }
        if (DIRECT_LUNAR) return true;

        ItemStack heldItem = player.getCurrentEquippedItem();
        return heldItem == null
                || !heldItem.getItem().onLeftClickEntity(heldItem, player, target);
    }
public static void registerTickListener(Object listener) {
        if (DIRECT_LUNAR) SYNTHETIC_EVENT_BUS.register(listener);
        else FMLCommonHandler.instance().bus().register(listener);
    }

    public static void unregisterTickListener(Object listener) {
        if (listener == null) return;
        if (DIRECT_LUNAR) SYNTHETIC_EVENT_BUS.unregister(listener);
        else FMLCommonHandler.instance().bus().unregister(listener);
    }
public static void registerSyntheticListener(Object listener) {
        if (DIRECT_LUNAR && listener != null) {
            SYNTHETIC_EVENT_BUS.register(listener);
        }
    }
public static void unregisterSyntheticListener(Object listener) {
        if (DIRECT_LUNAR && listener != null) {
            SYNTHETIC_EVENT_BUS.unregister(listener);
        }
    }
public static void cancelCurrentLivingSpecials() {}
}
