package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.MicrosoftAuth;
import keystrokesmod.accountmanager.auth.SessionManager;
import keystrokesmod.accountmanager.gui.GuiAccountManager;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.Session;
import org.lwjgl.input.Keyboard;

public class GuiChangeName
extends GuiScreen {
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
        this.status = TextFormatting.translate("&7Enter the new username&r");
    }

    public void initGui() {
        Keyboard.enableRepeatEvents((boolean)true);
        this.buttonList.clear();
        this.nameField = new GuiTextField(0, this.fontRendererObj, this.width / 2 - 100, this.height / 2 - 30, 200, 20);
        this.nameField.setMaxStringLength(16);
        this.nameField.setText(this.account.getUsername());
        this.nameField.setFocused(true);
        this.changeButton = new GuiButton(0, this.width / 2 - 100, this.height / 2, 200, 20, "Change Name");
        this.buttonList.add(this.changeButton);
        this.cancelButton = new GuiButton(1, this.width / 2 - 100, this.height / 2 + 25, 200, 20, "Cancel");
        this.buttonList.add(this.cancelButton);
        if (this.account.getType() == AccountType.CRACKED) {
            this.status = TextFormatting.translate("&cCracked (offline) accounts don't have a real Mojang profile to rename&r");
            this.changeButton.enabled = false;
        }
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents((boolean)false);
        if (this.task != null && !this.task.isDone()) {
            this.task.cancel(true);
            this.executor.shutdownNow();
        }
    }

    public void updateScreen() {
        this.nameField.updateCursorCounter();
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, "Change Username", this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        this.drawCenteredString(this.fontRendererObj, this.status, this.width / 2, this.height / 2 - 45, 0xAAAAAA);
        this.nameField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.actionPerformed(this.cancelButton);
            return;
        }
        this.nameField.textboxKeyTyped(typedChar, keyCode);
        if (keyCode == 28 && this.changeButton.enabled) {
            this.actionPerformed(this.changeButton);
        }
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }
        switch (button.id) {
            case 0: {
                this.handleChangeName();
                break;
            }
            case 1: {
                this.mc.displayGuiScreen(this.previousScreen);
            }
        }
    }

    private void handleChangeName() {
        if (this.task != null && !this.task.isDone()) {
            return;
        }
        String newName = this.nameField.getText().trim();
        if (newName.isEmpty()) {
            this.status = TextFormatting.translate("&cPlease enter a username&r");
            return;
        }
        if (newName.equalsIgnoreCase(this.account.getUsername())) {
            this.status = TextFormatting.translate("&cThat's already this account's name&r");
            return;
        }
        if (this.executor == null || this.executor.isShutdown()) {
            this.executor = Executors.newSingleThreadExecutor();
        }
        this.changeButton.enabled = false;
        this.status = TextFormatting.translate(String.format("&7Requesting name change to %s...&r", newName));
        this.task = MicrosoftAuth.changeName(this.account.getAccessToken(), newName, this.executor).whenCompleteAsync((confirmedName, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                this.status = TextFormatting.translate(String.format("&c%s&r", cause.getMessage()));
                this.changeButton.enabled = true;
                return;
            }
            String oldUsername = this.account.getUsername();
            this.account.setUsername((String)confirmedName);
            if (SessionManager.get() != null && SessionManager.get().getUsername().equals(oldUsername) && SessionManager.get().getPlayerID().equals(this.account.getUuid())) {
                SessionManager.set(new Session(confirmedName, this.account.getUuid(), this.account.getAccessToken(), "mojang"));
            }
            AccountManager.save();
            GuiAccountManager.notification = new Notification(TextFormatting.translate(String.format("&aName changed to %s!&r", confirmedName)), 5000L);
            this.mc.displayGuiScreen(this.previousScreen);
        }, runnable -> this.mc.addScheduledTask(runnable));
    }
}

