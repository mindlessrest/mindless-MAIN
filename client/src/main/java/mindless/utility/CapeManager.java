package mindless.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

public final class CapeManager {
    private static final long MAX_CAPE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CAPE_DIMENSION = 8192;
    private static final BundledCape[] BUNDLED_CAPES = {
            new BundledCape("Anime", "anime.png"),
            new BundledCape("Aqua", "rvn_aqua.png"),
            new BundledCape("Green", "rvn_green.png"),
            new BundledCape("Purple", "rvn_purple.png"),
            new BundledCape("Red", "rvn_red.png"),
            new BundledCape("White", "rvn_white.png"),
            new BundledCape("Yellow", "rvn_yellow.png")
    };
    private static final File CAPE_DIRECTORY = new File(
            new File(Minecraft.getMinecraft().mcDataDir, "mindless"), "capes");
    private static volatile List<ResourceLocation> capeTextures = Collections.emptyList();
    private static volatile List<ResourceLocation> customCapeTextures = Collections.emptyList();
    private static volatile String[] capeOptions = new String[]{"None"};

    private CapeManager() {}

    public static File getCapeDirectory() { ensureCapeDirectory(); return CAPE_DIRECTORY; }

    public static boolean openCapeDirectory() {
        ensureCapeDirectory();
        try {
            if (!Desktop.isDesktopSupported()) return false;
            Desktop.getDesktop().open(CAPE_DIRECTORY);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public static String[] getCapeOptions() { return capeOptions.clone(); }

    public static ResourceLocation getCape(int optionIndex) {
        List<ResourceLocation> current = capeTextures;
        int idx = optionIndex - 1;
        if (idx < 0 || idx >= current.size()) return null;
        return current.get(idx);
    }

    public static synchronized ReloadResult reloadCustomCapes() {
        ensureCapeDirectory();
        deleteOldCustomTextures();
        List<String> names = new ArrayList<>();
        List<ResourceLocation> textures = new ArrayList<>();
        List<ResourceLocation> dynamic = new ArrayList<>();
        names.add("None");
        for (BundledCape c : BUNDLED_CAPES) {
            names.add(c.displayName);
            textures.add(new ResourceLocation("mindless", "textures/capes/" + c.fileName));
        }
        int loaded = 0, failed = 0;
        File[] files = CAPE_DIRECTORY.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (file.length() <= 0 || file.length() > MAX_CAPE_BYTES) { failed++; continue; }
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (!isValidCape(img)) { failed++; continue; }
                    String name = makeDisplayName(file, names);
                    ResourceLocation tex = Minecraft.getMinecraft().getTextureManager()
                            .getDynamicTextureLocation("custom_cape_" + loaded, new DynamicTexture(img));
                    names.add(name); textures.add(tex); dynamic.add(tex); loaded++;
                } catch (Throwable ignored) { failed++; }
            }
        }
        capeTextures = Collections.unmodifiableList(textures);
        customCapeTextures = Collections.unmodifiableList(dynamic);
        capeOptions = names.toArray(new String[0]);
        return new ReloadResult(loaded, failed);
    }

    private static boolean isValidCape(BufferedImage img) {
        return img != null && img.getWidth() > 0 && img.getHeight() > 0
                && img.getWidth() <= MAX_CAPE_DIMENSION && img.getHeight() <= MAX_CAPE_DIMENSION
                && img.getWidth() == img.getHeight() * 2;
    }

    private static String makeDisplayName(File file, List<String> existing) {
        String base = file.getName().replaceAll("\\.[^.]+$", "");
        if (!containsIgnoreCase(existing, base)) return base;
        String name = file.getName(); int suffix = 2;
        while (containsIgnoreCase(existing, name)) name = file.getName() + " (" + suffix++ + ")";
        return name;
    }

    private static boolean containsIgnoreCase(List<String> list, String val) {
        for (String s : list) if (s.equalsIgnoreCase(val)) return true;
        return false;
    }

    private static void deleteOldCustomTextures() {
        for (ResourceLocation r : customCapeTextures) Minecraft.getMinecraft().getTextureManager().deleteTexture(r);
        customCapeTextures = Collections.emptyList();
    }

    private static void ensureCapeDirectory() { if (!CAPE_DIRECTORY.exists()) CAPE_DIRECTORY.mkdirs(); }

    public static final class ReloadResult {
        public final int loaded, failed;
        ReloadResult(int l, int f) { loaded = l; failed = f; }
    }

    private static final class BundledCape {
        final String displayName, fileName;
        BundledCape(String d, String f) { displayName = d; fileName = f; }
    }
}
