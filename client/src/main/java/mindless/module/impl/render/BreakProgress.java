package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class BreakProgress extends Module {
    private SliderSetting mode;
    private ButtonSetting manual;
    private ButtonSetting bedAura;
    private ButtonSetting fadeIn;

    private String[] MODES = new String[] { "Percentage", "Second", "Decimal" };

    private float progress;
    private BlockPos block;
    private String progressStr;
    private int progressColor = -1;

    public BreakProgress() {
        super("Break Progress", "Shows how far the block you are mining is.", category.render);
        this.liteModule = true;
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(manual = new ButtonSetting("Show manual", true));
        this.registerSetting(bedAura = new ButtonSetting("Show BedAura", true));
        this.registerSetting(fadeIn = new ButtonSetting("Fade in", false));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (this.progress == 0.0f || this.block == null || !Utils.nullCheck()) {
            return;
        }
        final double x = this.block.getX() + 0.5 - mc.getRenderManager().viewerPosX;
        final double y = this.block.getY() + 0.5 - mc.getRenderManager().viewerPosY;
        final double z = this.block.getZ() + 0.5 - mc.getRenderManager().viewerPosZ;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate((mc.gameSettings.thirdPersonView == 2 ? -1 : 1) * mc.getRenderManager().playerViewX, 1.0f, 0.0f, 0.0f);
        GlStateManager.scale(-0.02266667f, -0.02266667f, -0.02266667f);
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        int colorAlpha = Utils.mergeAlpha(-1, Math.max(10, (int) (255 * progress)));
        int textColor = progressColor == -1 ? -1 : Utils.mergeAlpha(progressColor & 0xFFFFFF, Math.max(10, (int) (255 * progress)));
        mc.fontRendererObj.drawString(this.progressStr, (float) (-mc.fontRendererObj.getStringWidth(this.progressStr) / 2), -3.0f,
                fadeIn.isToggled() ? textColor == -1 ? colorAlpha : textColor : textColor, true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    private void setProgress() {
        switch ((int) mode.getInput()) {
            case 0: {
                this.progressStr = (int) (100.0 * (this.progress / 1.0)) + "%";
                break;
            }
            case 1: {
                double timeLeft = Utils.round((double) ((1.0f - this.progress) / BlockUtils.getBlockHardness(BlockUtils.getBlock(this.block), mc.thePlayer.getHeldItem(), false, false)) / 20.0, 1);
                this.progressStr = timeLeft == 0 ? "0" : timeLeft + "s";
                break;
            }
            case 2: {
                this.progressStr = String.valueOf(Utils.round(this.progress, 2));
                break;
            }
        }
    }

    @Override
    public void onUpdate() {
        if (mc.thePlayer.capabilities.isCreativeMode || !mc.thePlayer.capabilities.allowEdit) {
            this.resetVariables();
            return;
        }
        if (bedAura.isToggled() && ModuleManager.bedAura != null && ModuleManager.bedAura.isEnabled()) {
            BlockPos ap = ModuleManager.bedAura.getAuraTargetPos();
            float bp = ModuleManager.bedAura.getAuraBreakProgress();
            if (ap != null && bp > 0.0f && ModuleManager.bedAura.shouldShowAuraProgress()) {
                this.progress = Math.min(1.0f, bp);
                this.block = ap;
                this.progressColor = ModuleManager.bedAura.getAuraProgressColor();
                this.setProgress();
                return;
            }
        }
        if (!manual.isToggled() || mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            this.resetVariables();
            return;
        }
        this.progress = AccessorBridge.PlayerControllerMP_getCurBlockDamageMP(mc.playerController);
        if (this.progress == 0.0f) {
            this.resetVariables();
            return;
        }
        this.block = mc.objectMouseOver.getBlockPos();
        this.progressColor = -1;
        this.setProgress();
    }

    @Override
    public void onDisable() {
        this.resetVariables();
    }

    private void resetVariables() {
        this.progress = 0.0f;
        this.block = null;
        this.progressStr = "";
        this.progressColor = -1;
    }
}
