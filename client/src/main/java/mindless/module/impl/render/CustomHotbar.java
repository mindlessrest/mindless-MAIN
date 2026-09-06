package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class CustomHotbar extends Module {

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 22;
    private static final int SLOT_WIDTH = 20;

    private final ColorSetting background;
    private final SliderSetting rounding;
    private final ColorSetting selectionColor;
    private final ButtonSetting animated;
    private final SliderSetting selectionSpeed;
    private final ButtonSetting itemBob;
    private final ButtonSetting showXP;
    private final ColorSetting xpBackground;
    private final ColorSetting xpColor;
    private final ButtonSetting showLevel;
    private final ColorSetting levelColor;

    private float selectionX = Float.NaN;
    private float xpProgress = Float.NaN;
    private long lastNanos = 0L;

    public CustomHotbar() {
        super("CustomHotbar", "Replaces the vanilla hotbar and XP bar.", category.render);
        this.registerSetting(background = new ColorSetting("Background", 0, 0, 0, 140));
        this.registerSetting(rounding = new SliderSetting("Rounding", 4.0, 0.0, 10.0, 0.5));
        this.registerSetting(selectionColor = new ColorSetting("Selection", 255, 255, 255, 70));
        this.registerSetting(animated = new ButtonSetting("Animated selection", true));
        this.registerSetting(selectionSpeed = new SliderSetting("Selection speed", 0.35, 0.05, 1.0, 0.05));
        this.registerSetting(itemBob = new ButtonSetting("Item bob", true));
        this.registerSetting(showXP = new ButtonSetting("XP bar", true));
        this.registerSetting(xpBackground = new ColorSetting("XP background", 0, 0, 0, 150));
        this.registerSetting(xpColor = new ColorSetting("XP fill", 120, 214, 96, 255));
        this.registerSetting(showLevel = new ButtonSetting("XP level", true));
        this.registerSetting(levelColor = new ColorSetting("Level color", 128, 220, 96, 255));
    }

    @Override
    public void guiUpdate() {
        selectionSpeed.setVisible(animated.isToggled(), this);
        xpBackground.setVisible(showXP.isToggled(), this);
        xpColor.setVisible(showXP.isToggled(), this);
        levelColor.setVisible(showLevel.isToggled(), this);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Pre event) {
        if (event.type == RenderGameOverlayEvent.ElementType.EXPERIENCE) {
            event.setCanceled(true);
            return;
        }
        if (event.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        if (!Utils.nullCheck() || !(mc.getRenderViewEntity() instanceof EntityPlayer)) return;

        event.setCanceled(true);
        render(event.resolution, (EntityPlayer) mc.getRenderViewEntity(), event.partialTicks);
    }

    private void render(ScaledResolution res, EntityPlayer player, float partialTicks) {
        int sw = res.getScaledWidth();
        int sh = res.getScaledHeight();
        float barLeft = sw / 2.0f - BAR_WIDTH / 2.0f;
        float barTop = sh - BAR_HEIGHT;
        float radius = (float) rounding.getInput();
        int slot = player.inventory.currentItem;

        long now = System.nanoTime();
        float dtMs = lastNanos == 0L ? 16.6f : Math.min(120.0f, (now - lastNanos) / 1.0E6f);
        lastNanos = now;

        // Background panel.
        RenderUtils.drawRoundedRectangle(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT,
                radius, background.getColor());

        // XP bar hugging the top edge of the panel + centered level number above it.
        if (showXP.isToggled()) {
            float xpLeft = barLeft;
            float xpTop = barTop - 4.0f;
            float xpW = BAR_WIDTH;
            float xpH = 3.0f;
            float xpR = Math.min(radius, 1.5f);
            float target = Math.max(0.0f, Math.min(1.0f, player.experience));
            xpProgress = Float.isNaN(xpProgress) ? target : lerp(xpProgress, target, smoothing(dtMs));
            RenderUtils.drawRoundedRectangle(xpLeft, xpTop, xpLeft + xpW, xpTop + xpH, xpR, xpBackground.getColor());
            if (xpProgress > 0.001f) {
                RenderUtils.drawRoundedRectangle(xpLeft, xpTop, xpLeft + Math.max(xpR * 2.0f, xpW * xpProgress),
                        xpTop + xpH, xpR, xpColor.getColor());
            }
            if (showLevel.isToggled() && player.experienceLevel > 0) {
                String level = String.valueOf(player.experienceLevel);
                int width = mc.fontRendererObj.getStringWidth(level);
                float lx = sw / 2.0f - width / 2.0f;
                float ly = xpTop - mc.fontRendererObj.FONT_HEIGHT - 1.0f;
                mc.fontRendererObj.drawStringWithShadow(level, lx, ly, levelColor.getColor());
            }
        }

        // Animated selection highlight.
        float targetSelX = barLeft + 1.0f + slot * SLOT_WIDTH;
        if (animated.isToggled()) {
            selectionX = Float.isNaN(selectionX) ? targetSelX : lerp(selectionX, targetSelX, smoothing(dtMs));
        } else {
            selectionX = targetSelX;
        }
        RenderUtils.drawRoundedRectangle(selectionX, barTop + 1.0f, selectionX + (SLOT_WIDTH - 2.0f),
                barTop + BAR_HEIGHT - 1.0f, Math.min(radius, 6.0f), selectionColor.getColor());

        // Items.
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem renderItem = mc.getRenderItem();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null) continue;
            int ix = (int) (barLeft + 3.0f + i * SLOT_WIDTH);
            int iy = (int) (barTop + 3.0f);
            float bob = itemBob.isToggled() ? stack.animationsToGo - partialTicks : 0.0f;
            if (bob > 0.0f) {
                GlStateManager.pushMatrix();
                float s = 1.0f + bob / 5.0f;
                GlStateManager.translate(ix + 8, iy + 12, 0.0f);
                GlStateManager.scale(1.0f / s, (s + 1.0f) / 2.0f, 1.0f);
                GlStateManager.translate(-(ix + 8), -(iy + 12), 0.0f);
            }
            renderItem.renderItemAndEffectIntoGUI(stack, ix, iy);
            if (bob > 0.0f) GlStateManager.popMatrix();
            renderItem.renderItemOverlays(mc.fontRendererObj, stack, ix, iy);
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float smoothing(float dtMs) {
        return Math.max(0.0f, Math.min(1.0f, (float) selectionSpeed.getInput() * dtMs / 16.6f));
    }

    private float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
