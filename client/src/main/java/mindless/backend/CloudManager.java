package mindless.backend;

import com.google.gson.*;
import mindless.Raven;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages cloud profiles/scripts — API client + local subscription tracking.
 */
public class CloudManager {
    private static final String BACKEND_URL = "https://api.mindless.rest";
    private static CloudManager instance;

    private final File cloudFile;
    private final List<SubscribedItem> subscribed = new CopyOnWriteArrayList<>();
    private List<CloudItem> cachedList = new ArrayList<>();
    private List<CloudItem> pendingUpdates = new ArrayList<>();
    private volatile boolean loaded = false;

    public static class CloudItem {
        public String id;
        public String uuid;
        public String name;
        public String type;
        public int version;
        public int downloads;
        public long updatedAt;
    }

    public static class SubscribedItem {
        public String id;
        public String name;
        public String type;
        public int version;
    }

    private CloudManager() {
        Minecraft mc = Minecraft.getMinecraft();
        File mindlessDir = new File(mc.mcDataDir, "mindless");
        if (!mindlessDir.exists()) mindlessDir.mkdirs();
        cloudFile = new File(mindlessDir, "cloud.json");
        loadLocal();
    }

    public static CloudManager getInstance() {
        if (instance == null) {
            instance = new CloudManager();
        }
        return instance;
    }

    // --- Local Storage ---

    private void loadLocal() {
        if (!cloudFile.exists()) {
            loaded = true;
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(cloudFile), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("subscribed");
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    SubscribedItem item = new SubscribedItem();
                    item.id = obj.get("id").getAsString();
                    item.name = obj.has("name") ? obj.get("name").getAsString() : "";
                    item.type = obj.has("type") ? obj.get("type").getAsString() : "";
                    item.version = obj.has("version") ? obj.get("version").getAsInt() : 0;
                    subscribed.add(item);
                }
            }
        } catch (Exception e) {
            System.out.println("[Cloud] failed to load cloud.json: " + e.getMessage());
        }
        loaded = true;
    }

    private void saveLocal() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(cloudFile), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (SubscribedItem item : subscribed) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", item.id);
                obj.addProperty("name", item.name);
                obj.addProperty("type", item.type);
                obj.addProperty("version", item.version);
                arr.add(obj);
            }
            root.add("subscribed", arr);
            writer.write(new Gson().toJson(root));
        } catch (Exception e) {
            System.out.println("[Cloud] failed to save cloud.json: " + e.getMessage());
        }
    }

    // --- API Methods ---

    /**
     * Fetch cloud item list from server. Call on background thread.
     */
    public void refreshList(String type) {
        Raven.getCachedExecutor().execute(() -> {
            try {
                String url = BACKEND_URL + "/api/cloud/list";
                if (type != null && !type.isEmpty()) url += "?type=" + type;
                HttpURLConnection conn = openGet(url);
                if (conn.getResponseCode() == 200) {
                    String json = readResponse(conn);
                    List<CloudItem> items = parseItemList(json);
                    cachedList = items;
                    System.out.println("[Cloud] refreshed list: " + items.size() + " items");
                }
                conn.disconnect();
            } catch (Exception e) {
                System.out.println("[Cloud] refreshList error: " + e.getMessage());
            }
        });
    }

    /**
     * Check for updates to subscribed items. Call on background thread.
     */
    public void checkUpdates() {
        if (subscribed.isEmpty()) return;
        Raven.getCachedExecutor().execute(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray items = new JsonArray();
                for (SubscribedItem sub : subscribed) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", sub.id);
                    obj.addProperty("version", sub.version);
                    items.add(obj);
                }
                body.add("items", items);

                HttpURLConnection conn = openPost(BACKEND_URL + "/api/cloud/check", body.toString());
                if (conn.getResponseCode() == 200) {
                    String json = readResponse(conn);
                    JsonObject resp = new JsonParser().parse(json).getAsJsonObject();
                    JsonArray updates = resp.getAsJsonArray("updates");
                    List<CloudItem> updateList = new ArrayList<>();
                    if (updates != null) {
                        for (JsonElement el : updates) {
                            updateList.add(parseItem(el.getAsJsonObject()));
                        }
                    }
                    pendingUpdates = updateList;
                    System.out.println("[Cloud] " + updateList.size() + " updates available");
                }
                conn.disconnect();
            } catch (Exception e) {
                System.out.println("[Cloud] checkUpdates error: " + e.getMessage());
            }
        });
    }

    /**
     * Download a cloud item blob. Returns raw bytes.
     */
    public byte[] download(String id) {
        try {
            HttpURLConnection conn = openGet(BACKEND_URL + "/api/cloud/download/" + id);
            if (conn.getResponseCode() == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                return data;
            }
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("[Cloud] download error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Subscribe to a cloud item — downloads and saves locally.
     */
    public boolean subscribe(CloudItem item) {
        byte[] data = download(item.id);
        if (data == null) return false;

        // Save locally
        String ext = item.type.equals("script") ? ".java" : ".json";
        String filename = "cloud_" + item.name + ext;
        File dir = getLocalDir(item.type);
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, filename);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data);
        } catch (IOException e) {
            System.out.println("[Cloud] save error: " + e.getMessage());
            return false;
        }

        // Track subscription
        SubscribedItem sub = new SubscribedItem();
        sub.id = item.id;
        sub.name = item.name;
        sub.type = item.type;
        sub.version = item.version;
        subscribed.add(sub);
        saveLocal();
        System.out.println("[Cloud] subscribed to: " + item.name);
        return true;
    }

    /**
     * Update a subscribed item to latest version.
     */
    public boolean update(CloudItem item) {
        byte[] data = download(item.id);
        if (data == null) return false;

        String ext = item.type.equals("script") ? ".java" : ".json";
        String filename = "cloud_" + item.name + ext;
        File dir = getLocalDir(item.type);
        File outFile = new File(dir, filename);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data);
        } catch (IOException e) {
            return false;
        }

        // Update local version
        for (SubscribedItem sub : subscribed) {
            if (sub.id.equals(item.id)) {
                sub.version = item.version;
                break;
            }
        }
        saveLocal();
        pendingUpdates.removeIf(u -> u.id.equals(item.id));
        return true;
    }

    /**
     * Unsubscribe — delete local file and remove tracking.
     */
    public void unsubscribe(String id) {
        SubscribedItem toRemove = null;
        for (SubscribedItem sub : subscribed) {
            if (sub.id.equals(id)) {
                toRemove = sub;
                break;
            }
        }
        if (toRemove == null) return;

        // Delete local file
        String ext = toRemove.type.equals("script") ? ".java" : ".json";
        String filename = "cloud_" + toRemove.name + ext;
        File dir = getLocalDir(toRemove.type);
        File file = new File(dir, filename);
        if (file.exists()) file.delete();

        subscribed.remove(toRemove);
        saveLocal();
        System.out.println("[Cloud] unsubscribed from: " + toRemove.name);
    }

    /**
     * Upload a local file to the cloud.
     */
    public CloudItem upload(String name, String type, byte[] data) {
        try {
            String uuid = Minecraft.getMinecraft().getSession().getPlayerID();
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            body.addProperty("type", type);
            body.addProperty("data", java.util.Base64.getEncoder().encodeToString(data));

            HttpURLConnection conn = openPost(BACKEND_URL + "/api/cloud/upload", body.toString());
            conn.setRequestProperty("X-UUID", uuid);
            // Re-send since openPost already wrote — need to restructure
            conn.disconnect();

            // Redo properly
            conn = (HttpURLConnection) new URL(BACKEND_URL + "/api/cloud/upload").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-UUID", uuid);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String json = readResponse(conn);
                CloudItem item = parseItem(new JsonParser().parse(json).getAsJsonObject());
                conn.disconnect();
                System.out.println("[Cloud] uploaded: " + name);
                return item;
            }
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("[Cloud] upload error: " + e.getMessage());
        }
        return null;
    }

    // --- Getters ---

    public List<CloudItem> getCachedList() { return cachedList; }
    public List<SubscribedItem> getSubscribed() { return new ArrayList<>(subscribed); }
    public List<CloudItem> getPendingUpdates() { return pendingUpdates; }
    public boolean isLoaded() { return loaded; }

    public boolean isSubscribed(String id) {
        for (SubscribedItem sub : subscribed) {
            if (sub.id.equals(id)) return true;
        }
        return false;
    }

    public boolean hasUpdate(String id) {
        for (CloudItem u : pendingUpdates) {
            if (u.id.equals(id)) return true;
        }
        return false;
    }

    public int getLocalVersion(String id) {
        for (SubscribedItem sub : subscribed) {
            if (sub.id.equals(id)) return sub.version;
        }
        return 0;
    }

    // --- Helpers ---

    private File getLocalDir(String type) {
        Minecraft mc = Minecraft.getMinecraft();
        String subdir = type.equals("script") ? "scripts" : "profiles";
        return new File(mc.mcDataDir + File.separator + "mindless", subdir);
    }

    private List<CloudItem> parseItemList(String json) {
        List<CloudItem> result = new ArrayList<>();
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("items");
        if (arr == null) return result;
        for (JsonElement el : arr) {
            result.add(parseItem(el.getAsJsonObject()));
        }
        return result;
    }

    private CloudItem parseItem(JsonObject obj) {
        CloudItem item = new CloudItem();
        item.id = obj.has("id") ? obj.get("id").getAsString() : "";
        item.uuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "";
        item.name = obj.has("name") ? obj.get("name").getAsString() : "";
        item.type = obj.has("type") ? obj.get("type").getAsString() : "";
        item.version = obj.has("version") ? obj.get("version").getAsInt() : 1;
        item.downloads = obj.has("downloads") ? obj.get("downloads").getAsInt() : 0;
        item.updatedAt = obj.has("updated_at") ? obj.get("updated_at").getAsLong() : 0;
        return item;
    }

    private HttpURLConnection openGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private HttpURLConnection openPost(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return new String(readBytes(in), StandardCharsets.UTF_8);
        }
    }

    private byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
