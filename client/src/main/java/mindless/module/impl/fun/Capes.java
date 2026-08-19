package mindless.module.impl.fun;

import mindless.Raven;
import mindless.event.PostProfileLoadEvent;
import mindless.module.Module;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
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
import java.util.Iterator;
import java.util.List;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Capes extends Module {
    private static final String MINDLESS_CAPE_FILE = "mindless.png";
    private static final String MINDLESS_CAPE_NAME = "mindless";
    private static final String MINDLESS_CAPE_URL =
            "https://raw.githubusercontent.com/mindlessrest/resources/55d3bc366279c55854b8f3dac3270409f3b1d0c2/cape.png";
    private static Capes instance;
    public static SliderSetting selectedCape;
    public static List<ResourceLocation> loadedCapes = new ArrayList<>();
    private static final List<AnimatedCape> animatedCapes = new ArrayList<>();
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
        capeDir = new File(mc.mcDataDir + File.separator + "mindless", "capes");
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
            try (InputStream in = getClass().getResourceAsStream("/assets/mindless/textures/capes/" + name)) {
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
        animatedCapes.clear();
        capeDisplayNames.clear();
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

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || animatedCapes.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (AnimatedCape cape : animatedCapes) {
            if (cape != null && now >= cape.nextFrameAt) cape.advance(now);
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
