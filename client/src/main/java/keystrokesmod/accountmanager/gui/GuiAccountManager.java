package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import keystrokesmod.accountmanager.AccountAuthStatus;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.PlayerHeadCache;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountLogin;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.CrackedAuth;
import keystrokesmod.accountmanager.auth.SessionManager;
import keystrokesmod.accountmanager.gui.GuiAddAccount;
import keystrokesmod.accountmanager.gui.GuiChangeName;
import keystrokesmod.accountmanager.gui.GuiChangeSkin;
import keystrokesmod.accountmanager.gui.GuiLocaltsMenu;
import keystrokesmod.accountmanager.gui.GuiLocaltsSetup;
import keystrokesmod.accountmanager.gui.GuiNicealtsMenu;
import keystrokesmod.accountmanager.gui.GuiNicealtsSetup;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

public class GuiAccountManager
extends GuiScreen {
    protected final GuiScreen previousScreen;
    private GuiButton loginButton = null;
    private GuiButton deleteButton = null;
    private GuiButton cancelButton = null;
    private GuiButton restoreButton = null;
    private GuiButton renameButton = null;
    private GuiButton skinButton = null;
    private GuiButton deleteInvalidButton = null;
    private GuiButton pasteTokenButton = null;
    private GuiButton nicealtsButton = null;
    private GuiButton localtsButton = null;
    private GuiAccountList guiAccountList = null;
    public static Notification notification = null;
    private int selectedAccount = -1;
    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private volatile boolean checkingInvalid = false;

    public GuiAccountManager(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    public GuiAccountManager(GuiScreen previousScreen, Notification notification) {
        this.previousScreen = previousScreen;
        GuiAccountManager.notification = notification;
    }

    public void initGui() {
        AccountManager.load();
        Keyboard.enableRepeatEvents((boolean)true);
        this.buttonList.clear();
        if (SessionManager.getLaunchSession() != null) {
            String launchName = SessionManager.getLaunchSession().getUsername();
            String label = "Restore: " + launchName;
            int restoreWidth = Math.min(220, this.fontRendererObj.getStringWidth(label) + 12);
            this.restoreButton = new GuiButton(4, this.width - restoreWidth - 6, 6, restoreWidth, 20, label);
            this.buttonList.add(this.restoreButton);
        } else {
            this.restoreButton = null;
        }
        int colWidth = 98;
        int colGap = 4;
        int col1 = this.width / 2 - (colWidth * 3 + colGap * 2) / 2;
        int col2 = col1 + colWidth + colGap;
        int col3 = col2 + colWidth + colGap;
        int totalRowWidth = colWidth * 3 + colGap * 2;
        int wideWidth = (totalRowWidth - colGap) / 2;
        // Services row — Localts | NiceAlts
        int servRow = this.height - 100;
        this.localtsButton  = new GuiButton(9,  col1,                    servRow, wideWidth, 20, "Localts");
        this.nicealtsButton = new GuiButton(10, col1 + wideWidth + colGap, servRow, wideWidth, 20, "NiceAlts");
        this.buttonList.add(this.localtsButton);
        this.buttonList.add(this.nicealtsButton);
        // Delete invalid | Paste token row
        int newRow = this.height - 76;
        this.deleteInvalidButton = new GuiButton(7, col1, newRow, wideWidth, 20, "Delete invalid");
        this.buttonList.add(this.deleteInvalidButton);
        this.pasteTokenButton = new GuiButton(8, col1 + wideWidth + colGap, newRow, wideWidth, 20, "Paste token");
        this.buttonList.add(this.pasteTokenButton);
        int row1 = this.height - 52;
        int row2 = this.height - 28;
        this.loginButton = new GuiButton(0, col1, row1, colWidth, 20, "Login");
        this.buttonList.add(this.loginButton);
        this.renameButton = new GuiButton(5, col2, row1, colWidth, 20, "Rename");
        this.buttonList.add(this.renameButton);
        this.buttonList.add(new GuiButton(1, col3, row1, colWidth, 20, "Add"));
        this.deleteButton = new GuiButton(2, col1, row2, colWidth, 20, "Delete");
        this.buttonList.add(this.deleteButton);
        this.skinButton = new GuiButton(6, col2, row2, colWidth, 20, "Skin");
        this.buttonList.add(this.skinButton);
        this.cancelButton = new GuiButton(3, col3, row2, colWidth, 20, "Cancel");
        this.buttonList.add(this.cancelButton);
        int listBottom = servRow - 8;
        this.guiAccountList = new GuiAccountList(this.mc, listBottom);
        this.guiAccountList.registerScrollButtons(11, 12);
        this.updateScreen();
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents((boolean)false);
        if (this.task != null && !this.task.isDone()) {
            this.task.cancel(true);
            this.executor.shutdownNow();
        }
    }

    public void updateScreen() {
        if (this.loginButton != null && this.deleteButton != null) {
            this.deleteButton.enabled = this.selectedAccount >= 0;
            this.loginButton.enabled = this.deleteButton.enabled;
            if (this.task != null && !this.task.isDone()) {
                this.loginButton.enabled = false;
            }
        }
        if (this.renameButton != null) {
            this.renameButton.enabled = this.selectedAccount >= 0 && (this.task == null || this.task.isDone());
        }
        if (this.skinButton != null) {
            this.skinButton.enabled = this.selectedAccount >= 0 && (this.task == null || this.task.isDone());
        }
        if (this.deleteInvalidButton != null) {
            this.deleteInvalidButton.enabled = !checkingInvalid && !AccountManager.accounts.isEmpty()
                    && (this.task == null || this.task.isDone());
        }
        if (this.pasteTokenButton != null) {
            this.pasteTokenButton.enabled = (this.task == null || this.task.isDone()) && !checkingInvalid;
        }
        if (this.nicealtsButton != null) this.nicealtsButton.enabled = true;
        if (this.localtsButton  != null) this.localtsButton.enabled  = true;
        this.updateRestoreButtonState();
    }

    private void updateRestoreButtonState() {
        if (this.restoreButton == null) {
            return;
        }
        this.restoreButton.enabled = !SessionManager.isUsingLaunchSession() && (this.task == null || this.task.isDone());
    }

    public void drawScreen(int mouseX, int mouseY, float renderPartialTicks) {
        if (this.guiAccountList != null) {
            this.guiAccountList.drawScreen(mouseX, mouseY, renderPartialTicks);
        }
        super.drawScreen(mouseX, mouseY, renderPartialTicks);
        this.drawCenteredString(this.fontRendererObj, TextFormatting.translate(String.format("&rLumiere Account Manager &8(&7%s&8)&r", AccountManager.accounts.size())), this.width / 2, 20, -1);
        String text = TextFormatting.translate(String.format("&7Username: &3%s&r", SessionManager.get().getUsername()));
        this.mc.currentScreen.drawString(this.mc.fontRendererObj, text, 3, 3, -1);
        if (notification != null && !notification.isExpired()) {
            String notificationText = notification.getMessage();
            Gui.drawRect((int)(this.mc.currentScreen.width / 2 - this.mc.fontRendererObj.getStringWidth(notificationText) / 2 - 3), (int)4, (int)(this.mc.currentScreen.width / 2 + this.mc.fontRendererObj.getStringWidth(notificationText) / 2 + 3), (int)(7 + this.mc.fontRendererObj.FONT_HEIGHT + 2), (int)0x64000000);
            this.mc.currentScreen.drawCenteredString(this.mc.fontRendererObj, notification.getMessage(), this.mc.currentScreen.width / 2, 7, -1);
        }
    }

    public void handleMouseInput() throws IOException {
        if (this.guiAccountList != null) {
            this.guiAccountList.handleMouseInput();
        }
        super.handleMouseInput();
    }

    protected void keyTyped(char typedChar, int keyCode) {
        switch (keyCode) {
            case 200: {
                if (this.selectedAccount <= 0) break;
                --this.selectedAccount;
                if (!GuiScreen.isCtrlKeyDown()) break;
                Collections.swap(AccountManager.accounts, this.selectedAccount, this.selectedAccount + 1);
                AccountManager.save();
                break;
            }
            case 208: {
                if (this.selectedAccount >= AccountManager.accounts.size() - 1) break;
                ++this.selectedAccount;
                if (!GuiScreen.isCtrlKeyDown()) break;
                Collections.swap(AccountManager.accounts, this.selectedAccount, this.selectedAccount - 1);
                AccountManager.save();
                break;
            }
            case 28: {
                this.actionPerformed(this.loginButton);
                break;
            }
            case 211: {
                this.actionPerformed(this.deleteButton);
                break;
            }
            case 1: {
                this.actionPerformed(this.cancelButton);
            }
        }
        if (GuiScreen.isKeyComboCtrlC((int)keyCode) && this.selectedAccount >= 0) {
            GuiScreen.setClipboardString((String)AccountManager.accounts.get(this.selectedAccount).getUsername());
        }
    }

    protected void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }
        if (button.enabled) {
            switch (button.id) {
                case 0: {
                    Account account;
                    String username;
                    if (this.task != null && !this.task.isDone()) break;
                    if (this.executor == null) {
                        this.executor = Executors.newSingleThreadExecutor();
                    }
                    String string = username = StringUtils.isBlank((CharSequence)(account = AccountManager.accounts.get(this.selectedAccount)).getUsername()) ? "???" : account.getUsername();
                    if (account.getType() == AccountType.CRACKED) {
                        boolean loginSuccess = CrackedAuth.login(account.getUsername());
                        account.authStatus = loginSuccess ? AccountAuthStatus.AUTHED : AccountAuthStatus.FAILED;
                        notification = loginSuccess ? new Notification(TextFormatting.translate(String.format("&aSuccessful login! (%s)&r", account.getUsername())), 5000L) : new Notification(TextFormatting.translate(String.format("&cFailed to log in! (%s)&r", account.getUsername())), 5000L);
                        if (loginSuccess) {
                            this.mc.displayGuiScreen(this.previousScreen);
                        } else {
                            this.updateScreen();
                        }
                        return;
                    }
                    account.authStatus = AccountAuthStatus.WORKING;
                    notification = new Notification(TextFormatting.translate(String.format("&7Fetching your Minecraft profile... (%s)&r", username)), -1L);
                    Account loginAccount = account;
                    this.updateScreen();
                    this.task = AccountLogin.login(loginAccount, this.executor).whenComplete((ignored, error) ->
                            this.mc.addScheduledTask(() -> {
                                if (loginAccount.authStatus == AccountAuthStatus.AUTHED) {
                                    this.mc.displayGuiScreen(this.previousScreen);
                                } else {
                                    this.updateScreen();
                                }
                            }));
                    break;
                }
                case 1: {
                    this.mc.displayGuiScreen((GuiScreen)new GuiAddAccount(this.previousScreen));
                    break;
                }
                case 2: {
                    if (this.selectedAccount <= -1 || this.selectedAccount >= AccountManager.accounts.size()) break;
                    AccountManager.accounts.remove(this.selectedAccount);
                    AccountManager.save();
                    this.selectedAccount = -1;
                    this.updateScreen();
                    break;
                }
                case 3: {
                    this.mc.displayGuiScreen(this.previousScreen);
                    break;
                }
                case 4: {
                    SessionManager.restoreLaunchSession();
                    notification = new Notification(TextFormatting.translate(String.format("&aRestored launch session (%s)&r", SessionManager.get().getUsername())), 5000L);
                    this.updateScreen();
                    break;
                }
                case 5: {
                    if (this.selectedAccount <= -1 || this.selectedAccount >= AccountManager.accounts.size()) break;
                    this.mc.displayGuiScreen((GuiScreen)new GuiChangeName(this, AccountManager.accounts.get(this.selectedAccount)));
                    break;
                }
                case 6: {
                    if (this.selectedAccount <= -1 || this.selectedAccount >= AccountManager.accounts.size()) break;
                    this.mc.displayGuiScreen((GuiScreen)new GuiChangeSkin(this, AccountManager.accounts.get(this.selectedAccount)));
                    break;
                }
                case 9: {
                    // Localts
                    String lkey = GuiLocaltsSetup.loadKey();
                    if (lkey != null) {
                        this.mc.displayGuiScreen(new GuiLocaltsMenu(this, lkey));
                    } else {
                        this.mc.displayGuiScreen(new GuiLocaltsSetup(this));
                    }
                    break;
                }
                case 10: {
                    // NiceAlts
                    String nkey = GuiNicealtsSetup.loadKey();
                    if (nkey != null) {
                        this.mc.displayGuiScreen(new GuiNicealtsMenu(this, nkey));
                    } else {
                        this.mc.displayGuiScreen(new GuiNicealtsSetup(this));
                    }
                    break;
                }
                default: {
                    this.guiAccountList.actionPerformed(button);
                }
                case 7: {
                    // Delete invalid — check all non-cracked accounts, remove ones that fail auth
                    if (AccountManager.accounts.isEmpty() || checkingInvalid) break;
                    if (this.task != null && !this.task.isDone()) break;
                    checkingInvalid = true;
                    this.updateScreen();
                    List<Account> snapshot = new ArrayList<>(AccountManager.accounts);
                    Session savedSession = SessionManager.get();
                    int total = snapshot.size();
                    notification = new Notification(TextFormatting.translate("&7Checking accounts 0/" + total + "..."), -1L);
                    new Thread(() -> {
                        List<Account> invalid = new ArrayList<>();
                        int checked = 0;
                        for (Account acc : snapshot) {
                            if (acc.getType() == AccountType.CRACKED) { checked++; continue; }
                            acc.authStatus = AccountAuthStatus.WORKING;
                            final int c = ++checked;
                            this.mc.addScheduledTask(() -> notification = new Notification(
                                    TextFormatting.translate("&7Checking " + c + "/" + total + "..."), -1L));
                            ExecutorService checkExec = Executors.newSingleThreadExecutor();
                            try {
                                AccountLogin.login(acc, checkExec).get(20L, TimeUnit.SECONDS);
                                if (acc.authStatus == AccountAuthStatus.FAILED
                                        || StringUtils.isBlank(acc.getUsername())) {
                                    invalid.add(acc);
                                    acc.authStatus = AccountAuthStatus.FAILED;
                                }
                            } catch (Exception e) {
                                invalid.add(acc);
                                acc.authStatus = AccountAuthStatus.FAILED;
                            } finally {
                                checkExec.shutdownNow();
                            }
                            try { Thread.sleep(300L); } catch (InterruptedException ignored) { break; }
                        }
                        // Restore original session so we don't accidentally switch accounts
                        if (savedSession != null) SessionManager.set(savedSession);
                        AccountManager.accounts.removeAll(invalid);
                        AccountManager.save();
                        final int removed = invalid.size();
                        this.mc.addScheduledTask(() -> {
                            checkingInvalid = false;
                            notification = new Notification(
                                    TextFormatting.translate("&aRemoved " + removed + " invalid account(s)"), 5000L);
                            this.updateScreen();
                        });
                    }, "raven-delete-invalid").start();
                    break;
                }
                case 8: {
                    // Paste token from clipboard — auto-detect refresh vs access token
                    String clipboard = GuiScreen.getClipboardString().trim();
                    if (clipboard.isEmpty()) {
                        notification = new Notification(TextFormatting.translate("&cClipboard is empty"), 3000L);
                        break;
                    }
                    if (this.task != null && !this.task.isDone()) break;
                    if (this.executor == null) this.executor = Executors.newSingleThreadExecutor();
                    // Refresh tokens start with 'M' and are long; everything else treat as access token
                    boolean isRefresh = clipboard.startsWith("M") && clipboard.length() > 20;
                    Account newAcc = isRefresh
                            ? new Account(clipboard, "", "", "", 0L, AccountType.REFRESH)
                            : new Account("", clipboard, "", "", 0L, AccountType.TOKEN);
                    newAcc.authStatus = AccountAuthStatus.WORKING;
                    notification = new Notification(TextFormatting.translate("&7Verifying token..."), -1L);
                    AccountManager.accounts.add(newAcc);
                    this.updateScreen();
                    this.task = AccountLogin.login(newAcc, this.executor).whenComplete((v, err) ->
                            this.mc.addScheduledTask(() -> {
                                if (StringUtils.isBlank(newAcc.getUsername())) {
                                    AccountManager.accounts.remove(newAcc);
                                    notification = new Notification(
                                            TextFormatting.translate("&cToken invalid or expired"), 5000L);
                                } else {
                                    AccountManager.save();
                                }
                                this.updateScreen();
                            }));
                    break;
                }
            }
        }
    }

    class GuiAccountList
    extends GuiSlot {
        public GuiAccountList(Minecraft mc, int listBottom) {
            super(mc, GuiAccountManager.this.width, GuiAccountManager.this.height, 32, listBottom, 36);
        }

        protected int getSize() {
            return AccountManager.accounts.size();
        }

        protected boolean isSelected(int slotIndex) {
            return slotIndex == GuiAccountManager.this.selectedAccount;
        }

        protected int getScrollBarX() {
            return (this.width + this.getListWidth()) / 2 + 2;
        }

        public int getListWidth() {
            return 308;
        }

        protected int getContentHeight() {
            return AccountManager.accounts.size() * 36;
        }

        protected void elementClicked(int slotIndex, boolean isDoubleClick, int mouseX, int mouseY) {
            GuiAccountManager.this.selectedAccount = slotIndex;
            GuiAccountManager.this.updateScreen();
            if (isDoubleClick) {
                GuiAccountManager.this.actionPerformed(GuiAccountManager.this.loginButton);
            }
        }

        protected void drawBackground() {
            GuiAccountManager.this.drawDefaultBackground();
        }

        protected void drawSlot(int entryID, int x, int y, int k, int mouseXIn, int mouseYIn) {
            FontRenderer fr = GuiAccountManager.this.fontRendererObj;
            Account account = AccountManager.accounts.get(entryID);
            String rawUsername = account.getUsername();

            // ── Player head ───────────────────────────────────────────────
            ResourceLocation head = PlayerHeadCache.get(StringUtils.isBlank(rawUsername) ? null : rawUsername);
            if (head != null) {
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                GuiAccountManager.this.mc.getTextureManager().bindTexture(head);
                Gui.drawScaledCustomSizeModalRect(x + 2, y + 2, 0, 0, 32, 32, 32, 32, 32.0f, 32.0f);
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            } else {
                drawRect(x + 2, y + 2, x + 34, y + 34, 0xFF333333);
            }

            int textX = x + 38;

            // ── Username + type ───────────────────────────────────────────
            String username = StringUtils.isBlank(rawUsername) ? "&7&l?" : rawUsername;
            if (SessionManager.get() != null && !StringUtils.isBlank(rawUsername)
                    && rawUsername.equals(SessionManager.get().getUsername())) {
                username = "&a&l" + rawUsername;
            }
            String accountTypeSuffix;
            switch (account.getType()) {
                case CRACKED:  accountTypeSuffix = " &7(Cracked)";  break;
                case COOKIE:   accountTypeSuffix = " &7(Cookie)";   break;
                case REFRESH:  accountTypeSuffix = " &7(Refresh)";  break;
                case TOKEN:    accountTypeSuffix = " &7(Token)";    break;
                default:       accountTypeSuffix = " &7(Premium)";  break;
            }
            String translatedUsername = TextFormatting.translate(String.format("&r%s", username));
            String translatedSuffix   = TextFormatting.translate(accountTypeSuffix);
            GuiAccountManager.this.drawString(fr, translatedUsername, textX, y + 3, -1);
            GuiAccountManager.this.drawString(fr, translatedSuffix, textX + fr.getStringWidth(translatedUsername), y + 3, -1);

            // ── Auth status (second line) ─────────────────────────────────
            AccountAuthStatus status = account.authStatus;
            String statusText = null;
            if (status == AccountAuthStatus.WORKING) {
                statusText = TextFormatting.translate("&6Logging in...");
            } else if (status == AccountAuthStatus.FAILED) {
                statusText = TextFormatting.translate("&cInvalid / Expired");
            } else if (status == AccountAuthStatus.AUTHED) {
                statusText = TextFormatting.translate("&aLogged in");
            }
            if (statusText != null) {
                GuiAccountManager.this.drawString(fr, statusText, textX, y + 14, -1);
            }

            // ── Ban indicator (bottom-right) ──────────────────────────────
            long currentTime = System.currentTimeMillis();
            long unbanTime = account.getUnban();
            String unban;
            if (unbanTime < 0L) {
                unban = "&4&l\u26a0";
            } else if (unbanTime <= currentTime) {
                unban = "&2&l\u2714";
            } else {
                long diff = unbanTime - currentTime;
                long s = diff / 1000L % 60L;
                long m = diff / 60000L % 60L;
                long h = diff / 3600000L % 24L;
                long d = diff / 86400000L;
                String banStr = (d > 0 ? d + "d " : "") + (h > 0 ? h + "h " : "") + (m > 0 ? m + "m " : "") + (s > 0 ? s + "s" : "");
                unban = banStr.trim() + " &c&l\u26a0";
            }
            String unbanText = TextFormatting.translate(String.format("&r%s&r", unban));
            GuiAccountManager.this.drawString(fr, unbanText,
                    x + getListWidth() - 5 - fr.getStringWidth(unbanText), y + 25, -1);
        }
    }
}

