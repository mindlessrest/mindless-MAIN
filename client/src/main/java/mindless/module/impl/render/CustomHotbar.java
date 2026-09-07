package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public class CustomHotbar extends Module {

    private static final float BASE_BAR_HEIGHT = 22.0f;
    private static final float MIN_OUTLINE_THICKNESS = 0.1f;
    private final ItemStack[] overlayStacks = new ItemStack[9];
    private final int[] overlayX = new int[9];
    private final int[] overlayY = new int[9];
    private static final float BASE_SLOT_WIDTH = 20.0f;

    private final ColorSetting background;
    private final SliderSetting rounding;
    private final ColorSetting selectionColor;
    private final SliderSetting scale;
    private final SliderSetting verticalOffset;
    private final SliderSetting slotSpacing;
    private final SliderSetting itemScale;
    private final ButtonSetting outline;
    private final ColorSetting outlineColor;
    private final SliderSetting outlineThickness;
    private final SliderSetting outlineMode;
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
        super("Custom Hotbar", "Replaces the vanilla hotbar and XP bar.", category.render);
        this.registerSetting(background = new ColorSetting("Background", 0, 0, 0, 140));
        this.registerSetting(rounding = new SliderSetting("Rounding", 4.0, 0.0, 10.0, 0.5));
        this.registerSetting(selectionColor = new ColorSetting("Selection", 255, 255, 255, 70));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.7, 1.5, 0.05));
        this.registerSetting(verticalOffset = new SliderSetting("Vertical offset", 0.0, 0.0, 60.0, 1.0));
        this.registerSetting(slotSpacing = new SliderSetting("Slot spacing", 0.0, -2.0, 8.0, 0.5));
        this.registerSetting(itemScale = new SliderSetting("Item scale", 1.0, 0.65, 1.3, 0.05));
        this.registerSetting(outline = new ButtonSetting("Outline", false));
        this.registerSetting(outlineColor = new ColorSetting("Outline color", 255, 255, 255, 70));
        this.registerSetting(outlineThickness = new SliderSetting("Outline thickness", "px", 1.0,
                MIN_OUTLINE_THICKNESS, 4.0, 0.05));
        this.registerSetting(outlineMode = new SliderSetting("Outline mode", 0, new String[]{"Whole bar", "Each slot"}));
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
        showLevel.setVisible(showXP.isToggled(), this);
        levelColor.setVisible(showXP.isToggled() && showLevel.isToggled(), this);
        outlineColor.setVisible(outline.isToggled(), this);
        outlineThickness.setVisible(outline.isToggled(), this);
        outlineMode.setVisible(outline.isToggled(), this);
    }

    /** Direct render entry used by the vanilla/Lunar HUD transformer path. */
    public static boolean renderReplacement(ScaledResolution resolution, float partialTicks) {
        Module module = ModuleManager.getModule(CustomHotbar.class);
        if (!(module instanceof CustomHotbar) || !module.isEnabled() || !Utils.nullCheck()
                || !(mc.getRenderViewEntity() instanceof EntityPlayer)) {
            return false;
        }
        try {
            ((CustomHotbar) module).render(resolution,
                    (EntityPlayer) mc.getRenderViewEntity(), partialTicks);
            return true;
        }
        catch (RuntimeException ignored) {
            // Preserve the vanilla hotbar if a third-party renderer leaves incompatible GL state.
            return false;
        }
    }

    public static boolean replacesExperienceBar() {
        Module module = ModuleManager.getModule(CustomHotbar.class);
        return module instanceof CustomHotbar && module.isEnabled();
    }

    @Override
    public void onEnable() {
        selectionX = Float.NaN;
        xpProgress = Float.NaN;
        lastNanos = 0L;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Pre event) {
        if (!Utils.nullCheck() || !(mc.getRenderViewEntity() instanceof EntityPlayer)) return;
        if (event.type == RenderGameOverlayEvent.ElementType.EXPERIENCE) {
            event.setCanceled(true);
            return;
        }
        if (event.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;

        try {
            render(event.resolution, (EntityPlayer) mc.getRenderViewEntity(), event.partialTicks);
            event.setCanceled(true);
        }
        catch (RuntimeException ignored) {
        }
    }

    private void render(ScaledResolution res, EntityPlayer player, float partialTicks) {
        int sw = res.getScaledWidth();
        int sh = res.getScaledHeight();
        int scaleFactor = Math.max(1, res.getScaleFactor());
        float uiScale = (float) scale.getInput();
        float slotWidth = (BASE_SLOT_WIDTH + (float) slotSpacing.getInput()) * uiScale;
        float barWidth = 2.0f * uiScale + slotWidth * 9.0f;
        float barHeight = BASE_BAR_HEIGHT * uiScale;
        float barLeft = sw / 2.0f - barWidth / 2.0f;
        float barTop = sh - barHeight - (float) verticalOffset.getInput();
        if (mc.currentScreen instanceof GuiChat) {
            // GuiChat owns the bottom 15 scaled pixels. Keep the cosmetic and its selection
            // animation fully above that input surface instead of blending both panels together.
            barTop -= 17.0f;
        }
        float radius = (float) rounding.getInput() * uiScale;
        int slot = player.inventory.currentItem;

        long now = System.nanoTime();
        float dtMs = lastNanos == 0L ? 16.6f : Math.min(120.0f, (now - lastNanos) / 1.0E6f);
        lastNanos = now;

        float inset = uiScale;
        float slotBoxWidth = Math.max(1.0f, slotWidth - inset * 2.0f);
        boolean individualOutlines = outline.isToggled() && (int) outlineMode.getInput() == 1;
        float slotHeight = Math.max(1.0f, barHeight - inset * 2.0f);
        float outlineGeometry = individualOutlines ? Math.min(slotBoxWidth, slotHeight) : barHeight;
        // Profiles are intentionally forward-compatible and may contain values outside the
        // current slider range. Clamp at render time as well, so a manually edited/legacy value
        // cannot invert the selector geometry or ask the outline shader for a nonsensical stroke.
        float borderThickness = clamp((float) outlineThickness.getInput() * uiScale,
                MIN_OUTLINE_THICKNESS * uiScale,
                Math.max(MIN_OUTLINE_THICKNESS * uiScale, outlineGeometry * 0.45f));
        if (outline.isToggled() && !individualOutlines) {
            RoundedUtils.drawRoundOutline(barLeft, barTop, barWidth, barHeight, radius,
                    borderThickness, new Color(background.getColor(), true),
                    new Color(outlineColor.getColor(), true));
        }
        else {
            RenderUtils.drawRoundedRectangle(barLeft, barTop, barLeft + barWidth, barTop + barHeight,
                    radius, background.getColor());
        }

        if (showXP.isToggled()) {
            float xpLeft = barLeft;
            float xpTop = barTop - 4.0f * uiScale;
            float xpW = barWidth;
            float xpH = 3.0f * uiScale;
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

        // the selection sits inside the per-slot stroke rather than under it: the outline
        // shader centres its stroke on the rect edge, so a fill on the same rect shows
        // through the inner half of the border and its rounded corners poke past it.
        float selectionInset = individualOutlines ? borderThickness : 0.0f;
        float targetSelX = barLeft + inset + slot * slotWidth + inset;
        if (animated.isToggled()) {
            selectionX = Float.isNaN(selectionX) ? targetSelX : lerp(selectionX, targetSelX, smoothing(dtMs));
        } else {
            selectionX = targetSelX;
        }
        RenderUtils.drawRoundedRectangle(selectionX + selectionInset, barTop + inset + selectionInset,
                selectionX + slotBoxWidth - selectionInset,
                barTop + barHeight - inset - selectionInset,
                Math.max(0.0f, Math.min(radius, 6.0f * uiScale) - selectionInset),
                selectionColor.getColor());

        if (individualOutlines) {
            float slotRadius = Math.min(radius, 6.0f * uiScale);
            Color transparent = new Color(0, 0, 0, 0);
            Color border = new Color(outlineColor.getColor(), true);
            for (int i = 0; i < 9; i++) {
                float slotLeft = barLeft + inset + i * slotWidth + inset;
                RoundedUtils.drawRoundOutline(slotLeft, barTop + inset,
                        slotBoxWidth, slotHeight,
                        slotRadius, borderThickness, transparent, border);
            }
        }

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            RenderItem renderItem = mc.getRenderItem();
            int slots = Math.min(9, player.inventory.mainInventory.length);
            for (int i = 0; i < slots; i++) {
                ItemStack stack = player.inventory.mainInventory[i];
                if (stack == null) continue;
                float centerX = barLeft + inset + i * slotWidth + slotWidth * 0.5f;
                float centerY = barTop + barHeight * 0.5f;
                // Land the sprite on the physical pixel grid. At a fractional origin a
                // 16px item resampled by an arbitrary scale picks up a half-texel skew and
                // the edges crawl as the bar animates.
                centerX = Math.round(centerX * scaleFactor) / (float) scaleFactor;
                centerY = Math.round(centerY * scaleFactor) / (float) scaleFactor;
                float bob = itemBob.isToggled() ? Math.max(0.0f, stack.animationsToGo - partialTicks) : 0.0f;
                float renderScale = uiScale * (float) itemScale.getInput();
                float bobX = bob > 0.0f ? 1.0f / (1.0f + bob / 5.0f) : 1.0f;
                float bobY = bob > 0.0f ? (2.0f + bob / 5.0f) * 0.5f : 1.0f;
                GlStateManager.pushMatrix();
                GlStateManager.translate(centerX, centerY, 0.0f);
                GlStateManager.scale(renderScale * bobX, renderScale * bobY, renderScale);
                try {
                    renderItem.renderItemAndEffectIntoGUI(stack, -8, -8);
                }
                finally {
                    GlStateManager.popMatrix();
                }

                // Overlays are drawn outside the item matrix. Inside it the stack count was
                // multiplied by the item scale and by the bob's non-uniform squash, so the
                // glyphs landed between pixels and smeared; the count is a fixed-size label,
                // not part of the sprite. Snapped to whole GUI pixels for the same reason.
                overlayStacks[i] = stack;
                overlayX[i] = Math.round(centerX - 8.0f);
                overlayY[i] = Math.round(centerY - 8.0f);
            }
        }
        finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // renderItemOverlayIntoGUI ends every branch with enableLighting and enableDepth.
        // Vanilla gets away with that because it calls it inside the item-lighting block and
        // disables lighting straight after. Drawing the overlays outside that block, which is
        // what keeps the stack counts unscaled, leaves nothing to turn lighting back off, and it
        // leaks into every text draw that follows -- chat, scoreboard, the whole HUD -- which is
        // what rendered them grey.
        boolean lightingWas = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderItem overlayRenderer = mc.getRenderItem();
        for (int i = 0; i < overlayStacks.length; i++) {
            ItemStack stack = overlayStacks[i];
            if (stack == null) continue;
            overlayStacks[i] = null;
            overlayRenderer.renderItemOverlays(mc.fontRendererObj, stack, overlayX[i], overlayY[i]);
        }
        if (!lightingWas) GlStateManager.disableLighting();
        if (!depthWas) GlStateManager.disableDepth();
    }

    private float smoothing(float dtMs) {
        return Math.max(0.0f, Math.min(1.0f, (float) selectionSpeed.getInput() * dtMs / 16.6f));
    }

    private float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
