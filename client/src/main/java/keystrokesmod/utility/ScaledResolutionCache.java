package keystrokesmod.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/** Reuses the immutable scaled-resolution calculation during normal rendering. */
public final class ScaledResolutionCache {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static ScaledResolution cached;
    private static int displayWidth = -1;
    private static int displayHeight = -1;
    private static int guiScale = Integer.MIN_VALUE;
    private static boolean unicode;

    private ScaledResolutionCache() {
    }

    public static ScaledResolution get() {
        int currentWidth = mc.displayWidth;
        int currentHeight = mc.displayHeight;
        int currentGuiScale = mc.gameSettings == null ? 0 : mc.gameSettings.guiScale;
        boolean currentUnicode = mc.isUnicode();
        if (cached == null || displayWidth != currentWidth || displayHeight != currentHeight
                || guiScale != currentGuiScale || unicode != currentUnicode) {
            cached = new ScaledResolution(mc);
            displayWidth = currentWidth;
            displayHeight = currentHeight;
            guiScale = currentGuiScale;
            unicode = currentUnicode;
        }
        return cached;
    }
}
