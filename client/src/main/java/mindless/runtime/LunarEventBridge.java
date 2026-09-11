package mindless.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraft.util.MovingObjectPosition;
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
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
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
        // Diagnostics measured this pass leaving blend enabled and the depth test disabled, so
        // both are captured and put back rather than left for whatever draws next.
        GlSnapshot before = GlSnapshot.take();
        mindless.utility.Diagnostics.sectionBegin("render tick " + phase);
        try {
            SYNTHETIC_EVENT_BUS.post(new TickEvent.RenderTickEvent(phase, partialTicks));
        }
        finally {
            if (phase == TickEvent.Phase.END) {
                RenderUtils.restoreGuiTextState();
            }
            before.restore();
            // Closed after the restores, so it reports what actually survives the pass
            // rather than the state part way through cleaning up.
            mindless.utility.Diagnostics.sectionEnd();
        }
    }

    public static void postRenderWorld(float partialTicks) {
        if (!DIRECT_LUNAR) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.renderGlobal == null) return;
        GlSnapshot before = GlSnapshot.take();
        mindless.utility.Diagnostics.sectionBegin("render world last");
        try {
            SYNTHETIC_EVENT_BUS.post(
                    new RenderWorldLastEvent(minecraft.renderGlobal, partialTicks));
        }
        finally {
            RenderUtils.restoreGuiTextState();
            before.restore();
            mindless.utility.Diagnostics.sectionEnd();
        }
    }
    /**
     * The fixed-function state the host had before a synthetic pass ran.
     *
     * Alpha is captured alongside blend and depth because restoreGuiTextState forces the
     * vanilla GUI values and those outlived the pass: diagnostics reported the alpha test
     * switched on and its reference moved from 0.01 to 0.1 on every frame. The repairs that
     * call makes to the shader, texture unit and colour are still wanted, so it runs first
     * and only the state the host actually had is put back afterwards.
     *
     * Restored through GlStateManager rather than raw GL, or its cache would still believe
     * whatever a module last told it and skip the next enable as redundant.
     */
    private static final class GlSnapshot {
        private static final FloatBuffer COLOR = BufferUtils.createFloatBuffer(16);
        private final boolean blend;
        private final boolean depth;
        private final boolean alpha;
        private final boolean lighting;
        private final int alphaFunc;
        private final float alphaRef;
        private final float red;
        private final float green;
        private final float blue;
        private final float opacity;

        private GlSnapshot(boolean blend, boolean depth, boolean alpha, boolean lighting,
                           int alphaFunc, float alphaRef, float red, float green,
                           float blue, float opacity) {
            this.blend = blend;
            this.depth = depth;
            this.alpha = alpha;
            this.lighting = lighting;
            this.alphaFunc = alphaFunc;
            this.alphaRef = alphaRef;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.opacity = opacity;
        }

        static GlSnapshot take() {
            COLOR.clear();
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_CURRENT_COLOR, COLOR);
            return new GlSnapshot(
                    org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND),
                    org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),
                    org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST),
                    org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_LIGHTING),
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_FUNC),
                    org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF),
                    COLOR.get(0), COLOR.get(1), COLOR.get(2), COLOR.get(3));
        }

        void restore() {
            if (blend) {
                net.minecraft.client.renderer.GlStateManager.enableBlend();
            }
            else {
                net.minecraft.client.renderer.GlStateManager.disableBlend();
            }
            if (depth) {
                net.minecraft.client.renderer.GlStateManager.enableDepth();
            }
            else {
                net.minecraft.client.renderer.GlStateManager.disableDepth();
            }
            net.minecraft.client.renderer.GlStateManager.alphaFunc(alphaFunc, alphaRef);
            if (alpha) {
                net.minecraft.client.renderer.GlStateManager.enableAlpha();
            }
            else {
                net.minecraft.client.renderer.GlStateManager.disableAlpha();
            }
            if (lighting) {
                net.minecraft.client.renderer.GlStateManager.enableLighting();
            }
            else {
                net.minecraft.client.renderer.GlStateManager.disableLighting();
            }
            net.minecraft.client.renderer.GlStateManager.color(red, green, blue, opacity);
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

    /**
     * The block highlight event, for the Lunar path.
     *
     * Forge fires this from its own hook inside drawSelectionBox, so on the Forge path it
     * arrives without help. There is no Forge under Lunar, and nothing else posted it, so
     * Block Overlay listened for an event that was never sent and the whole module did
     * nothing at all in that launch path -- including the modes that only cancel vanilla.
     *
     * @return true when a listener cancelled it, meaning vanilla must not draw its box.
     */
    public static boolean postDrawBlockHighlight(EntityPlayer player,
                                                 MovingObjectPosition target,
                                                 int subId, float partialTicks) {
        if (!DIRECT_LUNAR || player == null || target == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = player.inventory == null ? null : player.inventory.getCurrentItem();
        DrawBlockHighlightEvent event = new DrawBlockHighlightEvent(
                mc.renderGlobal, player, target, subId, held, partialTicks);
        mindless.module.impl.render.BlockOverlay.resetVanillaSuppression();
        SYNTHETIC_EVENT_BUS.post(event);
        // Cancellation is unavailable on this path, so the module reports its decision
        // directly; isCanceled still covers any listener that can cancel.
        return event.isCanceled()
                || mindless.module.impl.render.BlockOverlay.isVanillaSuppressed();
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
