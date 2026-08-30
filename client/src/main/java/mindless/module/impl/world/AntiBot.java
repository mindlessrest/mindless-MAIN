package mindless.module.impl.world;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.player.Freecam;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Works out whether an entity is a real player, on behalf of Target Filter.
 *
 * <p>Not a module of its own any more. Every consumer of this already had to keep Anti Bot and
 * Target Filter both switched on for either to do anything, because Target Filter's bot check
 * delegates here and this used to refuse to answer unless its own module was enabled. Two
 * switches for one behaviour is a trap, and the one people fell into was ticking the box in
 * Target Filter and getting nothing. There is one switch now, and it lives with the other target
 * filtering where it belongs.
 */
public final class AntiBot {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final HashMap<EntityPlayer, Long> entities = new HashMap();
    private static SliderSetting delay;
    private static SliderSetting pitSpawn;
    private static ButtonSetting tablist;
    private static ButtonSetting npcChecks;
    private static ButtonSetting printWorldJoin;
    private static final Set<String> tablistCache = new HashSet<>();
    private static final Set<UUID> tablistUuidCache = new HashSet<>();
    private static final Set<Integer> npcEntityIdCache = new HashSet<>();
    private static final Set<Long> shopPositionCache = new HashSet<>();
    // Slinky-style checks: track players that were invisible at spawn or never moved
    private static final Set<Integer> spawnedInvisible = new HashSet<>();
    private static final HashMap<Integer, double[]> spawnPositions = new HashMap<>();
    private static long tablistCacheTime;
    private static long npcCacheTime;
    private static World npcCacheWorld;

    private AntiBot() {}

    /** Hangs the detection settings off whichever module owns this. */
    public static void registerSettings(Module owner) {
        owner.registerSetting(new DescriptionSetting("Anti Bot"));
        owner.registerSetting(delay = new SliderSetting("Delay", " second", true, -1, 0.5, 15.0, 0.5));
        owner.registerSetting(pitSpawn = new SliderSetting("Pit spawn", true, -1, 70, 120, 1));
        owner.registerSetting(tablist = new ButtonSetting("Tab list", false));
        owner.registerSetting(npcChecks = new ButtonSetting("NPC checks", true));
        owner.registerSetting(printWorldJoin = new ButtonSetting("Print world join", false));
    }

    public static void onEntityJoin(EntityJoinWorldEvent e) {
        if ((e.entity instanceof EntityPlayer || Mindless.DEBUG) && e.entity != mc.thePlayer) {
            if (delay.getInput() != -1 && e.entity instanceof EntityPlayer) {
                entities.put((EntityPlayer) e.entity, System.currentTimeMillis());
            }
            if (e.entity instanceof EntityPlayer) {
                EntityPlayer p = (EntityPlayer) e.entity;
                int id = p.getEntityId();
                // Always invisible check: record if invisible at spawn
                if (p.isInvisible()) {
                    spawnedInvisible.add(id);
                }
                // Always stationary check: record spawn position
                spawnPositions.put(id, new double[]{ p.posX, p.posZ });
            }
            if (printWorldJoin.isToggled()) {
                Utils.sendMessage("&7Entity &b" + e.entity.getEntityId() + " &7joined: &r" + e.entity.getDisplayName().getFormattedText());
            }
        }
    }

    public static void onUpdate() {
        refreshNpcCache();
        if (delay.getInput() != -1 && !entities.isEmpty()) {
            long delayMillis = (long) (delay.getInput() * 1000.0);
            long cutoff = System.currentTimeMillis() - delayMillis;
            entities.values().removeIf(n -> n < cutoff);
        }
    }

    /** Drops everything learned about the world we were in. */
    public static void clear() {
        entities.clear();
        tablistCache.clear();
        tablistUuidCache.clear();
        npcEntityIdCache.clear();
        shopPositionCache.clear();
        spawnedInvisible.clear();
        spawnPositions.clear();
        tablistCacheTime = 0L;
        npcCacheTime = 0L;
        npcCacheWorld = null;
    }

    public static boolean isBot(Entity entity) {
        if (!TargetFilter.isAntiBotActive()) {
            return false;
        }
        if (Freecam.freeEntity != null && Freecam.freeEntity == entity) {
            return true;
        }
        if (entity == null || !(entity instanceof EntityPlayer)) {
            return true;
        }
        final EntityPlayer entityPlayer = (EntityPlayer) entity;
        if (delay.getInput() != -1 && !entities.isEmpty() && entities.containsKey(entityPlayer)) {
            return true;
        }
        if (entityPlayer.isDead) {
            return true;
        }
        String profileName = entityPlayer.getGameProfile() == null
                ? null : entityPlayer.getGameProfile().getName();
        if (!isValidMinecraftProfileName(profileName)) {
            return true;
        }
        if (npcChecks.isToggled()) {
            int entityId = entityPlayer.getEntityId();
            if (entityId < 0 || entityId >= 1_000_000_000) {
                return true;
            }
            if (entityPlayer.rotationPitch < -90.0F || entityPlayer.rotationPitch > 90.0F) {
                return true;
            }
            UUID uuid = entityPlayer.getUniqueID();
            if (uuid != null && uuid.version() == 2) {
                return true;
            }
            if (npcEntityIdCache.contains(entityId)) {
                return true;
            }
            if (shopPositionCache.contains(shopPosHash(entityPlayer.posX, entityPlayer.posZ))) {
                return true;
            }
            // Sleeping check (Slinky): bots/NPCs are sometimes put into sleep state
            if (entityPlayer.isPlayerSleeping()) {
                return true;
            }
            // Always invisible check (Slinky): invisible since first spawn → bot
            if (spawnedInvisible.contains(entityId) && entityPlayer.isInvisible()) {
                return true;
            }
            // Entity age check (Slinky): spawned within last 2 ticks → likely bot spawn packet
            if (entityPlayer.ticksExisted <= 2) {
                return true;
            }
            // Always stationary check (Slinky): never moved horizontally since spawn
            // Only applied when UUID is also invalid to avoid false positives on AFK players
            double[] spawnPos = spawnPositions.get(entityId);
            if (spawnPos != null && entityPlayer.ticksExisted > 60) {
                double dx = entityPlayer.posX - spawnPos[0];
                double dz = entityPlayer.posZ - spawnPos[1];
                boolean neverMoved = (dx * dx + dz * dz) < 0.0001;
                if (neverMoved) {
                    // Invalid UUID check (Slinky): applied to stationary players
                    if (uuid != null && uuid.version() == 2) {
                        return true;
                    }
                }
            }
        }
        // A player's world entity can arrive before its tab entry. Give the
        // client two seconds to synchronize, and fail open when the server's
        // tab list is unavailable instead of hiding a real player forever.
        if (tablist.isToggled() && entityPlayer.ticksExisted > 40
                && hasUsableTablist() && !isInTablist(entityPlayer)) {
            return true;
        }
        // Do not use response time as bot evidence. Hypixel and proxy-backed
        // servers can legitimately expose 0-1 ms for an entire lobby, which
        // previously made ESP and combat modules drop real players after the
        // three-second grace period. UUID/name membership above is the stable
        // tab-list signal; uncertain cases deliberately fail open.
        if (pitSpawn.getInput() != -1 && entityPlayer.posY >= pitSpawn.getInput() && entityPlayer.posY <= 130 && entityPlayer.getDistance(0, 114, 0) <= 25) {
            if (Utils.isHypixel()) {
                List<String> sidebarLines = Utils.getSidebarLines();
                if (!sidebarLines.isEmpty() && Utils.stripColor(sidebarLines.get(0)).contains("THE HYPIXEL PIT")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Detects shop-style NPCs from their aligned hologram labels. The scan is
     * intentionally tick-cached: AntiBot is queried from several combat and
     * render modules, so walking loadedEntityList inside isBot would be costly.
     */
    private static void refreshNpcCache() {
        if (!npcChecks.isToggled() || mc.theWorld == null || mc.thePlayer == null) {
            npcEntityIdCache.clear();
            npcCacheWorld = mc.theWorld;
            npcCacheTime = 0L;
            return;
        }

        if (npcCacheWorld != mc.theWorld) {
            npcEntityIdCache.clear();
            shopPositionCache.clear();
            spawnedInvisible.clear();
            spawnPositions.clear();
            npcCacheWorld = mc.theWorld;
            npcCacheTime = 0L;
        }

        long now = System.currentTimeMillis();
        if (now - npcCacheTime < 500L) {
            return;
        }
        npcCacheTime = now;
        npcEntityIdCache.clear();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer) continue;
            if (hasExplicitNpcName(player)) {
                npcEntityIdCache.add(player.getEntityId());
            }
        }

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityArmorStand) || !hasShopLabel(entity)) continue;
            // Persist the shop position — survives chunk unloads and works
            // for NPCs whose hologram chunk isn't loaded yet (distance fix).
            shopPositionCache.add(shopPosHash(entity.posX, entity.posZ));
            EntityPlayer alignedPlayer = findAlignedNpcPlayer((EntityArmorStand) entity);
            if (alignedPlayer != null) {
                npcEntityIdCache.add(alignedPlayer.getEntityId());
            }
        }
    }

    /** Packs block-precision XZ into a long for O(1) lookup. */
    private static long shopPosHash(double x, double z) {
        return ((long) (int) Math.floor(x) & 0xFFFFFFFFL) << 32
             | ((long) (int) Math.floor(z) & 0xFFFFFFFFL);
    }

    private static EntityPlayer findAlignedNpcPlayer(EntityArmorStand label) {
        // NPC holograms share almost the exact X/Z coordinate with the entity.
        // A narrow horizontal pad prevents a real player merely standing near
        // a shop from being classified as that shopkeeper.
        AxisAlignedBB searchBox = label.getEntityBoundingBox().expand(0.45D, 3.5D, 0.45D);
        EntityPlayer closest = null;
        double closestHorizontalSq = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || player.isDead
                    || !player.getEntityBoundingBox().intersectsWith(searchBox)) {
                continue;
            }
            double dx = player.posX - label.posX;
            double dz = player.posZ - label.posZ;
            double horizontalSq = dx * dx + dz * dz;
            if (horizontalSq <= 0.2025D && horizontalSq < closestHorizontalSq) {
                closest = player;
                closestHorizontalSq = horizontalSq;
            }
        }
        return closest;
    }

    private static boolean hasExplicitNpcName(EntityPlayer player) {
        String display = normalizeDisplayName(player.getDisplayName() == null
                ? "" : player.getDisplayName().getUnformattedText());
        return display.equals("NPC") || display.startsWith("[NPC]")
                || display.startsWith("CIT-") || hasShopMarker(display);
    }

    private static boolean hasShopLabel(Entity entity) {
        String display = normalizeDisplayName(entity.getDisplayName() == null
                ? "" : entity.getDisplayName().getUnformattedText());
        return hasShopMarker(display);
    }

    private static boolean hasShopMarker(String display) {
        return display.contains("RIGHT CLICK")
                || display.contains("ITEM SHOP")
                || display.contains("TEAM UPGRADES")
                || display.contains("SOLO UPGRADES")
                || display.equals("UPGRADES")
                || display.contains("BANKER")
                || display.contains("STREAK POWERS");
    }

    private static String normalizeDisplayName(String display) {
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(display);
        return stripped == null ? "" : stripped.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isValidMinecraftProfileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static void refreshTablistCache() {
        long now = System.currentTimeMillis();
        if (now - tablistCacheTime >= 750L) {
            tablistCache.clear();
            tablistUuidCache.clear();
            for (NetworkPlayerInfo playerInfo : Utils.getTablist(true)) {
                if (playerInfo == null || playerInfo.getGameProfile() == null) continue;
                String name = playerInfo.getGameProfile().getName();
                UUID id = playerInfo.getGameProfile().getId();
                if (name != null && !name.isEmpty()) tablistCache.add(name.toLowerCase());
                if (id != null) tablistUuidCache.add(id);
            }
            tablistCacheTime = now;
        }
    }

    private static boolean hasUsableTablist() {
        refreshTablistCache();
        return !tablistCache.isEmpty() || !tablistUuidCache.isEmpty();
    }

    private static boolean isInTablist(EntityPlayer player) {
        refreshTablistCache();
        UUID id = player.getUniqueID();
        if (id != null && tablistUuidCache.contains(id)) return true;
        String name = player.getName();
        return name != null && tablistCache.contains(name.toLowerCase());
    }
}
