package mindless.module.impl.fun;

import mindless.Raven;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Capes extends Module {
    private static final String CAPE_URL = "https://api.mindless.rest/api/capes/texture/mindless.png";

    private static Capes instance;
    public static ButtonSetting displaySelf;

    private static ResourceLocation localCape;

    public Capes() {
        super("Capes", category.render);
        instance = this;
        this.registerSetting(displaySelf = new ButtonSetting("Display self", true));
        loadCapeFromServer();
    }

    private void loadCapeFromServer() {
        Raven.getCachedExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(CAPE_URL).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }
                BufferedImage image;
                try (InputStream in = conn.getInputStream()) {
                    image = ImageIO.read(in);
                }
                conn.disconnect();
                if (image == null) return;
                mc.addScheduledTask(() -> {
                    DynamicTexture texture = new DynamicTexture(image);
                    localCape = mc.renderEngine.getDynamicTextureLocation("mindless_cape", texture);
                });
            } catch (Exception ignored) {}
        });
    }

    public static ResourceLocation getSelectedCapeTexture() {
        if (instance == null || !instance.isEnabled() || !displaySelf.isToggled()) {
            return null;
        }
        return localCape;
    }

    public static ResourceLocation getCapeForPlayer(String uuid) {
        return null;
    }

    public static ResourceLocation getCapeForPlayer(AbstractClientPlayer player) {
        return null;
    }
}
