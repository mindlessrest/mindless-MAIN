package keystrokesmod.accountmanager.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import keystrokesmod.accountmanager.auth.StoredCookie;
import org.apache.commons.lang3.StringUtils;

final class CookieStore {
    private static final Gson GSON = new Gson();
    private static final int FORMAT_VERSION = 2;
    private final List<StoredCookie> cookies = new ArrayList<StoredCookie>();

    CookieStore() {
    }

    void put(StoredCookie cookie) {
        if (cookie == null || StringUtils.isBlank((CharSequence)cookie.name) || StringUtils.isBlank((CharSequence)cookie.value)) {
            return;
        }
        if ("Disabled".equalsIgnoreCase(cookie.value)) {
            return;
        }
        for (int i = 0; i < this.cookies.size(); ++i) {
            StoredCookie existing = this.cookies.get(i);
            if (!CookieStore.sameSlot(existing, cookie)) continue;
            this.cookies.set(i, cookie);
            return;
        }
        this.cookies.add(cookie);
    }

    void put(String domain, String path, String name, String value, boolean secure) {
        this.put(new StoredCookie(domain, path, name, value, secure));
    }

    void putAll(CookieStore other) {
        if (other == null) {
            return;
        }
        for (StoredCookie cookie : other.cookies) {
            this.put(cookie);
        }
    }

    boolean isEmpty() {
        return this.cookies.isEmpty();
    }

    List<StoredCookie> snapshot() {
        return new ArrayList<StoredCookie>(this.cookies);
    }

    String buildCookieHeader(URI uri, List<String> preferredOrder) {
        ArrayList<StoredCookie> matching = new ArrayList<StoredCookie>();
        for (StoredCookie storedCookie : this.cookies) {
            if (!storedCookie.matches(uri)) continue;
            matching.add(storedCookie);
        }
        if (matching.isEmpty()) {
            return "";
        }
        ArrayList<String> orderedNames = new ArrayList<String>();
        if (preferredOrder != null) {
            orderedNames.addAll(preferredOrder);
        }
        for (StoredCookie storedCookie : matching) {
            if (orderedNames.contains(storedCookie.name)) continue;
            orderedNames.add(storedCookie.name);
        }
        LinkedHashMap<String, String> linkedHashMap = new LinkedHashMap<String, String>();
        for (StoredCookie cookie : matching) {
            linkedHashMap.put(cookie.name, cookie.value);
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (String name : orderedNames) {
            if (!linkedHashMap.containsKey(name)) continue;
            if (stringBuilder.length() > 0) {
                stringBuilder.append("; ");
            }
            stringBuilder.append(name).append('=').append((String)linkedHashMap.get(name));
        }
        return stringBuilder.toString();
    }

    String buildCookieHeader(URI uri) {
        return this.buildCookieHeader(uri, null);
    }

    String buildCookieHeader(List<String> preferredOrder) {
        LinkedHashMap<String, String> flat = this.toFlatMap();
        StringBuilder header = new StringBuilder();
        ArrayList<String> ordered = new ArrayList<String>(preferredOrder);
        for (String name : flat.keySet()) {
            if (ordered.contains(name)) continue;
            ordered.add(name);
        }
        for (String name : ordered) {
            if (!flat.containsKey(name)) continue;
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(name).append('=').append(flat.get(name));
        }
        return header.toString();
    }

    LinkedHashMap<String, String> toFlatMap() {
        LinkedHashMap<String, String> flat = new LinkedHashMap<String, String>();
        for (StoredCookie cookie : this.cookies) {
            flat.put(cookie.name, cookie.value);
        }
        return flat;
    }

    String findValue(String cookieName) {
        for (int i = this.cookies.size() - 1; i >= 0; --i) {
            StoredCookie cookie = this.cookies.get(i);
            if (!cookieName.equals(cookie.name)) continue;
            return cookie.value;
        }
        return null;
    }

    String findMinecraftNetValue(String cookieName) {
        for (int i = this.cookies.size() - 1; i >= 0; --i) {
            String domain;
            StoredCookie cookie = this.cookies.get(i);
            if (!cookieName.equals(cookie.name)) continue;
            String string = domain = cookie.domain == null ? "" : cookie.domain.toLowerCase(Locale.ROOT);
            if (!domain.contains("minecraft.net")) continue;
            return cookie.value;
        }
        return null;
    }

    boolean hasRequiredAuthCookies() {
        LinkedHashMap<String, String> flat = this.toFlatMap();
        return flat.containsKey("__Host-MSAAUTH") || flat.containsKey("__Host-MSAAUTHP") || flat.containsKey("JSH") || flat.containsKey("JSHP");
    }

    String serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("v", (Number)2);
        JsonArray array = new JsonArray();
        for (StoredCookie cookie : this.cookies) {
            JsonObject entry = new JsonObject();
            entry.addProperty("domain", cookie.domain);
            entry.addProperty("path", cookie.path);
            entry.addProperty("name", cookie.name);
            entry.addProperty("value", cookie.value);
            entry.addProperty("secure", Boolean.valueOf(cookie.secure));
            array.add((JsonElement)entry);
        }
        root.add("cookies", (JsonElement)array);
        return GSON.toJson((JsonElement)root);
    }

    static CookieStore deserialize(String serialized) {
        CookieStore store = new CookieStore();
        if (StringUtils.isBlank((CharSequence)serialized)) {
            return store;
        }
        try {
            JsonElement rootElement = new JsonParser().parse(serialized);
            if (!rootElement.isJsonObject()) {
                return store;
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (root.has("cookies") && root.get("cookies").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("cookies")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject entry = element.getAsJsonObject();
                    String name = CookieStore.getString(entry, "name");
                    String value = CookieStore.getString(entry, "value");
                    if (StringUtils.isBlank((CharSequence)name) || StringUtils.isBlank((CharSequence)value)) continue;
                    store.put(CookieStore.getString(entry, "domain"), CookieStore.getString(entry, "path"), name, value, entry.has("secure") && entry.get("secure").getAsBoolean());
                }
                return store;
            }
            for (Map.Entry entry : root.entrySet()) {
                if (!((JsonElement)entry.getValue()).isJsonPrimitive()) continue;
                store.put("", "/", (String)entry.getKey(), ((JsonElement)entry.getValue()).getAsString(), true);
            }
        }
        catch (Exception e) {
            System.err.println("[CookieStore] Failed to deserialize cookies: " + e.getMessage());
        }
        return store;
    }

    static boolean isRelevantDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return true;
        }
        return (domain = domain.toLowerCase(Locale.ROOT)).contains("live.com") || domain.contains("microsoftonline.com") || domain.contains("microsoft.com") || domain.contains("xboxlive.com") || domain.contains("minecraft.net") || domain.contains("mojang.com");
    }

    private static boolean sameSlot(StoredCookie left, StoredCookie right) {
        return CookieStore.eq(left.domain, right.domain) && CookieStore.eq(left.path, right.path) && CookieStore.eq(left.name, right.name);
    }

    private static boolean eq(String left, String right) {
        if (left == null) {
            return right == null || right.isEmpty();
        }
        return left.equals(right);
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }
}

