package mindless.accountmanager.gui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.AccountType;
import mindless.accountmanager.auth.MicrosoftAuth;
import mindless.accountmanager.auth.SessionManager;
import mindless.accountmanager.utils.Notification;
import mindless.accountmanager.utils.TextFormatting;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.Session;
import org.lwjgl.input.Keyboard;

public class GuiChangeName extends GuiScreen {
    private final GuiScreen previousScreen;
    private final Account account;
    private GuiTextField nameField;
    private GuiButton changeButton;
    private GuiButton cancelButton;
    private String status;
    private ExecutorService executor;
    private CompletableFuture<?> task;

    public GuiChangeName(GuiScreen previousScreen, Account account) {
        this.previousScreen = previousScreen;
        this.account = account;
        this.status = "Enter the new username";
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        int cx = width/2, bw = 200, bh = 20;
        nameField = new GuiTextField(0, fontRendererObj, cx - bw/2, height/2 - 22, bw, 18);
        nameField.setMaxStringLength(16);
        nameField.setText(account.getUsername());
        nameField.setFocused(true);
        nameField.setEnableBackgroundDrawing(false);

        changeButton = new GuiButton(0, cx - bw/2, height/2 + 4,  bw, bh, "Change Name");
        cancelButton = new GuiButton(1, cx - bw/2, height/2 + 28, bw, bh, "Cancel");
        buttonList.add(changeButton);
        buttonList.add(cancelButton);

        if (account.getType() == AccountType.CRACKED) {
            status = "Cracked accounts can't be renamed";
            changeButton.enabled = false;
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (task != null && !task.isDone()) { task.cancel(true); executor.shutdownNow(); }
    }

    @Override
    public void updateScreen() { nameField.updateCursorCounter(); }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawRect(0, 0, width, height, GuiAccountManager.C_BG);
        RavenFontRenderer sfBold = FontManager.getClickGuiHeaderRenderer("Sf-Bold");
        RavenFontRenderer sfReg  = FontManager.getClickGuiSettingRenderer("Sf-Regular");
        RavenFontRenderer sfSm   = FontManager.getClickGuiSmallRenderer("Sf-Regular");

        int cardW = 240, cardH = 140;
        int cardX = width/2 - cardW/2, cardY = height/2 - cardH/2 - 20;
        RoundedUtils.drawRound(cardX, cardY, cardW, cardH, 6f, GuiAccountManager.C_PANEL);
        drawRect(cardX, cardY, cardX + cardW, cardY + 1, GuiAccountManager.C_ACCENT_DIM);
        sfBold.drawString("Change Username", width/2f - sfBold.getStringWidth("Change Username")/2f, cardY + 10f, GuiAccountManager.C_TEXT, false);
        sfSm.drawString(status, width/2f - sfSm.getStringWidth(status)/2f, cardY + 24f, GuiAccountManager.C_DIM, false);

        int fw = 200, fh = 22, fx = width/2 - fw/2, fy = height/2 - 26;
        RoundedUtils.drawRound(fx, fy, fw, fh, 4f, GuiAccountManager.C_ROW);
        drawRect(fx, fy + fh - 1, fx + fw, fy + fh, GuiAccountManager.C_ACCENT_DIM);
        if (nameField.getText().isEmpty() && !nameField.isFocused())
            sfReg.drawString("Username...", fx + 6f, fy + 5f, GuiAccountManager.C_DIM, false);
        nameField.drawTextBox();

        for (GuiButton b : buttonList) {
            boolean isCancel = b.id == 1;
            boolean hov = b.enabled && mx >= b.xPosition && mx < b.xPosition + b.width
                    && my >= b.yPosition && my < b.yPosition + b.height;
            int bg  = isCancel ? GuiAccountManager.C_ROW : 0xCC181A2A;
            int bgH = isCancel ? GuiAccountManager.C_ROW_HOV : 0xCC1E2035;
            int fg  = !b.enabled ? GuiAccountManager.C_DIM : (isCancel ? GuiAccountManager.C_MUTED : GuiAccountManager.C_ACCENT);
            RoundedUtils.drawRound(b.xPosition, b.yPosition, b.width, b.height, 4f, hov ? bgH : bg);
            float tw = sfReg.getStringWidth(b.displayString);
            sfReg.drawString(b.displayString, b.xPosition + b.width/2f - tw/2f,
                    b.yPosition + b.height/2f - sfReg.getFontHeight()/2f, fg, false);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { actionPerformed(cancelButton); return; }
        nameField.textboxKeyTyped(typedChar, keyCode);
        if (keyCode == 28 && changeButton.enabled) actionPerformed(changeButton);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        nameField.mouseClicked(mx, my, btn);
        super.mouseClicked(mx, my, btn);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) return;
        if (button.id == 0) handleChangeName();
        else mc.displayGuiScreen(previousScreen);
    }

    private void handleChangeName() {
        if (task != null && !task.isDone()) return;
        String newName = nameField.getText().trim();
        if (newName.isEmpty()) { status = "Please enter a username"; return; }
        if (newName.equalsIgnoreCase(account.getUsername())) { status = "That's already this account's name"; return; }
        if (executor == null || executor.isShutdown()) executor = Executors.newSingleThreadExecutor();
        changeButton.enabled = false;
        status = "Requesting name change to " + newName + "...";
        task = MicrosoftAuth.changeName(account.getAccessToken(), newName, executor).whenCompleteAsync((confirmedName, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                status = cause.getMessage();
                changeButton.enabled = true;
                return;
            }
            String oldUsername = account.getUsername();
            account.setUsername(confirmedName);
            if (SessionManager.get() != null && SessionManager.get().getUsername().equals(oldUsername)
                    && SessionManager.get().getPlayerID().equals(account.getUuid())) {
                SessionManager.set(new Session(confirmedName, account.getUuid(), account.getAccessToken(), "mojang"));
            }
            AccountManager.save();
            GuiAccountManager.notification = new Notification(TextFormatting.translate(
                    String.format("&aName changed to %s!&r", confirmedName)), 5000L);
            mc.displayGuiScreen(previousScreen);
        }, r -> mc.addScheduledTask(r));
    }
}
