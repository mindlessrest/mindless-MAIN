package mindless.accountmanager.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import mindless.accountmanager.AltShopHttp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

public class GuiNicealtsSetup extends GuiScreen {
    private final GuiScreen parent;
    private GuiTextField apiKeyField;
    private GuiButton continueButton;

    public GuiNicealtsSetup(GuiScreen parent) {
        this.parent = parent;
    }

    public static File getKeyFile() {
        return new File(Minecraft.getMinecraft().mcDataDir, "nicealtsapi.txt");
    }

    public static String loadKey() {
        try {
            File f = getKeyFile();
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void saveKey(String key) {
        try {
            Files.write(getKeyFile().toPath(), key.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int cx = width / 2, cy = height / 2;
        apiKeyField = new GuiTextField(0, fontRendererObj, cx - 120, cy - 10, 240, 20);
        apiKeyField.setFocused(true);
        apiKeyField.setMaxStringLength(128);
        continueButton = new GuiButton(0, cx - 50, cy + 20, 100, 20, "Continue");
        buttonList.add(continueButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        apiKeyField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2, cy = height / 2;
        drawRect(cx - 145, cy - 65, cx + 145, cy + 58, 0xBB000000);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Nicealts API Key",
                cx, cy - 54, -1);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GRAY + "Enter your Nicealts API key to continue.",
                cx, cy - 36, -1);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.DARK_AQUA + "More info: docs.nicealts.com",
                cx, cy - 26, -1);
        apiKeyField.drawTextBox();
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "You will not be prompted again.",
                cx, cy + 48, -1);
        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            String key = apiKeyField.getText().trim();
            if (key.isEmpty()) return;
            saveKey(key);
            mc.displayGuiScreen(new GuiNicealtsMenu(parent, key));
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        apiKeyField.textboxKeyTyped(c, key);
        if (key == 28 || key == 156) actionPerformed(continueButton);
        if (key == 1) mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        apiKeyField.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
