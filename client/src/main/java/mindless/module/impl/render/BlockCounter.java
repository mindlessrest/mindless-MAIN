package mindless.module.impl.render;

import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.shader.RoundedUtils;
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

    private static BlockCounter instance;

    private static final String[] SHOW_MODES = {"While placing", "Holding blocks", "Always"};
    private static final int SHOW_PLACING = 0;
    private static final int SHOW_HOLDING = 1;
    private static final int SHOW_ALWAYS = 2;

    private final SliderSetting showWhen;
    private final SliderSetting hideAfter;
    private final SliderSetting counterFont;
    private final SliderSetting counterScale;
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
    private long overlayPopStart = -1L;
    private boolean overlayVisible;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private float displayedBlocks = Float.NaN;
    private float displayedBps;
    private long lastOverlayNanos;

    private long lastPlacement;
    private long scriptHoldUntil;

    public BlockCounter() {
        super("Block Counter", "Counts your placeable blocks and how fast you place.", category.render);
        instance = this;
        this.registerSetting(showWhen = new SliderSetting("Show when", SHOW_PLACING, SHOW_MODES));
        this.registerSetting(hideAfter = new SliderSetting("Hide after", "s", 2.0, 0.5, 10.0, 0.5));
        this.registerSetting(counterFont = new SliderSetting("Font", 0, ModuleFont.options()));
        this.registerSetting(counterScale = new SliderSetting("Scale", "x", 1.0, 0.5, 3.0, 0.05));
        this.registerSetting(wholeInventory = new ButtonSetting("Count whole inventory", false));
        this.registerSetting(showBadge = new ButtonSetting("Show block icon", true));
        this.registerSetting(showRate = new ButtonSetting("Show place rate", true));
        this.registerSetting(showBar = new ButtonSetting("Show bar", true));
        this.registerSetting(barFull = new SliderSetting("Bar full at", " blocks", 128, 16, 512, 8));
        this.registerSetting(alwaysShow = new ButtonSetting("Show when empty", false));
        this.registerSetting(editPosition = new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
    }

    public static BlockCounter get() {
        return instance;
    }

    @Override
    public void guiUpdate() {
        barFull.setVisible(showBar.isToggled(), this);
        int mode = (int) showWhen.getInput();
        hideAfter.setVisible(mode == SHOW_PLACING, this);
        alwaysShow.setVisible(mode != SHOW_PLACING, this);
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
    private boolean shouldShow() {
        if (System.currentTimeMillis() < scriptHoldUntil) {
            return true;
        }
        int blocks = getBlockCount();
        switch ((int) showWhen.getInput()) {
            case SHOW_HOLDING: {
                ItemStack held = mc.thePlayer.inventory.getCurrentItem();
                boolean holding = held != null && held.getItem() instanceof ItemBlock && held.stackSize > 0;
                return holding || (alwaysShow.isToggled() && blocks > 0);
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
        displayedBps = 0f;
        lastOverlayNanos = 0L;
        lastPlacement = 0L;
        scriptHoldUntil = 0L;
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
        boolean shouldShow = shouldShow();

        if (shouldShow && !overlayVisible) {
            overlayVisible = true;
            overlayPopStart = System.currentTimeMillis();
        }
        else if (!shouldShow && overlayVisible) {
            if (overlayPopStart > 0 && overlayScale <= 0.01f) {
                overlayVisible = false;
                overlayPopStart = -1L;
                return;
            }
            if (overlayPopStart > 0 && overlayScale > 0.99f) {
                overlayPopStart = System.currentTimeMillis();
            }
        }

        if (overlayPopStart > 0) {
            float progress = Math.min(1f, (System.currentTimeMillis() - overlayPopStart) / (float) POP_DURATION_MS);
            overlayScale = shouldShow ? easeOutBack(progress) : 1f - progress;
        }
        if (overlayScale <= 0.01f) return;

        syncPosition();
        drawOverlay(getDisplayBlock(), blocks, getBlocksPerSecond(), posX, posY, overlayScale);
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

        RoundedUtils.drawRoundShadow(panelLeft, panelTop, width, height, radius, 5.0F * scale, 0x96000000);
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

        float full = Math.max(1.0F, (float) barFull.getInput());
        float fraction = Math.max(0.0F, Math.min(1.0F, displayedBlocks / full));
        float radius = barHeight * 0.5F;
        RoundedUtils.drawRound(x, y, width, barHeight, radius, 0x26FFFFFF);
        float filled = width * fraction;
        if (filled > barHeight) {
            RoundedUtils.drawRound(x, y, filled, barHeight, radius, 0xE6000000 | fillColour);
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

        if (Float.isNaN(displayedBlocks) || Math.abs(blocks - displayedBlocks) > 48.0F) {
            displayedBlocks = blocks;
        }
        else {
            displayedBlocks += (blocks - displayedBlocks) * (1.0F - (float) Math.exp(-delta * 12.0F));
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

    /** Bounds for the HUD editor, or null while the overlay has nothing to show. */
    public float[] renderPreview() {
        if (!Utils.nullCheck()) return null;
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
