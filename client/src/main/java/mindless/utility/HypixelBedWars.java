package mindless.utility;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class HypixelBedWars {
    private static final String BASE_URL = "https://api.hypixel.net";
    private final String apiKey;
    private final boolean resolveUsernames;

    public HypixelBedWars(String apiKey) {
        this(apiKey, true);
    }

    public HypixelBedWars(String apiKey, boolean resolveUsernames) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("a hypixel api key is required.");
        }
        this.apiKey = apiKey;
        this.resolveUsernames = resolveUsernames;
    }

    public enum Mode {
        OVERALL(""),
        SOLO("eight_one"),
        DOUBLES("eight_two"),
        THREES("four_three"),
        FOURS("four_four"),
        FOUR_V_FOUR("two_four");

        private final String prefix;

        Mode(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    public static class LevelData {
        public final int level;
        public final String prestige;
        public final int progress;
        public final int nextLevelExp;

        public LevelData(int level, String prestige, int progress, int nextLevelExp) {
            this.level = level;
            this.prestige = prestige;
            this.progress = progress;
            this.nextLevelExp = nextLevelExp;
        }
    }

    public static class ModeStats {
        public final long wins;
        public final long losses;
        public final long gamesPlayed;
        public final double winLossRatio;
        public final long finalKills;
        public final long finalDeaths;
        public final double finalKillDeathRatio;
        public final long kills;
        public final long deaths;
        public final double killDeathRatio;
        public final long bedsBroken;
        public final long bedsLost;
        public final double bedsBrokenLostRatio;

        public ModeStats(long wins, long losses, long gamesPlayed, double winLossRatio,
                         long finalKills, long finalDeaths, double finalKillDeathRatio,
                         long kills, long deaths, double killDeathRatio,
                         long bedsBroken, long bedsLost, double bedsBrokenLostRatio) {
            this.wins = wins;
            this.losses = losses;
            this.gamesPlayed = gamesPlayed;
            this.winLossRatio = winLossRatio;
            this.finalKills = finalKills;
            this.finalDeaths = finalDeaths;
            this.finalKillDeathRatio = finalKillDeathRatio;
            this.kills = kills;
            this.deaths = deaths;
            this.killDeathRatio = killDeathRatio;
            this.bedsBroken = bedsBroken;
            this.bedsLost = bedsLost;
            this.bedsBrokenLostRatio = bedsBrokenLostRatio;
        }
    }

    public static class CurrencyData {
        public final long coins;
        public final long tokens;
        public final long iron;
        public final long gold;
        public final long diamonds;
        public final long emeralds;

        public CurrencyData(long coins, long tokens, long iron, long gold, long diamonds, long emeralds) {
            this.coins = coins;
            this.tokens = tokens;
            this.iron = iron;
            this.gold = gold;
            this.diamonds = diamonds;
            this.emeralds = emeralds;
        }
    }

    public static class ModesContainer {
        public final ModeStats solo;
        public final ModeStats doubles;
        public final ModeStats threes;
        public final ModeStats fours;
        public final ModeStats fourVFour;

        public ModesContainer(ModeStats solo, ModeStats doubles, ModeStats threes, ModeStats fours, ModeStats fourVFour) {
            this.solo = solo;
            this.doubles = doubles;
            this.threes = threes;
            this.fours = fours;
            this.fourVFour = fourVFour;
        }
    }

    public static class BedwarsPlayer {
        public final String uuid;
        public final String displayName;
        public final Date firstLogin;
        public final Date lastLogin;
        public final LevelData level;
        public final CurrencyData currency;
        public final ModeStats overall;
        public final ModesContainer modes;

        public BedwarsPlayer(String uuid, String displayName, Date firstLogin, Date lastLogin,
                             LevelData level, CurrencyData currency, ModeStats overall, ModesContainer modes) {
            this.uuid = uuid;
            this.displayName = displayName;
            this.firstLogin = firstLogin;
            this.lastLogin = lastLogin;
            this.level = level;
            this.currency = currency;
            this.overall = overall;
            this.modes = modes;
        }
    }

    public static LevelData parseBedwarsLevel(long exp) {
        int easyLevels = 4;
        int[] easyLevelsXp = {500, 1000, 2000, 3500};
        int xpPerLevel = 5000;
        int levelsPerPrestige = 100;

        int level = 0;
        long remainingExp = exp;

        int easyXpSum = 0;
        for (int x : easyLevelsXp) easyXpSum += x;

        long prestigeExp = (long) levelsPerPrestige * xpPerLevel - easyXpSum + ((long) easyLevels * xpPerLevel);

        long prestiges = remainingExp / prestigeExp;
        level += (int) (prestiges * levelsPerPrestige);
        remainingExp -= prestiges * prestigeExp;

        for (int i = 0; i < easyLevels; i++) {
            if (remainingExp < easyLevelsXp[i]) break;
            remainingExp -= easyLevelsXp[i];
            level++;
        }

        if (level % levelsPerPrestige >= easyLevels) {
            level += (int) (remainingExp / xpPerLevel);
            remainingExp = remainingExp % xpPerLevel;
        }

        int currentLevelInCycle = level % levelsPerPrestige;
        int nextLevelExp = xpPerLevel;

        if (currentLevelInCycle < easyLevels) {
            nextLevelExp = easyLevelsXp[currentLevelInCycle];
        }

        String[] prestigesList = {
                "None", "Iron", "Gold", "Diamond", "Emerald", "Sapphire",
                "Ruby", "Crystal", "Opal", "Amethyst", "Rainbow", "Mythic"
        };

        int prestigeIndex = level / levelsPerPrestige;
        String prestige = prestigesList[Math.min(prestigeIndex, prestigesList.length - 1)];

        return new LevelData(level, prestige, (int) remainingExp, nextLevelExp);
    }

    private static double ratio(long a, long b) {
        if (b == 0) return 0.0;
        return Math.round((double) a / b * 100.0) / 100.0;
    }

    private static ModeStats parseMode(JsonObject stats, String modePrefix) {
        String p = (modePrefix != null && !modePrefix.isEmpty()) ? modePrefix + "_" : "";

        long wins = getLong(stats, p + "wins_bedwars");
        long losses = getLong(stats, p + "losses_bedwars");
        long finalKills = getLong(stats, p + "final_kills_bedwars");
        long finalDeaths = getLong(stats, p + "final_deaths_bedwars");
        long kills = getLong(stats, p + "kills_bedwars");
        long deaths = getLong(stats, p + "deaths_bedwars");
        long bedsBroken = getLong(stats, p + "beds_broken_bedwars");
        long bedsLost = getLong(stats, p + "beds_lost_bedwars");
        long gamesPlayedRaw = getLong(stats, p + "games_played_bedwars");
        long gamesPlayed = gamesPlayedRaw > 0 ? gamesPlayedRaw : (wins + losses);

        return new ModeStats(
                wins, losses, gamesPlayed, ratio(wins, losses),
                finalKills, finalDeaths, ratio(finalKills, finalDeaths),
                kills, deaths, ratio(kills, deaths),
                bedsBroken, bedsLost, ratio(bedsBroken, bedsLost)
        );
    }

    private static CurrencyData parseCurrency(JsonObject stats) {
        return new CurrencyData(
                getLong(stats, "coins"),
                getLong(stats, "Experience"),
                getLong(stats, "iron_resources_collected_bedwars"),
                getLong(stats, "gold_resources_collected_bedwars"),
                getLong(stats, "diamond_resources_collected_bedwars"),
                getLong(stats, "emerald_resources_collected_bedwars")
        );
    }

    public static String usernameToUuid(String username) throws Exception {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name());
        JsonObject data = getJson("https://api.mojang.com/users/profiles/minecraft/" + encoded);
        if (data == null || !data.has("id")) {
            throw new RuntimeException("player \"" + username + "\" not found.");
        }
        return data.get("id").getAsString();
    }

    public String resolveUuid(String identifier) throws Exception {
        if (identifier.matches("(?i)^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$")) {
            return identifier.replace("-", "");
        }
        if (!this.resolveUsernames) {
            throw new IllegalStateException("uuid required when resolveUsernames is disabled.");
        }
        return usernameToUuid(identifier);
    }

    public BedwarsPlayer getStats(String player) throws Exception {
        String uuid = resolveUuid(player);
        JsonObject data = fetchHypixel("/player", "uuid=" + uuid);

        if (!data.has("player") || data.get("player").isJsonNull()) {
            throw new RuntimeException("player has never logged into hypixel.");
        }

        JsonObject raw = data.getAsJsonObject("player");
        JsonObject stats = raw.has("stats") && raw.get("stats").isJsonObject() ? raw.getAsJsonObject("stats") : new JsonObject();
        JsonObject bwStats = stats.has("Bedwars") && stats.get("Bedwars").isJsonObject() ? stats.getAsJsonObject("Bedwars") : new JsonObject();

        long exp = getLong(bwStats, "Experience");
        LevelData levelData = parseBedwarsLevel(exp);

        String displayName = raw.has("displayname") ? raw.get("displayname").getAsString() : "";
        Date firstLogin = raw.has("firstLogin") ? new Date(raw.get("firstLogin").getAsLong()) : null;
        Date lastLogin = raw.has("lastLogin") ? new Date(raw.get("lastLogin").getAsLong()) : null;

        return new BedwarsPlayer(
                raw.get("uuid").getAsString(),
                displayName,
                firstLogin,
                lastLogin,
                levelData,
                parseCurrency(bwStats),
                parseMode(bwStats, Mode.OVERALL.getPrefix()),
                new ModesContainer(
                        parseMode(bwStats, Mode.SOLO.getPrefix()),
                        parseMode(bwStats, Mode.DOUBLES.getPrefix()),
                        parseMode(bwStats, Mode.THREES.getPrefix()),
                        parseMode(bwStats, Mode.FOURS.getPrefix()),
                        parseMode(bwStats, Mode.FOUR_V_FOUR.getPrefix())
                )
        );
    }

    public List<BedwarsPlayer> getMultipleStats(List<String> players) {
        List<CompletableFuture<BedwarsPlayer>> futures = new ArrayList<>();
        for (String p : players) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return getStats(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        return futures.stream().map(CompletableFuture::join).collect(java.util.stream.Collectors.toList());
    }

    private JsonObject fetchHypixel(String endpoint, String query) throws Exception {
        String fullUrl = BASE_URL + endpoint + "?key=" + this.apiKey + (query != null ? "&" + query : "");
        JsonObject data = getJson(fullUrl);
        if (data == null || !data.has("success") || !data.get("success").getAsBoolean()) {
            String cause = (data != null && data.has("cause")) ? data.get("cause").getAsString() : "unknown error";
            throw new RuntimeException("hypixel api error: " + cause);
        }
        return data;
    }

    private static JsonObject getJson(String urlString) throws Exception {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement element = new JsonParser().parse(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
    }

    private static long getLong(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return 0L;
    }
}