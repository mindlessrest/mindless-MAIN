package mindless.runtime;

import mindless.module.impl.render.Animations;
import mindless.module.impl.render.Slow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;
public final class ItemRendererState {
    private ItemRendererState() {}

    private static volatile boolean cancelUpdate;
    private static volatile boolean cancelReset;
    private static volatile boolean forceSwordBlockAnimationActive;
    private static final ThreadLocal<ItemStack> originalRenderedItem = new ThreadLocal<>();

    public static boolean isCancelUpdate() { return cancelUpdate; }
    public static void setCancelUpdate(boolean value) { cancelUpdate = value; }

    public static boolean isCancelReset() { return cancelReset; }
    public static void setCancelReset(boolean value) { cancelReset = value; }

    public static boolean isHeldItemSpoofActive() {
        mindless.module.impl.player.BedAura bedAura = mindless.module.ModuleManager.bedAura;
        if (bedAura != null && bedAura.isEnabled() && bedAura.isSpoofingHeldItem()) {
            return true;
        }
        mindless.module.impl.player.AutoTool autoTool = mindless.module.ModuleManager.autoTool;
        if (autoTool != null && autoTool.isEnabled() && autoTool.isSpoofingHeldItem()) {
            return true;
        }
        mindless.module.impl.player.Scaffold scaffold = mindless.module.ModuleManager.scaffold;
        return scaffold != null && scaffold.isEnabled() && scaffold.isSpoofingHeldItem();
    }

    public static void setForceSwordBlockAnimation(boolean value) {
        forceSwordBlockAnimationActive = value;
    }

    public static boolean isForceSwordBlockAnimationActive() {
        return forceSwordBlockAnimationActive || mindless.module.ModuleManager.killAura != null && mindless.module.ModuleManager.killAura.shouldRenderBlocking();
    }
public static boolean isRenderItemInUse() { return forceSwordBlockAnimationActive || mindless.module.ModuleManager.killAura != null && mindless.module.ModuleManager.killAura.shouldRenderBlocking(); }
    public static void setRenderItemInUse(boolean value) { forceSwordBlockAnimationActive = value; }
public static void rememberOriginalRenderedItem(ItemStack item) {
        originalRenderedItem.set(item);
    }

    public static ItemStack takeOriginalRenderedItem() {
        ItemStack item = originalRenderedItem.get();
        originalRenderedItem.remove();
        return item;
    }
public static boolean shouldRenderForcedSwordBlock(ItemStack stack) {
        if (!isForceSwordBlockAnimationActive()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        return held != null && held.getItem() instanceof ItemSword
                && (stack == null || stack.getItem() instanceof ItemSword);
    }

    public static ItemStack getForcedSwordRenderItem() {
        if (!isForceSwordBlockAnimationActive()) return null;
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = mc == null || mc.thePlayer == null
                ? null : mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemSword ? held : null;
    }
    private static float spin;
    private static float delay;
    private static long lastUpdate = System.currentTimeMillis();

    public static float getSpin() { return spin; }
    public static float getDelay() { return delay; }
    public static long getLastUpdate() { return lastUpdate; }
    public static void setSpin(float v) { spin = v; }
    public static void setDelay(float v) { delay = v; }
    public static void setLastUpdate(long v) { lastUpdate = v; }

    public static boolean isRenderedSword(ItemStack itemToRender) {
        return itemToRender != null && itemToRender.getItem() instanceof ItemSword;
    }
public static void applyAnimationTransform(float equippedProgress, float prevEquippedProgress, float partialTicks) {
        float equipProgress = 1.0f - (prevEquippedProgress + (equippedProgress - prevEquippedProgress) * partialTicks);
        AbstractClientPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        float swingProgress = Slow.getVisualSwingProgress(player, player.getSwingProgress(partialTicks));

        GL11.glTranslated(Animations.blockPosX, Animations.blockPosY, Animations.blockPosZ);
        animate(equipProgress, swingProgress);
    }

    private static void transformFirstPersonItemRefl(Object instance, float equip, float swing) {
        try {
            java.lang.reflect.Method m = instance.getClass().getDeclaredMethod("transformFirstPersonItem", float.class, float.class);
            m.setAccessible(true);
            m.invoke(instance, equip, swing);
        } catch (Exception ignored) {}
    }

    private static void doBlockTransformationsRefl(Object instance) {
        try {
            java.lang.reflect.Method m = instance.getClass().getDeclaredMethod("doBlockTransformations");
            m.setAccessible(true);
            m.invoke(instance);
        } catch (Exception ignored) {}
    }
public static void animate(float equip, float sp) {
        Object renderer = Minecraft.getMinecraft().entityRenderer != null
                ? Minecraft.getMinecraft().getItemRenderer() : null;

        float si = MathHelper.sin(MathHelper.sqrt_float(sp) * 3.1415927f);
        float ss = MathHelper.sqrt_float(sp);
        float s1 = MathHelper.sin(sp * sp * 3.1415927f);
        int m = Animations.modeIndex;

        if (m >= 16 && renderer != null) {
            transformFirstPersonItemRefl(renderer, equip, 0.0f);
        }

        switch (m) {
            case 0:  GL11.glTranslated(0, 0.05, -0.1); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, sp); break;
            case 1:  GL11.glTranslated(0, -0.1, 0); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip / 2.0f, 0.0f); GL11.glTranslatef(0.1f, 0.4f, -0.1f); GL11.glRotated(-si*30, si/2, 0, 9); GL11.glRotated(-si*50, 0.8, si/2, 0); break;
            case 2:  GL11.glTranslated(0, -0.1, 0); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); GL11.glTranslatef(0.1f, 0.4f, -0.1f); GL11.glRotated(-si*35, -8, 0, 9); GL11.glRotated(-si*70, 1.5, -0.4, 0); break;
            case 3:  if(renderer!=null) transformFirstPersonItemRefl(renderer, equip * 0.5f, 0.0f); GL11.glRotated(-si*27.5, -8, 0, 9); GL11.glRotated(-si*45, 1, si/2, 0); GL11.glTranslated(-0.1, 0.3, 0.1); break;
            case 4:  { float alt = MathHelper.sin(ss*3.1415927f-3); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); GL11.glRotated(-si*10, 0, 15, 200); GL11.glRotated(-si*10, 300, si/2, 1); GL11.glTranslated(3.4, 0.3, -0.4); GL11.glTranslatef(-2.1f, -0.2f, 0.1f); GL11.glRotated(alt*13, -10, -1.4, -10); } break;
            case 5:  GL11.glTranslated(0, 0.05, 0); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); break;
            case 6:  GL11.glRotated(spin, 0, 0, -0.1); if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); spin = -(System.currentTimeMillis()/2L%360L); break;
            case 7:  GL11.glTranslatef(0.56f, -0.52f, -0.72f); GL11.glRotatef(45, 0, 1, 0); GL11.glRotatef(s1*-20, 0, 1, 0); GL11.glRotatef(si*-20, 0, 0, 1); GL11.glRotatef(si*-40, 1, 0, 0); GL11.glScalef(0.4f, 0.4f, 0.4f); break;
            case 8:  if(renderer!=null) transformFirstPersonItemRefl(renderer, equip / 2.0f, 0.0f); GL11.glRotated(-si*20, si/2, 0, 9); GL11.glRotated(-si*30, 1, si/2, 0); break;
            case 9:  if(renderer!=null) transformFirstPersonItemRefl(renderer, equip / 2.0f, sp); GL11.glRotated(si*15, -si, 0, 9); GL11.glRotated(si*40, 1, -si/2, 0); break;
            case 10: if(renderer!=null) transformFirstPersonItemRefl(renderer, equip / 2.0f, sp); GL11.glRotated(si*30, -si, 0, 9); GL11.glRotated(si*40, 1, -si, 0); break;
            case 11: if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); GL11.glTranslatef(-0.05f, 0.2f, 0); GL11.glRotated(-si*35, -8, 0, 9); GL11.glRotated(-si*70, 1, -0.4, 0); break;
            case 12: { GL11.glTranslated(-0.1, 0.09, 0); GL11.glRotated(0, -320, 320, 0); if(renderer!=null) transformFirstPersonItemRefl(renderer, 0.0f, 1.0f); float ns1 = MathHelper.sin(ss*3); float ns2 = MathHelper.sin(ss*4.9415927f); GL11.glRotated(-ns1*60, -90, -ns2, 10); GL11.glRotated(-ns1*110, 15, ns2, 0); } break;
            case 13: if(renderer!=null) transformFirstPersonItemRefl(renderer, equip, 0.0f); GL11.glTranslatef(0.1f, 0.2f, 0.3f); GL11.glRotated(-si*30, -5, 0, 9); GL11.glRotated(-si*10, 1, -0.4, -0.5); break;
            case 14: GL11.glTranslatef(0.56f, -0.42f, -0.72f); GL11.glTranslatef(0.1f*si, 0, -0.22f*si); GL11.glTranslatef(0, s1*-0.15f, 0); GL11.glRotated(s1*45, 0, 1, 0); GL11.glRotated(s1*-20, 0, 1, 0); GL11.glRotated(si*-20, 0, 0, 1); GL11.glRotated(si*-80, 1, 0, 0); break;
            case 15: { GL11.glTranslated(-0.1, 0.15, 0); if(renderer!=null) transformFirstPersonItemRefl(renderer, 0.0f, 0.0f); float ss2 = MathHelper.sin(ss*2.9415927f); GL11.glTranslatef(-0.05f, 0, 0.35f); GL11.glRotated(-ss2*30, -15, ss2, 10); GL11.glRotated(-ss2*70, 5, -ss2, 0); } break;
            case 16: if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 17: GL11.glTranslated(0.08, -0.14, -0.05); GlStateManager.translate(-0.35f, 0.2f, 0); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 18: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*20, sw/2, 1, 4); GlStateManager.rotate(-sw*30, 1, sw/3, 0); } break;
            case 19: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GL11.glRotated(-sw*22, sw/2, 0, 9); GL11.glRotated(-sw*50, 0.8, sw/2, 0); } break;
            case 20: { GL11.glTranslated(0.08, 0.08, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*70, 5, 13, 50); } break;
            case 21: { GL11.glTranslated(0.84, -0.77, -1.1); GlStateManager.translate(0.56f, -0.52f, -0.72f); GlStateManager.rotate(45, 0, 1, 0); } break;
            case 22: { GL11.glTranslated(0, 0.03, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GL11.glRotated(-sw*-15, sw/2, 1, 4); GL11.glRotated(-sw*7.5, 1, sw/3, 0); } break;
            case 23: GlStateManager.translate(-0.5f, 0.3f, -0.2f); GlStateManager.rotate(32, 0, 1, 0); GlStateManager.rotate(-70, 1, 0, 0); GlStateManager.rotate(40, 0, 1, 0); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 24: GL11.glTranslated(-0.01, 0.03, -0.24); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 25: { GL11.glTranslated(-0.04, 0.06, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(sw*8, -sw, 0, 2); GlStateManager.rotate(sw*22, 1, -sw/3, 0); } break;
            case 26: { GL11.glTranslated(0, 0.19, 0); GlStateManager.translate(0.41f, -0.25f, -0.56f); GlStateManager.rotate(35, 0, 1.5f, 0); float sl = MathHelper.sin(sp*sp/64f*3.1415927f); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(sl*-5, 0, 0, 0); GlStateManager.rotate(sw*-12, 0, 0, 1); GlStateManager.rotate(sw*-65, 1, 0, 0); } break;
            case 27: { GL11.glTranslated(-0.25, 0.45, 0.8); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.translate(0.6f, 0.3f, -0.6f+ -sw*0.7f); GlStateManager.rotate(330, 0, 0, 0.1f); GlStateManager.rotate(325, 0, 0.1f, 0); GlStateManager.rotate(350, 0.1f, 0, 0); } break;
            case 28: { float sw = MathHelper.sin(sp*sp*3.1415927f); GlStateManager.rotate(-sw*20, sw/2, 0, 9); GlStateManager.rotate(-sw*30, 1, sw/2, 0); } break;
            case 29: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(0, -2, 0, 10); GlStateManager.rotate(-sw*25, 0.5f, 0, 1); } break;
            case 30: { float x1 = MathHelper.sin(sp*sp*3.1415927f); float x2 = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-x1*20, 0, 1, 0); GlStateManager.rotate(-x2*20, 0, 0, 1); GlStateManager.rotate(-x2*80, 1, 0, 0); } break;
            case 31: GL11.glTranslated(0, -0.16, 0); GL11.glTranslatef(-0.35f, 0.1f, 0); GL11.glTranslatef(-0.05f, -0.1f, 0.1f); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 32: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GL11.glRotatef(-sw*100, -9, 5, 9); } break;
            case 33: GlStateManager.translate(0.56f, -0.52f, -0.72f); GlStateManager.rotate(45, 0, 1, 0); float sw33 = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(sw33*-80, 1, 0, 0); GlStateManager.scale(0.4f, 0.4f, 0.4f); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 34: GlStateManager.translate(0.56f, -0.52f, -0.72f); GlStateManager.rotate(45, 0, 1, 0); float p134 = MathHelper.sin(sp*sp*3.1415927f); float p234 = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(p134*-10, 1, 1, 1); GlStateManager.rotate(p234*-10, 1, 1, 1); GlStateManager.rotate(p234*-10, 1, 1, 1); GlStateManager.scale(0.4f, 0.4f, 0.4f); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 35: GL11.glTranslated(0, 0.1, -0.12); GL11.glTranslated(0.08, -0.1, -0.3); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 36: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*30, -8, -0.2f, 9); } break;
            case 37: { GL11.glTranslated(0.08, 0.02, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*41, 1.1f, 0.8f, -0.3f); } break;
            case 38: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*8.5f, sw/2, 1, 4); GlStateManager.rotate(-sw*6, 1, sw/3, 0); } break;
            case 39: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(sw*50/9f, -sw, 0, 90); GlStateManager.rotate(sw*50, 200, -sw/2, 0); } break;
            case 40: { float f1 = MathHelper.sin(sp*sp*3.1415927f); GlStateManager.rotate(-f1*45, 0, 0, 1); GlStateManager.rotate(0, 1.5f, 0, 0); } break;
            case 41: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*29, sw/2, 1, 0.5f); GlStateManager.rotate(-sw*43, 1, sw/3, 0); } break;
            case 42: { long cur = System.currentTimeMillis(); delay += (cur-lastUpdate)*360f/850f; lastUpdate=cur; if(delay>360f)delay=0; GlStateManager.rotate(delay, 0, 0, -0.1f); if(renderer!=null) doBlockTransformationsRefl(renderer); } break;
            case 43: { GL11.glTranslated(-0.08, 0.12, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*32.5f, sw/2, 1, 4); GlStateManager.rotate(-sw*60, 1, sw/3, 0); } break;
            case 44: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.translate(-0.2f, 0.45f, 0.25f); GlStateManager.rotate(-sw*20, -5, -5, 9); } break;
            case 45: { GL11.glTranslated(0.14, -0.1, -0.24); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.translate(-0.36f, 0.25f, -0.06f); GlStateManager.rotate(-sw*35, -8, 0, 9); GlStateManager.rotate(-sw*70, 1, 0.4f, 0); } break;
            case 46: GlStateManager.translate(0.56f, -0.52f, -0.72f); GlStateManager.rotate(45, 0, 1, 0); GlStateManager.rotate((sp*0.8f-sp*sp*0.8f)*-90, 0, 1, 0); GlStateManager.scale(0.37f, 0.37f, 0.37f); break;
            case 47: GL11.glTranslated(0, -0.1, 0); GlStateManager.translate(0.56f, -0.42f, -0.72f); GlStateManager.rotate(30, 0, 1, 0); GlStateManager.rotate(MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f)*-30, 0, 1, 0); GlStateManager.scale(0.4f, 0.4f, 0.4f); break;
            case 48: { GL11.glTranslated(0.02, 0.02, 0); GL11.glTranslated(0.4, -0.06, -0.46); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(sw*12.5f, -sw, 0, 9); GlStateManager.rotate(sw*15, 1, -sw/2, 0); } break;
            case 49: { GL11.glTranslated(-0.6, 0.2, 0.11); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*27.5f, -8, 0, 9); GlStateManager.rotate(-sw*45, 1, sw/2, 0); if(renderer!=null) doBlockTransformationsRefl(renderer); GL11.glTranslated(-0.08, -1.25, 1.25); } break;
            case 50: break;
            case 51: { GL11.glTranslated(0.08, -0.11, -0.07); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.translate(-0.4f, 0.28f, 0); GlStateManager.rotate(-sw*35, -8, 0, 9); GlStateManager.rotate(-sw*70, 1, -0.4f, 0); } break;
            case 52: { float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GL11.glRotatef(sw*15, -sw, 0, 9); GL11.glRotatef(sw*40, 1, -sw/2, 0); } break;
            case 53: { GL11.glTranslated(0, 0.03, 0); float sw = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(-sw*37, sw/2, 1, 4); GlStateManager.rotate(-sw*52, 1, sw/3, 0); } break;
            case 54: GlStateManager.translate(0.56f, -0.52f, -0.72f); GlStateManager.rotate(45, 0, 1, 0); float a154 = MathHelper.sin(sp*sp*3.1415927f); float a254 = MathHelper.sin(MathHelper.sqrt_float(sp)*3.1415927f); GlStateManager.rotate(a154*-20, 0, 1, 0); GlStateManager.rotate(a254*-20, 0, 0, 1); GlStateManager.rotate(a254*-40, 1, 0, 0); GlStateManager.scale(0.4f, 0.4f, 0.4f); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
            case 55: GL11.glTranslated(0, -0.18, -0.1); GlStateManager.translate(-0.5f, 0, 0); if(renderer!=null) doBlockTransformationsRefl(renderer); break;
        }
        ItemRendererState.spin = spin;
        ItemRendererState.delay = delay;
        ItemRendererState.lastUpdate = lastUpdate;
    }
public static Object getItemRenderer() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("itemRenderer");
            f.setAccessible(true);
            return f.get(mc);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField("field_175620_Y");
                f.setAccessible(true);
                return f.get(mc);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
