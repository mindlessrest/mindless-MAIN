package mindless.module.setting.impl;

import com.google.gson.JsonObject;

public class ProfiledSliderSetting extends SliderSetting {
    private final String profileKey;

    public ProfiledSliderSetting(GroupSetting group, String name, String suffix, double value,
                                 double min, double max, double interval, String profileKey) {
        super(group, name, suffix, value, min, max, interval);
        this.profileKey = profileKey;
    }

    public ProfiledSliderSetting(GroupSetting group, String name, int value, String[] options, String profileKey) {
        super(group, name, value, options);
        this.profileKey = profileKey;
    }

    @Override
    public String getProfileKey() {
        return profileKey;
    }

    @Override
    public String[] getProfileKeys() {
        return new String[]{profileKey};
    }

    @Override
    public void loadProfile(JsonObject data) {
        if (data == null || !data.has(profileKey) || !data.get(profileKey).isJsonPrimitive()) {
            return;
        }
        try {
            double value = data.getAsJsonPrimitive(profileKey).getAsDouble();
            if (value == -1) {
                setValueRaw(value);
            }
            else {
                setValueUnclamped(value);
            }
        }
        catch (Exception ignored) {
        }
    }
}
