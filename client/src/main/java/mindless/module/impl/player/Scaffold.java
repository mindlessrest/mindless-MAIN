package mindless.module.impl.player;

import java.awt.Color;
import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.RightClickDelayTickEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import mindless.utility.shader.RoundedUtils;
import mindless.module.impl.render.HUD;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import org.lwjgl.opengl.GL20;
import java.awt.Color;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

public class Scaffold extends Module {
    private static final ItemBlock PLACEHOLDER = new ItemBlock(Blocks.tnt);
    private static final int BPS_WINDOW_MS = 3000;
    private static final int TIMESTAMP_RING = 512;

    private final SliderSetting rotationSpeed;
    private final SliderSetting sprint;
    private final ButtonSetting keepY;
    private final ButtonSetting eagle;
    private final SliderSetting eagleSafety;
    private final ButtonSetting showBlockCount;
    private final ButtonSetting switchBack;
    private final ButtonSetting editPosition;

    private BlockPos previewPos;
    private EnumFacing previewFace;
    private BlockPos queuedPos;
    private EnumFacing queuedFace;
    private Vec3 queuedVec;
    private boolean placeQueued;

    private float lastYaw, lastPitch;
    private boolean lastRotsValid;

    private boolean eagleActive;

    private int wdBlocksPlaced;
    private float wdOverrideYaw = Float.NaN;
    private float wdOverrideSpeed = Float.NaN;

    private final long[] timestamps = new long[TIMESTAMP_RING];
    private int tsHead, tsCount;

    /** Sixteen for the icon itself, three of padding either side. */
    private static final float BADGE_SIZE = 22.0F;
    private static final float BADGE_GAP = 7.0F;
    private static final float BADGE_RADIUS = 6.0F;
    private static final int BADGE_COLOUR = 0x66000000;
    private static final float PANEL_PAD_X = 7.0F;
    private static final float PANEL_PAD_Y = 6.0F;
    private static final float BAR_HEIGHT = 2.5F;
    private static final float BAR_GAP = 3.5F;
    private static final float MIN_CONTENT_WIDTH = 78.0F;
    private static final long POP_DURATION_MS = 200L;
    /**
     * Where the capacity bar reads full.
     *
     * <p>Two stacks rather than one. A bridge is not in trouble at sixty-four blocks and a bar that
     * is already pinned at the top says nothing; anchoring it here leaves the top half of its
     * travel to describe a comfortable supply and the bottom half to describe running out.
     */
    private static final int BAR_FULL_BLOCKS = 128;

    private float overlayScale = 0f;
    private long overlayPopStart = -1L;
    private boolean overlayVisible = false;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    /**
     * Counters shown on the overlay, eased toward the real ones.
     *
     * <p>Blocks come off the stack in ones and BPS is a windowed average that steps whenever a
     * placement leaves the window, so both jump. Read as numbers that is fine; drawn as a bar and
     * a decimal it is a twitch, and a twitching HUD element reads as broken rather than live.
     */
    private float displayedBlocks = Float.NaN;
    private float displayedBps;
    private long lastOverlayNanos;

    /** The hotbar slot held when the module was switched on, so it can be given back. */
    private int previousSlot = -1;

    public Scaffold() {
        super("Scaffold", category.player);
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 180, 1, 360, 1));
        this.registerSetting(sprint = new SliderSetting("Sprint", 0, new String[]{"Off", "Legit", "Watchdog"}));
        this.registerSetting(keepY = new ButtonSetting("Keep Y", false));
        this.registerSetting(eagle = new ButtonSetting("Eagle", false));
        this.registerSetting(eagleSafety = new SliderSetting("Eagle safety", " tick", 1, 1, 3, 0.1));
        this.registerSetting(showBlockCount = new ButtonSetting("Show block count", false));
        this.registerSetting(switchBack = new ButtonSetting("Switch back", true));
        this.registerSetting(editPosition = new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
    }

    @Override
    public void onEnable() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        // Remembered before the first placement moves the hand off it. Bridging is something you
        // drop into for a second in the middle of a fight, and coming out of it holding a stack of
        // wool instead of the sword you went in with is a death.
        previousSlot = Utils.nullCheck() ? mc.thePlayer.inventory.currentItem : -1;
        previewPos = null;
        previewFace = null;
        queuedPos = null;
        queuedFace = null;
        queuedVec = null;
        placeQueued = false;
        lastRotsValid = false;
        eagleActive = false;
        wdBlocksPlaced = 0;
        wdOverrideYaw = Float.NaN;
        wdOverrideSpeed = Float.NaN;
    }

    @Override
    public void onDisable() {
        previewPos = null;
        previewFace = null;
        queuedPos = null;
        queuedFace = null;
        queuedVec = null;
        placeQueued = false;
        lastRotsValid = false;
        if (eagleActive) {
            setShiftOverride(false);
            eagleActive = false;
        }
        restorePreviousSlot();
    }

    /**
     * Puts the hand back on whatever it was holding before the module took it.
     *
     * <p>Only when the slot actually moved. Someone who picked their blocks by hand and then
     * switched the module on is already holding what they want; snapping them back to a slot they
     * left on purpose would be the same bug in the other direction.
     */
    private void restorePreviousSlot() {
        int slot = previousSlot;
        previousSlot = -1;

        if (!switchBack.isToggled() || slot < 0 || slot > 8 || !Utils.nullCheck()) {
            return;
        }
        if (mc.thePlayer.inventory.currentItem == slot) {
            return;
        }

        mc.thePlayer.inventory.currentItem = slot;
    }

    @Override
    public void guiUpdate() {
        editPosition.setVisible(showBlockCount.isToggled(), this);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;

        float baseYaw = getBaseYaw();

        if (!Float.isNaN(wdOverrideYaw) && (int) sprint.getInput() == 2) {
            baseYaw = wdOverrideYaw;
            float curYaw = lastRotsValid ? lastYaw : RotationUtils.serverRotations[0];
            float diff = Math.abs(MathHelper.wrapAngleTo180_float(wdOverrideYaw - curYaw));
            if (diff < 5f) {
                wdOverrideYaw = Float.NaN;
                wdOverrideSpeed = Float.NaN;
            }
        }

        BlockData best = findBestPlacement();
        boolean willFall = Utils.isEdgeOfBlock() && mc.thePlayer.motionY < 0.3;

        if (best == null) {
            previewPos = null;
            previewFace = null;
            float[] rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                    lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                    baseYaw, 82f);
            rots = RotationUtils.fixRotation(rots[0], rots[1],
                    RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
            e.setYaw(rots[0]);
            e.setPitch(rots[1]);
            lastYaw = rots[0];
            lastPitch = rots[1];
            lastRotsValid = true;
            return;
        }

        previewPos = best.pos;
        previewFace = best.face;

        Item item = getBlockItem();
        if (item == null) return;

        float[] rots;
        if (willFall) {
            float[] solved = getRotationsForFace(best.pos, best.face, baseYaw);
            if (solved != null) {
                rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                        lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                        baseYaw, solved[1]);
                rots[0] = baseYaw;
            } else {
                rots = getFreeRotationsForFace(best.pos, best.face);
                rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                        lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                        rots[0], rots[1]);
            }
        } else {
            rots = applySpeedCap(lastRotsValid ? lastYaw : baseYaw,
                    lastRotsValid ? lastPitch : mc.thePlayer.rotationPitch,
                    baseYaw, 82f);
        }

        float[] fixed = RotationUtils.fixRotation(rots[0], rots[1],
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

        e.setYaw(willFall ? (getRotationsForFace(best.pos, best.face, baseYaw) != null ? baseYaw : fixed[0]) : fixed[0]);
        e.setPitch(fixed[1]);

        lastYaw = e.yaw != null ? e.yaw : fixed[0];
        lastPitch = e.pitch != null ? e.pitch : fixed[1];
        lastRotsValid = true;

        float useYaw = e.yaw != null ? e.yaw : fixed[0];
        float usePitch = e.pitch != null ? e.pitch : fixed[1];

        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = Utils.getLookVec(useYaw, usePitch);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);

        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(best.pos) && mop.sideHit == best.face) {
            queuedPos = best.pos;
            queuedFace = best.face;
            queuedVec = computeHitVec(best.pos, best.face);
            placeQueued = true;
        } else {
            placeQueued = false;
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) return;

        boolean placed = false;
        if (placeQueued && queuedPos != null && queuedFace != null && queuedVec != null) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() instanceof ItemBlock) {
                if (!keepY.isToggled() || queuedFace != EnumFacing.UP) {
                    mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                            queuedPos, queuedFace, queuedVec);
                    mc.thePlayer.swingItem();
                    placed = true;
                    recordPlacement();
                }
            }
        }
        placeQueued = false;

        updateEagle(placed);

        int sprintMode = (int) sprint.getInput();
        if (sprintMode == 0) return;

        if (placed && sprintMode == 2) {
            wdBlocksPlaced++;
            if (wdBlocksPlaced >= 3) {
                wdOverrideYaw = mc.thePlayer.rotationYaw;
                wdOverrideSpeed = 2.2f * 18f;
                wdBlocksPlaced = 0;
            }
        }

        if (sprintMode == 2) {
            float serverYaw = RotationUtils.serverRotations[0];
            float diff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw)
                    - MathHelper.wrapAngleTo180_float(serverYaw));
            if (diff > 90f) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
                mc.thePlayer.setSprinting(false);
            } else {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
            }
        } else if (!placed) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        }
    }

    @SubscribeEvent
    public void onRightClickDelay(RightClickDelayTickEvent e) {
        if (!Utils.nullCheck() || !mc.inGameHasFocus) return;
        if (!Utils.isBindDown(mc.gameSettings.keyBindUseItem)) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;
        try {
            java.lang.reflect.Field f = net.minecraft.client.Minecraft.class.getDeclaredField("field_71467_ac");
            f.setAccessible(true);
            f.setInt(mc, 0);
        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (previewPos == null) return;
        int color = 0x4000AAFF;
        RenderUtils.renderBlock(previewPos, color, true, false);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (!showBlockCount.isToggled()) return;
        if (mc.currentScreen != null) return;

        int blocks = getTotalBlocks();
        boolean shouldShow = blocks > 0;

        if (shouldShow && !overlayVisible) {
            overlayVisible = true;
            overlayPopStart = System.currentTimeMillis();
        } else if (!shouldShow && overlayVisible) {
            if (overlayPopStart > 0 && overlayScale <= 0.01f) {
                overlayVisible = false;
                overlayPopStart = -1L;
                return;
            }
            if (overlayPopStart > 0 && overlayScale > 0.99f) {
                overlayPopStart = System.currentTimeMillis();
            }
        }

        float targetScale = shouldShow ? 1f : 0f;
        if (overlayPopStart > 0) {
            float progress = Math.min(1f, (System.currentTimeMillis() - overlayPopStart) / (float) POP_DURATION_MS);
            overlayScale = shouldShow ? easeOutBack(progress) : 1f - progress;
        }
        if (overlayScale <= 0.01f) return;

        syncPosition();
        drawOverlay(getDisplayBlock(), blocks, computeBps(), posX, posY, overlayScale);
    }

    /**
     * The pop curve: overshoots a little past full size before settling.
     */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    /**
     * The block-count overlay.
     *
     * <p>Two lines of dropshadowed vanilla text next to an icon is what a debug readout looks
     * like, not what the rest of this client looks like. Everything else on the HUD -- the
     * scoreboard, the session card, the alerts -- is a blurred panel with a hairline rim, in the
     * client font, and this now matches: the count is the thing you glance at so it gets a large
     * face of its own, the rate sits beside it as a secondary reading, and the bar underneath says
     * how much is left without having to be read at all.
     */
    private void drawOverlay(ItemStack badgeStack, int blocks, float bps, float left, float top, float popScale) {
        advanceOverlayCounters(blocks, bps);

        // The pop is applied to the geometry rather than to the modelview.
        //
        // Every rounded shape here is drawn by a shader that is told where the rectangle is in
        // screen pixels and works out its own corners and coverage from that. A matrix scale moves
        // the quad those uniforms are painted onto but leaves the uniforms where they were, so the
        // panel would be sliced by a rounded mask sitting somewhere else for the length of the
        // animation. Scaling the coordinates instead keeps the shape and the quad describing the
        // same rectangle.
        float scale = Math.max(0.0F, popScale);

        RavenFontRenderer countFont = countFont();
        RavenFontRenderer labelFont = HUD.getHudFontRenderer();

        int shownBlocks = Math.round(displayedBlocks);
        String countText = Integer.toString(shownBlocks);
        String rateText = String.format("%.1f", displayedBps);
        String rateSuffix = " BPS";

        float countW = countFont.getStringWidth(countText);
        float rateW = labelFont.getStringWidth(rateText) + labelFont.getStringWidth(rateSuffix);
        float labelW = labelFont.getStringWidth("BLOCKS");
        float contentW = Math.max(MIN_CONTENT_WIDTH, Math.max(countW + 12.0F + rateW, labelW + 12.0F + rateW));

        float badgeSpan = badgeStack == null ? 0.0F : BADGE_SIZE + BADGE_GAP;
        float layoutWidth = PANEL_PAD_X * 2.0F + badgeSpan + contentW;
        float layoutHeight = overlayHeight(countFont, labelFont);

        // Grown from the centre, so the panel expands in place instead of sliding out of its
        // anchor on the way in.
        float centerX = left + layoutWidth * 0.5F;
        float centerY = top + layoutHeight * 0.5F;
        float width = layoutWidth * scale;
        float height = layoutHeight * scale;
        float panelLeft = centerX - width * 0.5F;
        float panelTop = centerY - height * 0.5F;
        float radius = 8.0F * mindless.module.impl.theme.ThemeManager.roundingScale() * scale;

        // Shadow first: everything from prepareBlur on is stencilled to the panel and would paint
        // straight over it.
        RoundedUtils.drawRoundShadow(panelLeft, panelTop, width, height, radius, 5.0F * scale, 0x82000000);
        BlurUtils.prepareBlur(panelLeft, panelTop, width, height);
        RoundedUtils.drawRound(panelLeft, panelTop, width, height, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4F, 0.85F, panelLeft - 2.0F, panelTop - 2.0F, width + 4.0F, height + 4.0F);
        RoundedUtils.drawRound(panelLeft, panelTop, width, height, radius, 0x82000000);
        RoundedUtils.drawGradientVertical(panelLeft, panelTop, width, height, radius,
                new Color(255, 255, 255, 20), new Color(255, 255, 255, 4));
        RoundedUtils.drawRoundOutline(panelLeft, panelTop, width, height, radius, 1.0F,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 30));

        // The rounded-rect and blur shaders leave a program bound; glyphs drawn through it come
        // out garbled.
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        // The contents are laid out at full size and scaled by the matrix. Unlike the panel they
        // are ordinary quads, so the matrix carries them correctly and there is no reason to work
        // the pop into every coordinate below.
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-centerX, -centerY, 0.0F);
        try {

        float contentX = left + PANEL_PAD_X + badgeSpan;
        float contentRight = left + layoutWidth - PANEL_PAD_X;
        int countColor = countColour(shownBlocks);

        float countY = top + PANEL_PAD_Y;
        countFont.drawString(countText, contentX, countY, 0xFF000000 | countColor, false);

        // Right-aligned on the same line as the count, so the two readings share a baseline
        // instead of stacking into a column of numbers.
        float rateBaseline = countY + (countFont.getFontHeight() - labelFont.getFontHeight()) * 0.6F;
        float suffixW = labelFont.getStringWidth(rateSuffix);
        float rateX = contentRight - rateW;
        labelFont.drawString(rateText, rateX, rateBaseline, 0xFFE8E8E8, false);
        labelFont.drawString(rateSuffix, contentRight - suffixW, rateBaseline, 0x8AFFFFFF, false);

        float labelY = countY + countFont.getFontHeight() + 1.0F;
        labelFont.drawString("BLOCKS", contentX, labelY, 0x7AFFFFFF, false);

        float barY = labelY + labelFont.getFontHeight() + BAR_GAP;
        drawCapacityBar(contentX, barY, contentRight - contentX, countColor);

        drawHeldBlockBadge(badgeStack, left + PANEL_PAD_X, top + (layoutHeight - BADGE_SIZE) * 0.5F);
        }
        finally {
            GlStateManager.popMatrix();
        }
    }

    /** Height is driven by the fonts, so a larger HUD scale grows the panel with the text. */
    private float overlayHeight(RavenFontRenderer countFont, RavenFontRenderer labelFont) {
        float content = countFont.getFontHeight() + 1.0F + labelFont.getFontHeight() + BAR_GAP + BAR_HEIGHT;
        return Math.max(BADGE_SIZE, content) + PANEL_PAD_Y * 2.0F;
    }

    /** The overlay's footprint, for the drag handle and the editor. */
    private float[] overlaySize(ItemStack badgeStack) {
        RavenFontRenderer countFont = countFont();
        RavenFontRenderer labelFont = HUD.getHudFontRenderer();
        float rateW = labelFont.getStringWidth("0.0") + labelFont.getStringWidth(" BPS");
        float countW = countFont.getStringWidth(Integer.toString(Math.max(0, getTotalBlocks())));
        float labelW = labelFont.getStringWidth("BLOCKS");
        float contentW = Math.max(MIN_CONTENT_WIDTH, Math.max(countW + 12.0F + rateW, labelW + 12.0F + rateW));
        float badgeSpan = badgeStack == null ? 0.0F : BADGE_SIZE + BADGE_GAP;
        return new float[]{PANEL_PAD_X * 2.0F + badgeSpan + contentW, overlayHeight(countFont, labelFont)};
    }

    /** A real larger face for the count, rather than scaling the small one up and blurring it. */
    private static RavenFontRenderer countFont() {
        return FontManager.getHudRenderer(HUD.getSelectedFontName(),
                Math.min(2.0F, HUD.getSelectedFontScale() * 1.55F));
    }

    private static int countColour(int blocks) {
        if (blocks <= 16) return 0xFF5555;
        if (blocks <= 32) return 0xFFAA00;
        if (blocks <= 64) return 0xFFFF55;
        return 0xFFFFFF;
    }

    /** How much of the supply is left, as a bar rather than a number to be read. */
    private void drawCapacityBar(float x, float y, float width, int fillColour) {
        if (width <= 1.0F) return;

        float fraction = Math.max(0.0F, Math.min(1.0F, displayedBlocks / (float) BAR_FULL_BLOCKS));
        float radius = BAR_HEIGHT * 0.5F;
        RoundedUtils.drawRound(x, y, width, BAR_HEIGHT, radius, 0x33FFFFFF);
        float filled = width * fraction;
        if (filled > BAR_HEIGHT) {
            RoundedUtils.drawRound(x, y, filled, BAR_HEIGHT, radius, 0xD8000000 | fillColour);
        }
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Eases the shown counters toward the real ones, per frame rather than per step.
     *
     * <p>Time-based, so the animation takes the same wall-clock time however fast the game is
     * drawing. The block count snaps rather than easing when it jumps a long way -- a fresh stack
     * from the inventory is a new supply, not the old one growing.
     */
    private void advanceOverlayCounters(int blocks, float bps) {
        long now = System.nanoTime();
        float delta = lastOverlayNanos == 0L ? 1.0F / 60.0F
                : Math.max(0.0F, Math.min(0.25F, (now - lastOverlayNanos) / 1_000_000_000.0F));
        lastOverlayNanos = now;

        if (Float.isNaN(displayedBlocks) || Math.abs(blocks - displayedBlocks) > 48.0F) {
            displayedBlocks = blocks;
        }
        else {
            displayedBlocks += (blocks - displayedBlocks) * (1.0F - (float) Math.exp(-delta * 12.0F));
        }
        displayedBps += (bps - displayedBps) * (1.0F - (float) Math.exp(-delta * 9.0F));
    }

    /**
     * The block the scaffold would place, without reaching for it.
     *
     * <p>{@link #getBlockItem} answers the same question but switches the held slot on its way to
     * the answer. That is right when it is about to place; it is wrong from a render pass, where
     * it would be moving the player's hand every frame in order to draw a picture of it.
     */
    private ItemStack getDisplayBlock() {
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held != null && held.getItem() instanceof ItemBlock && held.stackSize > 0) {
            return held;
        }
        int slot = getBestBlockSlot();
        return slot == -1 ? null : mc.thePlayer.inventory.mainInventory[slot];
    }

    /** A rounded slot to the left of the count holding whatever is about to be bridged with. */
    private void drawHeldBlockBadge(ItemStack stack, float badgeX, float badgeY) {
        if (stack == null) {
            return;
        }

        RoundedUtils.drawRound(badgeX, badgeY, BADGE_SIZE, BADGE_SIZE, BADGE_RADIUS, BADGE_COLOUR);
        RoundedUtils.drawRoundOutline(badgeX, badgeY, BADGE_SIZE, BADGE_SIZE, BADGE_RADIUS, 1.0F,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 22));
        GL20.glUseProgram(0);

        int iconX = Math.round(badgeX + (BADGE_SIZE - 16.0F) * 0.5F);
        int iconY = Math.round(badgeY + (BADGE_SIZE - 16.0F) * 0.5F);

        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getRenderItem().zLevel = 0.0F;
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, iconX, iconY);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.popMatrix();

        // Item rendering leaves lighting, colour and texture state however it pleases, and
        // everything drawn after this on the HUD expects them neutral.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
    }

    private void updateEagle(boolean placedThisTick) {
        if (!eagle.isToggled()) {
            if (eagleActive) {
                setShiftOverride(false);
                eagleActive = false;
            }
            return;
        }

        boolean shouldSneak = false;
        if (mc.thePlayer.onGround) {
            int lookahead = (int) eagleSafety.getInput();
            boolean edgeDetected = false;
            for (int i = 0; i <= lookahead; i++) {
                BlockPos checkPos = new BlockPos(
                        mc.thePlayer.posX + mc.thePlayer.motionX * i,
                        mc.thePlayer.posY - 1 + mc.thePlayer.motionY * i,
                        mc.thePlayer.posZ + mc.thePlayer.motionZ * i
                );
                if (mc.theWorld.isAirBlock(checkPos)) {
                    edgeDetected = true;
                    break;
                }
            }
            if (edgeDetected && !placedThisTick) {
                shouldSneak = true;
            }
        }

        if (!shouldSneak && mc.thePlayer.onGround) {
            BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
            if (mc.theWorld.isAirBlock(below)) {
                shouldSneak = true;
            }
        }

        if (shouldSneak != eagleActive) {
            setShiftOverride(shouldSneak);
            eagleActive = shouldSneak;
        }
    }

    private void setShiftOverride(boolean shift) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),
                shift || Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()));
    }

    private BlockData findBestPlacement() {
        EntityPlayerSP player = mc.thePlayer;
        float baseYaw = getBaseYaw();
        BlockPos playerPos = new BlockPos(player);
        BlockPos scanY = playerPos.down();

        double targetX = player.posX + player.motionX;
        double targetZ = player.posZ + player.motionZ;
        double targetY = scanY.getY() + 0.5;

        double existingScore = Double.MAX_VALUE;
        BlockData best = null;
        double bestScore = Double.MAX_VALUE;

        boolean tower = !player.onGround && !keepY.isToggled();
        int lowestLayer = tower ? -1 : 0;

        for (int layer = 0; layer >= lowestLayer; layer--) {
            BlockPos layerPos = scanY.add(0, layer, 0);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = layerPos.add(x, 0, z);
                    IBlockState state = mc.theWorld.getBlockState(pos);

                    if (state.getBlock() == Blocks.air) continue;
                    if (!state.getBlock().isFullCube()) continue;

                    double exDx = (pos.getX() + 0.5) - targetX;
                    double exDz = (pos.getZ() + 0.5) - targetZ;
                    double exDy = (pos.getY() + 0.5) - targetY;
                    double exScore = exDx * exDx + exDz * exDz + exDy * exDy * 0.25;
                    if (exScore < existingScore) existingScore = exScore;

                    java.util.List<EnumFacing> facings = new java.util.ArrayList<>();
                    facings.add(EnumFacing.NORTH);
                    facings.add(EnumFacing.SOUTH);
                    facings.add(EnumFacing.EAST);
                    facings.add(EnumFacing.WEST);
                    if (tower) facings.add(EnumFacing.UP);

                    for (EnumFacing facing : facings) {
                        if (!PLACEHOLDER.canPlaceBlockOnSide(mc.theWorld, pos, facing, mc.thePlayer, mc.thePlayer.getHeldItem()))
                            continue;

                        BlockPos neighbor = pos.offset(facing);
                        IBlockState neighborState = mc.theWorld.getBlockState(neighbor);
                        if (neighborState.getBlock() != Blocks.air) continue;

                        double nbX = neighbor.getX() + 0.5;
                        double nbY = neighbor.getY() + 0.5;
                        double nbZ = neighbor.getZ() + 0.5;
                        double dx = nbX - targetX;
                        double dz = nbZ - targetZ;
                        double dy = nbY - targetY;
                        double score = dx * dx + dz * dz + dy * dy * 0.25;

                        if (score >= bestScore) continue;

                        float[] rots = getRotationsForFace(pos, facing, baseYaw);
                        if (rots == null) rots = getFreeRotationsForFace(pos, facing);

                        Vec3 eye = player.getPositionEyes(1f);
                        Vec3 look = Utils.getLookVec(rots[0], rots[1]);
                        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
                        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eye, end, false, false, true);

                        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) continue;
                        if (!hit.getBlockPos().equals(pos)) continue;
                        if (hit.sideHit != facing) continue;

                        bestScore = score;
                        best = new BlockData(pos, facing);
                    }
                }
            }
        }

        if (best != null && existingScore <= bestScore) return null;
        return best;
    }

    private float getBaseYaw() {
        if (((int) sprint.getInput()) != 0) return mc.thePlayer.rotationYaw;

        int forward = 0, strafe = 0;
        if (mc.gameSettings.keyBindForward.isKeyDown()) forward++;
        if (mc.gameSettings.keyBindBack.isKeyDown()) forward--;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) strafe++;
        if (mc.gameSettings.keyBindRight.isKeyDown()) strafe--;

        float offset;
        if (forward > 0) {
            offset = strafe == 0 ? 180f : (strafe > 0 ? 135f : -135f);
        } else if (forward < 0) {
            offset = strafe == 0 ? 0f : (strafe > 0 ? 45f : -45f);
        } else {
            offset = strafe == 0 ? 180f : (strafe > 0 ? 90f : -90f);
        }
        return mc.thePlayer.rotationYaw + offset;
    }

    private float[] getRotationsForFace(BlockPos pos, EnumFacing facing, float lockedYaw) {
        EntityPlayerSP player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        float yawRad = (float) Math.toRadians(lockedYaw);
        double hx = -Math.sin(yawRad);
        double hz = Math.cos(yawRad);

        double bx0 = pos.getX(), bx1 = bx0 + 1.0;
        double by0 = pos.getY(), by1 = by0 + 1.0;
        double bz0 = pos.getZ(), bz1 = bz0 + 1.0;

        float currentPitch = RotationUtils.serverRotations[1];
        float bestPitch = Float.MAX_VALUE;
        float bestDiff = Float.MAX_VALUE;

        switch (facing) {
            case UP: {
                float p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, bx0 + 0.5, by1, bz0 + 0.5);
                if (!Float.isNaN(p)) {
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                    if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                }
                for (double cx : new double[]{bx0 + 0.1, bx1 - 0.1}) {
                    for (double cz : new double[]{bz0 + 0.1, bz1 - 0.1}) {
                        p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, cx, by1, cz);
                        if (!Float.isNaN(p)) {
                            float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                            if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                        }
                    }
                }
                break;
            }
            case DOWN: {
                float p = pitchToHitPoint(eyeX, eyeY, eyeZ, hx, hz, bx0 + 0.5, by0, bz0 + 0.5);
                if (!Float.isNaN(p)) {
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                    if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                }
                break;
            }
            case NORTH: {
                float[] cands = pitchesToHitZPlane(eyeX, eyeY, eyeZ, hx, hz, bz0, bx0, bx1, by0, by1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case SOUTH: {
                float[] cands = pitchesToHitZPlane(eyeX, eyeY, eyeZ, hx, hz, bz1, bx0, bx1, by0, by1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case WEST: {
                float[] cands = pitchesToHitXPlane(eyeX, eyeY, eyeZ, hx, hz, bx0, by0, by1, bz0, bz1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            case EAST: {
                float[] cands = pitchesToHitXPlane(eyeX, eyeY, eyeZ, hx, hz, bx1, by0, by1, bz0, bz1);
                for (float p : cands) {
                    if (!Float.isNaN(p)) {
                        float diff = Math.abs(MathHelper.wrapAngleTo180_float(p - currentPitch));
                        if (diff < bestDiff) { bestDiff = diff; bestPitch = p; }
                    }
                }
                break;
            }
            default: return null;
        }

        if (bestPitch == Float.MAX_VALUE) return null;
        bestPitch = MathHelper.clamp_float(bestPitch, -90f, 90f);

        float[] last = {lastRotsValid ? lastYaw : lockedYaw,
                lastRotsValid ? lastPitch : currentPitch};
        float[] target = {lockedYaw, bestPitch};
        float[] fixed = RotationUtils.fixRotation(target[0], target[1], last[0], last[1]);
        fixed[0] = lockedYaw;
        return fixed;
    }

    private float[] getFreeRotationsForFace(BlockPos pos, EnumFacing facing) {
        EntityPlayerSP player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;

        double faceCX = pos.getX() + 0.5 + facing.getFrontOffsetX() * 0.5;
        double faceCY = pos.getY() + 0.5 + facing.getFrontOffsetY() * 0.5;
        double faceCZ = pos.getZ() + 0.5 + facing.getFrontOffsetZ() * 0.5;

        double dx = faceCX - eyeX;
        double dy = faceCY - eyeY;
        double dz = faceCZ - eyeZ;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        pitch = MathHelper.clamp_float(pitch, -90f, 90f);

        return RotationUtils.fixRotation(yaw, pitch,
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
    }

    private float[] applySpeedCap(float curYaw, float curPitch, float targetYaw, float targetPitch) {
        float maxStep = !Float.isNaN(wdOverrideSpeed) && (int) sprint.getInput() == 2
                ? wdOverrideSpeed : (float) rotationSpeed.getInput();
        if (maxStep <= 1) return new float[]{targetYaw, targetPitch};
        if (maxStep >= 360) return new float[]{targetYaw, targetPitch};

        float dy = MathHelper.wrapAngleTo180_float(targetYaw - curYaw);
        float dp = targetPitch - curPitch;
        float dist = (float) Math.sqrt(dy * dy + dp * dp);
        if (dist <= maxStep) return new float[]{targetYaw, targetPitch};

        float scale = maxStep / dist;
        return new float[]{curYaw + dy * scale, curPitch + dp * scale};
    }

    private static float pitchToHitPoint(double eyeX, double eyeY, double eyeZ,
                                         double hx, double hz,
                                         double tx, double ty, double tz) {
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double tCosp;
        if (Math.abs(hx) > Math.abs(hz)) {
            if (Math.abs(hx) < 1e-6) return Float.NaN;
            tCosp = dx / hx;
        } else {
            if (Math.abs(hz) < 1e-6) return Float.NaN;
            tCosp = dz / hz;
        }
        if (tCosp <= 0) return Float.NaN;
        double tanPitch = -dy / tCosp;
        return (float) Math.toDegrees(Math.atan(tanPitch));
    }

    private static float[] pitchesToHitZPlane(double eyeX, double eyeY, double eyeZ,
                                              double hx, double hz, double faceZ,
                                              double xMin, double xMax,
                                              double yMin, double yMax) {
        if (Math.abs(hz) < 1e-6) return new float[0];
        double tCosp = (faceZ - eyeZ) / hz;
        if (tCosp <= 0) return new float[0];

        double[] sampleY = {yMin + 0.1, yMin + (yMax - yMin) * 0.3, (yMin + yMax) * 0.5,
                yMin + (yMax - yMin) * 0.7, yMax - 0.1};
        float[] results = new float[sampleY.length];
        int count = 0;
        for (double sy : sampleY) {
            double dy = sy - eyeY;
            double hitX = eyeX + hx * tCosp;
            if (hitX < xMin || hitX > xMax) continue;
            double tanPitch = -dy / tCosp;
            results[count++] = (float) Math.toDegrees(Math.atan(tanPitch));
        }
        float[] trimmed = new float[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return trimmed;
    }

    private static float[] pitchesToHitXPlane(double eyeX, double eyeY, double eyeZ,
                                              double hx, double hz, double faceX,
                                              double yMin, double yMax,
                                              double zMin, double zMax) {
        if (Math.abs(hx) < 1e-6) return new float[0];
        double tCosp = (faceX - eyeX) / hx;
        if (tCosp <= 0) return new float[0];

        double[] sampleY = {yMin + 0.1, yMin + (yMax - yMin) * 0.3, (yMin + yMax) * 0.5,
                yMin + (yMax - yMin) * 0.7, yMax - 0.1};
        float[] results = new float[sampleY.length];
        int count = 0;
        for (double sy : sampleY) {
            double dy = sy - eyeY;
            double hitZ = eyeZ + hz * tCosp;
            if (hitZ < zMin || hitZ > zMax) continue;
            double tanPitch = -dy / tCosp;
            results[count++] = (float) Math.toDegrees(Math.atan(tanPitch));
        }
        float[] trimmed = new float[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return trimmed;
    }

    private Item getBlockItem() {
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || held.stackSize <= 1) {
            int slot = getBestBlockSlot();
            if (slot != -1) mc.thePlayer.inventory.currentItem = slot;
        }
        held = mc.thePlayer.inventory.getCurrentItem();
        return held != null ? held.getItem() : null;
    }

    private int getBestBlockSlot() {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 1) {
                if (stack.stackSize > bestCount) {
                    bestCount = stack.stackSize;
                    best = i;
                }
            }
        }
        if (best == -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
                if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                    best = i;
                    break;
                }
            }
        }
        return best;
    }

    private void recordPlacement() {
        long now = System.currentTimeMillis();
        timestamps[tsHead % TIMESTAMP_RING] = now;
        tsHead++;
        tsCount = Math.min(tsCount + 1, TIMESTAMP_RING);
    }

    private float computeBps() {
        if (tsCount == 0) return 0f;
        long now = System.currentTimeMillis();
        long cutoff = now - BPS_WINDOW_MS;
        int inWindow = 0;
        int total = Math.min(tsCount, TIMESTAMP_RING);
        int start = tsHead - total;
        if (start < 0) start += TIMESTAMP_RING;
        for (int i = 0; i < total; i++) {
            int idx = (start + i) % TIMESTAMP_RING;
            if (timestamps[idx] > cutoff) inWindow++;
        }
        return inWindow / (BPS_WINDOW_MS / 1000f);
    }

    private int getTotalBlocks() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private void syncPosition() {
        syncPosition(new ScaledResolution(mc));
    }

    private void syncPosition(ScaledResolution sr) {
        int w = Math.max(1, sr.getScaledWidth());
        int h = Math.max(1, sr.getScaledHeight());
        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            if (Float.isNaN(posX) || Float.isNaN(posY)) {
                posX = w / 2f - 20;
                posY = h / 2f - 20;
            }
            relativePosX = posX / w;
            relativePosY = posY / h;
        }
        posX = relativePosX * w;
        posY = relativePosY * h;
    }

    public void setRelativePosition(float nx, float ny) {
        relativePosX = nx;
        relativePosY = ny;
        syncPosition();
    }

    public void resetPosition() {
        relativePosX = Float.NaN;
        relativePosY = Float.NaN;
        posX = Float.NaN;
        posY = Float.NaN;
        syncPosition();
    }

    private static Vec3 computeHitVec(BlockPos pos, EnumFacing face) {
        return new Vec3(
                pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5
        );
    }

    private static class BlockData {
        final BlockPos pos;
        final EnumFacing face;
        BlockData(BlockPos pos, EnumFacing face) { this.pos = pos; this.face = face; }
    }

    private class EditScreen extends GuiScreen {
        private boolean dragging;
        private float ax, ay, lax, lay;
        private int lmx, lmy;
        private GuiButtonExt resetBtn;

        @Override
        public void initGui() {
            super.initGui();
            buttonList.add(resetBtn = new GuiButtonExt(1, width - 90, height - 25, 85, 20, "Reset"));
            syncPosition(new ScaledResolution(mc));
            ax = posX;
            ay = posY;
        }

        @Override
        public void drawScreen(int mx, int my, float pt) {
            ScaledResolution sr = new ScaledResolution(mc);
            if (!dragging) { syncPosition(sr); ax = posX; ay = posY; }
            drawRect(0, 0, width, height, 0xB2000000);

            posX = ax; posY = ay;
            // Previewed with a stack in hand even when the inventory is empty, so the panel being
            // positioned is the same size as the one that will be shown in a game.
            ItemStack badgeStack = getDisplayBlock();
            int blocks = Math.max(1, getTotalBlocks());
            drawOverlay(badgeStack, blocks, computeBps(), posX, posY, 1.0F);

            try { handleInput(); } catch (IOException ignored) {}
            super.drawScreen(mx, my, pt);
        }

        @Override
        protected void mouseClickMove(int mx, int my, int btn, long time) {
            super.mouseClickMove(mx, my, btn, time);
            if (btn != 0) return;
            if (dragging) {
                ax = lax + (mx - lmx);
                ay = lay + (my - lmy);
            } else {
                float[] size = overlaySize(getDisplayBlock());
                float tw = size[0];
                float th = size[1];
                if (mx >= posX - 2 && mx <= posX + tw + 2 && my >= posY - 2 && my <= posY + th + 2) {
                    dragging = true;
                    lmx = mx; lmy = my; lax = ax; lay = ay;
                }
            }
        }

        @Override
        protected void mouseReleased(int mx, int my, int state) {
            super.mouseReleased(mx, my, state);
            if (state == 0) dragging = false;
        }

        @Override
        public void actionPerformed(GuiButton btn) {
            if (btn == resetBtn) { resetPosition(); ax = posX; ay = posY; }
        }

        @Override
        public boolean doesGuiPauseGame() { return false; }
    }
}
