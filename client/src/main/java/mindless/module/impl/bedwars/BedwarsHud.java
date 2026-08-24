package mindless.module.impl.bedwars;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.List;

/**
 * The panel every bedwars overlay in this tab is drawn on.
 *
 * <p>Four of them are the same thing wearing different text -- a small glass card, anchored by
 * its top-left, holding a handful of lines. Writing the position plumbing, the blur, the shadow
 * and the hairline four times over would be four chances to get one of them subtly different, and
 * a HUD whose panels do not match reads as several mods stapled together rather than one client.
 *
 * <p>Subclasses supply the lines and say when they have anything to show. Everything else is
 * here, including the hooks the HUD editor drags panels around by.
 */
public abstract class BedwarsHud extends Module {
    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 6.0f;
    private static final float LINE_GAP = 2.0f;

    protected final SliderSetting scale;

    private float relativeX = Float.NaN;
    private float relativeY = Float.NaN;
    private float posX = Float.NaN;
    private float posY = Float.NaN;

    protected BedwarsHud(String name, float defaultX, float defaultY) {
        super(name, category.bedwars);
        this.relativeX = defaultX;
        this.relativeY = defaultY;
        this.registerSetting(scale = new SliderSetting("Scale", "x", 1.0, 0.5, 1.5, 0.05));
        this.registerSetting(new ButtonSetting("Edit position",
                () -> mc.displayGuiScreen(new HudEditor.Screen())));
    }

    /** The lines to draw, top to bottom, or empty for nothing to say. Section signs are fine. */
    protected abstract List<String> lines();

    /** Whether the overlay belongs on screen at all right now. */
    protected abstract boolean shouldDraw();

    // ------------------------------------------------------------------ render

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        // The editor draws its own copy, and a second one underneath it drags out of step.
        if (mc.currentScreen != null) return;
        if (!shouldDraw()) return;
        draw();
    }

    /** Draws where it already sits and reports its bounds, for the HUD editor. */
    public float[] renderPreview() {
        return draw();
    }

    /** Draws at a requested top-left and reports its bounds, for the HUD editor. */
    public float[] renderDesignerPreview(float left, float top) {
        setAbsolute(left, top, ScaledResolutionCache.get());
        return draw();
    }

    private float[] draw() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;

        List<String> lines = lines();
        if (lines.isEmpty()) return null;

        float s = (float) scale.getInput();
        float widest = 0.0f;
        for (String line : lines) widest = Math.max(widest, font.getStringWidth(line));

        float w = (widest + PAD_X * 2.0f) * s;
        float h = (PAD_Y * 2.0f + lines.size() * font.getFontHeight()
                + (lines.size() - 1) * LINE_GAP) * s;

        syncPosition();
        float left = posX;
        float top = posY;
        float radius = 7.0f * mindless.module.impl.theme.ThemeManager.roundingScale();

        // Same construction as the other glass panels: the shadow first, because everything from
        // prepareBlur on is stencilled to the panel and would paint over it.
        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 5.0f, new Color(0, 0, 0, 120).getRGB());

        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 125));
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 18), new Color(255, 255, 255, 4));

        // A real ring rather than a translucent rect showing round the edges of the fill -- see
        // SessionInfo, where that trick turned the whole panel white whenever the blur missed.
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 28));

        // The rounded and blur shaders leave a program bound; glyphs drawn through it come out
        // garbled.
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;
        float textX = left * inv + PAD_X;
        float textY = top * inv + PAD_Y;
        for (String line : lines) {
            font.drawString(line, textX, textY, 0xFFFFFFFF, false);
            textY += font.getFontHeight() + LINE_GAP;
        }
        GlStateManager.popMatrix();

        return new float[] { left, top, left + w, top + h };
    }

    // ------------------------------------------------------------------ position

    public float getPosX() { syncPosition(); return posX; }

    public float getPosY() { syncPosition(); return posY; }

    public SliderSetting scaleSetting() { return scale; }

    public void resetPosition() {
        relativeX = defaultRelativeX();
        relativeY = defaultRelativeY();
        syncPosition();
    }

    protected float defaultRelativeX() { return 0.02f; }

    protected float defaultRelativeY() { return 0.35f; }

    private void syncPosition() {
        ScaledResolution resolution = ScaledResolutionCache.get();
        int width = Math.max(1, resolution.getScaledWidth());
        int height = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(relativeX) || Float.isNaN(relativeY)) {
            relativeX = defaultRelativeX();
            relativeY = defaultRelativeY();
        }
        posX = relativeX * width;
        posY = relativeY * height;
    }

    private void setAbsolute(float left, float top, ScaledResolution resolution) {
        posX = left;
        posY = top;
        relativeX = left / Math.max(1, resolution.getScaledWidth());
        relativeY = top / Math.max(1, resolution.getScaledHeight());
    }
}
