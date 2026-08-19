package mindless.module.impl.fun;

import com.google.gson.JsonObject;
import mindless.Raven;
import mindless.backend.BackendClient;
import mindless.event.PostProfileLoadEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Capes extends Module {
    private static final String BACKEND_URL = "https://api.mindless.rest";

    private static Capes instance;
    public static SliderSetting selectedCape;
    public static List<ResourceLocation> loadedCapes = new ArrayList<>();
    private static final List<AnimatedCape> animatedCapes = new ArrayList<>();
    private static List<String> capeDisplayNames = new ArrayList<>();
    private static List<String> capeFileNames = new ArrayList<>();

    // Other players' capes: UUID -> ResourceLocation
    private static final ConcurrentHashMap<String, ResourceLocation> remoteCapes = new ConcurrentHashMap<>();
    // Cache downloaded textures: filename -> ResourceLocation
    private static final ConcurrentHashMap<String, ResourceLocation> textureCache = new ConcurrentHashMap<>();

    private ButtonSetting openFolder;
    private ButtonSetting reloadCapes;

    private static File capeDir;
    private int lastSelectedIndex = -1;

    public Capes() {
        super("Capes", category.fun);
        instance = this;
        capeDir = new File(mc.mcDataDir + File.separator + "mindless", "capes");
        if (!capeDir.exists()) capeDir.mkdirs();
        List<String> names = buildCapeList();
        this.registerSetting(selectedCape = new SliderSetting("Cape", 0, names.toArray(new String[0])));
        this.registerSetting(openFolder = new ButtonSetting("Open folder", this::openCapeFolder));
        this.registerSetting(reloadCapes = new ButtonSetting("Reload capes", this::reload));
        fetchServerCapeList();
        initBackendListeners();
    }

    // --- Backend Integration ---

    private void initBackendListeners() {
        BackendClient backend = BackendClient.getInstance();

        // When connected, query all online players' capes
        backend.onConnect(() -> {
            backend.send("cape_query", new Object());
            // Send our current selection
            syncCapeToServer();
        });

        // Receive full state dump (on connect)
        backend.on("cape_state", payload -> {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : payload.entrySet()) {
                String uuid = entry.getKey();
                String cape = entry.getValue().getAsString();
                if (!cape.isEmpty()) {
                    loadRemoteCape(uuid, cape);
                }
            }
        });

        // Receive individual cape updates
        backend.on("cape_update", payload -> {
            String uuid = payload.get("uuid").getAsString();
            String cape = payload.get("cape").getAsString();
            if (cape.isEmpty()) {
                remoteCapes.remove(uuid);
            } else {
                loadRemoteCape(uuid, cape);
            }
        });

        // Connect if not already
        backend.connect();
    }

    /**
     * Sync currently selected cape to the backend server.
     */
    private void syncCapeToServer() {
        BackendClient backend = BackendClient.getInstance();
        if (!backend.isConnected()) return;

        int index = selectedCape != null ? (int) selectedCape.getInput() : 0;
        if (index <= 0 || !this.isEnabled()) {
            backend.send("cape_clear", new Object());
        } else {
            int fileIndex = index - 1;
            if (fileIndex >= 0 && fileIndex < capeFileNames.size()) {
                String filename = capeFileNames.get(fileIndex);
                Map<String, String> payload = new HashMap<>();
                payload.put("cape", filename);
                backend.send("cape_select", payload);
                // Upload cape to server if it's a custom one
                uploadCapeIfNeeded(filename);
            }
        }
    }

    /**
     * Upload cape texture to the backend server (deduplication handled server-side).
     */
    private void uploadCapeIfNeeded(String filename) {
        Raven.getCachedExecutor().execute(() -> {
            File file = new File(capeDir, filename);
            if (!file.isFile()) return;
            try {
                byte[] data = readFileBytes(file);
                HttpURLConnection conn = (HttpURLConnection) new URL(BACKEND_URL + "/api/capes/upload").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("X-Filename", filename);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data);
                }
                conn.getResponseCode(); // consume response
                conn.disconnect();
            } catch (IOException ignored) {}
        });
    }

    /**
     * Download and register a remote player's cape texture.
     */
    private void loadRemoteCape(String uuid, String filename) {
        // Check if already cached
        if (textureCache.containsKey(filename)) {
            remoteCapes.put(uuid, textureCache.get(filename));
            return;
        }

        Raven.getCachedExecutor().execute(() -> {
            try {
                // First check local cache
                File local = new File(capeDir, filename);
                BufferedImage image;
                if (local.isFile()) {
                    image = ImageIO.read(local);
                } else {
                    // Download from backend
                    HttpURLConnection conn = (HttpURLConnection) new URL(
                            BACKEND_URL + "/api/capes/texture/" + filename).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    if (conn.getResponseCode() != 200) {
                        conn.disconnect();
                        return;
                    }
                    try (InputStream in = conn.getInputStream()) {
                        image = ImageIO.read(in);
                    }
                    conn.disconnect();
                }

                if (image == null) return;

                // Register texture on main thread
                BufferedImage finalImage = image;
                mc.addScheduledTask(() -> {
                    DynamicTexture texture = new DynamicTexture(finalImage);
                    ResourceLocation loc = mc.renderEngine.getDynamicTextureLocation(
                            "remote_cape_" + filename, texture);
                    textureCache.put(filename, loc);
                    remoteCapes.put(uuid, loc);
                });
            } catch (IOException ignored) {}
        });
    }

    /**
     * Fetch available capes from server and merge with local.
     */
    private void fetchServerCapeList() {
        Raven.getCachedExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        BACKEND_URL + "/api/capes/list").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream in = conn.getInputStream()) {
                        // Download any capes we don't have locally
                        String json = new String(readAllBytes(in));
                        // Simple parse — look for filenames
                        // Full JSON parsing would be cleaner but keeping deps minimal
                        for (String name : extractCapeNames(json)) {
                            File local = new File(capeDir, name);
                            if (!local.exists()) {
                                downloadCapeToLocal(name);
                            }
                        }
                    }
                }
                conn.disconnect();
                // Refresh list on main thread
                mc.addScheduledTask(() -> {
                    List<String> names = buildCapeList();
                    updateSliderOptions(names);
                });
            } catch (IOException ignored) {}
        });
    }

    private void downloadCapeToLocal(String filename) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    BACKEND_URL + "/api/capes/texture/" + filename).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    File out = new File(capeDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
            }
            conn.disconnect();
        } catch (IOException ignored) {}
    }

    private List<String> extractCapeNames(String json) {
        List<String> names = new ArrayList<>();
        // Parse "capes":["file1.png","file2.gif",...]
        int idx = json.indexOf("\"capes\"");
        if (idx == -1) return names;
        int start = json.indexOf('[', idx);
        int end = json.indexOf(']', start);
        if (start == -1 || end == -1) return names;
        String arr = json.substring(start + 1, end);
        for (String part : arr.split(",")) {
            String name = part.trim().replace("\"", "");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    // --- Local Cape Loading (unchanged logic) ---

    private List<String> buildCapeList() {
        for (ResourceLocation loadedCape : loadedCapes) {
            if (loadedCape != null) {
                mc.getTextureManager().deleteTexture(loadedCape);
            }
        }
        loadedCapes.clear();
        animatedCapes.clear();
        capeDisplayNames.clear();
        capeFileNames.clear();
        List<String> names = new ArrayList<>();
        names.add("None");
        if (!capeDir.exists() || !capeDir.isDirectory()) return names;
        File[] files = capeDir.listFiles();
        if (files == null) return names;
        for (File file : files) {
            if (!file.isFile()) continue;
            String lowerName = file.getName().toLowerCase();
            if (!lowerName.endsWith(".png") && !lowerName.endsWith(".gif")) continue;
            try {
                AnimatedCape animated = lowerName.endsWith(".gif") ? loadAnimatedCape(file) : null;
                BufferedImage image = animated == null ? readStaticCape(file) : animated.frames.get(0);
                if (image != null && image.getWidth() == image.getHeight() * 2) {
                    String displayName = file.getName();
                    displayName = displayName.substring(0, displayName.lastIndexOf('.'));
                    DynamicTexture texture = new DynamicTexture(image);
                    ResourceLocation tex = mc.renderEngine.getDynamicTextureLocation(
                            "cape_" + file.getName(), texture);
                    loadedCapes.add(tex);
                    animatedCapes.add(animated == null ? null : animated.withTexture(tex));
                    capeDisplayNames.add(displayName);
                    capeFileNames.add(file.getName());
                    names.add(displayName);
                }
            } catch (IOException e) {
                Utils.sendMessage("&cFailed to load: " + file.getName());
            }
        }
        return names;
    }

    private void updateSliderOptions(List<String> names) {
        if (selectedCape == null) return;
        try {
            Field optionsField = SliderSetting.class.getDeclaredField("options");
            optionsField.setAccessible(true);
            Field maxField = SliderSetting.class.getDeclaredField("max");
            maxField.setAccessible(true);
            int currentValue = (int) selectedCape.getInput();
            if (currentValue >= names.size()) {
                selectedCape.setValue(0);
            }
            optionsField.set(selectedCape, names.toArray(new String[0]));
            maxField.setDouble(selectedCape, names.size() - 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reload() {
        fetchServerCapeList();
        List<String> names = buildCapeList();
        updateSliderOptions(names);
        if (names.size() > 1) {
            Utils.sendMessage("&aLoaded " + (names.size() - 1) + " capes");
        } else {
            Utils.sendMessage("&7No .png capes found. Place them in:");
            Utils.sendMessage("&7" + capeDir.getAbsolutePath());
        }
    }

    // --- Tick: animate + detect selection changes ---

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Animate GIF capes
        if (!animatedCapes.isEmpty()) {
            long now = System.currentTimeMillis();
            for (AnimatedCape cape : animatedCapes) {
                if (cape != null && now >= cape.nextFrameAt) cape.advance(now);
            }
        }

        // Detect cape selection change and sync to server
        if (selectedCape != null) {
            int current = (int) selectedCape.getInput();
            if (current != lastSelectedIndex) {
                lastSelectedIndex = current;
                syncCapeToServer();
            }
        }
    }

    @Override
    public void onEnable() {
        syncCapeToServer();
    }

    @Override
    public void onDisable() {
        BackendClient backend = BackendClient.getInstance();
        if (backend.isConnected()) {
            backend.send("cape_clear", new Object());
        }
    }

    @SubscribeEvent
    public void onProfileLoaded(PostProfileLoadEvent event) {
        syncCapeToServer();
    }

    // --- Public API for renderer ---

    /**
     * Get the cape texture for the local player.
     */
    public static ResourceLocation getSelectedCapeTexture() {
        if (instance == null || !instance.isEnabled() || selectedCape == null
                || selectedCape.getInput() <= 0 || loadedCapes.isEmpty()) {
            return null;
        }
        int index = (int) (selectedCape.getInput() - 1);
        if (index >= 0 && index < loadedCapes.size()) {
            return loadedCapes.get(index);
        }
        return null;
    }

    /**
     * Get the cape texture for another player (by UUID).
     * Called from the player renderer to render other Mindless users' capes.
     */
    public static ResourceLocation getCapeForPlayer(String uuid) {
        if (instance == null || !instance.isEnabled()) return null;
        return remoteCapes.get(uuid);
    }

    /**
     * Get the cape texture for a player entity.
     * Convenience method for the renderer.
     */
    public static ResourceLocation getCapeForPlayer(AbstractClientPlayer player) {
        if (instance == null || !instance.isEnabled()) return null;
        String uuid = player.getUniqueID().toString().replace("-", "");
        return remoteCapes.get(uuid);
    }

    // --- Utility ---

    private void openCapeFolder() {
        try {
            if (!capeDir.exists()) capeDir.mkdirs();
            Desktop.getDesktop().open(capeDir);
        } catch (IOException e) {
            try { Runtime.getRuntime().exec("explorer " + capeDir.getAbsolutePath()); }
            catch (IOException ignored) {}
        }
    }

    private static BufferedImage readStaticCape(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return ImageIO.read(fis);
        }
    }

    private static AnimatedCape loadAnimatedCape(File file) throws IOException {
        List<BufferedImage> frames = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) throw new IOException("unable to read GIF");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("no GIF reader");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count = Math.min(reader.getNumImages(true), 120);
                for (int i = 0; i < count; i++) {
                    BufferedImage frame = reader.read(i);
                    if (frame == null || frame.getWidth() != frame.getHeight() * 2) continue;
                    frames.add(frame);
                    delays.add(readFrameDelay(reader.getImageMetadata(i)));
                }
            } finally {
                reader.dispose();
            }
        }
        if (frames.isEmpty()) throw new IOException("GIF has no valid cape frames");
        return new AnimatedCape(frames, delays);
    }

    private static long readFrameDelay(IIOMetadata metadata) {
        try {
            org.w3c.dom.Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            org.w3c.dom.Node node = root.getFirstChild();
            while (node != null) {
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    org.w3c.dom.NamedNodeMap attributes = node.getAttributes();
                    org.w3c.dom.Node delay = attributes.getNamedItem("delayTime");
                    long millis = delay == null ? 100L : Long.parseLong(delay.getNodeValue()) * 10L;
                    return Math.max(30L, millis);
                }
                node = node.getNextSibling();
            }
        } catch (Exception ignored) {}
        return 100L;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readAllBytes(fis);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // --- Inner class ---

    private static final class AnimatedCape {
        final List<BufferedImage> frames;
        final List<Long> delays;
        ResourceLocation textureLocation;
        int frame;
        long nextFrameAt;

        AnimatedCape(List<BufferedImage> frames, List<Long> delays) {
            this.frames = frames;
            this.delays = delays;
            this.nextFrameAt = System.currentTimeMillis() + delays.get(0);
        }

        AnimatedCape withTexture(ResourceLocation textureLocation) {
            this.textureLocation = textureLocation;
            return this;
        }

        void advance(long now) {
            frame = (frame + 1) % frames.size();
            Minecraft.getMinecraft().getTextureManager().deleteTexture(textureLocation);
            Minecraft.getMinecraft().getTextureManager().loadTexture(textureLocation,
                    new DynamicTexture(frames.get(frame)));
            nextFrameAt = now + delays.get(frame);
        }
    }
}
