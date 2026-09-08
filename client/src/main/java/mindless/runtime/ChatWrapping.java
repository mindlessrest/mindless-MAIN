package mindless.runtime;

import mindless.module.impl.render.ChatModule;
import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Word wrapping for chat, measured with the font the chat is actually drawn in.
 *
 * GuiNewChat.setChatLine wraps every incoming message with mc.fontRendererObj and stores the
 * result, so once ChatModule swaps in a wider face the stored lines are all too long: they run out
 * past the panel and off the right of the screen. Nothing downstream re-measures them, because by
 * draw time they are already committed to drawnChatLines.
 *
 * This mirrors GuiUtilRenderComponents.splitText -- same greedy fill, same break-on-last-space,
 * same carrying of the format code onto the continuation -- with the width taken from the custom
 * renderer instead. With no custom font selected it hands straight back to vanilla so the vanilla
 * path stays byte-identical.
 */
public final class ChatWrapping {

    // A wrapWidth at or below this is nonsensical (nothing could ever fit), and trimToWidth
    // would previously hand back "" forever, turning every chat line into an empty component --
    // i.e. the whole chat goes invisible. Clamp instead of trusting the caller.
    private static final int MIN_WRAP_WIDTH = 20;

    private ChatWrapping() {
    }

    public static List<IChatComponent> split(IChatComponent component, int wrapWidth,
                                             FontRenderer vanillaFont, boolean spaceAtEnd,
                                             boolean keepFormatting) {
        // Applied here rather than only at draw time: this is where a line is measured and
        // broken, so widening chat without it would draw a wider panel around text that had
        // already been wrapped to the old width.
        int configuredWidth = ChatModule.wrappingWidth(wrapWidth);
        int effectiveWidth = Math.max(MIN_WRAP_WIDTH, configuredWidth);
        MindlessFontRenderer font = ChatModule.getCustomFont();
        if (font == null) {
            return GuiUtilRenderComponents.splitText(component, wrapWidth, vanillaFont, spaceAtEnd, keepFormatting);
        }
        try {
            List<IChatComponent> result = splitWith(font, component, effectiveWidth, spaceAtEnd, keepFormatting);
            if (result == null || result.isEmpty()) {
                // Shouldn't happen anymore, but never let a broken custom measurement silently
                // swallow a message -- fall back to vanilla rather than drawing nothing.
                return GuiUtilRenderComponents.splitText(component, wrapWidth, vanillaFont, spaceAtEnd, keepFormatting);
            }
            return result;
        }
        catch (RuntimeException fallbackToVanilla) {
            return GuiUtilRenderComponents.splitText(component, wrapWidth, vanillaFont, spaceAtEnd, keepFormatting);
        }
    }

    private static List<IChatComponent> splitWith(MindlessFontRenderer font, IChatComponent component,
                                                  int wrapWidth, boolean spaceAtEnd, boolean keepFormatting) {
        int used = 0;
        IChatComponent line = new ChatComponentText("");
        List<IChatComponent> lines = new ArrayList<IChatComponent>();
        List<IChatComponent> pending = new ArrayList<IChatComponent>();
        for (IChatComponent sibling : component) {
            pending.add(sibling);
        }

        for (int i = 0; i < pending.size(); i++) {
            IChatComponent part = pending.get(i);
            String raw = part.getUnformattedTextForChat();
            boolean breakHere = false;

            int newline = raw.indexOf('\n');
            if (newline >= 0) {
                ChatComponentText rest = new ChatComponentText(raw.substring(newline + 1));
                rest.setChatStyle(part.getChatStyle().createShallowCopy());
                pending.add(i + 1, rest);
                raw = raw.substring(0, newline + 1);
                breakHere = true;
            }

            String styled = stripColoursIfDisabled(part.getChatStyle().getFormattingCode() + raw, keepFormatting);
            String text = styled.endsWith("\n") ? styled.substring(0, styled.length() - 1) : styled;
            int width = font.getStringWidth(text);

            ChatComponentText piece = new ChatComponentText(text);
            piece.setChatStyle(part.getChatStyle().createShallowCopy());

            if (used + width > wrapWidth) {
                String head = trimToWidth(font, styled, wrapWidth - used);
                String tail = head.length() < styled.length() ? styled.substring(head.length()) : null;

                if (tail != null && tail.length() > 0) {
                    int lastSpace = head.lastIndexOf(' ');
                    if (lastSpace >= 0 && font.getStringWidth(styled.substring(0, lastSpace)) > 0) {
                        head = styled.substring(0, lastSpace);
                        tail = styled.substring(spaceAtEnd ? lastSpace + 1 : lastSpace);
                    }
                    else if (used > 0 && !styled.contains(" ")) {
                        head = "";
                        tail = styled;
                    }

                    // If, after all that, head is still empty while used == 0, nothing was
                    // consumed at all -- the tail we're about to carry over is identical to what
                    // we started with, so the next pass would hit this exact branch again and
                    // loop forever producing blank lines. Force at least one real character
                    // through so every pass makes progress and the message stays visible.
                    if (head.isEmpty() && used == 0 && !tail.isEmpty()) {
                        int forced = firstCharacterLength(tail);
                        head = tail.substring(0, forced);
                        tail = tail.substring(forced);
                    }

                    if (!tail.isEmpty()) {
                        ChatComponentText carried = new ChatComponentText(FontRenderer.getFormatFromString(head) + tail);
                        carried.setChatStyle(part.getChatStyle().createShallowCopy());
                        pending.add(i + 1, carried);
                    }
                }

                width = font.getStringWidth(head);
                piece = new ChatComponentText(head);
                piece.setChatStyle(part.getChatStyle().createShallowCopy());
                breakHere = true;
            }

            if (used + width <= wrapWidth) {
                used += width;
                line.appendSibling(piece);
            }
            else {
                breakHere = true;
            }

            if (breakHere) {
                lines.add(line);
                used = 0;
                line = new ChatComponentText("");
            }
        }

        lines.add(line);
        return lines;
    }

    /**
     * The custom-font equivalent of FontRenderer.trimStringToWidth: the longest prefix that still
     * fits, with a section sign and the code after it treated as zero width and never split apart.
     */
    private static String trimToWidth(MindlessFontRenderer font, String text, int maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) {
            return "";
        }

        StringBuilder kept = new StringBuilder(text.length());
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                kept.append(c).append(text.charAt(i + 1));
                i++;
                continue;
            }
            width += font.getStringWidth(String.valueOf(c));
            if (width > maxWidth) {
                break;
            }
            kept.append(c);
        }
        return kept.toString();
    }

    /**
     * Length in characters of the first "real" character of text, treating a leading formatting
     * code (§ + code char) as part of the same unit so we never split one off on its own.
     */
    private static int firstCharacterLength(String text) {
        if (text.length() >= 2 && text.charAt(0) == '§') {
            return Math.min(text.length(), 3);
        }
        return Math.min(text.length(), 1);
    }

    private static String stripColoursIfDisabled(String text, boolean keepFormatting) {
        Minecraft mc = Minecraft.getMinecraft();
        if (keepFormatting || mc.gameSettings == null || mc.gameSettings.chatColours) {
            return text;
        }
        return EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }
}