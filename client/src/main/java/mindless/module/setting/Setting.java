package mindless.module.setting;

import com.google.gson.JsonObject;
import mindless.Raven;
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

    /**
     * Every key this setting will accept from a saved profile, most preferred first.
     *
     * <p>Used to tell "the profile chose this value" apart from "the profile has never heard of
     * this setting". Without that distinction a profile that predates a setting silently inherits
     * whatever the previously loaded profile left in it, which is how two profiles end up
     * disagreeing about a value neither of them has ever been asked about. Subclasses that read
     * extra keys -- a renamed setting's old name, or a bare name where a grouped key is written --
     * list them here so the two answers stay in step with {@link #loadProfile}.
     */
    public String[] getProfileKeys() {
        return new String[]{ getProfileKey(), getName() };
    }

    /**
     * Remembers the value this setting starts life with, once.
     *
     * <p>Taken at the point profiles are first read rather than in the constructor: a few settings
     * are adjusted straight after being registered -- the scoreboard position sliders are built at
     * zero and immediately moved to their -1 "unset" sentinel -- and it is the adjusted value that
     * is the real default. Capturing later also costs nothing, because nothing can have changed a
     * setting before the first profile load.
     */
    public final void captureDefaultOnce() {
        if (!defaultCaptured) {
            defaultCaptured = true;
            captureDefault();
        }
    }

    public final boolean hasCapturedDefault() {
        return defaultCaptured;
    }

    /** Stores the current value as this setting's default. */
    protected void captureDefault() {
    }

    /** Puts the value back to the one {@link #captureDefault} recorded. */
    public void resetToDefault() {
    }

    public abstract void loadProfile(JsonObject data);
}
