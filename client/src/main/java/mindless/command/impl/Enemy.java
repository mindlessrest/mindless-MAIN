package mindless.command.impl;

import mindless.Mindless;
import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.utility.PlayerRelationsManager;
import mindless.utility.Utils;

import java.util.List;

public class Enemy extends Command {
    public Enemy() {
        super("enemy", "enemy", "e");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0) {
            List<PlayerRelationsManager.PlayerEntry> entries = Mindless.playerRelationsManager.getEntries(PlayerRelationsManager.RelationType.ENEMY);
            replyWithHeader("&b" + entries.size() + " &7enem" + (entries.size() == 1 ? "y" : "ies") + ".");
            for (PlayerRelationsManager.PlayerEntry entry : entries) {
                replyWithHeader(" &b" + entry.getDisplayName());
            }
            return;
        }

        if (input.argumentCount() != 1) {
            syntaxError();
            return;
        }

        String name = input.getArgument(0);
        if ("clear".equalsIgnoreCase(name)) {
            int cleared = Mindless.playerRelationsManager.getCount(PlayerRelationsManager.RelationType.ENEMY);
            Mindless.playerRelationsManager.clearEnemies();
            replyWithHeader("&b" + cleared + " &7enem" + (cleared == 1 ? "y" : "ies") + " cleared.");
            return;
        }

        if (Utils.addEnemy(name)) {
            replyWithHeader("&7Added enemy&7: &b" + name);
        }
        else {
            Utils.removeEnemy(name);
            replyWithHeader("&7Removed enemy&7: &b" + name);
        }
    }
}
