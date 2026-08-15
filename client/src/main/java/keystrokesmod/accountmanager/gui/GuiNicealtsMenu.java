package keystrokesmod.accountmanager.gui;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import keystrokesmod.accountmanager.AltShopHttp;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

public class GuiNicealtsMenu extends GuiScreen {
    private final GuiScreen parent;
    private final String apiKey;

    private String balance   = null;
    private String genAccess = null;
    private String subExpiry = null;
    private boolean balanceError = false;

    private int[] stock = null;
    private boolean stockError = false;

    private final AtomicBoolean fetchingBalance = new AtomicBoolean(false);
    private final AtomicBoolean fetchingStock   = new AtomicBoolean(false);

    public GuiNicealtsMenu(GuiScreen parent, String apiKey) {
        this.parent = parent;
        this.apiKey = apiKey;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2;
        buttonList.add(new GuiButton(0, cx - 50, height - 52, 100, 20, "Back"));
        buttonList.add(new GuiButton(1, cx - 106, height - 28, 100, 20, "Purchase"));
        buttonList.add(new GuiButton(2, cx + 6,   height - 28, 100, 20, "Generate"));
        fetchBalance();
        fetchStock();
    }

    private void fetchBalance() {
        if (!fetchingBalance.compareAndSet(false, true)) return;
        balance = null; balanceError = false;
        new Thread(() -> {
            try {
                String resp = AltShopHttp.post(
                        "https://app.nicealts.com/api/balance",
                        "{\"api_key\":\"" + AltShopHttp.escapeJson(apiKey) + "\"}");
                parseBalance(resp);
            } catch (Exception e) {
                balanceError = true;
                balance = "Failed";
            } finally {
                fetchingBalance.set(false);
            }
        }, "nicealts-balance").start();
    }

    private void fetchStock() {
        if (!fetchingStock.compareAndSet(false, true)) return;
        stock = null; stockError = false;
        new Thread(() -> {
            try {
                String resp = AltShopHttp.get("https://app.nicealts.com/public/stock");
                parseStock(resp);
            } catch (Exception e) {
                stockError = true;
                stock = new int[0];
            } finally {
                fetchingStock.set(false);
            }
        }, "nicealts-stock").start();
    }

    private void parseBalance(String json) {
        String bal = AltShopHttp.field(json, "balance");
        String status  = AltShopHttp.field(json, "sub_status");
        String expiry  = AltShopHttp.field(json, "sub_expiry");
        balance = bal != null ? bal : "?";
        if (status == null || status.equals("null")) {
            genAccess = "None"; subExpiry = null;
        } else {
            genAccess = "P2".equals(status) ? "Premium+" : ("P".equals(status) ? "Premium" : "None");
        }
        if (expiry != null && !expiry.equals("null") && !"None".equals(genAccess)) {
            try {
                long days = ChronoUnit.DAYS.between(Instant.now(), Instant.parse(expiry));
                subExpiry = days > 0 ? days + "d remaining" : "Expired";
            } catch (Exception ignored) {}
        }
    }

    private void parseStock(String json) {
        stock = new int[100];
        for (int i = 1; i <= 8; i++) {
            try { stock[i] = Integer.parseInt(AltShopHttp.field(json, String.valueOf(i))); }
            catch (Exception ignored) {}
        }
        try { stock[67] = Integer.parseInt(AltShopHttp.field(json, "67")); }
        catch (Exception ignored) {}
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2, y = 16;
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Nicealts", cx, y, -1);
        y += 14;
        if (balance == null) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Loading...", cx, y, -1);
            y += 11;
        } else if (balanceError) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.RED + "Balance: " + balance, cx, y, -1);
            y += 11;
        } else {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + "Balance: " + EnumChatFormatting.WHITE + balance + " credits", cx, y, -1);
            y += 11;
            String col = "None".equals(genAccess) ? EnumChatFormatting.RED.toString()
                    : ("Premium+".equals(genAccess) ? EnumChatFormatting.GOLD.toString()
                    : EnumChatFormatting.YELLOW.toString());
            String exp = subExpiry != null ? EnumChatFormatting.DARK_GRAY + " (" + subExpiry + ")" : "";
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + "Generator access: " + col + genAccess + exp, cx, y, -1);
            y += 14;
        }
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.YELLOW + "" + EnumChatFormatting.BOLD + "Stock", cx, y + 4, -1);
        y += 16;
        if (stock == null) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Loading...", cx, y, -1);
        } else if (stockError) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.RED + "Failed to load stock", cx, y, -1);
        } else {
            Object[][] rows = {
                    {"Hypixel Unbanned 1-7", 1, "10c"}, {"Hypixel Bedwars 8+", 2, "12c"},
                    {"Hypixel Ranked", 3, "20c"}, {"DonutSMP Unbanned", 5, "5c"},
                    {"Banned", 6, "2c"}, {"Generator Unbanned", -1, "SUB"}
            };
            for (Object[] row : rows) {
                int pid = (Integer) row[1];
                int cnt = pid == -1
                        ? stockAt(1) + stockAt(2) + stockAt(3) + stockAt(4) + stockAt(67)
                        : stockAt(pid);
                String cc = cnt > 0 ? EnumChatFormatting.GREEN.toString() : EnumChatFormatting.RED.toString();
                drawCenteredString(fontRendererObj,
                        EnumChatFormatting.GRAY + (String) row[0] + ": " + cc + cnt
                                + EnumChatFormatting.DARK_GRAY + " (" + row[2] + ")",
                        cx, y, -1);
                y += 10;
            }
        }
        super.drawScreen(mx, my, pt);
    }

    private int stockAt(int pid) {
        return (stock != null && pid < stock.length) ? stock[pid] : 0;
    }

    @Override
    protected void actionPerformed(GuiButton btn) throws IOException {
        switch (btn.id) {
            case 0: mc.displayGuiScreen(parent); break;
            case 1: mc.displayGuiScreen(new GuiNicealtsPurchase(this, apiKey, stock)); break;
            case 2: mc.displayGuiScreen(new GuiNicealtsGenerate(this, apiKey, stock)); break;
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == 1) mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
