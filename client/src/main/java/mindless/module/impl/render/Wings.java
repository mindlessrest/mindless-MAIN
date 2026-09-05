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

public class Wings extends Module {

    private static final int SHAPE_WINGS = 0;
    private static final int SHAPE_REALISTIC = 1;
    private static final int SHAPE_SHARDS = 2;
    private static final String[] SHAPES = {"Wings", "Realistic", "Shards"};
    private static final int PANELS_SHARD = 6;
    private static final float ROOT_Y = 1.32f;
    private static final float ROOT_Z = 0.14f;
    private static final float SNEAK_DROP = 0.22f;
    private static final float TIP_FADE_GLASS = 0.34f;
    private static final float TIP_FADE_SOLID = 0.88f;
    private static final int SOLID_ALPHA = 242;

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

    private static final float[] PIXEL_SEAMS = {
            0.18f, 0.27f, 0.65f, 0.53f,
            0.25f, 0.08f, 0.82f, 0.37f,
            0.37f,-0.09f, 0.99f, 0.17f,
            0.49f,-0.27f, 1.01f,-0.07f,
            0.61f,-0.46f, 0.89f,-0.28f
    };

    private static final float[] PIXEL_FEATHERS = {
            0.18f, 0.28f, 0.31f, 0.30f, 0.55f,-0.39f, 0.48f,-0.47f,
            0.30f, 0.48f, 0.47f, 0.48f, 0.75f,-0.57f, 0.62f,-0.67f,
            0.47f, 0.64f, 0.65f, 0.64f, 0.91f,-0.38f, 0.76f,-0.47f,
            0.65f, 0.54f, 0.82f, 0.54f, 1.03f,-0.17f, 0.90f,-0.28f,
            0.82f, 0.38f, 0.99f, 0.38f, 1.12f, 0.02f, 1.02f,-0.08f
    };

    private static final float[][] REALISTIC_LAYERS = {
            {0.25f, 1.00f,10.0f, 0.90f, 0.140f,  0.10f, -0.010f, 0.84f},
            {0.03f, 0.76f, 9.0f, 0.67f, 0.145f, -0.12f,  0.015f, 0.92f},
            {0.00f, 0.98f,12.0f, 0.38f, 0.135f, -0.28f,  0.060f, 1.00f}
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

    private final SliderSetting shape;
    private final ButtonSetting transparent;
    private final ButtonSetting visibleLines;
    private final SliderSetting size;
    private final SliderSetting spread;
    private final SliderSetting flapSpeed;
    private final SliderSetting flapAmount;
    private final ColorSetting fillColor;
    private final ColorSetting edgeColor;
    private final SliderSetting edgeWidth;
    private final ButtonSetting throughWalls;
    private final ButtonSetting hideFirstPerson;
    private final float[] point = new float[3];
    private final float[] pointB = new float[3];
    private final float[] pointC = new float[3];
    private final float[] ribs = new float[FEATHER_PROFILE.length / 2 * 12];
    private final float[] shardCorners = new float[PANELS_SHARD * 12];

    public Wings() {
        super("Wings", "Wings that sit on your back.", category.render);
        this.registerSetting(shape = new SliderSetting("Shape", SHAPE_WINGS, SHAPES));
        this.registerSetting(transparent = new ButtonSetting("Transparent", false));
        this.registerSetting(visibleLines = new ButtonSetting("Visible lines", false));
        this.registerSetting(size = new SliderSetting("Size", 1.0, 0.4, 2.5, 0.05));
        this.registerSetting(spread = new SliderSetting("Spread", 1.0, 0.4, 2.0, 0.05));
        this.registerSetting(flapSpeed = new SliderSetting("Flap speed", 1.0, 0.0, 4.0, 0.1));
        this.registerSetting(flapAmount = new SliderSetting("Flap amount", "\u00B0", 12.0, 0.0, 40.0, 1.0));
        this.registerSetting(fillColor = new ColorSetting("Fill color", 214, 224, 255, 178));
        this.registerSetting(edgeColor = new ColorSetting("Edge color", 255, 255, 255, 205));
        this.registerSetting(edgeWidth = new SliderSetting("Edge width", 1.1, 0.0, 3.0, 0.1));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", false));
        this.registerSetting(hideFirstPerson = new ButtonSetting("Hide in first person", true));
    }

    @Override
    public void guiUpdate() {
        flapAmount.setVisible(flapSpeed.getInput() > 0.0, this);
        edgeColor.setVisible(visibleLines.isToggled(), this);
        edgeWidth.setVisible(visibleLines.isToggled(), this);
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
        GL11.glShadeModel(GL11.GL_SMOOTH);

        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(180.0f - bodyYaw, 0.0f, 1.0f, 0.0f);
        GlStateManager.translate(0.0f, player.isSneaking() ? ROOT_Y - SNEAK_DROP : ROOT_Y, ROOT_Z);

        int style = (int) shape.getInput();
        float scale = (float) size.getInput();
        float span = (float) spread.getInput();
        float phase = phase();
        float amplitude = (float) Math.toRadians(flapAmount.getInput()) * flapDrive(player);

        for (int side = -1; side <= 1; side += 2) {
            if (style == SHAPE_REALISTIC) drawRealisticWing(side, scale, span, phase, amplitude);
            else if (style == SHAPE_WINGS) drawPixelWing(side, scale, span, phase, amplitude);
            else drawShardWing(side, scale, span, phase, amplitude);
        }

        GL11.glLineWidth(1.0f);
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

    private void drawPixelWing(int side, float scale, float span, float phase, float amplitude) {
        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = fillAlpha();
        int points = PIXEL_SHAPE.length / 2;
        transformPlanar(0.57f, 0.08f, 0.18f, side, scale, span, phase, amplitude, point);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            vertex(buffer, point, red, green, blue, alpha);
            transformPlanar(PIXEL_SHAPE[i * 2], PIXEL_SHAPE[i * 2 + 1], pixelDepth(PIXEL_SHAPE[i * 2]), side, scale, span, phase, amplitude, pointB);
            transformPlanar(PIXEL_SHAPE[next * 2], PIXEL_SHAPE[next * 2 + 1], pixelDepth(PIXEL_SHAPE[next * 2]), side, scale, span, phase, amplitude, pointC);
            vertex(buffer, pointB, red, green, blue, alpha);
            vertex(buffer, pointC, red, green, blue, alpha);
        }
        for (int i = 0; i < PIXEL_FEATHERS.length; i += 8) {
            float shade = 0.78f + 0.045f * ((i / 8) & 3);
            int fr = shade(red, shade);
            int fg = shade(green, shade);
            int fb = shade(blue, shade);
            transformPlanar(PIXEL_FEATHERS[i], PIXEL_FEATHERS[i + 1], pixelDepth(PIXEL_FEATHERS[i]) - 0.012f, side, scale, span, phase, amplitude, point);
            transformPlanar(PIXEL_FEATHERS[i + 2], PIXEL_FEATHERS[i + 3], pixelDepth(PIXEL_FEATHERS[i + 2]) - 0.012f, side, scale, span, phase, amplitude, pointB);
            transformPlanar(PIXEL_FEATHERS[i + 4], PIXEL_FEATHERS[i + 5], pixelDepth(PIXEL_FEATHERS[i + 4]) - 0.012f, side, scale, span, phase, amplitude, pointC);
            vertex(buffer, point, fr, fg, fb, alpha);
            vertex(buffer, pointB, fr, fg, fb, alpha);
            vertex(buffer, pointC, fr, fg, fb, Math.round(alpha * 0.92f));
            transformPlanar(PIXEL_FEATHERS[i + 6], PIXEL_FEATHERS[i + 7], pixelDepth(PIXEL_FEATHERS[i + 6]) - 0.012f, side, scale, span, phase, amplitude, pointB);
            vertex(buffer, point, fr, fg, fb, alpha);
            vertex(buffer, pointC, fr, fg, fb, Math.round(alpha * 0.92f));
            vertex(buffer, pointB, fr, fg, fb, Math.round(alpha * 0.92f));
        }
        tessellator.draw();

        float width = (float) edgeWidth.getInput();
        if (!visibleLines.isToggled() || width <= 0.01f) return;
        int edge = edgeColor.getColor();
        int er = (edge >> 16) & 0xFF;
        int eg = (edge >> 8) & 0xFF;
        int eb = edge & 0xFF;
        int ea = (edge >>> 24) & 0xFF;
        GL11.glLineWidth(width);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            transformPlanar(PIXEL_SHAPE[i * 2], PIXEL_SHAPE[i * 2 + 1], pixelDepth(PIXEL_SHAPE[i * 2]), side, scale, span, phase, amplitude, pointB);
            transformPlanar(PIXEL_SHAPE[next * 2], PIXEL_SHAPE[next * 2 + 1], pixelDepth(PIXEL_SHAPE[next * 2]), side, scale, span, phase, amplitude, pointC);
            vertex(buffer, pointB, er, eg, eb, ea);
            vertex(buffer, pointC, er, eg, eb, ea);
        }
        for (int i = 0; i < PIXEL_SEAMS.length; i += 4) {
            transformPlanar(PIXEL_SEAMS[i], PIXEL_SEAMS[i + 1], pixelDepth(PIXEL_SEAMS[i]), side, scale, span, phase, amplitude, pointB);
            transformPlanar(PIXEL_SEAMS[i + 2], PIXEL_SEAMS[i + 3], pixelDepth(PIXEL_SEAMS[i + 2]), side, scale, span, phase, amplitude, pointC);
            vertex(buffer, pointB, er, eg, eb, Math.round(ea * 0.42f));
            vertex(buffer, pointC, er, eg, eb, Math.round(ea * 0.42f));
        }
        tessellator.draw();
    }

    private float pixelDepth(float x) {
        return 0.07f + x * 0.16f;
    }

    private void drawRealisticWing(int side, float scale, float span, float phase, float amplitude) {
        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = fillAlpha();

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        for (int layer = 0; layer < REALISTIC_LAYERS.length; layer++) {
            float[] spec = REALISTIC_LAYERS[layer];
            int count = (int) spec[2];
            int rowRed = shade(red, spec[7]);
            int rowGreen = shade(green, spec[7]);
            int rowBlue = shade(blue, spec[7]);
            for (int i = 0; i < count; i++) {
                float along = count == 1 ? 0.5f : i / (float) (count - 1);
                float t = spec[0] + (spec[1] - spec[0]) * along;
                float width = spec[4] * (0.92f + 0.12f * (float) Math.sin((i + layer) * 2.13f));
                emitRealisticFeather(buffer, side, t, scale, span, phase, amplitude,
                        spec[3], width, spec[5], spec[6], layer,
                        rowRed, rowGreen, rowBlue, alpha, Math.round(alpha * tipFade()));
            }
        }
        tessellator.draw();

        if (visibleLines.isToggled()) drawRealisticLeadingEdge(side, scale, span, phase, amplitude);
    }

    private void emitRealisticFeather(WorldRenderer buffer, int side, float t, float scale, float span,
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
        dirX /= dirLength;
        dirY /= dirLength;
        dirZ /= dirLength;

        float wideX = -dirY;
        float wideY = dirX;
        float wideLength = (float) Math.sqrt(wideX * wideX + wideY * wideY);
        wideX /= wideLength;
        wideY /= wideLength;
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
        if (!visibleLines.isToggled() || width <= 0.01f) return;
        int colour = edgeColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = Math.round(((colour >>> 24) & 0xFF) * 0.72f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        GL11.glLineWidth(Math.max(0.7f, width * 0.72f));
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= 12; i++) {
            float t = i / 12.0f;
            float x = realisticSpineX(t);
            float y = realisticSpineY(t) + 0.045f;
            transformRealistic(x, y, realisticDepth(x) - 0.035f, side, scale, span, phase, amplitude, point);
            vertex(buffer, point, red, green, blue, alpha);
        }
        tessellator.draw();
    }

    private float realisticSpineX(float t) {
        return 0.08f + 1.16f * t;
    }

    private float realisticSpineY(float t) {
        return 0.06f + 0.72f * (float) Math.sin(t * 2.20f) - 0.26f * t;
    }

    private float realisticDepth(float x) {
        return 0.06f + 0.15f * x;
    }

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

        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = fillAlpha();
        int tipAlpha = Math.round(alpha * tipFade());
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PANELS_SHARD; i++) {
            int base = i * 12;
            shardVertex(buffer, base, red, green, blue, alpha);
            shardVertex(buffer, base + 3, red, green, blue, alpha);
            shardVertex(buffer, base + 6, red, green, blue, tipAlpha);
            shardVertex(buffer, base + 9, red, green, blue, tipAlpha);
        }
        tessellator.draw();

        float width = (float) edgeWidth.getInput();
        if (!visibleLines.isToggled() || width <= 0.01f) return;
        int edge = edgeColor.getColor();
        int er = (edge >> 16) & 0xFF;
        int eg = (edge >> 8) & 0xFF;
        int eb = edge & 0xFF;
        int ea = (edge >>> 24) & 0xFF;
        GL11.glLineWidth(width);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PANELS_SHARD; i++) {
            int base = i * 12;
            shardEdge(buffer, base, base + 3, er, eg, eb, ea, ea);
            shardEdge(buffer, base + 3, base + 6, er, eg, eb, ea, Math.round(ea * 0.55f));
            shardEdge(buffer, base + 6, base + 9, er, eg, eb, Math.round(ea * 0.55f), Math.round(ea * 0.55f));
            shardEdge(buffer, base + 9, base, er, eg, eb, Math.round(ea * 0.55f), ea);
        }
        tessellator.draw();
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
        int base = (fillColor.getColor() >>> 24) & 0xFF;
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
