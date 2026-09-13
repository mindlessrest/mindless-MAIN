package mindless.runtime;

import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MouseHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Timer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
public final class AccessorBridge {
    private AccessorBridge() {}

    // Keyed on the owner and then the field name, rather than on a string built from both. The
    // old key was concatenated on every call, and these are called per packet and per frame: it
    // was the largest single source of allocation attributable to the client in a JFR recording.
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELDS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static volatile Field entityArrowInGroundField;
    private static volatile Method entityRendererSetupCameraTransformMethod;
    private static volatile Field minecraftTimerField;

    private static Field field(Class<?> owner, String... candidates) {
        ConcurrentHashMap<String, Field> byName = FIELDS.get(owner);
        if (byName == null) {
            byName = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Field> raced = FIELDS.putIfAbsent(owner, byName);
            if (raced != null) byName = raced;
        }
        Field cached = byName.get(candidates[0]);
        if (cached != null) return cached;
        NoSuchFieldException last = null;
        for (String name : candidates) {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                byName.put(candidates[0], f);
                return f;
            } catch (NoSuchFieldException e) {
                last = e;
            }
        }
        throw new RuntimeException("Field not found on " + owner.getName()
                + " (tried " + java.util.Arrays.toString(candidates) + ")", last);
    }

    private static Method method(Class<?> owner, String[] candidates, Class<?>... args) {
        StringBuilder keyBuilder = new StringBuilder(owner.getName()).append('#').append(candidates[0]);
        for (Class<?> arg : args) keyBuilder.append(':').append(arg.getName());
        String key = keyBuilder.toString();
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        NoSuchMethodException last = null;
        for (String name : candidates) {
            try {
                Method m = owner.getDeclaredMethod(name, args);
                m.setAccessible(true);
                METHODS.put(key, m);
                return m;
            } catch (NoSuchMethodException e) {
                last = e;
            }
        }
        throw new RuntimeException("Method not found on " + owner.getName()
                + " (tried " + java.util.Arrays.toString(candidates) + ")", last);
    }

    private static RuntimeException wrap(String context, Throwable cause) {
        Throwable root = cause instanceof InvocationTargetException && cause.getCause() != null
                ? cause.getCause() : cause;
        return new RuntimeException("AccessorBridge." + context + " failed: " + root, root);
    }
    public static int Entity_getFire(Entity e) {
        try { return field(Entity.class, "fire", "field_70151_c").getInt(e); }
        catch (Exception t) { throw wrap("Entity_getFire", t); }
    }
    public static int Entity_getNextStepDistance(Entity e) {
        try { return field(Entity.class, "nextStepDistance", "field_70150_b").getInt(e); }
        catch (Exception t) { throw wrap("Entity_getNextStepDistance", t); }
    }
    public static boolean Entity_getIsInWeb(Entity e) {
        try { return field(Entity.class, "isInWeb", "field_70134_J").getBoolean(e); }
        catch (Exception t) { throw wrap("Entity_getIsInWeb", t); }
    }
    public static boolean EntityArrow_getInGround(EntityArrow a) {
        try {
            Field accessor = entityArrowInGroundField;
            if (accessor == null) {
                accessor = field(EntityArrow.class, "inGround", "field_70254_i");
                entityArrowInGroundField = accessor;
            }
            return accessor.getBoolean(a);
        }
        catch (Exception t) { throw wrap("EntityArrow_getInGround", t); }
    }
    public static int EntityLivingBase_getJumpTicks(EntityLivingBase e) {
        try { return field(EntityLivingBase.class, "jumpTicks", "field_70773_bE").getInt(e); }
        catch (Exception t) { throw wrap("EntityLivingBase_getJumpTicks", t); }
    }
    public static void EntityLivingBase_setJumpTicks(EntityLivingBase e, int ticks) {
        try { field(EntityLivingBase.class, "jumpTicks", "field_70773_bE").setInt(e, ticks); }
        catch (Exception t) { throw wrap("EntityLivingBase_setJumpTicks", t); }
    }
    public static void EntityPlayer_setItemInUseCount(EntityPlayer p, int count) {
        try { field(EntityPlayer.class, "itemInUseCount", "field_71072_f").setInt(p, count); }
        catch (Exception t) { throw wrap("EntityPlayer_setItemInUseCount", t); }
    }
    public static double EntityPlayerSP_getLastReportedPosX(EntityPlayerSP p) {
        try { return field(EntityPlayerSP.class, "lastReportedPosX", "field_175172_bI").getDouble(p); }
        catch (Exception t) { throw wrap("EntityPlayerSP_getLastReportedPosX", t); }
    }
    public static double EntityPlayerSP_getLastReportedPosY(EntityPlayerSP p) {
        try { return field(EntityPlayerSP.class, "lastReportedPosY", "field_175166_bJ").getDouble(p); }
        catch (Exception t) { throw wrap("EntityPlayerSP_getLastReportedPosY", t); }
    }
    public static double EntityPlayerSP_getLastReportedPosZ(EntityPlayerSP p) {
        try { return field(EntityPlayerSP.class, "lastReportedPosZ", "field_175167_bK").getDouble(p); }
        catch (Exception t) { throw wrap("EntityPlayerSP_getLastReportedPosZ", t); }
    }
    public static float EntityPlayerSP_getLastReportedYaw(EntityPlayerSP p) {
        try { return field(EntityPlayerSP.class, "lastReportedYaw", "field_175164_bL").getFloat(p); }
        catch (Exception t) { throw wrap("EntityPlayerSP_getLastReportedYaw", t); }
    }
    public static float EntityPlayerSP_getLastReportedPitch(EntityPlayerSP p) {
        try { return field(EntityPlayerSP.class, "lastReportedPitch", "field_175165_bM").getFloat(p); }
        catch (Exception t) { throw wrap("EntityPlayerSP_getLastReportedPitch", t); }
    }
    public static void EntityRenderer_callSetupCameraTransform(EntityRenderer r, float partial, int pass) {
        try {
            Method accessor = entityRendererSetupCameraTransformMethod;
            if (accessor == null) {
                accessor = method(EntityRenderer.class,
                        new String[]{"setupCameraTransform", "func_78479_a"},
                        float.class, int.class);
                entityRendererSetupCameraTransformMethod = accessor;
            }
            accessor.invoke(r, partial, pass);
        } catch (Exception t) { throw wrap("EntityRenderer_callSetupCameraTransform", t); }
    }
    public static void EntityRenderer_callLoadShader(EntityRenderer r, ResourceLocation loc) {
        try {
            method(EntityRenderer.class,
                    new String[]{"loadShader", "func_175069_a"},
                    ResourceLocation.class).invoke(r, loc);
        } catch (Exception t) { throw wrap("EntityRenderer_callLoadShader", t); }
    }
    public static ResourceLocation[] EntityRenderer_getShaderResourceLocations(EntityRenderer r) {
        try { return (ResourceLocation[]) field(EntityRenderer.class, "shaderResourceLocations", "field_147712_ad").get(r); }
        catch (Exception t) { throw wrap("EntityRenderer_getShaderResourceLocations", t); }
    }
    public static boolean EntityRenderer_getUseShader(EntityRenderer r) {
        try { return field(EntityRenderer.class, "useShader", "field_175083_ad").getBoolean(r); }
        catch (Exception t) { throw wrap("EntityRenderer_getUseShader", t); }
    }
    public static void EntityRenderer_setUseShader(EntityRenderer r, boolean value) {
        try { field(EntityRenderer.class, "useShader", "field_175083_ad").setBoolean(r, value); }
        catch (Exception t) { throw wrap("EntityRenderer_setUseShader", t); }
    }
    public static int EntityRenderer_getShaderIndex(EntityRenderer r) {
        try { return field(EntityRenderer.class, "shaderIndex", "field_147713_ae").getInt(r); }
        catch (Exception t) { throw wrap("EntityRenderer_getShaderIndex", t); }
    }
    public static void EntityRenderer_setShaderIndex(EntityRenderer r, int idx) {
        try { field(EntityRenderer.class, "shaderIndex", "field_147713_ae").setInt(r, idx); }
        catch (Exception t) { throw wrap("EntityRenderer_setShaderIndex", t); }
    }
    public static void EntityRenderer_setThirdPersonDistance(EntityRenderer r, float distance) {
        try { field(EntityRenderer.class, "thirdPersonDistance", "field_78490_B").setFloat(r, distance); }
        catch (Exception t) { throw wrap("EntityRenderer_setThirdPersonDistance", t); }
    }
    public static void EntityRenderer_setPointedEntity(EntityRenderer r, Entity entity) {
        try { field(EntityRenderer.class, "pointedEntity", "field_78528_u").set(r, entity); }
        catch (Exception t) { throw wrap("EntityRenderer_setPointedEntity", t); }
    }
    public static String GuiIngame_getRecordPlaying(GuiIngame g) {
        try { return (String) field(GuiIngame.class, "recordPlaying", "field_73838_g").get(g); }
        catch (Exception t) { throw wrap("GuiIngame_getRecordPlaying", t); }
    }
    public static String GuiIngame_getDisplayedTitle(GuiIngame g) {
        try { return (String) field(GuiIngame.class, "displayedTitle", "field_175201_x").get(g); }
        catch (Exception t) { throw wrap("GuiIngame_getDisplayedTitle", t); }
    }
    public static String GuiIngame_getDisplayedSubTitle(GuiIngame g) {
        try { return (String) field(GuiIngame.class, "displayedSubTitle", "field_175200_y").get(g); }
        catch (Exception t) { throw wrap("GuiIngame_getDisplayedSubTitle", t); }
    }
    public static IChatComponent GuiPlayerTabOverlay_getHeader(GuiPlayerTabOverlay g) {
        try { return (IChatComponent) field(GuiPlayerTabOverlay.class, "header", "field_175256_i").get(g); }
        catch (Exception t) { throw wrap("GuiPlayerTabOverlay_getHeader", t); }
    }
    public static IChatComponent GuiPlayerTabOverlay_getFooter(GuiPlayerTabOverlay g) {
        try { return (IChatComponent) field(GuiPlayerTabOverlay.class, "footer", "field_175255_h").get(g); }
        catch (Exception t) { throw wrap("GuiPlayerTabOverlay_getFooter", t); }
    }
    public static void GuiScreen_callMouseClicked(GuiScreen s, int x, int y, int button) {
        try {
            method(GuiScreen.class,
                    new String[]{"mouseClicked", "func_73864_a"},
                    int.class, int.class, int.class).invoke(s, x, y, button);
        } catch (Exception t) { throw wrap("GuiScreen_callMouseClicked", t); }
    }
    @SuppressWarnings("unchecked")
    public static List<IChatComponent> GuiScreenBook_getBookContents(GuiScreenBook b) {
        try { return (List<IChatComponent>) field(GuiScreenBook.class, "field_175386_A").get(b); }
        catch (Exception t) { throw wrap("GuiScreenBook_getBookContents", t); }
    }
    public static boolean ItemFood_getAlwaysEdible(ItemFood f) {
        try { return field(ItemFood.class, "alwaysEdible", "field_77852_bZ").getBoolean(f); }
        catch (Exception t) { throw wrap("ItemFood_getAlwaysEdible", t); }
    }
    public static Timer Minecraft_getTimer(Minecraft mc) {
        try {
            Field accessor = minecraftTimerField;
            if (accessor == null) {
                accessor = field(Minecraft.class, "timer", "field_71428_T");
                minecraftTimerField = accessor;
            }
            return (Timer) accessor.get(mc);
        }
        catch (Exception t) { throw wrap("Minecraft_getTimer", t); }
    }
    public static int Minecraft_getRightClickDelayTimer(Minecraft mc) {
        try { return field(Minecraft.class, "rightClickDelayTimer", "field_71467_ac").getInt(mc); }
        catch (Exception t) { throw wrap("Minecraft_getRightClickDelayTimer", t); }
    }
    public static void Minecraft_setRightClickDelayTimer(Minecraft mc, int delay) {
        try { field(Minecraft.class, "rightClickDelayTimer", "field_71467_ac").setInt(mc, delay); }
        catch (Exception t) { throw wrap("Minecraft_setRightClickDelayTimer", t); }
    }
    public static void Minecraft_setLeftClickCounter(Minecraft mc, int delay) {
        try { field(Minecraft.class, "leftClickCounter", "field_71429_W").setInt(mc, delay); }
        catch (Exception t) { throw wrap("Minecraft_setLeftClickCounter", t); }
    }
    public static void Minecraft_callRightClickMouse(Minecraft mc) {
        mindless.helper.MouseHelper.aR();
        try {
            method(Minecraft.class, new String[]{"rightClickMouse", "func_147121_ag"}).invoke(mc);
        } catch (Exception t) { throw wrap("Minecraft_callRightClickMouse", t); }
    }
    public static void Minecraft_callClickMouse(Minecraft mc) {
        mindless.helper.MouseHelper.aL();
        try {
            method(Minecraft.class, new String[]{"clickMouse", "func_147116_af"}).invoke(mc);
        } catch (Exception t) { throw wrap("Minecraft_callClickMouse", t); }
    }
    public static void RenderItem_renderItemModelTransform(net.minecraft.client.renderer.entity.RenderItem renderItem,
                                                           ItemStack stack,
                                                           net.minecraft.client.resources.model.IBakedModel model,
                                                           net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType transform) {
        try {
            method(net.minecraft.client.renderer.entity.RenderItem.class,
                    new String[]{"renderItemModelTransform", "func_175040_a"},
                    ItemStack.class, net.minecraft.client.resources.model.IBakedModel.class,
                    net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType.class)
                    .invoke(renderItem, stack, model, transform);
        }
        catch (Exception t) { throw wrap("RenderItem_renderItemModelTransform", t); }
    }

    public static Channel NetworkManager_getChannel(NetworkManager nm) {
        try { return (Channel) field(NetworkManager.class, "channel", "field_150746_k").get(nm); }
        catch (Exception t) { throw wrap("NetworkManager_getChannel", t); }
    }
    public static net.minecraft.network.INetHandler NetworkManager_getPacketListener(NetworkManager nm) {
        try { return (net.minecraft.network.INetHandler) field(NetworkManager.class, "packetListener", "field_150744_m").get(nm); }
        catch (Exception t) { throw wrap("NetworkManager_getPacketListener", t); }
    }
    public static float PlayerControllerMP_getCurBlockDamageMP(PlayerControllerMP c) {
        try { return field(PlayerControllerMP.class, "curBlockDamageMP", "field_78770_f").getFloat(c); }
        catch (Exception t) { throw wrap("PlayerControllerMP_getCurBlockDamageMP", t); }
    }
    public static void PlayerControllerMP_setCurBlockDamageMP(PlayerControllerMP c, float damage) {
        try { field(PlayerControllerMP.class, "curBlockDamageMP", "field_78770_f").setFloat(c, damage); }
        catch (Exception t) { throw wrap("PlayerControllerMP_setCurBlockDamageMP", t); }
    }
    public static int PlayerControllerMP_getBlockHitDelay(PlayerControllerMP c) {
        try { return field(PlayerControllerMP.class, "blockHitDelay", "field_78781_i").getInt(c); }
        catch (Exception t) { throw wrap("PlayerControllerMP_getBlockHitDelay", t); }
    }
    public static void PlayerControllerMP_setBlockHitDelay(PlayerControllerMP c, int delay) {
        try { field(PlayerControllerMP.class, "blockHitDelay", "field_78781_i").setInt(c, delay); }
        catch (Exception t) { throw wrap("PlayerControllerMP_setBlockHitDelay", t); }
    }
    public static BlockPos PlayerControllerMP_getCurrentBlock(PlayerControllerMP c) {
        try { return (BlockPos) field(PlayerControllerMP.class, "currentBlock", "field_78777_g").get(c); }
        catch (Exception t) { throw wrap("PlayerControllerMP_getCurrentBlock", t); }
    }
    public static void PlayerControllerMP_callSyncCurrentPlayItem(PlayerControllerMP c) {
        try {
            method(PlayerControllerMP.class,
                    new String[]{"syncCurrentPlayItem", "func_78750_j"}).invoke(c);
        } catch (Exception t) { throw wrap("PlayerControllerMP_callSyncCurrentPlayItem", t); }
    }
    public static int PlayerControllerMP_getCurrentPlayerItem(PlayerControllerMP c) {
        try { return field(PlayerControllerMP.class, "currentPlayerItem", "field_78777_l").getInt(c); }
        catch (Exception t) { throw wrap("PlayerControllerMP_getCurrentPlayerItem", t); }
    }
    public static void PlayerControllerMP_setCurrentPlayerItem(PlayerControllerMP c, int slot) {
        try { field(PlayerControllerMP.class, "currentPlayerItem", "field_78777_l").setInt(c, slot); }
        catch (Exception t) { throw wrap("PlayerControllerMP_setCurrentPlayerItem", t); }
    }
    public static float ItemRenderer_getEquippedProgress(Object renderer) {
        try { return field(renderer.getClass().getName().contains("ItemRenderer")
                ? renderer.getClass() : Class.forName("net.minecraft.client.renderer.ItemRenderer"),
                "equippedProgress", "field_78454_c").getFloat(renderer); }
        catch (Exception t) { throw wrap("ItemRenderer_getEquippedProgress", t); }
    }
    public static float ItemRenderer_getPrevEquippedProgress(ItemRenderer r) {
        try { return field(ItemRenderer.class, "prevEquippedProgress", "field_78451_d").getFloat(r); }
        catch (Exception t) { throw wrap("ItemRenderer_getPrevEquippedProgress", t); }
    }
    public static void ItemRenderer_callRotateArroundXAndY(ItemRenderer r, float pitch, float yaw) {
        try { method(ItemRenderer.class, new String[]{"rotateArroundXAndY", "func_178101_a"}, float.class, float.class).invoke(r, pitch, yaw); }
        catch (Exception t) { throw wrap("ItemRenderer_callRotateArroundXAndY", t); }
    }
    public static void ItemRenderer_callSetLightMapFromPlayer(ItemRenderer r, EntityPlayerSP p) {
        try { method(ItemRenderer.class, new String[]{"setLightMapFromPlayer", "func_178109_a"}, net.minecraft.client.entity.AbstractClientPlayer.class).invoke(r, p); }
        catch (Exception t) { throw wrap("ItemRenderer_callSetLightMapFromPlayer", t); }
    }
    public static void ItemRenderer_callRotateWithPlayerRotations(ItemRenderer r, EntityPlayerSP p, float partialTicks) {
        try { method(ItemRenderer.class, new String[]{"rotateWithPlayerRotations", "func_178110_a"}, EntityPlayerSP.class, float.class).invoke(r, p, partialTicks); }
        catch (Exception t) { throw wrap("ItemRenderer_callRotateWithPlayerRotations", t); }
    }
    public static void ItemRenderer_callDoItemUsedTransformations(ItemRenderer r, float swingProgress) {
        try { method(ItemRenderer.class, new String[]{"doItemUsedTransformations", "func_178105_d"}, float.class).invoke(r, swingProgress); }
        catch (Exception t) { throw wrap("ItemRenderer_callDoItemUsedTransformations", t); }
    }
    public static void ItemRenderer_callTransformFirstPersonItem(ItemRenderer r, float equip, float swing) {
        try { method(ItemRenderer.class, new String[]{"transformFirstPersonItem", "func_178096_b"}, float.class, float.class).invoke(r, equip, swing); }
        catch (Exception t) { throw wrap("ItemRenderer_callTransformFirstPersonItem", t); }
    }
    public static void ItemRenderer_callDoBlockTransformations(ItemRenderer r) {
        try { method(ItemRenderer.class, new String[]{"doBlockTransformations", "func_178103_d"}).invoke(r); }
        catch (Exception t) { throw wrap("ItemRenderer_callDoBlockTransformations", t); }
    }
    public static int EntityPlayer_getItemInUseCount(EntityPlayer p) {
        try { return field(EntityPlayer.class, "itemInUseCount", "field_71072_f").getInt(p); }
        catch (Exception t) { throw wrap("EntityPlayer_getItemInUseCount", t); }
    }
    public static double RenderManager_getRenderPosX(RenderManager rm) {
        try { return field(RenderManager.class, "renderPosX", "field_78725_b").getDouble(rm); }
        catch (Exception t) { throw wrap("RenderManager_getRenderPosX", t); }
    }
    public static double RenderManager_getRenderPosY(RenderManager rm) {
        try { return field(RenderManager.class, "renderPosY", "field_78726_c").getDouble(rm); }
        catch (Exception t) { throw wrap("RenderManager_getRenderPosY", t); }
    }
    public static double RenderManager_getRenderPosZ(RenderManager rm) {
        try { return field(RenderManager.class, "renderPosZ", "field_78723_d").getDouble(rm); }
        catch (Exception t) { throw wrap("RenderManager_getRenderPosZ", t); }
    }
    public static int S14PacketEntity_getEntityId(S14PacketEntity p) {
        try { return field(S14PacketEntity.class, "entityId", "field_149074_a").getInt(p); }
        catch (Exception t) { throw wrap("S14PacketEntity_getEntityId", t); }
    }
    public static byte S14PacketEntity_getDeltaX(S14PacketEntity p) {
        try { return field(S14PacketEntity.class, "posX", "field_149072_b").getByte(p); }
        catch (Exception t) { throw wrap("S14PacketEntity_getDeltaX", t); }
    }
    public static byte S14PacketEntity_getDeltaY(S14PacketEntity p) {
        try { return field(S14PacketEntity.class, "posY", "field_149073_c").getByte(p); }
        catch (Exception t) { throw wrap("S14PacketEntity_getDeltaY", t); }
    }
    public static byte S14PacketEntity_getDeltaZ(S14PacketEntity p) {
        try { return field(S14PacketEntity.class, "posZ", "field_149070_d").getByte(p); }
        catch (Exception t) { throw wrap("S14PacketEntity_getDeltaZ", t); }
    }
    public static int S19PacketEntityStatus_getEntityId(S19PacketEntityStatus p) {
        try { return field(S19PacketEntityStatus.class, "entityId", "field_149164_a", "field_149079_a").getInt(p); }
        catch (Exception t) { throw wrap("S19PacketEntityStatus_getEntityId", t); }
    }
    public static int C02PacketUseEntity_getEntityId(C02PacketUseEntity p) {
        try { return field(C02PacketUseEntity.class, "entityId", "field_149567_a").getInt(p); }
        catch (Exception t) { throw wrap("C02PacketUseEntity_getEntityId", t); }
    }
    public static int MouseHelper_getDeltaX(MouseHelper m) {
        try { return field(MouseHelper.class, "deltaX", "field_74377_a").getInt(m); }
        catch (Exception t) { throw wrap("MouseHelper_getDeltaX", t); }
    }
    public static int MouseHelper_getDeltaY(MouseHelper m) {
        try { return field(MouseHelper.class, "deltaY", "field_74375_b").getInt(m); }
        catch (Exception t) { throw wrap("MouseHelper_getDeltaY", t); }
    }
    public static void RendererLivingEntity_callUnsetBrightness(RendererLivingEntity<?> r) {
        try { method(RendererLivingEntity.class, new String[]{"unsetBrightness", "func_77039_h"}).invoke(r); }
        catch (Exception t) { throw wrap("RendererLivingEntity_callUnsetBrightness", t); }
    }
    public static boolean RenderEntityItem_shouldSpreadItems(RenderEntityItem r) {
        try { return (boolean) method(RenderEntityItem.class, new String[]{"shouldSpreadItems", "func_177077_a"}).invoke(r); }
        catch (Exception t) { throw wrap("RenderEntityItem_shouldSpreadItems", t); }
    }
    @SuppressWarnings("unchecked")
    public static java.util.List<Shader> ShaderGroup_getListShaders(ShaderGroup g) {
        try { return (java.util.List<Shader>) field(ShaderGroup.class, "listShaders", "field_148031_a").get(g); }
        catch (Exception t) { throw wrap("ShaderGroup_getListShaders", t); }
    }
}
