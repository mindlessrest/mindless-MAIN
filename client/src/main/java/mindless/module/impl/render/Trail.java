package mindless.module.impl.render;

import mindless.effect.EffectRenderer;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A line of where you have been.
 *
 * Positions are sampled once a tick and interpolated between, rather than sampled per frame. A
 * frame-sampled trail is denser at high frame rates and sparser at low ones, so the same settings
 * look different on two machines and the length setting stops meaning anything.
 *
 * This does not use the shared effect system. An effect is a thing that is spawned, ages and
 * expires; a trail is a continuously rewritten strip that belongs to an entity, and forcing it
 * through that shape would mean respawning an effect every tick to throw it away on the next.
 */
public class Trail extends Module {

    private static final String[] MODES = new String[]{"Ribbon", "Orbs", "Both"};
    private static final int MODE_RIBBON = 0;
    private static final int MODE_ORBS = 1;

    private static final String[] COLOR_MODES = new String[]{"Static", "Rainbow"};

    /** Beyond this the oldest points are dropped whatever the length setting says. */
    private static final int HARD_CAP = 160;

    private final SliderSetting mode;
    private final SliderSetting length;
    private final SliderSetting width;
    private final SliderSetting height;
    private final SliderSetting orbSize;
    private final SliderSetting colorMode;
    private final ColorSetting color;
    private final SliderSetting opacity;
    private final ButtonSetting others;
    private final ButtonSetting throughWalls;

    private final Map<Integer, List<double[]>> trails = new HashMap<Integer, List<double[]>>();

    public Trail() {
        super("Trail", "Leaves a visible trail behind you as you move.", category.render, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_RIBBON, MODES));
        this.registerSetting(length = new SliderSetting("Length", "ticks", 28, 4, 120, 1));
        this.registerSetting(width = new SliderSetting("Width", "blocks", 0.22, 0.02, 1.0, 0.02));
        this.registerSetting(height = new SliderSetting("Height", "blocks", 0.9, 0.0, 2.0, 0.05));
        this.registerSetting(orbSize = new SliderSetting("Orb size", "blocks", 0.06, 0.01, 0.3, 0.01));
        this.registerSetting(colorMode = new SliderSetting("Color mode", 1, COLOR_MODES));
        this.registerSetting(color = new ColorSetting("Color", 255, 79, 163, 255));
        this.registerSetting(opacity = new SliderSetting("Opacity", "%", 70, 5, 100, 5));
        this.registerSetting(others = new ButtonSetting("Other players", false));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", true));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        int selected = (int) mode.getInput();
        if (width != null) {
            width.setVisible(selected != MODE_ORBS, this);
        }
        if (orbSize != null) {
            orbSize.setVisible(selected != MODE_RIBBON, this);
        }
        if (color != null) {
            color.setVisible(colorMode != null && (int) colorMode.getInput() == 0, this);
        }
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()].toLowerCase();
    }

    @Override
    public void onDisable() {
        trails.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            trails.clear();
            return;
        }

        int keep = Math.min(HARD_CAP, (int) length.getInput());
        sample(mc.thePlayer, keep);

        if (others.isToggled()) {
            for (int i = 0; i < mc.theWorld.playerEntities.size(); i++) {
                EntityPlayer player = mc.theWorld.playerEntities.get(i);
                if (player != mc.thePlayer) {
                    sample(player, keep);
                }
            }
        }

        // A player who walked out of range or logged off keeps a stale trail forever otherwise.
        Iterator<Map.Entry<Integer, List<double[]>>> iterator = trails.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, List<double[]>> entry = iterator.next();
            if (mc.theWorld.getEntityByID(entry.getKey()) == null) {
                iterator.remove();
            }
        }
    }

    private void sample(EntityPlayer player, int keep) {
        if (player == null || player.isDead) {
            return;
        }
        if (player != mc.thePlayer && !(player instanceof EntityPlayerSP) && player.isInvisible()) {
            return;
        }

        List<double[]> points = trails.get(player.getEntityId());
        if (points == null) {
            points = new ArrayList<double[]>();
            trails.put(player.getEntityId(), points);
        }

        double footY = player.getEntityBoundingBox().minY;
        points.add(new double[]{player.posX, footY, player.posZ});
        while (points.size() > keep) {
            points.remove(0);
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || trails.isEmpty()) {
            return;
        }

        RenderManager rm = mc.getRenderManager();
        int selected = (int) mode.getInput();
        float alpha = (float) (opacity.getInput() / 100.0);
        int staticRgb = color.getRGB() & 0xFFFFFF;

        OpenGlHelper.glUseProgram(0);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls.isToggled()) {
            GlStateManager.disableDepth();
        }
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.translate(-rm.viewerPosX, -rm.viewerPosY, -rm.viewerPosZ);

        for (List<double[]> points : trails.values()) {
            if (points.size() < 2) {
                continue;
            }
            if (selected != MODE_ORBS) {
                drawRibbon(points, staticRgb, alpha);
            }
            if (selected != MODE_RIBBON) {
                drawOrbs(points, staticRgb, alpha);
            }
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    /**
     * A flat strip standing up from the ground along the path.
     *
     * Each segment is widened along the normal of its own direction rather than a fixed axis, so
     * a corner keeps its thickness instead of collapsing to a line when the path runs parallel to
     * whichever axis was picked.
     */
    private void drawRibbon(List<double[]> points, int staticRgb, float alpha) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        double half = width.getInput() * 0.5;
        double top = height.getInput();

        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double dx = b[0] - a[0], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-4) {
                continue;
            }
            double nx = -dz / len * half, nz = dx / len * half;

            float fadeA = (float) (i - 1) / points.size();
            float fadeB = (float) i / points.size();
            int colorA = colorAt(i - 1, points.size(), staticRgb);
            int colorB = colorAt(i, points.size(), staticRgb);

            quad(wr, a, b, nx, nz, top, colorA, colorB, fadeA * alpha, fadeB * alpha);
        }
        tessellator.draw();
    }

    private void quad(WorldRenderer wr, double[] a, double[] b, double nx, double nz, double top,
                      int colorA, int colorB, float alphaA, float alphaB) {
        int ra = (colorA >> 16) & 0xFF, ga = (colorA >> 8) & 0xFF, ba = colorA & 0xFF;
        int rb = (colorB >> 16) & 0xFF, gb = (colorB >> 8) & 0xFF, bb = colorB & 0xFF;
        int aa = Math.round(Math.max(0.0f, Math.min(1.0f, alphaA)) * 255.0f);
        int ab = Math.round(Math.max(0.0f, Math.min(1.0f, alphaB)) * 255.0f);
        if (aa <= 0 && ab <= 0) {
            return;
        }

        // The top edge fades out on its own so the strip has a soft crown rather than a cut line
        // hanging in the air.
        wr.pos(a[0] - nx, a[1], a[2] - nz).color(ra, ga, ba, aa).endVertex();
        wr.pos(b[0] - nx, b[1], b[2] - nz).color(rb, gb, bb, ab).endVertex();
        wr.pos(b[0] - nx, b[1] + top, b[2] - nz).color(rb, gb, bb, 0).endVertex();

        wr.pos(a[0] - nx, a[1], a[2] - nz).color(ra, ga, ba, aa).endVertex();
        wr.pos(b[0] - nx, b[1] + top, b[2] - nz).color(rb, gb, bb, 0).endVertex();
        wr.pos(a[0] - nx, a[1] + top, a[2] - nz).color(ra, ga, ba, 0).endVertex();

        wr.pos(a[0] + nx, a[1], a[2] + nz).color(ra, ga, ba, aa).endVertex();
        wr.pos(b[0] + nx, b[1], b[2] + nz).color(rb, gb, bb, ab).endVertex();
        wr.pos(b[0] + nx, b[1] + top, b[2] + nz).color(rb, gb, bb, 0).endVertex();

        wr.pos(a[0] + nx, a[1], a[2] + nz).color(ra, ga, ba, aa).endVertex();
        wr.pos(b[0] + nx, b[1] + top, b[2] + nz).color(rb, gb, bb, 0).endVertex();
        wr.pos(a[0] + nx, a[1] + top, a[2] + nz).color(ra, ga, ba, 0).endVertex();
    }

    private void drawOrbs(List<double[]> points, int staticRgb, float alpha) {
        WorldRenderer wr = EffectRenderer.beginSparks();
        double size = orbSize.getInput();
        double lift = height.getInput() * 0.5;
        for (int i = 0; i < points.size(); i++) {
            double[] point = points.get(i);
            float fade = (float) i / points.size();
            EffectRenderer.spark(wr, point[0], point[1] + lift, point[2],
                    size * (0.35 + fade * 0.65), colorAt(i, points.size(), staticRgb), fade * alpha);
        }
        EffectRenderer.endSparks();
    }

    private int colorAt(int index, int total, int staticRgb) {
        if ((int) colorMode.getInput() == 0) {
            return staticRgb;
        }
        float hue = (System.currentTimeMillis() % 4000L) / 4000.0f + (float) index / total * 0.5f;
        return java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) & 0xFFFFFF;
    }
}
