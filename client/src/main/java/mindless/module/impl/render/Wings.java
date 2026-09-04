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
 * A pair of glass wings on your back.
 *
 * They are built as loose panels rather than as a texture: a curved spine sweeps up and back from
 * the shoulder, and a feather hangs off it at each step, longest around three quarters of the way
 * out and tapering to the tip. Each panel is drawn twice -- a faint fill that fades toward its end,
 * and a bright outline -- which is what gives the cut-glass look rather than a flat sheet.
 *
 * Nothing is loaded and nothing is cached: the geometry is forty vertices a wing, laid out into a
 * reused array every frame, so a change to the size or the flap shows immediately and costs the
 * same as a fixed shape would.
 */
public class Wings extends Module {

    private static final int SHAPE_WINGS = 0;
    private static final int SHAPE_SHARDS = 1;
    private static final String[] SHAPES = {"Wings", "Shards"};

    /** Feathered wings want many narrow panels; shards want a few broad ones. */
    private static final int FEATHERS_WING = 11;
    private static final int FEATHERS_SHARD = 6;
    private static final int MAX_FEATHERS = 11;

    /** Where the wings meet the body, in blocks from the feet and behind the back. */
    private static final float ROOT_Y = 1.34f;
    private static final float ROOT_Z = 0.13f;
    private static final float SNEAK_DROP = 0.22f;

    /** Fill alpha at the root against fill alpha at the tip. */
    private static final float TIP_FADE = 0.28f;

    private final SliderSetting shape;
    private final SliderSetting size;
    private final SliderSetting spread;
    private final SliderSetting flapSpeed;
    private final SliderSetting flapAmount;
    private final ColorSetting fillColor;
    private final ColorSetting edgeColor;
    private final SliderSetting edgeWidth;
    private final ButtonSetting throughWalls;
    private final ButtonSetting hideFirstPerson;

    /** Four corners of three floats, per feather. Reused; the wings never allocate. */
    private final float[] corners = new float[MAX_FEATHERS * 12];

    public Wings() {
        super("Wings", "Glass wings that sit on your back.", category.render);
        this.registerSetting(shape = new SliderSetting("Shape", SHAPE_WINGS, SHAPES));
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
        // it as they flap forward, which reads as a flicker at the edge of the screen.
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
        int feathers = style == SHAPE_SHARDS ? FEATHERS_SHARD : FEATHERS_WING;
        float scale = (float) size.getInput();
        float span = (float) spread.getInput();
        float phase = phase();
        float amplitude = (float) Math.toRadians(flapAmount.getInput()) * flapDrive(player);

        for (int side = -1; side <= 1; side += 2) {
            layOut(feathers, side, style, scale, span, phase, amplitude);
            drawFill(feathers);
            drawEdges(feathers);
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

    // ------------------------------------------------------------------ geometry

    /**
     * Lays one wing into the corner array.
     *
     * Each feather is a quad with two corners on the spine and two at its far end, so neighbouring
     * feathers share an edge and the wing reads as one surface with seams rather than as a scatter
     * of loose blades. The small gap pulled out of each step is what keeps those seams visible.
     *
     * The flap is applied per corner from that corner's own position along the spine, with a lag
     * that grows outward: the shoulder leads and the tip follows, which is the difference between
     * a wing beating and a flat board tilting.
     */
    private void layOut(int feathers, int side, int style, float scale, float span,
                        float phase, float amplitude) {
        float gap = style == SHAPE_SHARDS ? 0.16f : 0.055f;

        for (int i = 0; i < feathers; i++) {
            float step = 1.0f / feathers;
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

        // The spine: out along the shoulder, arcing up past head height, sweeping back as it goes.
        float px = scale * span * (0.10f + 0.80f * t);
        float py = scale * (0.58f - 0.22f * shard) * (float) Math.sin(t * 1.85f);
        float pz = scale * (0.10f + 0.28f * t * t);

        if (far) {
            // Feathers hang almost straight down at the shoulder and fan outward as they go
            // outboard, raking back only about half as far as they spread -- lean on the rake and
            // the wings stop being wings and start being a tail. Shards keep a flatter, straighter
            // fan, which is what makes them read as panels rather than plumage.
            float angle = 0.05f + (1.05f - 0.30f * shard) * t;
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);
            float length = scale * (0.34f + (0.86f + 0.20f * shard)
                    * (float) Math.sin(Math.PI * Math.pow(t, 2.2)));
            px += sin * 0.60f * length;
            py -= cos * length;
            pz += sin * 0.52f * length;
        }

        if (amplitude != 0.0f) {
            // Roll about the fore-aft axis is the beat; the yaw sweep is the recovery stroke that
            // stops it looking like a hinge.
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

        corners[offset] = px * side;
        corners[offset + 1] = py;
        corners[offset + 2] = pz;
    }

    private void drawFill(int feathers) {
        int colour = fillColor.getColor();
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        int alpha = (colour >>> 24) & 0xFF;
        int tipAlpha = Math.round(alpha * TIP_FADE);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < feathers; i++) {
            int base = i * 12;
            vertex(buffer, base, red, green, blue, alpha);
            vertex(buffer, base + 3, red, green, blue, alpha);
            vertex(buffer, base + 6, red, green, blue, tipAlpha);
            vertex(buffer, base + 9, red, green, blue, tipAlpha);
        }
        tessellator.draw();
    }

    private void drawEdges(int feathers) {
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
        // One GL_LINES batch rather than a loop per feather: a line loop cannot be restarted
        // inside a draw, and one draw call per feather is what makes an outline pass expensive.
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < feathers; i++) {
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
        vertex(buffer, from, red, green, blue, fromAlpha);
        vertex(buffer, to, red, green, blue, toAlpha);
    }

    private void vertex(WorldRenderer buffer, int offset, int red, int green, int blue, int alpha) {
        buffer.pos(corners[offset], corners[offset + 1], corners[offset + 2])
                .color(red, green, blue, alpha).endVertex();
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
