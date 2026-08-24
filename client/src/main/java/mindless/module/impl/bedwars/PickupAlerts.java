package mindless.module.impl.bedwars;

import mindless.event.ReceivePacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S0DPacketCollectItem;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Says when an enemy picks a resource off a generator.
 *
 * <p>Diamonds and emeralds are the ones worth hearing about: they are what the upgrades and the
 * good items cost, and a team hoovering them up is a team about to be a problem. Iron and gold
 * are constant and are off by default, since alerting on them is just noise.
 *
 * <p>Per-player cooldown rather than a global one. A generator drops several at once, and a
 * single cooldown for everyone means whoever grabs theirs first hides the rest.
 */
public class PickupAlerts extends Module {
    private final SliderSetting cooldown;
    private final ButtonSetting pingSound;
    private final ButtonSetting iron;
    private final ButtonSetting gold;
    private final ButtonSetting diamonds;
    private final ButtonSetting emeralds;

    private final Map<UUID, Long> lastAlert = new HashMap<UUID, Long>();

    public PickupAlerts() {
        super("Pickup Alerts", category.bedwars);
        this.registerSetting(cooldown = new SliderSetting("Cooldown", " second", 2, 0, 10, 1));
        this.registerSetting(pingSound = new ButtonSetting("Ping sound", true));
        this.registerSetting(iron = new ButtonSetting("Iron", false));
        this.registerSetting(gold = new ButtonSetting("Gold", false));
        this.registerSetting(diamonds = new ButtonSetting("Diamonds", true));
        this.registerSetting(emeralds = new ButtonSetting("Emeralds", true));
    }

    @Override
    public void onDisable() {
        lastAlert.clear();
    }

    @SubscribeEvent
    public void onPacket(ReceivePacketEvent event) {
        if (!this.isEnabled() || !Utils.nullCheck()) return;
        if (!(event.getPacket() instanceof S0DPacketCollectItem)) return;
        if (Utils.getBedwarsStatus() != 2) return;

        S0DPacketCollectItem packet = (S0DPacketCollectItem) event.getPacket();
        Entity collector = mc.theWorld.getEntityByID(packet.getEntityID());
        Entity collected = mc.theWorld.getEntityByID(packet.getCollectedItemEntityID());

        if (!(collector instanceof EntityPlayer) || !(collected instanceof EntityItem)) return;
        EntityPlayer player = (EntityPlayer) collector;
        if (player == mc.thePlayer || Utils.isTeammate(player)) return;

        ItemStack stack = ((EntityItem) collected).getEntityItem();
        if (stack == null || !isWatched(stack.getItem())) return;

        UUID id = player.getUniqueID();
        long now = System.currentTimeMillis();
        Long previous = lastAlert.get(id);
        if (previous != null && now - previous <= (long) cooldown.getInput() * 1000L) return;
        lastAlert.put(id, now);

        Utils.sendMessage("&b" + player.getName() + " &7picked up "
                + colorOf(stack.getItem()) + stack.getDisplayName());
        if (pingSound.isToggled()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.6f);
        }
    }

    private boolean isWatched(Item item) {
        if (item == Items.iron_ingot) return iron.isToggled();
        if (item == Items.gold_ingot) return gold.isToggled();
        if (item == Items.diamond) return diamonds.isToggled();
        if (item == Items.emerald) return emeralds.isToggled();
        return false;
    }

    private String colorOf(Item item) {
        if (item == Items.iron_ingot) return "&f";
        if (item == Items.gold_ingot) return "&6";
        if (item == Items.diamond) return "&b";
        if (item == Items.emerald) return "&2";
        return "&7";
    }
}
