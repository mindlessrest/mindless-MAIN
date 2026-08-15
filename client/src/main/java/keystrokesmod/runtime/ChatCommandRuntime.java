package keystrokesmod.runtime;

import keystrokesmod.Raven;
import keystrokesmod.clickgui.animation.ScrollOffsetAnimation;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.shader.BlurUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Map;
import java.util.WeakHashMap;

/** Schema-safe command completion/preview state for native GuiChat transforms. */
public final class ChatCommandRuntime {
    private static final int MAX_PREVIEW_ROWS = 6;
    private static final Map<GuiChat, State> STATES = new WeakHashMap<>();

    private ChatCommandRuntime() {}

    private static State state(GuiChat chat) {
        synchronized (STATES) {
            State state = STATES.get(chat);
            if (state == null) {
                state = new State();
                STATES.put(chat, state);
            }
            return state;
        }
    }

    public static void onKeyTyped(GuiChat chat, int keyCode) {
        if (keyCode != Keyboard.KEY_TAB) resetCompletions(state(chat));
    }

    public static String nextCompletion(GuiChat chat, String input) {
        if (Raven.commandManager == null || !Raven.commandManager.isCommand(input)) {
            resetCompletions(state(chat));
            return null;
        }
        State state = state(chat);
        String[] suggestions;
        if (canCycle(state, input)) {
            suggestions = state.completions;
            state.completionIndex = (state.completionIndex + 1) % suggestions.length;
        } else {
            suggestions = Raven.commandManager.getAutoComplete(input);
            if (suggestions.length == 0) {
                resetCompletions(state);
                return null;
            }
            state.completions = suggestions;
            state.completionIndex = 0;
        }
        return suggestions[state.completionIndex];
    }

    public static boolean handleMouseInput(GuiChat chat, String input, int width, int height) {
        if (Raven.commandManager == null) return false;
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return false;
        State state = state(chat);
        if (!Raven.commandManager.isCommand(input)) {
            resetPreview(state);
            return false;
        }
        String[] suggestions = Raven.commandManager.getPreviewSuggestions(input);
        int rows = Math.min(MAX_PREVIEW_ROWS, suggestions.length);
        if (rows == 0 || suggestions.length <= rows) {
            syncPreview(state, input, suggestions.length, rows);
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!isOverPreview(mouseX, mouseY, suggestions, rows, width, height, mc)) return false;
        syncPreview(state, input, suggestions.length, rows);
        float speed = Gui.scrollSpeed == null ? 20.0F : (float) Gui.scrollSpeed.getInput();
        state.previewScroll.extend(-speed * (wheel / 120.0F));
        clampPreview(state, suggestions.length, rows, mc);
        return true;
    }

    public static void drawSuggestions(GuiChat chat, String input, int width, int height) {
        State state = state(chat);
        if (Raven.commandManager == null || !Raven.commandManager.isCommand(input)) {
            resetPreview(state);
            return;
        }
        String[] suggestions = Raven.commandManager.getPreviewSuggestions(input);
        if (suggestions.length == 0) {
            resetPreview(state);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int rows = Math.min(MAX_PREVIEW_ROWS, suggestions.length);
        syncPreview(state, input, suggestions.length, rows);
        int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
        int rowHeight = fontHeight + 2;
        int widest = mc.fontRendererObj.getStringWidth("Suggestions");
        for (String suggestion : suggestions) {
            widest = Math.max(widest, mc.fontRendererObj.getStringWidth(suggestion));
        }
        int left = 4;
        int right = Math.min(width - 4, left + widest + 10);
        int bottom = height - 16;
        int top = bottom - 6 - fontHeight - rows * rowHeight;
        int contentTop = top + 5 + fontHeight;
        int contentHeight = rows * rowHeight;
        drawPanel(left, top, right, bottom);
        RenderUtils.drawRoundedRectangle(left + 4, top + 1, right - 4, top + 2,
                0.5F, 0x30FFFFFF);
        mc.fontRendererObj.drawStringWithShadow("Suggestions", left + 4, top + 3, 0xFFE8D8FF);
        RenderUtils.scissorPushGui(left, contentTop, right - left, contentHeight);
        float offset = state.previewScroll.getValue();
        int active = activeSuggestion(input, suggestions);
        for (int i = 0; i < suggestions.length; i++) {
            int textY = Math.round(contentTop - offset + i * rowHeight);
            if (textY + fontHeight < contentTop || textY > contentTop + contentHeight) continue;
            mc.fontRendererObj.drawStringWithShadow(suggestions[i], left + 4, textY,
                    i == active ? 0xFFFFFFFF : 0xFFBFC4D6);
        }
        RenderUtils.scissorPop();
    }

    private static void drawPanel(int left, int top, int right, int bottom) {
        BlurUtils.prepareBlur();
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0F, 0xFFFFFFFF);
        BlurUtils.blurEnd(1, 1.8F);
        BlurUtils.prepareBloom();
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0F, 0xFF000000);
        BlurUtils.bloomEnd(2, 3.0F);
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0F, 0x40000000);
    }

    private static void syncPreview(State state, String input, int count, int rows) {
        if (!input.equals(state.previewInput)) {
            state.previewInput = input;
            state.previewScroll.reset(0.0F);
        }
        clampPreview(state, count, rows, Minecraft.getMinecraft());
    }

    private static void clampPreview(State state, int count, int rows, Minecraft mc) {
        state.previewScroll.clampTarget(0.0F,
                Math.max(0.0F, (count - rows) * (mc.fontRendererObj.FONT_HEIGHT + 2)));
    }

    private static boolean isOverPreview(int x, int y, String[] suggestions, int rows,
                                         int width, int height, Minecraft mc) {
        int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
        int widest = mc.fontRendererObj.getStringWidth("Suggestions");
        for (String suggestion : suggestions) widest = Math.max(widest, mc.fontRendererObj.getStringWidth(suggestion));
        int left = 4;
        int right = Math.min(width - 4, left + widest + 10);
        int bottom = height - 16;
        int top = bottom - 6 - fontHeight - rows * (fontHeight + 2);
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static int activeSuggestion(String input, String[] suggestions) {
        String lowered = input == null ? "" : input.toLowerCase();
        for (int i = 0; i < suggestions.length; i++) {
            if (lowered.endsWith(suggestions[i].toLowerCase())) return i;
        }
        return 0;
    }

    private static boolean canCycle(State state, String input) {
        if (state.completions.length == 0 || state.completionIndex < 0) return false;
        for (String suggestion : state.completions) if (suggestion.equals(input)) return true;
        return false;
    }

    private static void resetCompletions(State state) {
        state.completions = new String[0];
        state.completionIndex = -1;
    }

    private static void resetPreview(State state) {
        state.previewInput = "";
        state.previewScroll.reset(0.0F);
    }

    private static final class State {
        private String[] completions = new String[0];
        private int completionIndex = -1;
        private final ScrollOffsetAnimation previewScroll = new ScrollOffsetAnimation(200L);
        private String previewInput = "";
    }
}
