package mindless.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;

public final class BedwarsTeam {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private BedwarsTeam() {
    }

    public static String label(EntityPlayer player) {
        if (player == null) {
            return "?";
        }
        return label(player.getName(), player.getDisplayName() == null
                ? null : player.getDisplayName().getFormattedText(), player.getTeam());
    }

    public static String label(String username) {
        NetworkPlayerInfo info = playerInfo(username);
        if (info == null) {
            return username == null || username.isEmpty() ? "?" : username;
        }
        String formatted = info.getDisplayName() == null
                ? ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), username)
                : info.getDisplayName().getFormattedText();
        return label(username, formatted, info.getPlayerTeam());
    }

    public static boolean isOwnTeam(NetworkPlayerInfo info) {
        if (info == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return false;
        }
        NetworkPlayerInfo own = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (own == null) {
            return false;
        }
        ScorePlayerTeam first = info.getPlayerTeam();
        ScorePlayerTeam second = own.getPlayerTeam();
        if (first != null && second != null && first.getRegisteredName().equals(second.getRegisteredName())) {
            return true;
        }
        return sameIdentity(identity(info), identity(own));
    }

    public static boolean isSameColorTeam(EntityPlayer first, EntityPlayer second) {
        if (first == null || second == null || first == second) {
            return false;
        }
        return sameIdentity(identity(first), identity(second));
    }

    private static String label(String username, String formatted, net.minecraft.scoreboard.Team team) {
        Identity identity = identity(username, formatted, team);
        if (identity.marker == 0) {
            return username == null || username.isEmpty() ? "?" : username;
        }
        String color = identity.color == 0 ? "" : "\u00A7" + identity.color;
        return color + "[" + identity.marker + "] " + color + username;
    }

    private static Identity identity(EntityPlayer player) {
        String formatted = player.getDisplayName() == null
                ? null : player.getDisplayName().getFormattedText();
        return identity(player.getName(), formatted, player.getTeam());
    }

    private static Identity identity(NetworkPlayerInfo info) {
        String username = info.getGameProfile() == null ? "" : info.getGameProfile().getName();
        String formatted = info.getDisplayName() == null
                ? ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), username)
                : info.getDisplayName().getFormattedText();
        return identity(username, formatted, info.getPlayerTeam());
    }

    private static Identity identity(String username, String formatted, net.minecraft.scoreboard.Team team) {
        char marker = marker(formatted);
        char color = colorBefore(formatted, marker == 0 ? username : "[" + marker + "]");
        if (color == 0 && team instanceof ScorePlayerTeam) {
            color = lastColor(((ScorePlayerTeam) team).getColorPrefix());
        }
        if (marker == 0) {
            marker = markerForColor(color);
        }
        return new Identity(marker, color);
    }

    private static boolean sameIdentity(Identity first, Identity second) {
        if (first.color != 0 && second.color != 0) {
            return first.color == second.color;
        }
        if (first.marker != 0 && second.marker != 0) {
            return first.marker == second.marker;
        }
        return false;
    }

    private static char marker(String formatted) {
        if (formatted == null) {
            return 0;
        }
        String plain = formatted.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "");
        for (int i = 0; i + 2 < plain.length(); i++) {
            if (plain.charAt(i) != '[') {
                continue;
            }
            int close = plain.indexOf(']', i + 1);
            if (close < 0 || close - i > 8) {
                continue;
            }
            String value = plain.substring(i + 1, close).trim().toUpperCase();
            if (value.equals("RED")) return 'R';
            if (value.equals("BLUE")) return 'B';
            if (value.equals("GREEN")) return 'G';
            if (value.equals("YELLOW")) return 'Y';
            if (value.equals("AQUA")) return 'A';
            if (value.equals("WHITE")) return 'W';
            if (value.equals("PINK")) return 'P';
            if (value.equals("GRAY") || value.equals("GREY")) return 'G';
            if (value.length() == 1 && "RBGYAWP".indexOf(value.charAt(0)) >= 0) {
                return value.charAt(0);
            }
        }
        return 0;
    }

    private static char colorBefore(String formatted, String token) {
        if (formatted == null || token == null || token.isEmpty()) {
            return 0;
        }
        int end = formatted.indexOf(token);
        if (end < 0) {
            end = formatted.length();
        }
        char color = 0;
        for (int i = 0; i + 1 < end; i++) {
            if (formatted.charAt(i) != '\u00A7') {
                continue;
            }
            char value = Character.toLowerCase(formatted.charAt(i + 1));
            if (isColor(value)) {
                color = value;
            }
        }
        return color;
    }

    private static char lastColor(String text) {
        return colorBefore(text, "\u0000");
    }

    private static boolean isColor(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static char markerForColor(char color) {
        switch (color) {
            case '4':
            case 'c':
                return 'R';
            case '1':
            case '9':
                return 'B';
            case '2':
            case 'a':
                return 'G';
            case '6':
            case 'e':
                return 'Y';
            case '3':
            case 'b':
                return 'A';
            case 'f':
                return 'W';
            case '5':
            case 'd':
                return 'P';
            case '7':
            case '8':
                return 'G';
            default:
                return 0;
        }
    }

    private static NetworkPlayerInfo playerInfo(String username) {
        if (username == null || mc.getNetHandler() == null) {
            return null;
        }
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info.getGameProfile() != null && username.equalsIgnoreCase(info.getGameProfile().getName())) {
                return info;
            }
        }
        return null;
    }

    private static final class Identity {
        private final char marker;
        private final char color;

        private Identity(char marker, char color) {
            this.marker = marker;
            this.color = color;
        }
    }
}
