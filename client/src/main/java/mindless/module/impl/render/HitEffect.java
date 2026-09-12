package mindless.module.impl.render;

import mindless.effect.EffectSystem;
import mindless.effect.impl.BurstEffect;
import mindless.effect.impl.RippleEffect;
import mindless.effect.impl.ShockwaveEffect;
import mindless.event.AttackEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Random;

/**
 * Something visible at the moment a hit lands.
 *
 * Purely cosmetic and deliberately kept that way: it reads an attack that has already happened and
 * draws at the target's position. It never touches the attack itself, so turning it on cannot
 * change reach, timing or what goes on the wire.
 */
public class HitEffect extends Module {

    private static final String[] MODES = new String[]{"Ripple", "Shockwave", "Both"};
    private static final int MODE_RIPPLE = 0;
    private static final int MODE_SHOCKWAVE = 1;

    /** Two hits inside this many ticks share one effect, so a fast aura is not a strobe. */
    private static final int MIN_GAP_TICKS = 2;

    private final SliderSetting mode;
    private final SliderSetting duration;
    private final SliderSetting radius;
    private final SliderSetting thickness;
    private final SliderSetting ripples;
    private final ButtonSetting sparks;
    private final SliderSetting sparkCount;
    private final ButtonSetting playersOnly;
    private final ButtonSetting throughWalls;
    private final ColorSetting color;

    private final Random random = new Random();
    private long lastSpawn;

    public HitEffect() {
        super("Hit Effect", "Draws a wave where your hits land.", category.render, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_RIPPLE, MODES));
        this.registerSetting(duration = new SliderSetting("Duration", "s", 0.9, 0.2, 3.0, 0.05));
        this.registerSetting(radius = new SliderSetting("Radius", "blocks", 3.0, 0.5, 8.0, 0.1));
        this.registerSetting(thickness = new SliderSetting("Thickness", "blocks", 0.18, 0.02, 1.0, 0.02));
        this.registerSetting(ripples = new SliderSetting("Ripples", 3, 1, 6, 1));
        this.registerSetting(sparks = new ButtonSetting("Sparks", false));
        this.registerSetting(sparkCount = new SliderSetting("Spark count", 18, 4, 80, 1));
        this.registerSetting(playersOnly = new ButtonSetting("Players only", true));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", false));
        this.registerSetting(color = new ColorSetting("Color", 79, 216, 255, 255));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        int selected = (int) mode.getInput();
        if (ripples != null) {
            ripples.setVisible(selected != MODE_SHOCKWAVE, this);
        }
        if (sparkCount != null) {
            sparkCount.setVisible(sparks != null && sparks.isToggled(), this);
        }
    }

    @Override
    public void onDisable() {
        lastSpawn = 0L;
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        // A cancelled attack never lands, so drawing the wave for it would advertise a hit that
        // did not happen.
        if (event.isCanceled() || !Utils.nullCheck() || event.target == null) {
            return;
        }
        if (playersOnly.isToggled() && !(event.target instanceof EntityPlayer)) {
            return;
        }
        if (event.target == mc.thePlayer) {
            return;
        }

        long now = mc.theWorld.getTotalWorldTime();
        if (lastSpawn != 0L && now - lastSpawn < MIN_GAP_TICKS) {
            return;
        }
        lastSpawn = now;

        spawnAt(event.target);
    }

    private void spawnAt(Entity target) {
        int ticks = Math.max(1, (int) Math.round(duration.getInput() * 20.0));
        int rgb = color.getRGB() & 0xFFFFFF;
        boolean through = throughWalls.isToggled();
        int selected = (int) mode.getInput();

        // The ripple is anchored to the feet because it lies on the ground; the shockwave is
        // anchored to the middle of the target because it does not.
        double footY = target.getEntityBoundingBox().minY + 0.02;
        double chestY = footY + target.height * 0.5;

        if (selected != MODE_SHOCKWAVE) {
            EffectSystem.spawn(new RippleEffect(target.posX, footY, target.posZ, ticks,
                    radius.getInput(), thickness.getInput(),
                    (int) ripples.getInput(), rgb, through));
        }
        if (selected != MODE_RIPPLE) {
            EffectSystem.spawn(new ShockwaveEffect(target.posX, chestY, target.posZ,
                    Math.max(1, ticks / 2), radius.getInput() * 0.7,
                    thickness.getInput(), rgb, through));
        }
        if (sparks.isToggled()) {
            EffectSystem.spawn(new BurstEffect(target.posX, chestY, target.posZ, ticks,
                    (int) sparkCount.getInput(), 0.09, 0.10, 0.045,
                    rgb, true, through, random));
        }
    }
}
