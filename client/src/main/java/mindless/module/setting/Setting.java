package mindless.module.setting;

import com.google.gson.JsonObject;
import mindless.Raven;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.clickgui.components.impl.ModuleComponent;
import mindless.module.Module;

public abstract class Setting {
    public String name;
    public boolean visible = true;

    public Setting(String name) {
        this.name = name;
    }

    /**
     * Shows or hides this setting, rebuilding the panel at most once per frame.
     *
     * <p>This used to call {@code reloadSettings} directly, which throws away and reconstructs
     * every component in the module. A module's {@code guiUpdate} typically decides the visibility
     * of several settings together -- the audio visualiser flips seven -- so expanding it rebuilt
     * the whole list seven times in a single frame, each rebuild allocating two maps and a
     * component per setting, on a module that has about thirty of them. That is the hitch when the
     * panel opens.
     *
     * <p>Marking the component instead lets it rebuild once, on its next frame, no matter how many
     * settings changed at the same time.
     */
    public void setVisible(boolean visible, Module module) {
        if (visible == this.visible) {
            return;
        }
        this.visible = visible;
        for (CategoryComponent categoryComponent : Raven.clickGui.categories) {
            if (categoryComponent.category == module.moduleCategory()) {
                for (ModuleComponent moduleComponent : categoryComponent.modules) {
                    if (moduleComponent.mod.getName().equals(module.getName())) {
                        moduleComponent.settingsDirty = true;
                        break;
                    }
                }
            }
        }
    }

    public String getName() {
        return this.name;
    }

    public String getProfileKey() {
        return this.name;
    }

    public abstract void loadProfile(JsonObject data);
}
