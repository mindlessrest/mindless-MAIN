package mindless.module.impl.render;

import mindless.hud.HudModule;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.MindlessAccount;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;

public final class Watermark extends HudModule {
    private static final String SEPARATOR = "  |  ";
    private final SliderSetting font;
    private final SliderSetting scale;
    private final ButtonSetting account;
    private final ButtonSetting fps;
    private final ButtonSetting server;
    private final ButtonSetting clock;
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm");
    private float posX = 5.0f;
    private float posY = 5.0f;
    private String cachedValue = "Mindless";
    private long lastValueUpdate;

    public Watermark() {
        super("Watermark", "Persistent Mindless identity and game information.", category.render);
        registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));
        registerSetting(scale = new SliderSetting("Scale", 1.0, 0.7, 1.8, 0.05));
        registerSetting(account = new ButtonSetting("Account", true));
        registerSetting(fps = new ButtonSetting("FPS", true));
        registerSetting(server = new ButtonSetting("Server", true));
        registerSetting(clock = new ButtonSetting("Clock", false));
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        renderWatermark();
    }

    public float[] renderWatermark() {
        MindlessFontRenderer renderer = renderer();
        if (renderer == null) return null;
        String value = value();
        float uiScale = (float) scale.getInput();
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(posX, posY, 0.0f);
        net.minecraft.client.renderer.GlStateManager.scale(uiScale, uiScale, 1.0f);
        renderer.drawGlyphString(value, 0.0f, 0.0f,
                (character, offset, width, formattingColor) -> ThemeManager.getWatermarkColor(offset * 0.1), false);
        net.minecraft.client.renderer.GlStateManager.popMatrix();
        return new float[]{posX, posY, posX + renderer.getStringWidth(value) * uiScale,
                posY + renderer.getFontHeight() * uiScale};
    }

    @Override
    public float[] render() {
        return renderWatermark();
    }

    private MindlessFontRenderer renderer() {
        return FontManager.getHudRenderer(ModuleFont.nameOf(font), 1.0f);
    }

    private String value() {
        long now = System.currentTimeMillis();
        if (now - lastValueUpdate < 250L) return cachedValue;
        StringBuilder value = new StringBuilder("Mindless");
        if (account.isToggled()) value.append(SEPARATOR).append(MindlessAccount.displayName());
        if (fps.isToggled()) value.append(SEPARATOR).append(net.minecraft.client.Minecraft.getDebugFPS()).append(" fps");
        if (server.isToggled()) {
            ServerData data = mc.getCurrentServerData();
            value.append(SEPARATOR).append(mc.isSingleplayer() ? "Singleplayer"
                    : data == null || data.serverIP == null || data.serverIP.trim().isEmpty() ? "Multiplayer" : data.serverIP);
        }
        if (clock.isToggled()) value.append(SEPARATOR).append(clockFormat.format(new Date(now)));
        cachedValue = value.toString();
        lastValueUpdate = now;
        return cachedValue;
    }

    public void setPosition(float left, float top) {
        posX = left;
        posY = top;
    }

    public void resetPosition() {
        posX = 5.0f;
        posY = 5.0f;
    }

    @Override
    public void resetHudItem() {
        resetPosition();
    }

    @Override
    public SliderSetting getHudItemScale() {
        return scale;
    }
}
