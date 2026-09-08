package mindless.module.impl.render;

import mindless.Mindless;
import mindless.event.KeyPressEvent;
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
import java.util.List;

/**
 * A keyboard-driven module menu.
 *
 * Some people will not use a click GUI -- it means taking the mouse off the game, which in a
 * fight is not free. This is the alternative every legacy client shipped: arrow keys to move,
 * right or enter to go in and toggle, left to come back out, and it never grabs the cursor.
 *
 * Drawn from the live module list rather than a fixed table, so anything added to the client
 * appears here with no extra work.
 */
public class TabGUI extends Module {
    private static final Module.category[] CATEGORIES = new Module.category[]{
        Module.category.combat,
        Module.category.movement,
        Module.category.player,
        Module.category.world,
        Module.category.render,
        Module.category.bedwars,
        Module.category.other,
        Module.category.client
    };

    private final SliderSetting posX;
    private final SliderSetting posY;
    private final SliderSetting width;
    private final SliderSetting scale;
    private final ButtonSetting useThemeColor;
    private final ColorSetting accent;
    private final ColorSetting background;
    private final ButtonSetting showInChat;

    /** -1 while browsing categories; otherwise the category being looked inside. */
    private int openCategory = -1;
    private int categoryIndex;
    private int moduleIndex;

    public TabGUI() {
        super("Tab GUI", "A keyboard-driven module menu.", category.render, 0);
        this.registerSetting(posX = new SliderSetting("X", 4.0, 0.0, 400.0, 1.0));
        this.registerSetting(posY = new SliderSetting("Y", 4.0, 0.0, 400.0, 1.0));
        this.registerSetting(width = new SliderSetting("Width", 88.0, 60.0, 160.0, 1.0));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(useThemeColor = new ButtonSetting("Theme color", true));
        this.registerSetting(accent = new ColorSetting("Accent", 90, 170, 255, 255));
        this.registerSetting(background = new ColorSetting("Background", 12, 14, 18, 170));
        this.registerSetting(showInChat = new ButtonSetting("Show with chat open", false));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        if (accent != null) {
            accent.setVisible(useThemeColor == null || !useThemeColor.isToggled(), this);
        }
    }

    @Override
    public void onDisable() {
        openCategory = -1;
        categoryIndex = 0;
        moduleIndex = 0;
    }

    /** Modules in a category, in registration order, with the ones nobody binds left out. */
    private List<Module> modulesIn(Module.category which) {
        List<Module> found = new ArrayList<Module>();
        if (Mindless.getModuleManager() == null) {
            return found;
        }
        for (Module module : Mindless.getModuleManager().getModules()) {
            if (module.moduleCategory() == which && module.getName() != null) {
                found.add(module);
            }
        }
        return found;
    }

    @SubscribeEvent
    public void onKeyPress(KeyPressEvent event) {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            return;
        }

        switch (event.keyCode) {
            case Keyboard.KEY_UP:
                move(-1);
                break;
            case Keyboard.KEY_DOWN:
                move(1);
                break;
            case Keyboard.KEY_RIGHT:
            case Keyboard.KEY_RETURN:
                enter();
                break;
            case Keyboard.KEY_LEFT:
                openCategory = -1;
                moduleIndex = 0;
                break;
            default:
                return;
        }
        // Arrow keys otherwise fall through to whatever they are bound to in game.
        event.setCanceled(true);
    }

    private void move(int delta) {
        if (openCategory < 0) {
            categoryIndex = wrap(categoryIndex + delta, CATEGORIES.length);
            return;
        }
        int count = modulesIn(CATEGORIES[openCategory]).size();
        if (count > 0) {
            moduleIndex = wrap(moduleIndex + delta, count);
        }
    }

    private void enter() {
        if (openCategory < 0) {
            openCategory = categoryIndex;
            moduleIndex = 0;
            return;
        }
        List<Module> modules = modulesIn(CATEGORIES[openCategory]);
        if (moduleIndex >= 0 && moduleIndex < modules.size()) {
            modules.get(moduleIndex).toggle();
        }
    }

    private static int wrap(int value, int size) {
        if (size <= 0) {
            return 0;
        }
        return ((value % size) + size) % size;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }
        boolean chatOpen = mc.currentScreen instanceof net.minecraft.client.gui.GuiChat;
        if (mc.currentScreen != null && !(chatOpen && showInChat.isToggled())) {
            return;
        }

        float factor = (float) scale.getInput();
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) posX.getInput(), (float) posY.getInput(), 0.0f);
        GlStateManager.scale(factor, factor, 1.0f);

        float panelWidth = (float) width.getInput();
        float rowHeight = mc.fontRendererObj.FONT_HEIGHT + 5.0f;
        int accentColor = useThemeColor.isToggled() ? HUD.getHudColor(0.0) : accent.getRGB() | 0xFF000000;
        int backgroundColor = background.getRGB() | (background.getAlpha() << 24);

        List<String> rows = new ArrayList<String>();
        int selected;
        if (openCategory < 0) {
            for (Module.category which : CATEGORIES) {
                rows.add(capitalise(which.name()));
            }
            selected = categoryIndex;
        }
        else {
            for (Module module : modulesIn(CATEGORIES[openCategory])) {
                rows.add(module.getName());
            }
            selected = moduleIndex;
        }

        float height = Math.max(rowHeight, rows.size() * rowHeight) + 4.0f;
        RenderUtils.drawRect(0, 0, panelWidth, height, backgroundColor);
        // A bar down the left edge rather than a full border: it marks the panel without boxing
        // in text that is already hard up against the screen corner.
        RenderUtils.drawRect(0, 0, 2.0f, height, accentColor);

        List<Module> modules = openCategory < 0 ? null : modulesIn(CATEGORIES[openCategory]);
        for (int i = 0; i < rows.size(); i++) {
            float rowTop = 2.0f + i * rowHeight;
            if (i == selected) {
                RenderUtils.drawRect(2.0f, rowTop, panelWidth, rowTop + rowHeight, (accentColor & 0xFFFFFF) | 0x40000000);
            }
            boolean enabled = modules != null && i < modules.size() && modules.get(i).isEnabled();
            int textColor = enabled ? accentColor : (i == selected ? 0xFFFFFFFF : 0xFFB0B6BE);
            mc.fontRendererObj.drawStringWithShadow(rows.get(i), 7.0f, rowTop + 3.0f, textColor);
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static String capitalise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
