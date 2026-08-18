package mindless.keystroke;

import mindless.Raven;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

public class KeyStrokeCommand extends CommandBase {
    public String getCommandName() {
        return "mindless";
    }

    public void processCommand(ICommandSender sender, String[] args) {
        Raven.toggleKeyStrokeConfigGui();
    }

    public String getCommandUsage(ICommandSender sender) {
        return "/mindless";
    }

    public int getRequiredPermissionLevel() {
        return 0;
    }

    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }
}
