package mindless.utility.profile;

import mindless.Raven;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.Utils;

import java.awt.*;
import java.io.IOException;

public class Manager extends Module {
    private final TextSetting createProfileName;
    private ButtonSetting loadProfiles, openFolder, createProfile;

    public Manager() {
        super("+ New Profile", category.profiles);
        this.registerSetting(createProfileName = new TextSetting("Name", "", "Profile name...", 32, this::createProfile));
        this.registerSetting(createProfile = new ButtonSetting("Create", () -> createProfile()));
        this.registerSetting(loadProfiles = new ButtonSetting("Reload profiles", () -> {
            if (Utils.nullCheck() && Raven.profileManager != null) {
                Raven.profileManager.loadProfiles();
            }
        }));
        this.registerSetting(openFolder = new ButtonSetting("Open folder", () -> {
            try {
                Desktop.getDesktop().open(Raven.profileManager.directory);
            }
            catch (IOException ex) {
                Raven.profileManager.directory.mkdirs();
                Utils.sendMessage("&cError locating folder, recreated.");
            }
        }));
        ignoreOnSave = true;
        canBeEnabled = false;
    }

    private void createProfile() {
        if (!Utils.nullCheck() || Raven.profileManager == null) {
            return;
        }

        String name = createProfileName.getText().trim();
        if (name.isEmpty()) {
            Utils.sendMessage("&cProfile name cannot be empty.");
            return;
        }

        Profile profile = Raven.profileManager.createProfile(name, 0);
        if (profile != null) {
            createProfileName.setText("");
            Utils.sendMessage("&7Created profile: &b" + profile.getName());
        }
    }
}
