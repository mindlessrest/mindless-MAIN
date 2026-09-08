package mindless.module.impl.movement;

import mindless.event.PostPlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import static net.minecraft.util.EnumFacing.DOWN;

public class NoSlow extends Module {
    public static SliderSetting mode;
    public static SliderSetting slowed;
    public static ButtonSetting disableBow;
    public static ButtonSetting disablePotions;
    public static SliderSetting swordMode;
    public static SliderSetting otherMode;
    public static ButtonSetting onlyWhenBlocking;

    private final String[] NOSLOW_MODES = new String[] { "Vanilla", "Watchdog", "Blatant" };
    /**
     * Whether an item class gets the reduction lifted at all.
     *
     * Split by item because they are not equally safe. Moving at full speed while blocking a
     * sword is ordinary; moving at full speed while drinking or drawing a bow is not something a
     * legitimate client ever does, so that half defaults to leaving vanilla alone.
     */
    private final String[] ITEM_MODES = new String[] { "None", "Modified" };
    private static final int ITEM_MODE_NONE = 0;
    private static final int ITEM_MODE_MODIFIED = 1;

    public boolean noSlowing;
    private boolean setJump;

    public NoSlow() {
        super("No Slow", "Removes the slowdown from using items.", category.movement, 0);
        this.registerSetting(new DescriptionSetting("Default is 80% motion reduction."));
        this.registerSetting(mode = new SliderSetting("Mode", 0, NOSLOW_MODES));
        this.registerSetting(slowed = new SliderSetting("Slow %", 80.0D, 0.0D, 80.0D, 1.0D));
        this.registerSetting(disableBow = new ButtonSetting("Disable bow", false));
        this.registerSetting(disablePotions = new ButtonSetting("Disable potions", false));
        this.registerSetting(swordMode = new SliderSetting("Sword", ITEM_MODE_MODIFIED, ITEM_MODES));
        this.registerSetting(otherMode = new SliderSetting("Other items", ITEM_MODE_NONE, ITEM_MODES));
        this.registerSetting(onlyWhenBlocking = new ButtonSetting("Only while blocking", true));
    }

    @Override
    public void onDisable() {
        noSlowing = false;
    }

    @Override
    public void guiUpdate() {
        boolean blatant = (int) mode.getInput() == 2;
        boolean sword = !blatant && (int) swordMode.getInput() == ITEM_MODE_MODIFIED;
        boolean other = !blatant && (int) otherMode.getInput() == ITEM_MODE_MODIFIED;
        if (swordMode != null) swordMode.setVisible(!blatant, this);
        if (otherMode != null) otherMode.setVisible(!blatant, this);
        if (onlyWhenBlocking != null) onlyWhenBlocking.setVisible(sword, this);
        if (disableBow != null) disableBow.setVisible(other, this);
        if (disablePotions != null) disablePotions.setVisible(other, this);
        if (slowed != null) slowed.setVisible(!blatant, this);
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if ((int) swordMode.getInput() == ITEM_MODE_NONE && Utils.holdingSword()) {
            return;
        }
        boolean apply = getSlowed() != 0.2f;
        if (!apply || !mc.thePlayer.isUsingItem()) {
            return;
        }
        switch ((int) mode.getInput()) {
            case 1: // Beta
                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem % 8 + 1));
                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, DOWN));
                break;
        }
    }

    @SubscribeEvent
    public void onPostPlayerInput(PostPlayerInputEvent e) {
        if (setJump) {
            mc.thePlayer.movementInput.jump = true;
            setJump = false;
        }
    }

    /**
     * The movement multiplier while an item is in use. 0.2 is vanilla.
     *
     * Read from the player tick on both launch paths, so it must stay cheap and must always
     * return something sane rather than throwing.
     */
    public static float getSlowed() {
        net.minecraft.item.ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null || ModuleManager.noSlow == null || !ModuleManager.noSlow.isEnabled()) {
            return 0.2f;
        }
        if ((int) mode.getInput() == 2) {
            return 1.0f; // Blatant: no reduction at all, whatever is held.
        }

        float modified = (100.0F - (float) slowed.getInput()) / 100.0F;

        if (held.getItem() instanceof ItemSword) {
            if ((int) swordMode.getInput() == ITEM_MODE_NONE) {
                return 0.2f;
            }
            // Tied to the block rather than to holding a sword. Full speed for as long as a
            // sword is in hand is visible from a mile away; full speed only across the moments
            // Autoblock is actually blocking is the same benefit over a far smaller window.
            if (onlyWhenBlocking.isToggled() && !isBlockingNow()) {
                return 0.2f;
            }
            return modified;
        }

        if ((int) otherMode.getInput() == ITEM_MODE_NONE) {
            return 0.2f;
        }
        if (held.getItem() instanceof ItemBow && disableBow.isToggled()) {
            return 0.2f;
        }
        if (held.getItem() instanceof ItemPotion
                && !ItemPotion.isSplash(held.getItemDamage())
                && disablePotions.isToggled()) {
            return 0.2f;
        }
        return modified;
    }

    /** Whether Autoblock currently has a block standing. */
    private static boolean isBlockingNow() {
        if (ModuleManager.autoBlock == null || !ModuleManager.autoBlock.isEnabled()) {
            return false;
        }
        // Autoblock owns this decision: it knows whether a block is standing and
        // whether it wants the slowdown lifted right now, including its own
        // disable-in-range rule. Asking it is what keeps the two from disagreeing.
        return ModuleManager.autoBlock.isActive() && ModuleManager.autoBlock.allowsNoSlow();
    }

    @Override
    public String getInfo() {
        return NOSLOW_MODES[(int) mode.getInput()];
    }

}