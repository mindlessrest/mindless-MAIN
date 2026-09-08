package mindless.module.impl.combat;

import mindless.event.ReceivePacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.movement.LongJump;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.Random;

/**
 * Knockback control.
 *
 * The important difference from the previous implementation is where it acts. That one waited for
 * the server's knockback to land and then scaled the motion back down on the following tick, which
 * means the first tick of every hit was taken at full strength and only the remainder was reduced.
 * This intercepts the velocity packet itself, so the knockback is shaped before the player ever
 * moves, and it can hold one back entirely for a while instead of only shrinking it.
 *
 * Three effects, independent and combinable:
 *
 *   Modify scales the horizontal and vertical components, or reverses the horizontal.
 *   Delay holds the packet for a randomised number of ticks and applies it late.
 *   Reduce keeps damping motion for a short window after the hit rather than only on impact.
 */
public class Velocity extends Module {
    // Kept public and named as before: ModuleUtils reads vertical, and profiles key settings by
    // name, so both survive the rewrite.
    public static SliderSetting horizontal;
    public static SliderSetting vertical;
    private SliderSetting chance;
    private ButtonSetting onlyWhileTargeting;
    private ButtonSetting disableS;
    public boolean disable;

    private GroupSetting modifyGroup;
    private ButtonSetting modifyEnabled;
    private ButtonSetting reverse;

    private GroupSetting delayGroup;
    private ButtonSetting delayEnabled;
    private SliderSetting minDelayTicks;
    private SliderSetting maxDelayTicks;
    private ButtonSetting releaseOnGround;
    private ButtonSetting releaseOnReduce;

    private GroupSetting reduceGroup;
    private ButtonSetting reduceEnabled;
    private SliderSetting reduceTicks;

    private ButtonSetting requireMoving;
    private mindless.utility.combat.EntityTargets targets;

    private final Random random = new Random();

    /** The packet being withheld, and how long it has been held. */
    private S12PacketEntityVelocity heldVelocity;
    private int heldTicks;
    private int releaseAtTick;

    /** Ticks left in the post-hit damping window. */
    private int reduceTicksLeft;

    /**
     * Set when an explosion arrives with real force behind it.
     *
     * Explosion knockback comes through the same velocity packet as a hit. Shaping it makes fights
     * near TNT and creepers behave in a way no legitimate client does, and it is not knockback the
     * module is meant to be managing, so the next packet after one is passed through untouched.
     */
    private boolean skipNextFromExplosion;

    /**
     * Whether the last thing that happened to us was actually a hit.
     *
     * The server sends a velocity packet for plenty of reasons. Only the ones that follow a damage
     * status for our own player are knockback worth delaying; holding the others produces stuck
     * motion that looks nothing like lag.
     */
    private boolean hitLanded;

    public Velocity() {
        super("Velocity", "Cuts the knockback you take.", category.combat, 0);

        this.registerSetting(modifyGroup = new GroupSetting("Modify"));
        this.registerSetting(modifyEnabled = new ButtonSetting(modifyGroup, "Modify velocity", true));
        this.registerSetting(horizontal = new SliderSetting("Horizontal", "%", 90.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(vertical = new SliderSetting("Vertical", "%", 100.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(reverse = new ButtonSetting(modifyGroup, "Reverse", false));

        this.registerSetting(delayGroup = new GroupSetting("Delay"));
        this.registerSetting(delayEnabled = new ButtonSetting(delayGroup, "Delay velocity", false));
        this.registerSetting(minDelayTicks = new SliderSetting(delayGroup, "Min delay", 2.0D, 1.0D, 20.0D, 1.0D));
        this.registerSetting(maxDelayTicks = new SliderSetting(delayGroup, "Max delay", 4.0D, 1.0D, 20.0D, 1.0D));
        this.registerSetting(releaseOnGround = new ButtonSetting(delayGroup, "Release on ground", true));
        this.registerSetting(releaseOnReduce = new ButtonSetting(delayGroup, "Release on reduce", false));

        this.registerSetting(reduceGroup = new GroupSetting("Reduce"));
        this.registerSetting(reduceEnabled = new ButtonSetting(reduceGroup, "Reduce velocity", false));
        this.registerSetting(reduceTicks = new SliderSetting(reduceGroup, "Reduce ticks", 4.0D, 1.0D, 20.0D, 1.0D));

        this.registerSetting(chance = new SliderSetting("Chance", "%", 100.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(requireMoving = new ButtonSetting("Require moving", false));
        this.registerSetting(onlyWhileTargeting = new ButtonSetting("Only while targeting", false));
        this.registerSetting(disableS = new ButtonSetting("Disable while holding S", false));
        // Expo gates the whole module on somebody being nearby and worth reacting to, which
        // is what stops it firing on fall damage and mob hits in an empty lobby.
        this.targets = new mindless.utility.combat.EntityTargets(this, "Targets", 10.0, 1.0, 20.0);
        this.closetModule = true;
    }

    @Override
    public void guiUpdate() {
        boolean modify = modifyEnabled != null && modifyEnabled.isToggled();
        if (horizontal != null) horizontal.setVisible(modify, this);
        if (vertical != null) vertical.setVisible(modify, this);
        if (reverse != null) reverse.setVisible(modify, this);

        boolean delay = delayEnabled != null && delayEnabled.isToggled();
        if (minDelayTicks != null) minDelayTicks.setVisible(delay, this);
        if (maxDelayTicks != null) maxDelayTicks.setVisible(delay, this);
        if (releaseOnGround != null) releaseOnGround.setVisible(delay, this);
        if (releaseOnReduce != null) {
            releaseOnReduce.setVisible(delay && reduceEnabled != null && reduceEnabled.isToggled(), this);
        }

        if (reduceTicks != null) {
            reduceTicks.setVisible(reduceEnabled != null && reduceEnabled.isToggled(), this);
        }
    }

    @Override
    public String getInfo() {
        StringBuilder info = new StringBuilder();
        if (modifyEnabled.isToggled()) {
            if (reverse.isToggled()) {
                info.append("reverse ");
            }
            int h = (int) horizontal.getInput();
            int v = (int) vertical.getInput();
            info.append(h).append('%');
            if (h != v) {
                info.append(' ').append(v).append('%');
            }
        }
        if (delayEnabled.isToggled()) {
            if (info.length() > 0) info.append(", ");
            int min = (int) minDelayTicks.getInput();
            int max = (int) maxDelayTicks.getInput();
            info.append(min == max ? String.valueOf(min) : min + "-" + max);
            info.append(max <= 1 ? " tick" : " ticks");
        }
        if (reduceEnabled.isToggled()) {
            if (info.length() > 0) info.append(", ");
            info.append("reduce");
        }
        return info.length() == 0 ? "none" : info.toString();
    }

    @Override
    public void onDisable() {
        releaseHeld();
        reset();
    }

    private void reset() {
        heldVelocity = null;
        heldTicks = 0;
        releaseAtTick = 0;
        reduceTicksLeft = 0;
        skipNextFromExplosion = false;
        hitLanded = false;
    }

    /** Whether the module should act on this knockback at all. */
    private boolean shouldAct() {
        if (!Utils.nullCheck() || LongJump.stopVelocity || disable) {
            return false;
        }
        if (ModuleManager.antiKnockback != null && ModuleManager.antiKnockback.isEnabled()) {
            return false;
        }
        if (disableS.isToggled() && Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode())) {
            return false;
        }
        if (requireMoving.isToggled()
                && mc.thePlayer.motionX == 0.0D && mc.thePlayer.motionZ == 0.0D) {
            return false;
        }
        if (onlyWhileTargeting.isToggled()
                && (mc.objectMouseOver == null || mc.objectMouseOver.entityHit == null)) {
            return false;
        }
        // Nobody worth reacting to nearby means this was not combat knockback.
        if (targets != null && targets.findNearest() == null) {
            return false;
        }
        double roll = chance.getInput();
        if (roll <= 0.0D) {
            return false;
        }
        // Rolled once per knockback rather than per tick, so a partial chance thins out how many
        // hits are affected instead of making one hit flicker between damped and not.
        return roll >= 100.0D || random.nextDouble() < roll / 100.0D;
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (!Utils.nullCheck() || e.isCanceled()) {
            return;
        }

        if (e.getPacket() instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) e.getPacket();
            if (explosion.func_149149_c() != 0.0F
                    || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F) {
                skipNextFromExplosion = true;
            }
            return;
        }

        if (e.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) e.getPacket();
            Entity hurt = status.getEntity(mc.theWorld);
            // Opcode 2 is the hurt animation. Seeing it for ourselves is what marks the velocity
            // packet behind it as knockback rather than any of the other reasons one arrives.
            if (hurt == mc.thePlayer && status.getOpCode() == 2) {
                hitLanded = true;
            }
            return;
        }

        if (!(e.getPacket() instanceof S12PacketEntityVelocity)) {
            return;
        }
        S12PacketEntityVelocity packet = (S12PacketEntityVelocity) e.getPacket();
        if (packet.getEntityID() != mc.thePlayer.getEntityId()) {
            return;
        }

        if (skipNextFromExplosion) {
            skipNextFromExplosion = false;
            hitLanded = false;
            return;
        }
        if (!shouldAct()) {
            hitLanded = false;
            return;
        }

        boolean modify = modifyEnabled.isToggled();
        boolean cancelOutright = modify
                && horizontal.getInput() == 0.0D
                && vertical.getInput() == 0.0D
                && !reverse.isToggled();
        if (cancelOutright) {
            e.setCanceled(true);
            startReduceWindow();
            hitLanded = false;
            return;
        }

        if (delayEnabled.isToggled() && hitLanded && heldVelocity == null) {
            heldVelocity = packet;
            heldTicks = 0;
            int min = (int) Math.min(minDelayTicks.getInput(), maxDelayTicks.getInput());
            int max = (int) Math.max(minDelayTicks.getInput(), maxDelayTicks.getInput());
            releaseAtTick = min == max ? min : min + random.nextInt(max - min + 1);
            e.setCanceled(true);
            hitLanded = false;
            return;
        }

        if (modify) {
            e.setCanceled(true);
            applyVelocity(packet);
        }
        startReduceWindow();
        hitLanded = false;
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent ev) {
        if (!Utils.nullCheck() || ev.entity != mc.thePlayer) {
            return;
        }

        if (heldVelocity != null) {
            heldTicks++;
            boolean landed = releaseOnGround.isToggled() && mc.thePlayer.onGround;
            boolean damping = releaseOnReduce.isToggled() && reduceTicksLeft > 0;
            // Water and lava end the hold regardless: staying still in a fluid while the server
            // believes you were pushed diverges far faster than it does on land.
            boolean inFluid = mc.thePlayer.isInWater() || mc.thePlayer.isInLava();
            if (heldTicks >= releaseAtTick || landed || damping || inFluid) {
                releaseHeld();
            }
        }

        if (reduceTicksLeft > 0) {
            reduceTicksLeft--;
            if (modifyEnabled.isToggled() && reduceEnabled.isToggled()) {
                double h = horizontal.getInput() / 100.0D;
                double v = vertical.getInput() / 100.0D;
                mc.thePlayer.motionX *= h;
                mc.thePlayer.motionZ *= h;
                if (v != 1.0D) {
                    mc.thePlayer.motionY *= v;
                }
            }
        }
    }

    private void startReduceWindow() {
        if (reduceEnabled.isToggled()) {
            reduceTicksLeft = (int) reduceTicks.getInput();
        }
    }

    private void releaseHeld() {
        if (heldVelocity == null) {
            return;
        }
        S12PacketEntityVelocity packet = heldVelocity;
        heldVelocity = null;
        heldTicks = 0;
        if (Utils.nullCheck()) {
            applyVelocity(packet);
            startReduceWindow();
        }
    }

    /**
     * Apply a velocity packet to the player, shaped by the modify settings.
     *
     * The divisor is the same 8000 the vanilla handler uses; the packet carries motion as a fixed
     * point short, and reproducing the conversion here is what lets the packet be cancelled and
     * applied on our own terms instead of the server's.
     */
    private void applyVelocity(S12PacketEntityVelocity packet) {
        double x = packet.getMotionX() / 8000.0D;
        double y = packet.getMotionY() / 8000.0D;
        double z = packet.getMotionZ() / 8000.0D;

        if (modifyEnabled.isToggled()) {
            double h = horizontal.getInput() / 100.0D;
            double v = vertical.getInput() / 100.0D;
            if (reverse.isToggled()) {
                x = -x * h;
                z = -z * h;
            }
            else {
                x *= h;
                z *= h;
            }
            y *= v;
        }

        mc.thePlayer.motionX = x;
        mc.thePlayer.motionY = y;
        mc.thePlayer.motionZ = z;
    }
}
