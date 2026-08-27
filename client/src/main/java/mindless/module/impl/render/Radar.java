package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public class Radar extends Module {
    private static final float BASE_SIZE = 100.0f;
    private static final float DEFAULT_POS_X = 5.0f;
    private static final float DEFAULT_POS_Y = 70.0f;
    private static final float CORNER_RADIUS = 8.0f;
    private static final int BACKGROUND_COLOR = new Color(0, 0, 0, 125).getRGB();
    private static final int OUTLINE_COLOR = Color.WHITE.getRGB();

    private final SliderSetting range;
    private final SliderSetting radarScale;
    private final ButtonSetting tracerLines;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    public Radar() {
        super("Radar", category.render);
        this.registerSetting(range = new SliderSetting("Range", " block", 20, 8, 256, 1));
        this.registerSetting(radarScale = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.1));
        this.registerSetting(tracerLines = new ButtonSetting("Show tracer lines", false));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new HudEditor.Screen())));
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        renderRadar(false);
    }

    public SliderSetting scaleSetting() {
        return radarScale;
    }

    public float getPosX() {
        syncPositionToResolution();
        return posX;
    }

    public float getPosY() {
        syncPositionToResolution();
        return posY;
    }

    public float getRelativePosX() {
        syncPositionToResolution();
        return relativePosX;
    }

    public float getRelativePosY() {
        syncPositionToResolution();
        return relativePosY;
    }

    public void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPositionToResolution();
    }

    public void setAbsolutePosition(float absoluteX, float absoluteY) {
        setAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    public void resetPosition() {
        setAbsolutePosition(DEFAULT_POS_X, DEFAULT_POS_Y);
    }

    private void renderRadar(boolean editing) {
        ScaledResolution resolution = new ScaledResolution(mc);
        syncPositionToResolution(resolution);

        float size = getRadarSize();
        float centerX = posX + size / 2.0f;
        float centerY = posY + size / 2.0f;
        float visualScale = getRadarScale();

        RenderUtils.drawRoundedGradientOutlinedRectangle(
                posX,
                posY,
                posX + size,
                posY + size,
                CORNER_RADIUS * visualScale,
                BACKGROUND_COLOR,
                OUTLINE_COLOR,
                OUTLINE_COLOR
        );

        RenderUtils.drawPolygon(centerX, centerY + 2.0f * visualScale, 5.0f * visualScale, 3, Color.WHITE.getRGB());

        double selectedRange = range.getInput();
        double selectedRangeSquared = selectedRange * selectedRange;
        double usableRadius = Math.max(1.0, size / 2.0f - 5.0f * visualScale);

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.deathTime != 0 || AntiBot.isBot(player)) {
                continue;
            }

            double deltaX = player.posX - mc.thePlayer.posX;
            double deltaZ = player.posZ - mc.thePlayer.posZ;
            double horizontalDistanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (horizontalDistanceSquared > selectedRangeSquared) {
                continue;
            }

            double playerAngle = mc.thePlayer.rotationYaw + Math.toDegrees(Math.atan2(deltaX, deltaZ));
            double radarDistance = Math.sqrt(horizontalDistanceSquared) / selectedRange * usableRadius;
            double xOffset = radarDistance * Math.sin(Math.toRadians(playerAngle));
            double yOffset = radarDistance * Math.cos(Math.toRadians(playerAngle));
            double markerX = centerX - xOffset;
            double markerY = centerY - yOffset;

            if (tracerLines.isToggled()) {
                drawTracer(centerX, centerY, markerX, markerY);
            }
            RenderUtils.drawPolygon(markerX, markerY, 3.0f * visualScale, 4, Color.RED.getRGB());
        }
    }

    private void drawTracer(double startX, double startY, double endX, double endY) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(0.5f);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2d(startX, startY);
        GL11.glVertex2d(endX, endY);
        GL11.glEnd();
        RenderUtils.popAttrib();
        GL11.glPopMatrix();
    }

    private float getRadarScale() {
        return (float) radarScale.getInput();
    }

    private float getRadarSize() {
        return BASE_SIZE * getRadarScale();
    }

    private void syncPositionToResolution() {
        syncPositionToResolution(new ScaledResolution(mc));
    }

    private void syncPositionToResolution(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());

        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            if (Float.isNaN(posX) || Float.isNaN(posY)) {
                relativePosX = DEFAULT_POS_X / scaledWidth;
                relativePosY = DEFAULT_POS_Y / scaledHeight;
            }
            else {
                relativePosX = posX / scaledWidth;
                relativePosY = posY / scaledHeight;
            }
        }

        posX = relativePosX * scaledWidth;
        posY = relativePosY * scaledHeight;
        clampPosition(resolution);
    }

    private void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        posX = absoluteX;
        posY = absoluteY;
        clampPosition(resolution);
        updateRelativePosition(resolution);
    }

    private void clampPosition(ScaledResolution resolution) {
        float maxX = Math.max(0.0f, resolution.getScaledWidth() - getRadarSize());
        float maxY = Math.max(0.0f, resolution.getScaledHeight() - getRadarSize());
        posX = Math.max(0.0f, Math.min(maxX, posX));
        posY = Math.max(0.0f, Math.min(maxY, posY));
    }

    private void updateRelativePosition(ScaledResolution resolution) {
        relativePosX = posX / Math.max(1, resolution.getScaledWidth());
        relativePosY = posY / Math.max(1, resolution.getScaledHeight());
    }

    public float[] renderPreview() {
        renderRadar(true);
        float size = getRadarSize();
        return new float[] { posX, posY, posX + size, posY + size };
    }

    public float[] renderDesignerPreview(float left, float top) {
        setAbsolutePosition(left, top, new ScaledResolution(mc));
        renderRadar(true);
        float size = getRadarSize();
        return new float[] { posX, posY, posX + size, posY + size };
    }
}
