package mindless.module.impl.minigames;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import mindless.utility.HypixelLanguage;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Hypixel Bedwars shop and marks what is worth buying.
 *
 * Cost comes out of the item's own lore rather than a hardcoded price table, so a balance patch
 * does not silently break it. Tiered gear is tracked separately from raw affordability: an iron
 * sword you can pay for is still not worth buying when a diamond one is already in the hotbar.
 */
public class ShopHelper extends Module {

    /** Let the click through untouched. */
    public static final int CLICK_ALLOW = 0;
    /** Swallow the click entirely. */
    public static final int CLICK_CANCEL = 1;
    public static final int CLICK_PURCHASE = 2;

    private static final int IRON_TINT = 0xE8E8E8;
    private static final int GOLD_TINT = 0xFFAA00;
    private static final int DIAMOND_TINT = 0x55FFFF;
    private static final int EMERALD_TINT = 0x00C24A;

    private static final Map<Item, Gear> GEAR = new HashMap<Item, Gear>();
    private static final Map<Item, Integer> TIER = new HashMap<Item, Integer>();

    static {
        // Worst to best, so a higher index is a better item.
        registerTiers(Gear.SWORD, Items.wooden_sword, Items.stone_sword, Items.iron_sword, Items.diamond_sword);
        registerTiers(Gear.ARMOR, Items.chainmail_boots, Items.iron_boots, Items.diamond_boots);
        registerTiers(Gear.PICKAXE, Items.wooden_pickaxe, Items.iron_pickaxe, Items.golden_pickaxe, Items.diamond_pickaxe);
        registerTiers(Gear.AXE, Items.wooden_axe, Items.stone_axe, Items.iron_axe, Items.diamond_axe);
        registerTiers(Gear.SHEARS, Items.shears);
    }

    public ButtonSetting highlightAffordable;
    public ButtonSetting replaceClicks;
    public ButtonSetting preventDuplicate;
    public ButtonSetting onlyInGame;
    public ButtonSetting announceBlocked;
    public SliderSetting opacity;

    private final Map<Item, Integer> resources = new HashMap<Item, Integer>();
    private final EnumMap<Gear, Integer> owned = new EnumMap<Gear, Integer>(Gear.class);
    private final Map<ItemStack, Integer> highlights = new IdentityHashMap<ItemStack, Integer>();
    private int highlightRefreshTicks;

    public ShopHelper() {
        super("Shop Helper", "Tints what you can afford and blocks bad clicks.", category.bedwars);
        this.registerSetting(highlightAffordable = new ButtonSetting("Highlight affordable", true));
        this.registerSetting(replaceClicks = new ButtonSetting("Replace clicks", true));
        this.registerSetting(preventDuplicate = new ButtonSetting("Prevent duplicate", true));
        this.registerSetting(onlyInGame = new ButtonSetting("Only in game", true));
        this.registerSetting(announceBlocked = new ButtonSetting("Announce blocked buys", true));
        this.registerSetting(opacity = new SliderSetting("Opacity", "%", 50.0, 10.0, 100.0, 5.0));
        this.canBeEnabled = true;
    }

    @Override
    public void onDisable() {
        resources.clear();
        owned.clear();
        highlights.clear();
        highlightRefreshTicks = 0;
    }

    // ------------------------------------------------------------------ state

    /**
     * Rebuilt every tick the shop is open.
     *
     * Armour lives in its own inventory array, so scanning only the main inventory left every
     * armour tier looking unowned and every armour entry highlighted as an upgrade.
     */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!Utils.nullCheck() || !isShopOpen(mc.currentScreen)) {
            resources.clear();
            owned.clear();
            highlights.clear();
            highlightRefreshTicks = 0;
            return;
        }

        resources.clear();
        owned.clear();

        scan(mc.thePlayer.inventory.mainInventory);
        scan(mc.thePlayer.inventory.armorInventory);
        if (--highlightRefreshTicks <= 0) {
            rebuildHighlights((GuiContainer) mc.currentScreen);
            highlightRefreshTicks = 5;
        }
    }

    private void scan(ItemStack[] contents) {
        if (contents == null) return;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;

            Item item = stack.getItem();
            if (isResource(item)) {
                Integer held = resources.get(item);
                resources.put(item, (held == null ? 0 : held) + stack.stackSize);
            }

            Gear gear = GEAR.get(item);
            if (gear == null) continue;
            Integer tier = TIER.get(item);
            if (tier == null) continue;
            Integer best = owned.get(gear);
            if (best == null || tier > best) {
                owned.put(gear, tier);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** The tint to lay behind a shop slot, or 0 when it should be left alone. */
    public int highlightColour(ItemStack stack) {
        if (!highlightAffordable.isToggled() || stack == null) return 0;
        if (onlyInGame.isToggled() && !inGame()) return 0;

        Integer cached = highlights.get(stack);
        if (cached != null) return cached;

        int colour = computeHighlightColour(stack);
        highlights.put(stack, colour);
        return colour;
    }

    private int computeHighlightColour(ItemStack stack) {

        Cost cost = costOf(stack);
        if (cost == null || !canAfford(cost)) return 0;
        if (isTieredDuplicate(stack)) return 0;

        int alpha = Math.round(255.0f * (float) (opacity.getInput() / 100.0));
        return (Math.max(0, Math.min(255, alpha)) << 24) | tintFor(cost.resource);
    }

    private void rebuildHighlights(GuiContainer gui) {
        highlights.clear();
        if (gui == null || gui.inventorySlots == null || gui.inventorySlots.inventorySlots == null) return;
        for (Object entry : gui.inventorySlots.inventorySlots) {
            if (!(entry instanceof Slot)) continue;
            Slot slot = (Slot) entry;
            if (!slot.getHasStack()) continue;
            ItemStack stack = slot.getStack();
            highlights.put(stack, computeHighlightColour(stack));
        }
    }

    /** What to do with a click on a shop slot. */
    public int decideClick(GuiContainer gui, Slot slot, int clickType, int clickedButton) {
        if (!Utils.nullCheck() || slot == null || !slot.getHasStack()) return CLICK_ALLOW;
        if (onlyInGame.isToggled() && !inGame()) return CLICK_ALLOW;

        String title = titleOf(gui);
        if (title == null) return CLICK_ALLOW;

        boolean upgrades = HypixelLanguage.equals(title, HypixelLanguage.Key.UPGRADES_TRAPS);
        if (!upgrades && !isShopPage(title) && !isLargeBedwarsMenu(gui)) return CLICK_ALLOW;

        ItemStack stack = slot.getStack();

        // A tier you already match or beat is never worth re-buying, whether or not you could
        // afford it. The old check keyed off the highlight, so it also fired when the only problem
        // was being short on iron.
        if (preventDuplicate.isToggled() && !upgrades && isTieredDuplicate(stack)) {
            if (announceBlocked.isToggled()) {
                Utils.sendMessage(EnumChatFormatting.RED + "[ShopHelper] " + EnumChatFormatting.GRAY
                        + "You already have " + stack.getDisplayName() + EnumChatFormatting.GRAY + " or better.");
            }
            return CLICK_CANCEL;
        }

        if (clickedButton == 0 && clickType == 0 && replaceClicks.isToggled()) {
            return CLICK_PURCHASE;
        }
        return CLICK_ALLOW;
    }

    public boolean isShopOpen(Object screen) {
        String title = titleOf(screen);
        return title != null && (isShopPage(title)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.UPGRADES_TRAPS)
                || isLargeBedwarsMenu(screen));
    }

    private boolean inGame() {
        return Utils.getBedwarsStatus() == 2 || isShopOpen(mc.currentScreen);
    }

    private static String titleOf(Object screen) {
        if (!(screen instanceof GuiChest)) return null;
        GuiChest chest = (GuiChest) screen;
        if (!(chest.inventorySlots instanceof ContainerChest)) return null;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        if (container.getLowerChestInventory() == null) return null;
        String title = container.getLowerChestInventory().getDisplayName().getUnformattedText();
        return title == null ? null : EnumChatFormatting.getTextWithoutFormattingCodes(title).trim();
    }

    private static boolean isShopPage(String title) {
        return HypixelLanguage.equals(title, HypixelLanguage.Key.QUICK_BUY)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.BLOCKS)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.MELEE)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.ARMOR)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.TOOLS)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.RANGED)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.POTIONS)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.UTILITY)
                || HypixelLanguage.equals(title, HypixelLanguage.Key.ROTATING_ITEMS);
    }

    private static boolean isLargeBedwarsMenu(Object screen) {
        if (Utils.getBedwarsStatus() != 2 || !(screen instanceof GuiChest)) return false;
        ContainerChest container = (ContainerChest) ((GuiChest) screen).inventorySlots;
        return container.getLowerChestInventory() != null
                && container.getLowerChestInventory().getSizeInventory() >= 54;
    }

    private boolean canAfford(Cost cost) {
        Integer held = resources.get(cost.resource);
        return (held == null ? 0 : held) >= cost.amount;
    }

    /** True when the player already holds this gear category at an equal or better tier. */
    private boolean isTieredDuplicate(ItemStack stack) {
        if (stack == null) return false;
        Gear gear = GEAR.get(stack.getItem());
        if (gear == null) return false;
        Integer tier = TIER.get(stack.getItem());
        if (tier == null) return false;
        Integer best = owned.get(gear);
        return best != null && best >= tier;
    }

    // ------------------------------------------------------------------ lore

    /**
     * Pulls the price out of the tooltip.
     *
     * Shop entries read "Cost: 20 Iron"; upgrade entries put the price at the end of a tier line,
     * "Tier 2, 4 Diamonds". Anything already bought says so instead of naming a price.
     */
    public Cost costOf(ItemStack stack) {
        if (stack == null || mc.thePlayer == null) return null;

        List<String> lore;
        try {
            lore = stack.getTooltip(mc.thePlayer, false);
        } catch (Throwable unreadable) {
            return null;
        }
        if (lore == null) return null;

        for (int i = 0; i < lore.size(); i++) {
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(lore.get(i));
            if (line == null) continue;
            String lower = line.toLowerCase();

            if (HypixelLanguage.contains(line, HypixelLanguage.Key.UNLOCKED)
                    || HypixelLanguage.contains(line, HypixelLanguage.Key.MAXED)) {
                return null;
            }

            Cost cost = parseCost(line, lower);
            if (cost != null) return cost;
        }
        return null;
    }

    private static Cost parseCost(String line, String lower) {
        String tail;
        int colon = line.indexOf(':');
        if (colon >= 0 && HypixelLanguage.contains(line.substring(0, colon), HypixelLanguage.Key.COST)) {
            tail = line.substring(colon + 1).trim();
        } else if (HypixelLanguage.contains(line, HypixelLanguage.Key.TIER)) {
            int comma = line.lastIndexOf(',');
            if (comma < 0 || comma + 1 >= line.length()) return null;
            tail = line.substring(comma + 1).trim();
        } else {
            return null;
        }

        int space = tail.indexOf(' ');
        if (space <= 0) return null;

        int amount = parsePositiveInt(tail.substring(0, space));
        if (amount < 0) return null;

        Item resource = resourceNamed(tail.substring(space + 1).trim().toLowerCase());
        return resource == null ? null : new Cost(resource, amount);
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.isEmpty() || value.length() > 6) return -1;
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') return -1;
            total = total * 10 + (character - '0');
        }
        return total;
    }

    private static Item resourceNamed(String name) {
        if (HypixelLanguage.contains(name, HypixelLanguage.Key.IRON)) return Items.iron_ingot;
        if (HypixelLanguage.contains(name, HypixelLanguage.Key.GOLD)) return Items.gold_ingot;
        if (HypixelLanguage.contains(name, HypixelLanguage.Key.DIAMOND)) return Items.diamond;
        if (HypixelLanguage.contains(name, HypixelLanguage.Key.EMERALD)) return Items.emerald;
        return null;
    }

    private static boolean isResource(Item item) {
        return item == Items.iron_ingot || item == Items.gold_ingot
                || item == Items.diamond || item == Items.emerald;
    }

    private static int tintFor(Item resource) {
        if (resource == Items.iron_ingot) return IRON_TINT;
        if (resource == Items.gold_ingot) return GOLD_TINT;
        if (resource == Items.diamond) return DIAMOND_TINT;
        if (resource == Items.emerald) return EMERALD_TINT;
        return 0xAAAAAA;
    }

    private static void registerTiers(Gear gear, Item... items) {
        for (int i = 0; i < items.length; i++) {
            GEAR.put(items[i], gear);
            TIER.put(items[i], i);
        }
    }

    public static final class Cost {
        public final Item resource;
        public final int amount;

        Cost(Item resource, int amount) {
            this.resource = resource;
            this.amount = amount;
        }
    }

    private enum Gear {
        SWORD, ARMOR, PICKAXE, AXE, SHEARS
    }
}
