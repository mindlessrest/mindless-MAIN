package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public class Wings extends Module {

    private static final int SHAPE_REALISTIC = 0;
    private static final int SHAPE_SIMPLE = 1;
    private static final int SHAPE_WINGS = 2;
    private static final int SHAPE_SHARDS = 3;
    private static final String[] SHAPES = {"Realistic", "Simple", "Wings", "Shards"};

    private static final int COLOR_STATIC = 0;
    private static final int COLOR_GRADIENT = 1;
    private static final int COLOR_RAINBOW = 2;
    private static final String[] COLOR_MODES = {"Static", "Gradient", "Rainbow"};

    private static final int PANELS_SHARD = 6;
    private static final float ROOT_Y = 1.32f;
    private static final float ROOT_Z = 0.14f;
    private static final float SNEAK_DROP = 0.22f;
    private static final float TIP_FADE_GLASS = 0.34f;
    private static final float TIP_FADE_SOLID = 0.88f;
    private static final float REAL_TIP_FADE_START = 0.78f;
    private static final float ROOT_COLLISION_INNER = 0.070f;
    private static final float ROOT_COLLISION_OUTER = 0.175f;
    private static final int MAX_REAL_FEATHERS = 128;
    private static final int SOLID_ALPHA = 242;

    // Outer silhouette of the pixel/angel wing (the only outline shown when "Outline" is off).
    private static final float[] PIXEL_SHAPE = {
            0.08f,  0.02f,
            0.16f,  0.28f,
            0.30f,  0.28f,
            0.30f,  0.48f,
            0.47f,  0.48f,
            0.47f,  0.64f,
            0.65f,  0.64f,
            0.65f,  0.54f,
            0.82f,  0.54f,
            0.82f,  0.38f,
            0.99f,  0.38f,
            0.99f,  0.18f,
            1.12f,  0.18f,
            1.12f, -0.08f,
            1.02f, -0.08f,
            1.02f, -0.28f,
            0.90f, -0.28f,
            0.90f, -0.47f,
            0.76f, -0.47f,
            0.76f, -0.67f,
            0.62f, -0.67f,
            0.62f, -0.46f,
            0.50f, -0.46f,
            0.50f, -0.27f,
            0.38f, -0.27f,
            0.38f, -0.09f,
            0.25f, -0.09f,
            0.25f,  0.03f
    };

    // Inner seams — only drawn when "Outline" is enabled.
    private static final float[] PIXEL_SEAMS = {
            0.18f, 0.27f, 0.65f, 0.53f,
            0.25f, 0.08f, 0.82f, 0.37f,
            0.37f,-0.09f, 0.99f, 0.17f,
            0.49f,-0.27f, 1.01f,-0.07f,
            0.61f,-0.46f, 0.89f,-0.28f
    };

    // Simple (the old "Realistic") feather layers: {tStart,tEnd,count,lengthScale,widthScale,angleBias,lift,shade}
    private static final float[][] SIMPLE_LAYERS = {
            {0.25f, 1.00f,10.0f, 0.90f, 0.140f,  0.10f, -0.010f, 0.84f},
            {0.03f, 0.76f, 9.0f, 0.67f, 0.145f, -0.12f,  0.015f, 0.92f},
            {0.00f, 0.98f,12.0f, 0.38f, 0.135f, -0.28f,  0.060f, 1.00f}
    };

    // Realistic: four overlapping rows — primaries, secondaries, greater coverts, marginal coverts.
    // Widths and counts are set so a feather overlaps its neighbours along its whole length. At
    // the previous 0.120-0.150 they only met near the root; by the tip they had fanned apart and
    // every feather stood alone, which serrated the whole silhouette into a fan of spikes.
    private static final float[][] REAL_LAYERS = {
            {0.22f, 1.00f, 10.0f, 1.15f, 0.270f,  0.02f, -0.020f, 0.84f},
            {0.10f, 0.90f,  9.0f, 0.82f, 0.280f, -0.10f,  0.020f, 0.93f},
            {0.04f, 0.81f, 11.0f, 0.52f, 0.260f, -0.26f,  0.072f, 1.00f},
            {0.03f, 0.68f, 12.0f, 0.30f, 0.235f, -0.42f,  0.122f, 1.06f}
    };

    private static final float[] FEATHER_PROFILE = {
            0.00f, 0.16f,
            0.14f, 0.48f,
            0.36f, 0.64f,
            0.62f, 0.59f,
            0.82f, 0.43f,
            0.95f, 0.22f,
            1.00f, 0.055f
    };

    // Higher-resolution asymmetric vane used by the Realistic mode (u, halfWidth).
    // Convex rather than linear: it holds most of its width through the middle and rounds off,
    // instead of tapering to the 0.03 needle point that made every feather read as a spike.
    private static final float[] REAL_PROFILE = {
            0.000f, 0.06f,
            0.070f, 0.34f,
            0.160f, 0.56f,
            0.280f, 0.72f,
            0.420f, 0.80f,
            0.560f, 0.82f,
            0.680f, 0.79f,
            0.790f, 0.71f,
            0.880f, 0.58f,
            0.945f, 0.40f,
            0.980f, 0.24f,
            1.000f, 0.07f
    };

    private final SliderSetting shape;
    private final SliderSetting colorMode;
    private final ColorSetting fillColor;
    private final ColorSetting fillColor2;
    private final SliderSetting colorSpeed;
    private final SliderSetting colorSpread;
    private final ButtonSetting transparent;
    private final SliderSetting fade;
    private final SliderSetting size;
    private final SliderSetting spread;
    private final SliderSetting flapSpeed;
    private final SliderSetting flapAmount;
    private final ButtonSetting outline;
    private final ColorSetting edgeColor;
    private final SliderSetting edgeWidth;
    private final ButtonSetting throughWalls;
    private final ButtonSetting hideFirstPerson;
    private final float[] point = new float[3];
    private final float[] pointB = new float[3];
    private final float[] pointC = new float[3];
    private final float[] ribs = new float[FEATHER_PROFILE.length / 2 * 12];
    private final float[] realRibs = new float[REAL_PROFILE.length / 2 * 12];
    // One reusable per-side frame buffer. Keeping the mirrored halves in separate passes prevents
    // their roots from collapsing into one combined mass at the centre of the player's back.
    private final float[] realBuffer = new float[MAX_REAL_FEATHERS * (REAL_PROFILE.length / 2) * 3 * 5];
    private final int[] realFeatherPart = new int[MAX_REAL_FEATHERS];
    private final int[] realFeatherRGB = new int[MAX_REAL_FEATHERS];
    private final float[] realFeatherShade = new float[MAX_REAL_FEATHERS];
    private int realFeatherCount;
    private final float[] shardCorners = new float[PANELS_SHARD * 12];

    public Wings() {
        super("Wings", "Wings that sit on your back.", category.render);
        this.registerSetting(shape = new SliderSetting("Shape", SHAPE_REALISTIC, SHAPES));
        this.registerSetting(colorMode = new SliderSetting("Color mode", COLOR_STATIC, COLOR_MODES));
        this.registerSetting(fillColor = new ColorSetting("Color", 214, 224, 255, 178));
        this.registerSetting(fillColor2 = new ColorSetting("Color 2", 150, 120, 255, 178));
        this.registerSetting(colorSpeed = new SliderSetting("Color speed", 1.0, 0.0, 5.0, 0.1));
        this.registerSetting(colorSpread = new SliderSetting("Color spread", 1.0, 0.0, 4.0, 0.1));
        this.registerSetting(transparent = new ButtonSetting("Transparent", false));
        this.registerSetting(fade = new SliderSetting("Tip fade", 0.45, 0.0, 1.0, 0.02,
                "Bottom fade"));
        this.registerSetting(size = new SliderSetting("Size", 1.0, 0.4, 2.5, 0.05));
        this.registerSetting(spread = new SliderSetting("Spread", 1.0, 0.4, 2.0, 0.05));
        this.registerSetting(flapSpeed = new SliderSetting("Flap speed", 1.0, 0.0, 4.0, 0.1));
        this.registerSetting(flapAmount = new SliderSetting("Flap amount", "°", 12.0, 0.0, 40.0, 1.0));
        this.registerSetting(outline = new ButtonSetting("Outline", false));
        this.registerSetting(edgeColor = new ColorSetting("Edge color", 255, 255, 255, 205));
        this.registerSetting(edgeWidth = new SliderSetting("Edge width", 1.1, 0.0, 3.0, 0.1));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", false));
        this.registerSetting(hideFirstPerson = new ButtonSetting("Hide in first person", true));
    }

    @Override
    public void guiUpdate() {
        int mode = (int) colorMode.getInput();
        fillColor2.setVisible(mode == COLOR_GRADIENT, this);
        colorSpeed.setVisible(mode != COLOR_STATIC, this);
        colorSpread.setVisible(mode != COLOR_STATIC, this);
        flapAmount.setVisible(flapSpeed.getInput() > 0.0, this);
        fade.setVisible((int) shape.getInput() == SHAPE_REALISTIC, this);
        edgeColor.setVisible(outline.isToggled() && mode == COLOR_STATIC, this);
        edgeWidth.setVisible(outline.isToggled(), this);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (hideFirstPerson.isToggled() && mc.gameSettings.thirdPersonView == 0
                && mc.getRenderViewEntity() == player) return;

        float partialTicks = event.partialTicks;
        RenderManager manager = mc.getRenderManager();
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - manager.viewerPosX;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - manager.viewerPosY;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - manager.viewerPosZ;
        float bodyYaw = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        render(x, y, z, bodyYaw, player);
    }

    private void render(double x, double y, double z, float bodyYaw, EntityPlayerSP player) {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls.isToggled()) GlStateManager.disableDepth();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glShadeModel(GL11.GL_SMOOTH);

        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(180.0f - bodyYaw, 0.0f, 1.0f, 0.0f);
        GlStateManager.translate(0.0f, player.isSneaking() ? ROOT_Y - SNEAK_DROP : ROOT_Y, ROOT_Z);

        int style = (int) shape.getInput();
        float scale = (float) size.getInput();
        float span = (float) spread.getInput();
        float phase = phase();
        float amplitude = (float) Math.toRadians(flapAmount.getInput()) * flapDrive(player);

        if (style == SHAPE_REALISTIC && WingRenderPipeline.texturesReady()) {
            drawFeatheredWings(scale, span, phase, amplitude);
        }
        else {
            for (int side = -1; side <= 1; side += 2) {
                if (style == SHAPE_REALISTIC) drawRealisticWing(side, scale, span, phase, amplitude);
                else if (style == SHAPE_SIMPLE) drawSimpleWing(side, scale, span, phase, amplitude);
                else if (style == SHAPE_WINGS) drawPixelWing(side, scale, span, phase, amplitude);
                else drawShardWing(side, scale, span, phase, amplitude);
            }
        }

        GL11.glLineWidth(1.0f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glShadeModel(GL11.GL_FLAT);
        if (throughWalls.isToggled()) GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    // ---------------------------------------------------------------- colour

    private float colorTime() {
        double speed = colorSpeed.getInput();
        if (speed <= 0.0) return 0.0f;
        return (float) ((System.nanoTime() / 1.0E9) * speed);
    }

    /** Packed 0xRRGGBB for a point at normalised position t (0 = root, 1 = tip) along the wing. */
    private int baseColor(float t) {
        switch ((int) colorMode.getInput()) {
            case COLOR_RAINBOW: {
                float hue = colorTime() * 0.12f + t * (float) colorSpread.getInput();
                hue -= (float) Math.floor(hue);
                return Color.HSBtoRGB(hue, 0.82f, 1.0f) & 0xFFFFFF;
            }
            case COLOR_GRADIENT: {
                double angle = colorTime() * 0.9 + t * colorSpread.getInput() * Math.PI;
                float mix = (float) ((Math.sin(angle) + 1.0) * 0.5);
                return lerpRGB(fillColor.getRGB(), fillColor2.getRGB(), mix);
            }
            default:
                return fillColor.getRGB();
        }
    }

    /** Colour used for outlines at position t — follows the palette unless the mode is static. */
    private int edgeRGB(float t) {
        return (int) colorMode.getInput() == COLOR_STATIC ? edgeColor.getRGB() : baseColor(t);
    }

    private int lerpRGB(int a, int b, float m) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * m);
        int g = Math.round(ag + (bg - ag) * m);
        int bl = Math.round(ab + (bb - ab) * m);
        return (r << 16) | (g << 8) | bl;
    }

    // ---------------------------------------------------------------- feathered (textured)

    /**
     * The textured path.
     *
     * Each mirrored half receives its own coverage and colour pass. Feathers within a wing still
     * occlude cleanly, while the two roots remain visually separate at the centre.
     */
    private void drawFeatheredWings(float scale, float span, float phase, float amplitude) {
        boolean glass = transparent.isToggled();
        boolean walls = throughWalls.isToggled();

        for (int side = -1; side <= 1; side += 2) {
            // Keep mirrored halves independent. Sharing their coverage pass caused the two root
            // clusters to resolve as one dense shape at the middle of the player's back.
            realFeatherCount = 0;
            appendFeatheredWing(side, scale, span, phase, amplitude);

            if (glass || walls) {
                WingRenderPipeline.beginGlass(walls);
                emitFeatherPasses(false);
            }
            else {
                WingRenderPipeline.beginDepthPrepass();
                emitFeatherPasses(true);
                WingRenderPipeline.beginColour(false);
                emitFeatherPasses(false);
            }
            WingRenderPipeline.end();
        }
        if (outline.isToggled()) {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.depthMask(false);
            for (int side = -1; side <= 1; side += 2) {
                drawRealisticLeadingEdge(side, scale, span, phase, amplitude);
            }
            GlStateManager.depthMask(true);
            GlStateManager.enableTexture2D();
        }
    }

    /** Appends one mirrored wing to the shared frame buffer. */
    private void appendFeatheredWing(int side, float scale, float span, float phase, float amplitude) {
        int steps = REAL_PROFILE.length / 2;
        int cursor = realFeatherCount * steps * 3 * 5;

        for (int layer = 0; layer < REAL_LAYERS.length; layer++) {
            float[] spec = REAL_LAYERS[layer];
            int count = (int) spec[2];
            for (int i = 0; i < count; i++) {
                if (realFeatherCount >= MAX_REAL_FEATHERS) return;
                float along = count == 1 ? 0.5f : i / (float) (count - 1);
                float t = spec[0] + (spec[1] - spec[0]) * along;
                float width = spec[4] * (0.90f + 0.14f * (float) Math.sin((i + layer) * 2.13f));

                realFeatherPart[realFeatherCount] = layer;
                realFeatherRGB[realFeatherCount] = baseColor(t);
                realFeatherShade[realFeatherCount] = spec[7];
                cursor = buildFeather(cursor, side, t, scale, span, phase, amplitude,
                        spec[3], width, spec[5], spec[6], layer, steps);
                realFeatherCount++;
            }
        }
    }

    private int buildFeather(int cursor, int side, float t, float scale, float span, float phase,
                             float amplitude, float lengthScale, float widthScale, float angleBias,
                             float lift, int layer, int steps) {
        float rootX = realisticSpineX(t);
        float rootY = realisticSpineY(t) - lift;
        float rootZ = realisticDepth(rootX) - layer * 0.016f;
        // Small deterministic variation prevents the rows from reading as duplicated cards while
        // remaining stable frame-to-frame (random animation here would shimmer badly in motion).
        float variation = 0.5f + 0.5f * (float) Math.sin(t * 37.0f + layer * 11.3f);
        rootZ += (variation - 0.5f) * 0.012f;
        float baseLength = 0.46f + 0.42f * (float) Math.sin(Math.PI * (0.15f + 0.72f * t)) + 0.12f * t;
        float length = baseLength * lengthScale * (0.97f + 0.06f * variation);
        float angle = 0.12f + 1.28f * t + angleBias + (variation - 0.5f) * 0.035f;
        float dirX = (float) Math.sin(angle) * 0.76f;
        float dirY = -(float) Math.cos(angle);
        float dirZ = 0.13f + 0.18f * t;
        float dirLength = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX /= dirLength; dirY /= dirLength; dirZ /= dirLength;

        float wideX = -dirY, wideY = dirX;
        float wideLength = (float) Math.sqrt(wideX * wideX + wideY * wideY);
        wideX /= wideLength; wideY /= wideLength;

        for (int i = 0; i < steps; i++) {
            float u = REAL_PROFILE[i * 2];
            float bend = (float) Math.sin(Math.PI * u);
            float barb = 1.0f + 0.06f * (float) Math.sin(u * (float) Math.PI * 7.0f);
            float leadHalf = REAL_PROFILE[i * 2 + 1] * widthScale * 0.72f;
            float trailHalf = REAL_PROFILE[i * 2 + 1] * widthScale * barb;
            float cx = rootX + dirX * length * u + 0.025f * bend * (1.0f - t);
            float cy = rootY + dirY * length * u - 0.035f * bend * t;
            float cz = rootZ + dirZ * length * u - 0.025f * bend;

            // v across the chord: 0 leading edge, 0.5 shaft, 1 trailing edge
            transformRealistic(cx - wideX * leadHalf, cy - wideY * leadHalf, cz,
                    side, scale, span, phase, amplitude, point);
            cursor = store(cursor, point, u, 0.0f);
            transformRealistic(cx, cy, cz - 0.010f, side, scale, span, phase, amplitude, point);
            cursor = store(cursor, point, u, 0.5f);
            transformRealistic(cx + wideX * trailHalf, cy + wideY * trailHalf, cz,
                    side, scale, span, phase, amplitude, point);
            cursor = store(cursor, point, u, 1.0f);
        }
        return cursor;
    }

    private int store(int cursor, float[] source, float u, float v) {
        realBuffer[cursor] = source[0];
        realBuffer[cursor + 1] = source[1];
        realBuffer[cursor + 2] = source[2];
        realBuffer[cursor + 3] = u;
        realBuffer[cursor + 4] = v;
        return cursor + 5;
    }

    /**
     * Alpha for one vertex.
     *
     * Ordinary transparency is restricted to the final section of the feather.
     * A second, local mask softens only the narrow centre seam where the two
     * mirrored wing roots can physically intersect.
     */
    private int vertexAlpha(int baseAlpha, float u, float x) {
        float tip = smoothstep(REAL_TIP_FADE_START, 1.0f, u);
        float animatedFade = (float) fade.getInput();
        if ((int) colorMode.getInput() == COLOR_GRADIENT && colorSpeed.getInput() > 0.0) {
            // Let only the tips breathe. The body stays material instead of turning holographic.
            float breath = 0.5f + 0.5f * (float) Math.sin(colorTime() * 0.42f);
            animatedFade *= 0.78f + 0.22f * breath;
        }
        float alpha = baseAlpha * (1.0f - animatedFade * tip);

        float centreDistance = Math.abs(x);
        float clearOfSeam = smoothstep(ROOT_COLLISION_INNER, ROOT_COLLISION_OUTER,
                centreDistance);
        float rootInfluence = 1.0f - smoothstep(0.18f, 0.48f, u);
        alpha *= 1.0f - (1.0f - clearOfSeam) * rootInfluence;
        return Math.max(0, Math.min(255, Math.round(alpha)));
    }

    private float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private void emitFeatherPasses(boolean coverageOnly) {
        int steps = REAL_PROFILE.length / 2;
        int featherStride = steps * 3 * 5;
        int baseAlpha = coverageOnly ? 255 : fillAlpha();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();

        for (int part = 0; part < WingRenderPipeline.PART_COUNT; part++) {
            boolean any = false;
            for (int f = 0; f < realFeatherCount; f++) {
                if (realFeatherPart[f] != part) continue;
                if (!any) {
                    WingRenderPipeline.bind(part);
                    buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX_COLOR);
                    any = true;
                }
                emitFeather(buffer, f * featherStride, steps, realFeatherRGB[f],
                        realFeatherShade[f], baseAlpha, coverageOnly, part);
            }
            if (any) tessellator.draw();
        }
    }

    private void emitFeather(WorldRenderer buffer, int base, int steps, int rgb, float shade,
                             int baseAlpha, boolean coverageOnly, int part) {
        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;
        for (int i = 0; i + 1 < steps; i++) {
            float tipRamp = 1.0f - 0.30f * (i / (float) (steps - 1));
            int r = shade(red, shade * tipRamp);
            int g = shade(green, shade * tipRamp);
            int b = shade(blue, shade * tipRamp);
            int lo = base + i * 15;
            int hi = base + (i + 1) * 15;
            // leading vane, then trailing vane
            featherTri(buffer, lo, lo + 5, hi + 5, r, g, b, baseAlpha, coverageOnly, part);
            featherTri(buffer, lo, hi + 5, hi, r, g, b, baseAlpha, coverageOnly, part);
            featherTri(buffer, lo + 5, lo + 10, hi + 10, r, g, b, baseAlpha, coverageOnly, part);
            featherTri(buffer, lo + 5, hi + 10, hi + 5, r, g, b, baseAlpha, coverageOnly, part);
        }
    }

    private void featherTri(WorldRenderer buffer, int a, int b, int c,
                            int red, int green, int blue, int baseAlpha, boolean coverageOnly,
                            int part) {
        featherVertex(buffer, a, red, green, blue, baseAlpha, coverageOnly, part);
        featherVertex(buffer, b, red, green, blue, baseAlpha, coverageOnly, part);
        featherVertex(buffer, c, red, green, blue, baseAlpha, coverageOnly, part);
    }

    private void featherVertex(WorldRenderer buffer, int offset, int red, int green, int blue,
                               int baseAlpha, boolean coverageOnly, int part) {
        float x = realBuffer[offset];
        float y = realBuffer[offset + 1];
        float u = realBuffer[offset + 3];
        int alpha = coverageOnly ? baseAlpha : vertexAlpha(baseAlpha, u, x);
        if (!coverageOnly && part > WingRenderPipeline.PART_PRIMARY) {
            // Soften the root of upper rows where their cards enter the feather layer below.
            float rootBlend = 0.68f + 0.32f * smoothstep(0.02f, 0.20f, u);
            alpha = Math.round(alpha * rootBlend);
        }
        buffer.pos(x, y, realBuffer[offset + 2])
                .tex(u, realBuffer[offset + 4])
                .color(red, green, blue, alpha)
                .endVertex();
    }

    // ---------------------------------------------------------------- realistic (feathered)

    private void drawRealisticWing(int side, float scale, float span, float phase, float amplitude) {
        int alpha = fillAlpha();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        for (int layer = 0; layer < REAL_LAYERS.length; layer++) {
            float[] spec = REAL_LAYERS[layer];
            int count = (int) spec[2];
            for (int i = 0; i < count; i++) {
                float along = count == 1 ? 0.5f : i / (float) (count - 1);
                float t = spec[0] + (spec[1] - spec[0]) * along;
                float width = spec[4] * (0.90f + 0.14f * (float) Math.sin((i + layer) * 2.13f));
                emitRealFeather(buffer, side, t, scale, span, phase, amplitude,
                        spec[3], width, spec[5], spec[6], layer, spec[7], alpha);
            }
        }
        tessellator.draw();

        if (outline.isToggled()) drawRealisticLeadingEdge(side, scale, span, phase, amplitude);
    }

    private void emitRealFeather(WorldRenderer buffer, int side, float t, float scale, float span,
                                 float phase, float amplitude, float lengthScale, float widthScale,
                                 float angleBias, float lift, int layer, float shade, int rootAlpha) {
        int rgb = baseColor(t);
        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;

        float rootX = realisticSpineX(t);
        float rootY = realisticSpineY(t) - lift;
        float rootZ = realisticDepth(rootX) - layer * 0.016f;
        float baseLength = 0.46f + 0.42f * (float) Math.sin(Math.PI * (0.15f + 0.72f * t)) + 0.12f * t;
        float length = baseLength * lengthScale;
        float angle = 0.12f + 1.28f * t + angleBias;
        float dirX = (float) Math.sin(angle) * 0.76f;
        float dirY = -(float) Math.cos(angle);
        float dirZ = 0.13f + 0.18f * t;
        float dirLength = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX /= dirLength; dirY /= dirLength; dirZ /= dirLength;

        float wideX = -dirY, wideY = dirX;
        float wideLength = (float) Math.sqrt(wideX * wideX + wideY * wideY);
        wideX /= wideLength; wideY /= wideLength;

        int steps = REAL_PROFILE.length / 2;
        int tipAlpha = Math.round(rootAlpha * tipFade());
        for (int i = 0; i < steps; i++) {
            float u = REAL_PROFILE[i * 2];
            float bend = (float) Math.sin(Math.PI * u);
            // barb scallop on the trailing (outer) vane so the edge reads as separated feathers
            float barb = 1.0f + 0.06f * (float) Math.sin(u * (float) Math.PI * 7.0f);
            float leadHalf = REAL_PROFILE[i * 2 + 1] * widthScale * 0.72f;
            float trailHalf = REAL_PROFILE[i * 2 + 1] * widthScale * barb;
            float cx = rootX + dirX * length * u + 0.025f * bend * (1.0f - t);
            float cy = rootY + dirY * length * u - 0.035f * bend * t;
            float cz = rootZ + dirZ * length * u - 0.025f * bend;
            int a = Math.round(rootAlpha + (tipAlpha - rootAlpha) * u);
            int offset = i * 12;
            transformRealistic(cx - wideX * leadHalf, cy - wideY * leadHalf, cz, side, scale, span, phase, amplitude, point);
            storeReal(offset, point, a);
            transformRealistic(cx, cy, cz - 0.010f, side, scale, span, phase, amplitude, point);
            storeReal(offset + 4, point, a);
            transformRealistic(cx + wideX * trailHalf, cy + wideY * trailHalf, cz, side, scale, span, phase, amplitude, point);
            storeReal(offset + 8, point, a);
        }

        for (int i = 0; i + 1 < steps; i++) {
            // tips fade darker for depth, base keeps a soft sheen along the shaft
            float tipRamp = 1.0f - 0.30f * (i / (float) (steps - 1));
            int edgeR = shade(red, 0.60f * shade * tipRamp);
            int edgeG = shade(green, 0.60f * shade * tipRamp);
            int edgeB = shade(blue, 0.60f * shade * tipRamp);
            int shaftR = shade(red, 1.16f * shade * tipRamp);
            int shaftG = shade(green, 1.16f * shade * tipRamp);
            int shaftB = shade(blue, 1.16f * shade * tipRamp);
            int lo = i * 12, hi = (i + 1) * 12;
            // leading vane
            realTri(buffer, lo, lo + 4, hi + 4, edgeR, edgeG, edgeB, shaftR, shaftG, shaftB);
            realTri(buffer, lo, hi + 4, hi, edgeR, edgeG, edgeB, shaftR, shaftG, shaftB);
            // trailing vane
            realTri(buffer, lo + 4, lo + 8, hi + 8, shaftR, shaftG, shaftB, edgeR, edgeG, edgeB);
            realTri(buffer, lo + 4, hi + 8, hi + 4, shaftR, shaftG, shaftB, edgeR, edgeG, edgeB);
        }
    }

    private void realTri(WorldRenderer buffer, int a, int b, int c,
                         int r1, int g1, int b1, int r2, int g2, int b2) {
        realVertex(buffer, a, r1, g1, b1);
        realVertex(buffer, b, r2, g2, b2);
        realVertex(buffer, c, (c % 12 == 4) ? r2 : r1, (c % 12 == 4) ? g2 : g1, (c % 12 == 4) ? b2 : b1);
    }

    private void storeReal(int offset, float[] source, int alpha) {
        realRibs[offset] = source[0];
        realRibs[offset + 1] = source[1];
        realRibs[offset + 2] = source[2];
        realRibs[offset + 3] = alpha;
    }

    private void realVertex(WorldRenderer buffer, int offset, int red, int green, int blue) {
        buffer.pos(realRibs[offset], realRibs[offset + 1], realRibs[offset + 2])
                .color(red, green, blue, Math.round(realRibs[offset + 3])).endVertex();
    }

    // ---------------------------------------------------------------- simple (old realistic)

    private void drawSimpleWing(int side, float scale, float span, float phase, float amplitude) {
        int alpha = fillAlpha();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        for (int layer = 0; layer < SIMPLE_LAYERS.length; layer++) {
            float[] spec = SIMPLE_LAYERS[layer];
            int count = (int) spec[2];
            for (int i = 0; i < count; i++) {
                float along = count == 1 ? 0.5f : i / (float) (count - 1);
                float t = spec[0] + (spec[1] - spec[0]) * along;
                int rgb = baseColor(t);
                int rowRed = shade((rgb >> 16) & 0xFF, spec[7]);
                int rowGreen = shade((rgb >> 8) & 0xFF, spec[7]);
                int rowBlue = shade(rgb & 0xFF, spec[7]);
                float width = spec[4] * (0.92f + 0.12f * (float) Math.sin((i + layer) * 2.13f));
                emitSimpleFeather(buffer, side, t, scale, span, phase, amplitude,
                        spec[3], width, spec[5], spec[6], layer,
                        rowRed, rowGreen, rowBlue, alpha, Math.round(alpha * tipFade()));
            }
        }
        tessellator.draw();

        if (outline.isToggled()) drawRealisticLeadingEdge(side, scale, span, phase, amplitude);
    }

    private void emitSimpleFeather(WorldRenderer buffer, int side, float t, float scale, float span,
                                   float phase, float amplitude, float lengthScale, float widthScale,
                                   float angleBias, float lift, int layer,
                                   int red, int green, int blue, int rootAlpha, int tipAlpha) {
        float rootX = realisticSpineX(t);
        float rootY = realisticSpineY(t) - lift;
        float rootZ = realisticDepth(rootX) - layer * 0.014f;
        float baseLength = 0.46f + 0.42f * (float) Math.sin(Math.PI * (0.15f + 0.72f * t)) + 0.12f * t;
        float length = baseLength * lengthScale;
        float angle = 0.12f + 1.28f * t + angleBias;
        float dirX = (float) Math.sin(angle) * 0.76f;
        float dirY = -(float) Math.cos(angle);
        float dirZ = 0.13f + 0.18f * t;
        float dirLength = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX /= dirLength; dirY /= dirLength; dirZ /= dirLength;

        float wideX = -dirY, wideY = dirX;
        float wideLength = (float) Math.sqrt(wideX * wideX + wideY * wideY);
        wideX /= wideLength; wideY /= wideLength;
        float width = widthScale;
        int steps = FEATHER_PROFILE.length / 2;

        for (int i = 0; i < steps; i++) {
            float u = FEATHER_PROFILE[i * 2];
            float half = FEATHER_PROFILE[i * 2 + 1] * width;
            float bend = (float) Math.sin(Math.PI * u);
            float cx = rootX + dirX * length * u + 0.025f * bend * (1.0f - t);
            float cy = rootY + dirY * length * u - 0.035f * bend * t;
            float cz = rootZ + dirZ * length * u - 0.025f * bend;
            int offset = i * 12;
            int featherAlpha = Math.round(rootAlpha + (tipAlpha - rootAlpha) * u);
            transformRealistic(cx - wideX * half, cy - wideY * half, cz, side, scale, span, phase, amplitude, point);
            storeRib(offset, point, featherAlpha);
            transformRealistic(cx, cy, cz - 0.010f, side, scale, span, phase, amplitude, point);
            storeRib(offset + 4, point, featherAlpha);
            transformRealistic(cx + wideX * half, cy + wideY * half, cz, side, scale, span, phase, amplitude, point);
            storeRib(offset + 8, point, featherAlpha);
        }

        int edgeRed = shade(red, 0.74f);
        int edgeGreen = shade(green, 0.74f);
        int edgeBlue = shade(blue, 0.74f);
        int centerRed = shade(red, 1.08f);
        int centerGreen = shade(green, 1.08f);
        int centerBlue = shade(blue, 1.08f);
        for (int i = 0; i + 1 < steps; i++) {
            int lower = i * 12;
            int upper = (i + 1) * 12;
            featherTriangle(buffer, lower, lower + 4, upper + 4, edgeRed, edgeGreen, edgeBlue, centerRed, centerGreen, centerBlue);
            featherTriangle(buffer, lower, upper + 4, upper, edgeRed, edgeGreen, edgeBlue, centerRed, centerGreen, centerBlue);
            featherTriangleRight(buffer, lower + 4, lower + 8, upper + 8, red, green, blue, centerRed, centerGreen, centerBlue);
            featherTriangleRight(buffer, lower + 4, upper + 8, upper + 4, red, green, blue, centerRed, centerGreen, centerBlue);
        }
    }

    private void featherTriangle(WorldRenderer buffer, int a, int b, int c,
                                 int edgeRed, int edgeGreen, int edgeBlue,
                                 int centerRed, int centerGreen, int centerBlue) {
        ribVertex(buffer, a, edgeRed, edgeGreen, edgeBlue);
        ribVertex(buffer, b, centerRed, centerGreen, centerBlue);
        ribVertex(buffer, c, c % 12 == 0 ? edgeRed : centerRed,
                c % 12 == 0 ? edgeGreen : centerGreen,
                c % 12 == 0 ? edgeBlue : centerBlue);
    }

    private void featherTriangleRight(WorldRenderer buffer, int a, int b, int c,
                                      int edgeRed, int edgeGreen, int edgeBlue,
                                      int centerRed, int centerGreen, int centerBlue) {
        ribVertex(buffer, a, centerRed, centerGreen, centerBlue);
        ribVertex(buffer, b, edgeRed, edgeGreen, edgeBlue);
        ribVertex(buffer, c, c % 12 == 4 ? centerRed : edgeRed,
                c % 12 == 4 ? centerGreen : edgeGreen,
                c % 12 == 4 ? centerBlue : edgeBlue);
    }

    private void storeRib(int offset, float[] source, int alpha) {
        ribs[offset] = source[0];
        ribs[offset + 1] = source[1];
        ribs[offset + 2] = source[2];
        ribs[offset + 3] = alpha;
    }

    private void ribVertex(WorldRenderer buffer, int offset, int red, int green, int blue) {
        buffer.pos(ribs[offset], ribs[offset + 1], ribs[offset + 2])
                .color(red, green, blue, Math.round(ribs[offset + 3])).endVertex();
    }

    private void drawRealisticLeadingEdge(int side, float scale, float span, float phase, float amplitude) {
        float width = (float) edgeWidth.getInput();
        if (width <= 0.01f) return;
        int alpha = Math.round(edgeColor.getAlpha() * 0.72f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        GL11.glLineWidth(Math.max(0.7f, width * 0.72f));
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= 12; i++) {
            float t = i / 12.0f;
            float x = realisticSpineX(t);
            float y = realisticSpineY(t) + 0.045f;
            int rgb = edgeRGB(t);
            transformRealistic(x, y, realisticDepth(x) - 0.035f, side, scale, span, phase, amplitude, point);
            vertex(buffer, point, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
        }
        tessellator.draw();
    }

    private float realisticSpineX(float t) {
        return 0.145f + 1.095f * t;
    }

    private float realisticSpineY(float t) {
        return 0.06f + 0.72f * (float) Math.sin(t * 2.20f) - 0.26f * t;
    }

    private float realisticDepth(float x) {
        return 0.06f + 0.15f * x;
    }

    // ---------------------------------------------------------------- pixel / angel wing

    private void drawPixelWing(int side, float scale, float span, float phase, float amplitude) {
        int alpha = fillAlpha();
        int tipAlpha = Math.round(alpha * tipFade());
        int points = PIXEL_SHAPE.length / 2;
        // Smooth silhouette fill — no internal seams; alpha fades toward the tips for a soft glow.
        transformPlanar(0.57f, 0.08f, 0.18f, side, scale, span, phase, amplitude, point);
        int anchorRgb = baseColor(pixelT(0.57f));

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            vertex(buffer, point, (anchorRgb >> 16) & 0xFF, (anchorRgb >> 8) & 0xFF, anchorRgb & 0xFF, alpha);
            emitPixelEdgeVertex(buffer, i, side, scale, span, phase, amplitude, tipAlpha, pointB);
            emitPixelEdgeVertex(buffer, next, side, scale, span, phase, amplitude, tipAlpha, pointC);
        }
        tessellator.draw();

        // Always stroke the outer silhouette — that is the wing outline in the reference image.
        drawPixelOutline(side, scale, span, phase, amplitude, points);

        if (!outline.isToggled()) return;
        float width = (float) edgeWidth.getInput();
        if (width <= 0.01f) return;
        int ea = edgeColor.getAlpha();
        GL11.glLineWidth(width);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PIXEL_SEAMS.length; i += 4) {
            int rgbA = edgeRGB(pixelT(PIXEL_SEAMS[i]));
            int rgbB = edgeRGB(pixelT(PIXEL_SEAMS[i + 2]));
            transformPlanar(PIXEL_SEAMS[i], PIXEL_SEAMS[i + 1], pixelDepth(PIXEL_SEAMS[i]), side, scale, span, phase, amplitude, pointB);
            transformPlanar(PIXEL_SEAMS[i + 2], PIXEL_SEAMS[i + 3], pixelDepth(PIXEL_SEAMS[i + 2]), side, scale, span, phase, amplitude, pointC);
            vertex(buffer, pointB, (rgbA >> 16) & 0xFF, (rgbA >> 8) & 0xFF, rgbA & 0xFF, Math.round(ea * 0.42f));
            vertex(buffer, pointC, (rgbB >> 16) & 0xFF, (rgbB >> 8) & 0xFF, rgbB & 0xFF, Math.round(ea * 0.42f));
        }
        tessellator.draw();
    }

    private void emitPixelEdgeVertex(WorldRenderer buffer, int index, int side, float scale, float span,
                                     float phase, float amplitude, int alpha, float[] scratch) {
        float x = PIXEL_SHAPE[index * 2];
        float y = PIXEL_SHAPE[index * 2 + 1];
        int rgb = baseColor(pixelT(x));
        transformPlanar(x, y, pixelDepth(x), side, scale, span, phase, amplitude, scratch);
        vertex(buffer, scratch, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
    }

    private void drawPixelOutline(int side, float scale, float span, float phase, float amplitude, int points) {
        int mode = (int) colorMode.getInput();
        int outlineAlpha = mode == COLOR_STATIC ? edgeColor.getAlpha() : Math.max(160, fillColor.getAlpha());
        GL11.glLineWidth(outline.isToggled() ? Math.max(1.2f, (float) edgeWidth.getInput()) : 1.6f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < points; i++) {
            float x = PIXEL_SHAPE[i * 2];
            int rgb = edgeRGB(pixelT(x));
            transformPlanar(x, PIXEL_SHAPE[i * 2 + 1], pixelDepth(x), side, scale, span, phase, amplitude, point);
            vertex(buffer, point, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, outlineAlpha);
        }
        tessellator.draw();
    }

    private float pixelT(float x) {
        return Math.max(0.0f, Math.min(1.0f, x / 1.12f));
    }

    private float pixelDepth(float x) {
        return 0.07f + x * 0.16f;
    }

    // ---------------------------------------------------------------- shards

    private void drawShardWing(int side, float scale, float span, float phase, float amplitude) {
        for (int i = 0; i < PANELS_SHARD; i++) {
            float step = 1.0f / PANELS_SHARD;
            float ta = (i + 0.16f) * step;
            float tb = (i + 0.84f) * step;
            int base = i * 12;
            shardCorner(base, ta, false, side, scale, span, phase, amplitude);
            shardCorner(base + 3, tb, false, side, scale, span, phase, amplitude);
            shardCorner(base + 6, tb, true, side, scale, span, phase, amplitude);
            shardCorner(base + 9, ta, true, side, scale, span, phase, amplitude);
        }

        int alpha = fillAlpha();
        int tipAlpha = Math.round(alpha * tipFade());
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PANELS_SHARD; i++) {
            int base = i * 12;
            float t = (i + 0.5f) / PANELS_SHARD;
            int rgb = baseColor(t);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            shardVertex(buffer, base, r, g, b, alpha);
            shardVertex(buffer, base + 3, r, g, b, alpha);
            shardVertex(buffer, base + 6, r, g, b, tipAlpha);
            shardVertex(buffer, base + 9, r, g, b, tipAlpha);
        }
        tessellator.draw();

        // Outer silhouette of the whole fan — the only outline when "Outline" is off.
        drawShardOutline(side, tipAlpha);

        if (!outline.isToggled()) return;
        float width = (float) edgeWidth.getInput();
        if (width <= 0.01f) return;
        int ea = edgeColor.getAlpha();
        GL11.glLineWidth(width);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PANELS_SHARD; i++) {
            int base = i * 12;
            int rgb = edgeRGB((i + 0.5f) / PANELS_SHARD);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            shardEdge(buffer, base + 3, base + 6, r, g, b, ea, Math.round(ea * 0.55f));
            shardEdge(buffer, base + 9, base, r, g, b, Math.round(ea * 0.55f), ea);
        }
        tessellator.draw();
    }

    private void drawShardOutline(int side, int tipAlpha) {
        int mode = (int) colorMode.getInput();
        int outlineAlpha = mode == COLOR_STATIC ? edgeColor.getAlpha() : Math.max(150, fillColor.getAlpha());
        GL11.glLineWidth(outline.isToggled() ? Math.max(1.2f, (float) edgeWidth.getInput()) : 1.6f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        // near roots left -> right
        for (int i = 0; i < PANELS_SHARD; i++) {
            int rgb = edgeRGB((i + 0.16f) / PANELS_SHARD);
            shardOutlineVertex(buffer, i * 12, rgb, outlineAlpha);
            shardOutlineVertex(buffer, i * 12 + 3, rgb, outlineAlpha);
        }
        // far tips right -> left
        for (int i = PANELS_SHARD - 1; i >= 0; i--) {
            int rgb = edgeRGB((i + 0.84f) / PANELS_SHARD);
            shardOutlineVertex(buffer, i * 12 + 6, rgb, outlineAlpha);
            shardOutlineVertex(buffer, i * 12 + 9, rgb, outlineAlpha);
        }
        tessellator.draw();
    }

    private void shardOutlineVertex(WorldRenderer buffer, int offset, int rgb, int alpha) {
        buffer.pos(shardCorners[offset], shardCorners[offset + 1], shardCorners[offset + 2])
                .color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha).endVertex();
    }

    private void shardCorner(int offset, float t, boolean far, int side, float scale, float span,
                             float phase, float amplitude) {
        float x = 0.10f + 0.82f * t;
        float y = 0.36f * (float) Math.sin(t * 1.85f);
        float z = 0.10f + 0.28f * t * t;
        if (far) {
            float angle = 0.05f + 0.75f * t;
            float length = 0.34f + 1.06f * (float) Math.sin(Math.PI * Math.pow(t, 2.2));
            x += (float) Math.sin(angle) * 0.60f * length;
            y -= (float) Math.cos(angle) * length;
            z += (float) Math.sin(angle) * 0.52f * length;
        }
        transformRealistic(x, y, z, side, scale, span, phase, amplitude, point);
        shardCorners[offset] = point[0];
        shardCorners[offset + 1] = point[1];
        shardCorners[offset + 2] = point[2];
    }

    private void shardVertex(WorldRenderer buffer, int offset, int red, int green, int blue, int alpha) {
        buffer.pos(shardCorners[offset], shardCorners[offset + 1], shardCorners[offset + 2])
                .color(red, green, blue, alpha).endVertex();
    }

    private void shardEdge(WorldRenderer buffer, int from, int to, int red, int green, int blue,
                           int fromAlpha, int toAlpha) {
        shardVertex(buffer, from, red, green, blue, fromAlpha);
        shardVertex(buffer, to, red, green, blue, toAlpha);
    }

    // ---------------------------------------------------------------- transforms

    private void transformPlanar(float x, float y, float z, int side, float scale, float span,
                                 float phase, float amplitude, float[] out) {
        transformRealistic(x, y, z, side, scale, span, phase, amplitude, out);
    }

    private void transformRealistic(float x, float y, float z, int side, float scale, float span,
                                    float phase, float amplitude, float[] out) {
        float t = Math.max(0.0f, Math.min(1.0f, x / 1.25f));
        if (amplitude != 0.0f) {
            float flex = (float) Math.sin(phase - 0.92f * t) * amplitude * t * t;
            y += flex * 0.58f;
            z += (float) Math.cos(phase - 0.65f * t) * amplitude * t * t * 0.24f;
            x -= Math.abs(flex) * t * 0.10f;
        }
        float px = x * scale * span;
        float py = y * scale;
        float pz = z * scale;
        flap(px, py, pz, t, side, phase, amplitude, out);
    }

    private void flap(float px, float py, float pz, float t, int side,
                      float phase, float amplitude, float[] out) {
        if (amplitude != 0.0f) {
            float lag = phase - t * 0.68f;
            float reach = 0.34f + 0.66f * t;
            float wave = (float) Math.sin(lag) * 0.82f + (float) Math.sin(lag * 2.0f - 0.45f) * 0.10f;
            float beat = wave * amplitude * reach;
            float sweep = (float) Math.sin(lag - 0.82f) * amplitude * 0.22f * reach;
            float cosBeat = (float) Math.cos(beat);
            float sinBeat = (float) Math.sin(beat);
            float rolledX = px * cosBeat - py * sinBeat;
            float rolledY = px * sinBeat + py * cosBeat;
            float cosSweep = (float) Math.cos(sweep);
            float sinSweep = (float) Math.sin(sweep);
            px = rolledX * cosSweep + pz * sinSweep;
            pz = -rolledX * sinSweep + pz * cosSweep;
            py = rolledY;
        }
        out[0] = px * side;
        out[1] = py;
        out[2] = pz;
    }

    private void vertex(WorldRenderer buffer, float[] source, int red, int green, int blue, int alpha) {
        buffer.pos(source[0], source[1], source[2]).color(red, green, blue, alpha).endVertex();
    }

    private int shade(int channel, float amount) {
        return Math.max(0, Math.min(255, Math.round(channel * amount)));
    }

    private int fillAlpha() {
        int base = fillColor.getAlpha();
        return transparent.isToggled() ? base : Math.max(base, SOLID_ALPHA);
    }

    private float tipFade() {
        return transparent.isToggled() ? TIP_FADE_GLASS : TIP_FADE_SOLID;
    }

    private float phase() {
        double speed = flapSpeed.getInput();
        if (speed <= 0.0) return 0.0f;
        return (float) ((System.nanoTime() / 1.0E9) * speed * 1.65);
    }

    private float flapDrive(EntityPlayerSP player) {
        if (flapSpeed.getInput() <= 0.0) return 0.0f;
        double dx = player.posX - player.lastTickPosX;
        double dz = player.posZ - player.lastTickPosZ;
        float motion = (float) Math.min(1.0, Math.sqrt(dx * dx + dz * dz) * 3.2);
        float drive = 0.42f + 0.58f * motion;
        return player.onGround ? drive : Math.min(1.28f, drive + 0.30f);
    }
}
