package mindless.module.impl.bedwars;

import mindless.command.impl.Urchin;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import mindless.utility.BedwarsTeam;
import mindless.utility.HypixelLanguage;
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
public class BedTracker extends BedwarsHud {
private static final int SCAN_RADIUS = 36;
private static final int SCAN_HEIGHT = 18;
private static final int MAX_SCAN_ATTEMPTS = 10;
private static final long INITIAL_SCAN_DELAY_MS = 1200L;
private static final long RETRY_DELAY_MS = 1500L;
private static final long SETTLE_MS = 6000L;

    private final SliderSetting frequency;
    private final SliderSetting distance;
    private final ButtonSetting pingSound;
    private final ButtonSetting urchinAlerts;

    private final Map<UUID, Long> lastAlert = new HashMap<UUID, Long>();
    private final java.util.Set<String> urchinChecked = new java.util.HashSet<String>();
    private BlockPos bed;
    private BlockPos scanOrigin;
    private long scanAt;
    private long settledAt;
    private int scanAttempts;
    private boolean warnedOutOfRange;
    private int previousBedwarsStatus = -1;

    public BedTracker() {
        super("Bed Tracker", "Tracks which beds are still standing.", 0.02f, 0.34f);
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
        scanOrigin = null;
        scanAt = 0L;
        settledAt = 0L;
        scanAttempts = 0;
        warnedOutOfRange = false;
        lastAlert.clear();
        urchinChecked.clear();
        previousBedwarsStatus = -1;
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!this.isEnabled() || event.message == null) return;
        String message = Utils.stripColor(event.message.getUnformattedText());
        if (HypixelLanguage.contains(message, HypixelLanguage.Key.GAME_START_ONE)) {
            schedule(INITIAL_SCAN_DELAY_MS, true);
        }
        else if (HypixelLanguage.contains(message, HypixelLanguage.Key.RESPAWN_IN)) {
            // Respawning does not move the team's bed. Preserve a confirmed result and only
            // restart discovery if the initial scan never found one.
            if (bed == null) schedule(750L, false);
        }
        else if (HypixelLanguage.contains(message, HypixelLanguage.Key.TEAM_SWAP)) {
            schedule(750L, true);
        }
        else if (HypixelLanguage.contains(message, HypixelLanguage.Key.BED_DESTRUCTION)
                && HypixelLanguage.contains(message, HypixelLanguage.Key.YOUR_BED)) {
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

    private void schedule(long delay, boolean resetOrigin) {
        if (resetOrigin) {
            bed = null;
            scanOrigin = mc.thePlayer == null ? null : mc.thePlayer.getPosition();
            scanAttempts = 0;
        } else if (scanOrigin == null && mc.thePlayer != null) {
            scanOrigin = mc.thePlayer.getPosition();
        }
        long now = System.currentTimeMillis();
        scanAt = now + delay;
        settledAt = now + delay + SETTLE_MS;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || !Utils.nullCheck()) return;

        int status = Utils.getBedwarsStatus();
        if (status != 2) {
            if (bed != null || !lastAlert.isEmpty()) reset();
            previousBedwarsStatus = status;
            return;
        }
        if (previousBedwarsStatus != 2) schedule(INITIAL_SCAN_DELAY_MS, true);
        previousBedwarsStatus = status;

        long now = System.currentTimeMillis();
        if (bed == null && scanAt > 0L && now >= scanAt) {
            scanAttempts++;
            bed = findBed();
            if (bed != null) {
                scanAt = 0L;
                Utils.sendMessage("&a✓ &7Found your bed at &a"
                        + bed.getX() + "&7, &a" + bed.getY() + "&7, &a" + bed.getZ());
            } else if (scanAttempts < MAX_SCAN_ATTEMPTS) {
                scanAt = now + RETRY_DELAY_MS;
            } else {
                scanAt = 0L;
                Utils.sendMessage("&c⚠ &7Could not find your bed.");
            }
        }

        if (bed == null) return;

        if (mc.theWorld.getChunkProvider().chunkExists(bed.getX() >> 4, bed.getZ() >> 4)
                && !(mc.theWorld.getBlockState(bed).getBlock() instanceof BlockBed)) {
            bed = null;
            Utils.sendMessage("&4&l⚠ &cYour bed was destroyed.");
            return;
        }

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
            if (player == mc.thePlayer || Utils.isTeammate(player)
                    || BedwarsTeam.isSameColorTeam(mc.thePlayer, player)) continue;
            if (player.capabilities.isFlying) continue;
            if (player.ticksExisted < 100) continue;

            int away = (int) player.getDistance(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
            if (away > maxDistance) continue;

            UUID id = player.getUniqueID();
            Long previous = lastAlert.get(id);
            if (previous != null && now - previous < interval) continue;
            lastAlert.put(id, now);

            Utils.sendMessage(BedwarsTeam.label(player) + " &7is " + colourFor(away) + away
                    + " &7blocks from your bed.");
            if (pingSound.isToggled()) {
                mc.thePlayer.playSound("note.pling", 1.0f, 0.7f);
            }
        }
    }

    private BlockPos findBed() {
        BlockPos centre = scanOrigin != null ? scanOrigin : mc.thePlayer.getPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Search outward in rings. Bases normally resolve in the first few rings, avoiding the
        // old full cuboid walk (nearly 200k block lookups) and still retaining the wider fallback.
        for (int radius = 0; radius <= SCAN_RADIUS; radius++) {
            for (int x = -radius; x <= radius; x++) {
                BlockPos found = findBedAtColumn(centre, cursor, x, -radius);
                if (found != null) return found;
                if (radius > 0) {
                    found = findBedAtColumn(centre, cursor, x, radius);
                    if (found != null) return found;
                }
            }
            for (int z = -radius + 1; z < radius; z++) {
                BlockPos found = findBedAtColumn(centre, cursor, -radius, z);
                if (found != null) return found;
                if (radius > 0) {
                    found = findBedAtColumn(centre, cursor, radius, z);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private BlockPos findBedAtColumn(BlockPos centre, BlockPos.MutableBlockPos cursor,
                                     int offsetX, int offsetZ) {
        int worldX = centre.getX() + offsetX;
        int worldZ = centre.getZ() + offsetZ;
        if (!mc.theWorld.getChunkProvider().chunkExists(worldX >> 4, worldZ >> 4)) return null;
        for (int distanceY = 0; distanceY <= SCAN_HEIGHT; distanceY++) {
            BlockPos found = bedAt(centre, cursor, worldX, worldZ, distanceY);
            if (found != null) return found;
            if (distanceY > 0) {
                found = bedAt(centre, cursor, worldX, worldZ, -distanceY);
                if (found != null) return found;
            }
        }
        return null;
    }

    private BlockPos bedAt(BlockPos centre, BlockPos.MutableBlockPos cursor,
                           int worldX, int worldZ, int offsetY) {
        int worldY = centre.getY() + offsetY;
        if (worldY < 0 || worldY > 255) return null;
        cursor.set(worldX, worldY, worldZ);
        return mc.theWorld.getBlockState(cursor).getBlock() instanceof BlockBed
                ? new BlockPos(cursor) : null;
    }

    private int distanceToBed() {
        if (bed == null || mc.thePlayer == null) return 0;
        return (int) mc.thePlayer.getDistance(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
    }
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
