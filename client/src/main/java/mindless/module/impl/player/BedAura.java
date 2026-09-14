package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.GameTickEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PreMotionEvent;
import mindless.event.PreSlotScrollEvent;
import mindless.event.SlotUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.render.BlockOverlay;
import mindless.rotation.RotationSource;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.ProfiledButtonSetting;
import mindless.module.setting.impl.ProfiledSliderSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.block.BlockBed;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class BedAura extends Module {

    private static final String[] MODES = {"Silent", "Legit"};
    private static final String[] PROGRESS_MODES = {"Off", "Bar", "Circle"};
    private static final ItemStack BED_DISPLAY_STACK = new ItemStack(Items.bed);
    private static final int PROGRESS_COLOR = 0xFF19C6E6;

    private final SliderSetting mode;
    private final SliderSetting progressDisplay;
    private final SliderSetting range;
    private final SliderSetting speed;
    private final SliderSetting breakDelay;
    private final SliderSetting fov;
    private final SliderSetting aimSpeed;
    private final SliderSetting moveFix;
    private final ButtonSetting toolCheck;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting autoTool;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting spoofItem;
    private final ButtonSetting renderOutline;
    private final ColorSetting outlineColor;
    private final GroupSetting silentGroup;
    private final ProfiledSliderSetting silentRange;
    private final ProfiledSliderSetting silentSpeed;
    private final ProfiledButtonSetting silentSurroundings;
    private final ProfiledButtonSetting silentToolCheck;
    private final ProfiledButtonSetting silentWhitelist;
    private final ProfiledButtonSetting silentSwing;
    private final ProfiledSliderSetting silentMoveFix;
    private final ProfiledSliderSetting silentShowTarget;
    private final ProfiledSliderSetting silentShowProgress;
    private final SilentBedBreaker silent;
    private final LegitBedBreaker legit;

    private int activeMode = -1;
    private BlockPos progressTarget;
    private float displayedProgress;
    private float previousRawProgress;
    private long progressFrameTime;

    public BedAura() {
        super("Bed Breaker", "Smoothly breaks nearby beds and their defenses.", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(progressDisplay = new SliderSetting("Progress", 1, PROGRESS_MODES));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 2.0, 4.5, 0.1));
        this.registerSetting(speed = new SliderSetting("Speed", "%", 0.0, 0.0, 100.0, 1.0));
        this.registerSetting(breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0));
        this.registerSetting(aimSpeed = new SliderSetting("Aim speed", 14, 1, 30, 1));
        this.registerSetting(moveFix = new SliderSetting("Move fix", 1, new String[]{"None", "Silent", "Strict"}));
        this.registerSetting(toolCheck = new ButtonSetting("Tool check", true));
        this.registerSetting(whitelistOwnBed = new ButtonSetting("Whitelist own bed", true));
        this.registerSetting(prioritizeKillAura = new ButtonSetting("Prioritize KillAura", false));
        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(autoTool = new ButtonSetting(swapGroup, "Auto tool", true));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));
        this.registerSetting(spoofItem = new ButtonSetting(swapGroup, "Keep Original Item", false, "Spoof item"));
        this.registerSetting(renderOutline = new ButtonSetting("Render block outline", true));
        this.registerSetting(outlineColor = new ColorSetting("Outline color", 255, 64, 64, 229));
        this.registerSetting(silentGroup = new GroupSetting("Silent"));
        this.registerSetting(silentRange = new ProfiledSliderSetting(silentGroup, "Range", " block", 4.5, 2.0, 4.5, 0.1, "Silent.Range") {
            @Override
            public void loadProfile(com.google.gson.JsonObject data) {
                super.loadProfile(data);
                setValue(Double.isFinite(getInput()) ? getInput() : 4.5);
            }
        });
        this.registerSetting(silentSpeed = new ProfiledSliderSetting(silentGroup, "Speed", "%", 33.0, 0.0, 100.0, 1.0, "Silent.Speed"));
        this.registerSetting(silentSurroundings = new ProfiledButtonSetting(silentGroup, "Surroundings", true, "Silent.Surroundings"));
        this.registerSetting(silentToolCheck = new ProfiledButtonSetting(silentGroup, "Tool check", true, "Silent.Tool check"));
        this.registerSetting(silentWhitelist = new ProfiledButtonSetting(silentGroup, "Whitelist", true, "Silent.Whitelist"));
        this.registerSetting(silentSwing = new ProfiledButtonSetting(silentGroup, "Swing", true, "Silent.Swing"));
        this.registerSetting(silentMoveFix = new ProfiledSliderSetting(silentGroup, "Move fix", 1, new String[]{"None", "Silent", "Strict"}, "Silent.Move fix"));
        this.registerSetting(silentShowTarget = new ProfiledSliderSetting(silentGroup, "Show target", 1, new String[]{"None", "Default", "HUD"}, "Silent.Show target"));
        this.registerSetting(silentShowProgress = new ProfiledSliderSetting(silentGroup, "Show progress", 1, new String[]{"None", "Default", "HUD"}, "Silent.Show progress"));
        silent = new SilentBedBreaker(this, silentRange, silentSpeed,
                silentSurroundings, silentToolCheck, silentWhitelist, silentSwing, silentMoveFix,
                silentShowTarget, silentShowProgress);
        legit = new LegitBedBreaker(this, range, speed, breakDelay, fov, aimSpeed, moveFix, toolCheck,
                whitelistOwnBed, autoTool, switchBackWhenDone, overrideSwapBack, spoofItem);
    }

    @Override
    public void guiUpdate() {
        boolean legit = isLegitMode();
        range.setVisible(legit, this);
        speed.setVisible(legit, this);
        breakDelay.setVisible(legit, this);
        fov.setVisible(legit, this);
        aimSpeed.setVisible(legit, this);
        moveFix.setVisible(legit, this);
        toolCheck.setVisible(legit, this);
        whitelistOwnBed.setVisible(legit, this);
        swapGroup.setVisible(legit, this);
        autoTool.setVisible(legit, this);
        switchBackWhenDone.setVisible(legit && autoTool.isToggled(), this);
        overrideSwapBack.setVisible(legit && autoTool.isToggled(), this);
        spoofItem.setVisible(legit && autoTool.isToggled(), this);
        renderOutline.setVisible(legit, this);
        outlineColor.setVisible(legit && renderOutline.isToggled(), this);
        silentGroup.setVisible(!legit, this);
        silentRange.setVisible(!legit, this);
        silentSpeed.setVisible(!legit, this);
        silentSurroundings.setVisible(!legit, this);
        silentToolCheck.setVisible(!legit, this);
        silentWhitelist.setVisible(!legit, this);
        silentSwing.setVisible(!legit, this);
        silentMoveFix.setVisible(!legit, this);
        silentShowTarget.setVisible(!legit, this);
        silentShowProgress.setVisible(!legit, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    private boolean isLegitMode() {
        return (int) mode.getInput() == 1;
    }

    @Override
    public void onDisable() {
        silent.cleanup();
        legit.cleanup();
        activeMode = -1;
        resetProgressDisplay();
    }

    public void onWorldChange() {
        silent.cleanup();
        legit.cleanup();
        activeMode = -1;
        resetProgressDisplay();
    }

    @SubscribeEvent
    public void onGameTick(GameTickEvent event) {
        prepareSilentTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) prepareSilentTick();
    }

    private void prepareSilentTick() {
        if (!isEnabled() || !Utils.nullCheck()) return;
        int selectedMode = (int) mode.getInput();
        if (activeMode != -1 && activeMode != selectedMode) {
            silent.cleanup();
            legit.cleanup();
        }
        activeMode = selectedMode;
        if (!isLegitMode()) silent.prepareTick();
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            silent.cleanup();
            legit.cleanup();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMouse(MouseEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (e.button == 0 || e.button == 1) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreAttack(PreAttackEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotScroll(PreSlotScrollEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (isLegitMode()) legit.scrollIntendedSlot(e.slot);
        else silent.scrollIntendedSlot(e.slot);
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotUpdate(SlotUpdateEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (isLegitMode()) legit.recordIntendedSlot(e.slot);
        else silent.recordIntendedSlot(e.slot);
        e.setCanceled(true);
    }

    private boolean shouldSuppressManualMouse() {
        return isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura()
                && controlsInteractions();
    }

    public BlockPos getAuraTargetPos() {
        return isLegitMode() ? legit.target() : silent.target();
    }

    public boolean isActivelyMining() {
        return isLegitMode() ? legit.isDigging() : silent.isDigging();
    }

    public boolean isBreakingRoute() {
        return isEnabled() && controlsInteractions();
    }

    public boolean isPrioritizingKillAura() {
        return prioritizeKillAura.isToggled();
    }

    public boolean shouldSuppressControllerMining() {
        return isEnabled() && controlsInteractions();
    }

    public boolean controlsInteractions() {
        return isLegitMode() ? legit.controlsInteractions() : silent.controlsInteractions();
    }

    public boolean shouldYieldInteractionTo(RotationSource source) {
        return controlsInteractions() && source != null
                && source.getPriority() > RotationSource.BED_AURA.getPriority();
    }

    public float getAuraBreakProgress() {
        return isLegitMode() ? legit.progress() : silent.progress();
    }

    public float getAuraTotalProgress() {
        return isLegitMode() ? legit.totalProgress() : silent.progress();
    }

    public boolean shouldShowAuraProgress() {
        return isLegitMode() || silent.showsProgress();
    }

    public int getAuraProgressColor() {
        return isLegitMode() ? -1 : silent.progressColor();
    }

    public String getAuraToolName() {
        if (!Utils.nullCheck()) return "Bed Breaker";
        if (isAuraTargetBed()) return "Bed";
        net.minecraft.item.ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) return "Hand";
        if (stack.getItem() instanceof net.minecraft.item.ItemShears) return "Shears";
        if (stack.getItem() instanceof net.minecraft.item.ItemPickaxe) return "Pickaxe";
        if (stack.getItem() instanceof net.minecraft.item.ItemAxe) return "Axe";
        if (stack.getItem() instanceof net.minecraft.item.ItemSpade) return "Shovel";
        return "Hand";
    }

    public ItemStack getAuraToolStack() {
        if (isAuraTargetBed()) return BED_DISPLAY_STACK;
        return Utils.nullCheck() ? mc.thePlayer.getHeldItem() : null;
    }

    public boolean isAuraTargetBed() {
        BlockPos position = getAuraTargetPos();
        return Utils.nullCheck() && position != null
                && BlockUtils.getBlock(position) instanceof BlockBed;
    }

    public boolean isSpoofingHeldItem() {
        return isLegitMode() && legit.isSpoofingHeldItem();
    }

    public ItemStack getOriginalVisualItem() {
        return legit.originalVisualItem();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        if (isLegitMode()) legit.requestRotation(e);
        else silent.requestRotation(e);
    }

    public void afterMotionResolved(PreMotionEvent event) {
        if (isLegitMode()) legit.actionTick(event);
        else silent.actionTick(event);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled() || !Utils.nullCheck()
                || mc.currentScreen != null || (int) progressDisplay.getInput() == 0) {
            resetProgressDisplay();
            return;
        }

        BlockPos target = getAuraTargetPos();
        if (target == null || !isActivelyMining()) {
            resetProgressDisplay();
            return;
        }

        float rawProgress = Math.max(0.0F, Math.min(1.0F, getAuraBreakProgress()));
        long now = System.currentTimeMillis();
        if (!target.equals(progressTarget) || rawProgress + 0.01F < previousRawProgress) {
            progressTarget = target;
            displayedProgress = 0.0F;
            progressFrameTime = now;
        }

        long elapsed = progressFrameTime == 0L ? 16L : Math.min(100L, now - progressFrameTime);
        float blend = 1.0F - (float) Math.exp(-elapsed / 45.0F);
        displayedProgress += (rawProgress - displayedProgress) * blend;
        if (rawProgress >= 1.0F) displayedProgress = 1.0F;
        previousRawProgress = rawProgress;
        progressFrameTime = now;

        if ((int) progressDisplay.getInput() == 2) renderProgressCircle(displayedProgress);
        else renderProgressBar(displayedProgress);
    }

    private void renderProgressBar(float progress) {
        ScaledResolution resolution = new ScaledResolution(mc);
        float centerX = resolution.getScaledWidth() / 2.0F;
        float top = resolution.getScaledHeight() / 2.0F + 18.0F;
        float width = 116.0F;
        float height = 5.0F;
        float left = centerX - width / 2.0F;

        RenderUtils.drawRoundedRectangle(left - 1.5F, top - 1.5F,
                left + width + 1.5F, top + height + 1.5F, 2.5F, 0xA0000000);
        RenderUtils.drawRoundedRectangle(left, top, left + width, top + height,
                2.0F, 0xD91B2024);
        if (progress > 0.0F) {
            RenderUtils.drawRoundedRectangle(left, top, left + width * progress, top + height,
                    2.0F, PROGRESS_COLOR);
        }
    }

    private void renderProgressCircle(float progress) {
        ScaledResolution resolution = new ScaledResolution(mc);
        float centerX = resolution.getScaledWidth() / 2.0F + 0.5F;
        float centerY = resolution.getScaledHeight() / 2.0F + 0.5F;
        float radius = 10.0F;
        float thickness = 3.0F;

        RenderUtils.draw2DCircle(centerX, centerY, radius, 100, thickness,
                0.0F, 0.0F, 0.0F, 0.5F);
        if (progress >= 0.999F) {
            RenderUtils.draw2DCircle(centerX, centerY, radius, 100, thickness,
                    0.098F, 0.776F, 0.902F, 1.0F);
        } else if (progress > 0.0F) {
            RenderUtils.draw2DCircleArc(centerX, centerY, radius, 90.0F,
                    90.0F + progress * 360.0F + 0.5F, thickness, PROGRESS_COLOR);
        }
    }

    private void resetProgressDisplay() {
        progressTarget = null;
        displayedProgress = 0.0F;
        previousRawProgress = 0.0F;
        progressFrameTime = 0L;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!isEnabled() || !Utils.nullCheck()) {
            return;
        }
        if (!isLegitMode()) {
            BlockPos silentTarget = silent.target();
            int color = silent.targetColor();
            if (silentTarget != null && color != 0) {
                BlockOverlay.renderBlockOutline(silentTarget, color, color, 2.0f, true);
            }
            return;
        }
        BlockPos legitTarget = legit.target();
        if (renderOutline.isToggled() && legitTarget != null && canMineBlocks()) {
            int c = outlineColor.getColor();
            BlockOverlay.renderBlockOutline(legitTarget, c, c, 2.0f, true);
        }
    }

    private boolean canMineBlocks() {
        return mc.thePlayer.capabilities.allowEdit
                && !mc.thePlayer.capabilities.isCreativeMode
                && !mc.thePlayer.isSpectator();
    }

    boolean shouldYieldToKillAura() {
        if (!prioritizeKillAura.isToggled()) {
            return false;
        }
        return ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && KillAura.target != null;
    }
}
