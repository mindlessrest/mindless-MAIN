package mindless.hud;

import mindless.module.Module;

public abstract class HudModule extends Module implements HudItem {
    protected HudModule(String name, String description, Module.category category) {
        super(name, description, category);
    }

    @Override
    public final String getHudItemName() {
        return getName();
    }

    @Override
    public final Module getHudItemModule() {
        return this;
    }
}
