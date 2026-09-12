package mindless.module.impl.render;

import mindless.effect.EffectSystem;
import mindless.effect.impl.BurstEffect;
import mindless.effect.impl.RippleEffect;
import mindless.event.JumpEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Random;

/**
 * A ring left behind at the point of takeoff.
 *
 * Reads the jump rather than causing it, and never cancels the event, so this cannot change how
 * anyone moves. Other players are off by default: the effect fires on every jump in sight and a
 * crowded lobby is the one place that stops being cheap.
 */
public class JumpEffect extends Module {

    private static final String[] MODES = new String[]{"Ring", "Dust", "Both"};
    private static final int MODE_RING = 0;
    private static final int MODE_DUST = 1;

    private final SliderSetting mode;
    private final SliderSetting duration;
    private final SliderSetting radius;
    private final SliderSetting thickness;
    private final SliderSetting dustCount;
    private final ButtonSetting others;
    private final ColorSetting color;

    private final Random random = new Random();

    public JumpEffect() {
        super("Jump Effect", "Leaves a ring where you take off.", category.render, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_RING, MODES));
        this.registerSetting(duration = new SliderSetting("Duration", "s", 0.7, 0.2, 2.0, 0.05));
        this.registerSetting(radius = new SliderSetting("Radius", "blocks", 1.8, 0.4, 5.0, 0.1));
        this.registerSetting(thickness = new SliderSetting("Thickness", "blocks", 0.14, 0.02, 0.8, 0.02));
        this.registerSetting(dustCount = new SliderSetting("Dust count", 18, 4, 60, 1));
        this.registerSetting(others = new ButtonSetting("Other players", false));
        this.registerSetting(color = new ColorSetting("Color", 255, 79, 163, 255));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        int selected = (int) mode.getInput();
        if (radius != null) {
            radius.setVisible(selected != MODE_DUST, this);
        }
        if (thickness != null) {
            thickness.setVisible(selected != MODE_DUST, this);
        }
        if (dustCount != null) {
            dustCount.setVisible(selected != MODE_RING, this);
        }
    }

    @SubscribeEvent
    public void onJump(JumpEvent event) {
        if (event.isCanceled() || !Utils.nullCheck()) {
            return;
        }
        EntityLivingBase entity = event.entity;
        if (entity == null) {
            return;
        }
        if (entity != mc.thePlayer) {
            if (!others.isToggled() || !(entity instanceof EntityPlayer)) {
                return;
            }
        }

        int ticks = Math.max(1, (int) Math.round(duration.getInput() * 20.0));
        int rgb = color.getRGB() & 0xFFFFFF;
        // Takeoff is the last moment the feet are on the floor, so the ring belongs at the
        // bounding box's base rather than at posY, which is already climbing.
        double footY = entity.getEntityBoundingBox().minY + 0.02;
        int selected = (int) mode.getInput();

        if (selected != MODE_DUST) {
            EffectSystem.spawn(new RippleEffect(entity.posX, footY, entity.posZ, ticks,
                    radius.getInput(), thickness.getInput(), 1, rgb, false));
        }
        if (selected != MODE_RING) {
            EffectSystem.spawn(new BurstEffect(entity.posX, footY, entity.posZ, ticks,
                    (int) dustCount.getInput(), 0.06, 0.05, 0.04,
                    rgb, true, false, random));
        }
    }
}
