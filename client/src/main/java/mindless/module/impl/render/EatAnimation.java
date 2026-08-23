package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;

/**
 * Holds the first-person eating pose up for as long as you are actually eating.
 *
 * <p>Vanilla decides between the eating pose and the ordinary held pose on one number:
 * {@code getItemInUseCount()}. That number counts down from the food's use duration, and on the
 * client it stops at zero rather than finishing the meal -- the server owns that. So any tick the
 * count is spent, or the count is cleared and re-taken underneath you, the renderer falls out of
 * the eating branch and back to the held pose, arm swing and all. Alternate between the two often
 * enough and the item flicks side to side in your hand while you are still very much eating.
 *
 * <p>This module answers that question differently: if you are holding a food or a potion and you
 * are consuming it, the eating pose is what gets drawn. Nothing about the eat itself changes --
 * no packets, no timing, no reach -- it decides which pose the hand is in and nothing else.
 */
public class EatAnimation extends Module {
    private static EatAnimation instance;
    private static boolean enabled;

    private final ButtonSetting keyHeldSetting;

    public EatAnimation() {
        super("Eat Animation", category.render);
        instance = this;
        this.registerSetting(keyHeldSetting = new ButtonSetting("While Key Held", true));
    }

    @Override
    public void onEnable() { enabled = true; }

    @Override
    public void onDisable() { enabled = false; }

    public static boolean isActive() {
        return enabled || instance != null && instance.isEnabled();
    }

    /**
     * The item-in-use count the first-person renderer should work from.
     *
     * @return a count that puts the renderer in its eating branch, or 0 to leave it alone.
     */
    public static int spoofUseCount(EntityPlayer player, ItemStack rendered, int actual) {
        // A real count already draws the real animation; there is nothing to stand in for.
        if (actual > 0 || !isActive() || player == null || rendered == null) return 0;

        EnumAction action = rendered.getItemUseAction();
        if (action != EnumAction.EAT && action != EnumAction.DRINK) return 0;

        // The client still has an item in use, it has just run its counter down waiting on the
        // server. This is the case that snaps the hand back mid-meal.
        if (player.isUsingItem()) return 1;

        if (instance != null && !instance.keyHeldSetting.isToggled()) return 0;

        // Nothing has told the client an eat is happening, but the use key is down on food, so
        // one is. Covers the counter being cleared and re-taken between ticks, which is the
        // flicker rather than the snap.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || !mc.inGameHasFocus) return 0;
        return mc.gameSettings.keyBindUseItem.isKeyDown() ? 1 : 0;
    }
}
