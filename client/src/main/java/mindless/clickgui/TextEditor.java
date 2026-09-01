package mindless.clickgui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
public final class TextEditor {
    private String text = "";
    private int caret;
    private int anchor;

    public String getText() {
        return text;
    }
public void reset(String value) {
        text = value == null ? "" : value;
        caret = text.length();
        anchor = caret;
    }

    public int getCaret() {
        return caret;
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    public void moveCaret(int target, boolean extend) {
        caret = Math.max(0, Math.min(text.length(), target));
        if (!extend) anchor = caret;
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }
public boolean keyTyped(char typed, int key, int max) {
        boolean control = GuiScreen.isCtrlKeyDown();
        boolean shift = GuiScreen.isShiftKeyDown();

        if (control && key == Keyboard.KEY_A) {
            selectAll();
            return false;
        }
        if (control && key == Keyboard.KEY_C) {
            copySelection();
            return false;
        }
        if (control && key == Keyboard.KEY_X) {
            copySelection();
            return deleteSelection();
        }
        if (control && key == Keyboard.KEY_V) {
            return insert(GuiScreen.getClipboardString(), max);
        }

        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT) {
            boolean left = key == Keyboard.KEY_LEFT;
            int target;
            if (!shift && hasSelection()) target = left ? selectionStart() : selectionEnd();
            else if (left) target = control ? previousWord() : caret - 1;
            else target = control ? nextWord() : caret + 1;
            moveCaret(target, shift);
            return false;
        }
        if (key == Keyboard.KEY_HOME) {
            moveCaret(0, shift);
            return false;
        }
        if (key == Keyboard.KEY_END) {
            moveCaret(text.length(), shift);
            return false;
        }

        if (key == Keyboard.KEY_BACK) {
            if (deleteSelection()) return true;
            if (caret == 0) return false;
            int from = control ? previousWord() : caret - 1;
            text = text.substring(0, from) + text.substring(caret);
            caret = from;
            anchor = caret;
            return true;
        }
        if (key == Keyboard.KEY_DELETE) {
            if (deleteSelection()) return true;
            if (caret >= text.length()) return false;
            int to = control ? nextWord() : caret + 1;
            text = text.substring(0, caret) + text.substring(to);
            anchor = caret;
            return true;
        }

        if (ChatAllowedCharacters.isAllowedCharacter(typed)) return insert(String.valueOf(typed), max);
        return false;
    }
private boolean insert(String value, int max) {
        if (value == null || value.isEmpty()) return false;

        StringBuilder filtered = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(c)) filtered.append(c);
        }
        if (filtered.length() == 0) return false;

        int from = selectionStart();
        int to = selectionEnd();
        int room = max - (text.length() - (to - from));
        if (room <= 0) return false;
        if (filtered.length() > room) filtered.setLength(room);

        text = text.substring(0, from) + filtered + text.substring(to);
        caret = from + filtered.length();
        anchor = caret;
        return true;
    }

    private boolean deleteSelection() {
        if (!hasSelection()) return false;
        int from = selectionStart();
        int to = selectionEnd();
        text = text.substring(0, from) + text.substring(to);
        caret = from;
        anchor = caret;
        return true;
    }

    private void copySelection() {
        if (hasSelection()) GuiScreen.setClipboardString(text.substring(selectionStart(), selectionEnd()));
    }
private int previousWord() {
        int i = caret;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int nextWord() {
        int i = caret;
        int n = text.length();
        while (i < n && text.charAt(i) != ' ') i++;
        while (i < n && text.charAt(i) == ' ') i++;
        return i;
    }
}
