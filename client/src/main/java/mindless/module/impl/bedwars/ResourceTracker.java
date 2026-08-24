package mindless.module.impl.bedwars;

import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What you can actually afford, without opening your inventory.
 *
 * <p>Every purchase in the game is priced in these four, and the answer to "can I buy this" is a
 * count you otherwise have to stop and go and look up. Mid-fight, on a bridge, that is the whole
 * reason to have it on screen.
 *
 * <p>The ender chest is counted too, but only from the last time you had it open -- the server
 * does not tell the client what is in a container that is closed, so there is nothing else to go
 * on. It is stale by definition rather than by accident, which is why it is shown apart from the
 * inventory figure instead of added into it.
 */
public class ResourceTracker extends BedwarsHud {
    private static final Item[] TRACKED = {
            Items.iron_ingot, Items.gold_ingot, Items.diamond, Items.emerald
    };

    private final ButtonSetting iron;
    private final ButtonSetting gold;
    private final ButtonSetting diamond;
    private final ButtonSetting emerald;
    private final ButtonSetting enderChest;

    private final Map<Item, Integer> inventory = new LinkedHashMap<Item, Integer>();
    private final Map<Item, Integer> chest = new LinkedHashMap<Item, Integer>();

    public ResourceTracker() {
        super("Resource Tracker", 0.02f, 0.42f);
        this.registerSetting(iron = new ButtonSetting("Iron", true));
        this.registerSetting(gold = new ButtonSetting("Gold", true));
        this.registerSetting(diamond = new ButtonSetting("Diamonds", true));
        this.registerSetting(emerald = new ButtonSetting("Emeralds", true));
        this.registerSetting(enderChest = new ButtonSetting("Include ender chest", true));
    }

    @Override
    public void onDisable() {
        inventory.clear();
        chest.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || !Utils.nullCheck()) return;

        if (Utils.getBedwarsStatus() != 2) {
            if (!inventory.isEmpty() || !chest.isEmpty()) {
                inventory.clear();
                chest.clear();
            }
            return;
        }

        countInventory();
        countOpenEnderChest();
    }

    private void countInventory() {
        inventory.clear();
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
            if (stack == null) continue;
            if (!isTracked(stack.getItem())) continue;
            add(inventory, stack.getItem(), stack.stackSize);
        }
    }

    /**
     * Snapshots the ender chest whenever it happens to be open.
     *
     * <p>Only the upper container is read. The lower slots of a chest screen are the player's own
     * inventory, and counting those here would double every figure on the overlay.
     */
    private void countOpenEnderChest() {
        if (!(mc.currentScreen instanceof GuiChest)) return;
        ContainerChest container = (ContainerChest) ((GuiChest) mc.currentScreen).inventorySlots;
        String title = Utils.stripColor(container.getLowerChestInventory().getDisplayName()
                .getUnformattedText());
        if (!title.toLowerCase().contains("ender chest")) return;

        chest.clear();
        int size = container.getLowerChestInventory().getSizeInventory();
        for (Slot slot : container.inventorySlots) {
            if (slot.slotNumber >= size) break;
            ItemStack stack = slot.getStack();
            if (stack == null || !isTracked(stack.getItem())) continue;
            add(chest, stack.getItem(), stack.stackSize);
        }
    }

    private void add(Map<Item, Integer> into, Item item, int count) {
        Integer previous = into.get(item);
        into.put(item, (previous == null ? 0 : previous) + count);
    }

    private boolean isTracked(Item item) {
        if (item == Items.iron_ingot) return iron.isToggled();
        if (item == Items.gold_ingot) return gold.isToggled();
        if (item == Items.diamond) return diamond.isToggled();
        if (item == Items.emerald) return emerald.isToggled();
        return false;
    }

    private String label(Item item) {
        if (item == Items.iron_ingot) return "§fIron";
        if (item == Items.gold_ingot) return "§6Gold";
        if (item == Items.diamond) return "§bDiamond";
        return "§2Emerald";
    }

    private String colour(Item item) {
        if (item == Items.iron_ingot) return "§f";
        if (item == Items.gold_ingot) return "§6";
        if (item == Items.diamond) return "§b";
        return "§2";
    }

    @Override
    protected boolean shouldDraw() {
        return Utils.getBedwarsStatus() == 2;
    }

    @Override
    protected List<String> lines() {
        List<String> out = new ArrayList<String>(TRACKED.length);
        for (Item item : TRACKED) {
            if (!isTracked(item)) continue;
            Integer held = inventory.get(item);
            Integer stored = chest.get(item);
            String line = label(item) + "§7: " + colour(item) + (held == null ? 0 : held);
            if (enderChest.isToggled()) {
                line += " §8+ " + colour(item) + (stored == null ? 0 : stored);
            }
            out.add(line);
        }
        return out;
    }
}
