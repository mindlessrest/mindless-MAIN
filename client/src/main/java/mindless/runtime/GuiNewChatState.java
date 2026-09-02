package mindless.runtime;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.MathHelper;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class GuiNewChatState {
    public static final long MINDLESS_MESSAGE_ANIMATION_MS = 320L;
    private static final float BASE_PANEL_RADIUS = 8.0f;

    public static float panelRadius() {
        return BASE_PANEL_RADIUS * mindless.module.impl.theme.ThemeManager.roundingScale();
    }
public static float chatPanelRadius() {
        float configured = mindless.module.impl.render.ChatModule.cornerRadius();
        return configured < 0.0f ? panelRadius()
                : configured * mindless.module.impl.theme.ThemeManager.roundingScale();
    }
public static String senderOf(net.minecraft.util.IChatComponent component) {
        if (component == null) {
            return null;
        }

        net.minecraft.event.ClickEvent click = component.getChatStyle() == null ? null
                : component.getChatStyle().getChatClickEvent();
        if (click != null && click.getAction() == net.minecraft.event.ClickEvent.Action.SUGGEST_COMMAND) {
            String value = click.getValue();
            if (value != null && value.startsWith("/msg ")) {
                String name = value.substring(5).trim();
                int space = name.indexOf(' ');
                if (space > 0) {
                    name = name.substring(0, space);
                }
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }

        String insertion = component.getChatStyle() == null ? null : component.getChatStyle().getInsertion();
        if (insertion != null && !insertion.isEmpty()) {
            return insertion;
        }

        for (Object sibling : component.getSiblings()) {
            String found = senderOf((net.minecraft.util.IChatComponent) sibling);
            if (found != null) {
                return found;
            }
        }

        return null;
    }
public static void drawPlayerHead(String name, float x, float y, float size, int alpha) {
        if (name == null || name.isEmpty() || size <= 0.0f) {
            return;
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) {
            return;
        }

        net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(name);
        if (info == null || info.getLocationSkin() == null) {
            return;
        }

        mc.getTextureManager().bindTexture(info.getLocationSkin());
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f,
                Math.max(0, Math.min(255, alpha)) / 255.0f);
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                (int) x, (int) y, 8.0f, 8.0f, 8, 8, (int) size, (int) size, 64.0f, 64.0f);
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                (int) x, (int) y, 40.0f, 8.0f, 8, 8, (int) size, (int) size, 64.0f, 64.0f);
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }
    public static final int PANEL_FILL_COLOR = 0x55000000;
    public static final float PANEL_BLUR_OPACITY = 0.85f;

    public static final Map<ChatLine, Long> messageBirths = new IdentityHashMap<ChatLine, Long>();
    public static long lastAnimationCleanup;

    private GuiNewChatState() {}

    public static void updateMessageAnimations(List<ChatLine> drawnChatLines, long now) {
        List<ChatLine> snapshot = new java.util.ArrayList<ChatLine>(drawnChatLines);

        for (ChatLine line : snapshot) {
            if (line != null && !messageBirths.containsKey(line)) {
                messageBirths.put(line, now);
            }
        }

        if (now - lastAnimationCleanup >= 1000L) {
            Iterator<ChatLine> iterator = messageBirths.keySet().iterator();

            while (iterator.hasNext()) {
                Object key = iterator.next();

                if (!snapshot.contains(key)) {
                    iterator.remove();
                }
            }

            lastAnimationCleanup = now;
        }
    }

    public static double getAnimationProgress(ChatLine line, long now) {
        Long birth = messageBirths.get(line);
        if (birth == null) return 1.0;
        return MathHelper.clamp_double((now - birth) / (double) MINDLESS_MESSAGE_ANIMATION_MS, 0.0, 1.0);
    }

    public static double easeOutCubic(double progress) {
        double remaining = 1.0 - progress;
        return 1.0 - remaining * remaining * remaining;
    }

    public static void drawGlass(float x, float y, float w, float h, boolean includeInput,
                                   int screenWidth, int screenHeight) {
        if (w <= 0.0f || h <= 0.0f) return;
        float maskLeft = x - 2.0f;
        float maskTop = y - 2.0f;
        float maskRight = x + w + 2.0f;
        float maskBottom = y + h + 2.0f;
        if (includeInput) {
            maskLeft = Math.min(maskLeft, 1.0f);
            maskTop = Math.min(maskTop, screenHeight - 17.0f);
            maskRight = Math.max(maskRight, screenWidth - 1.0f);
            maskBottom = Math.max(maskBottom, screenHeight);
        }
        BlurUtils.prepareBlur(maskLeft, maskTop, maskRight - maskLeft, maskBottom - maskTop);

        RoundedUtils.drawRound(x, y, w, h, chatPanelRadius(), 0xFF000000);
        if (includeInput) {
            RoundedUtils.drawRound(3.0f, screenHeight - 15.0f,
                    screenWidth - 6.0f, 13.0f, chatPanelRadius(), 0xFF000000);
        }
        float bx = x - 2, by = y - 2, bw = w + 4;
        float bh = h + 4;
        if (includeInput) {
            float inputBottom = screenHeight - 15.0f + 13.0f + 2.0f;
            bh = Math.max(bh, inputBottom - by);
        }
        BlurUtils.blurEndRegion(2, 2.4f, mindless.module.impl.render.ChatModule.backgroundOpacity(), bx, by, bw, bh);

        RoundedUtils.drawRound(x, y, w, h, chatPanelRadius(), PANEL_FILL_COLOR);
        if (includeInput) {
            RoundedUtils.drawRound(3.0f, screenHeight - 15.0f,
                    screenWidth - 6.0f, 13.0f, chatPanelRadius(), PANEL_FILL_COLOR);
        }
    }

    public static void drawSurface(float x, float y, float w, float h) {
        if (w <= 0.0f || h <= 0.0f) return;
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, w, h, panelRadius(), 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, PANEL_BLUR_OPACITY, x - 2, y - 2, w + 4, h + 4);
        RoundedUtils.drawRound(x, y, w, h, panelRadius(), PANEL_FILL_COLOR);
    }
}
