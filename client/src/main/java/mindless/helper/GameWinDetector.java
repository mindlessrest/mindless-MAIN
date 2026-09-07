package mindless.helper;

import mindless.event.GameWinEvent;
import mindless.event.ReceivePacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import mindless.utility.HypixelLanguage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameWinDetector {
    private static final long COOLDOWN_MS = 20_000L;
    private static final Pattern RANK_TAG = Pattern.compile("\\[[^\\]]*\\]\\s*");
    private static final Pattern DUEL_WINNER = Pattern.compile("^Winner: ([A-Za-z0-9_]{1,16})\\b");

    private long lastWinMs;

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || Minecraft.getMinecraft().thePlayer == null) return;

        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (raw == null) return;
        String line = RANK_TAG.matcher(raw).replaceAll("").trim();
        if (line.isEmpty()) return;

        Matcher duel = DUEL_WINNER.matcher(line);
        if (duel.find()) {
            if (isMe(duel.group(1))) fireWin();
            return;
        }
        if (HypixelLanguage.contains(line, HypixelLanguage.Key.VICTORY)
                || HypixelLanguage.contains(line, HypixelLanguage.Key.WIN)) {
            fireWin();
        }
    }

    @SubscribeEvent
    public void onPacket(ReceivePacketEvent event) {
        if (!(event.getPacket() instanceof S45PacketTitle)) return;
        S45PacketTitle packet = (S45PacketTitle) event.getPacket();
        if (packet.getType() != S45PacketTitle.Type.TITLE) return;
        if (packet.getMessage() == null) return;
        String text = EnumChatFormatting.getTextWithoutFormattingCodes(packet.getMessage().getUnformattedText());
        if (text == null) return;
        if (HypixelLanguage.contains(text, HypixelLanguage.Key.WIN)
                || HypixelLanguage.contains(text, HypixelLanguage.Key.VICTORY)) {
            fireWin();
        }
    }

    private void fireWin() {
        long now = System.currentTimeMillis();
        if (now - lastWinMs < COOLDOWN_MS) return;
        lastWinMs = now;
        MinecraftForge.EVENT_BUS.post(new GameWinEvent());
    }

    private boolean isMe(String name) {
        if (name == null || Minecraft.getMinecraft().thePlayer == null) return false;
        return name.equalsIgnoreCase(Minecraft.getMinecraft().thePlayer.getName());
    }
}
