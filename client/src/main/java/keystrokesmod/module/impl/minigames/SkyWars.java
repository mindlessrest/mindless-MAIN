package keystrokesmod.module.impl.minigames;

import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.event.UseItemEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class SkyWars extends Module {
    private static final String[] CLOSEST_ENEMY_MODES = new String[]{"Player"};

    public SliderSetting closestEnemy;
    public ButtonSetting strengthIndicator;
    public ButtonSetting onlyAuraHostileMobs;
    public ButtonSetting renderTimeWarp;

    public Map<EntityPlayer, Long> strengthPlayers = new HashMap<>();
    private Map<String, SpawnEggInfo> entitySpawnQueue = new LinkedHashMap<>(); // type name, spawn info
    private Map<Vec3, Long> timeWarpPositions = new LinkedHashMap<>(); // position when thrown, time when thrown
    public List<Integer> spawnedMobs = new ArrayList<>(); // entity id

    private final int STRENGTH_COLOR = new Color(255, 0, 0).getRGB();
    private final int TIME_WARP_COLOR = new Color(210, 0, 255, 64).getRGB();

    private String[] KILL_MESSAGES = new String[] {" by ", " to ", " with ", " of ", " from ", " knight ", " for "};

    private boolean thrownPearl;

    private float closestEnemyPosX = Float.NaN;
    private float closestEnemyPosY = Float.NaN;
    private float closestEnemyRelativePosX = Float.NaN;
    private float closestEnemyRelativePosY = Float.NaN;

    /**
     * A global variable used to determine if the current skywars game you are in is a teams mode or not
     */
    public static boolean isSkyWarsTeams = false;

    public SkyWars() {
        super("Sky Wars", category.minigames);
        this.registerSetting(closestEnemy = new SliderSetting("Closest enemy", true, 0, CLOSEST_ENEMY_MODES));
        this.registerSetting(new ButtonSetting("Edit positions", () -> mc.displayGuiScreen(new EditPositionScreen())));
        this.registerSetting(onlyAuraHostileMobs = new ButtonSetting("Only aura hostile mobs", true));
        this.registerSetting(renderTimeWarp = new ButtonSetting("Render time warp", true));
        this.registerSetting(strengthIndicator = new ButtonSetting("Strength indicator", true));
    }

    @Override
    public void onDisable() {
        this.clear();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!strengthIndicator.isToggled() || !Utils.nullCheck() || strengthPlayers.isEmpty() || Utils.getSkyWarsStatus() != 2) {
            return;
        }
        int customMode = getCustomMode();
        if (customMode == 2) {
            return;
        }
        isSkyWarsTeams = customMode == 1;
        long duration = isSkyWarsTeams ? 2000 : 5000;
        ArrayList<EntityPlayer> keysList = new ArrayList<>(strengthPlayers.keySet());
        for (EntityPlayer entityPlayer : keysList) {
            long storedTime = strengthPlayers.get(entityPlayer);
            long timePassed = System.currentTimeMillis() - storedTime;
            if (timePassed < duration && !AntiBot.isBot(entityPlayer)) {
                continue;
            }
            strengthPlayers.remove(entityPlayer);
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (e.type == 2 || !Utils.nullCheck()) {
            return;
        }
        String stripped = Utils.stripColor(e.message.getUnformattedText());
        if (stripped.isEmpty()) {
            return;
        }
        if (stripped.equals("You will be warped back in 3 seconds!") && thrownPearl) {
            timeWarpPositions.put(new Vec3(mc.thePlayer.lastTickPosX, mc.thePlayer.lastTickPosY, mc.thePlayer.lastTickPosZ), System.currentTimeMillis());
            thrownPearl = false;
            return;
        }
        if (strengthIndicator.isToggled() && Utils.getSkyWarsStatus() == 2) {
            if (getCustomMode() == 2) { // lab, then no
                return;
            }
            if (stripped.endsWith(".") && Arrays.stream(KILL_MESSAGES).anyMatch(stripped::contains)) {
                String[] parts = stripped.split(" ");
                for (String part : parts) {
                    if (!part.endsWith(".")) {
                        continue;
                    }
                    String name = part.substring(0, part.length() - 1);
                    for (EntityPlayer entity : mc.theWorld.playerEntities) {
                        if (!entity.getName().trim().equals(name) || entity == mc.thePlayer) {
                            continue;
                        }
                        strengthPlayers.put(entity, System.currentTimeMillis());
                        break;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || Utils.getSkyWarsStatus() != 2) {
            return;
        }
        if (strengthIndicator.isToggled()) {
            for (EntityPlayer entityPlayer : strengthPlayers.keySet()) {
                if (AntiBot.isBot(entityPlayer)) {
                    continue;
                }
                RenderUtils.renderEntity(entityPlayer, 2, 0, 0, STRENGTH_COLOR, false);
            }
        }
        if (renderTimeWarp.isToggled()) {
            Iterator<Map.Entry<Vec3, Long>> iterator = this.timeWarpPositions.entrySet().iterator();
            long currentTime = System.currentTimeMillis();

            while (iterator.hasNext()) {
                Map.Entry<Vec3, Long> entry = iterator.next();
                Vec3 position = entry.getKey();
                long timeThrown = entry.getValue();

                if (currentTime - timeThrown >= 3050) {
                    iterator.remove();
                }
                else {
                    RenderUtils.drawPlayerBoundingBox(position, TIME_WARP_COLOR);
                }
            }
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            clear();
        }
        else {
            if (e.entity != null) {
                if (Utils.getSkyWarsStatus() != 2) {
                    return;
                }
                String entityClassName = e.entity.getClass().getSimpleName();
                if (entitySpawnQueue.containsKey(entityClassName)) {
                    Vec3 spawnPosition = new Vec3(e.entity.posX, e.entity.posY, e.entity.posZ);
                    SpawnEggInfo eggInfo = entitySpawnQueue.get(entityClassName);
                    if (eggInfo.spawnPos.distanceTo(spawnPosition) > 3 || Utils.timeBetween(mc.thePlayer.ticksExisted, eggInfo.tickSpawned) > 60) { // 3 seconds or not at spawn point then not own mob
                        return;
                    }
                    if (!entitySpawnQueue.remove(entityClassName, eggInfo)) {
                        return;
                    }
                    spawnedMobs.add(e.entity.getEntityId());
                }
            }
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement p = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (p.getPlacedBlockDirection() != 255 && p.getStack() != null && p.getStack().getItem() != null) {
                if (!(p.getStack().getItem() instanceof ItemMonsterPlacer)) {
                    return;
                }
                Class<? extends Entity> oclass = EntityList.stringToClassMapping.get(ItemMonsterPlacer.getEntityName(p.getStack()));
                if (oclass == null) {
                    return;
                }
                entitySpawnQueue.put(oclass.getSimpleName(), new SpawnEggInfo(p.getPosition(), mc.thePlayer.ticksExisted));
            }
        }
    }

    @SubscribeEvent
    public void onUseItem(UseItemEvent e) {
        if (e.usedItemStack != null && e.usedItemStack.getItem() instanceof ItemEnderPearl && Utils.getSkyWarsStatus() == 2) {
            ItemStack stack = e.usedItemStack;
            if (Utils.stripString(stack.getDisplayName()).equals("Time Warp Pearl")) {
                thrownPearl = true;
            }
            else {
                if (stack.getDisplayName().startsWith("§b§l")) {
                    List<String> toolTip = stack.getTooltip(mc.thePlayer, true);
                    if (toolTip != null && toolTip.size() > 1 && Utils.stripString(toolTip.get(1)).contains("Teleports you back to your")) {
                        thrownPearl = true;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck() || closestEnemy.getInput() == -1
                || Utils.getSkyWarsStatus() != 2 || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        EntityPlayer enemy = findClosestEnemy();
        if (enemy == null) {
            return;
        }

        double distance = Math.round(mc.thePlayer.getDistanceToEntity(enemy));
        String text = enemy.getDisplayName().getFormattedText() + " §r"
                + Utils.formatColor(getDistanceColor(distance)) + Utils.asWholeNum(distance) + "m§r";
        ScaledResolution resolution = new ScaledResolution(mc);
        syncClosestEnemyPosition(resolution);
        drawHudBox(text, closestEnemyPosX, closestEnemyPosY);
    }

    private HudBoxBounds drawHudBox(String text, float x, float textY) {
        float horizontalPadding = 3.0f;
        float verticalPadding = 4.0f;
        float textX = x + horizontalPadding;
        float textWidth = mc.fontRendererObj.getStringWidth(text);
        float left = x - 10.0f;
        float top = textY - verticalPadding;
        float right = textX + textWidth + horizontalPadding + 1.0f;
        float bottom = textY + mc.fontRendererObj.FONT_HEIGHT + verticalPadding - 1.0f;
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 7.0f, 2013265920);
        mc.fontRendererObj.drawString(text, textX, textY, Color.WHITE.getRGB(), true);
        return new HudBoxBounds(left, top, right, bottom);
    }

    private void clear() {
        strengthPlayers.clear();
        spawnedMobs.clear();
        entitySpawnQueue.clear();
        timeWarpPositions.clear();
        thrownPearl = false;
    }

    public static boolean onlyAuraHostiles() {
        return ModuleManager.skyWars != null && ModuleManager.skyWars.isEnabled() && ModuleManager.skyWars.onlyAuraHostileMobs.isToggled() && Utils.getSkyWarsStatus() == 2;
    }

    private EntityPlayer findClosestEnemy() {
        EntityPlayer closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || !player.isEntityAlive() || player.isSpectator()
                    || AntiBot.isBot(player) || Utils.isTeammate(player) || Utils.isFriended(player)) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = player;
            }
        }
        return closest;
    }

    private String getDistanceColor(double distance) {
        if (distance < 10) return "&c";
        if (distance < 25) return "&6";
        if (distance < 50) return "&e";
        if (distance > 100) return "&2";
        return "&a";
    }

    public float getClosestEnemyPosX() {
        syncClosestEnemyPosition();
        return closestEnemyPosX;
    }

    public float getClosestEnemyPosY() {
        syncClosestEnemyPosition();
        return closestEnemyPosY;
    }

    public void setClosestEnemyAbsolutePosition(float absoluteX, float absoluteY) {
        ScaledResolution resolution = new ScaledResolution(mc);
        closestEnemyPosX = absoluteX;
        closestEnemyPosY = absoluteY;
        closestEnemyRelativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        closestEnemyRelativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
    }

    public void resetClosestEnemyPosition() {
        ScaledResolution resolution = new ScaledResolution(mc);
        setClosestEnemyAbsolutePosition(0.0f,
                resolution.getScaledHeight() / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f);
    }

    private void syncClosestEnemyPosition() {
        syncClosestEnemyPosition(new ScaledResolution(mc));
    }

    private void syncClosestEnemyPosition(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(closestEnemyRelativePosX) || Float.isNaN(closestEnemyRelativePosY)) {
            if (Float.isNaN(closestEnemyPosX) || Float.isNaN(closestEnemyPosY)) {
                closestEnemyPosX = 0.0f;
                closestEnemyPosY = scaledHeight / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f;
            }
            closestEnemyRelativePosX = closestEnemyPosX / scaledWidth;
            closestEnemyRelativePosY = closestEnemyPosY / scaledHeight;
        }
        closestEnemyPosX = closestEnemyRelativePosX * scaledWidth;
        closestEnemyPosY = closestEnemyRelativePosY * scaledHeight;
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

    private class EditPositionScreen extends GuiScreen {
        private GuiButtonExt resetPosition;
        private HudBoxBounds bounds;
        private boolean dragging;
        private float actualX;
        private float actualY;
        private float dragStartX;
        private float dragStartY;
        private int lastMouseX;
        private int lastMouseY;

        @Override
        public void initGui() {
            this.buttonList.add(resetPosition = new GuiButtonExt(1, width - 95, height - 25, 90, 20, "Reset position"));
            syncClosestEnemyPosition(new ScaledResolution(mc));
            syncEditorPosition();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(mc);
            if (!dragging) {
                syncClosestEnemyPosition(resolution);
                syncEditorPosition();
            }
            drawRect(0, 0, width, height, 0xB2000000);
            setClosestEnemyAbsolutePosition(actualX, actualY);
            bounds = drawHudBox("Closest player: §a16m", actualX, actualY);
            String message = "Drag the box to reposition it.";
            int messageX = resolution.getScaledWidth() / 2 - fontRendererObj.getStringWidth(message) / 2;
            int messageY = resolution.getScaledHeight() / 2 - 10;
            RenderUtils.drawColoredString(message, '-', messageX, messageY, 2L, 0L, true, fontRendererObj);
            try {
                handleInput();
            } catch (IOException ignored) {
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            if (button != 0) return;
            if (dragging) {
                actualX = dragStartX + mouseX - lastMouseX;
                actualY = dragStartY + mouseY - lastMouseY;
            } else if (bounds != null && bounds.contains(mouseX, mouseY)) {
                dragging = true;
                dragStartX = actualX;
                dragStartY = actualY;
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            if (state == 0) dragging = false;
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == resetPosition) {
                dragging = false;
                resetClosestEnemyPosition();
                syncEditorPosition();
            }
        }

        private void syncEditorPosition() {
            actualX = closestEnemyPosX;
            actualY = closestEnemyPosY;
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }

    public int getCustomMode() {
        List<String> sidebar = Utils.getSidebarLines();
        if (sidebar.isEmpty()) {
            return -1;
        }
        for (String line : sidebar) {
            line = Utils.stripColor(line);
            if (line.startsWith("Teams left: ")) {
                return 1;
            }
            else if (line.startsWith("Lab: ") || line.startsWith("Mode: Mini")) {
                return 2;
            }
        }
        return -1;
    }

    public static class SpawnEggInfo {
        public Vec3 spawnPos;
        public int tickSpawned;

        public SpawnEggInfo(BlockPos spawnPos, int tickSpawned) {
            this.spawnPos = new Vec3(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            this.tickSpawned = tickSpawned;
        }
    }
}
