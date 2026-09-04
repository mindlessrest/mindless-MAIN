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

/**
 * Wings on your back.
 *
 * Every shape shares one skeleton: a spine that sweeps up and back from the shoulder, and a set of
 * feathers hanging off it -- almost straight down at the root, fanning outward as they go, longest
 * around three quarters of the way out and tapering to the tip. What changes is what gets drawn
 * along that skeleton.
 *
 * Panels (Wings, Shards) put a flat quad between each pair of steps, which is the cut-glass look.
 * Realistic lays three overlapping rows of tapered feathers instead -- primaries, secondaries and
 * coverts, drawn back to front the way a real wing stacks -- so the silhouette is a wing rather
 * than a fan. Transparency is separate from either, so a realistic wing can be solid or glass.
 *
 * Nothing is loaded and nothing is cached. The geometry is rebuilt each frame from the settings,
 * so changing the size or the beat shows immediately and costs what a fixed shape would.
 */
public class Wings extends Module {

    private static final int SHAPE_WINGS = 0;
    private static final int SHAPE_REALISTIC = 1;
    private static final int SHAPE_SHARDS = 2;
    private static final String[] SHAPES = {"Wings", "Realistic", "Shards"};

    /** Panelled wings want many narrow steps; shards want a few broad ones. */
    private static final int PANELS_WING = 11;
    private static final int PANELS_SHARD = 6;
    private static final int MAX_PANELS = 11;

    /** Where the wings meet the body, in blocks from the feet and behind the back. */
    private static final float ROOT_Y = 1.34f;
    private static final float ROOT_Z = 0.13f;
    private static final float SNEAK_DROP = 0.22f;

    /** Alpha at a feather's end against alpha at its root, glass and solid. */
    private static final float TIP_FADE_GLASS = 0.28f;
    private static final float TIP_FADE_SOLID = 0.82f;
    /** The alpha a fill is forced to when transparency is off. */
    private static final int SOLID_ALPHA = 238;

    /**
     * The three rows of a real wing, back to front.
     *
     * Each row is {span start, span end, feathers, length, width, angle bias, lift}. Primaries are
     * the long outer flight feathers; secondaries fill the inner half; coverts are the short row
     * that laps over where the others root, which is what hides the fan seam and makes the whole
     * thing read as one wing instead of a row of blades.
     */
    private static final float[][] LAYERS = {
            {0.34f, 1.00f, 10.0f, 1.00f, 1.00f,  0.00f, 0.000f},
            {0.06f, 0.76f,  9.0f, 0.60f, 1.10f, -0.14f, 0.030f},
            {0.00f, 0.58f, 11.0f, 0.30f, 1.15f, -0.30f, 0.060f}
    };

    /**
     * A feather's outline as (distance along it, half width). Narrow at the quill, widest just
     * past halfway, closing to a point -- the profile is what separates a feather from a stick.
     */
    private static final float[] FEATHER_PROFILE = {
            0.00f, 0.10f,
            0.18f, 0.44f,
            0.42f, 0.64f,
            0.68f, 0.58f,
            0.88f, 0.34f,
            1.00f, 0.00f
    };

    private final SliderSetting shape;
    private final ButtonSetting transparent;
    private final SliderSetting size;
    private final SliderSetting spread;
    private final SliderSetting flapSpeed;
    private final SliderSetting flapAmount;
    private final ColorSetting fillColor;
    private final ColorSetting edgeColor;
    private final SliderSetting edgeWidth;
    private final ButtonSetting throughWalls;
    private final ButtonSetting hideFirstPerson;

    /** Four corners of three floats, per panel. Reused; the panelled shapes never allocate. */
    private final float[] corners = new float[MAX_PANELS * 12];
    /** Scratch for one transformed point, so the feather path does not allocate either. */
    private final float[] point = new float[3];
    /** One feather's ribs: two edge points and a shared alpha per profile step. */
    private final float[] ribs = new float[FEATHER_PROFILE.length / 2 * 8];

    public Wings() {
        super("Wings", "Wings that sit on your back.", category.render);
        this.registerSetting(shape = new SliderSetting("Shape", SHAPE_WINGS, SHAPES));
        this.registerSetting(transparent = new ButtonSetting("Transparent", true));
        this.registerSetting(size = new SliderSetting("Size", 1.0, 0.4, 2.5, 0.05));
        this.registerSetting(spread = new SliderSetting("Spread", 1.0, 0.4, 2.0, 0.05));
        this.registerSetting(flapSpeed = new SliderSetting("Flap speed", 1.0, 0.0, 4.0, 0.1));
        this.registerSetting(flapAmount = new SliderSetting("Flap amount", "°", 14.0, 0.0, 40.0, 1.0));
        this.registerSetting(fillColor = new ColorSetting("Fill color", 176, 216, 255, 66));
        this.registerSetting(edgeColor = new ColorSetting("Edge color", 255, 255, 255, 205));
        this.registerSetting(edgeWidth = new SliderSetting("Edge width", 1.2, 0.0, 3.0, 0.1));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", false));
        this.registerSetting(hideFirstPerson = new ButtonSetting("Hide in first person", true));
    }

    @Override
    public void guiUpdate() {
        flapAmount.setVisible(flapSpeed.getInput() > 0.0, this);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) return;

        EntityPlayerSP player = mc.thePlayer;
        // In first person the wings sit behind the camera, but a wide spread still clips through
        // it as they beat forward, which reads as a flicker at the edge of the screen.
        if (hideFirstPerson.isToggled() && mc.gameSettings.thirdPersonView == 0
                && mc.getRenderViewEntity() == player) {
            return;
        }

        float partialTicks = event.partialTicks;
        RenderManager manager = mc.getRenderManager();
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks
                - manager.viewerPosX;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks
                - manager.viewerPosY;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks
                - manager.viewerPosZ;
        float bodyYaw = player.prevRenderYawOffset
                + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;

        render(x, y, z, bodyYaw, player);
    }

    private void render(double x, double y, double z, float bodyYaw, EntityPlayerSP player) {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls.isToggled()) {
            GlStateManager.disableDepth();
        }
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        GlStateManager.translate(x, y, z);
        // Vanilla's own entity rotation: this puts local +Z behind the player and +Y up, so the
        // wings are laid out in body space and never have to know which way the world faces.
        GlStateManager.rotate(180.0f - bodyYaw, 0.0f, 1.0f, 0.0f);
        GlStateManager.translate(0.0f, player.isSneaking() ? ROOT_Y - SNEAK_DROP : ROOT_Y, ROOT_Z);

        int style = (int) shape.getInput();
        float scale = (float) size.getInput();
        float span = (float) spread.getInput();
        float phase = phase();
        float amplitude = (float) Math.toRadians(flapAmount.getInput()) * flapDrive(player);

        for (int side = -1; side <= 1; side += 2) {
            if (style == SHAPE_REALISTIC) {
                drawFeatheredWing(side, scale, span, phase, amplitude);
            }
            else {
                int panels = style == SHAPE_SHARDS ? PANELS_SHARD : PANELS_WING;
                layOut(panels, side, style, scale, span, phase, amplitude);
                drawPanelFill(panels);
                drawPanelEdges(panels);
            }
        }

        GL11.glLineWidth(1.0f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glShadeModel(GL11.GL_FLAT);
        if (throughWalls.isToggled()) {
            GlStateManager.enableDepth();
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ shared skeleton

    private static float spineX(float t, float scale, float span) {
        return scale * span * (0.10f + 0.80f * t);
    }

    private static float spineY(float t, float scale, float shard) {
        return scale * (0.58f - 0.22f * shard) * (float) Math.sin(t * 1.85f);
    }

    private static float spineZ(float t, float scale) {
        return scale * (0.10f + 0.28f * t * t);
    }

    private static float featherLength(float t, float scale, float shard) {
        return scale * (0.34f + (0.86f + 0.20f * shard)
                * (float) Math.sin(Math.PI * Math.pow(t, 2.2)));
    }

    /**
     * The beat, applied about the shoulder.
     *
     * Roll about the fore-aft axis is the stroke; the yaw sweep, a third of it and offset in
     * phase, is the recovery that stops it looking like a hinge. Both scale with how far out along
     * the spine the point sits and lag behind by the same measure, so the shoulder leads and the
     * tip follows -- which is the difference between a wing beating and a board tilting.
     */
    private void flap(float px, float py, float pz, float t, int side,
                      float phase, float amplitude, float[] out) {
        if (amplitude != 0.0f) {
            float lag = phase - t * 0.85f;
            float reach = 0.45f + 0.55f * t;
            float beat = (float) Math.sin(lag) * amplitude * reach;
            float sweep = (float) Math.sin(lag - 0.9f) * amplitude * 0.35f * reach;

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

    // ------------------------------------------------------------------ panelled shapes

    /**
     * Lays one panelled wing into the corner array.
     *
     * Each panel is a quad with two corners on the spine and two at its far end, so neighbouring
     * panels share an edge and the wing reads as one surface with seams rather than as a scatter
     * of loose blades. The small gap pulled out of each step is what keeps those seams visible.
     */
    private void layOut(int panels, int side, int style, float scale, float span,
                        float phase, float amplitude) {
        float gap = style == SHAPE_SHARDS ? 0.16f : 0.055f;

        for (int i = 0; i < panels; i++) {
            float step = 1.0f / panels;
            float ta = (i + gap) * step;
            float tb = (i + 1.0f - gap) * step;
            int base = i * 12;
            corner(base, ta, side, style, scale, span, phase, amplitude, false);
            corner(base + 3, tb, side, style, scale, span, phase, amplitude, false);
            corner(base + 6, tb, side, style, scale, span, phase, amplitude, true);
            corner(base + 9, ta, side, style, scale, span, phase, amplitude, true);
        }
    }

    private void corner(int offset, float t, int side, int style, float scale, float span,
                        float phase, float amplitude, boolean far) {
        float shard = style == SHAPE_SHARDS ? 1.0f : 0.0f;

        float px = spineX(t, scale, span);
        float py = spineY(t, scale, shard);
        float pz = spineZ(t, scale);

        if (far) {
            // Feathers hang almost straight down at the shoulder and fan outward as they go
            // outboard, raking back only about half as far as they spread -- lean on the rake and
            // the wings stop being wings and start being a tail.
            float angle = 0.05f + (1.05f - 0.30f * shard) * t;
            float length = featherLength(t, scale, shard);
            px += (float) Math.sin(angle) * 0.60f * length;
            py -= (float) Math.cos(angle) * length;
            pz += (float) Math.sin(angle) * 0.52f * length;
        }

        flap(px, py, pz, t, side, phase, amplitude, point);
        corners[offset] = point[0];
        corners[offset + 1] = point[1];
        corners[offset + 2] = point[2];
    }

    private void drawPanelFill(int panels) {
        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = fillAlpha();
        int tipAlpha = Math.round(alpha * tipFade());

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < panels; i++) {
            int base = i * 12;
            corner(buffer, base, red, green, blue, alpha);
            corner(buffer, base + 3, red, green, blue, alpha);
            corner(buffer, base + 6, red, green, blue, tipAlpha);
            corner(buffer, base + 9, red, green, blue, tipAlpha);
        }
        tessellator.draw();
    }

    private void drawPanelEdges(int panels) {
        float width = (float) edgeWidth.getInput();
        if (width <= 0.01f) return;

        int colour = edgeColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = (colour >>> 24) & 0xFF;
        int tipAlpha = Math.round(alpha * 0.55f);

        GL11.glLineWidth(width);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        // One GL_LINES batch rather than a loop per panel: a line loop cannot be restarted inside
        // a draw, and one draw call per panel is what makes an outline pass expensive.
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < panels; i++) {
            int base = i * 12;
            edge(buffer, base, base + 3, red, green, blue, alpha, alpha);
            edge(buffer, base + 3, base + 6, red, green, blue, alpha, tipAlpha);
            edge(buffer, base + 6, base + 9, red, green, blue, tipAlpha, tipAlpha);
            edge(buffer, base + 9, base, red, green, blue, tipAlpha, alpha);
        }
        tessellator.draw();
    }

    private void edge(WorldRenderer buffer, int from, int to,
                      int red, int green, int blue, int fromAlpha, int toAlpha) {
        corner(buffer, from, red, green, blue, fromAlpha);
        corner(buffer, to, red, green, blue, toAlpha);
    }

    private void corner(WorldRenderer buffer, int offset, int red, int green, int blue, int alpha) {
        buffer.pos(corners[offset], corners[offset + 1], corners[offset + 2])
                .color(red, green, blue, alpha).endVertex();
    }

    // ------------------------------------------------------------------ realistic shape

    /**
     * Three rows of tapered feathers, drawn back to front.
     *
     * Order is the whole trick: with depth writes off, what is drawn last sits on top, so painting
     * primaries first and coverts last stacks the rows the way a wing actually overlaps. Each
     * feather is its own strip, which means a feather can taper and cross its neighbours -- the
     * thing a fan of quads can never do, and the reason the panelled shapes read as glass panes
     * rather than as plumage.
     */
    private void drawFeatheredWing(int side, float scale, float span, float phase, float amplitude) {
        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = fillAlpha();
        float fade = tipFade();

        // Every feather of every row goes into one batch as triangles rather than a strip apiece.
        // A strip cannot be restarted inside a draw, so a strip per feather would be sixty draw
        // calls a frame; blending still resolves in the order vertices were written, which is what
        // keeps the rows stacked.
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        for (int layer = 0; layer < LAYERS.length; layer++) {
            float[] spec = LAYERS[layer];
            int count = (int) spec[2];
            // Each row back from the primaries is a shade darker, so overlapping rows separate
            // instead of merging into one flat mass.
            float shade = 1.0f - layer * 0.09f;
            int rowRed = Math.round(red * shade);
            int rowGreen = Math.round(green * shade);
            int rowBlue = Math.round(blue * shade);

            for (int i = 0; i < count; i++) {
                float along = count == 1 ? 0.5f : i / (float) (count - 1);
                float t = spec[0] + (spec[1] - spec[0]) * along;
                float width = (spec[1] - spec[0]) / count * spec[4] * 1.35f;
                emitFeather(buffer, side, t, scale, span, phase, amplitude,
                        spec[3], width, spec[5], spec[6],
                        rowRed, rowGreen, rowBlue, alpha, Math.round(alpha * fade));
            }
        }

        tessellator.draw();
    }

    private void emitFeather(WorldRenderer buffer, int side, float t, float scale, float span,
                             float phase, float amplitude, float lengthScale, float halfWidth,
                             float angleBias, float lift,
                             int red, int green, int blue, int rootAlpha, int tipAlpha) {
        float rootX = spineX(t, scale, span);
        float rootY = spineY(t, scale, 0.0f);
        float rootZ = spineZ(t, scale) - lift * scale;

        float angle = Math.max(0.02f, 0.05f + 1.05f * t + angleBias);
        float length = featherLength(t, scale, 0.0f) * lengthScale;
        float dirX = (float) Math.sin(angle) * 0.60f;
        float dirY = -(float) Math.cos(angle);
        float dirZ = (float) Math.sin(angle) * 0.52f;

        // The feather lies flat in the wing, so its width runs along the spine. Sampling the spine
        // either side of the root gives that direction without having to differentiate it.
        float back = Math.max(0.0f, t - 0.03f);
        float ahead = Math.min(1.0f, t + 0.03f);
        float tanX = spineX(ahead, scale, span) - spineX(back, scale, span);
        float tanY = spineY(ahead, scale, 0.0f) - spineY(back, scale, 0.0f);
        float tanZ = spineZ(ahead, scale) - spineZ(back, scale);
        float tanLength = (float) Math.sqrt(tanX * tanX + tanY * tanY + tanZ * tanZ);
        if (tanLength < 1.0E-5f) return;
        tanX /= tanLength;
        tanY /= tanLength;
        tanZ /= tanLength;

        float wide = halfWidth * scale * span;
        int steps = FEATHER_PROFILE.length / 2;
        for (int i = 0; i < steps; i++) {
            rib(i, rootX, rootY, rootZ, dirX, dirY, dirZ, tanX, tanY, tanZ, length, wide,
                    t, side, phase, amplitude, rootAlpha, tipAlpha, ribs, i * 8);
        }

        for (int i = 0; i + 1 < steps; i++) {
            int lower = i * 8;
            int upper = (i + 1) * 8;
            // Two triangles across the pair of ribs. Written as triangles rather than a strip so
            // the whole wing stays in one batch.
            rib(buffer, ribs, lower, red, green, blue);
            rib(buffer, ribs, lower + 4, red, green, blue);
            rib(buffer, ribs, upper + 4, red, green, blue);

            rib(buffer, ribs, lower, red, green, blue);
            rib(buffer, ribs, upper + 4, red, green, blue);
            rib(buffer, ribs, upper, red, green, blue);
        }
    }

    /** One rib of the feather: its two edge points and their shared alpha, into the scratch. */
    private void rib(int index, float rootX, float rootY, float rootZ,
                     float dirX, float dirY, float dirZ, float tanX, float tanY, float tanZ,
                     float length, float wide, float t, int side, float phase, float amplitude,
                     int rootAlpha, int tipAlpha, float[] out, int offset) {
        float step = FEATHER_PROFILE[index * 2];
        float half = FEATHER_PROFILE[index * 2 + 1] * wide;
        float alpha = rootAlpha + (tipAlpha - rootAlpha) * step;

        float cx = rootX + dirX * length * step;
        float cy = rootY + dirY * length * step;
        float cz = rootZ + dirZ * length * step;

        flap(cx - tanX * half, cy - tanY * half, cz - tanZ * half, t, side, phase, amplitude, point);
        out[offset] = point[0];
        out[offset + 1] = point[1];
        out[offset + 2] = point[2];
        out[offset + 3] = alpha;

        flap(cx + tanX * half, cy + tanY * half, cz + tanZ * half, t, side, phase, amplitude, point);
        out[offset + 4] = point[0];
        out[offset + 5] = point[1];
        out[offset + 6] = point[2];
        out[offset + 7] = alpha;
    }

    private void rib(WorldRenderer buffer, float[] source, int offset, int red, int green, int blue) {
        buffer.pos(source[offset], source[offset + 1], source[offset + 2])
                .color(red, green, blue, Math.round(source[offset + 3])).endVertex();
    }

    // ------------------------------------------------------------------ colour

    /**
     * Transparency is a switch, not a slider on the colour.
     *
     * Glass keeps whatever alpha the fill colour carries, so it can be tuned. Solid overrides it,
     * because a feather you can see the world through is not the look, and asking someone to hunt
     * for the alpha handle inside a colour picker to get there is a worse answer than a toggle.
     */
    private int fillAlpha() {
        int base = (fillColor.getColor() >>> 24) & 0xFF;
        return transparent.isToggled() ? base : Math.max(base, SOLID_ALPHA);
    }

    /** A glass feather fades out toward its end; a solid one only shades a little. */
    private float tipFade() {
        return transparent.isToggled() ? TIP_FADE_GLASS : TIP_FADE_SOLID;
    }

    // ------------------------------------------------------------------ motion

    private float phase() {
        double speed = flapSpeed.getInput();
        if (speed <= 0.0) return 0.0f;
        return (float) ((System.nanoTime() / 1.0E9) * speed * 2.6);
    }

    /**
     * How hard the wings are working.
     *
     * They idle while you stand still and beat harder the faster you move, hardest in the air.
     * A constant beat looks like an animation playing; one that answers what you are doing looks
     * like it belongs to you.
     */
    private float flapDrive(EntityPlayerSP player) {
        if (flapSpeed.getInput() <= 0.0) return 0.0f;
        double dx = player.posX - player.lastTickPosX;
        double dz = player.posZ - player.lastTickPosZ;
        float motion = (float) Math.min(1.0, Math.sqrt(dx * dx + dz * dz) * 3.4);
        float drive = 0.5f + 0.5f * motion;
        return player.onGround ? drive : Math.min(1.35f, drive + 0.35f);
    }
}
