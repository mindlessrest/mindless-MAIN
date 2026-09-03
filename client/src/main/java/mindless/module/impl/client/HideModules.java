package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.runtime.GuiIngameState;
import mindless.utility.font.FontManager;
import mindless.utility.font.ModuleFont;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.gui.MindlessButton;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public class HideModules extends Module {
    public HideModules() {
        super("Hide Modules", "Picks which modules the array list hides.", category.client);
        this.canBeEnabled = false;
        registerSetting(new ButtonSetting("Select modules", new Runnable() {
            @Override
            public void run() {
                mc.displayGuiScreen(new Screen());
            }
        }));
    }
private static boolean arrayListVisible() {
        return ModuleManager.hud != null && ModuleManager.hud.isEnabled();
    }
private static List<Module> candidates() {
        List<Module> candidates = new ArrayList<Module>();
        synchronized (ModuleManager.organizedModules) {
            for (Module module : ModuleManager.organizedModules) {
                if (module == null || !module.canBeEnabled || !module.isEnabled()) continue;
                candidates.add(module);
            }
        }
        return candidates;
    }

    public static final class Screen extends GuiScreen {
        private static final float ROW_HEIGHT = 19f;
        private static final float PANEL_W = 260f;
        private static final float PADDING = 12f;
private boolean inPlace;
        private final List<Module> modules = new ArrayList<Module>();
        private float scroll;
        private float panelLeft, panelTop, panelHeight, listTop, listBottom;
        private List<HUD.PickerRow> rows = new ArrayList<HUD.PickerRow>();

        @Override
        public void initGui() {
            inPlace = arrayListVisible();
            buttonList.clear();

            if (inPlace) {
                buttonList.add(new MindlessButton(0, width / 2 - 40, height - 34, 80, 20, "Done"));
                return;
            }

            modules.clear();
            modules.addAll(candidates());

            float desired = PADDING * 2f + 44f + modules.size() * ROW_HEIGHT;
            panelHeight = Math.min(desired, height - 40f);
            panelLeft = (width - PANEL_W) / 2f;
            panelTop = (height - panelHeight) / 2f;
            listTop = panelTop + 44f;
            listBottom = panelTop + panelHeight - PADDING;

            buttonList.add(new MindlessButton(0, (int) (panelLeft + PANEL_W / 2f - 40f),
                    (int) (panelTop + panelHeight + 8f), 80, 20, "Done"));
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            if (inPlace) {
                drawInPlace(mouseX, mouseY);
            } else {
                drawPanel(mouseX, mouseY);
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        }
private void drawInPlace(int mouseX, int mouseY) {
            drawRect(0, 0, width, height, 0x66000000);
            rows = HUD.renderHidePicker(mouseX, mouseY);

            MindlessFontRenderer font = FontManager.getHudRenderer(ModuleFont.nameOf(null), 1.0f);
            String title = "Click an entry to hide it";
            String subtitle = rows.isEmpty()
                    ? "Nothing is enabled to hide"
                    : hiddenCount(rows) + " of " + rows.size() + " hidden  -  struck through means hidden";
            font.drawString(title, width / 2f - font.getStringWidth(title) / 2f, 14f, 0xFFFFFFFF, true);
            font.drawString(subtitle, width / 2f - font.getStringWidth(subtitle) / 2f, 27f, 0xFF9AA0A6, true);
        }

        private static int hiddenCount(List<HUD.PickerRow> rows) {
            int hidden = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).module.isHidden()) hidden++;
            }
            return hidden;
        }

        private void drawPanel(int mouseX, int mouseY) {
            drawRect(0, 0, width, height, 0x88000000);

            BlurUtils.prepareBlur(panelLeft, panelTop, PANEL_W, panelHeight);
            RoundedUtils.drawRound(panelLeft, panelTop, PANEL_W, panelHeight,
                    GuiIngameState.panelRadius(), 0xFF000000);
            BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                    panelLeft - 2f, panelTop - 2f, PANEL_W + 4f, panelHeight + 4f);
            RoundedUtils.drawRound(panelLeft, panelTop, PANEL_W, panelHeight,
                    GuiIngameState.panelRadius(), GuiIngameState.PANEL_FILL_COLOR);

            MindlessFontRenderer font = FontManager.getHudRenderer(ModuleFont.nameOf(null), 1.0f);
            font.drawString("Hide from arraylist", panelLeft + PADDING, panelTop + PADDING,
                    0xFFFFFFFF, false);

            int hidden = 0;
            for (int i = 0; i < modules.size(); i++) {
                if (modules.get(i).isHidden()) hidden++;
            }
            String subtitle = modules.isEmpty()
                    ? "No modules are enabled"
                    : hidden + " of " + modules.size() + " hidden";
            font.drawString(subtitle, panelLeft + PADDING, panelTop + PADDING + 13f, 0xFF9AA0A6, false);

            scissor(panelLeft, listTop, panelLeft + PANEL_W, listBottom);
            for (int i = 0; i < modules.size(); i++) {
                float rowTop = listTop + scroll + i * ROW_HEIGHT;
                if (rowTop + ROW_HEIGHT < listTop || rowTop > listBottom) continue;
                drawPanelRow(font, modules.get(i), rowTop, mouseX, mouseY);
            }
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        }

        private void drawPanelRow(MindlessFontRenderer font, Module module, float rowTop, int mouseX, int mouseY) {
            float rowLeft = panelLeft + PADDING;
            float rowRight = panelLeft + PANEL_W - PADDING;
            boolean hovered = mouseX >= rowLeft && mouseX <= rowRight
                    && mouseY >= rowTop && mouseY <= rowTop + ROW_HEIGHT - 2f
                    && mouseY >= listTop && mouseY <= listBottom;
            boolean hidden = module.isHidden();

            if (hovered) {
                RoundedUtils.drawRound(rowLeft, rowTop, rowRight - rowLeft, ROW_HEIGHT - 2f, 3f,
                        0x22FFFFFF);
            }
            float boxSize = 9f;
            float boxX = rowLeft + 2f;
            float boxY = rowTop + (ROW_HEIGHT - 2f - boxSize) / 2f;
            RoundedUtils.drawRound(boxX, boxY, boxSize, boxSize, 2f,
                    hidden ? 0x33FFFFFF : 0xFF4C9AFF);
            if (!hidden) {
                RoundedUtils.drawRound(boxX + 2.5f, boxY + 2.5f, boxSize - 5f, boxSize - 5f, 1f,
                        0xFF11151C);
            }

            String name = HUD.getHudRenderText(module);
            font.drawString(name, boxX + boxSize + 7f,
                    rowTop + (ROW_HEIGHT - 2f - font.getFontHeight()) / 2f,
                    hidden ? 0xFF7A8087 : 0xFFFFFFFF, false);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
            if (mouseButton == 0) {
                if (inPlace) {
                    for (int i = 0; i < rows.size(); i++) {
                        HUD.PickerRow row = rows.get(i);
                        if (row.contains(mouseX, mouseY)) {
                            row.module.setHidden(!row.module.isHidden());
                            return;
                        }
                    }
                } else if (mouseY >= listTop && mouseY <= listBottom) {
                    float rowLeft = panelLeft + PADDING;
                    float rowRight = panelLeft + PANEL_W - PADDING;
                    if (mouseX >= rowLeft && mouseX <= rowRight) {
                        int index = (int) Math.floor((mouseY - listTop - scroll) / ROW_HEIGHT);
                        if (index >= 0 && index < modules.size()) {
                            Module module = modules.get(index);
                            module.setHidden(!module.isHidden());
                            return;
                        }
                    }
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }

        @Override
        public void handleMouseInput() throws IOException {
            super.handleMouseInput();
            if (inPlace) return;
            int wheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (wheel == 0) return;
            float content = modules.size() * ROW_HEIGHT;
            float viewport = listBottom - listTop;
            if (content <= viewport) {
                scroll = 0f;
                return;
            }
            scroll += wheel > 0 ? ROW_HEIGHT : -ROW_HEIGHT;
            scroll = Math.max(viewport - content, Math.min(0f, scroll));
        }

        @Override
        protected void actionPerformed(GuiButton button) throws IOException {
            if (button.id == 0) mc.displayGuiScreen(null);
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) throws IOException {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }
private void scissor(float x1, float y1, float x2, float y2) {
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            mindless.utility.RenderUtils.scissor(x1, y1, x2 - x1, y2 - y1);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
