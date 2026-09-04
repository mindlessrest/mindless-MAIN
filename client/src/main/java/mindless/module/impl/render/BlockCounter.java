package mindless.module.impl.render;

import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.gui.MindlessButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.io.IOException;

/**
 * The bridging block counter, lifted out of Scaffold so it stands on its own.
 *
 * It used to be a Scaffold sub-setting, which meant the readout only existed while a cheat was
 * running -- no use to anyone bridging by hand, and nothing a script could ask for. The count and
 * the rate are now sourced independently: blocks come straight off the inventory, and the rate
 * comes off outgoing C08 placements, so every placement source feeds it (Scaffold, Bridge Assist,
 * Fast Place, or your own right click).
 */
public class BlockCounter extends Module {

    private static final int BPS_WINDOW_MS = 3000;
    private static final int TIMESTAMP_RING = 512;

    private static final float BADGE_SIZE = 24.0F;
    private static final float BADGE_GAP = 9.0F;
    private static final float BADGE_RADIUS = 7.0F;
    private static final int BADGE_COLOUR = 0x59000000;
    private static final float PANEL_PAD_X = 10.0F;
    private static final float PANEL_PAD_Y = 8.0F;
    private static final float BAR_HEIGHT = 3.0F;
    private static final float BAR_GAP = 6.0F;
    private static final float LABEL_GAP = 2.0F;
    private static final float MIN_CONTENT_WIDTH = 84.0F;
    private static final long POP_DURATION_MS = 200L;

    private static final String[] ANCHOR_MODES = {"Screen", "Held item"};
    private static final int ANCHOR_SCREEN = 0;
    private static final int ANCHOR_HAND = 1;
    /**
     * Where the held item sits in front of the camera, in blocks: right of centre, below it, and
     * out in front. Vanilla's own first person item transform, near enough to project against.
     */
    private static final float HAND_RIGHT = 0.42f;
    private static final float HAND_UP = -0.34f;
    private static final float HAND_FORWARD = 0.62f;
    /** How far the hand drops while an item is being brought up. */
    private static final float HAND_EQUIP_DROP = 0.50f;
    /** Gap between the panel's right edge and the hand it hangs off. */
    private static final float HAND_GAP = 6.0f;
    /** Shoulder offset used when the item is really in the world rather than in a hand matrix. */
    private static final float SHOULDER_OUT = 0.38f;
    private static final float SHOULDER_UP = 1.18f;

    /**
     * How long the panel takes to cover most of the distance to the hand.
     *
     * Short enough that it reads as fixed to the item rather than trailing it, long enough that a
     * frame of projection noise never shows. Anything past a quarter of the screen is a camera
     * cut, not a movement, and snaps instead.
     */
    private static final float ANCHOR_SMOOTH_TIME = 0.045f;
    private static final float ANCHOR_SNAP_FRACTION = 0.25f;

    private static final String[] BAR_MODES = {"Auto", "Fixed"};
    private static final int BAR_AUTO = 0;

    private static BlockCounter instance;

    private static final String[] SHOW_MODES = {"While placing", "Holding blocks", "Always"};
    private static final int SHOW_PLACING = 0;
    private static final int SHOW_HOLDING = 1;
    private static final int SHOW_ALWAYS = 2;

    private final SliderSetting showWhen;
    private final SliderSetting anchorMode;
    private final SliderSetting handOffsetX;
    private final SliderSetting handOffsetY;
    private final SliderSetting hideAfter;
    private final SliderSetting counterFont;
    private final SliderSetting counterScale;
    private final SliderSetting barMode;
    private final SliderSetting barFull;
    private final ButtonSetting wholeInventory;
    private final ButtonSetting showBadge;
    private final ButtonSetting showRate;
    private final ButtonSetting showBar;
    private final ButtonSetting alwaysShow;
    private final ButtonSetting editPosition;

    private final long[] timestamps = new long[TIMESTAMP_RING];
    private int tsHead;
    private int tsCount;

    private float overlayScale;
    private float overlayFromScale;
    private long overlayPopStart = -1L;
    private boolean overlayVisible;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private float displayedBlocks = Float.NaN;
    private float displayedCapacity = Float.NaN;
    private float displayedBps;
    private long lastOverlayNanos;

    private RenderUtils.ProjectionContext projectionContext;

    private float anchorX = Float.NaN;
    private float anchorY = Float.NaN;
    private long anchorNanos;
    private long lastPlacement;
    private long scriptHoldUntil;
    private int peakBlocks;

    public BlockCounter() {
        super("Block Counter", "Counts your placeable blocks and how fast you place.", category.render);
        instance = this;
        this.registerSetting(showWhen = new SliderSetting("Show when", SHOW_HOLDING, SHOW_MODES));
        this.registerSetting(anchorMode = new SliderSetting("Anchor", ANCHOR_SCREEN, ANCHOR_MODES));
        this.registerSetting(handOffsetX = new SliderSetting("Hand offset X", 14, -120, 120, 1));
        this.registerSetting(handOffsetY = new SliderSetting("Hand offset Y", -34, -120, 120, 1));
        this.registerSetting(hideAfter = new SliderSetting("Hide after", "s", 2.0, 0.5, 10.0, 0.5));
        this.registerSetting(counterFont = new SliderSetting("Font", 0, ModuleFont.options()));
        this.registerSetting(counterScale = new SliderSetting("Scale", "x", 1.0, 0.5, 3.0, 0.05));
        this.registerSetting(wholeInventory = new ButtonSetting("Count whole inventory", false));
        this.registerSetting(showBadge = new ButtonSetting("Show block icon", true));
        this.registerSetting(showRate = new ButtonSetting("Show place rate", true));
        this.registerSetting(showBar = new ButtonSetting("Show bar", true));
        this.registerSetting(barMode = new SliderSetting("Bar scale", BAR_AUTO, BAR_MODES));
        this.registerSetting(barFull = new SliderSetting("Bar full at", " blocks", 128, 16, 512, 8));
        this.registerSetting(alwaysShow = new ButtonSetting("Show when empty", false));
        this.registerSetting(editPosition = new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
    }

    public static BlockCounter get() {
        return instance;
    }

    @Override
    public void guiUpdate() {
        barMode.setVisible(showBar.isToggled(), this);
        barFull.setVisible(showBar.isToggled() && (int) barMode.getInput() != BAR_AUTO, this);
        int mode = (int) showWhen.getInput();
        hideAfter.setVisible(mode == SHOW_PLACING, this);
        alwaysShow.setVisible(mode == SHOW_ALWAYS, this);
        handOffsetX.setVisible((int) anchorMode.getInput() == ANCHOR_HAND, this);
        handOffsetY.setVisible((int) anchorMode.getInput() == ANCHOR_HAND, this);
        editPosition.setVisible((int) anchorMode.getInput() != ANCHOR_HAND, this);
    }

    /** Blocks the counter is willing to count, for scripts and for the overlay alike. */
    public int getBlockCount() {
        if (!Utils.nullCheck()) return 0;
        int limit = wholeInventory.isToggled() ? mc.thePlayer.inventory.mainInventory.length : 9;
        int count = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    /** Placements per second over the last three seconds. */
    public float getBlocksPerSecond() {
        if (tsCount == 0) return 0f;
        long cutoff = System.currentTimeMillis() - BPS_WINDOW_MS;
        int inWindow = 0;
        int total = Math.min(tsCount, TIMESTAMP_RING);
        int start = tsHead - total;
        if (start < 0) start += TIMESTAMP_RING;
        for (int i = 0; i < total; i++) {
            if (timestamps[(start + i) % TIMESTAMP_RING] > cutoff) inWindow++;
        }
        return inWindow / (BPS_WINDOW_MS / 1000f);
    }

    /**
     * Every placement is a C08 with a real face; side 255 is the "used item in the air" form and
     * places nothing. Counting the packet rather than any one module keeps the rate honest no
     * matter what put the block down.
     */
    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) return;
        C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) event.getPacket();
        if (packet.getPlacedBlockDirection() == 255) return;
        ItemStack stack = packet.getStack();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;
        recordPlacement();
    }

    private void recordPlacement() {
        lastPlacement = System.currentTimeMillis();
        timestamps[tsHead % TIMESTAMP_RING] = lastPlacement;
        tsHead++;
        tsCount = Math.min(tsCount + 1, TIMESTAMP_RING);
    }

    /**
     * Keeps the panel up for a while longer, for scripts that bridge without going through a
     * normal block placement. Anything that sends a real C08 already feeds recordPlacement and
     * needs no help.
     */
    public void showFor(long millis) {
        scriptHoldUntil = Math.max(scriptHoldUntil, System.currentTimeMillis() + Math.max(0L, millis));
    }

    /**
     * Whether the overlay wants to be on screen.
     *
     * Holding blocks is nearly always true in Bed Wars, so keying off that alone put the panel up
     * for the whole game. The default keys off actual placements instead and falls away once you
     * stop, which is the only time the count and the rate are worth reading.
     */
    /**
     * The block to show in the Dynamic Island, or null when there is nothing worth showing.
     *
     * Deliberately reuses shouldShow rather than re-deciding: the island appearing and the panel
     * appearing must agree, or holding a block would light one and not the other. This answers
     * even while the module is drawing nothing itself, because the island is a separate surface
     * with its own visibility.
     */
    public ItemStack islandBlock() {
        // Deliberately not gated on isEnabled. The island has its own toggle, and requiring the
        // panel to be switched on as well meant turning the panel off silently took the chip with
        // it -- two switches for one thing, one of them invisible.
        if (!Utils.nullCheck() || !shouldShow()) return null;
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held != null && held.getItem() instanceof ItemBlock && held.stackSize > 0) {
            return held;
        }
        return getDisplayBlock();
    }

    /** Blocks currently counted, for the island. */
    public int islandCount() {
        return getBlockCount();
    }

    private boolean shouldShow() {
        if (System.currentTimeMillis() < scriptHoldUntil) {
            return true;
        }
        int blocks = getBlockCount();
        switch ((int) showWhen.getInput()) {
            case SHOW_HOLDING: {
                // Strictly what is in your hand. Falling back to "any blocks anywhere" here made
                // the mode indistinguishable from Always.
                ItemStack held = mc.thePlayer.inventory.getCurrentItem();
                return held != null && held.getItem() instanceof ItemBlock && held.stackSize > 0;
            }
            case SHOW_ALWAYS:
                return blocks > 0 || alwaysShow.isToggled();
            case SHOW_PLACING:
            default:
                return lastPlacement > 0
                        && System.currentTimeMillis() - lastPlacement <= (long) (hideAfter.getInput() * 1000.0);
        }
    }

    @Override
    public void onEnable() {
        overlayScale = 0f;
        overlayPopStart = -1L;
        overlayVisible = false;
        displayedBlocks = Float.NaN;
        displayedCapacity = Float.NaN;
        displayedBps = 0f;
        lastOverlayNanos = 0L;
        lastPlacement = 0L;
        scriptHoldUntil = 0L;
        peakBlocks = 0;
    }

    /**
     * Captures the world projection while the world matrices are still current.
     *
     * Player ESP captures one too, but only while it is enabled, so borrowing it left the anchor
     * reading a stale matrix whenever that module was off. Only runs with the hand anchor
     * selected, so it costs nothing otherwise.
     */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
    public void onRenderWorld(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || (int) anchorMode.getInput() != ANCHOR_HAND) return;
        projectionContext = RenderUtils.captureProjectionContext(projectionContext,
                mindless.utility.ScaledResolutionCache.get().getScaleFactor());
    }

    @Override
    public String getInfo() {
        int blocks = getBlockCount();
        return blocks > 0 ? String.valueOf(blocks) : "";
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null) return;

        int blocks = getBlockCount();
        trackPeak(blocks);
        boolean wanted = shouldShow();

        // The old version re-armed the fade timer every frame it was still above 0.99 scale. One
        // frame never moves it far enough to fall below that, so the timer reset forever and the
        // panel never faded -- it just sat there and re-popped when a block came back out.
        // The target is latched once and animated from wherever the scale happens to be.
        long now = System.currentTimeMillis();
        if (wanted != overlayVisible) {
            overlayVisible = wanted;
            overlayPopStart = now;
            overlayFromScale = overlayScale;
        }

        float progress = overlayPopStart <= 0L
                ? 1f
                : Math.min(1f, (now - overlayPopStart) / (float) POP_DURATION_MS);
        if (overlayVisible) {
            overlayScale = overlayFromScale + (1f - overlayFromScale) * easeOutBack(progress);
        }
        else {
            overlayScale = overlayFromScale * (1f - progress);
        }

        if (overlayScale <= 0.005f) {
            overlayScale = 0f;
            return;
        }

        syncPosition();
        float[] anchor = anchorPosition(ev.renderTickTime);
        drawOverlay(getDisplayBlock(), blocks, getBlocksPerSecond(), anchor[0], anchor[1], overlayScale);
    }

    /**
     * Remembers the most you have been carrying so the bar has something true to measure against.
     *
     * A fixed full point cannot describe a stack it does not reach: at 256 blocks against a
     * ceiling of 128 the bar simply pinned full and stopped moving, which is the opposite of what
     * it is for. Restocking raises the mark, spending it drains the bar, and running dry clears
     * it so the next stack starts from full again.
     */
    private void trackPeak(int blocks) {
        if (blocks <= 0) {
            peakBlocks = 0;
            return;
        }
        if (blocks > peakBlocks) {
            peakBlocks = blocks;
        }
    }

    /** Blocks the bar treats as a full bar. */
    private float barCapacity() {
        if ((int) barMode.getInput() != BAR_AUTO) {
            return Math.max(1.0F, (float) barFull.getInput());
        }
        return Math.max(1.0F, peakBlocks);
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    private void drawOverlay(ItemStack badgeStack, int blocks, float bps, float left, float top, float popScale) {
        advanceOverlayCounters(blocks, bps);

        float scale = Math.max(0.0F, popScale);
        if (scale <= 0.001F) return;

        // The user scale is baked into the glyph size rather than applied as a modelview scale,
        // so the text is rasterised at the size it is drawn at instead of being stretched from a
        // smaller atlas. Only the pop animation still goes through the matrix -- it is transient,
        // and feeding a per-frame value into the font cache is what makes atlases thrash.
        float ui = overlayUserScale();
        MindlessFontRenderer countFont = countFont(ui);
        MindlessFontRenderer labelFont = labelFont(ui);

        // The number is read, not watched -- show the real one. Only the bar and the rate are
        // eased, where a moving value is the point.
        String countText = Integer.toString(Math.max(0, blocks));
        String rateText = String.format("%.1f", displayedBps);
        String rateSuffix = " BPS";

        float badgeSize = BADGE_SIZE * ui;
        float badgeGap = BADGE_GAP * ui;
        float padX = PANEL_PAD_X * ui;
        float padY = PANEL_PAD_Y * ui;
        float labelGap = LABEL_GAP * ui;
        float barGap = BAR_GAP * ui;
        float barHeight = BAR_HEIGHT * ui;

        float badgeSpan = badgeStack == null ? 0.0F : badgeSize + badgeGap;
        float layoutWidth = padX * 2.0F + badgeSpan
                + overlayContentWidth(countFont, labelFont, countText, ui);
        float layoutHeight = overlayHeight(countFont, labelFont, ui);

        // RoundedUtils resolves its shapes against gl_FragCoord, so the geometry has to arrive
        // already scaled -- a modelview scale would move the quad and leave the shape behind.
        // Only text and the item icon go inside a scaled matrix.
        // Whole-pixel origin. Half-pixel placement smears every glyph in the panel, which reads
        // as a low-resolution font even when the atlas is fine.
        left = Math.round(left);
        top = Math.round(top);

        float centerX = left + layoutWidth * 0.5F;
        float centerY = top + layoutHeight * 0.5F;
        float width = layoutWidth * scale;
        float height = layoutHeight * scale;
        float panelLeft = centerX - width * 0.5F;
        float panelTop = centerY - height * 0.5F;
        float radius = 9.0F * mindless.module.impl.theme.ThemeManager.roundingScale() * ui * scale;

        int accent = countColour(blocks);

        // Half the spread and half the opacity. The old drop shadow read as a dark halo
        // around the panel rather than a shadow under it.
        RoundedUtils.drawRoundShadow(panelLeft, panelTop, width, height, radius, 2.5F * scale, 0x4B000000);
        RoundedUtils.drawRound(panelLeft, panelTop, width, height, radius, 0xF00E0E12);
        RoundedUtils.drawGradientVertical(panelLeft, panelTop, width, height, radius,
                new Color(255, 255, 255, 16), new Color(255, 255, 255, 0));
        // One flat hairline. The old outline faded from transparent to white along its length,
        // which read as a smeared edge rather than a border.
        Color edge = new Color(255, 255, 255, 28);
        RoundedUtils.drawRoundOutline(panelLeft, panelTop, width, height, radius, 1.0F, edge, edge);
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        float contentX = left + padX + badgeSpan;
        float contentRight = left + layoutWidth - padX;
        float countY = top + padY;
        float labelY = countY + countFont.getFontHeight() + labelGap;
        float barY = labelY + labelFont.getFontHeight() + barGap;

        if (showBar.isToggled()) {
            // Bar is a rounded rect, so it is placed in screen space like the panel.
            drawCapacityBar(panelLeft + (contentX - left) * scale,
                    panelTop + (barY - top) * scale,
                    (contentRight - contentX) * scale,
                    barHeight * scale,
                    accent);
        }
        if (badgeStack != null) {
            drawHeldBlockBadge(badgeStack,
                    panelLeft + padX * scale,
                    panelTop + (layoutHeight - badgeSize) * 0.5F * scale,
                    badgeSize * scale, ui * scale);
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-centerX, -centerY, 0.0F);
        try {
            countFont.drawString(countText, contentX, countY, 0xFF000000 | accent, false);

            if (showRate.isToggled()) {
                float rateW = labelFont.getStringWidth(rateText);
                float suffixW = labelFont.getStringWidth(rateSuffix);
                float rateBaseline = countY + (countFont.getFontHeight() - labelFont.getFontHeight()) * 0.5F;
                labelFont.drawString(rateText, contentRight - suffixW - rateW, rateBaseline, 0xFFF2F2F2, false);
                labelFont.drawString(rateSuffix, contentRight - suffixW, rateBaseline, 0x73FFFFFF, false);
            }

            labelFont.drawString("BLOCKS", contentX, labelY, 0x66FFFFFF, false);
        }
        finally {
            GlStateManager.popMatrix();
        }
    }

    private float overlayUserScale() {
        return counterScale == null ? 1.0F : (float) counterScale.getInput();
    }

    private float overlayContentWidth(MindlessFontRenderer countFont,
                                      MindlessFontRenderer labelFont, String countText, float ui) {
        float rateW = showRate.isToggled()
                ? labelFont.getStringWidth("0.0") + labelFont.getStringWidth(" BPS") : 0.0F;
        float countW = countFont.getStringWidth(countText);
        float labelW = labelFont.getStringWidth("BLOCKS");
        return Math.max(MIN_CONTENT_WIDTH * ui, Math.max(countW, labelW) + 14.0F * ui + rateW);
    }

    private float overlayHeight(MindlessFontRenderer countFont, MindlessFontRenderer labelFont, float ui) {
        float content = countFont.getFontHeight() + LABEL_GAP * ui + labelFont.getFontHeight();
        if (showBar.isToggled()) content += (BAR_GAP + BAR_HEIGHT) * ui;
        return Math.max(BADGE_SIZE * ui, content) + PANEL_PAD_Y * 2.0F * ui;
    }

    private float[] overlaySize(ItemStack badgeStack) {
        float ui = overlayUserScale();
        MindlessFontRenderer countFont = countFont(ui);
        MindlessFontRenderer labelFont = labelFont(ui);
        String countText = Integer.toString(Math.max(0, getBlockCount()));
        float badgeSpan = badgeStack == null ? 0.0F : (BADGE_SIZE + BADGE_GAP) * ui;
        return new float[]{
                PANEL_PAD_X * 2.0F * ui + badgeSpan
                        + overlayContentWidth(countFont, labelFont, countText, ui),
                overlayHeight(countFont, labelFont, ui)};
    }

    private MindlessFontRenderer countFont(float ui) {
        return FontManager.getLargeHudRenderer(ModuleFont.nameOf(counterFont),
                HUD.getSelectedFontScale() * 1.55F * ui);
    }

    private MindlessFontRenderer labelFont(float ui) {
        return FontManager.getLargeHudRenderer(ModuleFont.nameOf(counterFont),
                HUD.getSelectedFontScale() * ui);
    }

    private static int countColour(int blocks) {
        if (blocks <= 16) return 0xFF5C5C;
        if (blocks <= 32) return 0xFFA23D;
        if (blocks <= 64) return 0xFFE04D;
        return 0xF2F5FF;
    }

    private void drawCapacityBar(float x, float y, float width, float barHeight, int fillColour) {
        if (width <= 1.0F || barHeight <= 0.1F) return;

        float fraction = Math.max(0.0F, Math.min(1.0F,
                displayedBlocks / Math.max(1.0F, displayedCapacity)));
        float radius = barHeight * 0.5F;
        RoundedUtils.drawRound(x, y, width, barHeight, radius, 0x26FFFFFF);
        float filled = width * fraction;
        if (filled > 0.05F) {
            RoundedUtils.drawRound(x, y, Math.max(filled, barHeight * 0.35F), barHeight, radius,
                    0xE6000000 | fillColour);
        }
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void advanceOverlayCounters(int blocks, float bps) {
        long now = System.nanoTime();
        float delta = lastOverlayNanos == 0L ? 1.0F / 60.0F
                : Math.max(0.0F, Math.min(0.25F, (now - lastOverlayNanos) / 1_000_000_000.0F));
        lastOverlayNanos = now;

        // Only a restock snaps. Draining always animates, however fast it goes -- the old
        // threshold jumped whenever the count moved by more than 48 in either direction, which is
        // exactly what a fast bridge does.
        if (Float.isNaN(displayedBlocks) || blocks - displayedBlocks > 24.0F) {
            displayedBlocks = blocks;
        }
        else {
            displayedBlocks += (blocks - displayedBlocks) * (1.0F - (float) Math.exp(-delta * 7.0F));
        }
        // The capacity is eased too. Restocking moves the peak instantly, and a fill easing up to
        // a mark that has already jumped reads as the bar lurching.
        float capacity = barCapacity();
        if (Float.isNaN(displayedCapacity) || displayedCapacity <= 0.0F) {
            displayedCapacity = capacity;
        }
        else {
            displayedCapacity += (capacity - displayedCapacity) * (1.0F - (float) Math.exp(-delta * 7.0F));
        }
        displayedBps += (bps - displayedBps) * (1.0F - (float) Math.exp(-delta * 9.0F));
    }

    private ItemStack getDisplayBlock() {
        if (!showBadge.isToggled() || !Utils.nullCheck()) return null;
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held != null && held.getItem() instanceof ItemBlock && held.stackSize > 0) {
            return held;
        }
        int limit = wholeInventory.isToggled() ? mc.thePlayer.inventory.mainInventory.length : 9;
        ItemStack best = null;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack == null || !(stack.getItem() instanceof ItemBlock) || stack.stackSize <= 0) continue;
            if (best == null || stack.stackSize > best.stackSize) best = stack;
        }
        return best;
    }

    private void drawHeldBlockBadge(ItemStack stack, float badgeX, float badgeY,
                                    float badgeSize, float scale) {
        if (stack == null) {
            return;
        }

        float size = badgeSize;
        Color edge = new Color(255, 255, 255, 24);
        RoundedUtils.drawRound(badgeX, badgeY, size, size, BADGE_RADIUS * scale, BADGE_COLOUR);
        RoundedUtils.drawRoundOutline(badgeX, badgeY, size, size, BADGE_RADIUS * scale, 1.0F, edge, edge);
        GL20.glUseProgram(0);

        // renderItemAndEffectIntoGUI only takes integer coordinates, so the icon is scaled with a
        // matrix and drawn at the origin.
        float iconScale = scale * (BADGE_SIZE / 24.0F);
        float iconX = badgeX + (size - 16.0F * iconScale) * 0.5F;
        float iconY = badgeY + (size - 16.0F * iconScale) * 0.5F;

        GlStateManager.pushMatrix();
        GlStateManager.translate(iconX, iconY, 0.0F);
        GlStateManager.scale(iconScale, iconScale, 1.0F);
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getRenderItem().zLevel = 0.0F;
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
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

    public float getPosX() {
        return posX;
    }

    public float getPosY() {
        return posY;
    }

    public float getRelativePosX() {
        return relativePosX;
    }

    public float getRelativePosY() {
        return relativePosY;
    }

    public void setRelativePosition(float nx, float ny) {
        relativePosX = nx;
        relativePosY = ny;
        syncPosition();
    }

    public void setAbsolutePosition(float x, float y) {
        posX = x;
        posY = y;
        relativePosX = Float.NaN;
        relativePosY = Float.NaN;
        syncPosition();
    }

    public void resetPosition() {
        relativePosX = Float.NaN;
        relativePosY = Float.NaN;
        posX = Float.NaN;
        posY = Float.NaN;
        syncPosition();
    }

    /**
     * Where the panel sits this frame.
     *
     * Screen anchoring is the dragged position.
     *
     * Held item follows the item itself. Both camera modes project a point through the same
     * captured world matrices, so the panel is placed by the same maths that placed the item and
     * lands beside it rather than near it.
     *
     * In first person the item is drawn into the hand matrix rather than into the world, so the
     * point is built out of the camera's own basis: right, down and forward from the eye, moved by
     * the equip and swing animations so the panel rides the hand instead of hovering over a fixed
     * patch of screen. In third person the item really is in the world, so the point is the
     * player's shoulder, rotated by their body yaw.
     *
     * Everything here is interpolated with the render partial ticks. It was not before, which is
     * what made the panel judder while you moved: the world drew at the frame's position and the
     * panel drew at the last tick's.
     */
    private float[] anchorPosition(float partialTicks) {
        if ((int) anchorMode.getInput() != ANCHOR_HAND) {
            anchorX = Float.NaN;
            return new float[]{posX, posY};
        }

        float[] size = overlaySize(getDisplayBlock());
        float[] hand = handScreenPosition(partialTicks);
        if (hand == null) {
            return anchorFallback(size);
        }

        float targetX = hand[0] - size[0] - HAND_GAP + (float) handOffsetX.getInput();
        float targetY = hand[1] - size[1] * 0.5f + (float) handOffsetY.getInput();
        return smoothAnchor(targetX, targetY);
    }

    /** Keeps the last good placement when the hand is off screen rather than jumping home. */
    private float[] anchorFallback(float[] size) {
        if (Float.isNaN(anchorX)) {
            return new float[]{posX, posY};
        }
        return new float[]{anchorX, anchorY};
    }

    /**
     * Eases toward the hand at a rate that does not depend on the framerate.
     *
     * A straight per-frame fraction would smooth twice as hard at 60fps as at 120, so the panel
     * would feel like it lagged more the worse the machine. The exponential is the same curve in
     * wall time whatever the frame rate.
     */
    private float[] smoothAnchor(float targetX, float targetY) {
        long now = System.nanoTime();
        float delta = anchorNanos == 0L ? 1f : (now - anchorNanos) / 1.0E9f;
        anchorNanos = now;
        delta = Math.max(0f, Math.min(0.25f, delta));

        float snap = mindless.utility.ScaledResolutionCache.get().getScaledWidth()
                * ANCHOR_SNAP_FRACTION;
        if (Float.isNaN(anchorX)
                || Math.abs(targetX - anchorX) > snap || Math.abs(targetY - anchorY) > snap) {
            anchorX = targetX;
            anchorY = targetY;
            return new float[]{anchorX, anchorY};
        }

        float blend = 1f - (float) Math.exp(-delta / ANCHOR_SMOOTH_TIME);
        anchorX += (targetX - anchorX) * blend;
        anchorY += (targetY - anchorY) * blend;
        return new float[]{anchorX, anchorY};
    }

    /** The held item's position on screen, or null when it is behind the camera. */
    private float[] handScreenPosition(float partialTicks) {
        RenderUtils.ProjectionContext context = projectionContext;
        if (context == null) return null;

        net.minecraft.client.renderer.entity.RenderManager renderManager = mc.getRenderManager();
        double[] point = new double[3];
        boolean firstPerson = mc.gameSettings.thirdPersonView == 0
                && mc.getRenderViewEntity() == mc.thePlayer;
        if (firstPerson) {
            firstPersonHandPoint(partialTicks, point);
        }
        else {
            thirdPersonHandPoint(partialTicks, renderManager, point);
        }

        double[] projected = new double[3];
        if (!RenderUtils.projectTo2D(context, point[0], point[1], point[2], projected)
                || projected[2] <= 0.005 || projected[2] >= 1.0) {
            return null;
        }
        return new float[]{(float) projected[0], (float) projected[1]};
    }

    /** Camera relative, written back as an offset from the render manager's viewer position. */
    private void firstPersonHandPoint(float partialTicks, double[] out) {
        net.minecraft.entity.Entity view = mc.getRenderViewEntity();
        double yaw = Math.toRadians(view.prevRotationYaw
                + (view.rotationYaw - view.prevRotationYaw) * partialTicks);
        double pitch = Math.toRadians(view.prevRotationPitch
                + (view.rotationPitch - view.prevRotationPitch) * partialTicks);

        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);

        double forwardX = -sinYaw * cosPitch;
        double forwardY = -sinPitch;
        double forwardZ = cosYaw * cosPitch;
        double rightX = cosYaw;
        double rightZ = sinYaw;
        // up = forward x right, so it tips with the pitch instead of staying world vertical.
        double upX = forwardY * rightZ;
        double upY = forwardZ * rightX - forwardX * rightZ;
        double upZ = -forwardY * rightX;

        float equip = equippedProgress(partialTicks);
        float swing = (float) Math.sin(mc.thePlayer.getSwingProgress(partialTicks) * Math.PI);

        double right = HAND_RIGHT - swing * 0.10;
        double up = HAND_UP - (1.0f - equip) * HAND_EQUIP_DROP + swing * 0.12;
        double forward = HAND_FORWARD - swing * 0.08;

        out[0] = rightX * right + upX * up + forwardX * forward;
        out[1] = mc.thePlayer.getEyeHeight() + upY * up + forwardY * forward;
        out[2] = rightZ * right + upZ * up + forwardZ * forward;
    }

    private void thirdPersonHandPoint(float partialTicks,
                                      net.minecraft.client.renderer.entity.RenderManager manager,
                                      double[] out) {
        net.minecraft.client.entity.EntityPlayerSP player = mc.thePlayer;
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        float bodyYaw = player.prevRenderYawOffset
                + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        double yaw = Math.toRadians(bodyYaw);

        out[0] = px + Math.cos(yaw) * SHOULDER_OUT - manager.viewerPosX;
        out[1] = py + SHOULDER_UP - manager.viewerPosY;
        out[2] = pz + Math.sin(yaw) * SHOULDER_OUT - manager.viewerPosZ;
    }

    private float equippedProgress(float partialTicks) {
        try {
            Object renderer = mc.entityRenderer.itemRenderer;
            float previous = mindless.runtime.AccessorBridge
                    .ItemRenderer_getPrevEquippedProgress((net.minecraft.client.renderer.ItemRenderer) renderer);
            float current = mindless.runtime.AccessorBridge.ItemRenderer_getEquippedProgress(renderer);
            return previous + (current - previous) * partialTicks;
        } catch (Throwable unavailable) {
            return 1.0f;
        }
    }

    /** Bounds for the HUD editor, or null while the overlay has nothing to show. */
    public float[] renderPreview() {
        if (!isEnabled() || !Utils.nullCheck()) return null;
        syncPosition();
        float[] size = overlaySize(getDisplayBlock());
        return new float[]{posX, posY, posX + size[0], posY + size[1]};
    }

    public SliderSetting scaleSetting() {
        return counterScale;
    }

    private class EditScreen extends GuiScreen {
        private boolean dragging;
        private float ax, ay, lax, lay;
        private int lmx, lmy;
        private MindlessButton resetBtn;

        @Override
        public void initGui() {
            super.initGui();
            // MindlessButton, never Forge's GuiButtonExt: GuiButtonExt.drawButton reads
            // GuiButton.packedFGColour, a field Forge patches into the vanilla class. Lunar runs
            // unpatched vanilla, so that read is a NoSuchFieldError the moment this screen paints
            // -- which is what crashed the game on opening Edit position. Every other edit screen
            // already uses this button for the same reason.
            buttonList.add(resetBtn = new MindlessButton(1, width - 90, height - 25, 85, 20, "Reset position"));
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
            ItemStack badgeStack = getDisplayBlock();
            int blocks = Math.max(1, getBlockCount());
            drawOverlay(badgeStack, blocks, getBlocksPerSecond(), posX, posY, 1.0F);

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
            if (state != 0) return;
            if (dragging) {
                // Writing the dragged position back through setAbsolutePosition is what makes it
                // stick: it recomputes relativePosX/Y from the new pixels. Clearing the flag alone
                // left the old relative coordinates in place, and the next frame's syncPosition
                // snapped the panel straight back to where it started.
                setAbsolutePosition(ax, ay);
                mindless.utility.ProfileUtils.markUnsaved();
                ax = posX;
                ay = posY;
            }
            dragging = false;
        }

        @Override
        public void actionPerformed(GuiButton btn) {
            if (btn == resetBtn) { resetPosition(); ax = posX; ay = posY; }
        }

        @Override
        public boolean doesGuiPauseGame() { return false; }
    }
}
