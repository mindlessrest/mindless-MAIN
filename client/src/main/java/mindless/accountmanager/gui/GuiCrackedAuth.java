package mindless.accountmanager.gui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import mindless.accountmanager.auth.CrackedAuth;
import mindless.accountmanager.utils.UsernameGenerator;
import mindless.utility.font.MinecraftFontAdapter;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

public class GuiCrackedAuth extends GuiScreen {
    private final GuiScreen previousScreen;
    private GuiTextField usernameField;
    private GuiButton loginButton;
    private GuiButton generateRandomButton;
    private GuiButton cancelButton;

    public GuiCrackedAuth(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        int cx = width / 2, cy = height / 2;
        int bw = 200, bh = 20;
        usernameField = new GuiTextField(0, fontRendererObj, cx - bw/2, cy - 22, bw, 18);
        usernameField.setMaxStringLength(16);
        usernameField.setFocused(true);
        usernameField.setEnableBackgroundDrawing(false);

        loginButton          = new GuiButton(0, cx - bw/2, cy + 4,  bw, bh, "Login");
        generateRandomButton = new GuiButton(1, cx - bw/2, cy + 28, bw, bh, "Generate Random");
        cancelButton         = new GuiButton(2, cx - bw/2, cy + 52, bw, bh, "Cancel");
        buttonList.add(loginButton);
        buttonList.add(generateRandomButton);
        buttonList.add(cancelButton);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawRect(0, 0, width, height, GuiAccountManager.C_BG);
        MindlessFontRenderer sfBold = new MinecraftFontAdapter(fontRendererObj);
        MindlessFontRenderer sfReg  = new MinecraftFontAdapter(fontRendererObj);
        MindlessFontRenderer sfSm   = new MinecraftFontAdapter(fontRendererObj);

        int cardW = 240, cardH = 170;
        int cardX = width/2 - cardW/2, cardY = height/2 - cardH/2 - 20;
        RoundedUtils.drawRound(cardX, cardY, cardW, cardH, 6f, GuiAccountManager.C_PANEL);
        drawRect(cardX, cardY, cardX + cardW, cardY + 1, GuiAccountManager.C_ACCENT_DIM);
        sfBold.drawString("Cracked Login", width/2f - sfBold.getStringWidth("Cracked Login")/2f, cardY + 10f, GuiAccountManager.C_TEXT, false);
        sfSm.drawString("Offline / cracked username", width/2f - sfSm.getStringWidth("Offline / cracked username")/2f, cardY + 24f, GuiAccountManager.C_DIM, false);
        int fh = 22, fw = 200, fx = width/2 - fw/2;
        int fy = height/2 - 26;
        RoundedUtils.drawRound(fx, fy, fw, fh, 4f, GuiAccountManager.C_ROW);
        drawRect(fx, fy + fh - 1, fx + fw, fy + fh, GuiAccountManager.C_ACCENT_DIM);
        if (usernameField.getText().isEmpty() && !usernameField.isFocused())
            sfReg.drawString("Username...", fx + 6f, fy + 5f, GuiAccountManager.C_DIM, false);
        usernameField.drawTextBox();

        for (GuiButton b : buttonList) {
            boolean isCancel = b.id == 2;
            boolean hov = b.enabled && mx >= b.xPosition && mx < b.xPosition + b.width
                    && my >= b.yPosition && my < b.yPosition + b.height;
            int bg  = isCancel ? GuiAccountManager.C_ROW : (b.id == 0 ? 0xCC181A2A : GuiAccountManager.C_ROW);
            int bgH = isCancel ? GuiAccountManager.C_ROW_HOV : (b.id == 0 ? 0xCC1E2035 : GuiAccountManager.C_ROW_HOV);
            int fg  = isCancel ? GuiAccountManager.C_MUTED : (b.id == 0 ? GuiAccountManager.C_ACCENT : GuiAccountManager.C_TEXT);
            RoundedUtils.drawRound(b.xPosition, b.yPosition, b.width, b.height, 4f, hov ? bgH : bg);
            float tw = sfReg.getStringWidth(b.displayString);
            sfReg.drawString(b.displayString, b.xPosition + b.width/2f - tw/2f,
                    b.yPosition + b.height/2f - sfReg.getFontHeight()/2f, fg, false);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        usernameField.textboxKeyTyped(typedChar, keyCode);
        if (keyCode == 1) mc.displayGuiScreen(previousScreen);
        if (keyCode == 28) actionPerformed(loginButton);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        usernameField.mouseClicked(mx, my, btn);
        super.mouseClicked(mx, my, btn);
    }

    @Override
    public void updateScreen() { usernameField.updateCursorCounter(); }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) return;
        switch (button.id) {
            case 0: handleLogin(); break;
            case 1: handleGenerateRandom(); break;
            case 2: mc.displayGuiScreen(previousScreen); break;
        }
    }

    private void handleLogin() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) return;
        boolean ok = CrackedAuth.login(username);
        mc.displayGuiScreen(new GuiAccountManager(previousScreen));
    }

    private void handleGenerateRandom() {
        CompletableFuture.runAsync(() -> {
            String name = UsernameGenerator.generate();
            SwingUtilities.invokeLater(() -> usernameField.setText(name));
        });
    }
}
