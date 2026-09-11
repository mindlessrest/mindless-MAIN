package mindless.module.impl.combat;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Items;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.Collection;

/**
 * Switch to the best weapon before hitting something.
 *
 * The counterpart to Auto Tool, which only cares about blocks. Damage is read from the item's own
 * attack-damage attribute plus its Sharpness, rather than from a hardcoded table, so a
 * server-modified or enchanted item is ranked on what it will actually do instead of on what its
 * name suggests.
 *
 * Silent switching sends the slot change without moving the client's held item, so the hotbar
 * does not visibly jump on every swing. It costs a packet per switch and the server sees the
 * change either way.
 */
public class AutoWeapon extends Module {
    private final ButtonSetting axeCounts;
    private final ButtonSetting stickCounts;
    private final ButtonSetting rodCounts;
    private final ButtonSetting silent;
    private final ButtonSetting requireAttack;
    private final SliderSetting switchDelay;

    private long lastSwitchAt;
    private int silentSlot = -1;

    public AutoWeapon() {
        super("Auto Weapon", "Switches to your best weapon before attacking.", category.combat, 0);
        this.registerSetting(axeCounts = new ButtonSetting("Axes count", true));
        this.registerSetting(stickCounts = new ButtonSetting("Sticks count", false));
        this.registerSetting(rodCounts = new ButtonSetting("Rods count", false));
        this.registerSetting(silent = new ButtonSetting("Silent", false));
        this.registerSetting(requireAttack = new ButtonSetting("Require attack held", true));
        this.registerSetting(switchDelay = new SliderSetting("Switch delay", "ms", 0.0, 0.0, 500.0, 25.0));
    }

    @Override
    public void onDisable() {
        restoreSilent();
        lastSwitchAt = 0L;
    }

    @Override
    public String getInfo() {
        return silent.isToggled() ? "silent" : "";
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.hasCombatCandidate()) {
            restoreSilent();
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            restoreSilent();
            return;
        }

        boolean aiming = mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null;
        boolean auraTarget = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && ModuleManager.killAura.getHudTarget() != null;
        boolean attacking = !requireAttack.isToggled() || Mouse.isButtonDown(0) || auraTarget;

        if (!(aiming || auraTarget) || !attacking) {
            restoreSilent();
            return;
        }

        long now = System.currentTimeMillis();
        if (switchDelay.getInput() > 0 && now - lastSwitchAt < switchDelay.getInput()) {
            return;
        }

        int best = bestWeaponSlot();
        if (best < 0 || best == mc.thePlayer.inventory.currentItem) {
            return;
        }
        lastSwitchAt = now;

        if (silent.isToggled()) {
            // Tell the server only. The client keeps the slot it had, so nothing on screen moves.
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(best));
            silentSlot = best;
        }
        else {
            mc.thePlayer.inventory.currentItem = best;
        }
    }

    private void restoreSilent() {
        if (silentSlot < 0) {
            return;
        }
        if (Utils.nullCheck()) {
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
        }
        silentSlot = -1;
    }

    public int auraWeaponSlot(KillAura aura) {
        if (!isEnabled() || !Utils.nullCheck()) return -1;
        int best = -1;
        double damage = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && counts(stack) && aura.qualifies(stack) && damageOf(stack) > damage) {
                best = slot;
                damage = damageOf(stack);
            }
        }
        return best;
    }

    public int prepareAura(KillAura aura) {
        int best = auraWeaponSlot(aura);
        int original = mc.thePlayer.inventory.currentItem;
        if (best < 0 || best == original || System.currentTimeMillis() - lastSwitchAt < switchDelay.getInput()) return -1;
        lastSwitchAt = System.currentTimeMillis();
        mc.thePlayer.inventory.currentItem = best;
        mindless.runtime.AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
        return silent.isToggled() ? original : -1;
    }

    public void finishAura(int slot) {
        mc.thePlayer.inventory.currentItem = slot;
        mindless.runtime.AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
    }

    /** The hotbar slot holding the hardest-hitting eligible item, or -1. */
    private int bestWeaponSlot() {
        int best = -1;
        double bestDamage = -1.0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !counts(stack)) {
                continue;
            }
            double damage = damageOf(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = slot;
            }
        }
        return best;
    }

    private boolean counts(ItemStack stack) {
        if (stack.getItem() instanceof ItemSword) {
            return true;
        }
        if (axeCounts.isToggled() && stack.getItem() instanceof ItemAxe) {
            return true;
        }
        if (stickCounts.isToggled() && stack.getItem() == Items.stick) {
            return true;
        }
        return rodCounts.isToggled() && stack.getItem() == Items.fishing_rod;
    }

    /**
     * What the stack actually hits for.
     *
     * Read from the attack-damage attribute the item declares plus its Sharpness level, so a
     * sharpness five stone sword ranks above a plain diamond one -- which is the whole point of
     * choosing rather than assuming.
     */
    private double damageOf(ItemStack stack) {
        double damage = 0.0;
        try {
            Collection<net.minecraft.entity.ai.attributes.AttributeModifier> modifiers =
                    stack.getAttributeModifiers().get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
            if (modifiers != null) {
                for (net.minecraft.entity.ai.attributes.AttributeModifier modifier : modifiers) {
                    damage += modifier.getAmount();
                }
            }
        }
        catch (Exception unreadable) {
            // A malformed or modded stack should cost it the comparison, not the tick.
            damage = 0.0;
        }
        damage += EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack) * 1.25;
        return damage;
    }
}
