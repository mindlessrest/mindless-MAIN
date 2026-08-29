package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.Gui;
import mindless.module.impl.render.HUD;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.HudGlowHelper;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.InputStream;

public class DynamicIsland extends Module {
    private static final float RADIUS = 10.0f;
    private static final float PADDING_H = 10.0f;
    private static final float PADDING_V = 5.0f;
    private static final float DIVIDER_MARGIN = 8.0f;
    private static final int FILL_COLOR = 0x55000000;
    private static final float LOGO_SIZE = 12.0f;
    private static final float LOGO_GAP = 5.0f;

    private static final String[] MODES = new String[] { "Island", "Text" };
    private static final float DEFAULT_TEXT_X = 5.0f;
    private static final float DEFAULT_TEXT_Y = 5.0f;

    private final SliderSetting mode;
    private ResourceLocation logoTexture;
    private boolean textureLoaded;

    public float textPosX = DEFAULT_TEXT_X;
    public float textPosY = DEFAULT_TEXT_Y;

    private SliderSetting font;

    public DynamicIsland() {
        super("Watermark", category.render);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(font = new SliderSetting("Font", 0, mindless.utility.font.ModuleFont.options()));
    }

    public void resetPosition() {
        textPosX = DEFAULT_TEXT_X;
        textPosY = DEFAULT_TEXT_Y;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;

        if ((int) mode.getInput() == 1) {
            renderTextWatermark();
        } else {
            renderIsland();
        }
    }

    private static final float WATERMARK_SCALE = 3.0f;

    private mindless.utility.font.RavenFontRenderer getWatermarkFont() {
        return mindless.utility.font.FontManager.getHudRenderer(
                mindless.utility.font.ModuleFont.nameOf(font),
                HUD.getSelectedFontScale() * WATERMARK_SCALE);
    }

    private void renderTextWatermark() {
        mindless.utility.font.RavenFontRenderer font = getWatermarkFont();
        if (font == null) return;

        String text = "Mindless";
        float x = textPosX;
        float y = textPosY;

        int baseColor = mindless.module.impl.theme.ThemeManager.getWatermarkColor(0.0);
        int r = (baseColor >> 16) & 0xFF;
        int g = (baseColor >> 8) & 0xFF;
        int b = baseColor & 0xFF;

        if (mindless.utility.shader.HudGlowHelper.isAvailable()) {
            mindless.utility.shader.HudGlowHelper.beginMask();
            font.drawGlyphString(text, x, y, (character, xOffset, width, formattingColor) -> {
                return mindless.module.impl.theme.ThemeManager.getWatermarkColor(xOffset * 0.1);
            }, false);
            mindless.utility.shader.HudGlowHelper.endAndComposite(8.0f, 1.2f, r, g, b);
        }

        font.drawGlyphString(text, x, y, (character, xOffset, width, formattingColor) -> {
            return mindless.module.impl.theme.ThemeManager.getWatermarkColor(xOffset * 0.1);
        }, false);
    }

    public float[] getTextBounds() {
        mindless.utility.font.RavenFontRenderer font = getWatermarkFont();
        if (font == null) return null;
        String text = "Mindless";
        float w = font.getStringWidth(text);
        float h = font.getFontHeight();
        return new float[] { textPosX, textPosY, textPosX + w, textPosY + h };
    }

    private void renderIsland() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;
        ensureTexture();

        String clientName = "mindless";
        String serverInfo = getServerInfo();
        String pingStr = getPingStr();

        float dividerW = 1.0f;
        float sectionGap = DIVIDER_MARGIN * 2 + dividerW;

        float clientW = font.getStringWidth(clientName);
        float serverW = font.getStringWidth(serverInfo);
        float pingW = font.getStringWidth(pingStr);

        float logoSection = logoTexture != null ? LOGO_SIZE + LOGO_GAP : 0.0f;
        float totalW = PADDING_H + logoSection + clientW + sectionGap + serverW + sectionGap + pingW + PADDING_H;
        float fontH = font.getFontHeight();
        float totalH = PADDING_V * 2 + fontH;

        ScaledResolution sr = ScaledResolutionCache.get();
        float x = (sr.getScaledWidth() - totalW) * 0.5f;
        float y = 4.0f;

        float radius = RADIUS * ThemeManager.roundingScale();
        BlurUtils.prepareBlur(x, y, totalW, totalH);
        RoundedUtils.drawRound(x, y, totalW, totalH, radius, 0xFF000000);
        BlurUtils.blurEndRegion(3, 3.0f, 0.85f, x - 2.0f, y - 2.0f, totalW + 4.0f, totalH + 4.0f);
        RoundedUtils.drawRound(x, y, totalW, totalH, radius, FILL_COLOR);

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        float textY = y + PADDING_V;
        float cx = x + PADDING_H;

        int accentColor = Gui.themeColor != null ? (0xFF000000 | Gui.themeColor.getRGB()) : HUD.getHudColor(0.0);
        if (logoTexture != null) {
            float r = ((accentColor >> 16) & 0xFF) / 255.0f;
            float g = ((accentColor >> 8) & 0xFF) / 255.0f;
            float b = (accentColor & 0xFF) / 255.0f;
            GlStateManager.color(r, g, b, 1.0f);
            mc.getTextureManager().bindTexture(logoTexture);
            float logoY = y + (totalH - LOGO_SIZE) * 0.5f;
            net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(
                    (int) cx, (int) logoY, 0, 0, (int) LOGO_SIZE, (int) LOGO_SIZE, LOGO_SIZE, LOGO_SIZE);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            cx += LOGO_SIZE + LOGO_GAP;
        }

        font.drawString(clientName, cx, textY, accentColor, true);
        cx += clientW + DIVIDER_MARGIN;

        RoundedUtils.drawRound(cx, y + 5.0f, dividerW, totalH - 10.0f, 0.5f, 0x44FFFFFF);
        cx += dividerW + DIVIDER_MARGIN;

        font.drawString(serverInfo, cx, textY, 0xFFCCCCCC, false);
        cx += serverW + DIVIDER_MARGIN;

        RoundedUtils.drawRound(cx, y + 5.0f, dividerW, totalH - 10.0f, 0.5f, 0x44FFFFFF);
        cx += dividerW + DIVIDER_MARGIN;

        font.drawString(pingStr, cx, textY, 0xFFCCCCCC, false);
    }

    private String getServerInfo() {
        if (mc.isSingleplayer()) return "singleplayer";
        ServerData server = mc.getCurrentServerData();
        if (server == null) return "unknown";
        return server.serverIP;
    }

    private String getPingStr() {
        if (mc.isSingleplayer()) return "0 ms";
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) return "? ms";
        NetworkPlayerInfo info = handler.getPlayerInfo(mc.thePlayer.getUniqueID());
        if (info == null) return "? ms";
        return info.getResponseTime() + " ms";
    }

    private void ensureTexture() {
        if (textureLoaded) return;
        textureLoaded = true;
        try (InputStream is = Minecraft.class.getResourceAsStream("/assets/mindless/textures/gui/logo.png")) {
            if (is == null) return;
            logoTexture = mc.getTextureManager().getDynamicTextureLocation(
                    "dynamic_island_logo", new DynamicTexture(TextureUtil.readBufferedImage(is)));
        } catch (Exception ignored) {}
    }

}
