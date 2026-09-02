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
public class AntiMisplace extends Module {
    private final ButtonSetting endStone;
    private final ButtonSetting notify;

    public AntiMisplace() {
        super("Anti Misplace", "Disables ability to place certain blocks if not near bed", category.bedwars);
        this.registerSetting(endStone = new ButtonSetting("Include end stone", false));
        this.registerSetting(notify = new ButtonSetting("Chat message", true));
    }

    @SubscribeEvent
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled() || !Utils.nullCheck()) return;
        if (mc.currentScreen != null) return;
        if (Utils.getBedwarsStatus() != 2) return;

        MovingObjectPosition target = mc.objectMouseOver;
        if (target == null || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !isGuarded(held)) return;
        BlockPos placement = target.getBlockPos().offset(target.sideHit);
        if (isAdjacentToBed(placement)) return;

        event.setCanceled(true);
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
