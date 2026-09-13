package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
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
    private static final float MIN_OUTLINE_THICKNESS = 0.05f;
    /** Device pixels the hotbar's strokes fade over; see RoundedUtils.drawRoundOutline. */
    private static final float OUTLINE_SOFTNESS = 1.0f;
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
        boolean barOutline = outline.isToggled() && !individualOutlines;
        if (barOutline) {
            RoundedUtils.drawRoundOutline(barLeft, barTop, barWidth, barHeight, radius,
                    borderThickness, new Color(background.getColor(), true),
                    strokeColor(borderThickness, scaleFactor), OUTLINE_SOFTNESS);
        }
        else {
            // Through the rounded-rect shader, which antialiases its edge. The polygon fan it replaces
            // stepped its corners in three-degree chords with no smoothing, which is the jagged,
            // low-quality roundness on the bar and the selection.
            RoundedUtils.drawRound(barLeft, barTop, barWidth, barHeight, radius, background.getColor());
        }

        if (showXP.isToggled()) {
            float xpLeft = barLeft;
            float xpTop = barTop - 4.0f * uiScale;
            float xpW = barWidth;
            float xpH = 3.0f * uiScale;
            float xpR = Math.min(radius, 1.5f);
            float target = Math.max(0.0f, Math.min(1.0f, player.experience));
            xpProgress = Float.isNaN(xpProgress) ? target : lerp(xpProgress, target, smoothing(dtMs));
            RoundedUtils.drawRound(xpLeft, xpTop, xpW, xpH, xpR, xpBackground.getColor());
            if (xpProgress > 0.001f) {
                RoundedUtils.drawRound(xpLeft, xpTop, Math.max(xpR * 2.0f, xpW * xpProgress), xpH, xpR,
                        xpColor.getColor());
            }
            if (showLevel.isToggled() && player.experienceLevel > 0) {
                String level = String.valueOf(player.experienceLevel);
                int width = mc.fontRendererObj.getStringWidth(level);
                float lx = sw / 2.0f - width / 2.0f;
                float ly = xpTop - mc.fontRendererObj.FONT_HEIGHT - 1.0f;
                mc.fontRendererObj.drawStringWithShadow(level, lx, ly, levelColor.getColor());
            }
        }

        // The selection fills its slot right up to the outline's solid edge. The outline shader
        // centres its stroke a device pixel outside the rect, so that edge is a device pixel in from
        // the rect whatever the thickness, and a rounded rect there is concentric with the stroke.
        // The selection's own antialiased edge reaches a pixel further out: against a stroke drawn
        // over it (one per slot) that pixel sits under the stroke, and against one drawn beneath it
        // (the whole bar) the selection stops a pixel short so the two never overlap. The previous
        // layout left a visible gap all round, which read as a selection that was not filled.
        float devicePixel = 1.0f / scaleFactor;
        float strokeDevice = borderThickness * scaleFactor;
        float slotRadius = Math.min(radius, 6.0f * uiScale);
        float selTop = barTop + inset;
        float selBottom = barTop + barHeight - inset;
        float selInsetX;
        float selRadius;
        if (individualOutlines) {
            // A stroke thinner than a device pixel cannot cover the selection's edge, so the
            // selection steps back by what the stroke is missing.
            float depth = Math.max(1.0f, 2.0f - strokeDevice) * devicePixel;
            selInsetX = depth;
            selTop += depth;
            selBottom -= depth;
            selRadius = concentricRadius(slotRadius, depth, borderThickness, scaleFactor, true);
        } else {
            float depth = barOutline ? Math.max(inset, 2.0f * devicePixel) : inset;
            float extra = depth - inset;
            selInsetX = extra;
            selTop += extra;
            selBottom -= extra;
            selRadius = concentricRadius(radius, depth, borderThickness, scaleFactor, barOutline);
        }
        float targetSelX = barLeft + inset + slot * slotWidth + inset;
        if (animated.isToggled()) {
            selectionX = Float.isNaN(selectionX) ? targetSelX : lerp(selectionX, targetSelX, smoothing(dtMs));
        } else {
            selectionX = targetSelX;
        }
        float selLeft = selectionX + selInsetX;
        float selRight = selectionX + slotBoxWidth - selInsetX;
        selRadius = Math.min(selRadius, Math.min(selRight - selLeft, selBottom - selTop) * 0.5f);
        RoundedUtils.drawRound(selLeft, selTop, selRight - selLeft, selBottom - selTop,
                Math.max(0.0f, selRadius), selectionColor.getColor());

        if (individualOutlines) {
            Color transparent = new Color(0, 0, 0, 0);
            Color border = strokeColor(borderThickness, scaleFactor);
            for (int i = 0; i < 9; i++) {
                float slotLeft = barLeft + inset + i * slotWidth + inset;
                RoundedUtils.drawRoundOutline(slotLeft, barTop + inset,
                        slotBoxWidth, slotHeight,
                        slotRadius, borderThickness, transparent, border, OUTLINE_SOFTNESS);
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

    /**
     * Corner radius of the rounded rect lying `depth` inside another, so the pair stays concentric.
     *
     * For an outlined rect the shader rounds the shape a device pixel outside it with the stroke
     * centred there, hence the half stroke and the pixel.
     */
    private static float concentricRadius(float radius, float depth, float stroke, float scaleFactor,
                                          boolean outlined) {
        float result = outlined
                ? radius - depth - stroke * 0.5f + 1.0f / scaleFactor
                : radius - depth;
        return Math.max(0.0f, result);
    }

    /**
     * The outline colour, fainter below a device pixel of thickness.
     *
     * A stroke cannot be drawn narrower than its falloff, so under a pixel it reads thinner only by
     * reading lighter. At a pixel and above the colour is used as set.
     */
    private Color strokeColor(float thickness, float scaleFactor) {
        int argb = outlineColor.getColor();
        float coverage = Math.min(1.0f, 0.3f + thickness * scaleFactor * 0.7f);
        int alpha = Math.round(((argb >>> 24) & 0xFF) * coverage);
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, alpha);
    }

    private float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
