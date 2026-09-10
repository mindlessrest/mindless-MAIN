package mindless.module.impl.render;

import mindless.module.Module;
import mindless.helper.MouseHelper;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class Keystrokes extends Module {
    private static final float DEFAULT_X = 8.0f;
    private static final float DEFAULT_Y = 62.0f;
    private final SliderSetting font;
    private final SliderSetting scale;
    private final SliderSetting opacity;
    private final ButtonSetting mouseButtons;
    public float posX = DEFAULT_X;
    public float posY = DEFAULT_Y;

    public Keystrokes() {
        super("Keystrokes", "Shows movement and mouse input on the HUD.", category.render);
        this.registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.65, 1.75, 0.05));
        this.registerSetting(opacity = new SliderSetting("Opacity", "%", 82.0, 20.0, 100.0, 1.0));
        this.registerSetting(mouseButtons = new ButtonSetting("Mouse buttons", true));
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw();
    }

    public float[] renderPreview() {
        return draw();
    }

    public void moveTo(float x, float y) {
        posX = x;
        posY = y;
    }

    public void resetPosition() {
        posX = DEFAULT_X;
        posY = DEFAULT_Y;
    }

    public SliderSetting scaleSetting() {
        return scale;
    }

    private float[] draw() {
        MindlessFontRenderer renderer = FontManager.getHudRenderer(
                ModuleFont.nameOf(font), HUD.getSelectedFontScale());
        if (renderer == null) return null;
        float s = (float) scale.getInput();
        float key = 20.0f * s;
        float gap = 3.0f * s;
        float x = posX;
        float y = posY;
        drawKey(renderer, "W", x + key + gap, y, key, key,
                mc.gameSettings.keyBindForward);
        float row = y + key + gap;
        drawKey(renderer, "A", x, row, key, key, mc.gameSettings.keyBindLeft);
        drawKey(renderer, "S", x + key + gap, row, key, key, mc.gameSettings.keyBindBack);
        drawKey(renderer, "D", x + (key + gap) * 2.0f, row, key, key,
                mc.gameSettings.keyBindRight);
        float width = key * 3.0f + gap * 2.0f;
        float height = key * 2.0f + gap;
        if (mouseButtons.isToggled()) {
            float mouseY = row + key + gap;
            float mouseWidth = (width - gap) * 0.5f;
            drawMouse(renderer, "LMB", x, mouseY, mouseWidth, key, 0);
            drawMouse(renderer, "RMB", x + mouseWidth + gap, mouseY, mouseWidth, key, 1);
            height += key + gap;
        }
        return new float[]{x, y, x + width, y + height};
    }

    private void drawKey(MindlessFontRenderer renderer, String label, float x, float y,
                         float width, float height, KeyBinding binding) {
        drawBox(renderer, label, x, y, width, height, binding != null && binding.isKeyDown());
    }

    private void drawMouse(MindlessFontRenderer renderer, String label, float x, float y,
                           float width, float height, int button) {
        KeyBinding binding = button == 0 ? mc.gameSettings.keyBindAttack
                : mc.gameSettings.keyBindUseItem;
        boolean pressed = MouseHelper.isVisuallyDown(button)
                || binding != null && binding.isKeyDown();
        drawBox(renderer, label, x, y, width, height, pressed);
    }

    private void drawBox(MindlessFontRenderer renderer, String label, float x, float y,
                         float width, float height, boolean pressed) {
        int alpha = (int) Math.round(opacity.getInput() * 2.55);
        int background = (alpha << 24) | (pressed ? 0x9F8FD2 : 0x17191F);
        int foreground = pressed ? 0xFF17151D : 0xFFE8E8EC;
        RoundedUtils.drawRound(x, y, width, height, 4.0f * (float) scale.getInput(), background);
        float tx = x + (width - renderer.getStringWidth(label)) * 0.5f;
        float ty = y + (height - renderer.getFontHeight()) * 0.5f;
        renderer.drawString(label, tx, ty, foreground, false);
    }
}
