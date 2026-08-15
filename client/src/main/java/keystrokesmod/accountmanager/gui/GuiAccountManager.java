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
import keystrokesmod.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

public class GuiAccountManager extends GuiScreen {
    protected final GuiScreen previousScreen;

    // ── Buttons ────────────────────────────────────────────────────────────────
    private GuiButton loginButton        = null;
    private GuiButton deleteButton       = null;
    private GuiButton cancelButton       = null;
    private GuiButton restoreButton      = null;
    private GuiButton renameButton       = null;
    private GuiButton skinButton         = null;
    private GuiButton deleteInvalidButton = null;
    private GuiButton pasteTokenButton   = null;
    private GuiButton nicealtsButton     = null;
    private GuiButton localtsButton      = null;

    // ── State ──────────────────────────────────────────────────────────────────
    private GuiAccountList guiAccountList = null;
    public static Notification notification = null;

    /** Index into {@link #filteredList}, -1 if nothing selected. */
    private int selectedAccount = -1;

    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private volatile boolean checkingInvalid = false;

    // ── Search / filtering ─────────────────────────────────────────────────────
    private GuiTextField searchField;
    private final List<Account> filteredList = new ArrayList<>();
    private String lastSearch = "";

    // ── Layout constants ───────────────────────────────────────────────────────
    private static final int HEADER_H    = 30;   // top header bar height
    private static final int SEARCH_TOP  = HEADER_H + 4;
    private static final int SEARCH_H    = 20;
    private static final int LIST_TOP    = SEARCH_TOP + SEARCH_H + 4; // ≈58

    // ── Colours ────────────────────────────────────────────────────────────────
    private static final int COL_HEADER_BG  = 0xFF0C0C12;
    private static final int COL_SEP        = 0x40FFFFFF;
    private static final int COL_SEL        = 0x3346A0FF;
    private static final int COL_HOVER      = 0x12FFFFFF;
    private static final int COL_ACCENT     = 0xFF5E9BF5;
    private static final int COL_SEARCH_BG  = 0xAA0A0A10;
    private static final int COL_TOAST_BG   = 0xCC0C0C14;

    public GuiAccountManager(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    public GuiAccountManager(GuiScreen previousScreen, Notification notification) {
        this.previousScreen = previousScreen;
        GuiAccountManager.notification = notification;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Init
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void initGui() {
        AccountManager.load();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();

        // Restore-session button (top-right)
        if (SessionManager.getLaunchSession() != null) {
            String launchName = SessionManager.getLaunchSession().getUsername();
            String label = "Restore: " + launchName;
            int rw = Math.min(220, fontRendererObj.getStringWidth(label) + 12);
            restoreButton = new GuiButton(4, width - rw - 6, 5, rw, 20, label);
            buttonList.add(restoreButton);
        } else {
            restoreButton = null;
        }

        // Search field
        int sfW = 300;
        int sfX = width / 2 - sfW / 2;
        searchField = new GuiTextField(0, fontRendererObj, sfX, SEARCH_TOP, sfW, SEARCH_H);
        searchField.setMaxStringLength(64);
        searchField.setCanLoseFocus(true);

        // Button geometry
        int colW = 98, gap = 4;
        int col1 = width / 2 - (colW * 3 + gap * 2) / 2;
        int col2 = col1 + colW + gap;
        int col3 = col2 + colW + gap;
        int totalW = colW * 3 + gap * 2;
        int wideW  = (totalW - gap) / 2;

        int servRow = height - 100;
        localtsButton  = new GuiButton(9,  col1,           servRow, wideW, 20, "Localts");
        nicealtsButton = new GuiButton(10, col1 + wideW + gap, servRow, wideW, 20, "NiceAlts");
        buttonList.add(localtsButton);
        buttonList.add(nicealtsButton);

        int delRow = height - 76;
        deleteInvalidButton = new GuiButton(7, col1,           delRow, wideW, 20, "Delete invalid");
        pasteTokenButton    = new GuiButton(8, col1 + wideW + gap, delRow, wideW, 20, "Paste token");
        buttonList.add(deleteInvalidButton);
        buttonList.add(pasteTokenButton);

        int row1 = height - 52, row2 = height - 28;
        loginButton  = new GuiButton(0, col1, row1, colW, 20, "Login");
        renameButton = new GuiButton(5, col2, row1, colW, 20, "Rename");
        buttonList.add(loginButton);
        buttonList.add(renameButton);
        buttonList.add(new GuiButton(1, col3, row1, colW, 20, "Add"));

        deleteButton = new GuiButton(2, col1, row2, colW, 20, "Delete");
        skinButton   = new GuiButton(6, col2, row2, colW, 20, "Skin");
        cancelButton = new GuiButton(3, col3, row2, colW, 20, "Cancel");
        buttonList.add(deleteButton);
        buttonList.add(skinButton);
        buttonList.add(cancelButton);

        int listBottom = servRow - 6;
        guiAccountList = new GuiAccountList(mc, listBottom);
        guiAccountList.registerScrollButtons(11, 12);

        updateFilter();
        updateScreen();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (task != null && !task.isDone()) {
            task.cancel(true);
            executor.shutdownNow();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Filter
    // ══════════════════════════════════════════════════════════════════════════

    private void updateFilter() {
        String q = searchField != null ? searchField.getText().toLowerCase().trim() : "";
        Account wasSelected = (selectedAccount >= 0 && selectedAccount < filteredList.size())
                ? filteredList.get(selectedAccount) : null;

        filteredList.clear();
        for (Account acc : AccountManager.accounts) {
            if (q.isEmpty() || !StringUtils.isBlank(acc.getUsername())
                    && acc.getUsername().toLowerCase().contains(q)) {
                filteredList.add(acc);
            }
        }

        // Try to keep the same account selected after a filter change
        if (wasSelected != null) {
            int newIdx = filteredList.indexOf(wasSelected);
            selectedAccount = newIdx; // -1 if filtered out
        } else if (selectedAccount >= filteredList.size()) {
            selectedAccount = -1;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Update / state
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void updateScreen() {
        if (searchField != null) searchField.updateCursorCounter();

        String cur = searchField != null ? searchField.getText() : "";
        if (!cur.equals(lastSearch)) {
            lastSearch = cur;
            updateFilter();
        }

        boolean hasSelection = selectedAccount >= 0 && selectedAccount < filteredList.size();
        boolean busy = task != null && !task.isDone();

        if (deleteButton  != null) deleteButton.enabled  = hasSelection;
        if (loginButton   != null) loginButton.enabled   = hasSelection && !busy;
        if (renameButton  != null) renameButton.enabled  = hasSelection && !busy;
        if (skinButton    != null) skinButton.enabled    = hasSelection && !busy;
        if (deleteInvalidButton != null)
            deleteInvalidButton.enabled = !checkingInvalid && !AccountManager.accounts.isEmpty() && !busy;
        if (pasteTokenButton != null)
            pasteTokenButton.enabled = !busy && !checkingInvalid;
        if (nicealtsButton != null) nicealtsButton.enabled = true;
        if (localtsButton  != null) localtsButton.enabled  = true;

        if (restoreButton != null)
            restoreButton.enabled = !SessionManager.isUsingLaunchSession() && !busy;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Render
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void drawScreen(int mx, int my, float pt) {
        // ── Full-screen dark backdrop ────────────────────────────────────────
        drawDefaultBackground();
        drawRect(0, 0, width, height, 0xBB000000);

        // ── Account list ─────────────────────────────────────────────────────
        if (guiAccountList != null) guiAccountList.drawScreen(mx, my, pt);

        // ── Buttons ──────────────────────────────────────────────────────────
        super.drawScreen(mx, my, pt);

        // ── Header bar ───────────────────────────────────────────────────────
        drawRect(0, 0, width, HEADER_H, COL_HEADER_BG);
        drawRect(0, HEADER_H - 1, width, HEADER_H, COL_SEP);

        drawCenteredString(fontRendererObj, "\u00a7fAccount Manager", width / 2, 10, -1);

        Session sess = SessionManager.get();
        if (sess != null) {
            String loggedIn = "\u00a77Logged in: \u00a7f" + sess.getUsername();
            drawString(fontRendererObj, loggedIn, 5, 10, -1);
        }
        String countStr = "\u00a77" + AccountManager.accounts.size() + " accounts";
        drawString(fontRendererObj, countStr,
                width - fontRendererObj.getStringWidth(countStr) - 5, 10, -1);

        // ── Search field ─────────────────────────────────────────────────────
        drawRect(searchField.xPosition - 2,
                searchField.yPosition - 2,
                searchField.xPosition + searchField.width + 2,
                searchField.yPosition + searchField.height + 2,
                COL_SEARCH_BG);
        searchField.drawTextBox();
        if (searchField.getText().isEmpty() && !searchField.isFocused()) {
            drawString(fontRendererObj, "\u00a77Search accounts...",
                    searchField.xPosition + 3,
                    searchField.yPosition + (SEARCH_H - fontRendererObj.FONT_HEIGHT) / 2,
                    -1);
        }

        // ── Bottom toast notification ─────────────────────────────────────────
        if (notification != null && !notification.isExpired()) {
            String msg = notification.getMessage();
            int msgW = fontRendererObj.getStringWidth(msg);
            int pw = msgW + 14, ph = fontRendererObj.FONT_HEIGHT + 6;
            int px = width / 2 - pw / 2;
            int py = height - 14 - ph;
            drawRect(px, py, px + pw, py + ph, COL_TOAST_BG);
            drawRect(px, py, px + pw, py + 1, COL_SEP);
            drawRect(px, py + ph - 1, px + pw, py + ph, COL_SEP);
            drawCenteredString(fontRendererObj, msg, width / 2, py + 3, -1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void handleMouseInput() throws IOException {
        if (guiAccountList != null) guiAccountList.handleMouseInput();
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        if (searchField != null) searchField.mouseClicked(mx, my, btn);
        super.mouseClicked(mx, my, btn);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField != null && searchField.isFocused()) {
            searchField.textboxKeyTyped(typedChar, keyCode);
            return; // consume input while searching
        }

        switch (keyCode) {
            case 200: // up
                if (selectedAccount <= 0) break;
                --selectedAccount;
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()) {
                    Account a = filteredList.get(selectedAccount);
                    Account b = filteredList.get(selectedAccount + 1);
                    int ai = AccountManager.accounts.indexOf(a);
                    int bi = AccountManager.accounts.indexOf(b);
                    Collections.swap(AccountManager.accounts, ai, bi);
                    AccountManager.save();
                    updateFilter();
                }
                updateScreen();
                break;
            case 208: // down
                if (selectedAccount >= filteredList.size() - 1) break;
                ++selectedAccount;
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()) {
                    Account a = filteredList.get(selectedAccount);
                    Account b = filteredList.get(selectedAccount - 1);
                    int ai = AccountManager.accounts.indexOf(a);
                    int bi = AccountManager.accounts.indexOf(b);
                    Collections.swap(AccountManager.accounts, ai, bi);
                    AccountManager.save();
                    updateFilter();
                }
                updateScreen();
                break;
            case 28: // enter
                actionPerformed(loginButton);
                break;
            case 211: // delete
                actionPerformed(deleteButton);
                break;
            case 1: // escape
                actionPerformed(cancelButton);
                break;
        }

        if (GuiScreen.isKeyComboCtrlC(keyCode) && selectedAccount >= 0
                && selectedAccount < filteredList.size()) {
            GuiScreen.setClipboardString(filteredList.get(selectedAccount).getUsername());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Actions
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) return;
        switch (button.id) {

            case 0: { // Login
                if (task != null && !task.isDone()) break;
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                if (executor == null) executor = Executors.newSingleThreadExecutor();

                Account account = filteredList.get(selectedAccount);
                String username = StringUtils.isBlank(account.getUsername()) ? "???" : account.getUsername();

                if (account.getType() == AccountType.CRACKED) {
                    boolean ok = CrackedAuth.login(account.getUsername());
                    account.authStatus = ok ? AccountAuthStatus.AUTHED : AccountAuthStatus.FAILED;
                    notification = ok
                            ? new Notification(TextFormatting.translate(String.format("&aLogged in as %s&r", account.getUsername())), 5000L)
                            : new Notification(TextFormatting.translate(String.format("&cFailed to log in! (%s)&r", account.getUsername())), 5000L);
                    if (ok) mc.displayGuiScreen(previousScreen); else updateScreen();
                    return;
                }

                account.authStatus = AccountAuthStatus.WORKING;
                notification = new Notification(TextFormatting.translate(
                        String.format("&7Fetching profile... (%s)&r", username)), -1L);
                updateScreen();
                task = AccountLogin.login(account, executor).whenComplete((v, err) ->
                        mc.addScheduledTask(() -> {
                            if (account.authStatus == AccountAuthStatus.AUTHED) {
                                mc.displayGuiScreen(previousScreen);
                            } else {
                                updateScreen();
                            }
                        }));
                break;
            }

            case 1: // Add
                mc.displayGuiScreen(new GuiAddAccount(previousScreen));
                break;

            case 2: // Delete
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                Account toRemove = filteredList.get(selectedAccount);
                AccountManager.accounts.remove(toRemove);
                AccountManager.save();
                selectedAccount = -1;
                updateFilter();
                updateScreen();
                break;

            case 3: // Cancel
                mc.displayGuiScreen(previousScreen);
                break;

            case 4: // Restore session
                SessionManager.restoreLaunchSession();
                notification = new Notification(TextFormatting.translate(
                        String.format("&aRestored session (%s)&r", SessionManager.get().getUsername())), 5000L);
                updateScreen();
                break;

            case 5: // Rename
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                mc.displayGuiScreen(new GuiChangeName(this, filteredList.get(selectedAccount)));
                break;

            case 6: // Skin
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                mc.displayGuiScreen(new GuiChangeSkin(this, filteredList.get(selectedAccount)));
                break;

            case 7: { // Delete invalid
                if (AccountManager.accounts.isEmpty() || checkingInvalid) break;
                if (task != null && !task.isDone()) break;
                checkingInvalid = true;
                updateScreen();
                List<Account> snapshot = new ArrayList<>(AccountManager.accounts);
                Session saved = SessionManager.get();
                int total = snapshot.size();
                notification = new Notification(TextFormatting.translate("&7Checking 0/" + total + "..."), -1L);
                new Thread(() -> {
                    List<Account> invalid = new ArrayList<>();
                    int checked = 0;
                    for (Account acc : snapshot) {
                        if (acc.getType() == AccountType.CRACKED) { checked++; continue; }
                        acc.authStatus = AccountAuthStatus.WORKING;
                        final int c = ++checked;
                        mc.addScheduledTask(() -> notification = new Notification(
                                TextFormatting.translate("&7Checking " + c + "/" + total + "..."), -1L));
                        ExecutorService ex = Executors.newSingleThreadExecutor();
                        try {
                            AccountLogin.login(acc, ex).get(20L, TimeUnit.SECONDS);
                            if (acc.authStatus == AccountAuthStatus.FAILED
                                    || StringUtils.isBlank(acc.getUsername()))
                                invalid.add(acc);
                        } catch (Exception e) {
                            invalid.add(acc);
                            acc.authStatus = AccountAuthStatus.FAILED;
                        } finally { ex.shutdownNow(); }
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
                    }
                    if (saved != null) SessionManager.set(saved);
                    AccountManager.accounts.removeAll(invalid);
                    AccountManager.save();
                    final int removed = invalid.size();
                    mc.addScheduledTask(() -> {
                        checkingInvalid = false;
                        selectedAccount = -1;
                        updateFilter();
                        notification = new Notification(
                                TextFormatting.translate("&aRemoved " + removed + " invalid account(s)"), 5000L);
                        updateScreen();
                    });
                }, "raven-delete-invalid").start();
                break;
            }

            case 8: { // Paste token
                String clip = GuiScreen.getClipboardString().trim();
                if (clip.isEmpty()) {
                    notification = new Notification(TextFormatting.translate("&cClipboard is empty"), 3000L);
                    break;
                }
                if (task != null && !task.isDone()) break;
                if (executor == null) executor = Executors.newSingleThreadExecutor();
                boolean isRefresh = clip.startsWith("M") && clip.length() > 20;
                Account newAcc = isRefresh
                        ? new Account(clip, "", "", "", 0L, AccountType.REFRESH)
                        : new Account("", clip, "", "", 0L, AccountType.TOKEN);
                newAcc.authStatus = AccountAuthStatus.WORKING;
                notification = new Notification(TextFormatting.translate("&7Verifying token..."), -1L);
                AccountManager.accounts.add(newAcc);
                updateFilter();
                updateScreen();
                task = AccountLogin.login(newAcc, executor).whenComplete((v, err) ->
                        mc.addScheduledTask(() -> {
                            if (StringUtils.isBlank(newAcc.getUsername())) {
                                AccountManager.accounts.remove(newAcc);
                                notification = new Notification(
                                        TextFormatting.translate("&cToken invalid or expired"), 5000L);
                            } else {
                                AccountManager.save();
                            }
                            updateFilter();
                            updateScreen();
                        }));
                break;
            }

            case 9: { // Localts
                String lk = GuiLocaltsSetup.loadKey();
                mc.displayGuiScreen(lk != null ? new GuiLocaltsMenu(this, lk) : new GuiLocaltsSetup(this));
                break;
            }
            case 10: { // NiceAlts
                String nk = GuiNicealtsSetup.loadKey();
                mc.displayGuiScreen(nk != null ? new GuiNicealtsMenu(this, nk) : new GuiNicealtsSetup(this));
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Account list
    // ══════════════════════════════════════════════════════════════════════════

    class GuiAccountList extends GuiSlot {
        private static final int SLOT_H   = 36;
        private static final int HEAD_SZ  = 32;

        GuiAccountList(Minecraft mc, int listBottom) {
            super(mc, GuiAccountManager.this.width, GuiAccountManager.this.height,
                    LIST_TOP, listBottom, SLOT_H);
        }

        @Override protected int getSize()            { return filteredList.size(); }
        @Override protected boolean isSelected(int i){ return i == selectedAccount; }
        @Override public int getListWidth()          { return 310; }
        @Override protected int getContentHeight()   { return filteredList.size() * SLOT_H; }
        @Override protected int getScrollBarX()      { return (width + getListWidth()) / 2 + 2; }

        @Override
        protected void elementClicked(int idx, boolean dbl, int mx, int my) {
            selectedAccount = idx;
            GuiAccountManager.this.updateScreen();
            if (dbl) GuiAccountManager.this.actionPerformed(loginButton);
        }

        /** No-op — GuiAccountManager.drawScreen handles the background. */
        @Override
        protected void drawBackground() {}

        @Override
        protected void drawSlot(int entryID, int x, int y, int k, int mx, int my) {
            if (entryID < 0 || entryID >= filteredList.size()) return;
            Account account = filteredList.get(entryID);

            // ── Row highlight (selection / hover) ─────────────────────────────
            if (isSelected(entryID)) {
                // Blue tinted selection
                RoundedUtils.drawRound(x, y + 1, getListWidth(), k - 2, 3f, COL_SEL);
                // Left accent bar
                Gui.drawRect(x, y + 3, x + 2, y + k - 3, COL_ACCENT);
            } else {
                boolean hovered = mx >= x && mx <= x + getListWidth() && my >= y && my <= y + k;
                if (hovered) RoundedUtils.drawRound(x, y + 1, getListWidth(), k - 2, 3f, COL_HOVER);
            }

            FontRenderer fr = GuiAccountManager.this.fontRendererObj;
            String rawName = account.getUsername();

            // ── Player head ───────────────────────────────────────────────────
            ResourceLocation head = PlayerHeadCache.get(StringUtils.isBlank(rawName) ? null : rawName);
            if (head != null) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                GuiAccountManager.this.mc.getTextureManager().bindTexture(head);
                Gui.drawScaledCustomSizeModalRect(x + 3, y + 2, 0, 0, 32, 32, HEAD_SZ, HEAD_SZ, 32f, 32f);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } else {
                RoundedUtils.drawRound(x + 3, y + 2, HEAD_SZ, HEAD_SZ, 3f, 0xFF1A1A22);
            }

            int tx = x + HEAD_SZ + 8;

            // ── Username + type ───────────────────────────────────────────────
            String dispName = StringUtils.isBlank(rawName) ? "\u00a77\u00a7l?" : rawName;
            Session sess = SessionManager.get();
            boolean active = sess != null && !StringUtils.isBlank(rawName)
                    && rawName.equals(sess.getUsername());
            if (active) dispName = "\u00a7a\u00a7l" + rawName;

            String typeSuffix;
            switch (account.getType()) {
                case CRACKED:  typeSuffix = " \u00a77(Cracked)";  break;
                case COOKIE:   typeSuffix = " \u00a77(Cookie)";   break;
                case REFRESH:  typeSuffix = " \u00a77(Refresh)";  break;
                case TOKEN:    typeSuffix = " \u00a77(Token)";    break;
                default:       typeSuffix = " \u00a77(Premium)";  break;
            }
            String tName   = TextFormatting.translate("\u00a7r" + dispName);
            String tSuffix = TextFormatting.translate(typeSuffix);
            GuiAccountManager.this.drawString(fr, tName,   tx, y + 4,  -1);
            GuiAccountManager.this.drawString(fr, tSuffix, tx + fr.getStringWidth(tName), y + 4, -1);

            // ── Auth status (line 2) ──────────────────────────────────────────
            String statusTxt = null;
            switch (account.authStatus) {
                case WORKING: statusTxt = "\u00a76Logging in...";     break;
                case FAILED:  statusTxt = "\u00a7cInvalid / Expired"; break;
                case AUTHED:  statusTxt = "\u00a7aLogged in";         break;
                default: break;
            }
            if (statusTxt != null)
                GuiAccountManager.this.drawString(fr, statusTxt, tx, y + 15, -1);

            // ── Ban indicator (bottom-right) ──────────────────────────────────
            long now   = System.currentTimeMillis();
            long unban = account.getUnban();
            String banTxt;
            if (unban < 0L) {
                banTxt = "\u00a74\u00a7l\u26a0";
            } else if (unban <= now) {
                banTxt = "\u00a72\u00a7l\u2714";
            } else {
                long diff = unban - now;
                long d = diff / 86400000L, h = diff / 3600000L % 24,
                     m = diff / 60000L % 60,  s = diff / 1000L % 60;
                String t = (d > 0 ? d + "d " : "") + (h > 0 ? h + "h " : "")
                         + (m > 0 ? m + "m " : "") + (s > 0 ? s + "s" : "");
                banTxt = t.trim() + " \u00a7c\u00a7l\u26a0";
            }
            String tBan = TextFormatting.translate("\u00a7r" + banTxt + "\u00a7r");
            GuiAccountManager.this.drawString(fr, tBan,
                    x + getListWidth() - 5 - fr.getStringWidth(tBan), y + 26, -1);
        }
    }
}
