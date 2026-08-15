package keystrokesmod.module.impl.minigames;

import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockObsidian;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BedWars extends Module {
    private static final String[] CLOSEST_ENEMY_MODES = new String[]{"Bed", "Player"};

    private static final double OWN_BED_PROTECTION_RADIUS_SQ = 800.0;
    private static final int OWN_BED_SEARCH_HORIZONTAL_RADIUS = 24;
    private static final int OWN_BED_SEARCH_VERTICAL_RADIUS = 6;

    private final SliderSetting closestEnemy;
    private final ButtonSetting whitelistOwnBed;

    private ButtonSetting diamondArmor;
    private ButtonSetting fireball;
    private ButtonSetting enderPearl;
    private ButtonSetting obsidian;
    private ButtonSetting shouldPing;

    private List<String> armoredPlayer = new ArrayList<>();
    private Map<String, String> lastHeldMap = new ConcurrentHashMap<>();
    private Map<BlockPos, Long> obsidianPos = new HashMap<>(); // blockPos, time received
    public List<SkyWars.SpawnEggInfo> entitySpawnQueue = new ArrayList<>();
    public List<Integer> spawnedMobs = new ArrayList<>(); // entity id

    private BlockPos spawnAnchor;
    private Vec3 ownBedCenter;
    private boolean ownBedDestroyed;
    private boolean pendingSpawnAnchorCapture;
    private boolean waitingForRespawn;
    private long respawnMessageTime;

    private int obsidianColor = new Color(106, 13, 173).getRGB();

    public BedWars() {
        super("Bed Wars", category.minigames);
        this.registerSetting(closestEnemy = new SliderSetting("Closest enemy", true, 0, CLOSEST_ENEMY_MODES));
        this.registerSetting(whitelistOwnBed = new ButtonSetting("Whitelist own bed", true));
        this.registerSetting(new DescriptionSetting("Game alerts"));
        this.registerSetting(diamondArmor = new ButtonSetting("Diamond armor", true));
        this.registerSetting(fireball = new ButtonSetting("Fireball", false));
        this.registerSetting(obsidian = new ButtonSetting("Obsidian", true));
        this.registerSetting(enderPearl = new ButtonSetting("Ender pearl", true));
        this.registerSetting(shouldPing = new ButtonSetting("Should ping", true));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        entitySpawnQueue.clear();
        spawnedMobs.clear();
        resetSpawnTracking();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (Utils.nullCheck() && obsidian.isToggled()) {
            if (this.obsidianPos.isEmpty()) {
                return;
            }
            try {
                List<BlockPos> blocksToRender = new ArrayList<>();
                Iterator<Map.Entry<BlockPos, Long>> iterator = this.obsidianPos.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, Long> entry = iterator.next();
                    BlockPos blockPos = entry.getKey();
                    Long receivedMs = entry.getValue();

                    if (!(mc.theWorld.getBlockState(blockPos).getBlock() instanceof BlockObsidian) && Utils.timeBetween(System.currentTimeMillis(), receivedMs) >= 500) {
                        iterator.remove();
                        continue;
                    }
                    blocksToRender.add(blockPos);
                }
                renderObsidianOverlays(mergeObsidianBounds(blocksToRender));

            }
            catch (Exception exception) {}
        }
    }

    private List<AxisAlignedBB> mergeObsidianBounds(List<BlockPos> blocks) {
        List<AxisAlignedBB> bounds = new ArrayList<>();

        for (BlockPos pos : blocks) {
            bounds.add(new AxisAlignedBB(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    pos.getX() + 1.0,
                    pos.getY() + 1.0,
                    pos.getZ() + 1.0
            ));
        }

        boolean merged;
        do {
            merged = false;

            outer:
            for (int i = 0; i < bounds.size(); i++) {
                for (int j = i + 1; j < bounds.size(); j++) {
                    AxisAlignedBB combined = mergeAdjacentBounds(bounds.get(i), bounds.get(j));
                    if (combined != null) {
                        bounds.set(i, combined);
                        bounds.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        } while (merged);

        return bounds;
    }

    private AxisAlignedBB mergeAdjacentBounds(AxisAlignedBB first, AxisAlignedBB second) {
        boolean sameX = first.minX == second.minX && first.maxX == second.maxX;
        boolean sameY = first.minY == second.minY && first.maxY == second.maxY;
        boolean sameZ = first.minZ == second.minZ && first.maxZ == second.maxZ;

        if (sameY && sameZ && (first.maxX == second.minX || second.maxX == first.minX)) {
            return new AxisAlignedBB(
                    Math.min(first.minX, second.minX),
                    first.minY,
                    first.minZ,
                    Math.max(first.maxX, second.maxX),
                    first.maxY,
                    first.maxZ
            );
        }

        if (sameX && sameZ && (first.maxY == second.minY || second.maxY == first.minY)) {
            return new AxisAlignedBB(
                    first.minX,
                    Math.min(first.minY, second.minY),
                    first.minZ,
                    first.maxX,
                    Math.max(first.maxY, second.maxY),
                    first.maxZ
            );
        }

        if (sameX && sameY && (first.maxZ == second.minZ || second.maxZ == first.minZ)) {
            return new AxisAlignedBB(
                    first.minX,
                    first.minY,
                    Math.min(first.minZ, second.minZ),
                    first.maxX,
                    first.maxY,
                    Math.max(first.maxZ, second.maxZ)
            );
        }

        return null;
    }

    private void renderObsidianOverlays(List<AxisAlignedBB> obsidianBounds) {
        if (obsidianBounds.isEmpty()) {
            return;
        }

        float red = (obsidianColor >> 16 & 0xFF) / 255.0f;
        float green = (obsidianColor >> 8 & 0xFF) / 255.0f;
        float blue = (obsidianColor & 0xFF) / 255.0f;
        RenderManager renderManager = mc.getRenderManager();

        GL11.glPushMatrix();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);

        try {
            for (AxisAlignedBB bounds : obsidianBounds) {
                AxisAlignedBB renderedBounds = bounds.offset(
                        -renderManager.viewerPosX,
                        -renderManager.viewerPosY,
                        -renderManager.viewerPosZ
                );
                RenderUtils.drawBoundingBox(renderedBounds, red, green, blue, 0.25f);
            }
        } finally {
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glPopMatrix();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()
                || closestEnemy.getInput() == -1 || Utils.getBedwarsStatus() != 2
                || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        Vec3 bedReference = (int) closestEnemy.getInput() == 0 && !ownBedDestroyed ? getOwnBedReference() : null;
        EntityPlayer enemy = findClosestEnemy(bedReference);
        if (enemy == null) {
            return;
        }

        double distance = Math.round(distanceToReference(enemy, bedReference));
        String text = enemy.getDisplayName().getFormattedText() + " §r"
                + Utils.formatColor(getDistanceColor(distance)) + Utils.asWholeNum(distance) + "m§r";
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = 0.0f;
        float centerY = resolution.getScaledHeight() / 4.0f;
        float textWidth = mc.fontRendererObj.getStringWidth(text);
        float horizontalPadding = 3.0f;
        float verticalPadding = 4.0f;
        float textX = x + horizontalPadding;
        float textY = centerY - mc.fontRendererObj.FONT_HEIGHT / 2.0f;

        RenderUtils.drawRoundedRectangle(
                x - 10,
                textY - verticalPadding,
                textX + textWidth + horizontalPadding + 1,
                textY + mc.fontRendererObj.FONT_HEIGHT + verticalPadding - 1,
                7.0f,
                2013265920
        );
        mc.fontRendererObj.drawString(text, textX, textY, Color.WHITE.getRGB(), true);
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            armoredPlayer.clear();
            lastHeldMap.clear();
            obsidianPos.clear();
            entitySpawnQueue.clear();
            spawnedMobs.clear();
            resetSpawnTracking();
        }
        else {
            if (e.entity instanceof EntityIronGolem) {
                if (Utils.getBedwarsStatus() != 2) {
                    return;
                }
                Vec3 spawnPosition = new Vec3(e.entity.posX, e.entity.posY, e.entity.posZ);
                for (SkyWars.SpawnEggInfo eggInfo : entitySpawnQueue) {
                    if (eggInfo.spawnPos.distanceTo(spawnPosition) > 3 || Utils.timeBetween(mc.thePlayer.ticksExisted, eggInfo.tickSpawned) > 60) { // 3 seconds or not at spawn point then not own mob
                        return;
                    }
                    if (!entitySpawnQueue.remove(eggInfo)) {
                        return;
                    }
                    spawnedMobs.add(e.entity.getEntityId());
                }
            }
        }
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }

        if (pendingSpawnAnchorCapture && Utils.getBedwarsStatus() == 2) {
            spawnAnchor = mc.thePlayer.getPosition();
            ownBedCenter = findOwnBedCenter();
            pendingSpawnAnchorCapture = false;
        }

        if (Utils.getBedwarsStatus() == 2) {
            if (diamondArmor.isToggled() || enderPearl.isToggled() || obsidian.isToggled()) {
                for (EntityPlayer p : mc.theWorld.playerEntities) {
                    if (p == null) {
                        continue;
                    }
                    if (p == mc.thePlayer) {
                        continue;
                    }
                    if (AntiBot.isBot(p)) {
                        continue;
                    }
                    String name = p.getName();
                    ItemStack item = p.getHeldItem();
                    if (diamondArmor.isToggled()) {
                        ItemStack leggings = p.inventory.armorInventory[1];
                        if (!armoredPlayer.contains(name) && p.inventory != null && leggings != null && leggings.getItem() != null && leggings.getItem() == Items.diamond_leggings) {
                            armoredPlayer.add(name);
                            Utils.sendMessage(p.getDisplayName().getFormattedText() + " &7has purchased &bDiamond Armor");
                            ping();
                        }
                    }
                    if (item != null && !lastHeldMap.containsKey(name)) {
                        String itemType = getItemType(item);
                        if (itemType != null) {
                            lastHeldMap.put(name, itemType);
                            double distance = Math.round(mc.thePlayer.getDistanceToEntity(p));
                            handleAlert(itemType, p.getDisplayName().getFormattedText(), distance);
                        }
                    } else if (lastHeldMap.containsKey(name)) {
                        String itemType = lastHeldMap.get(name);
                        if (!itemType.equals(getItemType(item))) {
                            lastHeldMap.remove(name);
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        String strippedMessage = Utils.stripColor(event.message.getUnformattedText());
        if (strippedMessage.startsWith(" ") && strippedMessage.contains("Protect your bed and destroy the enemy beds.")) {
            ownBedDestroyed = false;
            ownBedCenter = null;
            pendingSpawnAnchorCapture = true;
            waitingForRespawn = false;
        }
        else if (strippedMessage.equals("You will respawn because you still have a bed!")) {
            waitingForRespawn = true;
            respawnMessageTime = System.currentTimeMillis();
        }
        else if (strippedMessage.equals("You have respawned!") && waitingForRespawn && Utils.timeBetween(System.currentTimeMillis(), respawnMessageTime) <= 12000) {
            pendingSpawnAnchorCapture = true;
            waitingForRespawn = false;
        }

        if (strippedMessage.trim().startsWith("BED DESTRUCTION > Your Bed")) {
            ownBedDestroyed = true;
            waitingForRespawn = false;
        }
    }

    public void removeOwnBedPair(List<BlockPos[]> bedPairs) {
        if (!shouldWhitelistOwnBed() || bedPairs.isEmpty()) {
            return;
        }

        BlockPos[] ownBedPair = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        Vec3 spawnCenter = spawnAnchorCenter();

        for (BlockPos[] pair : bedPairs) {
            double distance = spawnCenter.squareDistanceTo(bedCenter(pair));
            if (distance < closestDistance) {
                closestDistance = distance;
                ownBedPair = pair;
            }
        }

        if (ownBedPair != null) {
            ownBedCenter = bedCenter(ownBedPair);
            bedPairs.remove(ownBedPair);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement p = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (p.getPlacedBlockDirection() != 255 && p.getStack() != null && p.getStack().getItem() != null) {
                if (p.getStack().getItem() instanceof ItemMonsterPlacer) {
                    Class<? extends Entity> oclass = EntityList.stringToClassMapping.get(ItemMonsterPlacer.getEntityName(p.getStack()));
                    if (oclass == null) {
                        return;
                    }
                    if (oclass.getSimpleName().equals("EntityIronGolem")) {
                        entitySpawnQueue.add(new SkyWars.SpawnEggInfo(p.getPosition(), mc.thePlayer.ticksExisted));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.getPacket() instanceof S23PacketBlockChange) {
            S23PacketBlockChange p = (S23PacketBlockChange) e.getPacket();
            if (p.getBlockState() != null && p.getBlockState().getBlock() instanceof BlockObsidian && isNextToBed(p.getBlockPosition())) {
                this.obsidianPos.put(p.getBlockPosition(), System.currentTimeMillis());
            }
        }
    }

    private boolean isNextToBed(BlockPos blockPos) {
        for (EnumFacing enumFacing : EnumFacing.values()) {
            BlockPos offset = blockPos.offset(enumFacing);
            if (BlockUtils.getBlock(offset) instanceof BlockBed) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldWhitelistOwnBed() {
        return whitelistOwnBed.isToggled() && spawnAnchor != null && Utils.getBedwarsStatus() == 2
                && mc.thePlayer.getDistanceSq(spawnAnchor) <= OWN_BED_PROTECTION_RADIUS_SQ;
    }

    private Vec3 spawnAnchorCenter() {
        return new Vec3(spawnAnchor.getX() + 0.5, spawnAnchor.getY() + 0.5, spawnAnchor.getZ() + 0.5);
    }

    private Vec3 getOwnBedReference() {
        return ownBedCenter != null ? ownBedCenter : spawnAnchor == null ? null : spawnAnchorCenter();
    }

    private Vec3 findOwnBedCenter() {
        if (spawnAnchor == null) {
            return null;
        }

        Vec3 spawnCenter = spawnAnchorCenter();
        Vec3 closestBedCenter = null;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (int x = -OWN_BED_SEARCH_HORIZONTAL_RADIUS; x <= OWN_BED_SEARCH_HORIZONTAL_RADIUS; x++) {
            for (int y = -OWN_BED_SEARCH_VERTICAL_RADIUS; y <= OWN_BED_SEARCH_VERTICAL_RADIUS; y++) {
                for (int z = -OWN_BED_SEARCH_HORIZONTAL_RADIUS; z <= OWN_BED_SEARCH_HORIZONTAL_RADIUS; z++) {
                    BlockPos position = spawnAnchor.add(x, y, z);
                    if (!(BlockUtils.getBlock(position) instanceof BlockBed)) {
                        continue;
                    }

                    Vec3 center = new Vec3(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
                    double distance = spawnCenter.squareDistanceTo(center);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestBedCenter = center;
                    }
                }
            }
        }

        return closestBedCenter;
    }

    private EntityPlayer findClosestEnemy(Vec3 bedReference) {
        EntityPlayer closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || !player.isEntityAlive() || player.isSpectator()
                    || AntiBot.isBot(player) || Utils.isTeammate(player)) {
                continue;
            }

            double distance = distanceToReference(player, bedReference);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = player;
            }
        }

        return closest;
    }

    private double distanceToReference(EntityPlayer player, Vec3 bedReference) {
        if (bedReference == null) {
            return mc.thePlayer.getDistanceToEntity(player);
        }
        return player.getDistance(bedReference.xCoord, bedReference.yCoord, bedReference.zCoord);
    }

    private Vec3 bedCenter(BlockPos[] pair) {
        return new Vec3(
                (pair[0].getX() + pair[1].getX() + 1.0) * 0.5,
                (pair[0].getY() + pair[1].getY() + 1.0) * 0.5,
                (pair[0].getZ() + pair[1].getZ() + 1.0) * 0.5
        );
    }

    private void resetSpawnTracking() {
        spawnAnchor = null;
        ownBedCenter = null;
        ownBedDestroyed = false;
        pendingSpawnAnchorCapture = false;
        waitingForRespawn = false;
        respawnMessageTime = 0L;
    }

    private String getItemType(ItemStack item) {
        if (item == null || item.getItem() == null) {
            return null;
        }
        String unlocalizedName = item.getItem().getUnlocalizedName();
        if (item.getItem() instanceof ItemEnderPearl && enderPearl.isToggled()) {
            return "&7an §3Ender Pearl";
        }
        else if (unlocalizedName.contains("tile.obsidian") && obsidian.isToggled()) {
            return "§dObsidian";
        }
        else if (item.getItem() instanceof ItemFireball && fireball.isToggled()) {
            return "&7a §6Fireball";
        }
        return null;
    }

    private void handleAlert(String itemType, String name, double distance) {
        String alert = name + " &7is holding " + itemType + " " + getDistanceColor(distance) + Utils.asWholeNum(distance) + "m§r";
        Utils.sendMessage(alert);
        ping();
    }

    private String getDistanceColor(double distance) {
        if (distance < 10) {
            return "&c";
        }
        if (distance < 25) {
            return "&6";
        }
        if (distance < 50) {
            return "&e";
        }
        if (distance > 100) {
            return "&2";
        }
        return "&a";
    }

    private void ping() {
        if (shouldPing.isToggled()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
    }
}
