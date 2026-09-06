package mindless.hud;

import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;

public interface HudItem {
    String getHudItemName();
    Module getHudItemModule();
    float[] render();
    void setPosition(float left, float top);
    void resetHudItem();
    SliderSetting getHudItemScale();
}
