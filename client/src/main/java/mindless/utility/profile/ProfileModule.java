package mindless.utility.profile;

import mindless.Raven;
import mindless.clickgui.ClickGui;
import mindless.module.Module;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.Utils;

public class ProfileModule extends Module {
    private final Profile profile;
    private final TextSetting profileNameSetting;
    private String displayName;
    public boolean saved = true;

    public ProfileModule(Profile profile, String name, int bind) {
        super(name, category.profiles, bind);
        this.profile = profile;
        this.displayName = name;
        this.registerSetting(new ButtonSetting("Update profile", () -> {
            Raven.profileManager.saveProfile(this.profile);
            saved = true;
            Utils.sendMessage("&7Updated profile: &b" + getName());
        }));
        this.registerSetting(profileNameSetting = new TextSetting("Profile name", name, "Type a new name...", 32, this::renameProfile));
        this.registerSetting(new ButtonSetting("Delete profile", () -> {
            String profileName = getName();
            if (Raven.profileManager.deleteProfile(profileName)) {
                Utils.sendMessage("&7Deleted profile: &b" + profileName);
            }
        }));
        ignoreOnSave = true;
    }

    @Override
    public void toggle() {
        if (mc.currentScreen instanceof ClickGui || mc.currentScreen == null) {
            Raven.profileManager.loadProfile(this.getName());

            Raven.currentProfile = profile;

            if (Settings.sendMessage.isToggled()) {
                Utils.sendMessage("&7Enabled profile: &b" + this.getName());
            }
            saved = true;
        }
    }

    @Override
    public boolean isEnabled() {
        if (Raven.currentProfile == null) {
            return false;
        }
        return Raven.currentProfile.getModule() == this;
    }

    @Override
    public String getName() {
        return displayName;
    }

    public void setProfileName(String profileName) {
        this.displayName = profileName;
        profileNameSetting.setText(profileName);
    }

    private void renameProfile() {
        if (Raven.profileManager == null) {
            return;
        }

        String oldName = getName();
        if (Raven.profileManager.renameProfile(profile, profileNameSetting.getText())) {
            profileNameSetting.setText(profile.getName());
            if (!oldName.equals(profile.getName())) {
                Utils.sendMessage("&7Renamed profile: &b" + oldName + " &7to &b" + profile.getName());
            }
        }
    }
}
