package mindless.module.impl.bedwars;

import mindless.event.RightClickMouseEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Stops obsidian going anywhere except on the bed.
 *
 * <p>Obsidian is the one block in the game you cannot take back. Misplace a piece a block off the
 * bed and it is there for the rest of the game, in the way, and you are down the resources that
 * would have covered the bed properly. The block is only ever wanted touching the bed, so
 * anywhere else is a slip rather than a decision.
 *
 * <p>Only while a game is actually running -- in the lobby or on the practice server there is no
 * bed to be near, and blocking every placement there would be worse than useless.
 */
public class AntiMisplace extends Module {
    private final ButtonSetting endStone;
    private final ButtonSetting notify;

    public AntiMisplace() {
        super("Anti Misplace", category.bedwars);
        this.registerSetting(endStone = new ButtonSetting("Include end stone", false));
        this.registerSetting(notify = new ButtonSetting("Chat message", true));
    }

    @SubscribeEvent
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled() || !Utils.nullCheck()) return;
        if (mc.currentScreen != null) return;
        // 2 is "in a running game"; -1 not bedwars, 0 lobby, 1 pre-game.
        if (Utils.getBedwarsStatus() != 2) return;

        MovingObjectPosition target = mc.objectMouseOver;
        if (target == null || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !isGuarded(held)) return;

        // Where the block would land, not the face that was clicked.
        BlockPos placement = target.getBlockPos().offset(target.sideHit);
        if (isAdjacentToBed(placement)) return;

        event.setCanceled(true);
        // Let go of the button as well. Cancelling the click alone leaves it held, so the very
        // next tick tries the same placement again and the message repeats until you notice.
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);

        if (notify.isToggled()) {
            Utils.sendMessage("&cNot next to a bed &7- placement blocked.");
        }
    }

    private boolean isGuarded(ItemStack held) {
        Item item = held.getItem();
        if (item == Item.getItemFromBlock(Blocks.obsidian)) return true;
        return endStone.isToggled() && item == Item.getItemFromBlock(Blocks.end_stone);
    }

    private boolean isAdjacentToBed(BlockPos pos) {
        for (EnumFacing facing : EnumFacing.values()) {
            Block neighbour = mc.theWorld.getBlockState(pos.offset(facing)).getBlock();
            if (neighbour == Blocks.bed) return true;
        }
        return false;
    }
}
