package mindless.accountmanager.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mindless.accountmanager.AccountAuthStatus;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.AutoSkinSettings;
import mindless.accountmanager.PlayerHeadCache;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.AccountLogin;
import mindless.accountmanager.auth.AccountType;
import mindless.accountmanager.auth.CrackedAuth;
import mindless.accountmanager.auth.SessionManager;
import mindless.accountmanager.utils.Notification;
import mindless.accountmanager.utils.ModernFileChooser;
import mindless.accountmanager.utils.TextFormatting;
import mindless.utility.font.MinecraftFontAdapter;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
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
    private GuiButton presetSkinButton  = null;
    private GuiButton autoSkinButton    = null;
    private GuiButton skinModelButton   = null;
    private GuiAccountList guiAccountList = null;
    public static Notification notification = null;

    private int selectedAccount = -1;

    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private volatile boolean checkingInvalid = false;
    private GuiTextField searchField;
    private final List<Account> filteredList = new ArrayList<>();

    /**
     * View order. Manual is the stored order and the only one Ctrl+Up/Down can reorder,
     * because a swap under any other ordering would move rows that the sort immediately
     * puts back, which reads as the reorder silently failing.
     */
    private static final String[] SORT_MODES = { "Manual", "Name", "Type", "Status" };
    private static final int SORT_MANUAL = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_TYPE = 2;
    private static final int SORT_STATUS = 3;
    private static int sortMode = SORT_MANUAL;
    private GuiButton sortButton;
    private String lastSearch = "";
    static final int C_BG       = 0xF207080A;
    static final int C_PANEL    = 0xF00D0F12;
    static final int C_ROW      = 0xE817191C;
    static final int C_ROW_HOV  = 0xF0202327;
    static final int C_SEL      = 0x2EE8E8E8;
    static final int C_ACCENT   = 0xFFF0F0EE;
    static final int C_ACCENT_DIM = 0x58C7C8CA;
    static final int C_TEXT     = 0xFFF0F0EE;
    static final int C_MUTED    = 0xFFA7A9AC;
    static final int C_DIM      = 0xFF707378;
    static final int C_BORDER   = 0x3AD8DADF;
    static final int C_DANGER   = 0xFFD6817E;
    static final int C_SUCCESS  = 0xFFD7D9D7;
    private static final int HEADER_H   = 52;
    private static final int SEARCH_H   = 26;
    private static final int FOOTER_H   = 132;
    private int contentX;
    private int contentW;
    private int listPanelX;
    private int listPanelW;
    private int detailPanelX;
    private int detailPanelW;
    private int searchTop;
    private int listTop;
    private int footerTop;
    private boolean splitLayout;

    public GuiAccountManager(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    public GuiAccountManager(GuiScreen previousScreen, Notification notification) {
        this.previousScreen = previousScreen;
        GuiAccountManager.notification = notification;
    }

    private void computeLayout() {
        contentW = Math.max(300, Math.min(1180, width - 28));
        contentX = (width - contentW) / 2;
        splitLayout = contentW >= 760;
        int gap = 10;
        detailPanelW = splitLayout ? Math.max(270, Math.round(contentW * .34f)) : 0;
        listPanelW = contentW - (splitLayout ? detailPanelW + gap : 0);
        listPanelX = contentX;
        detailPanelX = listPanelX + listPanelW + gap;
        searchTop = HEADER_H + 22;
        listTop = searchTop + SEARCH_H + 10;
        footerTop = Math.max(listTop + 74, height - FOOTER_H);
    }

    @Override
    public void initGui() {
        AccountManager.load();
        AutoSkinSettings.load();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        computeLayout();

        if (SessionManager.getLaunchSession() != null) {
            String launchName = SessionManager.getLaunchSession().getUsername();
            String label = "Restore: " + launchName;
            int rw = Math.min(220, fontRendererObj.getStringWidth(label) + 12);
            restoreButton = new GuiButton(4, contentX + contentW - rw, 16, rw, 22, label);
            buttonList.add(restoreButton);
        } else {
            restoreButton = null;
        }

        int searchX = listPanelX + 14;
        int searchW = listPanelW - 28;
        searchField = new GuiTextField(0, fontRendererObj, searchX + 10, searchTop + 5,
                searchW - 124, SEARCH_H - 9);
        searchField.setMaxStringLength(64);
        searchField.setCanLoseFocus(true);
        searchField.setEnableBackgroundDrawing(false);

        sortButton = new GuiButton(11, searchX + searchW - 108, searchTop + 3, 104, SEARCH_H - 6,
                "Sort: " + SORT_MODES[sortMode]);
        buttonList.add(sortButton);

        int groupGap = 10;
        int groupW = (contentW - groupGap * 2) / 3;
        int buttonGap = 5;
        int halfW = (groupW - 24 - buttonGap) / 2;
        int actionX = contentX + 12;
        int toolsX = contentX + groupW + groupGap + 12;
        int appearanceX = contentX + (groupW + groupGap) * 2 + 12;
        int innerW = groupW - 24;
        int row1 = footerTop + 34;
        int row2 = row1 + 27;
        int row3 = row2 + 27;

        loginButton  = new GuiButton(0, actionX, row1, halfW, 22, "Login");
        GuiButton addButton = new GuiButton(1, actionX + halfW + buttonGap, row1, halfW, 22, "Add");
        renameButton = new GuiButton(5, actionX, row2, halfW, 22, "Rename");
        skinButton   = new GuiButton(6, actionX + halfW + buttonGap, row2, halfW, 22, "Change skin");
        deleteButton = new GuiButton(2, actionX, row3, innerW, 22, "Delete selected");
        buttonList.add(loginButton);
        buttonList.add(addButton);
        buttonList.add(renameButton);
        buttonList.add(skinButton);
        buttonList.add(deleteButton);

        localtsButton  = new GuiButton(9, toolsX, row1, halfW, 22, "Localts");
        nicealtsButton = new GuiButton(10, toolsX + halfW + buttonGap, row1, halfW, 22, "NiceAlts");
        buttonList.add(localtsButton);
        buttonList.add(nicealtsButton);
        pasteTokenButton = new GuiButton(8, toolsX, row2, halfW, 22, "Paste token");
        deleteInvalidButton = new GuiButton(7, toolsX + halfW + buttonGap, row2, halfW, 22, "Delete invalid");
        buttonList.add(deleteInvalidButton);
        buttonList.add(pasteTokenButton);
        cancelButton = new GuiButton(3, toolsX, row3, innerW, 22, "Done");
        buttonList.add(cancelButton);

        presetSkinButton = new GuiButton(13, appearanceX, row1, innerW, 22,
                AutoSkinSettings.hasPreset() ? "Replace preset skin" : "Select preset skin");
        autoSkinButton = new GuiButton(14, appearanceX, row2, innerW, 22,
                autoSkinLabel());
        skinModelButton = new GuiButton(15, appearanceX, row3, innerW, 22,
                skinModelLabel());
        buttonList.add(presetSkinButton);
        buttonList.add(autoSkinButton);
        buttonList.add(skinModelButton);

        int listBottom = footerTop - 18;
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

    private void cycleSort() {
        sortMode = (sortMode + 1) % SORT_MODES.length;
        if (sortButton != null) {
            sortButton.displayString = "Sort: " + SORT_MODES[sortMode];
        }
        updateFilter();
    }

    private static String typeLabel(Account account) {
        switch (account.getType()) {
            case CRACKED: return "Cracked";
            case COOKIE:  return "Cookie";
            case REFRESH: return "Refresh";
            case TOKEN:   return "Token";
            default:      return "Premium";
        }
    }

    /** Banned first, then temporarily banned soonest-free first, then everything clean. */
    private static int statusRank(Account account) {
        long unban = account.getUnban();
        if (unban < 0L) return 0;
        if (unban > System.currentTimeMillis()) return 1;
        return 2;
    }

    private void sortFiltered() {
        switch (sortMode) {
            case SORT_NAME:
                Collections.sort(filteredList, new Comparator<Account>() {
                    @Override public int compare(Account a, Account b) {
                        return String.valueOf(a.getUsername())
                                .compareToIgnoreCase(String.valueOf(b.getUsername()));
                    }
                });
                break;
            case SORT_TYPE:
                Collections.sort(filteredList, new Comparator<Account>() {
                    @Override public int compare(Account a, Account b) {
                        int byType = typeLabel(a).compareToIgnoreCase(typeLabel(b));
                        if (byType != 0) return byType;
                        return String.valueOf(a.getUsername())
                                .compareToIgnoreCase(String.valueOf(b.getUsername()));
                    }
                });
                break;
            case SORT_STATUS:
                Collections.sort(filteredList, new Comparator<Account>() {
                    @Override public int compare(Account a, Account b) {
                        int byRank = Integer.compare(statusRank(a), statusRank(b));
                        if (byRank != 0) return byRank;
                        int byUnban = Long.compare(a.getUnban(), b.getUnban());
                        if (byUnban != 0) return byUnban;
                        return String.valueOf(a.getUsername())
                                .compareToIgnoreCase(String.valueOf(b.getUsername()));
                    }
                });
                break;
            default:
                break;
        }
    }

    private void updateFilter() {
        String q = searchField != null ? searchField.getText().toLowerCase().trim() : "";
        Account wasSelected = (selectedAccount >= 0 && selectedAccount < filteredList.size())
                ? filteredList.get(selectedAccount) : null;
        filteredList.clear();
        for (Account acc : AccountManager.accounts) {
            boolean matches = q.isEmpty()
                    || (!StringUtils.isBlank(acc.getUsername())
                        && acc.getUsername().toLowerCase().contains(q))
                    || typeLabel(acc).toLowerCase().contains(q);
            if (matches) {
                filteredList.add(acc);
            }
        }
        sortFiltered();
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
        if (presetSkinButton != null) presetSkinButton.enabled = !busy;
        if (autoSkinButton != null) autoSkinButton.enabled = AutoSkinSettings.hasPreset() && !busy;
        if (skinModelButton != null) skinModelButton.enabled = AutoSkinSettings.hasPreset() && !busy;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        MindlessFontRenderer sfReg  = new MinecraftFontAdapter(fontRendererObj);
        MindlessFontRenderer sfBold = new MinecraftFontAdapter(fontRendererObj);
        drawRect(0, 0, width, height, C_BG);
        RoundedUtils.drawRound(0, 0, width, HEADER_H, 0f, C_PANEL);
        drawRect(0, HEADER_H - 1, width, HEADER_H, C_BORDER);

        // Compact product-style header: identity on the left, session controls on the right.
        RoundedUtils.drawRound(contentX, 14, 24, 24, 5f, 0xFF25282D);
        drawRect(contentX + 7, 21, contentX + 17, 31, 0xFFBFC1C4);
        sfBold.drawString("Mindless Account Manager", contentX + 34f, 13f, C_TEXT, false);
        sfReg.drawString("Manage accounts, sessions, and skins", contentX + 34f, 29f, C_MUTED, false);

        Session sess = SessionManager.get();
        String countStr = AccountManager.accounts.size() + (AccountManager.accounts.size() == 1 ? " account" : " accounts");
        if (restoreButton == null) {
            sfReg.drawString(countStr, contentX + contentW - sfReg.getStringWidth(countStr), 22f, C_DIM, false);
        }

        int panelTop = HEADER_H + 10;
        int panelBottom = footerTop - 8;
        RoundedUtils.drawRound(listPanelX, panelTop, listPanelW, panelBottom - panelTop, 7f, C_PANEL);
        outline(listPanelX, panelTop, listPanelW, panelBottom - panelTop);
        sfBold.drawString("Accounts", listPanelX + 14f, panelTop + 10f, C_TEXT, false);
        sfReg.drawString("(" + filteredList.size() + ")", listPanelX + 14f + sfBold.getStringWidth("Accounts") + 5f,
                panelTop + 10f, C_DIM, false);

        int searchX = listPanelX + 14;
        int searchW = listPanelW - 28;
        RoundedUtils.drawRound(searchX, searchTop, searchW, SEARCH_H, 5f, C_ROW);
        outline(searchX, searchTop, searchW, SEARCH_H);
        searchField.drawTextBox();
        if (searchField.getText().isEmpty() && !searchField.isFocused()) {
            sfReg.drawString("Search accounts...", searchX + 10f, searchTop + 6f, C_DIM, false);
        }
        if (guiAccountList != null) guiAccountList.drawScreen(mx, my, pt);
        if (filteredList.isEmpty()) {
            String empty = AccountManager.accounts.isEmpty() ? "No accounts yet" : "No matching accounts";
            sfReg.drawString(empty, listPanelX + listPanelW / 2f - sfReg.getStringWidth(empty) / 2f,
                    listTop + 18f, C_DIM, false);
        }

        if (splitLayout) drawAccountDetails(sfReg, sfBold, panelTop, panelBottom);

        drawFooterPanels(sfReg, sfBold);
        drawStyledButtons(mx, my, sfReg);
        if (notification != null && !notification.isExpired()) {
            String msg = notification.getMessage();
            int msgW = fontRendererObj.getStringWidth(msg);
            int pw = msgW + 16, ph = fontRendererObj.FONT_HEIGHT + 8;
            int px = width / 2 - pw / 2;
            int py = footerTop - ph - 12;
            RoundedUtils.drawRound(px, py, pw, ph, 4f, 0xF0181A1E);
            outline(px, py, pw, ph);
            drawCenteredString(fontRendererObj, msg, width / 2, py + 4, C_TEXT);
        }
        if (restoreButton != null) {
            drawStyledButton(restoreButton, mx, my, sfReg, C_ROW, C_ROW_HOV, C_TEXT);
        }
    }

    private void drawAccountDetails(MindlessFontRenderer regular, MindlessFontRenderer bold,
                                    int panelTop, int panelBottom) {
        RoundedUtils.drawRound(detailPanelX, panelTop, detailPanelW, panelBottom - panelTop, 7f, C_PANEL);
        outline(detailPanelX, panelTop, detailPanelW, panelBottom - panelTop);
        bold.drawString("Account details", detailPanelX + 14f, panelTop + 10f, C_TEXT, false);

        if (selectedAccount < 0 || selectedAccount >= filteredList.size()) {
            regular.drawString("Select an account to view its details.", detailPanelX + 14f,
                    panelTop + 38f, C_DIM, false);
            return;
        }

        Account account = filteredList.get(selectedAccount);
        String name = StringUtils.isBlank(account.getUsername()) ? "Unknown account" : account.getUsername();
        ResourceLocation head = PlayerHeadCache.get(StringUtils.isBlank(account.getUsername()) ? null : account.getUsername());
        int headX = detailPanelX + 14;
        int headY = panelTop + 35;
        drawHead(head, headX, headY, 44);
        bold.drawString(name, headX + 55f, headY + 5f, C_TEXT, false);
        regular.drawString(typeLabel(account), headX + 55f, headY + 23f, C_MUTED, false);

        int dividerY = headY + 59;
        drawRect(detailPanelX + 14, dividerY, detailPanelX + detailPanelW - 14, dividerY + 1, C_BORDER);
        int labelX = detailPanelX + 14;
        int valueX = detailPanelX + Math.max(102, detailPanelW / 2);
        drawDetailRow(regular, "Status", authStatusLabel(account), labelX, valueX, dividerY + 15);
        drawDetailRow(regular, "Session", isActive(account) ? "Active" : "Stored", labelX, valueX, dividerY + 35);
        drawDetailRow(regular, "Type", typeLabel(account), labelX, valueX, dividerY + 55);
        String uuid = StringUtils.isBlank(account.getUuid()) ? "Not available" : shortUuid(account.getUuid());
        drawDetailRow(regular, "UUID", uuid, labelX, valueX, dividerY + 75);
        drawDetailRow(regular, "Skin model", AutoSkinSettings.isSlim() ? "Slim" : "Classic",
                labelX, valueX, dividerY + 95);

        int previewTop = dividerY + 124;
        if (previewTop + 70 < panelBottom) {
            RoundedUtils.drawRound(detailPanelX + 14, previewTop, detailPanelW - 28,
                    panelBottom - previewTop - 14, 6f, 0xB8121417);
            outline(detailPanelX + 14, previewTop, detailPanelW - 28, panelBottom - previewTop - 14);
            regular.drawString("PROFILE PREVIEW", detailPanelX + 25f, previewTop + 11f, C_DIM, false);
            int previewSize = Math.min(74, panelBottom - previewTop - 42);
            if (previewSize > 24) {
                drawHead(head, detailPanelX + (detailPanelW - previewSize) / 2,
                        previewTop + 29, previewSize);
            }
        }
    }

    private void drawFooterPanels(MindlessFontRenderer regular, MindlessFontRenderer bold) {
        int gap = 10;
        int groupW = (contentW - gap * 2) / 3;
        String[] titles = {"Account actions", "Tools", "Appearance"};
        String[] subtitles = {"Manage the selected account", "Import and quick actions", "Skin and model defaults"};
        for (int i = 0; i < 3; i++) {
            int x = contentX + i * (groupW + gap);
            RoundedUtils.drawRound(x, footerTop, groupW, height - footerTop - 8, 7f, C_PANEL);
            outline(x, footerTop, groupW, height - footerTop - 8);
            bold.drawString(titles[i], x + 12f, footerTop + 9f, C_TEXT, false);
            regular.drawString(subtitles[i], x + 12f, footerTop + 21f, C_DIM, false);
        }
    }

    private void drawDetailRow(MindlessFontRenderer font, String label, String value,
                               int labelX, int valueX, int y) {
        font.drawString(label, labelX, y, C_MUTED, false);
        font.drawString(value, valueX, y, C_TEXT, false);
    }

    private String authStatusLabel(Account account) {
        switch (account.authStatus) {
            case WORKING: return "Authenticating";
            case FAILED: return "Invalid / expired";
            case AUTHED: return "Ready";
            default: return "Saved";
        }
    }

    private boolean isActive(Account account) {
        Session session = SessionManager.get();
        return session != null && !StringUtils.isBlank(account.getUsername())
                && account.getUsername().equals(session.getUsername());
    }

    private static String shortUuid(String uuid) {
        if (uuid.length() <= 18) return uuid;
        return uuid.substring(0, 8) + "..." + uuid.substring(uuid.length() - 6);
    }

    private void drawHead(ResourceLocation head, int x, int y, int size) {
        RoundedUtils.drawRound(x, y, size, size, 4f, 0xFF17191C);
        if (head == null) return;
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(head);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 32, 32, size, size, 32f, 32f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void outline(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + 1, C_BORDER);
        drawRect(x, y + h - 1, x + w, y + h, C_BORDER);
        drawRect(x, y, x + 1, y + h, C_BORDER);
        drawRect(x + w - 1, y, x + w, y + h, C_BORDER);
    }

    private void drawStyledButtons(int mx, int my, MindlessFontRenderer fr) {
        for (GuiButton b : buttonList) {
            if (b == restoreButton) continue;
            int bg   = b.id == 2 ? (b.enabled ? 0xCC2A1212 : C_ROW) : C_ROW;
            int bgH  = b.id == 2 ? (b.enabled ? 0xCC3D1A1A : C_ROW_HOV) : C_ROW_HOV;
            int fg   = b.enabled ? (b.id == 2 ? C_DANGER : (b.id == 0 ? C_ACCENT : C_TEXT)) : C_DIM;
            drawStyledButton(b, mx, my, fr, bg, bgH, fg);
        }
    }

    private void drawStyledButton(GuiButton b, int mx, int my, MindlessFontRenderer fr,
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

    private String autoSkinLabel() {
        return "Auto skin: " + (AutoSkinSettings.isEnabled() ? "On" : "Off");
    }

    private String skinModelLabel() {
        return "Preset model: " + (AutoSkinSettings.isSlim() ? "Slim" : "Classic");
    }

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
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()
                        && sortMode == SORT_MANUAL) {
                    Account a = filteredList.get(selectedAccount);
                    Account b = filteredList.get(selectedAccount + 1);
                    Collections.swap(AccountManager.accounts, AccountManager.accounts.indexOf(a), AccountManager.accounts.indexOf(b));
                    AccountManager.save(); updateFilter();
                }
                updateScreen(); break;
            case 208:
                if (selectedAccount >= filteredList.size() - 1) break;
                ++selectedAccount;
                if (GuiScreen.isCtrlKeyDown() && searchField.getText().isEmpty()
                        && sortMode == SORT_MANUAL) {
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

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) return;
        switch (button.id) {
            case 11: cycleSort(); return;
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
                }, "mindless-delete-invalid").start();
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
            case 13: {
                ModernFileChooser.showOpenDialog("Select preset skin", null,
                        "PNG images (*.png)", new String[]{"png"}, file -> {
                            try {
                                AutoSkinSettings.setPreset(file);
                                presetSkinButton.displayString = "Replace preset skin";
                                autoSkinButton.displayString = autoSkinLabel();
                                skinModelButton.displayString = skinModelLabel();
                                notification = new Notification(TextFormatting.translate(
                                        "&aPreset skin saved. Turn Auto skin on to use it after refresh login.&r"), 5000L);
                            } catch (IOException error) {
                                notification = new Notification(TextFormatting.translate(
                                        "&c" + error.getMessage() + "&r"), 5000L);
                            }
                            updateScreen();
                        }, null);
                break;
            }
            case 14:
                AutoSkinSettings.setEnabled(!AutoSkinSettings.isEnabled());
                autoSkinButton.displayString = autoSkinLabel();
                updateScreen();
                break;
            case 15:
                AutoSkinSettings.setSlim(!AutoSkinSettings.isSlim());
                skinModelButton.displayString = skinModelLabel();
                break;
        }
    }

    class GuiAccountList extends GuiSlot {
        private static final int SLOT_H  = 36;
        private static final int HEAD_SZ = 28;

        GuiAccountList(Minecraft mc, int listBottom) {
            super(mc, GuiAccountManager.this.listPanelW - 20, GuiAccountManager.this.height,
                    GuiAccountManager.this.listTop, listBottom, SLOT_H);
            setSlotXBoundsFromLeft(GuiAccountManager.this.listPanelX + 10);
        }

        @Override protected int getSize()             { return filteredList.size(); }
        @Override protected boolean isSelected(int i) { return i == selectedAccount; }
        @Override public int getListWidth()           { return GuiAccountManager.this.listPanelW - 28; }
        @Override protected int getContentHeight()    { return filteredList.size() * SLOT_H; }
        @Override protected int getScrollBarX()       { return GuiAccountManager.this.listPanelX
                + GuiAccountManager.this.listPanelW - 10; }

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
            MindlessFontRenderer sfReg  = new MinecraftFontAdapter(fontRendererObj);
            MindlessFontRenderer sfBold = new MinecraftFontAdapter(fontRendererObj);

            boolean hov = mx >= x && mx <= x + getListWidth() && my >= y && my <= y + h;
            if (isSelected(id)) {
                RoundedUtils.drawRound(x, y + 1, getListWidth(), h - 2, 4f, C_SEL);
                drawRect(x, y + 5, x + 2, y + h - 5, C_ACCENT);
            } else if (hov) {
                RoundedUtils.drawRound(x, y + 1, getListWidth(), h - 2, 4f, C_ROW_HOV);
            } else {
                RoundedUtils.drawRound(x, y + 1, getListWidth(), h - 2, 4f, C_ROW);
            }

            String rawName = account.getUsername();
            ResourceLocation head = PlayerHeadCache.get(StringUtils.isBlank(rawName) ? null : rawName);
            int headX = x + 4;
            // Centre on the row rather than sitting at a fixed inset. GuiSlot hands us
            // h = SLOT_H - 4 = 32 against a 28px head, so the old y + 4 left 4px above and
            // nothing below, and the head overhung the bottom of the row background.
            int headY = y + Math.max(0, (h - HEAD_SZ) / 2);
            if (head != null) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                GuiAccountManager.this.mc.getTextureManager().bindTexture(head);
                Gui.drawScaledCustomSizeModalRect(headX, headY, 0, 0, 32, 32, HEAD_SZ, HEAD_SZ, 32f, 32f);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } else {
                RoundedUtils.drawRound(headX, headY, HEAD_SZ, HEAD_SZ, 3f, 0xFF1A1A22);
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
