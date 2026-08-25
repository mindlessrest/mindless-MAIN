package mindless.command.impl;

import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.module.ModuleManager;
import mindless.utility.HypixelPresence;
import mindless.utility.Utils;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * Reports why the Rich Presence is showing what it is showing.
 *
 * <p>The presence has two halves that fail independently and look identical from the outside: the
 * pipe to Discord, and whether the client believes it is on Hypixel. Nothing distinguished them,
 * so "the RPC is broken" could mean Discord is not running, or that a proxy made the server
 * address unrecognisable. This prints both.
 */
public class Rpc extends Command {
    public Rpc() {
        super("rpc");
    }

    @Override
    public void execute(CommandInput input) {
        mindless.module.impl.other.DiscordRPC module = ModuleManager.discordRPC;
        if (module == null) {
            reply("&cDiscord RPC module is missing.");
            return;
        }

        reply("&b--- Discord RPC ---");
        reply("&7module: " + (module.isEnabled() ? "&aenabled" : "&cdisabled"));

        mindless.utility.DiscordRPC transport = module.getTransport();
        if (transport == null) {
            reply("&7discord: &cnot started &8(enable the module)");
        }
        else {
            List<String> attached = transport.describeConnections();
            if (attached.isEmpty()) {
                reply("&7discord: &cno client found &8(is Discord running?)");
            }
            else {
                reply("&7discord: &a" + attached.size() + " attached");
                for (String label : attached) {
                    reply("&8  - " + label);
                }
            }

            mindless.utility.DiscordRPC.RichPresence desired = transport.getDesired();
            if (desired == null) {
                reply("&7presence: &cnothing set yet");
            }
            else {
                reply("&7presence: &f" + safe(desired.details) + " &8| &f" + safe(desired.state));
                reply("&7image: &f" + safe(desired.largeImage) + " &8(" + safe(desired.largeText) + ")");
                if (desired.partyMax > 0) {
                    reply("&7party: &f" + desired.partySize + " of " + desired.partyMax);
                }
            }
        }

        ServerData serverData = mc.getCurrentServerData();
        String address = serverData == null || serverData.serverIP == null ? "(none)" : serverData.serverIP;
        reply("&7address: &f" + address);
        reply("&7brand: &f" + Utils.getServerBrand());

        boolean detected = Utils.isHypixel();
        boolean hypixel = detected || module.isForcingHypixel();
        reply("&7hypixel: " + (detected ? "&adetected"
                : module.isForcingHypixel() ? "&eforced &8(not detected)" : "&cno"));
        if (!detected) {
            // The usual cause. A proxy replaces the address with its own, so only the brand can
            // still answer, and a proxy that rewrites the brand takes that away too.
            reply("&8  address must contain hypixel.net, or brand must contain hypixel");
            reply("&8  behind a proxy only the brand can match");
            if (!module.isForcingHypixel()) {
                reply("&8  if the brand above is the proxy's, turn on Force Hypixel");
            }
        }

        if (!module.wantsHypixelStats()) {
            reply("&7stats: &cHypixel Stats setting is off");
        }
        else if (hypixel) {
            reply("&7game: &f" + blank(HypixelPresence.getGame())
                    + " &8| mode: &f" + blank(HypixelPresence.getMode()));
            reply("&7map: &f" + blank(HypixelPresence.getMap())
                    + " &8| server: &f" + blank(HypixelPresence.getServer()));
            reply("&7party: &f" + HypixelPresence.getPartyMembers()
                    + " &8| sidebar lines: &f" + Utils.getSidebarLines().size());

            int teamSize = HypixelPresence.getTeamSize();
            int remaining = HypixelPresence.getTeamsRemaining();
            reply("&7team size: &f" + (teamSize > 1 ? String.valueOf(teamSize) : "none (solo or unknown)")
                    + " &8| teams left: &f" + (remaining > 0 ? String.valueOf(remaining) : "not shown"));
            if (teamSize <= 1 && !module.wantsSoloTeamsLeft()) {
                reply("&8  turn on Solo Teams Left to show \"1 of " + Math.max(remaining, 2) + "\" here");
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "(unset)" : value;
    }

    private static String blank(String value) {
        return value == null || value.isEmpty() ? "(empty)" : value;
    }
}
