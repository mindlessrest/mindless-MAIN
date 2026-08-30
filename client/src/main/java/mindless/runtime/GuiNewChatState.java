package mindless.runtime;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.MathHelper;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class GuiNewChatState {
    public static final long MINDLESS_MESSAGE_ANIMATION_MS = 320L;
    private static final float BASE_PANEL_RADIUS = 8.0f;

    public static float panelRadius() {
        return BASE_PANEL_RADIUS * mindless.module.impl.theme.ThemeManager.roundingScale();
    }

    /** The chat panel's own radius, which the Chat module may override. */
    public static float chatPanelRadius() {
        float configured = mindless.module.impl.render.ChatModule.cornerRadius();
        return configured < 0.0f ? panelRadius()
                : configured * mindless.module.impl.theme.ThemeManager.roundingScale();
    }

    /**
     * Who sent a chat line, or null when it did not come from a player.
     *
     * <p>Read from the insertion the server attaches to the sender's part of the message -- the
     * text that gets put in the input box when the name is shift-clicked. That is set for player
     * messages and nothing else, which makes it a better answer than pattern-matching the text:
     * a message that merely mentions a name is not from them, and the format varies per server
     * while the insertion does not.
     */
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

    /**
     * The sender's face, drawn at the start of their line.
     *
     * <p>Both skin layers, because a hat is most of what makes a face recognisable at eight
     * pixels. Nothing is drawn for a name the client has never seen -- an offline player, or a
     * message from the server itself -- rather than a placeholder, so the column stays quiet.
     */
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
        // Eight-pixel faces on a sixty-four pixel sheet: the head at (8,8) and the hat over it.
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                (int) x, (int) y, 8.0f, 8.0f, 8, 8, (int) size, (int) size, 64.0f, 64.0f);
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                (int) x, (int) y, 40.0f, 8.0f, 8, 8, (int) size, (int) size, 64.0f, 64.0f);
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }
    public static final int PANEL_FILL_COLOR = 0x55000000;
    public static final float PANEL_BLUR_OPACITY = 0.85f;

    public static final Map<Object, Long> messageBirths = new IdentityHashMap<Object, Long>();
    public static long lastAnimationCleanup;

    private GuiNewChatState() {}

    public static void updateMessageAnimations(List<ChatLine> drawnChatLines, long now) {
        for (ChatLine line : drawnChatLines) {
            if (line != null && !messageBirths.containsKey(line)) {
                messageBirths.put(line, now);
            }
        }

        if (now - lastAnimationCleanup >= 1000L) {
            messageBirths.keySet().retainAll(drawnChatLines);
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

        // Only the part of the shared mask this panel writes into needs wiping, so the clear is a
        // rectangle rather than the whole screen. It has to cover the input box as well as the
        // chat body -- both are drawn into the mask below, and the input box reaches further
        // across than the chat does.
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
