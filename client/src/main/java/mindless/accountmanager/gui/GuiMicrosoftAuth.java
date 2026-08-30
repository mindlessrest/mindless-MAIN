package mindless.accountmanager.gui;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.AccountType;
import mindless.accountmanager.auth.MicrosoftAuth;
import mindless.accountmanager.auth.SessionManager;
import mindless.accountmanager.utils.Notification;
import mindless.accountmanager.utils.SystemUtils;
import mindless.accountmanager.utils.TextFormatting;
import mindless.utility.font.MinecraftFontAdapter;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.apache.commons.lang3.RandomStringUtils;

public class GuiMicrosoftAuth extends GuiScreen {
    private final GuiScreen previousScreen;
    private final String state;
    private GuiButton openButton   = null;
    private GuiButton copyButton   = null;
    private GuiButton cancelButton = null;
    private boolean openButtonEnabled = true;
    private String status = null;
    private String cause  = null;
    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private boolean success = false;
    private long lastDotUpdateTime;
    private int dotCount;

    public GuiMicrosoftAuth(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
        this.state = RandomStringUtils.randomAlphanumeric(8);
        this.lastDotUpdateTime = System.currentTimeMillis();
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int bw = 200, bh = 20, gap = 6, cx = width/2;
        int baseY = height/2 + 10;
        openButton   = new GuiButton(0, cx - bw/2, baseY,          bw, bh, "Open Link");
        copyButton   = new GuiButton(1, cx - bw/2, baseY+bh+gap,   bw, bh, "Copy Link");
        cancelButton = new GuiButton(2, cx - bw/2, baseY+(bh+gap)*2, bw, bh, "Cancel");
        buttonList.add(openButton);
        buttonList.add(copyButton);
        buttonList.add(cancelButton);

        if (task == null) {
            status = "Waiting for login";
            if (executor == null) executor = Executors.newSingleThreadExecutor();
            AtomicReference<String> refreshRef = new AtomicReference<>("");
            AtomicReference<String> accessRef  = new AtomicReference<>("");
            CompletableFuture<String> s1 = MicrosoftAuth.acquireMSAuthCode(state, executor);
            CompletableFuture<java.util.Map<String,String>> s2 = s1.thenComposeAsync(msCode -> {
                openButtonEnabled = false;
                status = "Acquiring Microsoft access tokens";
                return MicrosoftAuth.acquireMSAccessTokens(msCode, executor);
            }, executor);
            CompletableFuture<String> s3 = s2.thenComposeAsync(ms -> {
                status = "Acquiring Xbox access token";
                refreshRef.set(ms.get("refresh_token"));
                return MicrosoftAuth.acquireXboxAccessToken(ms.get("access_token"), executor);
            }, executor);
            CompletableFuture<java.util.Map<String,String>> s4 = s3.thenComposeAsync(xbox -> {
                status = "Acquiring Xbox XSTS token";
                return MicrosoftAuth.acquireXboxXstsToken(xbox, executor);
            }, executor);
            CompletableFuture<String> s5 = s4.thenComposeAsync(xsts -> {
                status = "Acquiring Minecraft access token";
                return MicrosoftAuth.acquireMCAccessToken(xsts.get("Token"), xsts.get("uhs"), executor);
            }, executor);
            CompletableFuture<net.minecraft.util.Session> s6 = s5.thenComposeAsync(mc2 -> {
                status = "Fetching your Minecraft profile";
                accessRef.set(mc2);
                return MicrosoftAuth.login(mc2, executor);
            }, executor);
            task = s6.thenAccept(session -> {
                status = null; cause = null;
                Account acc = new Account(refreshRef.get(), accessRef.get(),
                        session.getUsername(), session.getPlayerID(), 0L, AccountType.MICROSOFT);
                for (Account a : AccountManager.accounts) {
                    if (!acc.getUsername().equals(a.getUsername())) continue;
                    acc.setUnban(a.getUnban()); break;
                }
                AccountManager.accounts.add(acc);
                AccountManager.save();
                SessionManager.set(session);
                success = true;
            }).exceptionally(error -> {
                openButtonEnabled = true;
                status = "Login failed!";
                Throwable c = error.getCause();
                cause = (c != null && c.getMessage() != null)
                        ? "Reason: " + c.getMessage() : "Unknown error occurred.";
                return null;
            });
        }
    }

    @Override
    public void onGuiClosed() {
        if (task != null && !task.isDone()) { task.cancel(true); executor.shutdownNow(); }
    }

    @Override
    public void updateScreen() {
        if (success) {
            mc.displayGuiScreen(new GuiAccountManager(previousScreen,
                    new Notification(TextFormatting.translate(
                            String.format("&aSuccessful login! (%s)&r", SessionManager.get().getUsername())), 5000L)));
            success = false;
        }
        if (status != null && !success && task != null && !task.isDone()) {
            if (System.currentTimeMillis() - lastDotUpdateTime >= 200L) {
                dotCount = (dotCount + 1) % 4;
                lastDotUpdateTime = System.currentTimeMillis();
            }
        } else { dotCount = 0; }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        if (openButton  != null) openButton.enabled  = openButtonEnabled;
        if (copyButton  != null) copyButton.enabled  = openButtonEnabled;

        drawRect(0, 0, width, height, GuiAccountManager.C_BG);
        MindlessFontRenderer sfBold = new MinecraftFontAdapter(fontRendererObj);
        MindlessFontRenderer sfReg  = new MinecraftFontAdapter(fontRendererObj);
        MindlessFontRenderer sfSm   = new MinecraftFontAdapter(fontRendererObj);

        int cardW = 280, cardH = 160;
        int cardX = width/2 - cardW/2, cardY = height/2 - cardH/2 - 30;
        RoundedUtils.drawRound(cardX, cardY, cardW, cardH, 6f, GuiAccountManager.C_PANEL);
        drawRect(cardX, cardY, cardX + cardW, cardY + 1, GuiAccountManager.C_ACCENT_DIM);
        sfBold.drawString("Microsoft Authentication", width/2f - sfBold.getStringWidth("Microsoft Authentication")/2f, cardY + 10f, GuiAccountManager.C_TEXT, false);

        if (status != null) {
            StringBuilder sb = new StringBuilder(status);
            if (task != null && !task.isDone() && cause == null) {
                for (int i = 0; i < dotCount; i++) sb.append('.');
            }
            String disp = sb.toString();
            sfReg.drawString(disp, width/2f - sfReg.getStringWidth(disp)/2f, cardY + 30f, GuiAccountManager.C_MUTED, false);
        }
        if (cause != null) {
            sfSm.drawString(cause, width/2f - sfSm.getStringWidth(cause)/2f, cardY + 48f, GuiAccountManager.C_DANGER, false);
        }

        for (GuiButton b : buttonList) {
            boolean isCancel = b.id == 2;
            boolean isOpen   = b.id == 0;
            boolean hov = b.enabled && mx >= b.xPosition && mx < b.xPosition + b.width
                    && my >= b.yPosition && my < b.yPosition + b.height;
            int bg  = isCancel ? GuiAccountManager.C_ROW : (isOpen ? 0xCC181A2A : GuiAccountManager.C_ROW);
            int bgH = isCancel ? GuiAccountManager.C_ROW_HOV : (isOpen ? 0xCC1E2035 : GuiAccountManager.C_ROW_HOV);
            int fg  = !b.enabled ? GuiAccountManager.C_DIM
                    : (isCancel ? GuiAccountManager.C_MUTED : (isOpen ? GuiAccountManager.C_ACCENT : GuiAccountManager.C_TEXT));
            RoundedUtils.drawRound(b.xPosition, b.yPosition, b.width, b.height, 4f, hov ? bgH : bg);
            float tw = sfReg.getStringWidth(b.displayString);
            sfReg.drawString(b.displayString, b.xPosition + b.width/2f - tw/2f,
                    b.yPosition + b.height/2f - sfReg.getFontHeight()/2f, fg, false);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) actionPerformed(cancelButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) return;
        switch (button.id) {
            case 0:
                SystemUtils.openWebLink(MicrosoftAuth.getMSAuthLink(state));
                status = "Please complete the login in your browser";
                cause = null; lastDotUpdateTime = System.currentTimeMillis(); dotCount = 0;
                break;
            case 1:
                URI url = MicrosoftAuth.getMSAuthLink(state);
                if (url != null) { SystemUtils.setClipboard(url.toString()); status = "Login link copied!"; cause = null; dotCount = 0; }
                else { status = "Failed to get login link."; cause = "Please try again."; dotCount = 0; }
                break;
            case 2:
                mc.displayGuiScreen(previousScreen);
                break;
        }
    }
}
