package keystrokesmod.runtime;

import keystrokesmod.module.impl.render.AlwaysBlock;
import keystrokesmod.module.impl.render.Animations;
import keystrokesmod.module.impl.render.Slow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;

/** Schema-safe implementation of the first-person animation Mixin. */
public final class ItemAnimationRuntime {
    private static float spin;
    private static float delay;
    private static long lastUpdate = System.currentTimeMillis();

    private ItemAnimationRuntime() {}

    /**
     * Renders only animated swords through a self-contained path. Lunar puts
     * a cancellable first-person callback ahead of vanilla's action switch;
     * when that callback owns the frame, redirects deeper in the switch never
     * become authoritative. Taking the sword frame at method HEAD makes Slow
     * and Sword Animation deterministic while leaving maps, food, bows, hands,
     * and every non-sword Lunar feature on the original renderer.
     */
    public static boolean renderSwordOverride(ItemRenderer renderer, ItemStack rendered, float partialTicks) {
        if (!isSword(rendered) || !Slow.isActive() && !Animations.isActive()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;

        float equipped = AccessorBridge.ItemRenderer_getEquippedProgress(renderer);
        float previous = AccessorBridge.ItemRenderer_getPrevEquippedProgress(renderer);
        float equip = 1.0F - (previous + (equipped - previous) * partialTicks);
        float swing = visualSwing(player, partialTicks);
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

        AccessorBridge.ItemRenderer_callRotateArroundXAndY(renderer, pitch, yaw);
        AccessorBridge.ItemRenderer_callSetLightMapFromPlayer(renderer, player);
        AccessorBridge.ItemRenderer_callRotateWithPlayerRotations(renderer, player, partialTicks);
        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        try {
            boolean blocking = player.getItemInUseCount() > 0 || player.isBlocking()
                    || AlwaysBlock.isActive() || ItemRendererState.isForceSwordBlockAnimationActive();
            if (blocking) {
                if (Animations.isActive()) {
                    apply(renderer, rendered, partialTicks);
                    block(renderer);
                } else {
                    transform(renderer, equip, 0.0F);
                    block(renderer);
                }
            } else {
                AccessorBridge.ItemRenderer_callDoItemUsedTransformations(renderer, swing);
                if (Animations.isActive() && Animations.renderMode == 1) {
                    apply(renderer, rendered, partialTicks);
                } else {
                    transform(renderer, equip, swing);
                }
            }
            if (Animations.isActive()) applyScaleOnly();
            renderer.renderItem(player, rendered, ItemCameraTransforms.TransformType.FIRST_PERSON);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();
        }
        return true;
    }

    public static float visualSwing(AbstractClientPlayer player, float partialTicks) {
        float vanilla = player.getSwingProgress(partialTicks);
        return Slow.getVisualSwingProgress(player, vanilla);
    }

    public static void blockingBase(ItemRenderer renderer, ItemStack rendered, float equip, float swing) {
        if (!Animations.isActive() || !isSword(rendered)) transform(renderer, equip, swing);
    }

    public static void normalBase(ItemRenderer renderer, ItemStack rendered, float equip, float swing) {
        if (!shouldApplyNormal(rendered)) transform(renderer, equip, swing);
    }

    public static void blockingAnimation(ItemRenderer renderer, ItemStack rendered, float partialTicks) {
        if (Animations.isActive() && isSword(rendered)) apply(renderer, rendered, partialTicks);
    }

    public static void scale(ItemRenderer renderer, ItemStack rendered, float partialTicks) {
        if (!Animations.isActive() || !isSword(rendered)) return;
        if (shouldApplyNormal(rendered)) apply(renderer, rendered, partialTicks);
        applyScaleOnly();
    }

    private static boolean shouldApplyNormal(ItemStack rendered) {
        Minecraft mc = Minecraft.getMinecraft();
        return Animations.isActive() && Animations.renderMode == 1 && mc.thePlayer != null
                && isSword(rendered) && mc.thePlayer.getItemInUseCount() <= 0
                && !mc.thePlayer.isBlocking() && !AlwaysBlock.enabled;
    }

    private static boolean isSword(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemSword;
    }

    private static boolean isBlocking(EntityPlayerSP player) {
        return player.getItemInUseCount() > 0 || player.isBlocking()
                || AlwaysBlock.isActive() || ItemRendererState.isForceSwordBlockAnimationActive();
    }

    private static void apply(ItemRenderer renderer, ItemStack rendered, float partialTicks) {
        float equipped = AccessorBridge.ItemRenderer_getEquippedProgress(renderer);
        float previous = AccessorBridge.ItemRenderer_getPrevEquippedProgress(renderer);
        float equipProgress = 1.0F - (previous + (equipped - previous) * partialTicks);
        AbstractClientPlayer player = Minecraft.getMinecraft().thePlayer;
        float swing = Slow.getVisualSwingProgress(player, player.getSwingProgress(partialTicks));
        GL11.glTranslated(Animations.blockPosX, Animations.blockPosY, Animations.blockPosZ);
        animate(renderer, equipProgress, swing);
    }

    private static void transform(ItemRenderer renderer, float equip, float swing) {
        AccessorBridge.ItemRenderer_callTransformFirstPersonItem(renderer, equip, swing);
    }

    private static void block(ItemRenderer renderer) {
        AccessorBridge.ItemRenderer_callDoBlockTransformations(renderer);
    }

    private static void applyScaleOnly() {
        double scale = Animations.scale / 100.0D * (1.0D + Animations.itemSize);
        scale = Math.max(0.1D, Math.min(scale, 2.0D));
        GL11.glScaled(scale, scale, scale);
    }

    private static void animate(ItemRenderer r, float equip, float sp) {
        float si = MathHelper.sin(MathHelper.sqrt_float(sp) * 3.1415927F);
        float ss = MathHelper.sqrt_float(sp);
        float s1 = MathHelper.sin(sp * sp * 3.1415927F);
        int m = Animations.modeIndex;
        if (m >= 16) transform(r, equip, 0.0F);
        switch (m) {
            case 0: GL11.glTranslated(0, .05, -.1); transform(r, equip, sp); break;
            case 1: GL11.glTranslated(0, -.1, 0); transform(r, equip / 2, 0); GL11.glTranslatef(.1F,.4F,-.1F); GL11.glRotated(-si*30,si/2,0,9); GL11.glRotated(-si*50,.8,si/2,0); break;
            case 2: GL11.glTranslated(0,-.1,0); transform(r,equip,0); GL11.glTranslatef(.1F,.4F,-.1F); GL11.glRotated(-si*35,-8,0,9); GL11.glRotated(-si*70,1.5,-.4,0); break;
            case 3: transform(r,equip*.5F,0); GL11.glRotated(-si*27.5,-8,0,9); GL11.glRotated(-si*45,1,si/2,0); GL11.glTranslated(-.1,.3,.1); break;
            case 4: float alt=MathHelper.sin(ss*3.1415927F-3); transform(r,equip,0); GL11.glRotated(-si*10,0,15,200); GL11.glRotated(-si*10,300,si/2,1); GL11.glTranslated(3.4,.3,-.4); GL11.glTranslatef(-2.1F,-.2F,.1F); GL11.glRotated(alt*13,-10,-1.4,-10); break;
            case 5: GL11.glTranslated(0,.05,0); transform(r,equip,0); break;
            case 6: GL11.glRotated(spin,0,0,-.1); transform(r,equip,0); spin=-(System.currentTimeMillis()/2L%360L); break;
            case 7: GL11.glTranslatef(.56F,-.52F,-.72F); GL11.glRotatef(45,0,1,0); GL11.glRotatef(s1*-20,0,1,0); GL11.glRotatef(si*-20,0,0,1); GL11.glRotatef(si*-40,1,0,0); GL11.glScalef(.4F,.4F,.4F); break;
            case 8: transform(r,equip/2,0); GL11.glRotated(-si*20,si/2,0,9); GL11.glRotated(-si*30,1,si/2,0); break;
            case 9: transform(r,equip/2,sp); GL11.glRotated(si*15,-si,0,9); GL11.glRotated(si*40,1,-si/2,0); break;
            case 10: transform(r,equip/2,sp); GL11.glRotated(si*30,-si,0,9); GL11.glRotated(si*40,1,-si,0); break;
            case 11: transform(r,equip,0); GL11.glTranslatef(-.05F,.2F,0); GL11.glRotated(-si*35,-8,0,9); GL11.glRotated(-si*70,1,-.4,0); break;
            case 12: GL11.glTranslated(-.1,.09,0); GL11.glRotated(0,-320,320,0); transform(r,0,1); float n1=MathHelper.sin(ss*3),n2=MathHelper.sin(ss*4.9415927F); GL11.glRotated(-n1*60,-90,-n2,10); GL11.glRotated(-n1*110,15,n2,0); break;
            case 13: transform(r,equip,0); GL11.glTranslatef(.1F,.2F,.3F); GL11.glRotated(-si*30,-5,0,9); GL11.glRotated(-si*10,1,-.4,-.5); break;
            case 14: GL11.glTranslatef(.56F,-.42F,-.72F); GL11.glTranslatef(.1F*si,0,-.22F*si); GL11.glTranslatef(0,s1*-.15F,0); GL11.glRotated(s1*45,0,1,0); GL11.glRotated(s1*-20,0,1,0); GL11.glRotated(si*-20,0,0,1); GL11.glRotated(si*-80,1,0,0); break;
            case 15: GL11.glTranslated(-.1,.15,0); transform(r,0,0); float q=MathHelper.sin(ss*2.9415927F); GL11.glTranslatef(-.05F,0,.35F); GL11.glRotated(-q*30,-15,q,10); GL11.glRotated(-q*70,5,-q,0); break;
            case 16: block(r); break;
            case 17: GL11.glTranslated(.08,-.14,-.05); GlStateManager.translate(-.35F,.2F,0); block(r); break;
            case 18: GlStateManager.rotate(-si*20,si/2,1,4); GlStateManager.rotate(-si*30,1,si/3,0); break;
            case 19: GL11.glRotated(-si*22,si/2,0,9); GL11.glRotated(-si*50,.8,si/2,0); break;
            case 20: GL11.glTranslated(.08,.08,0); GlStateManager.rotate(-si*70,5,13,50); break;
            case 21: GL11.glTranslated(.84,-.77,-1.1); GlStateManager.translate(.56F,-.52F,-.72F); GlStateManager.rotate(45,0,1,0); break;
            case 22: GL11.glTranslated(0,.03,0); GlStateManager.rotate(si*15,si/2,1,4); GlStateManager.rotate(-si*7.5F,1,si/3,0); break;
            case 23: GlStateManager.translate(-.5F,.3F,-.2F); GlStateManager.rotate(32,0,1,0); GlStateManager.rotate(-70,1,0,0); GlStateManager.rotate(40,0,1,0); block(r); break;
            case 24: GL11.glTranslated(-.01,.03,-.24); block(r); break;
            case 25: GL11.glTranslated(-.04,.06,0); GlStateManager.rotate(si*8,-si,0,2); GlStateManager.rotate(si*22,1,-si/3,0); break;
            case 26: GL11.glTranslated(0,.19,0); GlStateManager.translate(.41F,-.25F,-.56F); GlStateManager.rotate(35,0,1.5F,0); GlStateManager.rotate(si*-12,0,0,1); GlStateManager.rotate(si*-65,1,0,0); break;
            case 27: GL11.glTranslated(-.25,.45,.8); GlStateManager.translate(.6F,.3F,-.6F-si*.7F); GlStateManager.rotate(330,0,0,.1F); GlStateManager.rotate(325,0,.1F,0); GlStateManager.rotate(350,.1F,0,0); break;
            case 28: GlStateManager.rotate(-s1*20,s1/2,0,9); GlStateManager.rotate(-s1*30,1,s1/2,0); break;
            case 29: GlStateManager.rotate(0,-2,0,10); GlStateManager.rotate(-si*25,.5F,0,1); break;
            case 30: GlStateManager.rotate(-s1*20,0,1,0); GlStateManager.rotate(-si*20,0,0,1); GlStateManager.rotate(-si*80,1,0,0); break;
            case 31: GL11.glTranslated(0,-.16,0); GL11.glTranslatef(-.35F,.1F,0); GL11.glTranslatef(-.05F,-.1F,.1F); block(r); break;
            case 32: GL11.glRotatef(-si*100,-9,5,9); break;
            case 33: GlStateManager.translate(.56F,-.52F,-.72F); GlStateManager.rotate(45,0,1,0); GlStateManager.rotate(si*-80,1,0,0); GlStateManager.scale(.4F,.4F,.4F); block(r); break;
            case 34: GlStateManager.translate(.56F,-.52F,-.72F); GlStateManager.rotate(45,0,1,0); GlStateManager.rotate(s1*-10,1,1,1); GlStateManager.rotate(si*-20,1,1,1); GlStateManager.scale(.4F,.4F,.4F); block(r); break;
            case 35: GL11.glTranslated(0,.1,-.12); GL11.glTranslated(.08,-.1,-.3); block(r); break;
            case 36: GlStateManager.rotate(-si*30,-8,-.2F,9); break;
            case 37: GL11.glTranslated(.08,.02,0); GlStateManager.rotate(-si*41,1.1F,.8F,-.3F); break;
            case 38: GlStateManager.rotate(-si*8.5F,si/2,1,4); GlStateManager.rotate(-si*6,1,si/3,0); break;
            case 39: GlStateManager.rotate(si*50/9F,-si,0,90); GlStateManager.rotate(si*50,200,-si/2,0); break;
            case 40: GlStateManager.rotate(-s1*45,0,0,1); break;
            case 41: GlStateManager.rotate(-si*29,si/2,1,.5F); GlStateManager.rotate(-si*43,1,si/3,0); break;
            case 42: GlStateManager.rotate(delay,0,0,-.1F); long now=System.currentTimeMillis(); delay+=(now-lastUpdate)*360F/850F; lastUpdate=now; if(delay>360)delay=0; block(r); break;
            case 43: GL11.glTranslated(-.08,.12,0); GlStateManager.rotate(-si*32.5F,si/2,1,4); GlStateManager.rotate(-si*60,1,si/3,0); break;
            case 44: GlStateManager.translate(-.2F,.45F,.25F); GlStateManager.rotate(-si*20,-5,-5,9); break;
            case 45: GL11.glTranslated(.14,-.1,-.24); GlStateManager.translate(-.36F,.25F,-.06F); GlStateManager.rotate(-si*35,-8,0,9); GlStateManager.rotate(-si*70,1,.4F,0); break;
            case 46: GlStateManager.translate(.56F,-.52F,-.72F); GlStateManager.rotate(45,0,1,0); GlStateManager.rotate((sp*.8F-sp*sp*.8F)*-90,0,1,0); GlStateManager.scale(.37F,.37F,.37F); break;
            case 47: GL11.glTranslated(0,-.1,0); GlStateManager.translate(.56F,-.42F,-.72F); GlStateManager.rotate(30,0,1,0); GlStateManager.rotate(si*-30,0,1,0); GlStateManager.scale(.4F,.4F,.4F); break;
            case 48: GL11.glTranslated(.02,.02,0); GL11.glTranslated(.4,-.06,-.46); GlStateManager.rotate(si*12.5F,-si,0,9); GlStateManager.rotate(si*15,1,-si/2,0); break;
            case 49: GL11.glTranslated(-.6,.2,.11); GlStateManager.rotate(-si*27.5F,-8,0,9); GlStateManager.rotate(-si*45,1,si/2,0); block(r); GL11.glTranslated(-.08,-1.25,1.25); break;
            case 50: break;
            case 51: GL11.glTranslated(.08,-.11,-.07); GlStateManager.translate(-.4F,.28F,0); GlStateManager.rotate(-si*35,-8,0,9); GlStateManager.rotate(-si*70,1,-.4F,0); break;
            case 52: GL11.glRotatef(si*15,-si,0,9); GL11.glRotatef(si*40,1,-si/2,0); break;
            case 53: GL11.glTranslated(0,.03,0); GlStateManager.rotate(-si*37,si/2,1,4); GlStateManager.rotate(-si*52,1,si/3,0); break;
            case 54: GlStateManager.translate(.56F,-.52F,-.72F); GlStateManager.rotate(45,0,1,0); GlStateManager.rotate(s1*-20,0,1,0); GlStateManager.rotate(si*-20,0,0,1); GlStateManager.rotate(si*-40,1,0,0); GlStateManager.scale(.4F,.4F,.4F); block(r); break;
            case 55: GL11.glTranslated(0,-.18,-.1); GlStateManager.translate(-.5,0,0); block(r); break;
        }
    }
}
