package mindless.accountmanager.gui;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import mindless.accountmanager.AccountAuthStatus;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.AltShopHttp;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.AccountLogin;
import mindless.accountmanager.auth.AccountType;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.apache.commons.lang3.StringUtils;

public class GuiNicealtsPurchase extends GuiScreen {
    private final GuiScreen parent;
    private final String apiKey;
    private final int[] stock;

    private volatile String resultMsg  = null;
    private volatile boolean resultErr = false;
    private final AtomicBoolean purchasing = new AtomicBoolean(false);

    // product_id -> display name, price
    private static final int[]    PIDS   = {1,                    2,                  3,               5,                6};
    private static final String[] NAMES  = {"Hypixel Unbanned 1-7","Hypixel Bedwars 8+","Hypixel Ranked","DonutSMP Unbanned","Banned"};
    private static final String[] PRICES = {"10c",                 "12c",              "20c",           "5c",              "2c"};

    public GuiNicealtsPurchase(GuiScreen parent, String apiKey, int[] stock) {
        this.parent = parent;
        this.apiKey = apiKey;
        this.stock  = stock;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, startY = height / 2 - 55;
        for (int i = 0; i < PIDS.length; i++) {
            int pid = PIDS[i];
            int cnt = (stock != null && pid < stock.length) ? stock[pid] : 0;
            GuiButton btn = new GuiButton(pid, cx - 90, startY + i * 22, 180, 20,
                    NAMES[i] + ": " + cnt + " (" + PRICES[i] + ")");
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
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Purchase", cx, 16, -1);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GRAY + "Select a product to purchase.", cx, 28, -1);
        if (purchasing.get()) {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + "Purchasing...", cx, height - 52, -1);
        } else if (resultMsg != null) {
            String col = resultErr ? EnumChatFormatting.RED.toString() : EnumChatFormatting.GREEN.toString();
            drawCenteredString(fontRendererObj, col + resultMsg, cx, height - 52, -1);
        }
        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void actionPerformed(GuiButton btn) throws IOException {
        if (btn.id == 0) { mc.displayGuiScreen(parent); return; }
        if (!purchasing.compareAndSet(false, true)) return;
        resultMsg = null;
        setEnabled(false);
        int pid = btn.id;
        new Thread(() -> {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                String body = "{\"api_key\":\"" + AltShopHttp.escapeJson(apiKey) + "\","
                        + "\"product_id\":\"" + pid + "\"}";
                String resp = AltShopHttp.post("https://app.nicealts.com/api/purchase", body);

                // parse token from items array: [{mctoken: ... | refreshtoken: ...}]
                String mcToken = null, refreshToken = null;
                int as = resp.indexOf("["), ae = resp.lastIndexOf("]");
                if (as != -1 && ae != -1) {
                    String inner = resp.substring(as + 1, ae).trim();
                    if (inner.startsWith("\"") && inner.endsWith("\""))
                        inner = inner.substring(1, inner.length() - 1);
                    int mIdx = inner.indexOf("mctoken: ");
                    if (mIdx != -1) {
                        int end = inner.indexOf(" | ", mIdx);
                        mcToken = end != -1 ? inner.substring(mIdx + 9, end) : inner.substring(mIdx + 9);
                    }
                    int rIdx = inner.indexOf("refreshtoken: ");
                    if (rIdx != -1) refreshToken = inner.substring(rIdx + 14);
                }

                if (mcToken == null || mcToken.isEmpty()) {
                    resultMsg = "Purchase failed — no token returned"; resultErr = true; return;
                }

                Account acc;
                if (refreshToken != null && !refreshToken.isEmpty()) {
                    acc = new Account(refreshToken, mcToken, "", "", 0L, AccountType.REFRESH);
                } else {
                    acc = new Account("", mcToken, "", "", 0L, AccountType.TOKEN);
                }
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
                purchasing.set(false);
                mc.addScheduledTask(() -> setEnabled(true));
            }
        }, "nicealts-purchase").start();
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
