package mindless.module.impl.render;

import mindless.event.AttackEvent;
import mindless.module.ModuleManager;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Moves the held item when a hit lands.
 *
 * The swing animation fires on every click; this fires only on contact, which is what makes it
 * read as feedback rather than as decoration. The transform is applied at the end of the first
 * person item pass, so it composes with whatever Animations is doing rather than replacing it.
 */
public class HitAnimation extends Module {

    private static final String[] STYLES = new String[]{"Spin", "Flip", "Thrust", "Recoil", "Shake", "Twirl"};
    private static final int STYLE_SPIN = 0;
    private static final int STYLE_FLIP = 1;
    private static final int STYLE_THRUST = 2;
    private static final int STYLE_RECOIL = 3;
    private static final int STYLE_SHAKE = 4;
    private static final int STYLE_TWIRL = 5;

    private static final String[] EASINGS = new String[]{"Linear", "Ease out", "Bounce"};
    private static final int EASE_LINEAR = 0;
    private static final int EASE_OUT = 1;

    private static final String[] TRIGGERS = new String[]{"Hit lands", "Every swing"};
    private static final int TRIGGER_HIT = 0;

    private static HitAnimation instance;

    private final SliderSetting style;
    private final SliderSetting trigger;
    private final SliderSetting duration;
    private final SliderSetting turns;
    private final SliderSetting amount;
    private final SliderSetting easing;
    private final ButtonSetting playersOnly;
    private final ButtonSetting alsoScale;
    private final SliderSetting scaleAmount;

    private long startedAt;

    public HitAnimation() {
        super("Hit Animation", "Animates your sword when a hit lands.", category.render, 0);

        GroupSetting motion = new GroupSetting("Motion");
        registerSetting(motion);
        registerSetting(style = new SliderSetting(motion, "Style", STYLE_SPIN, STYLES));
        registerSetting(turns = new SliderSetting(motion, "Turns", 1.0, 0.25, 4.0, 0.25));
        registerSetting(amount = new SliderSetting(motion, "Amount", 1.0, 0.1, 3.0, 0.1));
        registerSetting(duration = new SliderSetting(motion, "Duration", "s", 0.35, 0.1, 1.5, 0.05));
        registerSetting(easing = new SliderSetting(motion, "Easing", EASE_OUT, EASINGS));

        GroupSetting when = new GroupSetting("When");
        registerSetting(when);
        registerSetting(trigger = new SliderSetting(when, "Trigger", TRIGGER_HIT, TRIGGERS));
        registerSetting(playersOnly = new ButtonSetting(when, "Players only", true));

        GroupSetting extra = new GroupSetting("Extra");
        registerSetting(extra);
        registerSetting(alsoScale = new ButtonSetting(extra, "Pulse size", false));
        registerSetting(scaleAmount = new SliderSetting(extra, "Pulse amount", "%", 18, 2, 60, 2));

        instance = this;
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        int selected = (int) style.getInput();
        if (turns != null) {
            turns.setVisible(selected == STYLE_SPIN || selected == STYLE_FLIP || selected == STYLE_TWIRL, this);
        }
        if (scaleAmount != null) {
            scaleAmount.setVisible(alsoScale != null && alsoScale.isToggled(), this);
        }
        if (playersOnly != null) {
            playersOnly.setVisible(trigger != null && (int) trigger.getInput() == TRIGGER_HIT, this);
        }
    }

    @Override
    public String getInfo() {
        return STYLES[(int) style.getInput()].toLowerCase();
    }

    @Override
    public void onDisable() {
        startedAt = 0L;
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        if (event.isCanceled() || !Utils.nullCheck() || event.target == null) {
            return;
        }
        if ((int) trigger.getInput() == TRIGGER_HIT) {
            if (playersOnly.isToggled() && !(event.target instanceof EntityPlayer)) {
                return;
            }
            if (event.target == mc.thePlayer) {
                return;
            }
        }
        startedAt = System.currentTimeMillis();

        // The crosshair reacts to the same moment, so it is told here rather than subscribing to
        // the attack itself: two listeners on one event drift apart the first time one is gated.
        if (ModuleManager.crosshair != null) {
            ModuleManager.crosshair.onHit();
        }
    }

    /**
     * Applied by the shared first person item pass, after every other transform.
     *
     * Rotations are about the item's own middle, which is why it is walked to the centre and back
     * either side: spinning about the corner it is modelled from throws it off screen instead.
     */
    public static void apply() {
        if (instance == null || !instance.isEnabled() || instance.startedAt == 0L) {
            return;
        }
        float span = (float) instance.duration.getInput() * 1000.0f;
        float age = System.currentTimeMillis() - instance.startedAt;
        if (age >= span) {
            return;
        }

        float linear = age / span;
        float t = instance.ease(linear);
        float strength = (float) instance.amount.getInput();
        float spins = (float) instance.turns.getInput();

        GlStateManager.translate(0.45f, 0.2f, -0.1f);
        switch ((int) instance.style.getInput()) {
            case STYLE_FLIP:
                GlStateManager.rotate(t * 360.0f * spins, 1.0f, 0.0f, 0.0f);
                break;
            case STYLE_TWIRL:
                GlStateManager.rotate(t * 360.0f * spins, 0.0f, 1.0f, 0.0f);
                break;
            case STYLE_THRUST:
                GlStateManager.translate(0.0f, 0.0f, -Math.abs((float) Math.sin(t * Math.PI)) * 0.5f * strength);
                GlStateManager.rotate(-(float) Math.sin(t * Math.PI) * 25.0f * strength, 1.0f, 0.0f, 0.0f);
                break;
            case STYLE_RECOIL:
                GlStateManager.translate(0.0f, -(float) Math.sin(t * Math.PI) * 0.18f * strength,
                        (float) Math.sin(t * Math.PI) * 0.3f * strength);
                GlStateManager.rotate((float) Math.sin(t * Math.PI) * 40.0f * strength, 1.0f, 0.0f, 0.0f);
                break;
            case STYLE_SHAKE:
                // Shake runs off the raw progress, not the eased one: easing a vibration turns it
                // into a slow wobble, which is the opposite of the point.
                float shake = (1.0f - linear) * strength;
                GlStateManager.rotate((float) Math.sin(linear * 46.0f) * 14.0f * shake, 0.0f, 0.0f, 1.0f);
                GlStateManager.translate((float) Math.sin(linear * 39.0f) * 0.05f * shake,
                        (float) Math.cos(linear * 51.0f) * 0.05f * shake, 0.0f);
                break;
            default:
                GlStateManager.rotate(t * 360.0f * spins, 0.0f, 0.0f, 1.0f);
                break;
        }

        if (instance.alsoScale.isToggled()) {
            float pulse = 1.0f + (float) Math.sin(linear * Math.PI)
                    * (float) (instance.scaleAmount.getInput() / 100.0);
            GlStateManager.scale(pulse, pulse, pulse);
        }
        GlStateManager.translate(-0.45f, -0.2f, 0.1f);
    }

    private float ease(float t) {
        switch ((int) easing.getInput()) {
            case EASE_LINEAR:
                return t;
            case EASE_OUT: {
                float inv = 1.0f - t;
                return 1.0f - inv * inv * inv;
            }
            default: {
                // A single overshoot and settle, rather than a decaying spring: one bounce reads
                // as a hit, three read as a broken animation.
                float p = 1.0f - (1.0f - t) * (1.0f - t);
                return p + (float) Math.sin(t * Math.PI) * 0.12f;
            }
        }
    }
}
