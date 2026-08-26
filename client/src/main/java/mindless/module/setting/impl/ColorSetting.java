package mindless.module.setting.impl;

import com.google.gson.JsonObject;
import mindless.module.setting.Setting;

import java.awt.Color;

public class ColorSetting extends Setting {
    private int red;
    private int green;
    private int blue;
    private int alpha;
    private final boolean hasAlpha;
    public GroupSetting groupSetting;

    /**
     * The hue and saturation the picker was last set to, kept because red, green and blue cannot
     * always answer for them.
     *
     * <p>Black has no hue and grey has no saturation -- there is nothing in the numbers to recover
     * them from, so converting back returns zero for both. That is why the picker forgot where it
     * was: drag the brightness to the bottom, close the menu, reopen it, and the handle had jumped
     * to red because red is what zero means. Remembering what was picked, and only trusting the
     * conversion when the colour actually carries the answer, keeps the handle where it was left.
     */
    private float pickedHue = Float.NaN;
    private float pickedSaturation = Float.NaN;

    public ColorSetting(String name, int red, int green, int blue) {
        this(null, name, red, green, blue, 255, false);
    }

    public ColorSetting(String name, int red, int green, int blue, int alpha) {
        this(null, name, red, green, blue, alpha, true);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue) {
        this(groupSetting, name, red, green, blue, 255, false);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue, int alpha) {
        this(groupSetting, name, red, green, blue, alpha, true);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue, int alpha, boolean hasAlpha) {
        super(name);
        this.groupSetting = groupSetting;
        this.red = clamp(red);
        this.green = clamp(green);
        this.blue = clamp(blue);
        this.alpha = clamp(alpha);
        this.hasAlpha = hasAlpha;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int getAlpha() {
        return alpha;
    }

    public void setAlpha(int alpha) {
        this.alpha = clamp(alpha);
    }

    public void setColor(int r, int g, int b) {
        this.red = clamp(r);
        this.green = clamp(g);
        this.blue = clamp(b);
        // Set from outside the picker, so whatever it was remembering no longer applies.
        this.pickedHue = Float.NaN;
        this.pickedSaturation = Float.NaN;
    }

    public void setColor(int r, int g, int b, int a) {
        setColor(r, g, b);
        this.alpha = clamp(a);
    }

    /** ARGB packed int. */
    public int getColor() {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** RGB packed int (no alpha). */
    public int getRGB() {
        return (red << 16) | (green << 8) | blue;
    }

    public float getHue() {
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        // A colour with no saturation has no hue to read; fall back on the one that was chosen.
        if (hsb[1] <= 0.001f && !Float.isNaN(pickedHue)) {
            return pickedHue;
        }
        return hsb[0] * 360f;
    }

    public float getSaturation() {
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        // Likewise black: every saturation looks the same at zero brightness.
        if (hsb[2] <= 0.001f && !Float.isNaN(pickedSaturation)) {
            return pickedSaturation;
        }
        return hsb[1];
    }

    public float getBrightness() {
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        return hsb[2];
    }

    /** @param hueDegrees 0-360 */
    public void setFromHSB(float hueDegrees, float saturation, float brightness) {
        this.pickedHue = ((hueDegrees % 360f) + 360f) % 360f;
        this.pickedSaturation = Math.max(0f, Math.min(1f, saturation));
        int rgb = Color.HSBtoRGB(hueDegrees / 360f,
                Math.max(0f, Math.min(1f, saturation)),
                Math.max(0f, Math.min(1f, brightness)));
        this.red = (rgb >> 16) & 0xFF;
        this.green = (rgb >> 8) & 0xFF;
        this.blue = rgb & 0xFF;
    }

    public void setHue(float hueDegrees) {
        setFromHSB(hueDegrees, getSaturation(), getBrightness());
    }

    public void setSaturation(float saturation) {
        setFromHSB(getHue(), saturation, getBrightness());
    }

    public void setBrightness(float brightness) {
        setFromHSB(getHue(), getSaturation(), brightness);
    }

    public boolean hasAlpha() {
        return hasAlpha;
    }

    @Override
    public String getProfileKey() {
        return groupSetting == null ? getName() : groupSetting.getName() + "." + getName();
    }

    @Override
    public void loadProfile(JsonObject data) {
        String profileKey = getProfileKey();
        String legacyKey = getName();
        String key = data.has(profileKey) ? profileKey : legacyKey;
        if (data.has(key) && data.get(key).isJsonPrimitive()) {
            try {
                String value = data.getAsJsonPrimitive(key).getAsString();
                String[] parts = value.split(",");
                if (parts.length >= 3) {
                    this.red = clamp(Integer.parseInt(parts[0].trim()));
                    this.green = clamp(Integer.parseInt(parts[1].trim()));
                    this.blue = clamp(Integer.parseInt(parts[2].trim()));
                    if (parts.length >= 4) {
                        this.alpha = clamp(Integer.parseInt(parts[3].trim()));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
