package mindless.module.impl.fun;

import com.google.gson.JsonObject;
import mindless.Raven;
import mindless.backend.BackendClient;
import mindless.event.PostProfileLoadEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Capes extends Module {
    private static final String BACKEND_URL = "https://api.mindless.rest";
    private static final String CAPE_FILENAME = "mindless.png";

    private static Capes instance;
    public static ButtonSetting displaySelf;
    public static ButtonSetting displayOthers;

    // Local player's cape texture
    private static ResourceLocation localCape;

    // Other players' capes: UUID -> ResourceLocation
    private static final ConcurrentHashMap<String, ResourceLocation> remoteCapes = new ConcurrentHashMap<>();

    public Capes() {
        super("Capes", category.fun);
        instance = this;
        this.registerSetting(displaySelf = new ButtonSetting("Display self", true));
        this.registerSetting(displayOthers = new ButtonSetting("Display others", true));
        loadCapeFromServer();
        initBackendListeners();
    }

    // --- Backend Integration ---

    private void initBackendListeners() {
        BackendClient backend = BackendClient.getInstance();

        backend.onConnect(() -> {
            backend.send("cape_query", new Object());
            syncCapeToServer();
        });

        // Receive full state dump (on connect)
        backend.on("cape_state", payload -> {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : payload.entrySet()) {
                String uuid = entry.getKey();
                String cape = entry.getValue().getAsString();
                if (!cape.isEmpty()) {
                    loadRemoteCape(uuid);
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
                loadRemoteCape(uuid);
            }
        });

        backend.connect();
    }

    private void syncCapeToServer() {
        BackendClient backend = BackendClient.getInstance();
        if (!backend.isConnected()) return;

        if (this.isEnabled() && displaySelf.isToggled() && localCape != null) {
            Map<String, String> payload = new HashMap<>();
            payload.put("cape", CAPE_FILENAME);
            backend.send("cape_select", payload);
        } else {
            backend.send("cape_clear", new Object());
        }
    }

    private void loadRemoteCape(String uuid) {
        if (remoteCapes.containsKey(uuid)) return;

        Raven.getCachedExecutor().execute(() -> {
            try {
                BufferedImage image = downloadCapeImage();
                if (image == null) return;
                mc.addScheduledTask(() -> {
                    DynamicTexture texture = new DynamicTexture(image);
                    ResourceLocation loc = mc.renderEngine.getDynamicTextureLocation(
                            "remote_cape_" + uuid, texture);
                    remoteCapes.put(uuid, loc);
                });
            } catch (Exception ignored) {}
        });
    }

    private void loadCapeFromServer() {
        Raven.getCachedExecutor().execute(() -> {
            try {
                BufferedImage image = downloadCapeImage();
                if (image == null) {
                    System.out.println("[Capes] failed to download mindless cape");
                    return;
                }
                mc.addScheduledTask(() -> {
                    DynamicTexture texture = new DynamicTexture(image);
                    localCape = mc.renderEngine.getDynamicTextureLocation("mindless_cape", texture);
                    System.out.println("[Capes] loaded mindless cape");
                    syncCapeToServer();
                });
            } catch (Exception e) {
                System.out.println("[Capes] loadCapeFromServer error: " + e.getMessage());
            }
        });
    }

    private BufferedImage downloadCapeImage() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                BACKEND_URL + "/api/capes/texture/" + CAPE_FILENAME).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return null;
        }
        BufferedImage image;
        try (InputStream in = conn.getInputStream()) {
            image = ImageIO.read(in);
        }
        conn.disconnect();
        return image;
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

    public static ResourceLocation getSelectedCapeTexture() {
        if (instance == null || !instance.isEnabled() || !displaySelf.isToggled()) {
            return null;
        }
        return localCape;
    }

    public static ResourceLocation getCapeForPlayer(String uuid) {
        if (instance == null || !instance.isEnabled() || !displayOthers.isToggled()) return null;
        return remoteCapes.get(uuid);
    }

    public static ResourceLocation getCapeForPlayer(AbstractClientPlayer player) {
        if (instance == null || !instance.isEnabled() || !displayOthers.isToggled()) return null;
        String uuid = player.getUniqueID().toString().replace("-", "");
        return remoteCapes.get(uuid);
    }
}
