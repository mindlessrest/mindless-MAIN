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

    public void setVisible(boolean visible, Module module) {
        if (visible == this.visible) {
            return;
        }
        this.visible = visible;
        for (CategoryComponent categoryComponent : Raven.clickGui.categories) {
            if (categoryComponent.category == module.moduleCategory()) {
                for (ModuleComponent moduleComponent : categoryComponent.modules) {
                    if (moduleComponent.mod.getName().equals(module.getName())) {
                        moduleComponent.reloadSettings();
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
