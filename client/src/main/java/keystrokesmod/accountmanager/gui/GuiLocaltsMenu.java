package keystrokesmod.accountmanager.gui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
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

public class GuiLocaltsMenu extends GuiScreen {
    private static final String BASE = "https://localts.store/v1";
    private static final String REFRESH_PID = "6a245da8e2ec8410af11582c";

    private final GuiScreen parent;
    private final String apiKey;
    private final Gson gson = new Gson();

    // account info
    private String localtsUser    = null;
    private String localtsBalance = null;
    private boolean accountErr    = false;

    // products
    private final List<String>  productIds    = new ArrayList<>();
    private final List<String>  productNames  = new ArrayList<>();
    private final List<String>  productTypes  = new ArrayList<>();
    private final List<Integer> productStocks = new ArrayList<>();
    private final List<Integer> productPrices = new ArrayList<>();
    private boolean productsErr = false;
    private boolean loading     = true;

    // purchase state
    private volatile String  resultMsg = null;
    private volatile boolean resultErr = false;
    private volatile String  statusMsg = null;
    private final AtomicBoolean purchasing = new AtomicBoolean(false);
    private final AtomicBoolean fetching   = new AtomicBoolean(false);

    public GuiLocaltsMenu(GuiScreen parent, String apiKey) {
        this.parent = parent;
        this.apiKey = apiKey;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 50, height - 28, 100, 20, "Back"));
        loadData();
    }

    private void loadData() {
        if (!fetching.compareAndSet(false, true)) return;
        loading = true; accountErr = false; productsErr = false;
        new Thread(() -> {
            try {
                String me = request("GET", "/me");
                JsonObject obj = gson.fromJson(me, JsonObject.class);
                if (obj != null) {
                    localtsUser    = obj.has("username") ? obj.get("username").getAsString() : "?";
                    localtsBalance = obj.has("balance")  ? obj.get("balance").getAsString()  : "?";
                }
            } catch (Exception e) { accountErr = true; }
            try {
                String pr = request("GET", "/products");
                JsonObject obj = gson.fromJson(pr, JsonObject.class);
                if (obj != null) {
                    JsonArray arr = obj.has("products") ? obj.getAsJsonArray("products") : null;
                    if (arr != null) parseProducts(arr);
                }
            } catch (Exception e) { productsErr = true; }
            loading = false;
            fetching.set(false);
            mc.addScheduledTask(this::rebuildProductButtons);
        }, "localts-load").start();
    }

    private void parseProducts(JsonArray arr) {
        productIds.clear(); productNames.clear(); productTypes.clear();
        productStocks.clear(); productPrices.clear();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject p = arr.get(i).getAsJsonObject();
            if (!p.has("id")) continue;
            String id = p.get("id").getAsString();
            // only show the supported product(s)
            if (!REFRESH_PID.equals(id)) continue;
            productIds.add(id);
            productNames.add("Hypixel (Refresh)");
            productTypes.add(p.has("type")           ? p.get("type").getAsString()           : "");
            productStocks.add(p.has("stock")          ? p.get("stock").getAsInt()             : 0);
            productPrices.add(p.has("priceInCredits") ? p.get("priceInCredits").getAsInt()    : 0);
        }
    }

    private void rebuildProductButtons() {
        buttonList.removeIf(b -> b.id >= 1 && b.id <= 10);
        int cx = width / 2, y = height / 2 - 44;
        for (int i = 0; i < productIds.size(); i++) {
            String label = productNames.get(i) + ": " + productStocks.get(i)
                    + " (" + productPrices.get(i) + "c)";
            GuiButton btn = new GuiButton(i + 1, cx - 110, y + i * 22, 220, 20, label);
            btn.enabled = productStocks.get(i) > 0;
            buttonList.add(btn);
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2, y = 16;
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Localts", cx, y, -1);
        y += 14;
        if (loading) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Loading...", cx, y, -1);
        } else {
            if (accountErr || localtsUser == null) {
                drawCenteredString(fontRendererObj, EnumChatFormatting.RED + "User: failed to load", cx, y, -1);
            } else {
                drawCenteredString(fontRendererObj,
                        EnumChatFormatting.GRAY + "User: " + EnumChatFormatting.WHITE + localtsUser, cx, y, -1);
            }
            y += 11;
            if (accountErr || localtsBalance == null) {
                drawCenteredString(fontRendererObj, EnumChatFormatting.RED + "Balance: failed to load", cx, y, -1);
            } else {
                drawCenteredString(fontRendererObj,
                        EnumChatFormatting.GRAY + "Balance: " + EnumChatFormatting.WHITE + localtsBalance + " credits",
                        cx, y, -1);
            }
        }
        if (productsErr) {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.RED + "Failed to load products", cx, height / 2 - 20, -1);
        } else if (productIds.isEmpty() && !loading) {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.RED + "No products available", cx, height / 2 - 20, -1);
        }
        if (resultMsg != null) {
            String col = resultErr ? EnumChatFormatting.RED.toString() : EnumChatFormatting.GREEN.toString();
            drawCenteredString(fontRendererObj, col + resultMsg, cx, height - 52, -1);
        } else if (statusMsg != null) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + statusMsg, cx, height - 52, -1);
        } else if (purchasing.get()) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Purchasing...", cx, height - 52, -1);
        }
        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void actionPerformed(GuiButton btn) throws IOException {
        if (btn.id == 0) { mc.displayGuiScreen(parent); return; }
        int idx = btn.id - 1;
        if (idx < 0 || idx >= productIds.size()) return;
        if (!purchasing.compareAndSet(false, true)) return;
        resultMsg = null; resultErr = false; statusMsg = null;
        setEnabled(false);
        String productId = productIds.get(idx);
        String type      = productTypes.get(idx);
        new Thread(() -> {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                // 1. Place order
                String purchResp = request("POST",
                        "/products/" + encode(productId) + "/purchase?amount=1");
                JsonObject purchase = gson.fromJson(purchResp, JsonObject.class);
                String orderId = (purchase != null && purchase.has("orderId"))
                        ? purchase.get("orderId").getAsString() : null;
                if (orderId == null) throw new IOException("No order ID returned");

                // 2. Poll for fulfillment (up to 60 s, 2-second intervals)
                String status = "PENDING";
                JsonObject order = null;
                for (int i = 0; i < 30; i++) {
                    statusMsg = "Order status: " + status + " \u2022 check " + (i + 1) + "/30";
                    try {
                        String oResp = request("GET", "/orders/get-order?id=" + encode(orderId));
                        order = gson.fromJson(oResp, JsonObject.class);
                        if (order != null && order.has("status"))
                            status = order.get("status").getAsString();
                    } catch (Exception ignored) {
                        statusMsg = "Waiting for order... \u2022 check " + (i + 1) + "/30";
                    }
                    if (isTerminal(status)) break;
                    Thread.sleep(2000L);
                }

                if ("PACKAGED".equalsIgnoreCase(status)) {
                    deliver(productId, type, order, exec);
                } else if (isFailedStatus(status)) {
                    resultMsg = "Order failed: " + status; resultErr = true;
                } else {
                    resultMsg = "Order still processing — check later"; resultErr = true;
                }
            } catch (Exception e) {
                resultMsg = "Error: " + e.getMessage(); resultErr = true;
            } finally {
                exec.shutdownNow();
                purchasing.set(false);
                statusMsg = null;
                mc.addScheduledTask(() -> setEnabled(true));
            }
        }, "localts-purchase").start();
    }

    private void deliver(String productId, String type, JsonObject order, ExecutorService exec) {
        String content = null;
        if (order != null && order.has("items")) {
            JsonArray items = order.getAsJsonArray("items");
            if (items.size() > 0) {
                JsonObject item = items.get(0).getAsJsonObject();
                if (item.has("content")) content = item.get("content").getAsString();
            }
        }
        if (content == null || content.isEmpty()) {
            resultMsg = "No item delivered"; resultErr = true; return;
        }
        try {
            Account acc;
            if (isRefreshType(type, productId)) {
                String token = content;
                int col = token.indexOf(':');
                if (col >= 0) token = token.substring(col + 1).trim();
                acc = new Account(token, "", "", "", 0L, AccountType.REFRESH);
            } else {
                acc = new Account("", content, "", "", 0L, AccountType.TOKEN);
            }
            acc.authStatus = AccountAuthStatus.WORKING;
            AccountManager.accounts.add(acc);
            AccountLogin.login(acc, exec).get();
            if (StringUtils.isBlank(acc.getUsername())) {
                AccountManager.accounts.remove(acc);
                resultMsg = "Delivered but login failed"; resultErr = true;
            } else {
                AccountManager.save();
                resultMsg = "Success! Added as " + acc.getUsername(); resultErr = false;
            }
        } catch (Exception e) {
            resultMsg = "Deliver error: " + e.getMessage(); resultErr = true;
        }
    }

    private String request(String method, String path) throws Exception {
        String url = BASE + path;
        if ("POST".equals(method)) {
            return AltShopHttp.post(url, "{}", "X-API-Key", apiKey);
        }
        return AltShopHttp.get(url, "X-API-Key", apiKey);
    }

    private static boolean isTerminal(String s) {
        return "PACKAGED".equalsIgnoreCase(s) || isFailedStatus(s);
    }

    private static boolean isFailedStatus(String s) {
        return "CANCELLED".equalsIgnoreCase(s) || "CANCELED".equalsIgnoreCase(s)
                || "FAILED".equalsIgnoreCase(s) || "REFUNDED".equalsIgnoreCase(s);
    }

    private static boolean isRefreshType(String type, String productId) {
        return (type != null && type.toUpperCase().contains("REFRESH"))
                || REFRESH_PID.equals(productId);
    }

    private static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
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
