package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.MindlessAccount;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class Watermark extends Module {
    private static final float DEFAULT_X = 5.0f;
    private static final float DEFAULT_Y = 5.0f;
    private static final float HEIGHT = 20.0f;
    private static final float PAD_X = 7.0f;
    private static final float ICON_SIZE = 7.0f;
    private static final float ICON_GAP = 4.0f;
    private static final float SEGMENT_GAP = 7.0f;

    private final SliderSetting font;
    private final GroupSetting contentGroup;
    private final ButtonSetting showAccount;
    private final ButtonSetting showFps;
    private final ButtonSetting showServer;
    private final ButtonSetting showPing;
    private final GroupSetting styleGroup;
    private final ButtonSetting blurBackdrop;
    private final ButtonSetting dropShadow;
    private final SliderSetting opacity;
    private final SliderSetting scale;

    public float posX = DEFAULT_X;
    public float posY = DEFAULT_Y;

    public Watermark() {
        super("Watermark", "Shows your account, performance and server.", category.render);
        this.registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));
        this.registerSetting(contentGroup = new GroupSetting("Content"));
        this.registerSetting(showAccount = new ButtonSetting(contentGroup, "Account", true));
        this.registerSetting(showFps = new ButtonSetting(contentGroup, "FPS", true));
        this.registerSetting(showServer = new ButtonSetting(contentGroup, "Server", true));
        this.registerSetting(showPing = new ButtonSetting(contentGroup, "Ping", false));
        this.registerSetting(styleGroup = new GroupSetting("Style"));
        this.registerSetting(blurBackdrop = new ButtonSetting(styleGroup, "Blur backdrop", true));
        this.registerSetting(dropShadow = new ButtonSetting(styleGroup, "Drop shadow", true));
        this.registerSetting(opacity = new SliderSetting(
                styleGroup, "Opacity", "%", 88.0, 20.0, 100.0, 1.0));
        this.registerSetting(scale = new SliderSetting(styleGroup, "Scale", 1.0, 0.7, 1.6, 0.05));
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw();
    }

    public float[] renderPreview() {
        return draw();
    }

    public void moveTo(float left, float top) {
        posX = left;
        posY = top;
    }

    public void resetPosition() {
        posX = DEFAULT_X;
        posY = DEFAULT_Y;
    }

    private float[] draw() {
        MindlessFontRenderer text = FontManager.getHudRenderer(
                ModuleFont.nameOf(font), HUD.getSelectedFontScale());
        if (text == null) return null;
        float uiScale = (float) scale.getInput();
        float width = measure(text) * uiScale;
        float height = HEIGHT * uiScale;
        int alpha = Math.round(255.0f * (float) (opacity.getInput() / 100.0));
        float radius = height * 0.5f;

        if (dropShadow.isToggled()) {
            RoundedUtils.drawRoundShadow(posX, posY + 0.8f, width, height, radius,
                    2.0f, withAlpha(0x000000, Math.round(alpha * 0.32f)));
        }
        if (blurBackdrop.isToggled()) {
            BlurUtils.prepareBlur(posX, posY, width, height);
            RoundedUtils.drawRound(posX, posY, width, height, radius, 0xFF000000);
            BlurUtils.blurEndRegion(2, 2.2f, alpha / 255.0f, posX, posY, width, height);
        }
        RoundedUtils.drawGradientVertical(posX, posY, width, height, radius,
                new java.awt.Color(39, 38, 46, alpha),
                new java.awt.Color(26, 25, 31, alpha));
        drawSegments(text, uiScale, height, alpha);
        return new float[]{posX, posY, posX + width, posY + height};
    }

    private void drawSegments(MindlessFontRenderer text, float uiScale, float height, int alpha) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        float cursor = posX + PAD_X * uiScale;
        float textY = posY + (height - text.getFontHeight() * uiScale) * 0.5f;
        int index = 0;
        if (showAccount.isToggled()) {
            cursor = drawSegment(text, cursor, textY, uiScale, index++, 0,
                    MindlessAccount.displayName(), alpha);
        }
        if (showFps.isToggled()) {
            cursor = drawSegment(text, cursor, textY, uiScale, index++, 1,
                    Minecraft.getDebugFPS() + " fps", alpha);
        }
        if (showServer.isToggled()) {
            cursor = drawSegment(text, cursor, textY, uiScale, index++, 2,
                    serverText(), alpha);
        }
        if (showPing.isToggled()) {
            drawSegment(text, cursor, textY, uiScale, index, 3, pingText(), alpha);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float drawSegment(MindlessFontRenderer text, float x, float y, float uiScale,
                              int index, int icon, String value, int alpha) {
        if (index > 0) x += SEGMENT_GAP * uiScale;
        float iconSize = ICON_SIZE * uiScale;
        float iconY = posY + (HEIGHT * uiScale - iconSize) * 0.5f;
        drawIcon(icon, x, iconY, iconSize, withAlpha(0xB9BBC4, alpha));
        x += (ICON_SIZE + ICON_GAP) * uiScale;
        drawScaled(text, value, x, y, uiScale, withAlpha(0xD9DAE0, alpha));
        return x + text.getStringWidth(value) * uiScale;
    }

    private void drawIcon(int icon, float x, float y, float size, int colour) {
        if (icon == 0) {
            float head = size * 0.35f;
            RoundedUtils.drawRound(x + (size - head) * 0.5f, y, head, head, head * 0.5f, colour);
            RoundedUtils.drawRound(x + size * 0.16f, y + size * 0.52f,
                    size * 0.68f, size * 0.4f, size * 0.2f, colour);
            return;
        }
        if (icon == 1) {
            RoundedUtils.drawRoundOutline(x, y, size, size, size * 0.5f,
                    Math.max(0.7f, size * 0.12f),
                    new java.awt.Color(0, 0, 0, 0), toColor(colour));
            RoundedUtils.drawRound(x + size * 0.48f, y + size * 0.2f,
                    Math.max(0.7f, size * 0.11f), size * 0.33f,
                    size * 0.06f, colour);
            return;
        }
        if (icon == 2) {
            float rowHeight = size * 0.3f;
            RoundedUtils.drawRound(x, y + size * 0.08f, size, rowHeight,
                    rowHeight * 0.3f, colour);
            RoundedUtils.drawRound(x, y + size * 0.62f, size, rowHeight,
                    rowHeight * 0.3f, colour);
            return;
        }
        float bar = size * 0.18f;
        for (int i = 0; i < 3; i++) {
            float barHeight = size * (0.35f + i * 0.24f);
            RoundedUtils.drawRound(x + i * size * 0.32f, y + size - barHeight,
                    bar, barHeight, bar * 0.5f, colour);
        }
    }

    private float measure(MindlessFontRenderer text) {
        float width = PAD_X * 2.0f;
        int count = 0;
        if (showAccount.isToggled()) width = addWidth(text, width, count++, MindlessAccount.displayName());
        if (showFps.isToggled()) width = addWidth(text, width, count++, Minecraft.getDebugFPS() + " fps");
        if (showServer.isToggled()) width = addWidth(text, width, count++, serverText());
        if (showPing.isToggled()) width = addWidth(text, width, count, pingText());
        return Math.max(40.0f, width);
    }

    private float addWidth(MindlessFontRenderer text, float width, int index, String value) {
        if (index > 0) width += SEGMENT_GAP;
        return width + ICON_SIZE + ICON_GAP + text.getStringWidth(value);
    }

    private String serverText() {
        if (mc.isSingleplayer()) return "singleplayer";
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null || server.serverIP.isEmpty()) return "unknown";
        String ip = server.serverIP;
        int port = ip.indexOf(':');
        return port > 0 ? ip.substring(0, port) : ip;
    }

    private String pingText() {
        if (mc.isSingleplayer()) return "0 ms";
        if (mc.thePlayer == null || mc.getNetHandler() == null) return "-- ms";
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? "-- ms" : info.getResponseTime() + " ms";
    }

    private void drawScaled(MindlessFontRenderer text, String value, float x, float y,
                            float uiScale, int colour) {
        if (uiScale == 1.0f) {
            text.drawString(value, x, y, colour, false);
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(uiScale, uiScale, 1.0f);
        text.drawString(value, 0.0f, 0.0f, colour, false);
        GlStateManager.popMatrix();
    }

    private static java.awt.Color toColor(int argb) {
        return new java.awt.Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF,
                argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }
}
