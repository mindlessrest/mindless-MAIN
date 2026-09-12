package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * A crosshair of your own in place of the vanilla one.
 *
 * The vanilla crosshair is drawn with an inverting blend, which is why it is readable on anything.
 * Replacing it with flat colour loses that, so the outline is on by default: without something
 * dark behind it a thin bright cross disappears over snow and over sky both.
 */
public class Crosshair extends Module {

    private static final String[] STYLES = new String[]{"Cross", "Dot", "Cross and dot", "Circle", "Square", "Brackets"};
    private static final int STYLE_CROSS = 0;
    private static final int STYLE_DOT = 1;
    private static final int STYLE_CROSS_DOT = 2;
    private static final int STYLE_CIRCLE = 3;
    private static final int STYLE_SQUARE = 4;
    private static final int STYLE_BRACKETS = 5;

    private static final String[] REACTIONS = new String[]{"Off", "Expand", "Shrink", "Spin"};
    private static final int REACT_OFF = 0;
    private static final int REACT_EXPAND = 1;
    private static final int REACT_SHRINK = 2;
    private static final int REACT_SPIN = 3;

    private static final long REACTION_MS = 220L;

    private final SliderSetting style;
    private final SliderSetting gap;
    private final SliderSetting length;
    private final SliderSetting thickness;
    private final SliderSetting dotSize;
    private final ColorSetting color;
    private final ButtonSetting outline;
    private final ColorSetting outlineColor;
    private final ButtonSetting hideVanilla;

    private final SliderSetting reaction;
    private final SliderSetting reactionAmount;
    private final ButtonSetting targetColor;
    private final ColorSetting onTargetColor;

    private long hitAt;
    private boolean onTarget;

    public Crosshair() {
        super("Crosshair", "Replaces the vanilla crosshair.", category.render, 0);

        GroupSetting shape = new GroupSetting("Shape");
        registerSetting(shape);
        registerSetting(style = new SliderSetting(shape, "Style", STYLE_CROSS, STYLES));
        registerSetting(gap = new SliderSetting(shape, "Gap", "px", 3.0, 0.0, 14.0, 0.5));
        registerSetting(length = new SliderSetting(shape, "Length", "px", 5.0, 1.0, 20.0, 0.5));
        registerSetting(thickness = new SliderSetting(shape, "Thickness", "px", 1.0, 0.5, 5.0, 0.5));
        registerSetting(dotSize = new SliderSetting(shape, "Dot size", "px", 1.5, 0.5, 6.0, 0.5));

        GroupSetting look = new GroupSetting("Look");
        registerSetting(look);
        registerSetting(color = new ColorSetting(look, "Color", 255, 255, 255, 255));
        registerSetting(outline = new ButtonSetting(look, "Outline", true));
        registerSetting(outlineColor = new ColorSetting(look, "Outline color", 0, 0, 0, 160));
        registerSetting(hideVanilla = new ButtonSetting(look, "Hide vanilla", true));

        GroupSetting react = new GroupSetting("Reaction");
        registerSetting(react);
        registerSetting(reaction = new SliderSetting(react, "On hit", REACT_EXPAND, REACTIONS));
        registerSetting(reactionAmount = new SliderSetting(react, "Amount", "px", 4.0, 1.0, 14.0, 0.5));
        registerSetting(targetColor = new ButtonSetting(react, "Color on target", true));
        registerSetting(onTargetColor = new ColorSetting(react, "Target color", 255, 79, 163, 255));

        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        int selected = (int) style.getInput();
        boolean hasArms = selected != STYLE_DOT;
        boolean hasDot = selected == STYLE_DOT || selected == STYLE_CROSS_DOT;
        if (gap != null) gap.setVisible(hasArms, this);
        if (length != null) length.setVisible(hasArms, this);
        if (thickness != null) thickness.setVisible(hasArms, this);
        if (dotSize != null) dotSize.setVisible(hasDot, this);
        if (outlineColor != null) outlineColor.setVisible(outline != null && outline.isToggled(), this);
        if (reactionAmount != null) {
            reactionAmount.setVisible(reaction != null && (int) reaction.getInput() != REACT_OFF, this);
        }
        if (onTargetColor != null) {
            onTargetColor.setVisible(targetColor != null && targetColor.isToggled(), this);
        }
    }

    @Override
    public String getInfo() {
        return STYLES[(int) style.getInput()].toLowerCase();
    }

    /** Called by the hit effect path so the two react to the same moment. */
    public void onHit() {
        hitAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !Utils.nullCheck()) {
            return;
        }
        if (hideVanilla.isToggled()) {
            // Lunar's copy of this event class is not always cancelable, and calling setCanceled
            // on one that is not throws rather than being ignored.
            if (event.isCancelable()) {
                event.setCanceled(true);
            }
        }
        draw(event.resolution);
    }

    private void draw(ScaledResolution resolution) {
        if (mc.gameSettings.thirdPersonView != 0 || mc.gameSettings.showDebugInfo) {
            return;
        }

        onTarget = lookingAtLiving();
        float centerX = resolution.getScaledWidth() / 2.0f;
        float centerY = resolution.getScaledHeight() / 2.0f;

        float since = System.currentTimeMillis() - hitAt;
        float pulse = since < REACTION_MS ? 1.0f - since / REACTION_MS : 0.0f;
        int mode = (int) reaction.getInput();
        float push = 0.0f;
        float spin = 0.0f;
        if (pulse > 0.0f) {
            float amount = (float) reactionAmount.getInput() * pulse;
            if (mode == REACT_EXPAND) push = amount;
            else if (mode == REACT_SHRINK) push = -Math.min(amount, (float) gap.getInput());
            else if (mode == REACT_SPIN) spin = pulse * 180.0f;
        }

        int tint = targetColor.isToggled() && onTarget
                ? (onTargetColor.getRGB() | (onTargetColor.getAlpha() << 24))
                : (color.getRGB() | (color.getAlpha() << 24));
        int edge = outlineColor.getRGB() | (outlineColor.getAlpha() << 24);
        boolean drawOutline = outline.isToggled();

        RenderUtils.beginTextPass();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0f);
        if (spin != 0.0f) {
            GlStateManager.rotate(spin, 0.0f, 0.0f, 1.0f);
        }

        int selected = (int) style.getInput();
        if (selected != STYLE_DOT) {
            if (drawOutline) {
                shape(selected, push, 1.0f, edge);
            }
            shape(selected, push, 0.0f, tint);
        }
        if (selected == STYLE_DOT || selected == STYLE_CROSS_DOT) {
            float d = (float) dotSize.getInput() * 0.5f;
            if (drawOutline) {
                RenderUtils.drawRect(-d - 1.0f, -d - 1.0f, d + 1.0f, d + 1.0f, edge);
            }
            RenderUtils.drawRect(-d, -d, d, d, tint);
        }

        GlStateManager.popMatrix();
        RenderUtils.restoreGuiTextState();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** grow is the outline's extra pixel on every side; zero for the fill pass. */
    private void shape(int selected, float push, float grow, int rgb) {
        float g = (float) gap.getInput() + push;
        float len = (float) length.getInput();
        float half = (float) thickness.getInput() * 0.5f;

        if (g < 0.0f) {
            g = 0.0f;
        }

        if (selected == STYLE_CIRCLE) {
            ring(g + len * 0.5f, half + grow, rgb);
            return;
        }

        if (selected == STYLE_SQUARE) {
            float r = g + len;
            RenderUtils.drawRect(-r - grow, -r - grow, r + grow, -r + half * 2 + grow, rgb);
            RenderUtils.drawRect(-r - grow, r - half * 2 - grow, r + grow, r + grow, rgb);
            RenderUtils.drawRect(-r - grow, -r - grow, -r + half * 2 + grow, r + grow, rgb);
            RenderUtils.drawRect(r - half * 2 - grow, -r - grow, r + grow, r + grow, rgb);
            return;
        }

        if (selected == STYLE_BRACKETS) {
            float r = g + len;
            float arm = len * 0.6f;
            corner(-r, -r, arm, arm, half, grow, rgb);
            corner(r, -r, -arm, arm, half, grow, rgb);
            corner(-r, r, arm, -arm, half, grow, rgb);
            corner(r, r, -arm, -arm, half, grow, rgb);
            return;
        }

        RenderUtils.drawRect(-half - grow, -g - len - grow, half + grow, -g + grow, rgb);
        RenderUtils.drawRect(-half - grow, g - grow, half + grow, g + len + grow, rgb);
        RenderUtils.drawRect(-g - len - grow, -half - grow, -g + grow, half + grow, rgb);
        RenderUtils.drawRect(g - grow, -half - grow, g + len + grow, half + grow, rgb);
    }

    private void corner(float x, float y, float armX, float armY, float half, float grow, int rgb) {
        float hx = Math.min(x, x + armX) - grow, hx2 = Math.max(x, x + armX) + grow;
        RenderUtils.drawRect(hx, y - half - grow, hx2, y + half + grow, rgb);
        float vy = Math.min(y, y + armY) - grow, vy2 = Math.max(y, y + armY) + grow;
        RenderUtils.drawRect(x - half - grow, vy, x + half + grow, vy2, rgb);
    }

    private void ring(float radius, float half, int rgb) {
        // Stepped by segment count rather than by a fixed angle, so a small ring does not spend
        // sixty quads on twelve visible pixels.
        int segments = Math.max(12, Math.min(64, Math.round(radius * 3.0f)));
        for (int i = 0; i < segments; i++) {
            double t = (i / (double) segments) * Math.PI * 2.0;
            float cx = (float) Math.sin(t) * radius;
            float cy = (float) -Math.cos(t) * radius;
            RenderUtils.drawRect(cx - half, cy - half, cx + half, cy + half, rgb);
        }
    }

    private boolean lookingAtLiving() {
        Entity pointed = mc.pointedEntity;
        if (pointed instanceof EntityLivingBase && pointed != mc.thePlayer) {
            return true;
        }
        return mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                && mc.objectMouseOver.entityHit != mc.thePlayer;
    }
}
