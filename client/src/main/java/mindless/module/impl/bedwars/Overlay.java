package mindless.module.impl.bedwars;

import com.mojang.realmsclient.gui.ChatFormatting;
import mindless.module.Module;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.HypixelBedWars;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Overlay extends Module {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final float PAD_X   = 10.0f;
    private static final float PAD_Y   = 8.0f;
    private static final float ROW_GAP = 3.0f;

    // Column widths (in unscaled font pixels)
    private static final float COL_IGN   = 90.0f;
    private static final float COL_STAR  = 36.0f;
    private static final float COL_FKDR  = 36.0f;
    private static final float COL_WLR   = 36.0f;
    private static final float COL_WS    = 32.0f;   // winstreak
    private static final float COL_WINS  = 38.0f;
    private static final float COL_BED   = 36.0f;   // beds broken
    private static final float COL_FK    = 36.0f;   // final kills
    private static final float COL_KDR   = 36.0f;
    private static final float[] COLS    = { COL_IGN, COL_STAR, COL_FKDR, COL_WLR,
            COL_WS, COL_WINS, COL_BED, COL_FK, COL_KDR };
    private static final String[] HEADERS = { "IGN", "⭐", "FKDR", "WLR",
            "WS", "Wins", "Beds", "FK", "KDR" };

    // ── Settings ──────────────────────────────────────────────────────────────
    private final TextSetting    apiKey;
    private final SliderSetting  scale;
    private final SliderSetting  maxPlayers;
    private final ButtonSetting  showTeammates;
    private final KeySetting     toggleKey;

    // ── State ─────────────────────────────────────────────────────────────────
    private static final Map<String, HypixelBedWars.BedwarsPlayer> cache = new ConcurrentHashMap<>();
    private static final Set<String> pendingFetches = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService executor    = Executors.newFixedThreadPool(4);

    private static HypixelBedWars api;
    private static String  currentKey  = "";
    private float          relativeX   = 0.02f;
    private float          relativeY   = 0.05f;

    // Drag support
    private boolean dragging;
    private float   dragOffX, dragOffY;

    // Keybind toggle – tracks previous key state to avoid repeat
    private boolean keyWasDown = false;
    private boolean overlayVisible = true;

    // ─────────────────────────────────────────────────────────────────────────

    public Overlay() {
        super("Overlay", "Lists everyone in the game with their team.", category.bedwars);
        this.registerSetting(apiKey       = new TextSetting("API Key",        "Paste key...", "", 64));
        this.registerSetting(scale        = new SliderSetting("Scale",        "x", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(maxPlayers   = new SliderSetting("Max Players",  "",  16,  4,  32, 1));
        this.registerSetting(showTeammates= new ButtonSetting("Show Teammates", false));
        this.registerSetting(toggleKey    = new KeySetting("Toggle Key",      Keyboard.KEY_INSERT));
    }

    @Override
    public void onDisable() {
        cache.clear();
        pendingFetches.clear();
    }

    // ── Tick: fetch stats ─────────────────────────────────────────────────────

    private void updateApiInstance() {
        String key = apiKey.getText();
        if (key != null && !key.trim().isEmpty() && !key.equals(currentKey)) {
            this.currentKey = key.trim();
            this.api        = new HypixelBedWars(this.currentKey);
            cache.clear();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.isEnabled() || !Utils.nullCheck()) return;

        // Keybind toggle (edge-detected so holding doesn't flicker)
        boolean keyDown = toggleKey.isPressed();
        if (keyDown && !keyWasDown) {
            overlayVisible = !overlayVisible;
        }
        keyWasDown = keyDown;

        updateApiInstance();

        if (Utils.getBedwarsStatus() != 2) {
            if (!cache.isEmpty()) cache.clear();
            return;
        }

        if (mc.getNetHandler() == null) return;

        Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
        Set<String> currentTabNames = new HashSet<>();

        for (NetworkPlayerInfo info : playerInfoMap) {
            if (info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            if (name == null || name.isEmpty()) continue;

            // skip NPC profiles (version == 2 UUID = offline/NPC)
            if (info.getGameProfile().getId() != null && info.getGameProfile().getId().version() == 2) continue;
            if (name.equalsIgnoreCase(mc.thePlayer.getName()) && !showTeammates.isToggled()) continue;

            String lk = name.toLowerCase();
            currentTabNames.add(lk);

            if (!cache.containsKey(lk) && !pendingFetches.contains(lk)) {
                fetchPlayerStats(name);
            }
        }

        // drop entries that aren't on tab anymore (dead or left match)
        cache.keySet().removeIf(player -> !currentTabNames.contains(player));
    }

    /**
     * Stats already fetched for this player, or null.
     *
     * The cache is shared rather than per-instance so Player ESP can label a nametag from
     * whatever the overlay has already pulled instead of issuing its own request for the same
     * player, and so both surfaces agree on what they are showing.
     */
    public static HypixelBedWars.BedwarsPlayer statsFor(String username) {
        return username == null ? null : cache.get(username.toLowerCase());
    }

    /** Queues a fetch if one is not already cached or in flight. Safe to call every frame. */
    public static void requestStats(String username) {
        if (username == null || username.isEmpty() || api == null) return;
        String key = username.toLowerCase();
        if (cache.containsKey(key) || pendingFetches.contains(key)) return;
        queueFetch(username);
    }

    private void fetchPlayerStats(String username) {
        queueFetch(username);
    }

    private static void queueFetch(String username) {
        String key = username.toLowerCase();
        if (pendingFetches.contains(key) || api == null) return;
        pendingFetches.add(key);
        executor.submit(() -> {
            try {
                HypixelBedWars.BedwarsPlayer data = api.getStats(username);
                if (data != null) cache.put(key, data);
            } catch (Exception ignored) {
            } finally {
                pendingFetches.remove(key);
            }
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;
        if (Utils.getBedwarsStatus() != 2) return;
        if (!overlayVisible) return;

        draw();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void draw() {
        MindlessFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        List<HypixelBedWars.BedwarsPlayer> players = buildSortedList();

        float s      = (float) scale.getInput();
        float fh     = font.getFontHeight();
        int   rows   = 1 + players.size() + (players.isEmpty() ? 1 : 0); // header + data (or placeholder)

        // Total table width = sum of column widths + dividers
        float tableW = 0;
        for (float c : COLS) tableW += c;

        float w = (tableW + PAD_X * 2.0f) * s;
        float h = (PAD_Y * 2.0f + rows * fh + (rows - 1) * ROW_GAP + (fh + ROW_GAP)) * s;

        ScaledResolution sr = ScaledResolutionCache.get();
        float sw   = Math.max(1, sr.getScaledWidth());
        float sh   = Math.max(1, sr.getScaledHeight());
        float left = relativeX * sw;
        float top  = relativeY * sh;

        float radius = 8.0f * mindless.module.impl.theme.ThemeManager.roundingScale();

        // ── Background stack ─────────────────────────────────────────────────
        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 6.0f, new Color(0, 0, 0, 140).getRGB());
        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(8, 8, 12, 200));
        BlurUtils.blurEndRegion(2, 2.6f, 0.88f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(10, 10, 18, 130));
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 22), new Color(255, 255, 255, 3));
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 35));

        // ── Header accent bar ────────────────────────────────────────────────
        float headerBarH = (fh + ROW_GAP) * s;
        RoundedUtils.drawRound(left, top, w, headerBarH + PAD_Y * s,
                radius, new Color(60, 80, 160, 55));

        // ── Text rendering ───────────────────────────────────────────────────
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv  = 1.0f / s;
        float baseX = left * inv + PAD_X;
        float baseY = top  * inv + PAD_Y;

        // Draw column headers
        drawRow(font, baseX, baseY, HEADERS, 0xFFA0B8FF, true);

        float dataY = baseY + fh + ROW_GAP * 2.5f;

        // Draw player rows
        if (apiKey.getText() == null || apiKey.getText().trim().isEmpty()) {
            font.drawString(ChatFormatting.RED + "Set API Key in settings!", baseX, dataY, 0xFFFFFFFF, false);
        } else if (players.isEmpty()) {
            font.drawString(ChatFormatting.GRAY + "Scanning tablist...", baseX, dataY, 0xFFFFFFFF, false);
        } else {
            int limit = (int) maxPlayers.getInput();
            int count = 0;
            for (HypixelBedWars.BedwarsPlayer p : players) {
                if (count >= limit) break;
                drawPlayerRow(font, baseX, dataY, p);
                dataY += fh + ROW_GAP;
                count++;
            }
        }

        GlStateManager.popMatrix();
    }

    /** Draw a full row of column-aligned strings. */
    private void drawRow(MindlessFontRenderer font, float x, float y,
                         String[] cells, int defaultColor, boolean isHeader) {
        float cx = x;
        for (int i = 0; i < COLS.length && i < cells.length; i++) {
            String cell = cells[i];
            font.drawString(cell, cx, y, defaultColor, false);
            cx += COLS[i];
        }
    }

    /** Draw one player row with per-cell coloring. */
    private void drawPlayerRow(MindlessFontRenderer font, float x, float y,
                               HypixelBedWars.BedwarsPlayer p) {
        float cx = x;

        // IGN
        String ign = p.displayName != null ? p.displayName : "?";
        font.drawString(ign, cx, y, 0xFFFFFFFF, false);
        cx += COL_IGN;

        // ⭐ star / level
        String levelColor = getLevelColor(p.level.level);
        font.drawString(levelColor + p.level.level, cx, y, 0xFFFFFFFF, false);
        cx += COL_STAR;

        // FKDR
        String fkdrStr = formatRatio(p.overall.finalKillDeathRatio);
        font.drawString(getFkdrColor(p.overall.finalKillDeathRatio) + fkdrStr, cx, y, 0xFFFFFFFF, false);
        cx += COL_FKDR;

        // WLR
        String wlrStr = formatRatio(p.overall.winLossRatio);
        font.drawString(getWlrColor(p.overall.winLossRatio) + wlrStr, cx, y, 0xFFFFFFFF, false);
        cx += COL_WLR;

        // Winstreak
        long ws = p.overall.winstreak;
        font.drawString(getWsColor(ws) + ws, cx, y, 0xFFFFFFFF, false);
        cx += COL_WS;

        // Wins
        font.drawString(ChatFormatting.GREEN + String.valueOf(p.overall.wins), cx, y, 0xFFFFFFFF, false);
        cx += COL_WINS;

        // Beds broken
        long beds = p.overall.bedsBroken;
        font.drawString(ChatFormatting.AQUA + String.valueOf(beds), cx, y, 0xFFFFFFFF, false);
        cx += COL_BED;

        // Final kills
        font.drawString(ChatFormatting.DARK_GREEN + String.valueOf(p.overall.finalKills), cx, y, 0xFFFFFFFF, false);
        cx += COL_FK;

        // KDR
        double kdr = p.overall.killDeathRatio;
        font.drawString(getKdrColor(kdr) + formatRatio(kdr), cx, y, 0xFFFFFFFF, false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<HypixelBedWars.BedwarsPlayer> buildSortedList() {
        List<HypixelBedWars.BedwarsPlayer> list = new ArrayList<>(cache.values());
        list.sort((a, b) -> Integer.compare(b.level.level, a.level.level));
        return list;
    }

    private static String formatRatio(double v) {
        if (v >= 100.0) return String.valueOf((int) v);
        return String.format("%.2f", v);
    }

    private String getLevelColor(int level) {
        if (level >= 1000) return ChatFormatting.DARK_RED.toString();
        if (level >= 500)  return ChatFormatting.DARK_AQUA.toString();
        if (level >= 400)  return ChatFormatting.DARK_GREEN.toString();
        if (level >= 300)  return ChatFormatting.AQUA.toString();
        if (level >= 200)  return ChatFormatting.GOLD.toString();
        if (level >= 100)  return ChatFormatting.WHITE.toString();
        return ChatFormatting.GRAY.toString();
    }

    private String getFkdrColor(double fkdr) {
        if (fkdr >= 10.0) return ChatFormatting.DARK_RED.toString();
        if (fkdr >= 5.0)  return ChatFormatting.RED.toString();
        if (fkdr >= 2.0)  return ChatFormatting.GOLD.toString();
        if (fkdr >= 1.0)  return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }

    private String getWlrColor(double wlr) {
        if (wlr >= 4.0)  return ChatFormatting.DARK_RED.toString();
        if (wlr >= 2.0)  return ChatFormatting.RED.toString();
        if (wlr >= 1.0)  return ChatFormatting.GOLD.toString();
        if (wlr >= 0.5)  return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }

    private String getKdrColor(double kdr) {
        if (kdr >= 5.0)  return ChatFormatting.DARK_RED.toString();
        if (kdr >= 3.0)  return ChatFormatting.RED.toString();
        if (kdr >= 1.5)  return ChatFormatting.GOLD.toString();
        if (kdr >= 1.0)  return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }

    private String getWsColor(long ws) {
        if (ws >= 50)  return ChatFormatting.DARK_RED.toString();
        if (ws >= 20)  return ChatFormatting.RED.toString();
        if (ws >= 10)  return ChatFormatting.GOLD.toString();
        if (ws >= 5)   return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }
}