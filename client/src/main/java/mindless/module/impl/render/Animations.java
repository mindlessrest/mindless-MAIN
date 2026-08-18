package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;

public class Animations extends Module {
    private static Animations instance;
    private static final String[] MODES = new String[]{
            "Vanilla", "Exhibition", "ETB", "Sigma", "Dortware", "Plain", "Spin", "Avatar",
            "Swong", "Swang", "Swank", "Styles", "Nudge", "Punch", "Jigsaw", "Slide",
            "Swing", "Old", "Push", "Dash", "Slash", "Scale", "Swonk", "Stella",
            "Small", "Edit", "Rhys", "Stab", "Float", "Remix", "Xiv", "Winter",
            "Yamato", "SlideSwing", "SmallPush", "Reverse", "Invent", "Leaked",
            "Aqua", "Astro", "Fadeaway", "Astolfo", "AstolfoSpin", "Moon",
            "MoonPush", "Smooth", "Tap1", "Tap2", "Sigma3", "Sigma4",
            "Myau1.8", "MyauSlide", "MyauSwank", "MyauSwang", "MyauAvatar", "MyauJigsaw"
    };
    public static final String[] RENDER_MODES = new String[]{"Blocking", "Always"};

    public static int modeIndex;
    public static int renderMode = 1;
    public static int scale = 100;
    public static float itemSize = 0.0f;
    public static float blockPosX = 0.0f;
    public static float blockPosY = 0.0f;
    public static float blockPosZ = 0.0f;
    public static int swingSpeed = 6;
    public static boolean enabled = false;

    private final SliderSetting modeSetting;
    private final SliderSetting renderSetting;
    private final SliderSetting scaleSetting;
    private final SliderSetting itemSizeSetting;
    private final SliderSetting blockPosXSetting;
    private final SliderSetting blockPosYSetting;
    private final SliderSetting blockPosZSetting;
    private final SliderSetting swingSpeedSetting;

    public Animations() {
        super("Sword Animation", category.render);
        instance = this;
        this.registerSetting(modeSetting = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(renderSetting = new SliderSetting("Render", 1, RENDER_MODES));
        this.registerSetting(scaleSetting = new SliderSetting("Scale", 100, 50, 150, 1));
        this.registerSetting(itemSizeSetting = new SliderSetting("Item Size", 0.0, -0.5, 0.5, 0.05));
        this.registerSetting(blockPosXSetting = new SliderSetting("Block Pos X", 0.0, -1.0, 1.0, 0.05));
        this.registerSetting(blockPosYSetting = new SliderSetting("Block Pos Y", 0.0, -1.0, 1.0, 0.05));
        this.registerSetting(blockPosZSetting = new SliderSetting("Block Pos Z", 0.0, -1.0, 1.0, 0.05));
        this.registerSetting(swingSpeedSetting = new SliderSetting("Swing Speed", 6, 0, 100, 1));
    }

    @Override
    public void onEnable() { syncConfig(); enabled = true; }
    @Override
    public void onDisable() { enabled = false; }
    @Override
    public void guiUpdate() { if (enabled) syncConfig(); }

    private void syncConfig() {
        modeIndex = (int) modeSetting.getInput();
        renderMode = (int) renderSetting.getInput();
        scale = (int) scaleSetting.getInput();
        itemSize = (float) itemSizeSetting.getInput();
        blockPosX = (float) blockPosXSetting.getInput();
        blockPosY = (float) blockPosYSetting.getInput();
        blockPosZ = (float) blockPosZSetting.getInput();
        swingSpeed = (int) swingSpeedSetting.getInput();
    }


    public static boolean isActive() {
        return enabled || instance != null && instance.isEnabled();
    }
}
