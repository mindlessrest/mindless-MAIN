package mindless.utility;

import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;

/**
 * Whether a player is nicked.
 *
 * A real Mojang account carries a version 4 UUID -- randomly generated. Hypixel hands a nicked
 * player a version 1 UUID instead, which is the time-and-MAC based kind, because it is minting
 * the identity on the spot rather than looking one up. The version is four bits of the UUID, so
 * the whole check is local, costs nothing, needs no API, and cannot be rate limited or go down.
 *
 * It says a name is not real. It does not say whose it is; recovering that needs a lookup service
 * and is a separate problem.
 */
public final class NickDetection {

    private NickDetection() {
    }

    public static boolean isNicked(EntityPlayer player) {
        return player != null && player.getGameProfile() != null
                && isNicked(player.getGameProfile().getId());
    }

    public static boolean isNicked(UUID id) {
        // Offline-mode and NPC entities also land outside version 4, so this is only meaningful
        // on a server that authenticates -- which is where anyone would use it.
        return id != null && id.version() == 1;
    }
}
