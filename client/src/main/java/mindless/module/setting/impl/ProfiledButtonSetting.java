package mindless.module.setting.impl;

import com.google.gson.JsonObject;

public class ProfiledButtonSetting extends ButtonSetting {
    private final String profileKey;

    public ProfiledButtonSetting(GroupSetting group, String name, boolean value, String profileKey) {
        super(group, name, value);
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
            setEnabled(data.getAsJsonPrimitive(profileKey).getAsBoolean());
        }
        catch (Exception ignored) {
        }
    }
}
