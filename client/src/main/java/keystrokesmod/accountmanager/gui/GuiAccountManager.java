package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountLogin;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.CrackedAuth;
import keystrokesmod.accountmanager.auth.SessionManager;
import keystrokesmod.accountmanager.gui.GuiAddAccount;
import keystrokesmod.accountmanager.gui.GuiChangeName;
import keystrokesmod.accountmanager.gui.GuiChangeSkin;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
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
    private GuiAccountList guiAccountList = null;
    public static Notification notification = null;
    private int selectedAccount = -1;
    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;

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
        this.guiAccountList = new GuiAccountList(this.mc, row1 - 8);
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
            boolean bl = this.renameButton.enabled = this.selectedAccount >= 0 && (this.task == null || this.task.isDone());
        }
        if (this.skinButton != null) {
            this.skinButton.enabled = this.selectedAccount >= 0 && (this.task == null || this.task.isDone());
        }
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
                        notification = loginSuccess ? new Notification(TextFormatting.translate(String.format("&aSuccessful login! (%s)&r", account.getUsername())), 5000L) : new Notification(TextFormatting.translate(String.format("&cFailed to log in! (%s)&r", account.getUsername())), 5000L);
                        this.updateScreen();
                        return;
                    }
                    notification = new Notification(TextFormatting.translate(String.format("&7Fetching your Minecraft profile... (%s)&r", username)), -1L);
                    Account loginAccount = account;
                    this.updateScreen();
                    this.task = AccountLogin.login(loginAccount, this.executor).whenComplete((ignored, error) -> this.mc.addScheduledTask(this::updateScreen));
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
                default: {
                    this.guiAccountList.actionPerformed(button);
                }
            }
        }
    }

    class GuiAccountList
    extends GuiSlot {
        public GuiAccountList(Minecraft mc, int listBottom) {
            super(mc, GuiAccountManager.this.width, GuiAccountManager.this.height, 32, listBottom, 16);
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
            return AccountManager.accounts.size() * 16;
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

        protected void drawSlot(int entryID, int x, int int_4, int k, int mouseXIn, int mouseYIn) {
            String unban;
            String accountTypeSuffix;
            FontRenderer fr = GuiAccountManager.this.fontRendererObj;
            Account account = AccountManager.accounts.get(entryID);
            String username = account.getUsername();
            if (StringUtils.isBlank((CharSequence)username)) {
                username = "&7&l?";
            }
            if (SessionManager.get() != null) {
                if (account.getType() == AccountType.CRACKED && username.equals(SessionManager.get().getUsername())) {
                    username = String.format("&a&l%s", username);
                } else if (account.getType() != AccountType.CRACKED && account.getUsername().equals(SessionManager.get().getUsername())) {
                    username = String.format("&a&l%s", username);
                }
            }
            switch (account.getType()) {
                case CRACKED: {
                    accountTypeSuffix = " &7(Cracked)";
                    break;
                }
                case COOKIE: {
                    accountTypeSuffix = " &7(Cookie)";
                    break;
                }
                case REFRESH: {
                    accountTypeSuffix = " &7(Refresh)";
                    break;
                }
                case TOKEN: {
                    accountTypeSuffix = " &7(Token)";
                    break;
                }
                default: {
                    accountTypeSuffix = " &7(Premium)";
                }
            }
            String translatedUsername = TextFormatting.translate(String.format("&r%s", username));
            String translatedSuffix = TextFormatting.translate(accountTypeSuffix);
            GuiAccountManager.this.drawString(fr, translatedUsername, x + 2, int_4 + 2, -1);
            GuiAccountManager.this.drawString(fr, translatedSuffix, x + 2 + fr.getStringWidth(translatedUsername), int_4 + 2, -1);
            long currentTime = System.currentTimeMillis();
            long unbanTime = account.getUnban();
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
                unban = String.format("%s%s%s%s", d > 0L ? String.format("%dd", d) : "", h > 0L ? String.format(" %dh", h) : "", m > 0L ? String.format(" %dm", m) : "", s > 0L ? String.format(" %ds", s) : "");
                unban = unban.trim();
                unban = String.format("%s &c&l\u26a0", unban);
            }
            unban = TextFormatting.translate(String.format("&r%s&r", unban));
            GuiAccountManager.this.drawString(fr, unban, x + this.getListWidth() - 5 - fr.getStringWidth(unban), int_4 + 2, -1);
        }
    }
}

