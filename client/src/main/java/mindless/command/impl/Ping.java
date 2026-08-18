package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.helper.PingHelper;

public class Ping extends Command {
    public Ping() {
        super("ping");
    }

    @Override
    public void execute(CommandInput input) {
        PingHelper.checkPing(true);
    }
}
