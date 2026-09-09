package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AccessorBridge;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Compact "What am I looking at" block inspector. */
public class Waila extends Module {
    private static final float DEFAULT_X = 0.68f;
    private static final float DEFAULT_Y = 0.46f;

    private final SliderSetting scale;
    private final SliderSetting horizontalPosition;
    private final SliderSetting verticalPosition;
    private final SliderSetting smoothness;
    private final SliderSetting backgroundOpacity;
    private final SliderSetting rounding;
    private final ButtonSetting showIcon;
    private final ButtonSetting showDistance;
    private final ButtonSetting showCoordinates;
    private final ButtonSetting showBreakProgress;
    private final ButtonSetting highlightBlock;
    private final ColorSetting accentColor;

    private BlockPos displayedPos;
    private IBlockState displayedState;
    private ItemStack displayedStack;
    private String displayedName = "Block";
    private float visibility;
    private float shownProgress;
    private long lastFrameNanos;

    public Waila() {
        super("WAILA", "Shows information about the block you are looking at.", category.render);
        this.registerSetting(scale = new SliderSetting("Scale", "x", 1.0, 0.6, 1.6, 0.05));
        this.registerSetting(horizontalPosition = new SliderSetting(
                "Horizontal position", "%", DEFAULT_X * 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(verticalPosition = new SliderSetting(
                "Vertical position", "%", DEFAULT_Y * 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(smoothness = new SliderSetting("Smoothness", "%", 72.0, 0.0, 100.0, 5.0));
        this.registerSetting(backgroundOpacity = new SliderSetting(
                "Background opacity", "%", 76.0, 0.0, 100.0, 5.0));
        this.registerSetting(rounding = new SliderSetting("Rounding", 6.0, 0.0, 12.0, 0.5));
        this.registerSetting(showIcon = new ButtonSetting("Show block icon", true));
        this.registerSetting(showDistance = new ButtonSetting("Show distance", true));
        this.registerSetting(showCoordinates = new ButtonSetting("Show coordinates", false));
        this.registerSetting(showBreakProgress = new ButtonSetting("Show break progress", true));
        this.registerSetting(highlightBlock = new ButtonSetting("Highlight block", true));
        this.registerSetting(accentColor = new ColorSetting("Accent", 133, 151, 255, 220));
    }

    @Override
    public void onDisable() {
        displayedPos = null;
        displayedState = null;
        displayedStack = null;
        visibility = 0.0f;
        shownProgress = 0.0f;
        lastFrameNanos = 0L;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw(false, Float.NaN, Float.NaN);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!highlightBlock.isToggled() || !Utils.nullCheck()) return;
        BlockPos focused = focusedBlock();
        if (focused == null) return;
        int color = accentColor.getColor();
        int outline = (Math.min(230, Math.max(90, (color >>> 24))) << 24) | (color & 0x00FFFFFF);
        BlockOverlay.renderBlockOutline(focused, outline, outline, 1.35f, false);
    }

    public float[] renderPreview() {
        return draw(true, Float.NaN, Float.NaN);
    }

    public float[] renderDesignerPreview(float left, float top) {
        setAbsolutePosition(left, top);
        return draw(true, left, top);
    }

    public void resetPosition() {
        horizontalPosition.setValue(DEFAULT_X * 100.0);
        verticalPosition.setValue(DEFAULT_Y * 100.0);
    }

    public SliderSetting scaleSetting() {
        return scale;
    }

    private float[] draw(boolean preview, float forcedLeft, float forcedTop) {
        BlockPos focused = preview ? null : focusedBlock();
        if (preview && (displayedState == null || displayedStack == null)) {
            setDisplayed(new BlockPos(0, 64, 0), Blocks.planks.getDefaultState());
        } else if (focused != null && !focused.equals(displayedPos)) {
            setDisplayed(focused, mc.theWorld.getBlockState(focused));
        }

        float targetVisibility = preview || focused != null ? 1.0f : 0.0f;
        float targetProgress = focused == null ? 0.0f : breakProgress();
        updateAnimation(targetVisibility, targetProgress, preview);
        if (!preview && visibility < 0.015f) return null;
        if (displayedState == null) return null;

        MindlessFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;
        float s = (float) scale.getInput();
        boolean icon = showIcon.isToggled();
        String detail = detailLine(displayedPos);
        float nameW = font.getStringWidth(displayedName);
        float detailW = detail.isEmpty() ? 0.0f : font.getStringWidth(detail);
        float contentLeft = icon ? 39.0f : 10.0f;
        float logicalW = Math.max(126.0f, contentLeft + Math.max(nameW, detailW) + 10.0f);
        float logicalH = showBreakProgress.isToggled() ? 45.0f : 36.0f;
        float w = logicalW * s;
        float h = logicalH * s;

        ScaledResolution resolution = ScaledResolutionCache.get();
        float left = Float.isNaN(forcedLeft)
                ? (float) (horizontalPosition.getInput() / 100.0 * resolution.getScaledWidth()) : forcedLeft;
        float top = Float.isNaN(forcedTop)
                ? (float) (verticalPosition.getInput() / 100.0 * resolution.getScaledHeight()) : forcedTop;
        left = Math.max(2.0f, Math.min(resolution.getScaledWidth() - w - 2.0f, left));
        top = Math.max(2.0f, Math.min(resolution.getScaledHeight() - h - 2.0f, top));

        float alpha = preview ? 1.0f : visibility;
        int bgAlpha = (int) Math.round(backgroundOpacity.getInput() * 2.55 * alpha);
        int accent = withAlpha(accentColor.getColor(), (int) (((accentColor.getColor() >>> 24) & 0xFF) * alpha));
        float radius = (float) rounding.getInput()
                * mindless.module.impl.theme.ThemeManager.roundingScale() * s;
        RoundedUtils.drawRound(left + 1.0f, top + 1.5f, w, h, radius, withAlpha(0xFF000000, (int) (75 * alpha)));
        RoundedUtils.drawRound(left, top, w, h, radius, (bgAlpha << 24) | 0x090B0E);
        RoundedUtils.drawRound(left + 3.0f * s, top + 4.0f * s, Math.max(1.0f, 1.4f * s),
                h - 8.0f * s, Math.max(.5f, .7f * s), accent);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float lx = snapScaled(left / s, s);
        float ly = snapScaled(top / s, s);
        if (icon && displayedStack != null) renderIcon(displayedStack, lx + 12.0f, ly + 10.0f);
        float tx = snapScaled(lx + contentLeft, s);
        font.drawString(displayedName, tx, snapScaled(ly + 8.0f, s),
                withAlpha(0xFFF1F1F3, (int) (255 * alpha)), false);
        if (!detail.isEmpty()) {
            font.drawString(detail, tx, snapScaled(ly + 21.0f, s),
                    withAlpha(0xFF999DA5, (int) (230 * alpha)), false);
        }
        GlStateManager.popMatrix();

        if (showBreakProgress.isToggled()) {
            float barX = left + contentLeft * s;
            float barY = top + 36.0f * s;
            float barW = w - (contentLeft + 10.0f) * s;
            RoundedUtils.drawRound(barX, barY, barW, Math.max(2.0f, 3.0f * s), 1.5f * s,
                    withAlpha(0xFF25282D, (int) (210 * alpha)));
            if (shownProgress > 0.002f) {
                RoundedUtils.drawRound(barX, barY, barW * Math.min(1.0f, shownProgress),
                        Math.max(2.0f, 3.0f * s), 1.5f * s, accent);
            }
        }
        return new float[] { left, top, left + w, top + h };
    }

    private void setDisplayed(BlockPos pos, IBlockState state) {
        displayedPos = pos;
        displayedState = state;
        Block block = state.getBlock();
        int meta = block.getMetaFromState(state);
        Item item = Item.getItemFromBlock(block);
        displayedStack = item == null ? null : new ItemStack(item, 1, meta);
        try {
            displayedName = displayedStack != null ? displayedStack.getDisplayName() : block.getLocalizedName();
        } catch (RuntimeException ignored) {
            displayedName = block.getLocalizedName();
        }
    }

    private BlockPos focusedBlock() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        if (pos == null || mc.theWorld.getBlockState(pos).getBlock() == Blocks.air) return null;
        return pos;
    }

    private float breakProgress() {
        try {
            return Math.max(0.0f, Math.min(1.0f,
                    AccessorBridge.PlayerControllerMP_getCurBlockDamageMP(mc.playerController)));
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    private String detailLine(BlockPos pos) {
        StringBuilder detail = new StringBuilder();
        if (showDistance.isToggled() && !isPreviewPosition(pos) && mc.objectMouseOver != null
                && mc.objectMouseOver.hitVec != null) {
            double distance = mc.thePlayer.getPositionEyes(1.0f).distanceTo(mc.objectMouseOver.hitVec);
            detail.append(String.format("%.1fm", distance));
        }
        if (showCoordinates.isToggled() && pos != null) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
        }
        return detail.toString();
    }

    private static boolean isPreviewPosition(BlockPos pos) {
        return pos != null && pos.getX() == 0 && pos.getY() == 64 && pos.getZ() == 0;
    }

    private void updateAnimation(float targetVisibility, float targetProgress, boolean preview) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1.0f / 60.0f
                : Math.min(0.1f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        if (preview) {
            visibility = 1.0f;
            shownProgress = 0.58f;
            return;
        }
        float smooth = (float) (smoothness.getInput() / 100.0);
        float response = 24.0f - smooth * 18.0f;
        float blend = 1.0f - (float) Math.exp(-response * dt);
        visibility += (targetVisibility - visibility) * blend;
        shownProgress += (targetProgress - shownProgress) * blend;
    }

    private void renderIcon(ItemStack stack, float x, float y) {
        RenderItem renderer = mc.getRenderItem();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        renderer.renderItemAndEffectIntoGUI(stack, Math.round(x), Math.round(y));
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void setAbsolutePosition(float left, float top) {
        ScaledResolution resolution = ScaledResolutionCache.get();
        horizontalPosition.setValue(left / Math.max(1, resolution.getScaledWidth()) * 100.0);
        verticalPosition.setValue(top / Math.max(1, resolution.getScaledHeight()) * 100.0);
    }

    private static float snapScaled(float value, float scale) {
        return Math.round(value * scale) / Math.max(0.01f, scale);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
