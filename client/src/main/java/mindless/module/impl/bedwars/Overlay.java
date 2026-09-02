package mindless.module.impl.bedwars;

import com.mojang.realmsclient.gui.ChatFormatting;
import mindless.module.Module;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
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
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Overlay extends Module {

    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 6.0f;
    private static final float LINE_GAP = 2.0f;

    private final SliderSetting scale;
    private final SliderSetting maxPlayers;
    private final ButtonSetting showTeammates;

    private final Map<String, HypixelBedWars.BedwarsPlayer> cache = new ConcurrentHashMap<>();
    private final Set<String> pendingFetches = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private HypixelBedWars api;
    private float relativeX = 0.05f;
    private float relativeY = 0.10f;

    public Overlay() {
        super("Overlay", category.bedwars);
        this.registerSetting(scale = new SliderSetting("Scale", "x", 1.0, 0.5, 1.5, 0.05));
        this.registerSetting(maxPlayers = new SliderSetting("Max Players", "", 12, 4, 24, 1));
        this.registerSetting(showTeammates = new ButtonSetting("Show Teammates", false));
    }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            this.api = new HypixelBedWars(apiKey);
        }
    }

    @Override
    public void onDisable() {
        cache.clear();
        pendingFetches.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.isEnabled() || !Utils.nullCheck()) return;

        if (Utils.getBedwarsStatus() != 2) {
            if (!cache.isEmpty()) cache.clear();
            return;
        }

        if (mc.getNetHandler() == null) return;

        // Fetch names directly from connection info (tablist) without sending chat commands
        Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
        for (NetworkPlayerInfo info : playerInfoMap) {
            if (info.getGameProfile() == null) continue;

            String name = info.getGameProfile().getName();
            if (name == null || name.isEmpty()) continue;

            // Ignore NPCs/Nicks or fake player profiles (version 2 UUIDs)
            if (info.getGameProfile().getId() != null && info.getGameProfile().getId().version() == 2) continue;

            if (name.equalsIgnoreCase(mc.thePlayer.getName()) && !showTeammates.isToggled()) continue;

            String lowerKey = name.toLowerCase();
            if (!cache.containsKey(lowerKey) && !pendingFetches.contains(lowerKey)) {
                fetchPlayerStats(name);
            }
        }
    }

    private void fetchPlayerStats(String username) {
        String key = username.toLowerCase();
        if (pendingFetches.contains(key) || api == null) return;

        pendingFetches.add(key);
        executor.submit(() -> {
            try {
                HypixelBedWars.BedwarsPlayer data = api.getStats(username);
                if (data != null) {
                    cache.put(key, data);
                }
            } catch (Exception ignored) {
                // Ignore nicks or failed requests
            } finally {
                pendingFetches.remove(key);
            }
        });
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;
        if (Utils.getBedwarsStatus() != 2) return;

        draw();
    }

    private void draw() {
        MindlessFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        List<String> lines = buildLines();
        if (lines.isEmpty()) return;

        float s = (float) scale.getInput();
        float widest = 0.0f;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringWidth(line));
        }

        float w = (widest + PAD_X * 2.0f) * s;
        float h = (PAD_Y * 2.0f + lines.size() * font.getFontHeight() + (lines.size() - 1) * LINE_GAP) * s;

        ScaledResolution resolution = ScaledResolutionCache.get();
        float left = relativeX * Math.max(1, resolution.getScaledWidth());
        float top = relativeY * Math.max(1, resolution.getScaledHeight());
        float radius = 7.0f * mindless.module.impl.theme.ThemeManager.roundingScale();

        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 5.0f, new Color(0, 0, 0, 120).getRGB());

        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 125));
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 18), new Color(255, 255, 255, 4));
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 28));

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;
        float textX = left * inv + PAD_X;
        float textY = top * inv + PAD_Y;

        for (String line : lines) {
            font.drawString(line, textX, textY, 0xFFFFFFFF, false);
            textY += font.getFontHeight() + LINE_GAP;
        }
        GlStateManager.popMatrix();
    }

    private List<String> buildLines() {
        List<String> lines = new ArrayList<>();
        lines.add(ChatFormatting.GOLD + "★ BedWars Players");

        if (api == null) {
            lines.add(ChatFormatting.RED + "API Key required");
            return lines;
        }

        List<HypixelBedWars.BedwarsPlayer> playerList = new ArrayList<>(cache.values());
        playerList.sort((a, b) -> Integer.compare(b.level.level, a.level.level));

        int limit = (int) maxPlayers.getInput();
        int count = 0;

        for (HypixelBedWars.BedwarsPlayer p : playerList) {
            if (count >= limit) break;

            String levelColor = getLevelColor(p.level.level);
            String fkdrColor = getFkdrColor(p.overall.finalKillDeathRatio);
            String wlrColor = getWlrColor(p.overall.winLossRatio);

            StringBuilder row = new StringBuilder();
            row.append(levelColor).append("[").append(p.level.level).append("★] ")
                    .append(ChatFormatting.RESET).append(p.displayName).append(" ")
                    .append(ChatFormatting.GRAY).append("FKDR: ").append(fkdrColor).append(p.overall.finalKillDeathRatio).append(" ")
                    .append(ChatFormatting.GRAY).append("WLR: ").append(wlrColor).append(p.overall.winLossRatio).append(" ")
                    .append(ChatFormatting.GRAY).append("W: ").append(ChatFormatting.GREEN).append(p.overall.wins).append(" ")
                    .append(ChatFormatting.GRAY).append("FK: ").append(ChatFormatting.DARK_GREEN).append(p.overall.finalKills);

            lines.add(row.toString());
            count++;
        }

        if (lines.size() == 1) {
            lines.add(ChatFormatting.GRAY + "Searching tablist...");
        }

        return lines;
    }

    private String getLevelColor(int level) {
        if (level >= 1000) return ChatFormatting.DARK_RED.toString();
        if (level >= 500) return ChatFormatting.DARK_AQUA.toString();
        if (level >= 400) return ChatFormatting.DARK_GREEN.toString();
        if (level >= 300) return ChatFormatting.AQUA.toString();
        if (level >= 200) return ChatFormatting.GOLD.toString();
        if (level >= 100) return ChatFormatting.WHITE.toString();
        return ChatFormatting.GRAY.toString();
    }

    private String getFkdrColor(double fkdr) {
        if (fkdr >= 10.0) return ChatFormatting.DARK_RED.toString();
        if (fkdr >= 5.0) return ChatFormatting.RED.toString();
        if (fkdr >= 2.0) return ChatFormatting.GOLD.toString();
        if (fkdr >= 1.0) return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }

    private String getWlrColor(double wlr) {
        if (wlr >= 4.0) return ChatFormatting.DARK_RED.toString();
        if (wlr >= 2.0) return ChatFormatting.RED.toString();
        if (wlr >= 1.0) return ChatFormatting.GOLD.toString();
        if (wlr >= 0.5) return ChatFormatting.YELLOW.toString();
        return ChatFormatting.WHITE.toString();
    }
}