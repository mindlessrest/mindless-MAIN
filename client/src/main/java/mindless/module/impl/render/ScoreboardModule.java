package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.StringListSetting;

import java.util.List;

public class ScoreboardModule extends Module {
    private static ScoreboardModule instance;

    private final ButtonSetting textSwap;
    private final StringListSetting findEntries;
    private final StringListSetting replaceEntries;

    public ScoreboardModule() {
        super("Scoreboard", category.render);
        instance = this;
        this.registerSetting(textSwap = new ButtonSetting("Text swap", false));
        this.registerSetting(findEntries = new StringListSetting("Find", "text to find", 64));
        this.registerSetting(replaceEntries = new StringListSetting("Replace", "replacement", 64));
    }

    @Override
    public void guiUpdate() {
        findEntries.setVisible(textSwap.isToggled(), this);
        replaceEntries.setVisible(textSwap.isToggled(), this);
    }

    public static boolean isCustomScoreboardEnabled() {
        return instance != null && instance.isEnabled();
    }

    public static String applyTextSwaps(String text) {
        if (instance == null || !instance.isEnabled() || !instance.textSwap.isToggled()) {
            return text;
        }
        List<String> finds = instance.findEntries.getEntries();
        List<String> replaces = instance.replaceEntries.getEntries();
        int count = Math.min(finds.size(), replaces.size());
        for (int i = 0; i < count; i++) {
            String find = finds.get(i);
            String replace = replaces.get(i);
            if (!find.isEmpty()) {
                text = text.replace(find, replace);
            }
        }
        return text;
    }
}
