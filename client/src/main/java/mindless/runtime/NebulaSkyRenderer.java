package mindless.runtime;

import mindless.module.impl.world.Weather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.Random;

public final class NebulaSkyRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int LONGITUDE_SEGMENTS = 64;
    private static final int LATITUDE_SEGMENTS = 18;
    private static final int MAX_STARS = 420;
    private static final float[] STAR_X = new float[MAX_STARS];
    private static final float[] STAR_Y = new float[MAX_STARS];
    private static final float[] STAR_Z = new float[MAX_STARS];
    private static final float[] STAR_ALPHA = new float[MAX_STARS];

    static {
        Random random = new Random(0x4D494E444C455353L);
        for (int i = 0; i < MAX_STARS; i++) {
            double azimuth = random.nextDouble() * Math.PI * 2.0;
            double elevation = Math.toRadians(5.0 + random.nextDouble() * 80.0);
            double horizontal = Math.cos(elevation);
            STAR_X[i] = (float) (Math.cos(azimuth) * horizontal);
            STAR_Y[i] = (float) Math.sin(elevation);
            STAR_Z[i] = (float) (Math.sin(azimuth) * horizontal);
            STAR_ALPHA[i] = 0.55f + random.nextFloat() * 0.45f;
        }
    }

    private NebulaSkyRenderer() {}

    public static void render(Weather atmosphere, float partialTicks) {
        if (atmosphere == null || !atmosphere.nebulaSky.isToggled() || mc.theWorld == null) return;

        int preset = (int) atmosphere.skyPreset.getInput();
        int zenith = presetColor(preset, true, atmosphere.nebulaColor.getColor());
        int horizon = presetColor(preset, false, atmosphere.nebulaColor2.getColor());
        float brightness = (float) atmosphere.nebulaBrightness.getInput();
        float rain = mc.theWorld.getRainStrength(partialTicks);
        float radius = Math.max(96.0f, mc.gameSettings.renderDistanceChunks * 16.0f * 0.9f);
        float celestial = mc.theWorld.getCelestialAngle(partialTicks);
        float daylight = clamp(0.5f + 0.5f * (float) Math.cos(celestial * Math.PI * 2.0));
        float night = 1.0f - daylight;

        zenith = grade(zenith, brightness, rain, preset == 3 ? 1.0f : 0.72f + daylight * 0.28f);
        horizon = grade(horizon, brightness, rain, preset == 3 ? 1.0f : 0.78f + daylight * 0.22f);

        OpenGlHelper.glUseProgram(0);
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        GlStateManager.translate(mc.getRenderManager().viewerPosX,
                mc.getRenderManager().viewerPosY, mc.getRenderManager().viewerPosZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GL11.glDepthRange(1.0, 1.0);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        try {
            drawDome(radius, zenith, horizon);
            if (atmosphere.horizonHaze.isToggled() && atmosphere.horizonHazeStrength.getInput() > 0.0) {
                int hazeColor = atmosphere.customFog.isToggled() ? atmosphere.fogColor.getColor() : horizon;
                drawHaze(radius * 0.985f, hazeColor,
                        (float) atmosphere.horizonHazeStrength.getInput() * (1.0f + rain * 0.65f));
            }
            drawStars(radius * 0.975f, (int) atmosphere.nebulaStars.getInput(),
                    night * (1.0f - rain) * presetStarScale(preset));
            if (atmosphere.celestialDiscs.isToggled()) {
                drawCelestialDiscs(radius * 0.965f, celestial, rain);
            }
        } finally {
            GL11.glPointSize(1.0f);
            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    private static void drawDome(float radius, int zenith, int horizon) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer world = tessellator.getWorldRenderer();
        double minLatitude = Math.toRadians(-12.0);
        double maxLatitude = Math.toRadians(90.0);
        for (int lat = 0; lat < LATITUDE_SEGMENTS; lat++) {
            double a0 = minLatitude + (maxLatitude - minLatitude) * lat / LATITUDE_SEGMENTS;
            double a1 = minLatitude + (maxLatitude - minLatitude) * (lat + 1) / LATITUDE_SEGMENTS;
            float mix0 = smooth((float) ((Math.sin(a0) + 0.2) / 1.2));
            float mix1 = smooth((float) ((Math.sin(a1) + 0.2) / 1.2));
            int color0 = mix(horizon, zenith, mix0);
            int color1 = mix(horizon, zenith, mix1);
            world.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int lon = 0; lon <= LONGITUDE_SEGMENTS; lon++) {
                double angle = Math.PI * 2.0 * lon / LONGITUDE_SEGMENTS;
                vertex(world, radius, a1, angle, color1, 255);
                vertex(world, radius, a0, angle, color0, 255);
            }
            tessellator.draw();
        }
    }

    private static void drawHaze(float radius, int color, float strength) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        drawLatitudeBand(radius, Math.toRadians(-7.0), Math.toRadians(2.0), color, 0,
                color, Math.round(clamp(strength) * 210f));
        drawLatitudeBand(radius, Math.toRadians(2.0), Math.toRadians(14.0), color,
                Math.round(clamp(strength) * 210f), color, 0);
        GlStateManager.disableBlend();
    }

    private static void drawLatitudeBand(float radius, double lower, double upper,
                                         int lowerColor, int lowerAlpha, int upperColor, int upperAlpha) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer world = tessellator.getWorldRenderer();
        world.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int lon = 0; lon <= LONGITUDE_SEGMENTS; lon++) {
            double angle = Math.PI * 2.0 * lon / LONGITUDE_SEGMENTS;
            vertex(world, radius, upper, angle, upperColor, upperAlpha);
            vertex(world, radius, lower, angle, lowerColor, lowerAlpha);
        }
        tessellator.draw();
    }

    private static void drawStars(float radius, int requested, float visibility) {
        if (visibility <= 0.01f) return;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE, GL11.GL_ONE);
        GL11.glPointSize(1.6f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer world = tessellator.getWorldRenderer();
        world.begin(GL11.GL_POINTS, DefaultVertexFormats.POSITION_COLOR);
        int count = Math.max(0, Math.min(MAX_STARS, requested));
        for (int i = 0; i < count; i++) {
            int alpha = Math.round(255f * clamp(visibility) * STAR_ALPHA[i]);
            int tint = i % 5 == 0 ? 0xDDE8FF : (i % 7 == 0 ? 0xFFE8D2 : 0xFFFFFF);
            world.pos(STAR_X[i] * radius, STAR_Y[i] * radius, STAR_Z[i] * radius)
                    .color(red(tint), green(tint), blue(tint), alpha).endVertex();
        }
        tessellator.draw();
        GlStateManager.disableBlend();
    }

    private static void drawCelestialDiscs(float radius, float celestial, float rain) {
        float visibility = 1.0f - rain * 0.85f;
        if (visibility <= 0.02f) return;
        GlStateManager.pushMatrix();
        GlStateManager.rotate(-90.0f, 1.0f, 0.0f, 0.0f);
        GlStateManager.rotate(celestial * 360.0f, 1.0f, 0.0f, 0.0f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE, GL11.GL_ONE);
        drawDisc(radius, radius * 0.055f, 0xFFF4C8, visibility);
        GlStateManager.rotate(180.0f, 1.0f, 0.0f, 0.0f);
        drawDisc(radius, radius * 0.042f, 0xD9E4F2, visibility * 0.82f);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private static void drawDisc(float radius, float size, int color, float alpha) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer world = tessellator.getWorldRenderer();
        world.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        world.pos(0.0, radius, 0.0).color(red(color), green(color), blue(color),
                Math.round(255f * clamp(alpha))).endVertex();
        for (int i = 0; i <= 40; i++) {
            double angle = Math.PI * 2.0 * i / 40.0;
            world.pos(Math.cos(angle) * size, radius, Math.sin(angle) * size)
                    .color(red(color), green(color), blue(color), 0).endVertex();
        }
        tessellator.draw();
    }

    private static void vertex(WorldRenderer world, float radius, double latitude, double longitude,
                               int color, int alpha) {
        double horizontal = Math.cos(latitude) * radius;
        world.pos(Math.cos(longitude) * horizontal, Math.sin(latitude) * radius,
                        Math.sin(longitude) * horizontal)
                .color(red(color), green(color), blue(color), alpha).endVertex();
    }

    private static int presetColor(int preset, boolean zenith, int custom) {
        switch (preset) {
            case 1: return zenith ? 0x2F78C8 : 0xA8D8FF;
            case 2: return zenith ? 0x26315F : 0xFF985C;
            case 3: return zenith ? 0x050B22 : 0x1C315F;
            case 4: return zenith ? 0x48576B : 0x98A3AE;
            default: return custom;
        }
    }

    private static float presetStarScale(int preset) {
        if (preset == 3) return 1.35f;
        if (preset == 4) return 0.0f;
        if (preset == 2) return 0.72f;
        return 1.0f;
    }

    private static int grade(int color, float brightness, float rain, float timeScale) {
        float gray = red(color) * 0.299f + green(color) * 0.587f + blue(color) * 0.114f;
        float desaturate = rain * 0.45f;
        float scale = brightness * timeScale * (1.0f - rain * 0.28f);
        int r = Math.round((red(color) + (gray - red(color)) * desaturate) * scale);
        int g = Math.round((green(color) + (gray - green(color)) * desaturate) * scale);
        int b = Math.round((blue(color) + (gray - blue(color)) * desaturate) * scale);
        return clampColor(r) << 16 | clampColor(g) << 8 | clampColor(b);
    }

    private static int mix(int first, int second, float amount) {
        return Math.round(red(first) + (red(second) - red(first)) * amount) << 16
                | Math.round(green(first) + (green(second) - green(first)) * amount) << 8
                | Math.round(blue(first) + (blue(second) - blue(first)) * amount);
    }

    private static float smooth(float value) {
        value = clamp(value);
        return value * value * (3.0f - 2.0f * value);
    }

    private static int red(int color) { return color >> 16 & 255; }
    private static int green(int color) { return color >> 8 & 255; }
    private static int blue(int color) { return color & 255; }
    private static int clampColor(int value) { return Math.max(0, Math.min(255, value)); }
    private static float clamp(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }

    public static void release() {}
}
