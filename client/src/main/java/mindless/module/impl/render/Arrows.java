package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;

/**
 * Off-screen pointers for the players around you.
 *
 * The old version drew one hardcoded red or green caret on a fixed circle. This one lets the ring
 * itself carry information: the pointer can sit further out the further away its target is, fade
 * as it goes, take its colour from distance or from the target's health, and ride the screen edge
 * instead of a circle when a rectangle suits the screen better.
 */
public class Arrows extends Module {
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();

    private static final String[] SHAPES = {"Caret", "Chevron", "Triangle", "Needle", "Diamond"};
    private static final int SHAPE_CARET = 0;
    private static final int SHAPE_CHEVRON = 1;
    private static final int SHAPE_TRIANGLE = 2;
    private static final int SHAPE_NEEDLE = 3;
    private static final int SHAPE_DIAMOND = 4;

    private static final String[] LAYOUTS = {"Ring", "Screen edge"};
    private static final int LAYOUT_RING = 0;

    private static final String[] COLOR_MODES = {"Team", "Distance", "Health", "Manual"};
    private static final int COLOR_TEAM = 0;
    private static final int COLOR_DISTANCE = 1;
    private static final int COLOR_HEALTH = 2;
    private static final int COLOR_MANUAL = 3;

    private final SliderSetting shape;
    private final SliderSetting shapeScale;
    private final SliderSetting thickness;
    private final ButtonSetting filled;
    private final SliderSetting opacity;

    private final SliderSetting layout;
    private final SliderSetting radius;
    private final ButtonSetting scaleRadius;
    private final SliderSetting farRadius;
    private final SliderSetting deadZone;
    private final SliderSetting edgeInset;

    private final SliderSetting colorMode;
    private final ColorSetting nearColor;
    private final ColorSetting farColor;
    private final SliderSetting fadeStart;
    private final SliderSetting fadeEnd;
    private final ColorSetting friendColor;
    private final ColorSetting enemyColor;
    private final ColorSetting neutralColor;
    private final ButtonSetting fadeWithDistance;

    private final SliderSetting range;
    private final SliderSetting ignoreFov;
    private final ButtonSetting hideTeammates;
    private final ButtonSetting renderFriends;
    private final ButtonSetting renderEnemies;
    private final ButtonSetting renderOnlyOffScreen;

    private final ButtonSetting renderDistance;
    private final ButtonSetting renderName;
    private final SliderSetting font;
    private final SliderSetting labelScale;
    private final SliderSetting labelOffset;

    private final ArrayList<ArrowRenderState> renderStates = new ArrayList<>();
    private int renderStateCount = 0;

    public Arrows() {
        super("Arrows", "Arrows around your crosshair point at players.", category.render);

        GroupSetting pointer = new GroupSetting("Pointer");
        registerSetting(pointer);
        registerSetting(shape = new SliderSetting(pointer, "Shape", SHAPE_CARET, SHAPES));
        registerSetting(shapeScale = new SliderSetting(pointer, "Scale", "x", 1.0, 0.4, 3.0, 0.05));
        registerSetting(thickness = new SliderSetting(pointer, "Thickness", 3.0, 1.0, 8.0, 0.5));
        registerSetting(filled = new ButtonSetting(pointer, "Filled", true));
        registerSetting(opacity = new SliderSetting(pointer, "Opacity", 255, 40, 255, 5));

        GroupSetting placement = new GroupSetting("Placement");
        registerSetting(placement);
        registerSetting(layout = new SliderSetting(placement, "Layout", LAYOUT_RING, LAYOUTS));
        registerSetting(radius = new SliderSetting(placement, "Radius", 50, 20, 250, 5));
        registerSetting(scaleRadius = new ButtonSetting(placement, "Radius by distance", false));
        registerSetting(farRadius = new SliderSetting(placement, "Far radius", 110, 20, 300, 5));
        registerSetting(deadZone = new SliderSetting(placement, "Dead zone", 15, 0, 120, 1));
        registerSetting(edgeInset = new SliderSetting(placement, "Edge inset", 18, 4, 80, 1));

        GroupSetting colors = new GroupSetting("Colors");
        registerSetting(colors);
        registerSetting(colorMode = new SliderSetting(colors, "Color mode", COLOR_TEAM, COLOR_MODES));
        registerSetting(nearColor = new ColorSetting(colors, "Near color", 255, 82, 82));
        registerSetting(farColor = new ColorSetting(colors, "Far color", 96, 165, 250));
        registerSetting(fadeStart = new SliderSetting(colors, "Fade from", " block", 10, 0, 200, 1));
        registerSetting(fadeEnd = new SliderSetting(colors, "Fade to", " block", 90, 5, 300, 5));
        registerSetting(friendColor = new ColorSetting(colors, "Friend color", 85, 255, 85));
        registerSetting(enemyColor = new ColorSetting(colors, "Enemy color", 255, 85, 85));
        registerSetting(neutralColor = new ColorSetting(colors, "Other color", 235, 235, 235));
        registerSetting(fadeWithDistance = new ButtonSetting(colors, "Fade out with distance", false));

        GroupSetting targets = new GroupSetting("Targets");
        registerSetting(targets);
        registerSetting(range = new SliderSetting(targets, "Range", " block", 200, 25, 300, 5));
        registerSetting(ignoreFov = new SliderSetting(targets, "Ignore within FOV", "°", 0, 0, 180, 5));
        registerSetting(hideTeammates = new ButtonSetting(targets, "Hide teammates", true));
        registerSetting(renderFriends = new ButtonSetting(targets, "Show friends", true));
        registerSetting(renderEnemies = new ButtonSetting(targets, "Show enemies", true));
        registerSetting(renderOnlyOffScreen = new ButtonSetting(targets, "Only offscreen", false));

        GroupSetting label = new GroupSetting("Label");
        registerSetting(label);
        registerSetting(renderDistance = new ButtonSetting(label, "Show distance", true));
        registerSetting(renderName = new ButtonSetting(label, "Show name", false));
        registerSetting(font = new SliderSetting(label, "Font", 0, FONT_OPTIONS));
        registerSetting(labelScale = new SliderSetting(label, "Label scale", "x", 0.8, 0.4, 2.0, 0.05));
        registerSetting(labelOffset = new SliderSetting(label, "Label offset", 13, 0, 40, 1));
    }

    @Override
    public void guiUpdate() {
        int mode = (int) colorMode.getInput();
        boolean gradient = mode == COLOR_DISTANCE || mode == COLOR_HEALTH;
        boolean ring = (int) layout.getInput() == LAYOUT_RING;
        int form = (int) shape.getInput();

        nearColor.setVisible(gradient, this);
        farColor.setVisible(gradient, this);
        fadeStart.setVisible(mode == COLOR_DISTANCE || fadeWithDistance.isToggled() || scaleRadius.isToggled(), this);
        fadeEnd.setVisible(mode == COLOR_DISTANCE || fadeWithDistance.isToggled() || scaleRadius.isToggled(), this);
        friendColor.setVisible(mode == COLOR_MANUAL || mode == COLOR_TEAM, this);
        enemyColor.setVisible(mode == COLOR_MANUAL || mode == COLOR_TEAM, this);
        neutralColor.setVisible(mode == COLOR_MANUAL, this);

        radius.setVisible(ring, this);
        scaleRadius.setVisible(ring, this);
        farRadius.setVisible(ring && scaleRadius.isToggled(), this);
        deadZone.setVisible(ring, this);
        edgeInset.setVisible(!ring, this);

        filled.setVisible(form == SHAPE_TRIANGLE || form == SHAPE_DIAMOND, this);
        thickness.setVisible(form == SHAPE_CARET || form == SHAPE_NEEDLE
                || ((form == SHAPE_TRIANGLE || form == SHAPE_DIAMOND) && !filled.isToggled()), this);

        boolean label = renderDistance.isToggled() || renderName.isToggled();
        font.setVisible(label || form == SHAPE_CHEVRON, this);
        labelScale.setVisible(label, this);
        labelOffset.setVisible(label, this);
    }

    @Override
    public void onDisable() {
        renderStates.clear();
        renderStateCount = 0;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        updateRenderStates();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (mc.currentScreen != null || !Utils.nullCheck()) {
            return;
        }
        try {
            for (int i = 0; i < renderStateCount; i++) {
                ArrowRenderState renderState = renderStates.get(i);
                if (renderState.player == null) {
                    continue;
                }
                this.renderIndicatorFor(renderState.player, renderState.color, event.renderTickTime);
            }
        }
        catch (Exception e) {}
    }

    private void updateRenderStates() {
        renderStateCount = 0;
        if (!Utils.nullCheck() || mc.theWorld == null) {
            renderStates.clear();
            return;
        }

        for (EntityPlayer en : mc.theWorld.playerEntities) {
            if (en == null || en == mc.thePlayer) {
                continue;
            }
            if (AntiBot.isBot(en)) {
                continue;
            }
            if (Utils.isTeammate(en) && hideTeammates.isToggled()) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceToEntity(en);
            if (distance > range.getInput()) {
                continue;
            }
            if (!renderFriends.isToggled() && Utils.isFriended(en)) {
                continue;
            }
            if (!renderEnemies.isToggled() && Utils.isEnemy(en) && !Utils.isFriended(en)) {
                continue;
            }
            // A player you are already looking at needs no pointer. The threshold is the whole
            // cone, so 60 means "anything within 30 degrees either side of where I am looking".
            double fovLimit = ignoreFov.getInput();
            if (fovLimit > 0 && angleTo(en) <= fovLimit * 0.5) {
                continue;
            }

            if (renderStateCount >= renderStates.size()) {
                renderStates.add(new ArrowRenderState());
            }
            renderStates.get(renderStateCount++).set(en, colorFor(en, distance));
        }
    }

    /** Degrees between where the camera is pointed and the direction to the target. */
    private double angleTo(EntityPlayer en) {
        double dx = en.posX - mc.thePlayer.posX;
        double dy = (en.posY + en.getEyeHeight()) - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = en.posZ - mc.thePlayer.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float wantedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantedPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float yawDelta = MathHelper.wrapAngleTo180_float(wantedYaw - mc.thePlayer.rotationYaw);
        float pitchDelta = wantedPitch - mc.thePlayer.rotationPitch;
        return Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
    }

    private int colorFor(EntityPlayer en, double distance) {
        switch ((int) colorMode.getInput()) {
            case COLOR_DISTANCE:
                return lerpColor(nearColor.getRGB(), farColor.getRGB(), fade(distance));
            case COLOR_HEALTH: {
                float max = Math.max(1.0F, en.getMaxHealth());
                float health = Math.max(0.0F, Math.min(max, en.getHealth()));
                // Full health sits at the far colour, a dying player at the near one.
                return lerpColor(nearColor.getRGB(), farColor.getRGB(), health / max);
            }
            case COLOR_MANUAL:
                if (Utils.isFriended(en)) return friendColor.getRGB();
                if (Utils.isEnemy(en)) return enemyColor.getRGB();
                return neutralColor.getRGB();
            case COLOR_TEAM:
            default:
                if (Utils.isFriended(en)) return friendColor.getRGB();
                if (Utils.isEnemy(en)) return enemyColor.getRGB();
                int team = Utils.getColorFromEntity(en);
                return team == -1 ? neutralColor.getRGB() : team & 0xFFFFFF;
        }
    }

    /** 0 at "Fade from", 1 at "Fade to", clamped either side. */
    private float fade(double distance) {
        double from = fadeStart.getInput();
        double to = Math.max(from + 1.0, fadeEnd.getInput());
        return (float) Math.max(0.0, Math.min(1.0, (distance - from) / (to - from)));
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private void renderIndicatorFor(EntityPlayer en, int color, float partialTicks) {
        if (renderOnlyOffScreen.isToggled() && RenderUtils.isInViewFrustum(en)) {
            return;
        }

        double x = en.lastTickPosX + (en.posX - en.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
        double y = en.lastTickPosY + (en.posY - en.lastTickPosY) * partialTicks - mc.getRenderManager().viewerPosY + en.height / 2;
        double z = en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;

        AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, AccessorBridge.Minecraft_getTimer(mc).renderPartialTicks, 0);

        ScaledResolution scaledResolution = ScaledResolutionCache.get();
        Vec3 vec = RenderUtils.convertTo2D(scaledResolution.getScaleFactor(), x, y, z);
        if (vec == null) {
            return;
        }

        mc.entityRenderer.setupOverlayRendering();
        ScaledResolution res = scaledResolution;

        double dx = vec.xCoord - res.getScaledWidth() / 2.0;
        double dy = vec.yCoord - res.getScaledHeight() / 2.0;
        boolean inFrustum = vec.zCoord < 1.0003684;

        if (!inFrustum) {
            dx *= -1.0;
            dy *= -1.0;
        }

        double angle1 = Math.atan2(dx, dy);
        double angle2 = Math.atan2(dy, dx) * 57.295780181884766 + 90.0;
        double hypotenuse = Math.hypot(dx, dy);

        double baseX = res.getScaledWidth() / 2.0;
        double baseY = res.getScaledHeight() / 2.0;
        double sinAng = Math.sin(angle1);
        double cosAng = Math.cos(angle1);

        double distance = mc.thePlayer.getDistanceToEntity(en);
        double placementRadius;
        double renderX;
        double renderY;
        double labelPull = labelOffset.getInput();

        if ((int) layout.getInput() == LAYOUT_RING) {
            placementRadius = radius.getInput();
            if (scaleRadius.isToggled()) {
                double far = farRadius.getInput();
                placementRadius = placementRadius + (far - placementRadius) * fade(distance);
            }
            if (inFrustum && hypotenuse < placementRadius + deadZone.getInput()) {
                return;
            }
            renderX = baseX + placementRadius * sinAng;
            renderY = baseY + placementRadius * cosAng;
        }
        else {
            // Screen edge: walk the direction vector out until it meets the inset rectangle, so a
            // pointer sits where the target actually leaves the screen rather than on a circle
            // that ignores the aspect ratio.
            double inset = edgeInset.getInput();
            double halfW = Math.max(1.0, baseX - inset);
            double halfH = Math.max(1.0, baseY - inset);
            double absSin = Math.abs(sinAng);
            double absCos = Math.abs(cosAng);
            double scaleToEdge = Math.min(absSin < 1e-4 ? Double.MAX_VALUE : halfW / absSin,
                    absCos < 1e-4 ? Double.MAX_VALUE : halfH / absCos);
            if (inFrustum && hypotenuse < scaleToEdge) {
                return;
            }
            placementRadius = scaleToEdge;
            renderX = baseX + scaleToEdge * sinAng;
            renderY = baseY + scaleToEdge * cosAng;
        }

        int alpha = (int) opacity.getInput();
        if (fadeWithDistance.isToggled()) {
            alpha = (int) (alpha * (1.0f - 0.75f * fade(distance)));
        }
        alpha = Math.max(8, Math.min(255, alpha));
        int rgba = (alpha << 24) | (color & 0xFFFFFF);

        GlStateManager.pushMatrix();
        GlStateManager.translate(renderX, renderY, 0.0);
        GlStateManager.rotate((float) angle2, 0.0f, 0.0f, 1.0f);
        float form = (float) shapeScale.getInput();
        GlStateManager.scale(form, form, 1.0f);
        drawShape((int) shape.getInput(), rgba);
        GlStateManager.popMatrix();

        if (!renderDistance.isToggled() && !renderName.isToggled()) {
            return;
        }

        renderX = baseX + (placementRadius - labelPull) * sinAng;
        renderY = baseY + (placementRadius - labelPull) * cosAng;

        float labelSize = (float) labelScale.getInput();
        GlStateManager.pushMatrix();
        GlStateManager.translate(renderX, renderY, 0.0);
        GlStateManager.scale(labelSize, labelSize, 1.0f);

        MindlessFontRenderer fr = getArrowFontRenderer();
        float lineY = -4.0f;
        if (renderName.isToggled()) {
            String name = en.getName();
            fr.drawString(name, (float) (-fr.getStringWidth(name) / 2), lineY, 0xFF000000 | color, true);
            lineY += fr.getFontHeight();
        }
        if (renderDistance.isToggled()) {
            String text = (int) distance + "m";
            fr.drawString(text, (float) (-fr.getStringWidth(text) / 2), lineY, -1, true);
        }

        GlStateManager.popMatrix();
    }

    private void drawShape(int form, int rgba) {
        float red = ((rgba >> 16) & 0xFF) / 255.0F;
        float green = ((rgba >> 8) & 0xFF) / 255.0F;
        float blue = (rgba & 0xFF) / 255.0F;
        float alpha = ((rgba >>> 24) & 0xFF) / 255.0F;

        if (form == SHAPE_CHEVRON) {
            GlStateManager.rotate(-90.0f, 0.0f, 0.0f, 1.0f);
            GlStateManager.scale(1.5, 1.5, 1.5);
            getArrowFontRenderer().drawString(">", -2.0f, -4.0f, rgba, false);
            return;
        }
        if (form == SHAPE_TRIANGLE && filled.isToggled()) {
            RenderUtils.draw2DPolygon(0.0, 0.0, 5.0, 3, rgba);
            return;
        }

        GlStateManager.color(red, green, blue, alpha);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth((float) thickness.getInput());

        switch (form) {
            case SHAPE_NEEDLE: {
                // A long thin spike: reads clearly at small scales where a caret turns to mush.
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2d(-4.0, 2.0);
                GL11.glVertex2d(0.0, -9.0);
                GL11.glVertex2d(4.0, 2.0);
                GL11.glVertex2d(0.0, -1.0);
                GL11.glVertex2d(-4.0, 2.0);
                GL11.glEnd();
                break;
            }
            case SHAPE_DIAMOND: {
                if (filled.isToggled()) {
                    GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                    GL11.glVertex2d(0.0, -2.0);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.0, -2.0);
                    GL11.glVertex2d(0.0, 4.0);
                    GL11.glVertex2d(-5.0, -2.0);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glEnd();
                }
                else {
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(0.0, -8.0);
                    GL11.glVertex2d(5.0, -2.0);
                    GL11.glVertex2d(0.0, 4.0);
                    GL11.glVertex2d(-5.0, -2.0);
                    GL11.glEnd();
                }
                break;
            }
            case SHAPE_TRIANGLE: {
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2d(0.0, -7.0);
                GL11.glVertex2d(5.0, 3.0);
                GL11.glVertex2d(-5.0, 3.0);
                GL11.glEnd();
                break;
            }
            case SHAPE_CARET:
            default: {
                double halfAngle = 0.6108652353286743;
                double size = 9.0;
                double offsetY = 5.0;
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2d(Math.sin(-halfAngle) * size, Math.cos(-halfAngle) * size - offsetY);
                GL11.glVertex2d(0.0, -offsetY);
                GL11.glVertex2d(Math.sin(halfAngle) * size, Math.cos(halfAngle) * size - offsetY);
                GL11.glEnd();
                break;
            }
        }

        GL11.glLineWidth(1.0f);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private String getSelectedFontName() {
        if (font == null) {
            return FONT_OPTIONS[0];
        }
        int index = (int) Math.max(0, Math.min(font.getOptions().length - 1, font.getInput()));
        return font.getOptions()[index];
    }

    private MindlessFontRenderer getArrowFontRenderer() {
        return FontManager.getNametagRenderer(getSelectedFontName());
    }

    private static final class ArrowRenderState {
        private EntityPlayer player;
        private int color;

        private void set(EntityPlayer player, int color) {
            this.player = player;
            this.color = color;
        }
    }
}
