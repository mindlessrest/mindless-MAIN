package mindless.module.impl.minigames;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.*;

public class ShopHelper extends Module {
    public ButtonSetting instantBuy;
    public ButtonSetting highlightAffordable;
    public ButtonSetting replaceClicks;
    public ButtonSetting preventDuplicate;
    public SliderSetting opacity;

    private static final Map<Item, Integer> INVENTORY_RESOURCES = new HashMap<>();
    private static final Map<Item, ItemCategory> CATEGORY = new HashMap<>();
    private static final Map<Item, Integer> PRIORITY = new HashMap<>();
    private static final EnumMap<ItemCategory, Integer> BEST_ITEMS = new EnumMap<>(ItemCategory.class);

    private static final String[] BEDWARS_SHOP = {
            "Quick Buy", "Blocks", "Melee", "Armor", "Tools",
            "Ranged", "Potions", "Utility", "Rotating Items", "Upgrades & Traps"
    };

    private static final int IRON_COLOR_BASE = 0xFFFFFF;
    private static final int GOLD_COLOR_BASE = 0xFFAA00;
    private static final int DIAMOND_COLOR_BASE = 0x55FFFF;
    private static final int EMERALD_COLOR_BASE = 0x00AA00;

    private static Field guiLeftField;
    private static Field guiTopField;

    static {
        register(ItemCategory.SWORD, Items.wooden_sword, Items.stone_sword, Items.iron_sword, Items.diamond_sword);
        register(ItemCategory.ARMOR, Items.chainmail_leggings, Items.iron_leggings, Items.diamond_leggings);
        register(ItemCategory.PICKAXE, Items.wooden_pickaxe, Items.iron_pickaxe, Items.golden_pickaxe, Items.diamond_pickaxe);
        register(ItemCategory.AXE, Items.wooden_axe, Items.stone_axe, Items.iron_axe, Items.diamond_axe);
        register(ItemCategory.STICK, Items.stick);
        register(ItemCategory.SHEARS, Items.shears);

        try {
            guiLeftField = GuiContainer.class.getDeclaredField("guiLeft");
            guiLeftField.setAccessible(true);
            guiTopField = GuiContainer.class.getDeclaredField("guiTop");
            guiTopField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                guiLeftField = GuiContainer.class.getDeclaredField("field_147003_i");
                guiLeftField.setAccessible(true);
                guiTopField = GuiContainer.class.getDeclaredField("field_147009_r");
                guiTopField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
        }
    }

    public ShopHelper() {
        super("ShopHelper", category.bedwars);
        this.registerSetting(instantBuy = new ButtonSetting("Instant buy", true));
        this.registerSetting(highlightAffordable = new ButtonSetting("Highlight affordable", true));
        this.registerSetting(replaceClicks = new ButtonSetting("Replace clicks", true));
        this.registerSetting(preventDuplicate = new ButtonSetting("Prevent duplicate", true));
        this.registerSetting(opacity = new SliderSetting("Opacity", 50, 10, 100, 5));
        this.canBeEnabled = true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!Utils.nullCheck()) return;
        if (!highlightAffordable.isToggled()) return;
        if (!isBedwarsShopOpen()) return;

        INVENTORY_RESOURCES.clear();
        BEST_ITEMS.clear();

        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
            if (stack == null) continue;
            Item item = stack.getItem();
            if (isResource(item)) {
                INVENTORY_RESOURCES.put(item, INVENTORY_RESOURCES.getOrDefault(item, 0) + stack.stackSize);
            }
            ItemCategory cat = CATEGORY.get(item);
            if (cat != null) {
                int prio = PRIORITY.get(item);
                Integer best = BEST_ITEMS.get(cat);
                if (best == null || prio > best) {
                    BEST_ITEMS.put(cat, prio);
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderGui(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!Utils.nullCheck()) return;
        if (!highlightAffordable.isToggled()) return;
        if (!(mc.currentScreen instanceof GuiChest)) return;

        GuiChest chest = (GuiChest) mc.currentScreen;
        if (!(chest.inventorySlots instanceof ContainerChest)) return;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        String title = container.getLowerChestInventory().getDisplayName().getUnformattedText();
        if (!isBedwarsTitle(title)) return;

        int guiLeft = getGuiLeft(chest);
        int guiTop = getGuiTop(chest);
        if (guiLeft == -1) return;

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GL11.glColorMask(true, true, true, true);

        int alpha = (int) (opacity.getInput() * 2.55);

        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = container.inventorySlots.get(i);
            if (slot == null || !slot.getHasStack()) continue;

            ItemStack stack = slot.getStack();
            ItemCost cost = getCostFromLore(stack);
            if (cost == null) continue;
            if (!shouldHighlight(stack, cost)) continue;

            int color = getResourceColor(cost.resourceType, alpha);
            int x = guiLeft + slot.xDisplayPosition;
            int y = guiTop + slot.yDisplayPosition;
            Gui.drawRect(x, y, x + 16, y + 16, color);
        }

        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
    }

    /**
     * Called from the transformer/mixin when handleMouseClick fires.
     * Returns: 0 = allow, 1 = cancel, 2 = replace with middle click
     */
    public int onShopClick(Slot slot, int clickedButton, int clickType) {
        if (!Utils.nullCheck()) return 0;
        if (!(mc.currentScreen instanceof GuiChest)) return 0;

        GuiChest chest = (GuiChest) mc.currentScreen;
        if (!(chest.inventorySlots instanceof ContainerChest)) return 0;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        String title = container.getLowerChestInventory().getDisplayName().getUnformattedText();
        if (!isBedwarsTitle(title)) return 0;

        if (slot == null || !slot.getHasStack()) return 0;
        ItemStack stack = slot.getStack();
        Item item = stack.getItem();

        // Prevent duplicate purchases
        if (preventDuplicate.isToggled() && !title.contains("Upgrades & Traps")) {
            ItemCost cost = getCostFromLore(stack);
            if (cost != null && !shouldHighlight(stack, cost)) {
                if ((item instanceof ItemSword || item == Items.stick) && item != Items.golden_sword) {
                    Utils.sendMessage(EnumChatFormatting.RED + "[ShopHelper] Prevented duplicate purchase!");
                    return 1; // cancel
                }
            }
        }

        // Replace clicks with middle click for quick buy
        if (replaceClicks.isToggled() && clickType == 0 && !title.contains("Upgrades & Traps")) {
            return 2; // replace with middle click
        }

        return 0; // allow
    }

    public static ItemCost getCostFromLore(ItemStack stack) {
        if (stack == null) return null;
        EntityPlayer player = mc.thePlayer;
        if (player == null) return null;

        List<String> lore = stack.getTooltip(player, false);
        for (String line : lore) {
            String clean = EnumChatFormatting.getTextWithoutFormattingCodes(line);
            if (clean == null) continue;
            String[] split = clean.split(" ");

            Item resourceItem = null;
            int amount = 0;

            if (clean.contains("Cost:") && split.length >= 3) {
                if (!isNumeric(split[1])) continue;
                amount = Integer.parseInt(split[1]);
                String type = split[2].toLowerCase();
                if (type.contains("unlocked")) return null;
                resourceItem = getResourceFromName(type);
            } else if (clean.contains("Tier") && split.length >= 3) {
                int commaIndex = clean.lastIndexOf(',');
                if (commaIndex == -1 || commaIndex + 2 >= clean.length()) continue;
                String costPart = clean.substring(commaIndex + 2);
                String[] costSplit = costPart.split(" ");
                if (costSplit.length < 2 || !isNumeric(costSplit[0])) continue;
                amount = Integer.parseInt(costSplit[0]);
                String type = costSplit[1].toLowerCase();
                if (type.contains("unlocked")) return null;
                resourceItem = getResourceFromName(type);
            }

            if (resourceItem != null) return new ItemCost(resourceItem, amount);
        }
        return null;
    }

    public static boolean shouldHighlight(ItemStack stack, ItemCost cost) {
        if (stack == null || cost == null) return false;

        int available = INVENTORY_RESOURCES.getOrDefault(cost.resourceType, 0);
        boolean affordable = available >= cost.amount;

        // For upgrades, just check affordability
        if (mc.currentScreen instanceof GuiChest) {
            GuiChest chest = (GuiChest) mc.currentScreen;
            if (chest.inventorySlots instanceof ContainerChest) {
                String title = ((ContainerChest) chest.inventorySlots)
                        .getLowerChestInventory().getDisplayName().getUnformattedText();
                if (title.contains("Upgrades & Traps")) {
                    return affordable;
                }
            }
        }

        // For diamond items, just check affordability (always show as purchasable upgrade)
        if (cost.resourceType == Items.diamond) {
            return affordable;
        }

        // For categorized items, only highlight if it's an upgrade over what we have
        Item item = stack.getItem();
        ItemCategory cat = CATEGORY.get(item);
        if (cat != null) {
            int priority = PRIORITY.get(item);
            Integer bestOwned = BEST_ITEMS.get(cat);
            int best = bestOwned != null ? bestOwned : -1;
            return priority > best && affordable;
        }

        return affordable;
    }

    private boolean isBedwarsShopOpen() {
        if (!(mc.currentScreen instanceof GuiChest)) return false;
        GuiChest chest = (GuiChest) mc.currentScreen;
        if (!(chest.inventorySlots instanceof ContainerChest)) return false;
        String title = ((ContainerChest) chest.inventorySlots)
                .getLowerChestInventory().getDisplayName().getUnformattedText();
        return isBedwarsTitle(title);
    }

    private static boolean isBedwarsTitle(String title) {
        for (String s : BEDWARS_SHOP) {
            if (title.equals(s)) return true;
        }
        return false;
    }

    private static boolean isResource(Item item) {
        return item == Items.iron_ingot || item == Items.gold_ingot
                || item == Items.diamond || item == Items.emerald;
    }

    private static Item getResourceFromName(String type) {
        if (type.startsWith("iron")) return Items.iron_ingot;
        if (type.startsWith("gold")) return Items.gold_ingot;
        if (type.startsWith("diamond")) return Items.diamond;
        if (type.startsWith("emerald")) return Items.emerald;
        return null;
    }

    private static int getResourceColor(Item item, int alpha) {
        int rgb;
        if (item == Items.iron_ingot) rgb = IRON_COLOR_BASE;
        else if (item == Items.gold_ingot) rgb = GOLD_COLOR_BASE;
        else if (item == Items.diamond) rgb = DIAMOND_COLOR_BASE;
        else if (item == Items.emerald) rgb = EMERALD_COLOR_BASE;
        else rgb = 0xAAAAAA;
        return (alpha << 24) | rgb;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static void register(ItemCategory category, Item... items) {
        for (int i = 0; i < items.length; i++) {
            CATEGORY.put(items[i], category);
            PRIORITY.put(items[i], i);
        }
    }

    private static int getGuiLeft(GuiContainer container) {
        try {
            if (guiLeftField != null) return guiLeftField.getInt(container);
        } catch (Exception ignored) {}
        return -1;
    }

    private static int getGuiTop(GuiContainer container) {
        try {
            if (guiTopField != null) return guiTopField.getInt(container);
        } catch (Exception ignored) {}
        return -1;
    }

    public static class ItemCost {
        public final Item resourceType;
        public final int amount;

        public ItemCost(Item resourceType, int amount) {
            this.resourceType = resourceType;
            this.amount = amount;
        }
    }

    private enum ItemCategory {
        SWORD, ARMOR, PICKAXE, AXE, STICK, SHEARS
    }
}
