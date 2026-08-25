package mindless.utility;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the Rich Presence knows about the current Hypixel session.
 *
 * <p>Two sources feed this. The scoreboard is free, since it is already on screen, and gives the
 * game, the map and the Skyblock figures. What it will not tell you is which variant of a game you
 * are in -- the Bed Wars sidebar looks the same in Solo as it does in Doubles -- and that variant
 * is the interesting half of a status line. {@code /locraw} answers it authoritatively, so it is
 * asked once per server and its reply is swallowed before it reaches chat. Where both have an
 * opinion, locraw wins and the scoreboard is the fallback.
 *
 * <p>The party chat patterns come from HyCord (DeDiamondPro), which had already worked out how
 * many ways Hypixel can word "somebody joined your party" once ranks, the dungeon finder and the
 * party finder are all in play. They are kept verbatim rather than tidied for exactly that reason:
 * every branch in them is load-bearing against some real message.
 */
public final class HypixelPresence {
    private HypixelPresence() {
    }

    /**
     * A rank prefix as it appears in party chat, colour codes and all.
     *
     * <p>Every party pattern below embeds this, which is why it is pulled out rather than repeated
     * nine times. It matches the bracketed ranks, the plus signs MVP++ carries, the odd two-colour
     * YOUTUBE tag, and the bare grey used for a player with no rank at all.
     */
    private static final String RANK =
            "(\\[(MVP((\u00a7r)?(\u00a7[a-z0-9])?(\\+)){0,2}(\u00a7r)?(\u00a7[a-z0-9])?"
                    + "|VIP(\u00a7r)?(\u00a7[a-z0-9])?\\+?(\u00a7r)?(\u00a7[a-z0-9])?"
                    + "|ADMIN|HELPER|MOD|(\u00a7r)?(\u00a7[a-z0-9])YOUTUBE(\u00a7r)?(\u00a7[a-z0-9]))]"
                    + "|(\u00a7r)?(\u00a77))";

    private static final Pattern PARTY_LIST = Pattern.compile(
            "(\u00a76Party Members \\(|\u00a7e(Looting|Visiting|Adventuring|Exploring) "
                    + "\u00a7r\u00a7cThe Catacombs( Entrance)? \u00a7r\u00a7ewith \u00a7r\u00a79)"
                    + "(?<users>[0-9]+)(\\)\u00a7r|/5 players( \u00a7r\u00a7eon \u00a7r\u00a76Floor [IV]+)?\u00a7r\u00a7e!\u00a7r)");

    private static final Pattern DISBAND = Pattern.compile(
            "\u00a7cThe party was disbanded because all invites expired and the party was empty\u00a7r"
                    + "|(\u00a7eYou have been kicked from the party by (\u00a7r)?)?\u00a7[a-z0-9]" + RANK
                    + " ([a-zA-Z0-9_]{3,16}) (\u00a7r\u00a7ehas disbanded the party!|\u00a7r\u00a7e)\u00a7r"
                    + "|\u00a7eYou left the party\\.\u00a7r"
                    + "|\u00a7cThe party was disbanded because the party leader disconnected\\.\u00a7r");

    private static final Pattern ALONE =
            Pattern.compile("\u00a7cYou are not currently in a party\\.\u00a7r");

    private static final Pattern PROMOTE = Pattern.compile(
            "((\u00a7[a-z0-9])" + RANK + " ([a-zA-Z0-9_]{3,16})\u00a7r\u00a7e has promoted"
                    + "|\u00a7eThe party was transferred to) \u00a7r(\u00a7[a-z0-9])?" + RANK
                    + "( )?(?<user>[a-zA-Z0-9_]{3,16})"
                    + "( \u00a7r\u00a7eto Party (Moderator|Leader)| \u00a7r\u00a7eby \u00a7r(\u00a7[a-z0-9])?"
                    + RANK + "( )?([a-zA-Z0-9_]{3,16}))\u00a7r");

    private static final Pattern DEMOTE = Pattern.compile(
            "(\u00a7[a-z0-9])?" + RANK + " ([a-zA-Z0-9_]{3,16})\u00a7r\u00a7e has demoted "
                    + "(\u00a7r)?(\u00a7[a-z0-9])?" + RANK
                    + " (?<user>[a-zA-Z0-9_]{3,16}) \u00a7r\u00a7eto Party Member\u00a7r");

    private static final Pattern JOIN = Pattern.compile(
            "(\u00a7dDungeon Finder \u00a7r\u00a7f> \u00a7r)?(\u00a7[a-b0-9])?" + RANK + "?( )?"
                    + "(?<user>[a-zA-Z0-9_]{3,16}) \u00a7r\u00a7ejoined the "
                    + "(dungeon group! \\(\u00a7r\u00a7b(Berserk|Tank|Healer|Mage|Archer) Level [0-9]+\u00a7r\u00a7e\\)"
                    + "|party\\.)\u00a7r");

    private static final Pattern LEAVE = Pattern.compile(
            "(\u00a7[a-z0-9])?" + RANK + "( )?(?<user>[a-zA-Z0-9_]{3,16}) "
                    + "(\u00a7r\u00a7ehas left the party\\."
                    + "|\u00a7r\u00a7ewas removed from your party because they disconnected"
                    + "|\u00a7r\u00a7ehas been removed from the party\\.)\u00a7r");

    private static final Pattern JOINED = Pattern.compile(
            "\u00a7eYou have joined (\u00a7r)?(\u00a7[a-z0-9])" + RANK
                    + "( )?(?<user>[a-zA-Z0-9_]{3,16})'s \u00a7r\u00a7eparty!\u00a7r");

    /**
     * The party finder's "you'll be partying with" line.
     *
     * <p>The rank is optional here where HyCord had it mandatory. Its version could not match a
     * list containing anyone without a rank, because a rankless name arrives as a single colour
     * code that the preceding group has already eaten, leaving nothing for the rank to match.
     */
    private static final Pattern PARTY_WITH = Pattern.compile(
            "\u00a7eYou'll be partying with: ((\u00a7r)(\u00a7[a-z0-9])" + RANK + "?"
                    + " ?(?<user>[a-zA-Z0-9_]{3,16})\u00a7r(\u00a7e, )?)+");

    private static final Pattern SB_TIME = Pattern.compile(" (?<time>[0-9]{1,2}:[0-9]{1,2}(am|pm)) ");
    private static final Pattern SB_DATE = Pattern.compile(" (?<date>[a-zA-Z ]+[0-9]+.{2})");

    /** The Skyblock location marker, which the sidebar puts in front of the area name. */
    private static final char SKYBLOCK_AREA_MARKER = '\u23e3';

    /**
     * Hypixel's internal game ids, spelled the way a person would say them.
     *
     * <p>locraw hands back things like {@code SURVIVAL_GAMES} and {@code GINGERBREAD}, which are
     * the original names of games Hypixel has since renamed. Nobody calls it Gingerbread.
     */
    private static final Map<String, String> GAME_NAMES = new HashMap<>();

    /**
     * Game variants, keyed by id with the game's own prefix already stripped.
     *
     * <p>This is the whole point of asking locraw: {@code EIGHT_ONE} is Solo and {@code EIGHT_TWO}
     * is Doubles, and no amount of staring at the scoreboard will tell you which one you are in.
     * Anything missing falls through to {@link #prettify}, which is wrong less often than it is
     * right but never worse than showing the raw id.
     */
    private static final Map<String, String> MODE_NAMES = new HashMap<>();

    static {
        GAME_NAMES.put("BEDWARS", "Bed Wars");
        GAME_NAMES.put("SKYWARS", "SkyWars");
        GAME_NAMES.put("SKYBLOCK", "SkyBlock");
        GAME_NAMES.put("DUELS", "Duels");
        GAME_NAMES.put("MURDER_MYSTERY", "Murder Mystery");
        GAME_NAMES.put("BUILD_BATTLE", "Build Battle");
        GAME_NAMES.put("SURVIVAL_GAMES", "Blitz SG");
        GAME_NAMES.put("TNTGAMES", "TNT Games");
        GAME_NAMES.put("ARCADE", "Arcade Games");
        GAME_NAMES.put("UHC", "UHC");
        GAME_NAMES.put("SPEED_UHC", "Speed UHC");
        GAME_NAMES.put("MCGO", "Cops and Crims");
        GAME_NAMES.put("BATTLEGROUND", "Warlords");
        GAME_NAMES.put("WALLS3", "Mega Walls");
        GAME_NAMES.put("WALLS", "The Walls");
        GAME_NAMES.put("SUPER_SMASH", "Smash Heroes");
        GAME_NAMES.put("PAINTBALL", "Paintball");
        GAME_NAMES.put("QUAKECRAFT", "Quakecraft");
        GAME_NAMES.put("GINGERBREAD", "Turbo Kart Racers");
        GAME_NAMES.put("VAMPIREZ", "VampireZ");
        GAME_NAMES.put("ARENA", "Arena Brawl");
        GAME_NAMES.put("LEGACY", "Classic Games");
        GAME_NAMES.put("PROTOTYPE", "Prototype");
        GAME_NAMES.put("HOUSING", "Housing");
        GAME_NAMES.put("PIT", "The Hypixel Pit");
        GAME_NAMES.put("SMP", "SMP");
        GAME_NAMES.put("REPLAY", "Replay");
        GAME_NAMES.put("MAIN", "Lobby");
        GAME_NAMES.put("LIMBO", "Limbo");

        MODE_NAMES.put("EIGHT_ONE", "Solo");
        MODE_NAMES.put("EIGHT_TWO", "Doubles");
        MODE_NAMES.put("FOUR_THREE", "3v3v3v3");
        MODE_NAMES.put("FOUR_FOUR", "4v4v4v4");
        MODE_NAMES.put("TWO_FOUR", "4v4");
        MODE_NAMES.put("EIGHT_TWO_RUSH", "Rush Doubles");
        MODE_NAMES.put("FOUR_FOUR_RUSH", "Rush 4v4v4v4");
        MODE_NAMES.put("EIGHT_TWO_ULTIMATE", "Ultimate Doubles");
        MODE_NAMES.put("FOUR_FOUR_ULTIMATE", "Ultimate 4v4v4v4");
        MODE_NAMES.put("EIGHT_TWO_LUCKY", "Lucky Doubles");
        MODE_NAMES.put("FOUR_FOUR_LUCKY", "Lucky 4v4v4v4");
        MODE_NAMES.put("EIGHT_TWO_VOIDLESS", "Voidless Doubles");
        MODE_NAMES.put("FOUR_FOUR_VOIDLESS", "Voidless 4v4v4v4");
        MODE_NAMES.put("EIGHT_TWO_ARMED", "Armed Doubles");
        MODE_NAMES.put("FOUR_FOUR_ARMED", "Armed 4v4v4v4");
        MODE_NAMES.put("EIGHT_TWO_SWAP", "Swap Doubles");
        MODE_NAMES.put("FOUR_FOUR_SWAP", "Swap 4v4v4v4");
        MODE_NAMES.put("CASTLE", "Castle");
        MODE_NAMES.put("PRACTICE", "Practice");
        MODE_NAMES.put("SOLO_NORMAL", "Solo");
        MODE_NAMES.put("SOLO_INSANE", "Solo Insane");
        MODE_NAMES.put("TEAMS_NORMAL", "Doubles");
        MODE_NAMES.put("TEAMS_INSANE", "Doubles Insane");
        MODE_NAMES.put("RANKED_NORMAL", "Ranked");
        MODE_NAMES.put("MEGA_NORMAL", "Mega");
        MODE_NAMES.put("MEGA_DOUBLES", "Mega Doubles");
        MODE_NAMES.put("SOLO_BRAWL", "Solo Brawl");
        MODE_NAMES.put("TEAM_BRAWL", "Team Brawl");
        MODE_NAMES.put("CLASSIC_DUEL", "Classic Duel");
        MODE_NAMES.put("SW_DUEL", "SkyWars Duel");
        MODE_NAMES.put("BRIDGE_DUEL", "Bridge Duel");
        MODE_NAMES.put("BRIDGE_DOUBLES", "Bridge Doubles");
        MODE_NAMES.put("BRIDGE_FOUR", "Bridge 4v4");
        MODE_NAMES.put("UHC_DUEL", "UHC Duel");
        MODE_NAMES.put("OP_DUEL", "OP Duel");
        MODE_NAMES.put("SUMO_DUEL", "Sumo Duel");
        MODE_NAMES.put("BOW_DUEL", "Bow Duel");
        MODE_NAMES.put("LOBBY", "Lobby");
    }

    private static String game = "";
    private static String mode = "Lobby";
    private static String map = "";
    private static String server = "";
    private static String coins = "";
    private static String bits = "";
    private static String sbTime = "";
    private static String sbDate = "";
    private static String itemHeld = "";

    private static int partyMembers = 1;
    private static long startedAt = System.currentTimeMillis() / 1000L;

    /** True once locraw has been asked on this server, so it is asked exactly once. */
    private static boolean askedLocraw;
    /** True while our own locraw reply is in flight, so only that one gets hidden from chat. */
    private static boolean awaitingOurLocraw;

    /**
     * Forgets everything tied to a server.
     *
     * <p>Called on world load. The elapsed timer restarts here too, so Discord shows time in the
     * current game rather than time since the client launched.
     */
    public static void reset() {
        game = "";
        mode = "Lobby";
        map = "";
        server = "";
        coins = "";
        bits = "";
        sbTime = "";
        sbDate = "";
        itemHeld = "";
        askedLocraw = false;
        awaitingOurLocraw = false;
        startedAt = System.currentTimeMillis() / 1000L;
    }

    /** Wipes the party count as well. Only for a full disconnect, not a server hop. */
    public static void resetParty() {
        partyMembers = 1;
    }

    /**
     * Re-reads the scoreboard. Cheap enough to call often, but the module throttles it anyway
     * since none of this changes between one tick and the next.
     */
    public static void tick(boolean useLocraw) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        List<String> sidebar = Utils.getSidebarLines();
        for (String raw : sidebar) {
            String line = cleanLine(raw);
            if (line.contains("Mode: ")) {
                mode = line.replace("Mode: ", "");
            }
            else if (line.indexOf(SKYBLOCK_AREA_MARKER) >= 0) {
                map = line.replace(" " + SKYBLOCK_AREA_MARKER + " ", "").trim();
            }
            else if (line.contains("Map: ")) {
                map = line.replace("Map: ", "");
            }
            else if (line.contains("Purse: ") || line.contains("Piggy: ")) {
                coins = line.replaceAll("Purse: |Piggy: ", "");
            }
            else if (line.contains("Bits: ")) {
                bits = line.replace("Bits: ", "");
            }
            else {
                Matcher date = SB_DATE.matcher(line);
                Matcher time = SB_TIME.matcher(line);
                if (date.matches()) {
                    sbDate = date.group("date");
                }
                else if (time.matches()) {
                    sbTime = time.group("time");
                }
            }
        }

        if (map.equals("Your Island")) {
            map = "Private Island";
        }

        // The sidebar heading is the game name on every Hypixel server. No heading means limbo,
        // which is also where you land when the server has nothing to say about you.
        String heading = sidebarHeading();
        if (!heading.isEmpty()) {
            game = heading;
        }
        else if (game.isEmpty()) {
            game = "Limbo";
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        itemHeld = held == null ? "" : Utils.stripColor(held.getDisplayName());

        // Hypixel gives each of these its own scoreboard heading, but Discord reads better with
        // the umbrella name up front and the specific game as the mode.
        if (isOneOf(game, "Bow spleef", "Pvp run") || game.toLowerCase(Locale.ROOT).contains("tnt")) {
            mode = game;
            game = "TNT Games";
        }
        else if (isArcade(game)) {
            mode = game;
            game = "Arcade Games";
        }
        else if (isClassic(game)) {
            mode = game;
            game = "Classic Games";
        }
        else if (game.equalsIgnoreCase("Pixel party")) {
            mode = game;
            game = "Prototype";
        }

        if (useLocraw && !askedLocraw && !sidebar.isEmpty() && Utils.isHypixel()) {
            askedLocraw = true;
            awaitingOurLocraw = true;
            mc.thePlayer.sendChatMessage("/locraw");
        }
    }

    /**
     * Feeds a chat line in.
     *
     * @param formatted   the message with its colour codes, which the party patterns need
     * @param unformatted the message stripped, which is what locraw's JSON arrives as
     * @return true when the line was our own locraw reply and should not reach chat
     */
    public static boolean onChat(String formatted, String unformatted) {
        if (unformatted.startsWith("{\"server\":\"") && unformatted.endsWith("}")) {
            readLocraw(unformatted);
            if (awaitingOurLocraw) {
                awaitingOurLocraw = false;
                return true;
            }
            return false;
        }

        Matcher list = PARTY_LIST.matcher(formatted);
        if (list.matches()) {
            partyMembers = Integer.parseInt(list.group("users"));
            return false;
        }
        if (DISBAND.matcher(formatted).matches() || ALONE.matcher(formatted).matches()) {
            partyMembers = 1;
            return false;
        }
        if (JOIN.matcher(formatted).matches()) {
            partyMembers++;
            return false;
        }
        if (LEAVE.matcher(formatted).matches()) {
            partyMembers = Math.max(1, partyMembers - 1);
            return false;
        }
        if (JOINED.matcher(formatted).matches()) {
            partyMembers = 2;
            return false;
        }
        if (PARTY_WITH.matcher(formatted).matches()) {
            // The line names everyone except you, comma separated, so a list of n names carries
            // n-1 commas and the party is that plus yourself. HyCord started this count at three
            // and came out one over on every group size.
            int commas = 0;
            for (int i = 0; i < formatted.length(); i++) {
                if (formatted.charAt(i) == ',') {
                    commas++;
                }
            }
            partyMembers = commas + 2;
            return false;
        }
        // Promotions and demotions do not change the head count, but they are matched so the
        // patterns stay exercised and a future invite button has the leader state to work from.
        PROMOTE.matcher(formatted).matches();
        DEMOTE.matcher(formatted).matches();
        return false;
    }

    /** Pulls what locraw knows over whatever the scoreboard guessed. */
    private static void readLocraw(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (root.has("server")) {
                server = root.get("server").getAsString();
            }

            String gametype = root.has("gametype") ? root.get("gametype").getAsString() : "";
            if (!gametype.isEmpty()) {
                game = gameName(gametype);
            }
            if (root.has("mode")) {
                mode = modeName(gametype, root.get("mode").getAsString());
            }
            if (root.has("map")) {
                map = root.get("map").getAsString();
            }
        }
        catch (Exception ignored) {
            // A malformed reply just leaves the scoreboard's version in place.
        }
    }

    /** Fills in the {@code {...}} placeholders. Empty when the result would not fit Discord. */
    public static String format(String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        Minecraft mc = Minecraft.getMinecraft();
        String user = mc.thePlayer == null ? "" : mc.thePlayer.getName();
        String players = mc.getNetHandler() == null
                ? "0" : String.valueOf(mc.getNetHandler().getPlayerInfoMap().size());

        String out = template
                .replace("{server}", server)
                .replace("{game}", game)
                .replace("{mode}", mode)
                .replace("{map}", map)
                .replace("{user}", user)
                .replace("{item}", itemHeld)
                .replace("{coins}", coins)
                .replace("{bits}", bits)
                .replace("{time}", sbTime)
                .replace("{date}", sbDate)
                .replace("{players}", players)
                .trim();

        // Discord rejects a field under two characters and truncates past 128, so anything that
        // lands outside that is dropped rather than sent and shown wrong.
        return out.length() < 2 || out.length() >= 128 ? "" : out;
    }

    /**
     * The art key for the current game.
     *
     * <p>These are asset names, not URLs: they only resolve if art with the same name has been
     * uploaded to the Discord application. Anything unrecognised falls back to the client's own
     * logo, which is always present.
     */
    public static String iconKey() {
        String g = game.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (g.contains("bed_wars") || g.contains("bedwars")) return "bedwars";
        if (g.contains("speed_uhc")) return "speeduhc";
        if (g.contains("uhc")) return "uhc";
        if (g.contains("skywars")) return "skywars";
        if (g.contains("duels")) return "duels";
        if (g.contains("turbo_kart_racers")) return "turbokartracers";
        if (g.contains("arcade")) return "arcade";
        if (g.contains("arena_brawl")) return "arena";
        if (g.contains("build_battle")) return "buildbattle";
        if (g.contains("paintball")) return "paintball";
        if (g.contains("smash_heroes")) return "smashheroes";
        if (g.contains("mega_walls")) return "megawalls";
        if (g.contains("cops_and_crims")) return "cvc";
        if (g.contains("the_walls")) return "walls";
        if (g.contains("quakecraft")) return "quakecraft";
        if (g.contains("warlords")) return "warlords";
        if (g.contains("murder_mystery")) return "murdermystery";
        if (g.contains("tnt")) return "tnt";
        if (g.contains("vampirez")) return "vampirez";
        if (g.contains("prototype")) return "prototype";
        if (g.contains("skyblock")) return "skyblock";
        if (g.contains("pit")) return "pit";
        if (g.contains("classic")) return "classic";
        if (g.contains("housing")) return "housing";
        if (g.contains("blitz")) return "blitz_sg";
        return "";
    }

    public static String getGame() {
        return game;
    }

    public static String getMode() {
        return mode;
    }

    public static String getMap() {
        return map;
    }

    public static String getServer() {
        return server;
    }

    public static int getPartyMembers() {
        return partyMembers;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static boolean isSkyblock() {
        return game.toLowerCase(Locale.ROOT).contains("skyblock");
    }

    public static boolean inLobby() {
        return mode.equalsIgnoreCase("Lobby") || game.equalsIgnoreCase("Lobby");
    }

    /**
     * Strips formatting the way the sidebar needs it stripped.
     *
     * <p>{@link Utils#stripString} drops everything above the ASCII range, which takes the
     * Skyblock area marker with it and loses the only thing identifying a location line. This
     * keeps that one character and discards the rest of the high range as usual.
     */
    private static String cleanLine(String line) {
        char[] chars = StringUtils.stripControlCodes(line).toCharArray();
        StringBuilder cleaned = new StringBuilder(chars.length);
        for (char c : chars) {
            if ((c > '\u0014' && c < '\u007f') || c == SKYBLOCK_AREA_MARKER) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static String sidebarHeading() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return "";
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return "";
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return "";
        }
        return titleCase(Utils.stripColor(objective.getDisplayName()));
    }

    private static String gameName(String gametype) {
        String known = GAME_NAMES.get(gametype.toUpperCase(Locale.ROOT));
        return known != null ? known : prettify(gametype);
    }

    private static String modeName(String gametype, String rawMode) {
        String id = rawMode.toUpperCase(Locale.ROOT);
        // Bed Wars reports BEDWARS_EIGHT_ONE, SkyWars reports solo_normal. Dropping the game's
        // own prefix lets one table cover both spellings.
        String prefix = gametype.toUpperCase(Locale.ROOT) + "_";
        if (id.startsWith(prefix)) {
            id = id.substring(prefix.length());
        }
        String known = MODE_NAMES.get(id);
        return known != null ? known : prettify(id);
    }

    /** {@code EIGHT_ONE} to {@code Eight One}. The fallback when nothing better is known. */
    private static String prettify(String id) {
        return titleCase(id.replace('_', ' '));
    }

    private static String titleCase(String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean startOfWord = true;
        for (char c : text.toCharArray()) {
            if (c == ' ') {
                startOfWord = true;
                out.append(c);
            }
            else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            }
            else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArcade(String value) {
        return isOneOf(value, "Creeper attack", "Farm hunt", "Party games", "Zombies",
                "Hide and seek", "Hypixel says", "Mini walls", "Blocking dead", "Hole in the wall",
                "Football", "Bounty hunters", "Pixel painters", "Capture the wool", "Dragon wars",
                "Ender spleef", "Galaxy wars", "Throw out");
    }

    private static boolean isClassic(String value) {
        return isOneOf(value, "Arena brawl", "Vampirez", "Turbo kart racers", "Quakecraft",
                "The walls", "Paintball warfare");
    }
}
