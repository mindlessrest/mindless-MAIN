package mindless.module.impl.world;

import mindless.event.LightmapUpdateEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AtmospherePostProcessor;
import mindless.runtime.NebulaSkyRenderer;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class Weather extends Module {
    public SliderSetting time;
    public SliderSetting lightning;
    public ButtonSetting rain;
    public ButtonSetting clearWeather;
    public ButtonSetting customTime;
    public ButtonSetting customFog;
    public ColorSetting fogColor;
    public SliderSetting fogStart;
    public SliderSetting fogEnd;
    public ButtonSetting customSky;
    public ColorSetting skyColor;
    public ButtonSetting customLighting;
    public ColorSetting lightColor;
    public ButtonSetting colorFilter;
    public ColorSetting filterColor;
    public ButtonSetting nebulaSky;
    public SliderSetting skyPreset;
    public ColorSetting nebulaColor;
    public ColorSetting nebulaColor2;
    public SliderSetting nebulaStars;
    public SliderSetting nebulaBrightness;
    public ButtonSetting celestialDiscs;
    public ButtonSetting horizonHaze;
    public SliderSetting horizonHazeStrength;
    public ButtonSetting postProcessing;
    public ColorSetting gradeTint;
    public SliderSetting tintStrength;
    public SliderSetting exposure;
    public SliderSetting contrast;
    public SliderSetting worldSaturation;
    public SliderSetting shadowDepth;
    public ButtonSetting selectiveBloom;
    public SliderSetting bloomStrength;
    public SliderSetting bloomThreshold;
    public SliderSetting vignette;

    public Weather() {
        super("Atmosphere", "Overrides sky, fog, lighting and weather.", category.world);
        this.liteModule = true;
        this.registerSetting(customTime = new ButtonSetting("Custom time", true));
        this.registerSetting(time = new SliderSetting("Time", 0, 0, 24, 0.1));
        this.registerSetting(clearWeather = new ButtonSetting("Clear weather", false));
        this.registerSetting(lightning = new SliderSetting("Lightning", 0, 0, 1, 0.01));
        this.registerSetting(rain = new ButtonSetting("Rain", false));
        this.registerSetting(customSky = new ButtonSetting("Custom sky", false));
        this.registerSetting(skyColor = new ColorSetting("Sky color", 124, 166, 255));
        this.registerSetting(customLighting = new ButtonSetting("Custom lighting", false));
        this.registerSetting(lightColor = new ColorSetting("Light color", 255, 255, 255));
        this.registerSetting(customFog = new ButtonSetting("Custom fog", false));
        this.registerSetting(fogColor = new ColorSetting("Fog color", 155, 176, 210));
        this.registerSetting(fogStart = new SliderSetting("Fog start", "%", 25, 0, 95, 1));
        this.registerSetting(fogEnd = new SliderSetting("Fog end", "%", 85, 5, 100, 1));
        this.registerSetting(colorFilter = new ButtonSetting("Mild color filter", false));
        this.registerSetting(filterColor = new ColorSetting("Filter color", 255, 222, 196, 18));

        GroupSetting skyEffects = new GroupSetting("Sky effects");
        this.registerSetting(skyEffects);
        this.registerSetting(nebulaSky = new ButtonSetting(skyEffects, "Procedural sky", false,
                "Sky effects.Nebula sky", "Nebula sky"));
        this.registerSetting(skyPreset = new SliderSetting(skyEffects, "Sky preset", 0,
                new String[]{"Custom", "Clear day", "Golden hour", "Night", "Overcast"}));
        this.registerSetting(nebulaColor = new ColorSetting(skyEffects, "Nebula dark", 18, 35, 84));
        this.registerSetting(nebulaColor2 = new ColorSetting(skyEffects, "Nebula bright", 105, 70, 190));
        this.registerSetting(nebulaStars = new SliderSetting(skyEffects, "Star density", 190, 40, 420, 10));
        this.registerSetting(nebulaBrightness = new SliderSetting(skyEffects, "Sky brightness", 1.0, 0.4, 1.8, 0.05));
        this.registerSetting(celestialDiscs = new ButtonSetting(skyEffects, "Sun and moon", true));
        this.registerSetting(horizonHaze = new ButtonSetting(skyEffects, "Horizon haze", true));
        this.registerSetting(horizonHazeStrength = new SliderSetting(skyEffects, "Haze strength", 0.28, 0.0, 0.8, 0.02));

        GroupSetting grading = new GroupSetting("World grading");
        this.registerSetting(grading);
        this.registerSetting(postProcessing = new ButtonSetting(grading, "Enabled", false));
        this.registerSetting(gradeTint = new ColorSetting(grading, "Tint", 112, 145, 255));
        this.registerSetting(tintStrength = new SliderSetting(grading, "Tint strength", 0.14, 0.0, 0.5, 0.01));
        this.registerSetting(exposure = new SliderSetting(grading, "Exposure", -0.08, -0.6, 0.6, 0.02));
        this.registerSetting(contrast = new SliderSetting(grading, "Contrast", 1.12, 0.7, 1.6, 0.02));
        this.registerSetting(worldSaturation = new SliderSetting(grading, "Saturation", 1.12, 0.0, 2.0, 0.02));
        this.registerSetting(shadowDepth = new SliderSetting(grading, "Shadow depth", 0.18, 0.0, 0.55, 0.01));
        this.registerSetting(selectiveBloom = new ButtonSetting(grading, "Selective bloom", true));
        this.registerSetting(bloomStrength = new SliderSetting(grading, "Bloom strength", 0.16, 0.0, 0.6, 0.01));
        this.registerSetting(bloomThreshold = new SliderSetting(grading, "Bloom threshold", 0.58, 0.25, 0.9, 0.01));
        this.registerSetting(vignette = new SliderSetting(grading, "Vignette", 0.08, 0.0, 0.35, 0.01));
        this.registerSetting(new ButtonSetting("Apply video-style preset", this::applyVideoPreset));
    }

    private void applyVideoPreset() {
        customTime.enable();
        time.setValue(18.0);
        clearWeather.enable();
        rain.disable();
        lightning.setValue(0.0);
        customSky.enable();
        skyColor.setColor(28, 45, 96);
        customLighting.enable();
        lightColor.setColor(72, 96, 165);
        customFog.enable();
        fogColor.setColor(40, 59, 108);
        fogStart.setValue(20.0);
        fogEnd.setValue(84.0);
        colorFilter.disable();
        nebulaSky.enable();
        nebulaColor.setColor(16, 31, 78);
        nebulaColor2.setColor(106, 68, 190);
        nebulaStars.setValue(210.0);
        nebulaBrightness.setValue(1.08);
        postProcessing.enable();
        gradeTint.setColor(105, 138, 255);
        tintStrength.setValue(0.16);
        exposure.setValue(-0.10);
        contrast.setValue(1.18);
        worldSaturation.setValue(1.18);
        shadowDepth.setValue(0.24);
        selectiveBloom.enable();
        bloomStrength.setValue(0.18);
        bloomThreshold.setValue(0.56);
        vignette.setValue(0.10);

        Module saturationModule = mindless.module.ModuleManager.getModule("Saturation");
        if (saturationModule != null && saturationModule.isEnabled()) saturationModule.disable();
        if (!isEnabled()) enable();
    }

    @Override
    public void onDisable() {
        AtmospherePostProcessor.release();
        NebulaSkyRenderer.release();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) return;
        NebulaSkyRenderer.render(this, event.partialTicks);
        AtmospherePostProcessor.render(this);
    }

    @SubscribeEvent
    public void onFogColors(EntityViewRenderEvent.FogColors event) {
        if (!customFog.isToggled()) return;
        event.red = fogColor.getRed() / 255.0F;
        event.green = fogColor.getGreen() / 255.0F;
        event.blue = fogColor.getBlue() / 255.0F;
    }

    @SubscribeEvent
    public void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        if (!customFog.isToggled() || !Utils.nullCheck() || mc.getRenderViewEntity() == null) return;
        if (mc.getRenderViewEntity().isInsideOfMaterial(Material.water)
                || mc.getRenderViewEntity().isInsideOfMaterial(Material.lava)) return;

        float startPercent = (float) Math.min(fogStart.getInput(), fogEnd.getInput() - 1.0) / 100.0F;
        float endPercent = (float) Math.max(fogEnd.getInput(), fogStart.getInput() + 1.0) / 100.0F;
        GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GL11.glFogf(GL11.GL_FOG_START, event.farPlaneDistance * Math.max(0.0F, startPercent));
        GL11.glFogf(GL11.GL_FOG_END, event.farPlaneDistance * Math.min(1.0F, endPercent));
    }

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !colorFilter.isToggled()) return;
        if (mc.thePlayer != null && mc.thePlayer.isBurning()) return;
        int color = filterColor.getColor();
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) return;
        int capped = Math.min(alpha, 120);
        int safeColor = (color & 0x00FFFFFF) | (capped << 24);
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        RenderUtils.drawRect(0, 0, event.resolution.getScaledWidth(),
                event.resolution.getScaledHeight(), safeColor);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popAttrib();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public void onLightmapUpdate(LightmapUpdateEvent event) {
        if (!customLighting.isToggled()) return;
        int[] colors = event.lightmapColors;
        int tR = lightColor.getRed();
        int tG = lightColor.getGreen();
        int tB = lightColor.getBlue();
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            int a = (c >>> 24) & 0xFF;
            int oR = (c >>> 16) & 0xFF;
            int oG = (c >>> 8) & 0xFF;
            int oB = c & 0xFF;
            int nR = (int) Math.round(oR * 0.45 + tR * 0.55);
            int nG = (int) Math.round(oG * 0.45 + tG * 0.55);
            int nB = (int) Math.round(oB * 0.45 + tB * 0.55);
            int floorR = (int) Math.round(tR * 0.12);
            int floorG = (int) Math.round(tG * 0.12);
            int floorB = (int) Math.round(tB * 0.12);
            nR = Math.max(nR, floorR);
            nG = Math.max(nG, floorG);
            nB = Math.max(nB, floorB);
            colors[i] = (a << 24) | (nR << 16) | (nG << 8) | nB;
        }
    }
}
