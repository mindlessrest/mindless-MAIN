package mindless.module.setting.impl;

import com.google.gson.JsonObject;
import mindless.event.PostSetSliderEvent;
import mindless.module.setting.Setting;
import net.minecraftforge.common.MinecraftForge;


public class SliderSetting extends Setting {
    private String settingName;
    private String[] options = null;
    private double defaultValue;
    private double max;
    private double min;
    private double intervals;
    public boolean isString;
    private String suffix = "";
    public boolean canBeDisabled;
    public GroupSetting groupSetting;
    private String[] legacyProfileKeys;
    private String minString;
    private double capturedDefault;

    public SliderSetting(GroupSetting groupSetting, String settingName, double defaultValue, double min, double max, double intervals) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.settingName = settingName;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.intervals = intervals;
        this.isString = false;
        this.legacyProfileKeys = new String[0];
    }

    public SliderSetting(String settingName, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
    }

    public SliderSetting(String settingName, double defaultValue, double min, double max,
                         double intervals, String... legacyProfileKeys) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this(groupSetting, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, defaultValue, min, max, intervals);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting group, String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(group, settingName, defaultValue, min, max, intervals);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(String settingName, String suffix, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, int defaultValue, String[] options) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.settingName = settingName;
        this.options = options;
        this.defaultValue = defaultValue;
        this.min = 0;
        this.max = options.length - 1;
        this.intervals = 1;
        this.isString = true;
        this.legacyProfileKeys = new String[0];
    }

    public SliderSetting(String settingName, int defaultValue, String[] options) {
        this((GroupSetting) null, settingName, defaultValue, options);
    }

    public SliderSetting(String settingName, int defaultValue, String[] options, String... legacyProfileKeys) {
        this((GroupSetting) null, settingName, defaultValue, options);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(String settingName, String suffix, int defaultValue, String[] options) {
        this((GroupSetting) null, settingName, defaultValue, options);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, String suffix, String minString, double defaultValue, double min, double max, double interval) {
        this(settingName, suffix, defaultValue, min, max, interval);
        this.minString = minString;
    }

    public SliderSetting(String settingName, boolean canBeDisabled, int defaultValue, String[] options) {
        this(settingName, defaultValue, options);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, int defaultValue, String[] options) {
        this(groupSetting, settingName, defaultValue, options);
        this.suffix = suffix;
    }

    public String getSuffix() {
        return this.suffix;
    }

    public String getMinString() {
        return this.minString;
    }

    public String[] getOptions() {
        return options;
    }

    public String getName() {
        return this.settingName;
    }

    @Override
    public String getProfileKey() {
        return groupSetting == null ? getName() : groupSetting.getName() + "." + getName();
    }

    @Override
    public String[] getProfileKeys() {
        String[] keys = new String[2 + legacyProfileKeys.length];
        keys[0] = getProfileKey();
        keys[1] = getName();
        System.arraycopy(legacyProfileKeys, 0, keys, 2, legacyProfileKeys.length);
        return keys;
    }

    @Override
    protected void captureDefault() {
        capturedDefault = defaultValue;
    }

    @Override
    public void resetToDefault() {
        this.defaultValue = capturedDefault;
    }

    public double getInput() {
        return roundToInterval(this.defaultValue, 4);
    }

    public double getMin() {
        return this.min;
    }

    public double getMax() {
        return this.max;
    }

    public double getInterval() {
        return this.intervals;
    }

    public double setValue(double newValue) {
        newValue = correctValue(newValue, this.min, this.max);
        newValue = (double) Math.round(newValue * (1.0D / this.intervals)) / (1.0D / this.intervals);
        return this.defaultValue = newValue;
    }

    public double setValueUnclamped(double newValue) {
        newValue = (double) Math.round(newValue * (1.0D / this.intervals)) / (1.0D / this.intervals);
        return this.defaultValue = newValue;
    }

    public void setValueUnclampedWithEvent(double newValue) {
        double prev = this.defaultValue;
        MinecraftForge.EVENT_BUS.post(new PostSetSliderEvent(prev, this.setValueUnclamped(newValue)));
    }

    public void setValueWithEvent(double newValue) {
        double prev = this.defaultValue;
        MinecraftForge.EVENT_BUS.post(new PostSetSliderEvent(prev, this.setValue(newValue)));
    }

    public void setValueRaw(double n) {
        this.defaultValue = n;
    }

    public void setValueRawWithEvent(double n) {
        double prev = this.defaultValue;
        this.defaultValue = n;
        MinecraftForge.EVENT_BUS.post(new PostSetSliderEvent(prev, n));
    }

    public static double correctValue(double v, double i, double a) {
        v = Math.max(i, v);
        v = Math.min(a, v);
        return v;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }
private static final double[] POWERS_OF_TEN = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9
    };
public static double roundToInterval(double v, int p) {
        if (p < 0) {
            return 0.0D;
        }
        if (p >= POWERS_OF_TEN.length || Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }

        double scale = POWERS_OF_TEN[p];
        double scaled = v * scale;
        if (scaled <= -9.007199254740992E15 || scaled >= 9.007199254740992E15) {
            return v;
        }

        double rounded = scaled < 0.0
                ? -Math.floor(-scaled + 0.5)
                : Math.floor(scaled + 0.5);
        return rounded / scale;
    }

    @Override
    public void loadProfile(JsonObject data) {
        String profileKey = getProfileKey();
        String legacyKey = getName();
        String key = null;
        if (data.has(profileKey)) {
            key = profileKey;
        }
        else if (data.has(legacyKey)) {
            key = legacyKey;
        }
        else {
            for (String legacyProfileKey : legacyProfileKeys) {
                if (data.has(legacyProfileKey)) {
                    key = legacyProfileKey;
                    break;
                }
            }
        }
        if (key != null && data.has(key) && data.get(key).isJsonPrimitive()) {
            double newValue = defaultValue;
            try {
                newValue = data.getAsJsonPrimitive(key).getAsDouble();
            }
            catch (Exception e) {

            }
            if (newValue == -1) {
                setValueRaw(newValue);
                return;
            }
            setValueUnclamped(newValue);
        }
    }
}
