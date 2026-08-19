package mindless.runtime;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.MathHelper;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class GuiNewChatState {
    public static final long RAVEN_MESSAGE_ANIMATION_MS = 320L;
    public static final float PANEL_RADIUS = 5.0f;
    public static final float PANEL_SHADOW_SOFTNESS = 5.0f;
    public static final int PANEL_SHADOW_COLOR = 0x72000000;
    public static final int PANEL_FILL_COLOR = 0x42000000;
    public static final float PANEL_BLUR_OPACITY = 0.60f;

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
        return MathHelper.clamp_double((now - birth) / (double) RAVEN_MESSAGE_ANIMATION_MS, 0.0, 1.0);
    }

    public static double easeOutCubic(double progress) {
        double remaining = 1.0 - progress;
        return 1.0 - remaining * remaining * remaining;
    }

    public static void drawGlass(float x, float y, float w, float h, boolean includeInput,
                                   int screenWidth, int screenHeight) {
        if (w <= 0.0f || h <= 0.0f) return;

        // Stencil pass: which pixels receive the blur
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, w, h, PANEL_RADIUS, 0xFF000000);
        if (includeInput) {
            RoundedUtils.drawRound(3.0f, screenHeight - 15.0f,
                    screenWidth - 6.0f, 13.0f, PANEL_RADIUS, 0xFF000000);
        }
        // Region composite — only blends the chat area, not the full screen
        float bx = x - 2, by = y - 2, bw = w + 4;
        float bh = h + 4;
        if (includeInput) {
            float inputBottom = screenHeight - 15.0f + 13.0f + 2.0f;
            bh = Math.max(bh, inputBottom - by);
        }
        BlurUtils.blurEndRegion(1, 1.4f, PANEL_BLUR_OPACITY, bx, by, bw, bh);

        drawSurface(x, y, w, h);
        if (includeInput) {
            drawSurface(3.0f, screenHeight - 15.0f, screenWidth - 6.0f, 13.0f);
        }
    }

    /** Draws the translucent glass surface and soft glow without a hard outline. */
    public static void drawSurface(float x, float y, float w, float h) {
        if (w <= 0.0f || h <= 0.0f) return;
        RoundedUtils.drawRoundShadow(x, y, w, h, PANEL_RADIUS,
                PANEL_SHADOW_SOFTNESS, PANEL_SHADOW_COLOR);
        RoundedUtils.drawLiquidGlass(x, y, w, h, PANEL_RADIUS, PANEL_FILL_COLOR);
    }
}
