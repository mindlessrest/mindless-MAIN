package mindless.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Supplies the lifecycle events normally emitted by Forge's patched Minecraft
 * classes when Raven is running in a plain Lunar + OptiFine MCP process.
 */
public final class LunarEventBridge {
    private static final boolean DIRECT_LUNAR = Boolean.parseBoolean(
            System.getProperty("raven.embeddedForge", "false"));

    private LunarEventBridge() {}

    public static boolean isDirectLunar() {
        return DIRECT_LUNAR;
    }

    public static void postClientTick(TickEvent.Phase phase) {
        if (!DIRECT_LUNAR) return;
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(phase));
    }

    public static void postRenderTick(TickEvent.Phase phase, float partialTicks) {
        if (!DIRECT_LUNAR) return;
        MinecraftForge.EVENT_BUS.post(new TickEvent.RenderTickEvent(phase, partialTicks));
    }

    public static void postRenderWorld(float partialTicks) {
        if (!DIRECT_LUNAR) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.renderGlobal == null) return;
        MinecraftForge.EVENT_BUS.post(
                new RenderWorldLastEvent(minecraft.renderGlobal, partialTicks));
    }

    /**
     * Replaces Mouse.next() inside Minecraft.runTick. Canceled Forge mouse
     * events are consumed and the loop advances to the next native event.
     */
    public static boolean nextMouseEvent() {
        if (!DIRECT_LUNAR) return Mouse.next();
        while (Mouse.next()) {
            if (!MinecraftForge.EVENT_BUS.post(new MouseEvent())) return true;
        }
        return false;
    }

    /** @return true when vanilla chat handling should be canceled. */
    public static boolean postChat(S02PacketChat packet) {
        if (!DIRECT_LUNAR || packet == null) return false;
        ClientChatReceivedEvent event = new ClientChatReceivedEvent(
                packet.getType(), packet.getChatComponent());
        return MinecraftForge.EVENT_BUS.post(event);
    }

    public static void postEntityJoin(Entity entity, World world) {
        if (!DIRECT_LUNAR || entity == null || world == null) return;
        MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entity, world));
    }

    /**
     * ForgeHooks cannot be initialized in an unpatched Lunar Minecraft. Its
     * static tool table calls Block.setHarvestLevel, a method added by Forge
     * and absent from Lunar's MCP/named bake. Posting the event directly is
     * exactly what ForgeHooks.onLivingJump does, without triggering that
     * incompatible initializer.
     */
    public static void onLivingJump(EntityLivingBase entity) {
        if (entity == null) return;
        MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingJumpEvent(entity));
    }

    /**
     * Mirrors ForgeHooks.onPlayerAttackTarget. Lunar receives the cancellable
     * attack event, but skips Item.onLeftClickEntity because that method is
     * another Forge-only patch and does not exist in the plain Lunar bake.
     */
    public static boolean onPlayerAttackTarget(EntityPlayer player, Entity target) {
        if (player == null || target == null
                || MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(player, target))) {
            return false;
        }
        if (DIRECT_LUNAR) return true;

        ItemStack heldItem = player.getCurrentEquippedItem();
        return heldItem == null
                || !heldItem.getItem().onLeftClickEntity(heldItem, player, target);
    }

    /**
     * Listeners that want the FML tick bus. Direct Lunar has no initialized
     * FMLCommonHandler, so share Raven's event bus instead.
     */
    public static void registerTickListener(Object listener) {
        if (DIRECT_LUNAR) MinecraftForge.EVENT_BUS.register(listener);
        else FMLCommonHandler.instance().bus().register(listener);
    }

    public static void unregisterTickListener(Object listener) {
        if (listener == null) return;
        if (DIRECT_LUNAR) MinecraftForge.EVENT_BUS.unregister(listener);
        else FMLCommonHandler.instance().bus().unregister(listener);
    }

    /**
     * Called when Nametags needs to suppress vanilla nametag rendering but the
     * RenderLivingEvent.Specials.Pre event is non-cancelable (Lunar path).
     * No-op stub: on Lunar the event is already fired non-cancelable, so the
     * visual duplicate is acceptable until a transformer injection is added.
     */
    public static void cancelCurrentLivingSpecials() {}
}
