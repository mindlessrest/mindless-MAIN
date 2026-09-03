package mindless.module.impl.render;

import mindless.module.Module;

public class AlwaysBlock extends Module {
    public static boolean enabled = false;
    private static AlwaysBlock instance;

    public AlwaysBlock() {
        super("Always Block", "Keeps the blocking animation on screen.", category.render);
        instance = this;
    }

    @Override
    public void onEnable() { enabled = true; }
    @Override
    public void onDisable() { enabled = false; }

    public static boolean isActive() {
        return enabled || instance != null && instance.isEnabled();
    }
}
