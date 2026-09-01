package mindless.module.setting;

import com.google.gson.JsonObject;
import mindless.Mindless;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.clickgui.components.impl.ModuleComponent;
import mindless.module.Module;

public abstract class Setting {
    public String name;
    public boolean visible = true;
    private boolean defaultCaptured;

    public Setting(String name) {
        this.name = name;
    }
public void setVisible(boolean visible, Module module) {
        if (visible == this.visible) {
            return;
        }
        this.visible = visible;
        for (CategoryComponent categoryComponent : Mindless.clickGui.categories) {
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
public String[] getProfileKeys() {
        return new String[]{ getProfileKey(), getName() };
    }
public final void captureDefaultOnce() {
        if (!defaultCaptured) {
            defaultCaptured = true;
            captureDefault();
        }
    }

    public final boolean hasCapturedDefault() {
        return defaultCaptured;
    }
protected void captureDefault() {
    }
public void resetToDefault() {
    }

    public abstract void loadProfile(JsonObject data);
}
