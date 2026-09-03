package mindless.utility.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;

/**
 * A {@link FontRenderer} that measures with a custom font.
 *
 * Chat wrapping is done by GuiUtilRenderComponents.splitText, which takes a FontRenderer and uses
 * exactly two things from it: getStringWidth and trimStringToWidth. Vanilla hands it
 * mc.fontRendererObj, so lines are broken to fit the default glyph widths and then drawn with
 * whatever font the chat is actually set to -- wider glyphs then overrun the chat box. Handing
 * splitText this instead breaks the lines against the font that will draw them.
 *
 * Nothing here renders. The superclass is only present to satisfy the parameter type.
 */
public final class ChatWrapFontRenderer extends FontRenderer {

    private static final char SECTION_SIGN = '§';
    private static ChatWrapFontRenderer instance;

    private MindlessFontRenderer delegate;

    private ChatWrapFontRenderer(Minecraft mc) {
        super(mc.gameSettings, new ResourceLocation("textures/font/ascii.png"),
                mc.getTextureManager(), false);
    }

    public static FontRenderer measuring(MindlessFontRenderer font) {
        Minecraft mc = Minecraft.getMinecraft();
        if (font == null) {
            return mc.fontRendererObj;
        }
        if (instance == null) {
            try {
                instance = new ChatWrapFontRenderer(mc);
            } catch (Throwable unavailable) {
                return mc.fontRendererObj;
            }
        }
        instance.delegate = font;
        return instance;
    }

    @Override
    public int getStringWidth(String text) {
        if (delegate == null) {
            return Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);
        }
        return text == null || text.isEmpty() ? 0 : delegate.getStringWidth(text);
    }

    @Override
    public String trimStringToWidth(String text, int width) {
        return trimStringToWidth(text, width, false);
    }

    @Override
    public String trimStringToWidth(String text, int width, boolean reverse) {
        if (delegate == null) {
            return Minecraft.getMinecraft().fontRendererObj.trimStringToWidth(text, width, reverse);
        }
        return trim(delegate, text, width, reverse);
    }

    /**
     * Vanilla's trim rules measured with a custom font. Format codes cost nothing and bold costs
     * one pixel per character, same as FontRenderer.
     */
    public static String trim(MindlessFontRenderer delegate, String text, int width, boolean reverse) {
        if (delegate == null || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }

        StringBuilder trimmed = new StringBuilder();
        int used = 0;
        int start = reverse ? text.length() - 1 : 0;
        int step = reverse ? -1 : 1;
        boolean readingFormatCode = false;
        boolean bold = false;

        for (int i = start; i >= 0 && i < text.length() && used < width; i += step) {
            char character = text.charAt(i);

            if (readingFormatCode) {
                readingFormatCode = false;
                if (character == 'l' || character == 'L') {
                    bold = true;
                }
                else if (character == 'r' || character == 'R') {
                    bold = false;
                }
            }
            else if (character == SECTION_SIGN) {
                readingFormatCode = true;
            }
            else {
                used += delegate.getStringWidth(String.valueOf(character));
                if (bold) {
                    used++;
                }
            }

            if (used > width) {
                break;
            }

            if (reverse) {
                trimmed.insert(0, character);
            }
            else {
                trimmed.append(character);
            }
        }

        return trimmed.toString();
    }
}
