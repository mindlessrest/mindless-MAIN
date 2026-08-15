package keystrokesmod.accountmanager.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.MicrosoftAuth;
import keystrokesmod.accountmanager.gui.GuiAccountManager;
import keystrokesmod.accountmanager.utils.ModernFileChooser;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

public class GuiChangeSkin
extends GuiScreen {
    private final GuiScreen previousScreen;
    private final Account account;
    private GuiButton browseButton;
    private GuiButton variantButton;
    private GuiButton uploadButton;
    private GuiButton cancelButton;
    private String status;
    private File selectedFile;
    private byte[] selectedSkin;
    private boolean slim = false;
    private ExecutorService executor;
    private CompletableFuture<?> task;

    public GuiChangeSkin(GuiScreen previousScreen, Account account) {
        this.previousScreen = previousScreen;
        this.account = account;
        this.status = TextFormatting.translate("&7Select a 64x64 or 64x32 PNG skin file&r");
    }

    public void initGui() {
        Keyboard.enableRepeatEvents((boolean)true);
        this.buttonList.clear();
        this.browseButton = new GuiButton(0, this.width / 2 - 100, this.height / 2 - 30, 200, 20, "Browse...");
        this.buttonList.add(this.browseButton);
        this.variantButton = new GuiButton(1, this.width / 2 - 100, this.height / 2 - 5, 200, 20, this.variantLabel());
        this.buttonList.add(this.variantButton);
        this.uploadButton = new GuiButton(2, this.width / 2 - 100, this.height / 2 + 20, 200, 20, "Upload");
        this.uploadButton.enabled = false;
        this.buttonList.add(this.uploadButton);
        this.cancelButton = new GuiButton(3, this.width / 2 - 100, this.height / 2 + 45, 200, 20, "Cancel");
        this.buttonList.add(this.cancelButton);
        if (this.account.getType() == AccountType.CRACKED) {
            this.status = TextFormatting.translate("&cCracked (offline) accounts don't have a real Mojang profile to reskin&r");
            this.browseButton.enabled = false;
            this.variantButton.enabled = false;
        }
    }

    private String variantLabel() {
        return "Model: " + (this.slim ? "Slim (Alex)" : "Classic (Steve)");
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents((boolean)false);
        if (this.task != null && !this.task.isDone()) {
            this.task.cancel(true);
            this.executor.shutdownNow();
        }
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, "Change Skin", this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        this.drawCenteredString(this.fontRendererObj, this.status, this.width / 2, this.height / 2 - 45, 0xAAAAAA);
        if (this.selectedFile != null) {
            this.drawCenteredString(this.fontRendererObj, TextFormatting.translate(String.format("&7Selected: &f%s&r", this.selectedFile.getName())), this.width / 2, this.height / 2 + 70, 0xAAAAAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.actionPerformed(this.cancelButton);
        }
    }

    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }
        switch (button.id) {
            case 0: {
                this.handleBrowse();
                break;
            }
            case 1: {
                this.slim = !this.slim;
                this.variantButton.displayString = this.variantLabel();
                break;
            }
            case 2: {
                this.handleUpload();
                break;
            }
            case 3: {
                this.mc.displayGuiScreen(this.previousScreen);
            }
        }
    }

    private void handleBrowse() {
        ModernFileChooser.showOpenDialog("Select Skin PNG", null, "PNG images (*.png)", new String[]{"png"}, file -> {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null || image.getWidth() != 64 || image.getHeight() != 64 && image.getHeight() != 32) {
                    this.status = TextFormatting.translate("&cInvalid skin: must be a 64x64 or 64x32 PNG&r");
                    this.selectedFile = null;
                    this.selectedSkin = null;
                    this.uploadButton.enabled = false;
                    return;
                }
                this.selectedSkin = Files.readAllBytes(file.toPath());
                this.selectedFile = file;
                this.status = TextFormatting.translate("&7Ready to upload&r");
                this.uploadButton.enabled = true;
            }
            catch (IOException e) {
                this.status = TextFormatting.translate(String.format("&cCouldn't read that file: %s&r", e.getMessage()));
                this.selectedFile = null;
                this.selectedSkin = null;
                this.uploadButton.enabled = false;
            }
        }, null);
    }

    private void handleUpload() {
        if (this.task != null && !this.task.isDone()) {
            return;
        }
        if (this.selectedSkin == null) {
            this.status = TextFormatting.translate("&cPlease select a skin file first&r");
            return;
        }
        if (this.executor == null || this.executor.isShutdown()) {
            this.executor = Executors.newSingleThreadExecutor();
        }
        this.uploadButton.enabled = false;
        this.status = TextFormatting.translate("&7Uploading skin...&r");
        String variant = this.slim ? "slim" : "classic";
        this.task = MicrosoftAuth.changeSkin(this.account.getAccessToken(), this.selectedSkin, variant, this.executor).whenCompleteAsync((ignored, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                this.status = TextFormatting.translate(String.format("&c%s&r", cause.getMessage()));
                this.uploadButton.enabled = true;
                return;
            }
            GuiAccountManager.notification = new Notification(TextFormatting.translate(String.format("&aSkin updated for %s!&r", this.account.getUsername())), 5000L);
            this.mc.displayGuiScreen(this.previousScreen);
        }, runnable -> this.mc.addScheduledTask(runnable));
    }
}

