package mindless.module.impl.combat;

import mindless.Mindless;
import mindless.event.GameTickEvent;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.KnockbackPacketPolicy;
import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.player.Blink;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.ItemListSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.Utils;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class KnockbackDelay extends Module {
    private final SliderSetting distanceToTarget;
    private final SliderSetting chance;
    private final SliderSetting maximumDelay;
    private final ButtonSetting inAir;
    private final ButtonSetting lookingAtPlayer;
    private final ButtonSetting requireLeftMouse;
    private final ButtonSetting onlyWhitelistedItem;
    private final ItemListSetting whitelistedItems;
    private KnockbackPacketPolicy packetPolicy = new KnockbackPacketPolicy();
    private DelayLease inboundLease;

    public KnockbackDelay() {
        super("Knockback Delay", "Delays the knockback you take.", category.combat);
        this.registerSetting(distanceToTarget = new SliderSetting("Distance to target", 6.0, 3.0, 12.0, 0.1));
        this.registerSetting(chance = new SliderSetting("Chance", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(maximumDelay = new SliderSetting("Maximum delay", "ms", 200.0, 50.0, 1000.0, 10.0));
        this.registerSetting(new DescriptionSetting("Conditions"));
        this.registerSetting(inAir = new ButtonSetting("In air", true));
        this.registerSetting(lookingAtPlayer = new ButtonSetting("Looking at player", false));
        this.registerSetting(requireLeftMouse = new ButtonSetting("Require Left mouse", false, "Require mouse down"));
        this.registerSetting(onlyWhitelistedItem = new ButtonSetting(
                "Restrict held item",
                false,
                "Item whitelist",
                "Restrict to listed items"));
        this.registerSetting(whitelistedItems = new ItemListSetting("Whitelisted items"));
        this.closetModule = true;
    }

    @Override
    public void guiUpdate() {
        whitelistedItems.setVisible(onlyWhitelistedItem.isToggled(), this);
    }

    @Override
    public void onEnable() {
        if (blinksInbound()) {
            Utils.sendMessage("&cKnockback Delay conflicts with Blink inbound / both. Disable Blink or use outbound-only.");
            disable();
            return;
        }
        packetPolicy = new KnockbackPacketPolicy();
        inboundLease = Mindless.packetDelayService.acquire(DelayRequest.fixedWindow(
                "Knockback Delay", EnumLagDirection.ONLY_INBOUND, packetPolicy,
                packetPolicy::chooseDelayNanos));
    }

    @Override
    public void onDisable() {
        packetPolicy.disable();
        if (inboundLease != null) {
            inboundLease.release();
            inboundLease = null;
        }
    }

    @Override
    public String getInfo() {
        return (int) maximumDelay.getInput() + "ms";
    }

    @SubscribeEvent
    public void onGameTick(GameTickEvent event) {
        if (!isEnabled()) return;
        if (!Utils.nullCheck() || mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) {
            packetPolicy.disable();
            if (inboundLease != null) inboundLease.releaseClaims();
            return;
        }
        if (blinksInbound()) {
            Utils.sendMessage("&cKnockback Delay conflicts with Blink inbound / both. Disable Blink or use outbound-only.");
            disable();
            return;
        }
        boolean eligible = conditionsFailureReason() == null;
        packetPolicy.configure(
                Mindless.packetDelayService.getCurrentEpoch(),
                eligible,
                mc.thePlayer.getEntityId(),
                (long) maximumDelay.getInput(),
                chance.getInput());
        if (!eligible && inboundLease != null) inboundLease.releaseClaims();
        Mindless.packetDelayService.drainExpired();
    }

    private String conditionsFailureReason() {
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || AccessorBridge.Entity_getIsInWeb(mc.thePlayer)) return "in fluid or web";
        double maxSq = distanceToTarget.getInput() * distanceToTarget.getInput();
        if (CombatTargeting.findTarget(maxSq) == null) return "no target in range";
        if (inAir.isToggled() && mc.thePlayer.onGround) return "not in air";
        if (lookingAtPlayer.isToggled() && CombatTargeting.getMouseOverTarget(maxSq) == null) return "not looking at player";
        if (requireLeftMouse.isToggled() && !Mouse.isButtonDown(0)) return "LMB not held";
        if (onlyWhitelistedItem.isToggled()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !whitelistedItems.matches(held)) return "held item not whitelisted";
        }
        return null;
    }

    private static boolean blinksInbound() {
        Blink blink = ModuleManager.blink;
        return blink != null && blink.isEnabled() && blink.delaysInboundPackets();
    }

}
