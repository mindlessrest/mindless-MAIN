package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.utility.Utils;
import mindless.utility.system.SystemUtils;

public class Name extends Command {
    public Name() {
        super("name", "ign", "name");
    }

    @Override
    public void execute(CommandInput input) {
        if (!Utils.nullCheck()) {
            return;
        }

        SystemUtils.addToClipboard(mc.thePlayer.getName());
        replyWithHeader("&7Copied &b" + mc.thePlayer.getName() + " &7to clipboard");
    }
}
