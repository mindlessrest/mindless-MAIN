package mindless.module.impl.bedwars;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.util.List;
public abstract class BedwarsHud extends Module {
    private static final float PAD_X = 9.0f;
    private static final float PAD_Y = 6.0f;
    private static final float LINE_GAP = 1.5f;

    protected final SliderSetting scale;
    protected final SliderSetting backgroundOpacity;
    protected final SliderSetting rounding;

    private final float initialRelativeX;
    private final float initialRelativeY;
    private float relativeX = Float.NaN;
    private float relativeY = Float.NaN;
    private float posX = Float.NaN;
    private float posY = Float.NaN;

    protected BedwarsHud(String name, String description, float defaultX, float defaultY) {
        super(name, description, category.bedwars);
        this.initialRelativeX = defaultX;
        this.initialRelativeY = defaultY;
        this.relativeX = defaultX;
        this.relativeY = defaultY;
        this.registerSetting(scale = new SliderSetting("Scale", "x", 1.0, 0.5, 1.5, 0.05));
        this.registerSetting(backgroundOpacity = new SliderSetting(
                "Background opacity", "%", 68.0, 0.0, 100.0, 5.0));
        this.registerSetting(rounding = new SliderSetting("Rounding", 5.0, 0.0, 10.0, 0.5));
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
        float radius = (float) rounding.getInput()
                * mindless.module.impl.theme.ThemeManager.roundingScale() * s;
        int opacity = Math.max(0, Math.min(255,
                (int) Math.round(backgroundOpacity.getInput() * 2.55)));
        // A restrained floating surface: no blur pass and no thick perimeter stroke. The small
        // offset shadow separates it from the world while the theme-coloured rail identifies it.
        RoundedUtils.drawRound(left + 1.0f, top + 1.5f, w, h, radius, 0x52000000);
        RoundedUtils.drawRound(left, top, w, h, radius, (opacity << 24) | 0x090B0E);
        int accent = HUD.getHudColor(0);
        int accentAlpha = Math.min(210, Math.max(80, opacity));
        accent = (accent & 0x00FFFFFF) | (accentAlpha << 24);
        RoundedUtils.drawRound(left + 3.0f * s, top + 4.0f * s,
                Math.max(1.0f, 1.35f * s), Math.max(2.0f, h - 8.0f * s),
                Math.max(0.5f, 0.7f * s), accent);
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;
        float textX = snapScaled(left * inv + PAD_X, s);
        float textY = snapScaled(top * inv + PAD_Y, s);
        for (String line : lines) {
            font.drawString(line, textX, textY, 0xFFFFFFFF, false);
            textY = snapScaled(textY + font.getFontHeight() + LINE_GAP, s);
        }
        GlStateManager.popMatrix();

        return new float[] { left, top, left + w, top + h };
    }

    private static float snapScaled(float value, float scale) {
        return Math.round(value * scale) / Math.max(0.01f, scale);
    }

    public float getPosX() { syncPosition(); return posX; }

    public float getPosY() { syncPosition(); return posY; }

    public SliderSetting scaleSetting() { return scale; }

    public void resetPosition() {
        relativeX = defaultRelativeX();
        relativeY = defaultRelativeY();
        syncPosition();
    }

    protected float defaultRelativeX() { return initialRelativeX; }

    protected float defaultRelativeY() { return initialRelativeY; }

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
