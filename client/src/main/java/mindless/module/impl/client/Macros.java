package mindless.module.impl.client;

import mindless.Mindless;
import mindless.event.KeyPressEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Bind a key to something you would otherwise type.
 *
 * Six slots, each a key and a line of text. Press the key and the line goes out: a client command
 * if it starts with the client prefix, a server command if it starts with a slash, and plain chat
 * otherwise. That covers "/shout", ".friend add", and a canned message with the same setting.
 *
 * Keys only fire while no screen is open, so a macro bound to a letter cannot go off mid-sentence
 * in chat or while clicking through a shop.
 */
public class Macros extends Module {
    private static final int SLOTS = 6;

    private final GroupSetting[] groups = new GroupSetting[SLOTS];
    private final KeySetting[] keys = new KeySetting[SLOTS];
    private final TextSetting[] texts = new TextSetting[SLOTS];
    private final ButtonSetting requireInWorld;

    public Macros() {
        super("Macros", "Binds keys to commands and chat messages.", category.client, 0);
        this.registerSetting(new DescriptionSetting("A key and a line. Slash for server commands."));
        this.registerSetting(requireInWorld = new ButtonSetting("Only in world", true));
        for (int i = 0; i < SLOTS; i++) {
            this.registerSetting(groups[i] = new GroupSetting("Macro " + (i + 1)));
            this.registerSetting(keys[i] = new KeySetting("Macro " + (i + 1) + " key", 0));
            this.registerSetting(texts[i] = new TextSetting(groups[i],
                    "Macro " + (i + 1) + " text", "", "", 100));
        }
    }

    @Override
    public String getInfo() {
        int bound = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i].getKey() > 0 && !texts[i].getText().trim().isEmpty()) {
                bound++;
            }
        }
        return bound == 0 ? "none bound" : bound + " bound";
    }

    @SubscribeEvent
    public void onKeyPress(KeyPressEvent event) {
        if (event.keyCode <= 0 || !Utils.nullCheck()) {
            return;
        }
        // A macro on a letter key would otherwise fire while typing.
        if (mc.currentScreen != null) {
            return;
        }
        if (requireInWorld.isToggled() && mc.theWorld == null) {
            return;
        }

        for (int i = 0; i < SLOTS; i++) {
            if (keys[i].getKey() != event.keyCode) {
                continue;
            }
            String line = texts[i].getText().trim();
            if (!line.isEmpty()) {
                run(line);
            }
        }
    }

    /**
     * Send one macro line.
     *
     * Client commands are dispatched in-process rather than through chat, so they never reach the
     * server even if the prefix happens to mean something there.
     */
    private void run(String line) {
        try {
            String prefix = Mindless.commandManager != null ? Mindless.commandManager.getPrefix() : null;
            if (prefix != null && !prefix.isEmpty() && line.startsWith(prefix)) {
                // Passed whole: the command manager strips the prefix itself when it parses.
                Mindless.commandManager.executeCommand(line);
                return;
            }
            mc.thePlayer.sendChatMessage(line);
        }
        catch (Exception failed) {
            // A bad macro line is not worth dropping the key event for.
            Utils.sendMessage("&cMacro failed: " + failed.getMessage());
        }
    }
}
