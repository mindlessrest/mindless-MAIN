package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.module.ModuleManager;
import mindless.utility.Utils;

public class Script extends Command {
    public Script() {
        super("script");
    }

    @Override
    public void execute(CommandInput input) {
        execute(input.getArguments());
    }

    public void execute(String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("load")) {
            Utils.sendMessage("&cUsage: .script load");
            return;
        }
        final long now = System.currentTimeMillis();
        ModuleManager.scriptManager.loadScripts(now);
    }
}
