package mindless.module.impl.other;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.HypixelPresence;
import mindless.utility.Utils;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;


public class DiscordRPC extends Module {
    /** Art uploaded to the Discord application under this name. Always present. */
    private static final String DEFAULT_IMAGE = "large_image";
    /** The scoreboard is rescanned this often. It does not change fast enough to want more. */
    private static final int SCRAPE_INTERVAL_TICKS = 20;
    /**
     * How often the presence is rebuilt.
     *
     * <p>Three hooks call into the update, so without this the templates were being expanded
     * something like sixty times a second to produce a string almost always identical to the last
     * one. Discord's own limit is far lower than that and the transport enforces it; twice a
     * second here is only so a change is noticed promptly.
     */
    private static final long BUILD_INTERVAL_MS = 500L;

    public static ButtonSetting showServer;

    private final ButtonSetting hypixelStats;
    private final ButtonSetting forceHypixel;
    private final ButtonSetting useLocraw;
    private final ButtonSetting gameIcons;
    private final ButtonSetting timeElapsed;
    private final ButtonSetting showParty;
    private final ButtonSetting soloTeamsLeft;

    private final GroupSetting gameGroup;
    private final TextSetting detail;
    private final TextSetting state;
    private final TextSetting imageText;

    private final GroupSetting lobbyGroup;
    private final TextSetting detailLobby;
    private final TextSetting stateLobby;
    private final TextSetting imageTextLobby;

    private final GroupSetting skyblockGroup;
    private final TextSetting detailSb;
    private final TextSetting stateSb;
    private final TextSetting imageTextSb;

    private mindless.utility.DiscordRPC rpc;
    private long startTimestamp;
    private long lastBuildAt;
    private int tickCounter;

    public DiscordRPC() {
        super("Discord RPC", Module.category.other);
        this.registerSetting(showServer = new ButtonSetting("Show Server", true));
        this.registerSetting(hypixelStats = new ButtonSetting("Hypixel Stats", true));
        this.registerSetting(forceHypixel = new ButtonSetting("Force Hypixel", false));
        this.registerSetting(useLocraw = new ButtonSetting("Use Locraw", true));
        this.registerSetting(gameIcons = new ButtonSetting("Game Icons", false));
        this.registerSetting(timeElapsed = new ButtonSetting("Time Elapsed", true));
        this.registerSetting(showParty = new ButtonSetting("Show Party", true));
        this.registerSetting(soloTeamsLeft = new ButtonSetting("Solo Teams Left", false));

        this.registerSetting(gameGroup = new GroupSetting("In game"));
        this.registerSetting(detail = new TextSetting(gameGroup, "Detail", "{game} - {mode}", "{game} - {mode}", 100));
        this.registerSetting(state = new TextSetting(gameGroup, "State", "{map}", "{map}", 100));
        this.registerSetting(imageText = new TextSetting(gameGroup, "Image Text", "{players} players", "{players} players", 100));

        this.registerSetting(lobbyGroup = new GroupSetting("In lobby"));
        this.registerSetting(detailLobby = new TextSetting(lobbyGroup, "Detail", "{game} Lobby", "{game} Lobby", 100));
        this.registerSetting(stateLobby = new TextSetting(lobbyGroup, "State", "{players} players", "{players} players", 100));
        this.registerSetting(imageTextLobby = new TextSetting(lobbyGroup, "Image Text", "{server}", "{server}", 100));

        this.registerSetting(skyblockGroup = new GroupSetting("Skyblock"));
        this.registerSetting(detailSb = new TextSetting(skyblockGroup, "Detail", "SkyBlock - {map}", "SkyBlock - {map}", 100));
        this.registerSetting(stateSb = new TextSetting(skyblockGroup, "State", "{date} {time}", "{date} {time}", 100));
        this.registerSetting(imageTextSb = new TextSetting(skyblockGroup, "Image Text", "Purse: {coins}", "Purse: {coins}", 100));
    }

    @Override
    public void onEnable() {
        startTimestamp = System.currentTimeMillis() / 1000L;
        if (rpc == null) {
            rpc = new mindless.utility.DiscordRPC("1541533225237749760");
            rpc.connect();
        }
        lastBuildAt = 0L;
        HypixelPresence.reset();
        updatePresence();
    }

    @Override
    public void onDisable() {
        if (rpc != null) {
            rpc.close();
            rpc = null;
        }
    }

    @Override
    public void onUpdate() {
        updatePresence();
    }

    @Override
    public void guiUpdate() {
        updatePresence();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // The scrape walks the whole sidebar, so it runs on a timer rather than every tick.
        if (scrapeHypixel() && ++tickCounter % SCRAPE_INTERVAL_TICKS == 0) {
            HypixelPresence.tick(useLocraw.isToggled());
        }
        updatePresence();
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        HypixelPresence.reset();
        startTimestamp = System.currentTimeMillis() / 1000L;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        HypixelPresence.reset();
        HypixelPresence.resetParty();
    }

    /**
     * Chat is watched for party messages and for the locraw reply.
     *
     * <p>Cancelled messages are still delivered, since a chat filter elsewhere hiding a party
     * message would otherwise leave the count wrong for the rest of the session.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type != 0 || event.message == null || !scrapeHypixel()) {
            return;
        }
        if (HypixelPresence.onChat(event.message.getFormattedText(), event.message.getUnformattedText())) {
            // Our own locraw reply. We asked for it, so the player should not have to read it.
            event.setCanceled(true);
        }
    }

    /**
     * Whether to read the scoreboard as a Hypixel one.
     *
     * <p>Force Hypixel exists for proxies. Detection reads the server address, and failing that
     * the brand the server introduced itself with; a proxy replaces the address with its own and
     * some replace the brand too, at which point nothing is left to detect and the only honest
     * answer is to let the player say so.
     */
    private boolean scrapeHypixel() {
        return hypixelStats.isToggled() && (forceHypixel.isToggled() || Utils.isHypixel());
    }

    /** Live state next to the module name, so a presence that is not showing says why. */
    @Override
    public String getInfo() {
        if (rpc == null) {
            return "";
        }
        if (!rpc.isConnected()) {
            return "no discord";
        }
        if (!hypixelStats.isToggled()) {
            return "server only";
        }
        if (!forceHypixel.isToggled() && !Utils.isHypixel()) {
            return "not hypixel";
        }
        String game = HypixelPresence.getGame();
        String mode = HypixelPresence.getMode();
        if (game.isEmpty()) {
            return "reading";
        }
        return mode.isEmpty() ? game : game + " - " + mode;
    }

    /** Everything the diagnostic command reports. Keep it cheap; it runs on demand only. */
    public mindless.utility.DiscordRPC getTransport() {
        return rpc;
    }

    public boolean wantsHypixelStats() {
        return hypixelStats.isToggled();
    }

    public boolean isForcingHypixel() {
        return forceHypixel.isToggled();
    }

    public boolean wantsSoloTeamsLeft() {
        return soloTeamsLeft.isToggled();
    }

    private void updatePresence() {
        if (rpc == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBuildAt < BUILD_INTERVAL_MS) {
            return;
        }
        lastBuildAt = now;

        mindless.utility.DiscordRPC.RichPresence presence = new mindless.utility.DiscordRPC.RichPresence();

        if (timeElapsed.isToggled()) {
            presence.startTimestamp(scrapeHypixel() ? HypixelPresence.getStartedAt() : startTimestamp);
        }

        if (scrapeHypixel()) {
            buildHypixelPresence(presence);
        }
        else {
            buildGenericPresence(presence);
        }

        // Handed over unconditionally. The transport decides what is worth sending and keeps this
        // as the target until it lands, so a send lost to a missing Discord or a spent rate limit
        // budget is retried rather than skipped because we already recorded it as sent.
        rpc.update(presence);
    }

    private void buildHypixelPresence(mindless.utility.DiscordRPC.RichPresence presence) {
        TextSetting detailFor;
        TextSetting stateFor;
        TextSetting imageTextFor;

        if (HypixelPresence.isSkyblock()) {
            detailFor = detailSb;
            stateFor = stateSb;
            imageTextFor = imageTextSb;
        }
        else if (HypixelPresence.inLobby()) {
            detailFor = detailLobby;
            stateFor = stateLobby;
            imageTextFor = imageTextLobby;
        }
        else {
            detailFor = detail;
            stateFor = state;
            imageTextFor = imageText;
        }

        String detailsLine = HypixelPresence.format(detailFor.getText());
        String stateLine = HypixelPresence.format(stateFor.getText());
        String imageLine = HypixelPresence.format(imageTextFor.getText());

        if (!detailsLine.isEmpty()) {
            presence.details(detailsLine);
        }
        if (!stateLine.isEmpty()) {
            presence.state(stateLine);
        }

        // A missing art key shows as a blank square rather than falling back, so the per-game key
        // is only used when the player has said their Discord application actually has that art.
        String icon = gameIcons.isToggled() ? HypixelPresence.iconKey() : "";
        presence.largeImage(icon.isEmpty() ? DEFAULT_IMAGE : icon,
                imageLine.isEmpty() ? "Mindless" : imageLine);

        // Discord draws this as "(1 of 2)" beside the state, and the number it counts against has
        // to be the team, not a setting. A fixed cap made Solo read "1 of 10", which is wrong
        // twice: there is no party of one, and there is no tenth slot. Doubles is two, 3v3v3v3 is
        // three, 4v4 is four, and a mode with no team to speak of gets no party field at all.
        int teamSize = HypixelPresence.getTeamSize();
        if (showParty.isToggled() && teamSize > 1) {
            presence.party(Math.min(HypixelPresence.getPartyMembers(), teamSize), teamSize);
        }
        else if (soloTeamsLeft.isToggled() && teamSize == 0) {
            // A solo game has no teammates, so the bracket counts the lobby down instead: you are
            // one of however many teams are still alive. It shrinks as the game is won, which is
            // the only number in a solo worth putting there.
            int remaining = HypixelPresence.getTeamsRemaining();
            if (remaining > 1) {
                presence.party(1, remaining);
            }
        }
    }

    private void buildGenericPresence(mindless.utility.DiscordRPC.RichPresence presence) {
        presence.largeImage(DEFAULT_IMAGE, "Mindless");

        if (!showServer.isToggled()) {
            return;
        }

        String line;
        if (mc.theWorld == null || mc.currentScreen instanceof GuiMainMenu) {
            line = "In menu";
        }
        else if (mc.isSingleplayer()) {
            line = "Singleplayer";
        }
        else {
            ServerData serverData = mc.getCurrentServerData();
            line = serverData != null && serverData.serverIP != null
                    ? "Playing on " + serverData.serverIP
                    : "Playing on multiplayer";
        }
        presence.details(line);
    }
}
