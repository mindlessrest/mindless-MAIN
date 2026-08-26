package mindless.command.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.utility.Utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class Urchin extends Command {
    private static String apiKey = "";

    public Urchin() {
        super("urchin", "urc");
    }

    public static String getApiKey() {
        return apiKey;
    }

    public static void setApiKey(String key) {
        apiKey = key == null ? "" : key.trim();
    }

    public static boolean hasKey() {
        return !apiKey.isEmpty();
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0) {
            replyWithHeader("&7Usage: &e.urchin <player> &7| &e.urchin setkey <key>");
            return;
        }

        String arg0 = input.getArgument(0);
        if ("setkey".equalsIgnoreCase(arg0)) {
            if (input.argumentCount() < 2) {
                replyWithHeader("&7Usage: &e.urchin setkey <key>");
                return;
            }
            apiKey = input.getArgument(1).trim();
            replyWithHeader("&7Urchin API key &aset&7.");
            return;
        }

        if (apiKey.isEmpty()) {
            replyWithHeader("&cNo API key set. Use &e.urchin setkey <key>");
            return;
        }

        String name = arg0;
        replyWithHeader("&7Looking up &b" + name + "&7...");
        new Thread(() -> {
            try {
                String result = fetchTags(name);
                if (result != null) {
                    Utils.sendMessage(result);
                }
            } catch (Exception e) {
                Utils.sendMessage("&c(Urchin) Error: " + e.getMessage());
            }
        }, "Urchin-Lookup").start();
    }

    public static String fetchTags(String name) {
        HttpURLConnection conn = null;
        try {
            String url = "https://api.urchin.gg/v3/player/tags?player=" + name + "&key=" + apiKey.replace(" ", "");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                return "&c(Urchin) Request failed (HTTP " + conn.getResponseCode() + ")";
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String response = reader.lines().collect(Collectors.joining());
            reader.close();

            JsonObject json = new JsonParser().parse(response).getAsJsonObject();
            JsonArray tagsArray = json.has("tags") && json.get("tags").isJsonArray() ? json.getAsJsonArray("tags") : new JsonArray();

            if (tagsArray.size() == 0) {
                return "&b" + name + " &7is not in the &5Urchin &7blacklist.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("&b").append(name).append(" &7is tagged on &5Urchin&7:");
            for (JsonElement element : tagsArray) {
                if (!element.isJsonObject()) continue;
                JsonObject tag = element.getAsJsonObject();
                String type = tag.has("type") && !tag.get("type").isJsonNull() ? tag.get("type").getAsString().replace('_', ' ') : "unknown";
                String reason = tag.has("reason") && !tag.get("reason").isJsonNull() ? tag.get("reason").getAsString() : "";
                sb.append("\n  &c").append(type);
                if (!reason.isEmpty()) {
                    sb.append(" &8- &7").append(reason);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "&c(Urchin) " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean isTagged(String name) {
        if (apiKey.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            String url = "https://api.urchin.gg/v3/player/tags?player=" + name + "&key=" + apiKey.replace(" ", "");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return false;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String response = reader.lines().collect(Collectors.joining());
            reader.close();

            JsonObject json = new JsonParser().parse(response).getAsJsonObject();
            JsonArray tagsArray = json.has("tags") && json.get("tags").isJsonArray() ? json.getAsJsonArray("tags") : new JsonArray();
            return tagsArray.size() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
