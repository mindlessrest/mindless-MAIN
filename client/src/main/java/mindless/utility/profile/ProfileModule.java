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
        this.registerSetting(new ButtonSetting("Upload to Cloud", () -> {
            Raven.getCachedExecutor().execute(() -> {
                try {
                    java.io.File profileDir = new java.io.File(mc.mcDataDir + java.io.File.separator + "mindless", "profiles");
                    java.io.File file = new java.io.File(profileDir, getName() + ".json");
                    if (!file.exists()) {
                        Utils.sendMessage("&cProfile file not found");
                        return;
                    }
                    byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                    mindless.backend.CloudManager.CloudItem result = mindless.backend.CloudManager.getInstance().upload(getName(), "profile", data);
                    if (result != null) {
                        Utils.sendMessage("&aUploaded profile to cloud: &b" + getName());
                        mindless.backend.CloudManager.getInstance().refreshList(null);
                    } else {
                        Utils.sendMessage("&cUpload failed");
                    }
                } catch (Exception e) {
                    Utils.sendMessage("&cUpload error: " + e.getMessage());
                }
            });
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
