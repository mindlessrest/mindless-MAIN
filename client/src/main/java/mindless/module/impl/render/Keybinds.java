package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.hud.HudModule;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Keybinds extends HudModule {
    private final ButtonSetting activeOnly;
    private final ButtonSetting background;
    private final ButtonSetting shadow;
    private final SliderSetting scale;
    private final SliderSetting positionX;
    private final SliderSetting positionY;

    public Keybinds() {
        super("Keybinds", "Shows assigned module keybinds.", category.render);
        registerSetting(activeOnly = new ButtonSetting("Active only", false));
        registerSetting(background = new ButtonSetting("Background", true));
        registerSetting(shadow = new ButtonSetting("Text shadow", true));
        registerSetting(scale = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.05));
        registerSetting(positionX = new SliderSetting("Position X", 0.02, 0.0, 1.0, 0.001));
        registerSetting(positionY = new SliderSetting("Position Y", 0.35, 0.0, 1.0, 0.001));
        positionX.visible = false;
        positionY.visible = false;
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck() || mc.currentScreen != null
                || mc.gameSettings.showDebugInfo) return;
        render();
    }

    @Override
    public void setPosition(float x, float y) {
        ScaledResolution sr = ScaledResolutionCache.get();
        positionX.setValueRaw(Math.max(0, Math.min(1, x / Math.max(1f, sr.getScaledWidth()))));
        positionY.setValueRaw(Math.max(0, Math.min(1, y / Math.max(1f, sr.getScaledHeight()))));
    }

    @Override
    public void resetHudItem() {
        positionX.setValueRaw(0.02);
        positionY.setValueRaw(0.35);
    }

    @Override
    public SliderSetting getHudItemScale() {
        return scale;
    }

    @Override
    public float[] render() {
        ScaledResolution sr = ScaledResolutionCache.get();
        float factor = (float) scale.getInput();
        float x = (float) (positionX.getInput() * sr.getScaledWidth());
        float y = (float) (positionY.getInput() * sr.getScaledHeight());
        List<Module> entries = new ArrayList<Module>();
        for (Module module : ModuleManager.modules) {
            if (module != this && module.getKeycode() != 0 && (!activeOnly.isToggled() || module.isEnabled())) entries.add(module);
        }
        entries.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        int width = mc.fontRendererObj.getStringWidth("Keybinds");
        for (Module module : entries) width = Math.max(width, mc.fontRendererObj.getStringWidth(module.getName() + "  [" + keyName(module.getKeycode()) + "]"));
        if (entries.isEmpty()) width = Math.max(width, mc.fontRendererObj.getStringWidth("No binds assigned"));
        int rows = Math.max(1, entries.size());
        float boxWidth = (width + 10) * factor;
        float boxHeight = (14 + rows * 11) * factor;
        if (background.isToggled()) RenderUtils.drawRect(x, y, x + boxWidth, y + boxHeight, 0x990B0C10);
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(x, y, 0);
        net.minecraft.client.renderer.GlStateManager.scale(factor, factor, 1);
        mc.fontRendererObj.drawString("Keybinds", 5, 4, 0xFFFFFFFF, shadow.isToggled());
        if (entries.isEmpty()) mc.fontRendererObj.drawString("No binds assigned", 5, 15, 0xFF9999A3, shadow.isToggled());
        int row = 0;
        for (Module module : entries) {
            int color = module.isEnabled() ? 0xFFFFFFFF : 0xFFAAAAAF;
            mc.fontRendererObj.drawString(module.getName(), 5, 15 + row * 11, color, shadow.isToggled());
            String key = "[" + keyName(module.getKeycode()) + "]";
            mc.fontRendererObj.drawString(key, width + 5 - mc.fontRendererObj.getStringWidth(key), 15 + row * 11, color, shadow.isToggled());
            row++;
        }
        net.minecraft.client.renderer.GlStateManager.popMatrix();
        return new float[]{x, y, x + boxWidth, y + boxHeight};
    }

    private String keyName(int code) {
        if (code == 1069) return "MWHEELDOWN";
        if (code == 1070) return "MWHEELUP";
        if (code >= 1000) return "MOUSE" + (code - 999);
        String name = Keyboard.getKeyName(code);
        return name == null ? "?" : name;
    }
}
