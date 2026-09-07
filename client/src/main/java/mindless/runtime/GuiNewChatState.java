package mindless.runtime;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.MathHelper;

import java.util.IdentityHashMap;
import java.util.Set;
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
/**
     * Resolve the tab-list entry for a chat sender.
     *
     * getPlayerInfo(String) matches the game profile name exactly and case-sensitively. A sender
     * pulled out of a Hypixel line can differ from that by case, so an exact miss is retried
     * against the player list before giving up.
     */
    private static net.minecraft.client.network.NetworkPlayerInfo resolvePlayer(String name) {
        net.minecraft.client.network.NetHandlerPlayClient handler =
                net.minecraft.client.Minecraft.getMinecraft().getNetHandler();
        if (handler == null || name == null || name.isEmpty()) {
            return null;
        }
        net.minecraft.client.network.NetworkPlayerInfo direct = handler.getPlayerInfo(name);
        if (direct != null) {
            return direct;
        }
        for (net.minecraft.client.network.NetworkPlayerInfo info : handler.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String profileName = info.getGameProfile().getName();
            if (profileName != null && profileName.equalsIgnoreCase(name)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Last resort sender extraction, for lines whose components carry no click event or insertion.
     *
     * Only accepts a leading "name:" or "rank name:" shape, and only a token that is a legal
     * Minecraft username, so ordinary sentences containing a colon are not mistaken for a sender.
     */
    /**
     * Sender guessed from the rendered text, for lines carrying no click event or insertion.
     *
     * Servers separate the name from the body with any of ":", "\u00bb" or ">", so all three are
     * tried and the earliest wins. Everything before it is taken, and the last token of that is
     * the candidate, which drops rank prefixes like "[MVP+] name".
     *
     * The result is only a candidate. It is deliberately not trusted on its own: "lunarclient:
     * v2.22" and "[ACTest] \u00bb ..." both produce something that looks like a username, which is
     * how server lines ended up wearing player heads. The caller confirms it against the tab
     * list, and that is what separates a real sender from a coincidence.
     */
    private static String senderFromText(String formatted) {
        if (formatted == null) {
            return null;
        }
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null || plain.isEmpty()) {
            return null;
        }

        int cut = -1;
        char[] separators = { ':', '\u00bb', '>' };
        for (int i = 0; i < separators.length; i++) {
            int at = plain.indexOf(separators[i]);
            if (at > 0 && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        if (cut <= 0 || cut > 48) {
            return null;
        }

        String head = plain.substring(0, cut).trim();
        int space = head.lastIndexOf(' ');
        String candidate = (space < 0 ? head : head.substring(space + 1)).trim();
        if (candidate.length() < 3 || candidate.length() > 16) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c != '_' && !Character.isLetterOrDigit(c)) {
                return null;
            }
        }
        return candidate;
    }

    /**
     * The player who sent a chat line, or null when it was not sent by one.
     *
     * Component metadata first, because it is authoritative, then the text as a guess. Either way
     * the answer has to correspond to somebody on the tab list, which is what keeps server
     * announcements from being attributed to a player.
     */
    public static String resolvedSenderOf(net.minecraft.util.IChatComponent component) {
        if (component == null) {
            return null;
        }
        String fromMetadata = senderOf(component);
        if (fromMetadata != null && resolvePlayer(fromMetadata) != null) {
            return fromMetadata;
        }
        String guessed = senderFromText(component.getFormattedText());
        net.minecraft.client.network.NetworkPlayerInfo info = resolvePlayer(guessed);
        return info == null ? null : info.getGameProfile().getName();
    }

    public static void drawPlayerHead(String name, float x, float y, float size, int alpha) {
        drawPlayerHead(name, null, x, y, size, alpha);
    }

    public static void drawPlayerHead(String name, String formattedLine,
                                      float x, float y, float size, int alpha) {
        if (size <= 0.0f) {
            return;
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) {
            return;
        }

        String sender = name;
        if (sender == null || sender.isEmpty() || resolvePlayer(sender) == null) {
            sender = senderFromText(formattedLine);
        }
        net.minecraft.client.network.NetworkPlayerInfo info = resolvePlayer(sender);
        if (info == null) {
            // Not a player line. Server announcements produce username-shaped tokens, so drawing
            // a fallback head on a miss put heads on every one of them.
            return;
        }

        net.minecraft.util.ResourceLocation skin = info.getLocationSkin();
        if (skin == null) {
            skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
        }

        // Own state, not inherited. The chat panel behind this is drawn with texturing off, so a
        // bind alone had nothing to sample and the head never appeared.
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(
                org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ZERO);
        net.minecraft.client.renderer.GlStateManager.enableAlpha();
        net.minecraft.client.renderer.GlStateManager.disableLighting();
        net.minecraft.client.renderer.GlStateManager.disableDepth();
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f,
                Math.max(0, Math.min(255, alpha)) / 255.0f);
        mc.getTextureManager().bindTexture(skin);

        int px = Math.round(x);
        int py = Math.round(y);
        int drawSize = Math.max(1, Math.round(size));
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                px, py, 8.0f, 8.0f, 8, 8, drawSize, drawSize, 64.0f, 64.0f);
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                px, py, 40.0f, 8.0f, 8, 8, drawSize, drawSize, 64.0f, 64.0f);
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static final int PANEL_FILL_COLOR = 0x55000000;
    public static final float PANEL_BLUR_OPACITY = 0.85f;

    public static final Map<ChatLine, Long> messageBirths = new IdentityHashMap<ChatLine, Long>();
    private static final Set<ChatLine> liveLines =
            java.util.Collections.newSetFromMap(new IdentityHashMap<ChatLine, Boolean>());
    public static long lastAnimationCleanup;

    private GuiNewChatState() {}

    public static void updateMessageAnimations(List<ChatLine> drawnChatLines, long now) {
        // Indexed, so a line added mid-iteration cannot throw, and no copy of the list per frame.
        for (int i = 0; i < drawnChatLines.size(); i++) {
            ChatLine line = drawnChatLines.get(i);
            if (line != null && !messageBirths.containsKey(line)) {
                messageBirths.put(line, now);
            }
        }

        if (now - lastAnimationCleanup < 1000L) {
            return;
        }
        lastAnimationCleanup = now;
        if (messageBirths.size() <= drawnChatLines.size()) {
            return;
        }

        // retainAll against the list itself was a linear scan per tracked line. Against an identity
        // set it is a lookup.
        liveLines.clear();
        for (int i = 0; i < drawnChatLines.size(); i++) {
            ChatLine line = drawnChatLines.get(i);
            if (line != null) {
                liveLines.add(line);
            }
        }
        messageBirths.keySet().retainAll(liveLines);
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

    /**
     * True when drawGlass already laid the input bar down this frame.
     *
     * drawGlass draws the input bar as part of the chat panel so the two share one blur pass. The
     * GuiChat overlay drew the same rect again with a second full-screen blur behind it, which both
     * stacked two translucent bars at the same place and doubled the panel's cost for every frame
     * the chat was open.
     */
    public static boolean inputSurfaceDrawn() {
        return inputSurfaceFrame == BlurUtils.getFrameSerial();
    }

    private static long inputSurfaceFrame = Long.MIN_VALUE;

    public static void drawGlass(float x, float y, float w, float h, boolean includeInput,
                                   int screenWidth, int screenHeight) {
        if (w <= 0.0f || h <= 0.0f) return;
        if (includeInput) {
            inputSurfaceFrame = BlurUtils.getFrameSerial();
        }
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
        if (includeInput) {
            BlurUtils.prepareBlur(maskLeft, maskTop, maskRight - maskLeft, maskBottom - maskTop);
            RoundedUtils.drawRound(x, y, w, h, chatPanelRadius(), 0xFF000000);
            RoundedUtils.drawRound(3.0f, screenHeight - 15.0f,
                    screenWidth - 6.0f, 13.0f, chatPanelRadius(), 0xFF000000);
            float bx = x - 2, by = y - 2, bw = w + 4;
            float bh = h + 4;
            float inputBottom = screenHeight - 15.0f + 13.0f + 2.0f;
            bh = Math.max(bh, inputBottom - by);
            BlurUtils.blurEndRegion(2, 2.4f, mindless.module.impl.render.ChatModule.backgroundOpacity(), bx, by, bw, bh);
        }

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
