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
    private static final Set<Integer> spawnedInvisible = new HashSet<>();
    private static final HashMap<Integer, double[]> spawnPositions = new HashMap<>();
    private static long tablistCacheTime;
    private static long npcCacheTime;
    private static World npcCacheWorld;

    private AntiBot() {}
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
                if (p.isInvisible()) {
                    spawnedInvisible.add(id);
                }
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
            if (entityPlayer.isPlayerSleeping()) {
                return true;
            }
            if (spawnedInvisible.contains(entityId) && entityPlayer.isInvisible()) {
                return true;
            }
            if (entityPlayer.ticksExisted <= 2) {
                return true;
            }
            double[] spawnPos = spawnPositions.get(entityId);
            if (spawnPos != null && entityPlayer.ticksExisted > 60) {
                double dx = entityPlayer.posX - spawnPos[0];
                double dz = entityPlayer.posZ - spawnPos[1];
                boolean neverMoved = (dx * dx + dz * dz) < 0.0001;
                if (neverMoved) {
                    if (uuid != null && uuid.version() == 2) {
                        return true;
                    }
                }
            }
        }
        if (tablist.isToggled() && entityPlayer.ticksExisted > 40
                && hasUsableTablist() && !isInTablist(entityPlayer)) {
            return true;
        }
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
            shopPositionCache.add(shopPosHash(entity.posX, entity.posZ));
            EntityPlayer alignedPlayer = findAlignedNpcPlayer((EntityArmorStand) entity);
            if (alignedPlayer != null) {
                npcEntityIdCache.add(alignedPlayer.getEntityId());
            }
        }
    }
private static long shopPosHash(double x, double z) {
        return ((long) (int) Math.floor(x) & 0xFFFFFFFFL) << 32
             | ((long) (int) Math.floor(z) & 0xFFFFFFFFL);
    }

    private static EntityPlayer findAlignedNpcPlayer(EntityArmorStand label) {
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

    /**
     * Reads the stand's own name tag rather than going through getDisplayName.
     *
     * A lobby holds hundreds of label stands and this runs over all of them twice a second.
     * getDisplayName builds a fresh chat component every call, and normalizing it ran a regex
     * strip plus a trim plus an uppercase copy on top -- five throwaway objects per stand per
     * pass, for a check that is almost always false.
     */
    private static boolean hasShopLabel(Entity entity) {
        String raw = entity.getCustomNameTag();
        return raw != null && !raw.isEmpty() && hasShopMarker(raw);
    }

    private static boolean hasShopMarker(String display) {
        return containsMarker(display, "RIGHT CLICK")
                || containsMarker(display, "ITEM SHOP")
                || containsMarker(display, "TEAM UPGRADES")
                || containsMarker(display, "SOLO UPGRADES")
                || equalsMarker(display, "UPGRADES")
                || containsMarker(display, "BANKER")
                || containsMarker(display, "STREAK POWERS");
    }

    /**
     * Case-insensitive substring search that steps over section-sign colour codes as it goes, so
     * the name never has to be stripped, trimmed and upper-cased into a new string first. Marker
     * must be upper case.
     */
    private static boolean containsMarker(String display, String marker) {
        int length = display.length();
        int markerLength = marker.length();
        for (int start = 0; start <= length - markerLength; start++) {
            int index = start;
            int matched = 0;
            while (index < length && matched < markerLength) {
                char character = display.charAt(index);
                if (character == '\u00a7') {
                    index += 2;
                    continue;
                }
                if (Character.toUpperCase(character) != marker.charAt(matched)) {
                    break;
                }
                index++;
                matched++;
            }
            if (matched == markerLength) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsMarker(String display, String marker) {
        int length = display.length();
        int index = 0;
        int end = length;
        while (index < end && (display.charAt(index) == ' ' || display.charAt(index) == '\u00a7')) {
            index += display.charAt(index) == '\u00a7' ? 2 : 1;
        }
        while (end > index && display.charAt(end - 1) == ' ') {
            end--;
        }

        int matched = 0;
        while (index < end) {
            char character = display.charAt(index);
            if (character == '\u00a7') {
                index += 2;
                continue;
            }
            if (matched >= marker.length() || Character.toUpperCase(character) != marker.charAt(matched)) {
                return false;
            }
            index++;
            matched++;
        }
        return matched == marker.length();
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
