package mindless.module.impl.bedwars;

import mindless.command.impl.Urchin;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.block.BlockBed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds your bed and tells you when someone is walking towards it.
 *
 * <p>The bed is the game. Losing it while you are three islands away, with nobody having said a
 * word, is how most games are actually lost -- so this locates it once at spawn and then watches
 * who gets close.
 *
 * <p>The find is deliberately delayed. At the moment the game starts the island is often not
 * loaded yet, and a scan against empty chunks finds nothing and gives up. Waiting a few seconds
 * costs nothing and is the difference between finding the bed and reporting that you have none.
 */
public class BedTracker extends BedwarsHud {
    /** Half-width of the scan box. A bedwars island fits inside this comfortably. */
    private static final int SCAN_RADIUS = 25;
    /** Time after the trigger line before the world is worth scanning. */
    private static final long SCAN_DELAY_MS = 6000L;
    /** Nobody is near your bed in the first seconds of a game; alerting then is just noise. */
    private static final long SETTLE_MS = 6000L;

    private final SliderSetting frequency;
    private final SliderSetting distance;
    private final ButtonSetting pingSound;
    private final ButtonSetting urchinAlerts;

    private final Map<UUID, Long> lastAlert = new HashMap<UUID, Long>();
    private final java.util.Set<String> urchinChecked = new java.util.HashSet<String>();
    private BlockPos bed;
    private long scanAt;
    private long settledAt;
    private boolean warnedOutOfRange;

    public BedTracker() {
        super("Bed Tracker", 0.02f, 0.34f);
        this.registerSetting(frequency = new SliderSetting("Alert interval", " second", 10, 5, 30, 1));
        this.registerSetting(distance = new SliderSetting("Max distance", " block", 50, 10, 100, 5));
        this.registerSetting(pingSound = new ButtonSetting("Ping sound", true));
        this.registerSetting(urchinAlerts = new ButtonSetting("Urchin alerts", true));
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        bed = null;
        scanAt = 0L;
        settledAt = 0L;
        warnedOutOfRange = false;
        lastAlert.clear();
        urchinChecked.clear();
    }

    // ------------------------------------------------------------------ chat triggers

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!this.isEnabled() || event.message == null) return;
        String message = Utils.stripColor(event.message.getUnformattedText());
        // Player chat carries a colon and can contain anything; only server lines are trusted.
        if (message.contains(":")) return;

        if (message.contains("The game starts in 1 second")) {
            schedule(SCAN_DELAY_MS);
        }
        else if (message.startsWith("You will respawn in")) {
            // Respawning puts you back on your island, which is a chance to find a bed the
            // opening scan missed.
            schedule(SCAN_DELAY_MS + 3000L);
        }
        else if (message.contains("Your team swapped and you are now")) {
            schedule(1000L);
        }
        else if (message.contains("BED DESTRUCTION") && message.contains("Your Bed")) {
            bed = null;
            Utils.sendMessage("&4&l⚠ &cYour bed was destroyed.");
        }

        if (urchinAlerts.isToggled() && Urchin.hasKey() && message.startsWith("ONLINE: ")) {
            String[] players = message.substring(8).split(", ");
            for (String player : players) {
                String trimmed = player.trim();
                if (trimmed.isEmpty() || trimmed.equals(mc.thePlayer.getName())) continue;
                if (urchinChecked.contains(trimmed.toLowerCase())) continue;
                urchinChecked.add(trimmed.toLowerCase());
                final String name = trimmed;
                new Thread(() -> {
                    String result = Urchin.fetchTags(name);
                    if (result != null && result.contains("tagged on")) {
                        Utils.sendMessage(result);
                        if (pingSound.isToggled()) {
                            mc.thePlayer.playSound("note.pling", 1.0f, 0.5f);
                        }
                    }
                }, "Urchin-" + name).start();
            }
        }
    }

    private void schedule(long delay) {
        bed = null;
        long now = System.currentTimeMillis();
        scanAt = now + delay;
        settledAt = now + delay + SETTLE_MS;
    }

    // ------------------------------------------------------------------ tracking

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || !Utils.nullCheck()) return;

        if (Utils.getBedwarsStatus() != 2) {
            if (bed != null || !lastAlert.isEmpty()) reset();
            return;
        }

        long now = System.currentTimeMillis();
        if (bed == null && scanAt > 0L && now >= scanAt) {
            scanAt = 0L;
            bed = findBed();
            if (bed != null) {
                Utils.sendMessage("&a✓ &7Found your bed at &a"
                        + bed.getX() + "&7, &a" + bed.getY() + "&7, &a" + bed.getZ());
            } else {
                Utils.sendMessage("&c⚠ &7Could not find your bed.");
            }
        }

        if (bed == null) return;

        boolean outOfRange = isOutOfRange();
        if (outOfRange && !warnedOutOfRange) {
            Utils.sendMessage("&d⚠ &7Your bed is out of render range.");
            warnedOutOfRange = true;
        } else if (!outOfRange) {
            warnedOutOfRange = false;
        }

        if (now < settledAt) return;
        alertNearbyEnemies(now);
    }

    private void alertNearbyEnemies(long now) {
        long interval = (long) frequency.getInput() * 1000L;
        int maxDistance = (int) distance.getInput();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || Utils.isTeammate(player)) continue;
            if (player.capabilities.isFlying) continue;
            // Freshly spawned entities are still being placed by the server and read as being
            // wherever the packet put them first, which is often your island.
            if (player.ticksExisted < 100) continue;

            int away = (int) player.getDistance(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
            if (away > maxDistance) continue;

            UUID id = player.getUniqueID();
            Long previous = lastAlert.get(id);
            if (previous != null && now - previous < interval) continue;
            lastAlert.put(id, now);

            Utils.sendMessage("&b" + player.getName() + " &7is " + colourFor(away) + away
                    + " &7blocks from your bed.");
            if (pingSound.isToggled()) {
                mc.thePlayer.playSound("note.pling", 1.0f, 0.7f);
            }
        }
    }

    private BlockPos findBed() {
        BlockPos centre = mc.thePlayer.getPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    cursor.set(centre.getX() + x, centre.getY() + y, centre.getZ() + z);
                    if (cursor.getY() < 0 || cursor.getY() > 255) continue;
                    if (!(mc.theWorld.getBlockState(cursor).getBlock() instanceof BlockBed)) continue;
                    return new BlockPos(cursor);
                }
            }
        }
        return null;
    }

    private int distanceToBed() {
        if (bed == null || mc.thePlayer == null) return 0;
        return (int) mc.thePlayer.getDistance(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
    }

    /**
     * Whether the bed is somewhere the client can no longer see.
     *
     * <p>Both halves matter. The server can drop the chunk, and the client can be further away
     * than its own render distance -- in either case the blocks are gone locally, so an ESP or a
     * distance readout is reporting a memory rather than the world.
     */
    private boolean isOutOfRange() {
        if (bed == null || !Utils.nullCheck()) return true;
        if (!mc.theWorld.getChunkProvider().chunkExists(bed.getX() >> 4, bed.getZ() >> 4)) return true;
        double dx = mc.thePlayer.posX - bed.getX();
        double dz = mc.thePlayer.posZ - bed.getZ();
        return Math.sqrt(dx * dx + dz * dz) > mc.gameSettings.renderDistanceChunks * 16;
    }

    private String colourFor(int away) {
        if (away <= 5) return "&4";
        if (away <= 15) return "&c";
        if (away <= 30) return "&6";
        if (away <= 40) return "&e";
        return "&a";
    }

    // ------------------------------------------------------------------ hud

    @Override
    protected boolean shouldDraw() {
        return Utils.getBedwarsStatus() == 2;
    }

    @Override
    protected List<String> lines() {
        List<String> out = new ArrayList<String>(1);
        if (bed == null) {
            out.add("§7Bed: §c✗");
            return out;
        }
        int away = distanceToBed();
        String colour = away < 70 ? "§a" : isOutOfRange() ? "§c" : "§e";
        out.add("§7Bed: §a✓ §8| §7" + colour + away + "§7m"
                + (isOutOfRange() ? " §c⚠" : ""));
        return out;
    }
}
