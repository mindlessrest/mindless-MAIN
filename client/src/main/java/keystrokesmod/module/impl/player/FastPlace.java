package keystrokesmod.module.impl.player;

import keystrokesmod.event.RightClickDelayTickEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.runtime.AccessorBridge;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.BlockListSetting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ItemListSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

public class FastPlace extends Module {
    public SliderSetting tickDelay;
    public SliderSetting activationTime;
    public ButtonSetting blocksOnly, pitchCheck, showBlockCount;
    public ButtonSetting editPosition;
    public ButtonSetting ignoredHeldItemsToggle;
    public ItemListSetting ignoredHeldItems;
    public ButtonSetting blockBlacklistToggle;
    public BlockListSetting blockBlacklist;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private long rightClickStartTime;
    private Timer fadeTimer;
    private Timer fadeInTimer;
    private float previousAlpha;

    public FastPlace() {
        super("Fast Place", Module.category.player, 0);
        this.registerSetting(tickDelay = new SliderSetting("Tick delay", 1.0, 0.0, 3.0, 1.0));
        this.registerSetting(activationTime = new SliderSetting("Activation time", "ms", 0.0, 0.0, 100.0, 5.0));
        this.registerSetting(blocksOnly = new ButtonSetting("Blocks only", true));
        this.registerSetting(pitchCheck = new ButtonSetting("Pitch check", false));
        this.registerSetting(showBlockCount = new ButtonSetting("Show block count", false));
        this.registerSetting(editPosition = new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
        this.registerSetting(ignoredHeldItemsToggle = new ButtonSetting("Held item blacklist", false, "Ignore held items", "Restrict held items", "Allow while holding"));
        this.registerSetting(ignoredHeldItems = new ItemListSetting("Held items", "Items"));
        this.registerSetting(blockBlacklistToggle = new ButtonSetting("Block blacklist", false, "Blocks.Block blacklist"));
        this.registerSetting(blockBlacklist = new BlockListSetting("Blacklisted blocks", "Block blacklist", "Blocks.Block blacklist", "Blocks.Blacklisted blocks"));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        rightClickStartTime = 0L;
    }

    @Override
    public void guiUpdate() {
        editPosition.setVisible(showBlockCount.isToggled(), this);
        ignoredHeldItems.setVisible(ignoredHeldItemsToggle.isToggled(), this);
        blockBlacklist.setVisible(blockBlacklistToggle.isToggled(), this);
    }

    @SubscribeEvent
    public void onRightClickDelayTick(RightClickDelayTickEvent e) {
        if (!Utils.nullCheck() || !mc.inGameHasFocus) {
            rightClickStartTime = 0L;
            return;
        }

        if (!isRightClickActive()) {
            rightClickStartTime = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (rightClickStartTime == 0L) {
            rightClickStartTime = now;
        }

        if (!canFastPlace(now, true)) {
            return;
        }

        int delay = (int) tickDelay.getInput();
        if (delay == 0) {
            AccessorBridge.Minecraft_setRightClickDelayTimer(mc, 0);
        }
        else {
            if (delay == 4) {
                return;
            }
            if (AccessorBridge.Minecraft_getRightClickDelayTimer(mc) > delay) {
                AccessorBridge.Minecraft_setRightClickDelayTimer(mc, delay);
            }
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (!Utils.nullCheck() || !(e.getPacket() instanceof C08PacketPlayerBlockPlacement)) {
            return;
        }

        C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) e.getPacket();
        if (packet.getPlacedBlockDirection() != 255) {
            return;
        }

        ItemStack packetStack = packet.getStack();
        if (packetStack == null || !(packetStack.getItem() instanceof ItemBlock)) {
            return;
        }

        if (!canFastPlace(System.currentTimeMillis(), true)) {
            return;
        }

        if (Math.random() < 0.7) {
            if (e.isCancelable()) {
                e.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) {
            return;
        }
        if (!Utils.nullCheck()) {
            return;
        }
        if (mc.currentScreen != null) {
            fadeTimer = null;
            fadeInTimer = null;
            previousAlpha = 0.0f;
            return;
        }
        boolean fastPlaceEnabled = ModuleManager.fastPlace != null && ModuleManager.fastPlace.isEnabled();
        boolean showBlockCountToggled = fastPlaceEnabled && ModuleManager.fastPlace.showBlockCount.isToggled();
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        boolean holdingPlaceableBlock = heldItem != null && heldItem.getItem() instanceof ItemBlock && Utils.canBePlaced((ItemBlock) heldItem.getItem());

        boolean shouldShow = showBlockCountToggled && mc.currentScreen == null && holdingPlaceableBlock && canFastPlace(System.currentTimeMillis(), false);

        if (shouldShow) {
            if (fadeTimer != null) {
                fadeTimer = null;
                (fadeInTimer = new Timer(150)).start();
            }
            else if (fadeInTimer == null && previousAlpha < 255.0f) {
                (fadeInTimer = new Timer(150)).start();
            }

            float alpha;
            if (fadeInTimer != null) {
                alpha = fadeInTimer.getValueFloat(10, 255, 1);
                if (alpha >= 255.0f) {
                    alpha = 255.0f;
                    fadeInTimer = null;
                }
            } else {
                alpha = 255.0f;
            }
            previousAlpha = alpha;

            renderBlockCount((int) alpha);
        } else {
            if (previousAlpha <= 10.0f) {
                fadeTimer = null;
                fadeInTimer = null;
                previousAlpha = 0.0f;
                return;
            }

            if (fadeTimer == null) {
                (fadeTimer = new Timer(150)).start();
                fadeInTimer = null;
            }

            float alpha = 255.0f - fadeTimer.getValueInt(0, 255, 1);
            if (alpha < 0.0f) {
                alpha = 0.0f;
            }
            previousAlpha = alpha;

            if (alpha > 10.0f) {
                renderBlockCount((int) alpha);
            }
        }
    }

    public float getPosX() {
        syncPosition();
        return posX;
    }

    public float getPosY() {
        syncPosition();
        return posY;
    }

    public float getRelativePosX() {
        syncPosition();
        return relativePosX;
    }

    public float getRelativePosY() {
        syncPosition();
        return relativePosY;
    }

    public void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPosition();
    }

    public void setAbsolutePosition(float absoluteX, float absoluteY) {
        setAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    public void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        posX = absoluteX;
        posY = absoluteY;
        int w = Math.max(1, resolution.getScaledWidth());
        int h = Math.max(1, resolution.getScaledHeight());
        relativePosX = absoluteX / w;
        relativePosY = absoluteY / h;
    }

    public void resetPosition() {
        relativePosX = Float.NaN;
        relativePosY = Float.NaN;
        posX = Float.NaN;
        posY = Float.NaN;
        syncPosition();
    }

    private void syncPosition() {
        syncPosition(new ScaledResolution(mc));
    }

    private void syncPosition(ScaledResolution resolution) {
        int w = Math.max(1, resolution.getScaledWidth());
        int h = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            if (Float.isNaN(posX) || Float.isNaN(posY)) {
                posX = (float) w / 2 + 9;
                posY = (float) h / 2 - (float) mc.fontRendererObj.FONT_HEIGHT / 2 + 1;
            }
            relativePosX = posX / w;
            relativePosY = posY / h;
        }
        posX = relativePosX * w;
        posY = relativePosY * h;
    }

    private void renderBlockCount(int alpha) {
        renderBlockCount(alpha, false);
    }

    private void renderBlockCount(int alpha, boolean editing) {
        int blocks = getTotalBlocks();
        if (editing && blocks == 0) {
            blocks = 64;
        }
        String color = "§";
        if (blocks <= 5) {
            color += "c";
        }
        else if (blocks <= 15) {
            color += "6";
        }
        else if (blocks <= 25) {
            color += "e";
        }
        else {
            color = "";
        }
        int colorAlpha = Utils.mergeAlpha(-1, alpha);
        ScaledResolution scaledResolution = new ScaledResolution(mc);
        syncPosition(scaledResolution);

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawStringWithShadow(color + blocks + " §rblock" + (blocks == 1 ? "" : "s"),
                posX,
                posY,
                colorAlpha
        );
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private boolean isBlockedHoverBlock() {
        if (!blockBlacklistToggle.isToggled() || mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }

        BlockPos hoveredPos = mc.objectMouseOver.getBlockPos();
        if (hoveredPos == null) {
            return false;
        }

        IBlockState state = mc.theWorld.getBlockState(hoveredPos);
        Block hoveredBlock = state.getBlock();
        if (hoveredBlock == null || Block.blockRegistry.getNameForObject(hoveredBlock) == null) {
            return false;
        }

        String registryId = Block.blockRegistry.getNameForObject(hoveredBlock).toString();
        int meta = hoveredBlock.getMetaFromState(state);
        String storageId = meta != 0 ? registryId + ":" + meta : registryId;
        return blockBlacklist.contains(storageId) || blockBlacklist.contains(registryId);
    }

    private boolean isRightClickActive() {
        return Utils.isBindDown(mc.gameSettings.keyBindUseItem) || mc.thePlayer.isUsingItem();
    }

    private boolean canFastPlace(long now, boolean requireActivationDelay) {
        if (blocksOnly.isToggled()) {
            ItemStack item = mc.thePlayer.getHeldItem();
            if (item == null || !(item.getItem() instanceof ItemBlock)) {
                return false;
            }
        }
        if (pitchCheck.isToggled() && mc.thePlayer.rotationPitch < 70.0f) {
            return false;
        }
        if (ignoredHeldItemsToggle.isToggled() && ignoredHeldItems.matches(mc.thePlayer.getHeldItem())) {
            return false;
        }
        if (isBlockedHoverBlock()) {
            return false;
        }
        return !requireActivationDelay || now - rightClickStartTime >= (long) activationTime.getInput();
    }

    public int getTotalBlocks() {
        int totalBlocks = 0;
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return 0;
        }
        for (int i = 0; i < 9; ++i) {
            final ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && Utils.canBePlaced((ItemBlock) stack.getItem()) && stack.stackSize > 0) {
                totalBlocks += stack.stackSize;
            }
        }
        return totalBlocks;
    }

    private class EditScreen extends GuiScreen {
        private GuiButtonExt resetBtn;
        private boolean dragging;
        private float actualX, actualY;
        private float lastActualX, lastActualY;
        private int lastMouseX, lastMouseY;

        @Override
        public void initGui() {
            super.initGui();
            buttonList.add(resetBtn = new GuiButtonExt(1, width - 90, height - 25, 85, 20, "Reset position"));
            syncPosition(new ScaledResolution(mc));
            actualX = posX;
            actualY = posY;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(mc);
            if (!dragging) {
                syncPosition(resolution);
                actualX = posX;
                actualY = posY;
            }

            drawRect(0, 0, width, height, 0xB2000000);
            setAbsolutePosition(actualX, actualY, resolution);
            renderBlockCount(255, true);
            actualX = posX;
            actualY = posY;

            String message = "Edit the position by dragging.";
            int textX = resolution.getScaledWidth() / 2 - fontRendererObj.getStringWidth(message) / 2;
            int textY = resolution.getScaledHeight() / 2 - 20;
            RenderUtils.drawColoredString(message, '-', textX, textY, 2L, 0L, true, mc.fontRendererObj);

            try {
                handleInput();
            } catch (IOException ignored) {
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button != 0) return;

            if (dragging) {
                actualX = lastActualX + (mouseX - lastMouseX);
                actualY = lastActualY + (mouseY - lastMouseY);
            } else {
                int blocks = getTotalBlocks();
                if (blocks == 0) blocks = 64;
                String text = blocks + " blocks";
                int textWidth = fontRendererObj.getStringWidth(text);
                int textHeight = fontRendererObj.FONT_HEIGHT;
                float minX = posX - 2;
                float minY = posY - 2;
                float maxX = posX + textWidth + 2;
                float maxY = posY + textHeight + 2;
                if (mouseX >= minX && mouseX <= maxX && mouseY >= minY && mouseY <= maxY) {
                    dragging = true;
                    lastMouseX = mouseX;
                    lastMouseY = mouseY;
                    lastActualX = actualX;
                    lastActualY = actualY;
                }
            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                dragging = false;
            }
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == resetBtn) {
                resetPosition();
                actualX = posX;
                actualY = posY;
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
