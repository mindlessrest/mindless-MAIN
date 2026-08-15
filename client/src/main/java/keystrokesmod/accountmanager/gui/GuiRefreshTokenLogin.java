package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.RefreshTokenAuth;
import keystrokesmod.accountmanager.gui.GuiAccountManager;
import keystrokesmod.accountmanager.gui.GuiTextArea;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

public class GuiRefreshTokenLogin
extends GuiScreen {
    private static final Pattern MICROSOFT_REFRESH_TOKEN = Pattern.compile("M\\.C[A-Za-z0-9._!*$\\-]+");
    private final GuiScreen previousScreen;
    private GuiTextArea tokenField;
    private GuiButton loginButton;
    private GuiButton cancelButton;
    private String status = "\u00a77Enter Microsoft OAuth refresh token(s)\u00a7r";
    private ExecutorService executor;
    private CompletableFuture<Void> task;

    public GuiRefreshTokenLogin(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    public void initGui() {
        Keyboard.enableRepeatEvents((boolean)true);
        this.buttonList.clear();
        this.loginButton = new GuiButton(0, this.width / 2 - 100, this.height / 2 + 30, 200, 20, "Login Account(s)");
        this.buttonList.add(this.loginButton);
        this.cancelButton = new GuiButton(1, this.width / 2 - 100, this.height / 2 + 55, 200, 20, "Cancel");
        this.buttonList.add(this.cancelButton);
        this.tokenField = new GuiTextArea(2, this.fontRendererObj, this.width / 2 - 100, this.height / 2 - 60, 200, 80);
        this.tokenField.setMaxStringLength(50000);
        this.tokenField.setFocused(true);
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents((boolean)false);
        if (this.task != null && !this.task.isDone()) {
            this.task.cancel(true);
            if (this.executor != null && !this.executor.isShutdown()) {
                this.executor.shutdownNow();
            }
        }
    }

    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) {
            return;
        }
        switch (button.id) {
            case 0: {
                String input = this.tokenField.getText().trim();
                if (input.isEmpty()) {
                    this.status = "\u00a7cPlease enter at least one refresh token.\u00a7r";
                    return;
                }
                this.processInputAndLogin(input);
                break;
            }
            case 1: {
                this.mc.displayGuiScreen(this.previousScreen);
            }
        }
    }

    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            this.actionPerformed(this.cancelButton);
            return;
        }
        this.tokenField.textboxKeyTyped(typedChar, keyCode);
        if (keyCode == 28 && GuiScreen.isCtrlKeyDown() && !this.tokenField.getText().trim().isEmpty()) {
            this.actionPerformed(this.loginButton);
        }
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.tokenField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public void updateScreen() {
        this.tokenField.updateCursorCounter();
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, "\u00a7fLogin with Refresh Token(s)", this.width / 2, this.height / 2 - 90, 0xFFFFFF);
        this.drawCenteredString(this.fontRendererObj, this.status, this.width / 2, this.height / 2 - 75, 0xAAAAAA);
        this.tokenField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void processInputAndLogin(String fullInput) {
        List<String> refreshTokens;
        if (this.executor == null || this.executor.isShutdown()) {
            this.executor = Executors.newFixedThreadPool(3);
        }
        if ((refreshTokens = GuiRefreshTokenLogin.extractRefreshTokens(fullInput)).isEmpty()) {
            this.status = "\u00a7cNo valid refresh tokens found.\u00a7r";
            return;
        }
        this.status = "\u00a77Processing accounts...\u00a7r";
        this.loginButton.enabled = false;
        ArrayList<CompletableFuture<Void>> loginTasks = new ArrayList<CompletableFuture<Void>>();
        ArrayList<String> failedAccounts = new ArrayList<String>();
        ArrayList<String> successfulAccounts = new ArrayList<String>();
        for (String refreshToken : refreshTokens) {
            CompletableFuture<Void> task = RefreshTokenAuth.authenticate(refreshToken, this.executor).thenAcceptAsync((Account account) -> {
                Optional<Account> existing = AccountManager.accounts.stream().filter(stored -> stored.getRefreshToken().equals(refreshToken) || stored.getAccessToken().equals(account.getAccessToken())).findFirst();
                if (existing.isPresent()) {
                    Account stored2 = existing.get();
                    stored2.setRefreshToken(account.getRefreshToken());
                    stored2.setAccessToken(account.getAccessToken());
                    stored2.setUsername(account.getUsername());
                    stored2.setType(AccountType.REFRESH);
                    stored2.setUuid(account.getUuid());
                } else {
                    AccountManager.accounts.add((Account)account);
                }
                successfulAccounts.add(account.getUsername());
            }, (Executor)this.executor).exceptionally((Throwable error) -> {
                String errorMessage = "Login failed!";
                if (error != null) {
                    Throwable cause = error.getCause();
                    errorMessage = cause != null ? cause.getMessage() : error.getMessage();
                }
                String preview = refreshToken.length() > 30 ? refreshToken.substring(0, 30) + "..." : refreshToken;
                failedAccounts.add("\u00a7cFailed (" + errorMessage + ") for token: " + preview + "\u00a7r");
                System.err.println("Error processing refresh token: " + preview + " - " + errorMessage);
                return null;
            });
            loginTasks.add(task);
        }
        this.task = CompletableFuture.allOf(loginTasks.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
            AccountManager.save();
            this.mc.addScheduledTask(() -> {
                String finalMessage = !successfulAccounts.isEmpty() && failedAccounts.isEmpty() ? String.format("\u00a7aSuccessfully logged in %d account(s)!\u00a7r", successfulAccounts.size()) : (successfulAccounts.isEmpty() && !failedAccounts.isEmpty() ? String.format("\u00a7cFailed to log in %d account(s).\u00a7r", failedAccounts.size()) : String.format("\u00a7aLogged in %d, \u00a7cfailed %d account(s).\u00a7r", successfulAccounts.size(), failedAccounts.size()));
                this.mc.displayGuiScreen((GuiScreen)new GuiAccountManager(this.previousScreen, new Notification(TextFormatting.translate(finalMessage), 5000L)));
                if (!failedAccounts.isEmpty()) {
                    System.err.println("Failed refresh token details:");
                    for (String failure : failedAccounts) {
                        System.err.println(failure);
                    }
                }
            });
        }, this.executor).exceptionally((Throwable totalError) -> {
            this.mc.addScheduledTask(() -> {
                this.status = "\u00a7cAn unexpected error occurred during batch processing.\u00a7r";
                this.loginButton.enabled = true;
            });
            return null;
        });
    }

    private static List<String> extractRefreshTokens(String input) {
        ArrayList<String> tokens = new ArrayList<String>();
        String normalized = input.trim();
        if (normalized.toLowerCase().startsWith("refreshtoken:")) {
            normalized = normalized.substring(normalized.indexOf(58) + 1).trim();
        }
        String collapsed = normalized.replaceAll("\\s+", "");
        Matcher matcher = MICROSOFT_REFRESH_TOKEN.matcher(collapsed);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 50 || tokens.contains(token)) continue;
            tokens.add(token);
        }
        if (!tokens.isEmpty()) {
            return tokens;
        }
        for (String line : input.split("[\\r\\n]+")) {
            String[] parts;
            if ((line = line.trim()).isEmpty()) continue;
            if (line.toLowerCase().contains("refreshtoken:") && (parts = line.split(":", 2)).length == 2) {
                line = parts[1].trim();
            }
            if (!(matcher = MICROSOFT_REFRESH_TOKEN.matcher(line = line.replaceAll("\\s+", ""))).matches() || matcher.group().length() < 50 || tokens.contains(matcher.group())) continue;
            tokens.add(matcher.group());
        }
        return tokens;
    }
}

