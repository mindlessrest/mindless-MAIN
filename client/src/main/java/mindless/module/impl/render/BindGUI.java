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

    private static final String[] SOURCES = new String[]{"Bound", "Bound and enabled", "Everything"};
    private static final int SOURCE_BOUND = 0;
    private static final int SOURCE_BOUND_AND_ENABLED = 1;
    private static final String UNBOUND_KEY = "-";
    private static final int UNBOUND_COLOR = 0xFF5A616B;
    private static final int OFF_COLOR = 0xFF9AA1AA;
    private static final int ON_COLOR = 0xFFFFFFFF;

    private final SliderSetting posX;
    private final SliderSetting posY;
    private final SliderSetting alignment;
    private final SliderSetting scale;
    private final SliderSetting source;
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
        this.registerSetting(source = new SliderSetting("Show", SOURCE_BOUND, SOURCES));
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

        // Listing only what is bound is why this looked broken. On a profile where nothing has
        // a key yet the list came out empty and the module drew nothing at all, with no way to
        // tell that apart from it being switched off. Disabled modules were always included;
        // they are just drawn grey.
        int mode = (int) source.getInput();
        List<Module> bound = new ArrayList<Module>();
        for (Module module : Mindless.getModuleManager().getModules()) {
            if (module.getName() == null || module.isHidden() || module == this) {
                continue;
            }
            boolean isBound = module.getKeycode() > 0;
            if (mode == SOURCE_BOUND && !isBound) {
                continue;
            }
            if (mode == SOURCE_BOUND_AND_ENABLED && !isBound && !module.isEnabled()) {
                continue;
            }
            bound.add(module);
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
        List<Integer> colors = new ArrayList<Integer>();
        for (Module module : bound) {
            boolean isBound = module.getKeycode() > 0;
            names.add(module.getName());
            keys.add(isBound ? keyName(module.getKeycode()) : UNBOUND_KEY);
            colors.add(module.isEnabled() ? ON_COLOR : OFF_COLOR);
        }
        // An empty panel is indistinguishable from the module being off, so say why it is empty
        // rather than drawing nothing.
        if (names.isEmpty()) {
            names.add("No binds set");
            keys.add(UNBOUND_KEY);
            colors.add(OFF_COLOR);
        }
        for (int i = 0; i < names.size(); i++) {
            float lineWidth = mc.fontRendererObj.getStringWidth(names.get(i) + "  " + keys.get(i));
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
            RenderUtils.drawRect(0, 0, panelWidth, names.size() * rowHeight + 4.0f,
                    background.getRGB() | (background.getAlpha() << 24));
        }

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String key = keys.get(i);
            float rowTop = 2.0f + i * rowHeight;
            int nameColor = colors.get(i);
            // An unbound row's key column carries nothing, so it is dimmed instead of accented.
            int accent = UNBOUND_KEY.equals(key)
                    ? UNBOUND_COLOR
                    : useThemeColor.isToggled()
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
