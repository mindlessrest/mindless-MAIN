package mindless.module.impl.bedwars;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.List;
public abstract class BedwarsHud extends Module {
    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 6.0f;
    private static final float LINE_GAP = 2.0f;

    protected final SliderSetting scale;

    private float relativeX = Float.NaN;
    private float relativeY = Float.NaN;
    private float posX = Float.NaN;
    private float posY = Float.NaN;

    protected BedwarsHud(String name, String description, float defaultX, float defaultY) {
        super(name, description, category.bedwars);
        this.relativeX = defaultX;
        this.relativeY = defaultY;
        this.registerSetting(scale = new SliderSetting("Scale", "x", 1.0, 0.5, 1.5, 0.05));
    }
protected abstract List<String> lines();
protected abstract boolean shouldDraw();

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;
        if (!shouldDraw()) return;
        draw();
    }
public float[] renderPreview() {
        return draw();
    }
public float[] renderDesignerPreview(float left, float top) {
        setAbsolute(left, top, ScaledResolutionCache.get());
        return draw();
    }

    private float[] draw() {
        MindlessFontRenderer font = HUD.getHudFontRenderer();
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
        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 5.0f, new Color(0, 0, 0, 120).getRGB());

        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 125));
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 18), new Color(255, 255, 255, 4));
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 28));
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
