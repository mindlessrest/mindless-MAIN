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
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.TextFormatting;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;
import keystrokesmod.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
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

    private int selectedAccount = -1;

    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private volatile boolean checkingInvalid = false;

    // ── Search ─────────────────────────────────────────────────────────────────
    private GuiTextField searchField;
    private final List<Account> filteredList = new ArrayList<>();
    private String lastSearch = "";

    // ── Palette ────────────────────────────────────────────────────────────────
    static final int C_BG       = 0xF0080A0C;
    static final int C_PANEL    = 0xEE0D1012;
    static final int C_ROW      = 0xE0181B1C;
    static final int C_ROW_HOV  = 0xEC1F2223;
    static final int C_SEL      = 0x339F8FD2;
    static final int C_ACCENT   = 0xFF9F8FD2;
    static final int C_ACCENT_DIM = 0x559F8FD2;
    static final int C_TEXT     = 0xFFEBEAE6;
    static final int C_MUTED    = 0xFF9D9E9C;
    static final int C_DIM      = 0xFF696C6C;
    static final int C_BORDER   = 0x34D2D2CC;
    static final int C_DANGER   = 0xFFDB6864;
    static final int C_SUCCESS  = 0xFF6EBF7A;

    // ── Layout ─────────────────────────────────────────────────────────────────
    private static final int HEADER_H   = 32;
    private static final int SEARCH_TOP = HEADER_H + 6;
    private static final int SEARCH_H   = 20;
    private static final int LIST_TOP   = SEARCH_TOP + SEARCH_H + 6;
    private static final int FOOTER_H   = 108;

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

        if (SessionManager.getLaunchSession() != null) {
            String launchName = SessionManager.getLaunchSession().getUsername();
            String label = "Restore: " + launchName;
            int rw = Math.min(220, fontRendererObj.getStringWidth(label) + 12);
            restoreButton = new GuiButton(4, width - rw - 8, 6, rw, 20, label);
            buttonList.add(restoreButton);
        } else {
            restoreButton = null;
        }

        int sfW = Math.min(340, width - 20);
        int sfX = width / 2 - sfW / 2;
        searchField = new GuiTextField(0, fontRendererObj, sfX + 8, SEARCH_TOP + 4, sfW - 16, SEARCH_H - 8);
        searchField.setMaxStringLength(64);
        searchField.setCanLoseFocus(true);
        searchField.setEnableBackgroundDrawing(false);

        int bFooterTop = height - FOOTER_H + 4;
        int colW = 96, gap = 4;
        int totalW = colW * 3 + gap * 2;
        int col1 = width / 2 - totalW / 2;
        int col2 = col1 + colW + gap;
        int col3 = col2 + colW + gap;
        int wideW = (totalW - gap) / 2;

        localtsButton  = new GuiButton(9,  col1,             bFooterTop,      wideW, 20, "Localts");
        nicealtsButton = new GuiButton(10, col1 + wideW + gap, bFooterTop,    wideW, 20, "NiceAlts");
        buttonList.add(localtsButton);
        buttonList.add(nicealtsButton);

        deleteInvalidButton = new GuiButton(7, col1,             bFooterTop + 26, wideW, 20, "Delete invalid");
        pasteTokenButton    = new GuiButton(8, col1 + wideW + gap, bFooterTop + 26, wideW, 20, "Paste token");
        buttonList.add(deleteInvalidButton);
        buttonList.add(pasteTokenButton);

        loginButton  = new GuiButton(0, col1, bFooterTop + 52, colW, 20, "Login");
        renameButton = new GuiButton(5, col2, bFooterTop + 52, colW, 20, "Rename");
        buttonList.add(loginButton);
        buttonList.add(renameButton);
        buttonList.add(new GuiButton(1, col3, bFooterTop + 52, colW, 20, "Add"));

        deleteButton = new GuiButton(2, col1, bFooterTop + 76, colW, 20, "Delete");
        skinButton   = new GuiButton(6, col2, bFooterTop + 76, colW, 20, "Skin");
        cancelButton = new GuiButton(3, col3, bFooterTop + 76, colW, 20, "Cancel");
        buttonList.add(deleteButton);
        buttonList.add(skinButton);
        buttonList.add(cancelButton);

        int listBottom = bFooterTop - 6;
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
    // Filter / update
    // ══════════════════════════════════════════════════════════════════════════

    private void updateFilter() {
        String q = searchField != null ? searchField.getText().toLowerCase().trim() : "";
        Account wasSelected = (selectedAccount >= 0 && selectedAccount < filteredList.size())
                ? filteredList.get(selectedAccount) : null;
        filteredList.clear();
        for (Account acc : AccountManager.accounts) {
            if (q.isEmpty() || (!StringUtils.isBlank(acc.getUsername())
                    && acc.getUsername().toLowerCase().contains(q))) {
                filteredList.add(acc);
            }
        }
        if (wasSelected != null) {
            int newIdx = filteredList.indexOf(wasSelected);
            selectedAccount = newIdx;
        } else if (selectedAccount >= filteredList.size()) {
            selectedAccount = -1;
        }
    }

    @Override
    public void updateScreen() {
        if (searchField != null) searchField.updateCursorCounter();
        String cur = searchField != null ? searchField.getText() : "";
        if (!cur.equals(lastSearch)) { lastSearch = cur; updateFilter(); }

        boolean has = selectedAccount >= 0 && selectedAccount < filteredList.size();
        boolean busy = task != null && !task.isDone();

        if (deleteButton  != null) deleteButton.enabled  = has;
        if (loginButton   != null) loginButton.enabled   = has && !busy;
        if (renameButton  != null) renameButton.enabled  = has && !busy;
        if (skinButton    != null) skinButton.enabled    = has && !busy;
        if (deleteInvalidButton != null)
            deleteInvalidButton.enabled = !checkingInvalid && !AccountManager.accounts.isEmpty() && !busy;
        if (pasteTokenButton != null) pasteTokenButton.enabled = !busy && !checkingInvalid;
        if (nicealtsButton != null) nicealtsButton.enabled = true;
        if (localtsButton  != null) localtsButton.enabled  = true;
        if (restoreButton  != null) restoreButton.enabled = !SessionManager.isUsingLaunchSession() && !busy;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Render
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void drawScreen(int mx, int my, float pt) {
        RavenFontRenderer sfReg  = FontManager.getClickGuiSettingRenderer("Sf-Regular");
        RavenFontRenderer sfBold = FontManager.getClickGuiHeaderRenderer("Sf-Bold");

        // Full-screen background
        drawRect(0, 0, width, height, C_BG);

        // ── Header ───────────────────────────────────────────────────────────
        RoundedUtils.drawRound(0, 0, width, HEADER_H, 0f, C_PANEL);
        drawRect(0, HEADER_H - 1, width, HEADER_H, C_BORDER);

        sfBold.drawString("Account Manager", width / 2f - sfBold.getStringWidth("Account Manager") / 2f, 10f, C_TEXT, false);

        Session sess = SessionManager.get();
        if (sess != null) {
            sfReg.drawString("\u00a77" + sess.getUsername(), 8f, 12f, C_MUTED, false);
        }
        String countStr = AccountManager.accounts.size() + " accounts";
        sfReg.drawString(countStr, width - sfReg.getStringWidth(countStr) - 8f, 12f, C_DIM, false);

        // ── Search field background ───────────────────────────────────────────
        int sfW = Math.min(340, width - 20);
        int sfX = width / 2 - sfW / 2;
        RoundedUtils.drawRound(sfX, SEARCH_TOP, sfW, SEARCH_H, 4f, C_ROW);
        drawRect(sfX, SEARCH_TOP + SEARCH_H - 1, sfX + sfW, SEARCH_TOP + SEARCH_H, C_ACCENT_DIM);
        searchField.drawTextBox();
        if (searchField.getText().isEmpty() && !searchField.isFocused()) {
            sfReg.drawString("Search accounts...", sfX + 9f, SEARCH_TOP + 5f, C_DIM, false);
        }

        // ── Account list ─────────────────────────────────────────────────────
        if (guiAccountList != null) guiAccountList.drawScreen(mx, my, pt);

        // ── Footer background ─────────────────────────────────────────────────
        int footerTop = height - FOOTER_H;
        drawRect(0, footerTop, width, footerTop + 1, C_BORDER);
        drawRect(0, footerTop, width, height, C_PANEL);

        // ── Buttons ──────────────────────────────────────────────────────────
        drawStyledButtons(mx, my, sfReg);

        // ── Toast ─────────────────────────────────────────────────────────────
        if (notification != null && !notification.isExpired()) {
            String msg = notification.getMessage();
            int msgW = fontRendererObj.getStringWidth(msg);
            int pw = msgW + 16, ph = fontRendererObj.FONT_HEIGHT + 8;
            int px = width / 2 - pw / 2;
            int py = height - FOOTER_H - ph - 6;
            RoundedUtils.drawRound(px, py, pw, ph, 4f, 0xDD0D1012);
            drawRect(px, py, px + pw, py + 1, C_ACCENT);
            drawCenteredString(fontRendererObj, msg, width / 2, py + 4, C_TEXT);
        }

        // ── Restore button on top ─────────────────────────────────────────────
        if (restoreButton != null) {
            drawStyledButton(restoreButton, mx, my, sfReg, C_ROW, C_ROW_HOV, C_TEXT);
        }
    }

    private void drawStyledButtons(int mx, int my, RavenFontRenderer fr) {
        for (GuiButton b : buttonList) {
            if (b == restoreButton) continue;
            int bg   = b.id == 2 ? (b.enabled ? 0xCC2A1212 : C_ROW) : C_ROW;
            int bgH  = b.id == 2 ? (b.enabled ? 0xCC3D1A1A : C_ROW_HOV) : C_ROW_HOV;
            int fg   = b.enabled ? (b.id == 2 ? C_DANGER : (b.id == 0 ? C_ACCENT : C_TEXT)) : C_DIM;
            drawStyledButton(b, mx, my, fr, bg, bgH, fg);
        }
    }

    private void drawStyledButton(GuiButton b, int mx, int my, RavenFontRenderer fr,
                                   int bg, int bgHover, int fg) {
        boolean hov = b.enabled && mx >= b.xPosition && mx < b.xPosition + b.width
                && my >= b.yPosition && my < b.yPosition + b.height;
        RoundedUtils.drawRound(b.xPosition, b.yPosition, b.width, b.height, 4f, hov ? bgHover : bg);
        if (b.enabled && b.id == 0) { // Login button gets accent left bar
            drawRect(b.xPosition, b.yPosition + 4, b.xPosition + 2, b.yPosition + b.height - 4, C_ACCENT);
        }
        float tw = fr.getStringWidth(b.displayString);
        fr.drawString(b.displayString, b.xPosition + b.width / 2f - tw / 2f,
                b.yPosition + b.height / 2f - fr.getFontHeight() / 2f, fg, false);
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
            return;
        }
        switch (keyCode) {
            case 200:
                if (selectedAccount <= 0) break;
                --selectedAccount;
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()) {
                    Account a = filteredList.get(selectedAccount);
                    Account b = filteredList.get(selectedAccount + 1);
                    Collections.swap(AccountManager.accounts, AccountManager.accounts.indexOf(a), AccountManager.accounts.indexOf(b));
                    AccountManager.save(); updateFilter();
                }
                updateScreen(); break;
            case 208:
                if (selectedAccount >= filteredList.size() - 1) break;
                ++selectedAccount;
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()) {
                    Account a = filteredList.get(selectedAccount);
                    Account b = filteredList.get(selectedAccount - 1);
                    Collections.swap(AccountManager.accounts, AccountManager.accounts.indexOf(a), AccountManager.accounts.indexOf(b));
                    AccountManager.save(); updateFilter();
                }
                updateScreen(); break;
            case 28: actionPerformed(loginButton);  break;
            case 211: actionPerformed(deleteButton); break;
            case 1:   actionPerformed(cancelButton); break;
        }
        if (GuiScreen.isKeyComboCtrlC(keyCode) && selectedAccount >= 0 && selectedAccount < filteredList.size()) {
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
            case 0: {
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
                notification = new Notification(TextFormatting.translate(String.format("&7Fetching profile... (%s)&r", username)), -1L);
                updateScreen();
                task = AccountLogin.login(account, executor).whenComplete((v, err) ->
                        mc.addScheduledTask(() -> {
                            if (account.authStatus == AccountAuthStatus.AUTHED) mc.displayGuiScreen(previousScreen);
                            else updateScreen();
                        }));
                break;
            }
            case 1: mc.displayGuiScreen(new GuiAddAccount(previousScreen)); break;
            case 2:
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                AccountManager.accounts.remove(filteredList.get(selectedAccount));
                AccountManager.save(); selectedAccount = -1; updateFilter(); updateScreen(); break;
            case 3: mc.displayGuiScreen(previousScreen); break;
            case 4:
                SessionManager.restoreLaunchSession();
                notification = new Notification(TextFormatting.translate(
                        String.format("&aRestored session (%s)&r", SessionManager.get().getUsername())), 5000L);
                updateScreen(); break;
            case 5:
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                mc.displayGuiScreen(new GuiChangeName(this, filteredList.get(selectedAccount))); break;
            case 6:
                if (selectedAccount < 0 || selectedAccount >= filteredList.size()) break;
                mc.displayGuiScreen(new GuiChangeSkin(this, filteredList.get(selectedAccount))); break;
            case 7: {
                if (AccountManager.accounts.isEmpty() || checkingInvalid) break;
                if (task != null && !task.isDone()) break;
                checkingInvalid = true; updateScreen();
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
                        mc.addScheduledTask(() -> notification = new Notification(TextFormatting.translate("&7Checking " + c + "/" + total + "..."), -1L));
                        ExecutorService ex = Executors.newSingleThreadExecutor();
                        try {
                            AccountLogin.login(acc, ex).get(20L, TimeUnit.SECONDS);
                            if (acc.authStatus == AccountAuthStatus.FAILED || StringUtils.isBlank(acc.getUsername())) invalid.add(acc);
                        } catch (Exception e) { invalid.add(acc); acc.authStatus = AccountAuthStatus.FAILED; }
                        finally { ex.shutdownNow(); }
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
                    }
                    if (saved != null) SessionManager.set(saved);
                    AccountManager.accounts.removeAll(invalid);
                    AccountManager.save();
                    final int removed = invalid.size();
                    mc.addScheduledTask(() -> {
                        checkingInvalid = false; selectedAccount = -1; updateFilter();
                        notification = new Notification(TextFormatting.translate("&aRemoved " + removed + " invalid account(s)"), 5000L);
                        updateScreen();
                    });
                }, "raven-delete-invalid").start();
                break;
            }
            case 8: {
                String clip = GuiScreen.getClipboardString().trim();
                if (clip.isEmpty()) { notification = new Notification(TextFormatting.translate("&cClipboard is empty"), 3000L); break; }
                if (task != null && !task.isDone()) break;
                if (executor == null) executor = Executors.newSingleThreadExecutor();
                boolean isRefresh = clip.startsWith("M") && clip.length() > 20;
                Account newAcc = isRefresh
                        ? new Account(clip, "", "", "", 0L, AccountType.REFRESH)
                        : new Account("", clip, "", "", 0L, AccountType.TOKEN);
                newAcc.authStatus = AccountAuthStatus.WORKING;
                notification = new Notification(TextFormatting.translate("&7Verifying token..."), -1L);
                AccountManager.accounts.add(newAcc); updateFilter(); updateScreen();
                task = AccountLogin.login(newAcc, executor).whenComplete((v, err) ->
                        mc.addScheduledTask(() -> {
                            if (StringUtils.isBlank(newAcc.getUsername())) {
                                AccountManager.accounts.remove(newAcc);
                                notification = new Notification(TextFormatting.translate("&cToken invalid or expired"), 5000L);
                            } else { AccountManager.save(); }
                            updateFilter(); updateScreen();
                        }));
                break;
            }
            case 9: {
                String lk = GuiLocaltsSetup.loadKey();
                mc.displayGuiScreen(lk != null ? new GuiLocaltsMenu(this, lk) : new GuiLocaltsSetup(this));
                break;
            }
            case 10: {
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
        private static final int SLOT_H  = 36;
        private static final int HEAD_SZ = 28;

        GuiAccountList(Minecraft mc, int listBottom) {
            super(mc, GuiAccountManager.this.width, GuiAccountManager.this.height,
                    LIST_TOP, listBottom, SLOT_H);
        }

        @Override protected int getSize()             { return filteredList.size(); }
        @Override protected boolean isSelected(int i) { return i == selectedAccount; }
        @Override public int getListWidth()           { return Math.min(380, width - 20); }
        @Override protected int getContentHeight()    { return filteredList.size() * SLOT_H; }
        @Override protected int getScrollBarX()       { return (width + getListWidth()) / 2 + 2; }

        @Override
        protected void elementClicked(int idx, boolean dbl, int mx, int my) {
            selectedAccount = idx;
            GuiAccountManager.this.updateScreen();
            if (dbl) GuiAccountManager.this.actionPerformed(loginButton);
        }

        @Override protected void drawBackground() {}

        @Override
        protected void drawSlot(int id, int x, int y, int h, int mx, int my) {
            if (id < 0 || id >= filteredList.size()) return;
            Account account = filteredList.get(id);
            RavenFontRenderer sfReg  = FontManager.getClickGuiSmallRenderer("Sf-Regular");
            RavenFontRenderer sfBold = FontManager.getClickGuiSettingRenderer("Sf-Regular");

            boolean hov = mx >= x && mx <= x + getListWidth() && my >= y && my <= y + h;
            if (isSelected(id)) {
                RoundedUtils.drawRound(x, y + 1, getListWidth(), h - 2, 4f, C_SEL);
                drawRect(x, y + 5, x + 2, y + h - 5, C_ACCENT);
            } else if (hov) {
                RoundedUtils.drawRound(x, y + 1, getListWidth(), h - 2, 4f, C_ROW_HOV);
            }

            String rawName = account.getUsername();
            ResourceLocation head = PlayerHeadCache.get(StringUtils.isBlank(rawName) ? null : rawName);
            int headX = x + 4;
            if (head != null) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                GuiAccountManager.this.mc.getTextureManager().bindTexture(head);
                Gui.drawScaledCustomSizeModalRect(headX, y + 4, 0, 0, 32, 32, HEAD_SZ, HEAD_SZ, 32f, 32f);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } else {
                RoundedUtils.drawRound(headX, y + 4, HEAD_SZ, HEAD_SZ, 3f, 0xFF1A1A22);
            }

            int tx = headX + HEAD_SZ + 7;

            Session sess = SessionManager.get();
            boolean active = sess != null && !StringUtils.isBlank(rawName) && rawName.equals(sess.getUsername());
            String dispName = StringUtils.isBlank(rawName) ? "???" : rawName;
            int nameColor = active ? C_SUCCESS : C_TEXT;
            sfBold.drawString(dispName, tx, y + 5f, nameColor, false);

            String typeSuffix;
            switch (account.getType()) {
                case CRACKED: typeSuffix = "Cracked"; break;
                case COOKIE:  typeSuffix = "Cookie";  break;
                case REFRESH: typeSuffix = "Refresh"; break;
                case TOKEN:   typeSuffix = "Token";   break;
                default:      typeSuffix = "Premium"; break;
            }
            float typeX = tx + sfBold.getStringWidth(dispName) + 5f;
            sfReg.drawString(typeSuffix, typeX, y + 6f, C_DIM, false);

            String statusTxt = null;
            int statusColor = C_MUTED;
            switch (account.authStatus) {
                case WORKING: statusTxt = "Logging in...";     statusColor = 0xFFE8C87A; break;
                case FAILED:  statusTxt = "Invalid / Expired"; statusColor = C_DANGER;   break;
                case AUTHED:  statusTxt = "Logged in";         statusColor = C_SUCCESS;  break;
                default: break;
            }
            if (statusTxt != null) sfReg.drawString(statusTxt, tx, y + 18f, statusColor, false);

            // Ban indicator
            long now = System.currentTimeMillis(), unban = account.getUnban();
            String banTxt;
            int banColor;
            if (unban < 0L) { banTxt = "\u26a0"; banColor = C_DANGER; }
            else if (unban <= now) { banTxt = "\u2714"; banColor = C_SUCCESS; }
            else {
                long diff = unban - now;
                long d = diff/86400000L, hh = diff/3600000L%24, m = diff/60000L%60, s = diff/1000L%60;
                banTxt = ((d>0?d+"d ":"")+(hh>0?hh+"h ":"")+(m>0?m+"m ":"")+(s>0?s+"s":"")).trim();
                banColor = 0xFFE8C87A;
            }
            float banW = sfReg.getStringWidth(banTxt);
            sfReg.drawString(banTxt, x + getListWidth() - banW - 5f, y + 20f, banColor, false);
        }
    }
}
