package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public class Queue extends Command {
    public Queue() {
        super("q", "queue", "play");
    }

    @Override
    public void execute(CommandInput input) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (input.argumentCount() == 0) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.GRAY + "[" + EnumChatFormatting.LIGHT_PURPLE + "Mindless" + EnumChatFormatting.GRAY + "] " +
                            EnumChatFormatting.RED + "Usage: ." + input.getLabel() + " <1s|2s|3s|4s|4v4>"
            ));
            return;
        }

        String mode = input.getArgument(0).toLowerCase();
        String playCmd;
        String modeName;

        switch (mode) {
            case "1s":
            case "solo":
                playCmd = "/play bedwars_eight_one";
                modeName = "Solo BedWars";
                break;
            case "2s":
            case "doubles":
                playCmd = "/play bedwars_eight_two";
                modeName = "Doubles BedWars";
                break;
            case "3s":
            case "triples":
                playCmd = "/play bedwars_four_three";
                modeName = "3v3v3v3 BedWars";
                break;
            case "4s":
            case "fours":
                playCmd = "/play bedwars_four_four";
                modeName = "4v4v4v4 BedWars";
                break;
            case "4v4":
                playCmd = "/play bedwars_two_four";
                modeName = "4v4 BedWars";
                break;
            default:
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        EnumChatFormatting.GRAY + "[" + EnumChatFormatting.LIGHT_PURPLE + "Mindless" + EnumChatFormatting.GRAY + "] " +
                                EnumChatFormatting.RED + "Unknown mode '" + mode + "'! Use 1s, 2s, 3s, 4s, or 4v4."
                ));
                return;
        }

        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GRAY + "[" + EnumChatFormatting.LIGHT_PURPLE + "Mindless" + EnumChatFormatting.GRAY + "] " +
                        EnumChatFormatting.GRAY + "Sending to " + EnumChatFormatting.LIGHT_PURPLE + modeName + EnumChatFormatting.GRAY + "..."
        ));

        mc.thePlayer.sendChatMessage(playCmd);
    }
}