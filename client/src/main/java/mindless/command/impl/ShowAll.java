package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.module.Module;
import mindless.module.ModuleManager;

public class ShowAll extends Command {
    public ShowAll() {
        super("show", "showall");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() > 0 && !"all".equalsIgnoreCase(input.getArgument(0))) {
            syntaxError();
            return;
        }

        int count = 0;
        for (Module module : ModuleManager.modules) {
            if (module.isHidden()) {
                module.setHidden(false);
                count++;
            }
        }
        replyWithHeader("&7Made &a" + count + " &7module" + (count == 1 ? "" : "s") + " visible in HUD.");
    }
}
