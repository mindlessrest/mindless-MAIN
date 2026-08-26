package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.module.Module;
import mindless.module.ModuleManager;

public class HideAll extends Command {
    public HideAll() {
        super("hide", "hideall");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0 || "all".equalsIgnoreCase(input.getArgument(0))) {
            int count = 0;
            for (Module module : ModuleManager.modules) {
                if (!module.isHidden()) {
                    module.setHidden(true);
                    count++;
                }
            }
            replyWithHeader("&7Hidden &c" + count + " &7module" + (count == 1 ? "" : "s") + " from HUD.");
            return;
        }

        String name = input.joinArguments(0);
        Module module = ModuleManager.getModule(name);
        if (module == null) {
            replyWithHeader("&cModule not found: &7" + name);
            return;
        }
        if (module.isHidden()) {
            replyWithHeader("&7" + module.getName() + " &7is already hidden.");
            return;
        }
        module.setHidden(true);
        replyWithHeader("&7Hidden &c" + module.getName() + " &7from HUD.");
    }
}
