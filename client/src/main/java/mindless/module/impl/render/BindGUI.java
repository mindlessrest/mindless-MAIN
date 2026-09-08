package mindless.module.impl.render;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The list of what your keys do.
 *
 * Anyone with more than a handful of binds forgets them, and the only way to check used to be
 * opening the click GUI and hunting. This is the reference card: every bound module and its key,
 * optionally only the ones currently on.
 *
 * The key name comes from LWJGL rather than a table of our own, so it stays correct for every
 * key including the ones nobody has a nice name for.
 */
public class BindGUI extends Module {
    private static final String[] ALIGNMENTS = new String[]{"Left", "Right"};
    private static final int ALIGN_LEFT = 0;

    private final SliderSetting posX;
    private final SliderSetting posY;
    private final SliderSetting alignment;
    private final SliderSetting scale;
    private final ButtonSetting onlyEnabled;
    private final ButtonSetting showBackground;
    private final ButtonSetting sortByName;
    private final ButtonSetting useThemeColor;
    private final ColorSetting keyColor;
    private final ColorSetting background;

    public BindGUI() {
        super("Bind GUI", "Lists your keybinds on screen.", category.render, 0);
        this.registerSetting(posX = new SliderSetting("X", 4.0, 0.0, 400.0, 1.0));
        this.registerSetting(posY = new SliderSetting("Y", 120.0, 0.0, 400.0, 1.0));
        this.registerSetting(alignment = new SliderSetting("Align", ALIGN_LEFT, ALIGNMENTS));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(onlyEnabled = new ButtonSetting("Only enabled", false));
        this.registerSetting(showBackground = new ButtonSetting("Background", true));
        this.registerSetting(sortByName = new ButtonSetting("Sort by name", true));
        this.registerSetting(useThemeColor = new ButtonSetting("Theme color", true));
        this.registerSetting(keyColor = new ColorSetting("Key color", 90, 170, 255, 255));
        this.registerSetting(background = new ColorSetting("Background color", 12, 14, 18, 150));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        if (keyColor != null) {
            keyColor.setVisible(useThemeColor == null || !useThemeColor.isToggled(), this);
        }
        if (background != null) {
            background.setVisible(showBackground == null || showBackground.isToggled(), this);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck() || mc.currentScreen != null) {
            return;
        }
        if (Mindless.getModuleManager() == null) {
            return;
        }

        List<Module> bound = new ArrayList<Module>();
        for (Module module : Mindless.getModuleManager().getModules()) {
            if (module.getKeycode() <= 0 || module.getName() == null) {
                continue;
            }
            if (onlyEnabled.isToggled() && !module.isEnabled()) {
                continue;
            }
            bound.add(module);
        }
        if (bound.isEmpty()) {
            return;
        }
        if (sortByName.isToggled()) {
            Collections.sort(bound, new Comparator<Module>() {
                @Override
                public int compare(Module a, Module b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
        }

        float factor = (float) scale.getInput();
        boolean right = (int) alignment.getInput() != ALIGN_LEFT;
        ScaledResolution resolution = new ScaledResolution(mc);
        float rowHeight = mc.fontRendererObj.FONT_HEIGHT + 2.0f;

        float widest = 0.0f;
        List<String> names = new ArrayList<String>();
        List<String> keys = new ArrayList<String>();
        for (Module module : bound) {
            String name = module.getName();
            String key = keyName(module.getKeycode());
            names.add(name);
            keys.add(key);
            float lineWidth = mc.fontRendererObj.getStringWidth(name + "  " + key);
            if (lineWidth > widest) {
                widest = lineWidth;
            }
        }

        float panelWidth = widest + 8.0f;
        float originX = right
                ? resolution.getScaledWidth() / factor - panelWidth - (float) posX.getInput()
                : (float) posX.getInput();

        GlStateManager.pushMatrix();
        GlStateManager.translate(originX, (float) posY.getInput(), 0.0f);
        GlStateManager.scale(factor, factor, 1.0f);

        if (showBackground.isToggled()) {
            RenderUtils.drawRect(0, 0, panelWidth, bound.size() * rowHeight + 4.0f,
                    background.getRGB() | (background.getAlpha() << 24));
        }

        for (int i = 0; i < bound.size(); i++) {
            String name = names.get(i);
            String key = keys.get(i);
            float rowTop = 2.0f + i * rowHeight;
            int nameColor = bound.get(i).isEnabled() ? 0xFFFFFFFF : 0xFF9AA1AA;
            int accent = useThemeColor.isToggled()
                    ? HUD.getHudColor(i * 30.0)
                    : keyColor.getRGB() | 0xFF000000;

            mc.fontRendererObj.drawStringWithShadow(name, 4.0f, rowTop, nameColor);
            // The key sits hard against the right edge so the column lines up whatever the
            // names are, which is what makes the list scannable rather than ragged.
            float keyX = panelWidth - 4.0f - mc.fontRendererObj.getStringWidth(key);
            mc.fontRendererObj.drawStringWithShadow(key, keyX, rowTop, accent);
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static String keyName(int keycode) {
        String name = Keyboard.getKeyName(keycode);
        return name == null ? "?" : name;
    }
}
