package mindless.module.impl.minigames;

import mindless.clickgui.animation.ScrollOffsetAnimation;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockObsidian;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerUseItemEvent;
import mindless.utility.gui.MindlessButton;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BedWars extends Module {
    private static final String[] CLOSEST_ENEMY_MODES = new String[]{"Bed", "Player"};
    private static final long MAGIC_MILK_DURATION_MS = 30000L;
    private static final long CLOSEST_ENEMY_ANIMATION_DURATION_MS = 100L;
    private static final long CLOSEST_ENEMY_FADE_DURATION_MS = 150L;
    private static final FloatBuffer ITEM_ALPHA_COLOR = BufferUtils.createFloatBuffer(4);

    private static final double OWN_BED_PROTECTION_RADIUS_SQ = 800.0;
    private static final int OWN_BED_SEARCH_HORIZONTAL_RADIUS = 24;
    private static final int OWN_BED_SEARCH_VERTICAL_RADIUS = 6;

    private final SliderSetting closestEnemy;
    private final SliderSetting alertInterval;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting magicMilkTimer;

    private ButtonSetting bow;
    private ButtonSetting diamondArmor;
    private ButtonSetting dreamDefender;
    private ButtonSetting fireball;
    private ButtonSetting enderPearl;
    private ButtonSetting knockbackStick;
    private ButtonSetting magicMilk;
    private ButtonSetting obsidian;
    private ButtonSetting potions;
    private ButtonSetting waterBucket;
    private ButtonSetting ignoreTeammate;
    private ButtonSetting shouldPing;

    private List<String> armoredPlayer = new ArrayList<>();
    private Map<String, String> lastHeldMap = new ConcurrentHashMap<>();
    private Map<String, Long> lastAlertMap = new ConcurrentHashMap<>();
    private Map<BlockPos, Long> obsidianPos = new HashMap<>(); // blockPos, time received
    public List<SpawnEggInfo> entitySpawnQueue = new ArrayList<>();
    public List<Integer> spawnedMobs = new ArrayList<>(); // entity id

    private BlockPos spawnAnchor;
    private Vec3 ownBedCenter;
    private boolean ownBedDestroyed;
    private boolean pendingSpawnAnchorCapture;
    private boolean waitingForRespawn;
    private long respawnMessageTime;
    private long magicMilkExpiresAt;

    private float closestEnemyPosX = Float.NaN;
    private float closestEnemyPosY = Float.NaN;
    private float closestEnemyRelativePosX = Float.NaN;
    private float closestEnemyRelativePosY = Float.NaN;
    private final ScrollOffsetAnimation closestEnemyWidthAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final ScrollOffsetAnimation closestEnemyHeightAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final ScrollOffsetAnimation closestEnemyContentAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final SmoothFloatAnimation closestEnemyVisibilityAnimation = new SmoothFloatAnimation(CLOSEST_ENEMY_FADE_DURATION_MS);
    private boolean closestEnemySizeInitialized;
    private boolean closestEnemyVisibilityInitialized;
    private List<Integer> closestEnemySelection;
    private List<EnemyHudEntry> lastClosestEnemyEntries = Collections.emptyList();
    private float magicMilkPosX = Float.NaN;
    private float magicMilkPosY = Float.NaN;
    private float magicMilkRelativePosX = Float.NaN;
    private float magicMilkRelativePosY = Float.NaN;

    private int obsidianColor = new Color(106, 13, 173).getRGB();

    public BedWars() {
        super("Bed Wars", category.bedwars);
        this.liteModule = true;
        this.registerSetting(closestEnemy = new SliderSetting("Closest enemy", true, 0, CLOSEST_ENEMY_MODES));
        this.registerSetting(whitelistOwnBed = new ButtonSetting("Whitelist own bed", true));
        this.registerSetting(magicMilkTimer = new ButtonSetting("Magic milk timer", true));
        this.registerSetting(new ButtonSetting("Edit positions", () -> mc.displayGuiScreen(new EditPositionsScreen())));
        this.registerSetting(new DescriptionSetting("Game alerts"));
        this.registerSetting(alertInterval = new SliderSetting("Alert interval", " second", 5.0, 0.0, 30.0, 1.0));
        this.registerSetting(bow = new ButtonSetting("Bow", true));
        this.registerSetting(diamondArmor = new ButtonSetting("Diamond armor", true));
        this.registerSetting(dreamDefender = new ButtonSetting("Dream defender", true));
        this.registerSetting(fireball = new ButtonSetting("Fireball", false));
        this.registerSetting(knockbackStick = new ButtonSetting("Knockback stick", true));
        this.registerSetting(magicMilk = new ButtonSetting("Magic milk", true));
        this.registerSetting(obsidian = new ButtonSetting("Obsidian", true));
        this.registerSetting(enderPearl = new ButtonSetting("Ender pearl", true));
        this.registerSetting(potions = new ButtonSetting("Potions", true));
        this.registerSetting(waterBucket = new ButtonSetting("Water bucket", true));
        this.registerSetting(ignoreTeammate = new ButtonSetting("Ignore teammate", false));
        this.registerSetting(shouldPing = new ButtonSetting("Should ping", true));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        entitySpawnQueue.clear();
        spawnedMobs.clear();
        lastAlertMap.clear();
        resetSpawnTracking();
        magicMilkExpiresAt = 0L;
        closestEnemySizeInitialized = false;
        closestEnemyVisibilityInitialized = false;
        closestEnemySelection = null;
        lastClosestEnemyEntries = Collections.emptyList();
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
                || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        boolean playingBedWars = Utils.getBedwarsStatus() == 2;
        boolean showClosestEnemyExit = !lastClosestEnemyEntries.isEmpty();
        long remainingMilkTime = magicMilkExpiresAt - System.currentTimeMillis();
        boolean showMagicMilkTimer = playingBedWars && magicMilkTimer.isToggled() && remainingMilkTime > 0L;
        boolean showClosestEnemy = playingBedWars && closestEnemy.getInput() != -1;
        if (!showClosestEnemy && !showClosestEnemyExit && !showMagicMilkTimer) {
            return;
        }

        ScaledResolution resolution = new ScaledResolution(mc);
        syncHudPositions(resolution);

        if (showClosestEnemy) {
            Vec3 bedReference = !ownBedDestroyed ? getOwnBedReference() : null;
            List<EntityPlayer> enemies = findClosestEnemies(
                    bedReference,
                    (int) closestEnemy.getInput()
            );

            List<EnemyHudEntry> entries = new ArrayList<>();

            for (EntityPlayer enemy : enemies) {
                double distance = Math.round(distanceToReference(enemy, bedReference));

                String name = enemy.getDisplayName().getFormattedText() + "§r";
                String distanceText = Utils.formatColor(Utils.getDistanceColor(distance))
                        + Utils.asWholeNum(distance) + "m§r";

                entries.add(new EnemyHudEntry(enemy.getEntityId(), name, distanceText, enemy.getHeldItem()));
            }

            drawEnemyHudBox(entries, closestEnemyPosX, closestEnemyPosY);
        }
        else if (showClosestEnemyExit) {
            drawEnemyHudBox(Collections.emptyList(), closestEnemyPosX, closestEnemyPosY);
        }

        if (showMagicMilkTimer) {
            String text = String.format(Locale.ROOT, "Magic Milk: §b%.1fs", remainingMilkTime / 1000.0D);
            drawHudBox(text, magicMilkPosX, magicMilkPosY);
        }
    }

    private HudBoxBounds drawHudBox(String text, float x, float textY) {
        return drawHudBox(text, null, x, textY);
    }

    private HudBoxBounds drawHudBox(String text, ItemStack heldItem, float x, float textY) {
        float horizontalPadding = 3.0f;
        float verticalPadding = 4.0f;
        float textX = x + horizontalPadding;
        float textWidth = mc.fontRendererObj.getStringWidth(text);
        float left = x - 10.0f;
        float top = textY - verticalPadding;
        float right = textX + textWidth + horizontalPadding + 1.0f;
        float bottom = textY + mc.fontRendererObj.FONT_HEIGHT + verticalPadding - 1.0f;
        float itemX = 0.0f;
        if (heldItem != null) {
            itemX = textX + textWidth + 3.0f;
            right = itemX + 14.5f;
        }
        RenderUtils.drawRoundedRectangle(
                left,
                top,
                right,
                bottom,
                7.0f,
                2013265920
        );
        mc.fontRendererObj.drawString(text, textX, textY, Color.WHITE.getRGB(), true);
        if (heldItem != null) {
            renderHeldItem(heldItem, itemX, top + 2f);
        }
        return new HudBoxBounds(left, top, right, bottom);
    }

    private void renderHeldItem(ItemStack heldItem, float x, float y) {
        renderHeldItem(heldItem, x, y, 1.0f);
    }

    private void renderHeldItem(ItemStack heldItem, float x, float y, float opacity) {
        float scale = 11.0f / 16.0f;
        boolean fading = opacity < 1.0f;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0.0f);
            GL11.glScalef(scale, scale, 1.0f);
            if (fading) {
                setItemOpacity(opacity);
            }
            RenderUtils.renderItemAndEffectIntoGui3D(heldItem, 0, 0);
        }
        finally {
            if (fading) {
                resetItemOpacity();
            }
            GL11.glPopMatrix();
        }
    }

    private void setItemOpacity(float opacity) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        ITEM_ALPHA_COLOR.clear();
        ITEM_ALPHA_COLOR.put(1.0f).put(1.0f).put(1.0f).put(opacity).flip();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PRIMARY_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA, OpenGlHelper.GL_CONSTANT);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ITEM_ALPHA_COLOR);
    }

    private void resetItemOpacity() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        ITEM_ALPHA_COLOR.clear();
        ITEM_ALPHA_COLOR.put(1.0f).put(1.0f).put(1.0f).put(1.0f).flip();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ITEM_ALPHA_COLOR);
    }

    @SubscribeEvent
    public void onItemUseFinish(PlayerUseItemEvent.Finish event) {
        if (magicMilkTimer.isToggled() && Utils.nullCheck() && Utils.getBedwarsStatus() == 2
                && event.entityPlayer == mc.thePlayer && event.item != null
                && event.item.getItem() == Items.milk_bucket) {
            magicMilkExpiresAt = System.currentTimeMillis() + MAGIC_MILK_DURATION_MS;
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            armoredPlayer.clear();
            lastHeldMap.clear();
            lastAlertMap.clear();
            obsidianPos.clear();
            entitySpawnQueue.clear();
            spawnedMobs.clear();
            resetSpawnTracking();
            magicMilkExpiresAt = 0L;
        }
        else {
            if (e.entity instanceof EntityIronGolem) {
                if (Utils.getBedwarsStatus() != 2) {
                    return;
                }
                Vec3 spawnPosition = new Vec3(e.entity.posX, e.entity.posY, e.entity.posZ);
                for (SpawnEggInfo eggInfo : entitySpawnQueue) {
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
            if (bow.isToggled() || diamondArmor.isToggled() || dreamDefender.isToggled()
                    || enderPearl.isToggled() || fireball.isToggled()
                    || knockbackStick.isToggled() || magicMilk.isToggled() || obsidian.isToggled()
                    || potions.isToggled() || waterBucket.isToggled()) {
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
                    if (ignoreTeammate.isToggled() && Utils.isTeammate(p)) {
                        continue;
                    }
                    String name = p.getName();
                    ItemStack item = p.getHeldItem();
                    if (diamondArmor.isToggled()) {
                        ItemStack leggings = p.inventory.armorInventory[1];
                        if (!armoredPlayer.contains(name) && leggings != null && leggings.getItem() != null && leggings.getItem() == Items.diamond_leggings) {
                            armoredPlayer.add(name);
                            double distance = Math.round(mc.thePlayer.getDistanceToEntity(p));
                            Utils.sendMessage(p.getDisplayName().getFormattedText() + " &7has purchased &bDiamond Armor " + Utils.getDistanceColor(distance) + Utils.asWholeNum(distance) + "m§r");
                            ping();
                        }
                    }
                    if (item != null && !lastHeldMap.containsKey(name)) {
                        String itemType = getItemType(item);
                        if (itemType != null) {
                            lastHeldMap.put(name, itemType);
                            double distance = Math.round(mc.thePlayer.getDistanceToEntity(p));
                            handleAlert(p, itemType, distance);
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
            magicMilkExpiresAt = 0L;
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
                        entitySpawnQueue.add(new SpawnEggInfo(p.getPosition(), mc.thePlayer.ticksExisted));
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

    private List<EntityPlayer> findClosestEnemies(Vec3 bedReference, int players) {
        List<EntityPlayer> enemies = new ArrayList<>();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null
                    || player == mc.thePlayer
                    || !player.isEntityAlive()
                    || player.isSpectator()
                    || AntiBot.isBot(player)
                    || Utils.isTeammate(player)
                    || Utils.isFriended(player)) {
                continue;
            }

            enemies.add(player);
        }

        enemies.sort(Comparator.comparingDouble(player -> distanceToReference(player, bedReference)));

        int amount = Math.min(Math.max(players, 0), enemies.size());
        return new ArrayList<>(enemies.subList(0, amount));
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
        else if (item.getItem() instanceof ItemBow && bow.isToggled()) {
            return "&7a §dBow";
        }
        else if (item.getItem() == Items.milk_bucket && magicMilk.isToggled()) {
            return "§fMagic Milk";
        }
        else if (item.getItem() == Items.stick && item.isItemEnchanted() && knockbackStick.isToggled()) {
            return "&7a §dKnockback Stick";
        }
        else if (item.getItem() instanceof ItemMonsterPlacer && !item.isItemEnchanted() && dreamDefender.isToggled()) {
            return "&7a §fDream Defender";
        }
        else if (item.getItem() instanceof ItemPotion && potions.isToggled()) {
            String potionType = getPotionType(item);
            if (potionType != null) {
                String article = potionType.equals("Invis") ? "an" : "a";
                return "&7" + article + " §d" + potionType + " §7Potion";
            }
        }
        else if (item.getItem() == Items.water_bucket && waterBucket.isToggled()) {
            return "&7a §3Water Bucket";
        }
        else if (unlocalizedName.contains("tile.obsidian") && obsidian.isToggled()) {
            return "§dObsidian";
        }
        else if (item.getItem() instanceof ItemFireball && fireball.isToggled()) {
            return "&7a §6Fireball";
        }
        return null;
    }

    private String getPotionType(ItemStack item) {
        List<PotionEffect> effects = ((ItemPotion) item.getItem()).getEffects(item);
        if (effects == null) {
            return null;
        }
        for (PotionEffect effect : effects) {
            if (effect.getPotionID() == Potion.moveSpeed.id) {
                return "Speed";
            }
            if (effect.getPotionID() == Potion.invisibility.id) {
                return "Invis";
            }
            if (effect.getPotionID() == Potion.jump.id) {
                return "Jump";
            }
        }
        return null;
    }

    private void handleAlert(EntityPlayer player, String itemType, double distance) {
        long currentTime = System.currentTimeMillis();
        long interval = Math.round(alertInterval.getInput() * 1000.0);
        String alertKey = player.getUniqueID() + ":" + itemType;
        Long lastAlert = lastAlertMap.get(alertKey);
        if (lastAlert != null && currentTime - lastAlert < interval) {
            return;
        }

        lastAlertMap.put(alertKey, currentTime);
        String alert = player.getDisplayName().getFormattedText() + " &7is holding " + itemType + " " + Utils.getDistanceColor(distance) + Utils.asWholeNum(distance) + "m§r";
        Utils.sendMessage(alert);
        ping();
    }

    public float getClosestEnemyPosX() {
        syncHudPositions();
        return closestEnemyPosX;
    }

    public float getClosestEnemyPosY() {
        syncHudPositions();
        return closestEnemyPosY;
    }

    public float getClosestEnemyRelativePosX() {
        syncHudPositions();
        return closestEnemyRelativePosX;
    }

    public float getClosestEnemyRelativePosY() {
        syncHudPositions();
        return closestEnemyRelativePosY;
    }

    public void setClosestEnemyRelativePosition(float normalizedX, float normalizedY) {
        closestEnemyRelativePosX = normalizedX;
        closestEnemyRelativePosY = normalizedY;
        syncHudPositions();
    }

    public void setClosestEnemyAbsolutePosition(float absoluteX, float absoluteY) {
        setClosestEnemyAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    public float getMagicMilkPosX() {
        syncHudPositions();
        return magicMilkPosX;
    }

    public float getMagicMilkPosY() {
        syncHudPositions();
        return magicMilkPosY;
    }

    public float getMagicMilkRelativePosX() {
        syncHudPositions();
        return magicMilkRelativePosX;
    }

    public float getMagicMilkRelativePosY() {
        syncHudPositions();
        return magicMilkRelativePosY;
    }

    public void setMagicMilkRelativePosition(float normalizedX, float normalizedY) {
        magicMilkRelativePosX = normalizedX;
        magicMilkRelativePosY = normalizedY;
        syncHudPositions();
    }

    public void setMagicMilkAbsolutePosition(float absoluteX, float absoluteY) {
        setMagicMilkAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    private void syncHudPositions() {
        syncHudPositions(new ScaledResolution(mc));
    }

    private void syncHudPositions(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        float defaultClosestEnemyY = scaledHeight / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f;

        if (Float.isNaN(closestEnemyRelativePosX) || Float.isNaN(closestEnemyRelativePosY)) {
            if (Float.isNaN(closestEnemyPosX) || Float.isNaN(closestEnemyPosY)) {
                closestEnemyPosX = 0.0f;
                closestEnemyPosY = defaultClosestEnemyY;
            }
            closestEnemyRelativePosX = closestEnemyPosX / scaledWidth;
            closestEnemyRelativePosY = closestEnemyPosY / scaledHeight;
        }
        closestEnemyPosX = closestEnemyRelativePosX * scaledWidth;
        closestEnemyPosY = closestEnemyRelativePosY * scaledHeight;

        if (Float.isNaN(magicMilkRelativePosX) || Float.isNaN(magicMilkRelativePosY)) {
            if (Float.isNaN(magicMilkPosX) || Float.isNaN(magicMilkPosY)) {
                magicMilkPosX = 0.0f;
                magicMilkPosY = defaultClosestEnemyY + mc.fontRendererObj.FONT_HEIGHT + 9.0f;
            }
            magicMilkRelativePosX = magicMilkPosX / scaledWidth;
            magicMilkRelativePosY = magicMilkPosY / scaledHeight;
        }
        magicMilkPosX = magicMilkRelativePosX * scaledWidth;
        magicMilkPosY = magicMilkRelativePosY * scaledHeight;
    }

    private void setClosestEnemyAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        closestEnemyPosX = absoluteX;
        closestEnemyPosY = absoluteY;
        closestEnemyRelativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        closestEnemyRelativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
    }

    private void setMagicMilkAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        magicMilkPosX = absoluteX;
        magicMilkPosY = absoluteY;
        magicMilkRelativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        magicMilkRelativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
    }

    private void resetHudPositions(ScaledResolution resolution) {
        float closestEnemyY = resolution.getScaledHeight() / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f;
        setClosestEnemyAbsolutePosition(0.0f, closestEnemyY, resolution);
        setMagicMilkAbsolutePosition(0.0f, closestEnemyY + mc.fontRendererObj.FONT_HEIGHT + 9.0f, resolution);
    }

    private static final class HudBoxBounds {
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        private HudBoxBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private class EditPositionsScreen extends GuiScreen {
        private static final int DRAG_NONE = 0;
        private static final int DRAG_CLOSEST_ENEMY = 1;
        private static final int DRAG_MAGIC_MILK = 2;

        private MindlessButton resetPositions;
        private HudBoxBounds closestEnemyBounds;
        private HudBoxBounds magicMilkBounds;
        private int dragging = DRAG_NONE;
        private float closestEnemyX;
        private float closestEnemyY;
        private float magicMilkX;
        private float magicMilkY;
        private float dragStartX;
        private float dragStartY;
        private int lastMouseX;
        private int lastMouseY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPositions = new MindlessButton(1, this.width - 95, this.height - 25, 90, 20, "Reset positions"));
            syncHudPositions(new ScaledResolution(this.mc));
            syncEditorPositions();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(this.mc);
            if (this.dragging == DRAG_NONE) {
                syncHudPositions(resolution);
                syncEditorPositions();
            }

            drawRect(0, 0, this.width, this.height, 0xB2000000);
            setClosestEnemyAbsolutePosition(this.closestEnemyX, this.closestEnemyY, resolution);
            setMagicMilkAbsolutePosition(this.magicMilkX, this.magicMilkY, resolution);

            List<EnemyHudEntry> previewEntries = new ArrayList<>();

            int previewPlayers = Math.max(1, (int) closestEnemy.getInput());

            for (int i = 0; i < previewPlayers; i++) {
                String name = mc.thePlayer.getDisplayName().getFormattedText() + "§r";
                String distance = "§a" + (16 + i * 3) + "m§r";

                previewEntries.add(
                        new EnemyHudEntry(mc.thePlayer.getEntityId(), name, distance, mc.thePlayer.getHeldItem())
                );
            }

            this.closestEnemyBounds = drawEnemyHudBox(
                    previewEntries,
                    this.closestEnemyX,
                    this.closestEnemyY
            );

            this.magicMilkBounds = drawHudBox(
                    "Magic Milk: §b30.0s",
                    this.magicMilkX,
                    this.magicMilkY
            );

            String message = "Drag either box to reposition it.";
            int messageX = resolution.getScaledWidth() / 2 - this.fontRendererObj.getStringWidth(message) / 2;
            int messageY = resolution.getScaledHeight() / 2 - 10;
            RenderUtils.drawColoredString(message, '-', messageX, messageY, 2L, 0L, true, this.fontRendererObj);

            try {
                this.handleInput();
            }
            catch (IOException ignored) {
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button != 0) {
                return;
            }

            if (this.dragging == DRAG_CLOSEST_ENEMY) {
                this.closestEnemyX = this.dragStartX + mouseX - this.lastMouseX;
                this.closestEnemyY = this.dragStartY + mouseY - this.lastMouseY;
            }
            else if (this.dragging == DRAG_MAGIC_MILK) {
                this.magicMilkX = this.dragStartX + mouseX - this.lastMouseX;
                this.magicMilkY = this.dragStartY + mouseY - this.lastMouseY;
            }
            else if (this.closestEnemyBounds != null && this.closestEnemyBounds.contains(mouseX, mouseY)) {
                beginDragging(DRAG_CLOSEST_ENEMY, this.closestEnemyX, this.closestEnemyY, mouseX, mouseY);
            }
            else if (this.magicMilkBounds != null && this.magicMilkBounds.contains(mouseX, mouseY)) {
                beginDragging(DRAG_MAGIC_MILK, this.magicMilkX, this.magicMilkY, mouseX, mouseY);
            }
        }

        private void beginDragging(int element, float elementX, float elementY, int mouseX, int mouseY) {
            this.dragging = element;
            this.dragStartX = elementX;
            this.dragStartY = elementY;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                this.dragging = DRAG_NONE;
            }
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == this.resetPositions) {
                this.dragging = DRAG_NONE;
                resetHudPositions(new ScaledResolution(this.mc));
                syncEditorPositions();
            }
        }

        private void syncEditorPositions() {
            this.closestEnemyX = closestEnemyPosX;
            this.closestEnemyY = closestEnemyPosY;
            this.magicMilkX = magicMilkPosX;
            this.magicMilkY = magicMilkPosY;
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }

    private void ping() {
        if (shouldPing.isToggled()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
    }

    public static class SpawnEggInfo {
        public final Vec3 spawnPos;
        public final int tickSpawned;

        public SpawnEggInfo(BlockPos pos, int tickSpawned) {
            this.spawnPos = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            this.tickSpawned = tickSpawned;
        }
    }

    private HudBoxBounds drawEnemyHudBox(List<EnemyHudEntry> entries, float x, float textY) {
        float verticalPadding = 4.0f;
        float left = x - 10.0f;
        float top = textY - verticalPadding;
        float contentProgress = updateClosestEnemySelection(entries);
        float visibility = updateClosestEnemyVisibility(entries);
        boolean fadingOut = entries.isEmpty();
        List<EnemyHudEntry> renderedEntries = fadingOut ? lastClosestEnemyEntries : entries;
        if (renderedEntries.isEmpty() || visibility <= 0.0f) {
            return null;
        }

        float horizontalPadding = 3.0f;
        float columnSpacing = 6.0f;
        float itemSpacing = 3.0f;
        float rowHeight = mc.fontRendererObj.FONT_HEIGHT + 4.0f;

        float textX = x + horizontalPadding;
        float maxNameWidth = 0.0f;
        float maxDistanceWidth = 0.0f;
        boolean hasItem = false;

        for (EnemyHudEntry entry : renderedEntries) {
            maxNameWidth = Math.max(
                    maxNameWidth,
                    mc.fontRendererObj.getStringWidth(entry.name)
            );

            maxDistanceWidth = Math.max(
                    maxDistanceWidth,
                    mc.fontRendererObj.getStringWidth(entry.distance)
            );

            if (entry.heldItem != null) {
                hasItem = true;
            }
        }

        float distanceX = textX + maxNameWidth + columnSpacing;
        float itemX = distanceX + maxDistanceWidth + itemSpacing;
        float contentOffset = (1.0f - contentProgress) * 6.0f;
        float contentOpacity = contentProgress * visibility;
        int contentColor = Utils.mergeAlpha(Color.WHITE.getRGB(), Math.max(4, Math.round(255.0f * contentOpacity)));
        float targetRight = distanceX + maxDistanceWidth + horizontalPadding + 1.0f;

        if (hasItem) {
            targetRight = itemX + 14.5f;
        }

        float targetBottom = textY
                + (renderedEntries.size() - 1) * rowHeight
                + mc.fontRendererObj.FONT_HEIGHT
                + verticalPadding
                - 1.0f;

        if (!fadingOut) {
            updateClosestEnemySize(targetRight - left, targetBottom - top);
        }
        float right = left + closestEnemyWidthAnimation.getValue();
        float bottom = top + closestEnemyHeightAnimation.getValue();
        int backgroundColor = Utils.mergeAlpha(Color.BLACK.getRGB(), Math.round(120.0f * visibility));

        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        try {
            enableClosestEnemyAlphaBlending();
            RenderUtils.drawRoundedRectangle(
                    left,
                    top,
                    right,
                    bottom,
                    7.0f,
                    backgroundColor
            );

            for (int i = 0; i < renderedEntries.size(); i++) {
                EnemyHudEntry entry = renderedEntries.get(i);
                float rowY = textY + i * rowHeight;

                enableClosestEnemyAlphaBlending();
                mc.fontRendererObj.drawString(
                        entry.name,
                        textX + contentOffset,
                        rowY,
                        contentColor,
                        true
                );

                float distanceWidth = mc.fontRendererObj.getStringWidth(entry.distance);

                mc.fontRendererObj.drawString(
                        entry.distance,
                        distanceX + maxDistanceWidth - distanceWidth + contentOffset,
                        rowY,
                        contentColor,
                        true
                );

                if (entry.heldItem != null) {
                    renderHeldItem(entry.heldItem, itemX + contentOffset, rowY - 2.0f, contentOpacity);
                }
            }
        }
        finally {
            GL11.glPopAttrib();
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        }

        return new HudBoxBounds(left, top, right, bottom);
    }

    private void enableClosestEnemyAlphaBlending() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private float updateClosestEnemyVisibility(List<EnemyHudEntry> entries) {
        if (!entries.isEmpty()) {
            lastClosestEnemyEntries = new ArrayList<>(entries);
            if (!closestEnemyVisibilityInitialized) {
                closestEnemyVisibilityAnimation.reset(1.0f);
                closestEnemyVisibilityInitialized = true;
            }
            else if (Float.compare(closestEnemyVisibilityAnimation.getTarget(), 1.0f) != 0) {
                closestEnemyVisibilityAnimation.setTarget(1.0f);
            }
        }
        else {
            if (lastClosestEnemyEntries.isEmpty()) {
                return 0.0f;
            }
            if (!closestEnemyVisibilityInitialized) {
                closestEnemyVisibilityAnimation.reset(1.0f);
                closestEnemyVisibilityInitialized = true;
            }
            if (Float.compare(closestEnemyVisibilityAnimation.getTarget(), 0.0f) != 0) {
                if (closestEnemySizeInitialized) {
                    closestEnemyWidthAnimation.reset(closestEnemyWidthAnimation.getValue());
                    closestEnemyHeightAnimation.reset(closestEnemyHeightAnimation.getValue());
                }
                closestEnemyVisibilityAnimation.setTarget(0.0f);
            }
        }

        float visibility = closestEnemyVisibilityAnimation.getValue();
        if (entries.isEmpty() && visibility <= 0.0f) {
            lastClosestEnemyEntries = Collections.emptyList();
        }
        return visibility;
    }

    private float updateClosestEnemySelection(List<EnemyHudEntry> entries) {
        List<Integer> selection = new ArrayList<>(entries.size());
        for (EnemyHudEntry entry : entries) {
            selection.add(entry.entityId);
        }

        if (closestEnemySelection == null) {
            closestEnemySelection = selection;
            closestEnemyContentAnimation.reset(1.0f);
        }
        else if (!closestEnemySelection.equals(selection)) {
            closestEnemySelection = selection;
            if (selection.isEmpty()) {
                closestEnemyContentAnimation.reset(1.0f);
            }
            else {
                closestEnemyContentAnimation.reset(0.0f);
                closestEnemyContentAnimation.setTarget(1.0f);
            }
        }

        return closestEnemyContentAnimation.getValue();
    }

    private void updateClosestEnemySize(float width, float height) {
        if (!closestEnemySizeInitialized) {
            closestEnemyWidthAnimation.reset(width);
            closestEnemyHeightAnimation.reset(height);
            closestEnemySizeInitialized = true;
            return;
        }

        if (Float.compare(closestEnemyWidthAnimation.getTarget(), width) != 0) {
            closestEnemyWidthAnimation.setTarget(width);
        }

        if (Float.compare(closestEnemyHeightAnimation.getTarget(), height) != 0) {
            closestEnemyHeightAnimation.setTarget(height);
        }
    }

    private static final class SmoothFloatAnimation {
        private final long durationMs;
        private float from;
        private float target;
        private long startedAt;

        private SmoothFloatAnimation(long durationMs) {
            this.durationMs = durationMs;
        }

        private void reset(float value) {
            from = value;
            target = value;
            startedAt = 0L;
        }

        private void setTarget(float value) {
            from = getValue();
            target = value;
            startedAt = System.currentTimeMillis();
        }

        private float getValue() {
            if (startedAt == 0L) {
                return target;
            }

            float progress = (float) (System.currentTimeMillis() - startedAt) / durationMs;
            if (progress >= 1.0f) {
                reset(target);
                return target;
            }

            progress = Math.max(0.0f, progress);
            float eased = progress * progress * (3.0f - 2.0f * progress);
            return from + (target - from) * eased;
        }

        private float getTarget() {
            return target;
        }
    }

    private static final class EnemyHudEntry {
        private final int entityId;
        private final String name;
        private final String distance;
        private final ItemStack heldItem;

        private EnemyHudEntry(int entityId, String name, String distance, ItemStack heldItem) {
            this.entityId = entityId;
            this.name = name;
            this.distance = distance;
            this.heldItem = heldItem;
        }
    }
}
