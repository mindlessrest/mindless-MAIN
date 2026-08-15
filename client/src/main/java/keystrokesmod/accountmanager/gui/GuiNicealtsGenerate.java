package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import keystrokesmod.accountmanager.AccountAuthStatus;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.AltShopHttp;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountLogin;
import keystrokesmod.accountmanager.auth.AccountType;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.apache.commons.lang3.StringUtils;

public class GuiNicealtsGenerate extends GuiScreen {
    private final GuiScreen parent;
    private final String apiKey;
    private final int[] stock;

    private volatile String resultMsg  = null;
    private volatile boolean resultErr = false;
    private final AtomicBoolean generating = new AtomicBoolean(false);

    private static final String[] CATEGORIES = {"Unbanned", "DonutSMP", "Banned"};
    private static final String[] PRICES     = {"SUB",      "SUB",      "SUB"};

    public GuiNicealtsGenerate(GuiScreen parent, String apiKey, int[] stock) {
        this.parent = parent;
        this.apiKey = apiKey;
        this.stock  = stock;
    }

    private int stockFor(int idx) {
        if (stock == null) return 0;
        switch (idx) {
            case 0: return safe(1) + safe(2) + safe(3) + safe(4) + safe(67);
            case 1: return safe(5);
            case 2: return safe(6);
            default: return 0;
        }
    }
    private int safe(int pid) { return (stock != null && pid < stock.length) ? stock[pid] : 0; }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, startY = height / 2 - 33;
        for (int i = 0; i < CATEGORIES.length; i++) {
            int cnt = stockFor(i);
            GuiButton btn = new GuiButton(i + 1, cx - 80, startY + i * 22, 160, 20,
                    CATEGORIES[i] + ": " + cnt + " (" + PRICES[i] + ")");
            btn.enabled = cnt > 0;
            buttonList.add(btn);
        }
        buttonList.add(new GuiButton(0, cx - 50, height - 28, 100, 20, "Back"));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2;
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Generate", cx, 16, -1);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GRAY + "Select a category to generate.", cx, 28, -1);
        if (generating.get()) {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + "Generating...", cx, height - 52, -1);
        } else if (resultMsg != null) {
            String col = resultErr ? EnumChatFormatting.RED.toString() : EnumChatFormatting.GREEN.toString();
            drawCenteredString(fontRendererObj, col + resultMsg, cx, height - 52, -1);
        }
        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void actionPerformed(GuiButton btn) throws IOException {
        if (btn.id == 0) { mc.displayGuiScreen(parent); return; }
        int idx = btn.id - 1;
        if (idx < 0 || idx >= CATEGORIES.length) return;
        if (!generating.compareAndSet(false, true)) return;
        resultMsg = null;
        setEnabled(false);
        String category = CATEGORIES[idx];
        new Thread(() -> {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                String body = "{\"api_key\":\"" + AltShopHttp.escapeJson(apiKey) + "\","
                        + "\"category\":\"" + category + "\"}";
                String resp  = AltShopHttp.post("https://app.nicealts.com/api/generate", body);
                String token = AltShopHttp.field(resp, "token");
                if (token == null || token.isEmpty()) {
                    resultMsg = "Generation failed — no token returned"; resultErr = true; return;
                }
                Account acc = new Account("", token, "", "", 0L, AccountType.TOKEN);
                acc.authStatus = AccountAuthStatus.WORKING;
                AccountManager.accounts.add(acc);
                AccountLogin.login(acc, exec).get();
                if (StringUtils.isBlank(acc.getUsername())) {
                    AccountManager.accounts.remove(acc);
                    resultMsg = "Login failed"; resultErr = true;
                } else {
                    AccountManager.save();
                    resultMsg = "Success! Logged in as " + acc.getUsername(); resultErr = false;
                }
            } catch (Exception e) {
                resultMsg = "Error: " + e.getMessage(); resultErr = true;
            } finally {
                exec.shutdownNow();
                generating.set(false);
                mc.addScheduledTask(() -> setEnabled(true));
            }
        }, "nicealts-generate").start();
    }

    private void setEnabled(boolean v) {
        for (GuiButton b : buttonList) if (b.id != 0) b.enabled = v;
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == 1) mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
