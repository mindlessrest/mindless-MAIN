package mindless.module.impl.movement;

import mindless.event.PostPlayerInputEvent;
import mindless.event.PreMotionEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.player.SafeWalk;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.ModuleUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockSnow;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Speed extends Module {
    public SliderSetting speed;
    public static SliderSetting multiplier;
    public static SliderSetting speedSetting;
    private ButtonSetting onlyForward;
    private ButtonSetting onlyStrafe;
    private ButtonSetting liquidDisable;
    private ButtonSetting sneakDisable;
    private ButtonSetting jumpMoving;
    public ButtonSetting rotateYawOption;
    public GroupSetting damageBoostGroup;
    public ButtonSetting damageBoost, damageBoostRequireKey;
    public KeySetting damageBoostKey;

    private static final String[] MODES = {"Vanilla", "Float", "Strafe", "Ground", "Grim"};
    private static final int MODE_VANILLA = 0;
    private static final int MODE_FLOAT = 1;
    private static final int MODE_STRAFE = 2;
    private static final int MODE_GROUND = 3;
    private static final int MODE_GRIM = 4;

    public boolean hopping, lowhop, didMove, setRotation;
    private boolean canFloat, requireJump;
    private int grimAirTicks;

    public Speed() {
        super("Speed", category.movement, 0);
        this.registerSetting(speed = new SliderSetting("Speed", 0, MODES));
        this.registerSetting(multiplier = new SliderSetting("Multiplier", "x", 1.2D, 1.0D, 1.5D, 0.01D));
        this.registerSetting(speedSetting = new SliderSetting("BHop Speed", 2.0, 0.8, 1.2, 0.01));
        this.registerSetting(onlyForward = new ButtonSetting("Only forward", false));
        this.registerSetting(onlyStrafe = new ButtonSetting("Only strafe", false));
        this.registerSetting(liquidDisable = new ButtonSetting("Disable in liquid", true));
        this.registerSetting(sneakDisable = new ButtonSetting("Disable while sneaking", true));
        this.registerSetting(jumpMoving = new ButtonSetting("Only jump when moving", true));
        this.registerSetting(rotateYawOption = new ButtonSetting("Rotate yaw", false));
        this.registerSetting(damageBoostGroup = new GroupSetting("Damage boost"));
        this.registerSetting(damageBoost = new ButtonSetting(damageBoostGroup, "Enable", false));
        this.registerSetting(damageBoostRequireKey = new ButtonSetting(damageBoostGroup, "Require key", false));
        this.registerSetting(damageBoostKey = new KeySetting(damageBoostGroup, "Enable key", 51));
    }

    @Override
    public void guiUpdate() {
        int m = (int) speed.getInput();
        boolean isBhop = m == MODE_STRAFE || m == MODE_GROUND;
        boolean isGrim = m == MODE_GRIM;
        multiplier.setVisible(m == MODE_VANILLA, this);
        speedSetting.setVisible(isBhop, this);
        onlyForward.setVisible(m == MODE_VANILLA || m == MODE_FLOAT, this);
        onlyStrafe.setVisible(m == MODE_VANILLA || m == MODE_FLOAT, this);
        liquidDisable.setVisible(isBhop || isGrim, this);
        sneakDisable.setVisible(isBhop || isGrim, this);
        jumpMoving.setVisible(isBhop || isGrim, this);
        rotateYawOption.setVisible(isBhop, this);
        damageBoostGroup.setVisible(isBhop, this);
        damageBoostKey.setVisible(damageBoostRequireKey.isToggled() && isBhop, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) speed.getInput()];
    }

    @Override
    public void onDisable() {
        hopping = false;
        grimAirTicks = 0;
    }

    @SubscribeEvent
    public void onPostPlayerInput(PostPlayerInputEvent e) {
        if (!Utils.nullCheck()) return;
        int m = (int) speed.getInput();
        if (m != MODE_GROUND) return;
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) return;
        if (hopping) {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent e) {
        if (!Utils.nullCheck()) return;
        int m = (int) speed.getInput();

        if (m == MODE_VANILLA || m == MODE_FLOAT) {
            tickVanillaFloat(e, m);
            return;
        }

        if (m == MODE_GRIM) {
            tickGrim(e);
            return;
        }

        tickBhop(e, m);
    }

    private void tickVanillaFloat(PreMotionEvent e, int m) {
        double horizontalSpeed = Utils.getHorizontalSpeed();
        if (horizontalSpeed == 0.0D) return;
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) return;
        if (mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0) return;
        if (Utils.jumpDown()) return;
        if (!settingsMet()) return;

        if (m == MODE_VANILLA) {
            double val = multiplier.getInput() - (multiplier.getInput() - 1.0D) * 0.5D;
            Utils.setSpeed(horizontalSpeed * val, true);
        } else {
            if (ModuleUtils.groundTicks <= 8 || floatConditions()) canFloat = true;
            if (!floatConditions()) canFloat = false;
            if (!mc.thePlayer.onGround) requireJump = false;
            if (canFloat && floatConditions() && !requireJump) {
                e.setPosY(e.getPosY() + ModuleUtils.offsetValue);
                if (Utils.isMoving()) {
                    Utils.setSpeed(getFloatSpeed(getSpeedLevel()));
                }
            }
        }
    }

    private void tickBhop(PreMotionEvent e, int m) {
        if (((mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) && liquidDisable.isToggled())
                || (mc.thePlayer.isSneaking() && sneakDisable.isToggled())) return;
        if (ModuleManager.longJump.function) return;

        if (m == MODE_STRAFE) {
            if (Utils.isMoving()) {
                if (mc.thePlayer.onGround) mc.thePlayer.jump();
                mc.thePlayer.setSprinting(true);
                Utils.setSpeed(Utils.getHorizontalSpeed() + 0.005 * speedSetting.getInput());
                hopping = true;
            }
            return;
        }

        // Ground mode
        if (mc.thePlayer.onGround && (!jumpMoving.isToggled() || Utils.isMoving())) {
            if (mc.thePlayer.moveForward <= -0.5 && mc.thePlayer.moveStrafing == 0
                    && KillAura.target == null && !Utils.noSlowingBackWithBow()
                    && !mc.thePlayer.isCollidedHorizontally) {
                setRotation = true;
            }
            mc.thePlayer.jump();
            double spd = (speedSetting.getInput() - 0.52);
            double speedModifier = spd;
            final int speedAmplifier = Utils.getSpeedAmplifier();
            switch (speedAmplifier) {
                case 1: speedModifier = spd + 0.02; break;
                case 2: speedModifier = spd + 0.04; break;
                case 3: speedModifier = spd + 0.1; break;
            }
            if (Utils.isMoving() && !Utils.noSlowingBackWithBow()) {
                Utils.setSpeed(speedModifier - Utils.randomizeDouble(0.0003, 0.0001));
                didMove = true;
            }
            hopping = true;
        }
        if (mc.thePlayer.moveForward <= 0.5 && hopping) {
            ModuleUtils.handleSlow();
        }
        if (!mc.thePlayer.onGround) {
            hopping = false;
        }
    }

    /**
     * Grim mode: strafe optimization within vanilla physics bounds.
     *
     * Stays within Grim's prediction by only using legal vanilla mechanics:
     * - Sprint + jump for maximum ground→air speed
     * - Optimal strafe angles for speed preservation through turns
     * - Maintains sprint flag through air
     * - No illegal speed additions — just optimized input timing
     */
    private void tickGrim(PreMotionEvent e) {
        if (((mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) && liquidDisable.isToggled())
                || (mc.thePlayer.isSneaking() && sneakDisable.isToggled())) return;
        if (!Utils.isMoving()) {
            grimAirTicks = 0;
            return;
        }

        mc.thePlayer.setSprinting(true);

        if (mc.thePlayer.onGround) {
            grimAirTicks = 0;
            if (!jumpMoving.isToggled() || Utils.isMoving()) {
                mc.thePlayer.jump();
            }
        } else {
            grimAirTicks++;
        }
    }

    public boolean settingsMet() {
        if (onlyForward.isToggled() && !Utils.isBindDown(mc.gameSettings.keyBindForward)) return false;
        if (onlyStrafe.isToggled() && mc.thePlayer.moveStrafing == 0.0f) return false;
        return true;
    }

    private boolean floatConditions() {
        int edgeY = (int) Math.round((mc.thePlayer.posY % 1.0D) * 100.0D);
        if (ModuleUtils.stillTicks > 20) { requireJump = true; return false; }
        if (!(mc.thePlayer.posY % 1 == 0) && edgeY >= 10 && !allowedBlocks()) { requireJump = true; return false; }
        if (SafeWalk.canSafeWalk()) { requireJump = true; return false; }
        if (!mc.thePlayer.onGround) return false;
        if (Utils.jumpDown()) return false;
        if (ModuleManager.longJump.function) return false;
        if (Utils.isBindDown(mc.gameSettings.keyBindSneak)) return false;
        return true;
    }

    private boolean allowedBlocks() {
        Block block = BlockUtils.getBlock(new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
        return block instanceof BlockSnow || block instanceof BlockCarpet;
    }

    private double[] floatSpeedLevels = {0.2, 0.22, 0.28, 0.29, 0.3};

    private double getFloatSpeed(int speedLevel) {
        double min = 0;
        if (mc.thePlayer.moveStrafing != 0 && mc.thePlayer.moveForward != 0) min = 0.003;
        if (speedLevel >= 0) return floatSpeedLevels[speedLevel] - min;
        return floatSpeedLevels[0] - min;
    }

    private int getSpeedLevel() {
        for (PotionEffect potionEffect : mc.thePlayer.getActivePotionEffects()) {
            if (potionEffect.getEffectName().equals("potion.moveSpeed")) {
                return potionEffect.getAmplifier() + 1;
            }
            return 0;
        }
        return 0;
    }
}
