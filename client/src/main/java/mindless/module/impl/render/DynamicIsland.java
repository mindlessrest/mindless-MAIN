package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.render.HUD;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class DynamicIsland extends Module {
    private static final float RADIUS = 16.0f;
    private static final float PADDING_H = 12.0f;
    private static final float PADDING_V = 6.0f;
    private static final float DIVIDER_MARGIN = 10.0f;
    private static final int FILL_COLOR = 0x55000000;

    public DynamicIsland() {
        super("Dynamic Island", category.render);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;

        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        String clientName = "mindless";
        String serverInfo = getServerInfo();
        String pingStr = getPingStr();

        float dividerW = 1.0f;
        float sectionGap = DIVIDER_MARGIN * 2 + dividerW;

        float clientW = font.getStringWidth(clientName);
        float serverW = font.getStringWidth(serverInfo);
        float pingW = font.getStringWidth(pingStr);

        float totalW = PADDING_H + clientW + sectionGap + serverW + sectionGap + pingW + PADDING_H;
        float fontH = font.getFontHeight();
        float totalH = PADDING_V * 2 + fontH;

        ScaledResolution sr = ScaledResolutionCache.get();
        float x = (sr.getScaledWidth() - totalW) * 0.5f;
        float y = 5.0f;

        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, totalW, totalH, RADIUS, 0xFF000000);
        BlurUtils.blurEnd(3, 3.0f, 0.85f);
        RoundedUtils.drawRound(x, y, totalW, totalH, RADIUS, FILL_COLOR);

        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        float textY = y + PADDING_V;
        float cx = x + PADDING_H;

        font.drawString(clientName, cx, textY, 0xFFFFFFFF, true);
        cx += clientW + DIVIDER_MARGIN;

        RoundedUtils.drawRound(cx, y + 6.0f, dividerW, totalH - 12.0f, 0.5f, 0x44FFFFFF);
        cx += dividerW + DIVIDER_MARGIN;

        font.drawString(serverInfo, cx, textY, 0xFFAAAAAA, false);
        cx += serverW + DIVIDER_MARGIN;

        RoundedUtils.drawRound(cx, y + 6.0f, dividerW, totalH - 12.0f, 0.5f, 0x44FFFFFF);
        cx += dividerW + DIVIDER_MARGIN;

        font.drawString(pingStr, cx, textY, 0xFFAAAAAA, false);
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
}
