package keystrokesmod.module.impl.fun;

import keystrokesmod.Raven;
import keystrokesmod.event.PostProfileLoadEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Capes extends Module {
    private static final String MINDLESS_CAPE_FILE = "mindless.png";
    private static final String MINDLESS_CAPE_NAME = "mindless";
    private static final String MINDLESS_CAPE_URL =
            "https://raw.githubusercontent.com/mindlessrest/resources/55d3bc366279c55854b8f3dac3270409f3b1d0c2/cape.png";
    private static Capes instance;
    public static SliderSetting selectedCape;
    public static List<ResourceLocation> loadedCapes = new ArrayList<>();
    private static List<String> capeDisplayNames = new ArrayList<>();

    private ButtonSetting openFolder;
    private ButtonSetting reloadCapes;

    private static final String[] bundledCapes = {
        "anime.png", "rvn_aqua.png", "rvn_green.png", "rvn_purple.png",
        "rvn_red.png", "rvn_white.png", "rvn_yellow.png"
    };

    private static File capeDir;

    public Capes() {
        super("Capes", category.fun);
        instance = this;
        capeDir = new File(mc.mcDataDir + File.separator + "keystrokes", "capes");
        extractBundledCapes();
        List<String> names = buildCapeList();
        this.registerSetting(selectedCape = new SliderSetting("Cape", 0, names.toArray(new String[0])));
        this.registerSetting(openFolder = new ButtonSetting("Open folder", this::openCapeFolder));
        this.registerSetting(reloadCapes = new ButtonSetting("Reload capes", this::reload));
        if (!selectMindlessCape(names)) {
            downloadMindlessCapeAsync();
        }
    }

    private void extractBundledCapes() {
        if (!capeDir.exists()) {
            capeDir.mkdirs();
        }
        for (String name : bundledCapes) {
            File out = new File(capeDir, name);
            if (out.isFile() && out.length() > 0) continue;
            try (InputStream in = getClass().getResourceAsStream("/assets/keystrokesmod/textures/capes/" + name)) {
                if (in != null) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {}
        }
    }

    private List<String> buildCapeList() {
        for (ResourceLocation loadedCape : loadedCapes) {
            if (loadedCape != null) {
                mc.getTextureManager().deleteTexture(loadedCape);
            }
        }
        loadedCapes.clear();
        capeDisplayNames.clear();
        List<String> names = new ArrayList<>();
        names.add("None");
        if (!capeDir.exists() || !capeDir.isDirectory()) return names;
        File[] files = capeDir.listFiles();
        if (files == null) return names;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase().endsWith(".png")) continue;
            try (FileInputStream fis = new FileInputStream(file)) {
                BufferedImage image = ImageIO.read(fis);
                if (image != null) {
                    String displayName = file.getName();
                    if (displayName.endsWith(".png")) {
                        displayName = displayName.substring(0, displayName.length() - 4);
                    }
                    ResourceLocation tex = mc.renderEngine.getDynamicTextureLocation(
                        "cape_" + file.getName(), new DynamicTexture(image));
                    loadedCapes.add(tex);
                    capeDisplayNames.add(displayName);
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
        extractBundledCapes();
        List<String> names = buildCapeList();
        updateSliderOptions(names);
        if (names.size() > 1) {
            Utils.sendMessage("&aLoaded " + (names.size() - 1) + " capes");
        } else {
            Utils.sendMessage("&7No .png capes found. Place them in:");
            Utils.sendMessage("&7" + capeDir.getAbsolutePath());
        }
    }

    private boolean selectMindlessCape(List<String> names) {
        if (selectedCape == null || names == null) return false;
        for (int i = 1; i < names.size(); i++) {
            if (MINDLESS_CAPE_NAME.equalsIgnoreCase(names.get(i))) {
                selectedCape.setValue(i);
                return true;
            }
        }
        return false;
    }

    private void downloadMindlessCapeAsync() {
        Raven.getCachedExecutor().execute(() -> {
            File destination = new File(capeDir, MINDLESS_CAPE_FILE);
            if (isValidCape(destination)) {
                scheduleMindlessCapeRefresh();
                return;
            }
            File temporary = null;
            HttpURLConnection connection = null;
            try {
                if (!capeDir.exists() && !capeDir.mkdirs() && !capeDir.isDirectory()) return;
                connection = (HttpURLConnection) new URL(MINDLESS_CAPE_URL).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Mindless-Client/1.0");
                if (connection.getResponseCode() / 100 != 2) return;
                BufferedImage image;
                try (InputStream input = connection.getInputStream()) {
                    image = ImageIO.read(input);
                }
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;
                temporary = File.createTempFile("mindless-cape-", ".png", capeDir);
                if (!ImageIO.write(image, "png", temporary)) return;
                try {
                    Files.move(temporary.toPath(), destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
                scheduleMindlessCapeRefresh();
            } catch (IOException ignored) {
                // Keep startup offline-safe; a missing cape is retried next launch.
            } finally {
                if (connection != null) connection.disconnect();
                if (temporary != null && temporary.isFile()) temporary.delete();
            }
        });
    }

    private static boolean isValidCape(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        try (InputStream input = new FileInputStream(file)) {
            BufferedImage image = ImageIO.read(input);
            return image != null && image.getWidth() > 0 && image.getHeight() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        selectMindlessCape(currentCapeNames());
    }

    @SubscribeEvent
    public void onProfileLoaded(PostProfileLoadEvent event) {
        selectMindlessCape(currentCapeNames());
    }

    private List<String> currentCapeNames() {
        List<String> names = new ArrayList<>();
        names.add("None");
        names.addAll(capeDisplayNames);
        return names;
    }

    private void scheduleMindlessCapeRefresh() {
        mc.addScheduledTask(() -> {
            List<String> names = buildCapeList();
            updateSliderOptions(names);
            selectMindlessCape(names);
        });
    }

    private void openCapeFolder() {
        try {
            if (!capeDir.exists()) capeDir.mkdirs();
            Desktop.getDesktop().open(capeDir);
        } catch (IOException e) {
            try { Runtime.getRuntime().exec("explorer " + capeDir.getAbsolutePath()); }
            catch (IOException ignored) {}
        }
    }

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
}
