package mindless.clickgui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

/**
 * One line of editable text with a caret and a selection.
 *
 * <p>The dashboard's search box grew a caret model of its own. The setting inputs, the list input
 * and the command line did not: they appended typed characters to the end of a string and deleted
 * from the end of it, which is why none of them took an arrow key, held a selection, or pasted
 * anywhere but the end. Anything past the right edge of the box was unreachable as well, because
 * the drawn text was a front-truncated prefix with no way to scroll it. This is the same model the
 * search box uses, factored out so the rest of them share it.
 *
 * <p>Only one field is ever focused, so one instance serves all of them: whichever field takes
 * focus loads its value in, and the edits are written straight back out.
 */
public final class TextEditor {
    private String text = "";
    private int caret;
    private int anchor;

    public String getText() {
        return text;
    }

    /** Loads a value and drops the caret at the end, which is what taking focus should do. */
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

    /**
     * @return true when the text itself changed, so the caller knows to write the value back.
     *         Caret and selection moves return false: nothing downstream needs to hear about them.
     */
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
            // An unshifted arrow against a selection collapses it to the matching end rather than
            // stepping one further from the caret, which is what every other text box does.
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

    /** Replaces the selection, or inserts at the caret when there is none. */
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

    /** Word motion stops where a run of non-spaces begins, matching the search box. */
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
