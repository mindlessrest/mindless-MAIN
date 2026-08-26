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
        if (input.argumentCount() == 0 || "all".equalsIgnoreCase(input.getArgument(0))) {
            int count = 0;
            for (Module module : ModuleManager.modules) {
                if (module.isHidden()) {
                    module.setHidden(false);
                    count++;
                }
            }
            replyWithHeader("&7Made &a" + count + " &7module" + (count == 1 ? "" : "s") + " visible in HUD.");
            return;
        }

        String name = input.joinArguments(0);
        Module module = ModuleManager.getModule(name);
        if (module == null) {
            replyWithHeader("&cModule not found: &7" + name);
            return;
        }
        if (!module.isHidden()) {
            replyWithHeader("&7" + module.getName() + " &7is already visible.");
            return;
        }
        module.setHidden(false);
        replyWithHeader("&7Made &a" + module.getName() + " &7visible in HUD.");
    }
}
