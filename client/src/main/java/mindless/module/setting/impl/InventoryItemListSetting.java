package mindless.module.setting.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * An item list where every entry also carries the hotbar slot it should end up in.
 *
 * <p>Slots are held by position rather than keyed by the item, because one item is allowed to
 * appear in the list more than once. Wool is the case that needs it: two stacks going to two
 * chosen slots is one entry per stack, and a map from item to slot has nowhere to put the second.
 * The list and the slots move together -- adding, removing and reordering all touch both.
 */
public class InventoryItemListSetting extends ItemListSetting {
    private static final int DEFAULT_ASSIGNED_SLOT = 1;
    /**
     * The one registry name allowed in the list twice.
     *
     * <p>Deliberately not a general rule. Two entries for the same sword are two rows competing
     * for one item and the second can never be satisfied; two entries for wool are two stacks,
     * which is an ordinary thing to be carrying and an ordinary thing to want laid out.
     */
    private static final String DUPLICATABLE_REGISTRY = "minecraft:wool";

    private final List<Integer> assignedSlots = new ArrayList<Integer>();
    private final List<Integer> capturedSlots = new ArrayList<Integer>();

    public InventoryItemListSetting(String name) {
        super(name);
    }

    public InventoryItemListSetting(String name, String... legacyProfileKeys) {
        super(name, legacyProfileKeys);
    }

    public InventoryItemListSetting(GroupSetting group, String name) {
        super(group, name);
    }

    public InventoryItemListSetting(GroupSetting group, String name, String... legacyProfileKeys) {
        super(group, name, legacyProfileKeys);
    }

    /** Whether another copy of this entry may be added. */
    public static boolean allowsDuplicates(String storageId) {
        return DUPLICATABLE_REGISTRY.equals(registryOf(storageId));
    }

    private static String registryOf(String storageId) {
        if (storageId == null || storageId.isEmpty() || storageId.charAt(0) == '@') {
            return storageId;
        }
        if (storageId.endsWith(":*")) {
            return storageId.substring(0, storageId.length() - 2);
        }
        String[] parts = storageId.split(":");
        return parts.length >= 3 ? parts[0] + ":" + parts[1] : storageId;
    }

    @Override
    public void addItem(String storageId) {
        if (storageId == null || storageId.isEmpty()) {
            return;
        }
        if (containsItem(storageId) && !allowsDuplicates(storageId)) {
            return;
        }

        // Straight onto the list rather than through addBlock, which refuses anything already
        // present and would silently drop the second wool entry.
        syncSlots();
        getItems().add(storageId);
        assignedSlots.add(DEFAULT_ASSIGNED_SLOT);
    }

    @Override
    public void removeItem(String storageId) {
        syncSlots();
        int index = getItems().indexOf(storageId);
        if (index < 0) {
            return;
        }
        removeItem(index);
    }

    /** Removes one row, which is the only way to remove the right one when an item repeats. */
    public void removeItem(int index) {
        syncSlots();
        List<String> items = getItems();
        if (index < 0 || index >= items.size()) {
            return;
        }
        items.remove(index);
        assignedSlots.remove(index);
    }

    public int getAssignedSlot(int index) {
        syncSlots();
        if (index < 0 || index >= assignedSlots.size()) {
            return DEFAULT_ASSIGNED_SLOT;
        }
        Integer slot = assignedSlots.get(index);
        return slot == null ? DEFAULT_ASSIGNED_SLOT : slot;
    }

    public void setAssignedSlot(int index, Integer slot) {
        syncSlots();
        if (index < 0 || index >= assignedSlots.size()) {
            return;
        }
        assignedSlots.set(index, slot == null || slot < 1 || slot > 9 ? DEFAULT_ASSIGNED_SLOT : slot);
    }

    /** The first row for this item. Kept for callers that have an id and no row. */
    public Integer getAssignedSlot(String storageId) {
        if (storageId == null) {
            return null;
        }
        int index = getItems().indexOf(storageId);
        return index < 0 ? null : getAssignedSlot(index);
    }

    public void setAssignedSlot(String storageId, Integer slot) {
        if (storageId == null) {
            return;
        }
        int index = getItems().indexOf(storageId);
        if (index >= 0) {
            setAssignedSlot(index, slot);
        }
    }

    public void moveItem(String storageId, int toIndex) {
        moveItem(getItems().indexOf(storageId), toIndex);
    }

    /** Reorders one row, carrying its slot with it. */
    public void moveItem(int fromIndex, int toIndex) {
        syncSlots();
        List<String> items = getItems();
        if (fromIndex < 0 || fromIndex >= items.size()) {
            return;
        }

        int clampedIndex = Math.max(0, Math.min(toIndex, items.size() - 1));
        if (fromIndex == clampedIndex) {
            return;
        }

        String storageId = items.remove(fromIndex);
        Integer slot = assignedSlots.remove(fromIndex);
        items.add(clampedIndex, storageId);
        assignedSlots.add(clampedIndex, slot);
    }

    /**
     * Brings the slot list back to the same length as the item list.
     *
     * <p>The item list is reachable directly through {@code getItems()} and the inherited
     * add/remove helpers, so it can be changed without this class hearing about it. Rather than
     * chase every route in, the two are reconciled before any read: extra rows get the default
     * slot and orphaned slots are dropped.
     */
    private void syncSlots() {
        int size = getItems().size();
        while (assignedSlots.size() > size) {
            assignedSlots.remove(assignedSlots.size() - 1);
        }
        while (assignedSlots.size() < size) {
            assignedSlots.add(DEFAULT_ASSIGNED_SLOT);
        }
    }

    @Override
    protected void captureDefault() {
        super.captureDefault();
        capturedSlots.clear();
        capturedSlots.addAll(assignedSlots);
    }

    @Override
    public void resetToDefault() {
        super.resetToDefault();
        assignedSlots.clear();
        assignedSlots.addAll(capturedSlots);
        syncSlots();
    }

    @Override
    public void loadProfile(JsonObject data) {
        String key = null;
        if (data.has(getProfileKey())) {
            key = getProfileKey();
        }
        else if (data.has(getName())) {
            key = getName();
        }
        else {
            for (String legacyProfileKey : getLegacyProfileKeys()) {
                if (data.has(legacyProfileKey)) {
                    key = legacyProfileKey;
                    break;
                }
            }
        }

        if (key == null) {
            return;
        }

        getItems().clear();
        assignedSlots.clear();
        JsonElement element = data.get(key);
        if (!element.isJsonArray()) {
            return;
        }

        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry == null || entry.isJsonNull()) {
                continue;
            }

            if (entry.isJsonPrimitive()) {
                addLoadedRow(entry.getAsString(), DEFAULT_ASSIGNED_SLOT);
                continue;
            }

            if (!entry.isJsonObject()) {
                continue;
            }

            JsonObject object = entry.getAsJsonObject();
            if (!object.has("id")) {
                continue;
            }

            int slot = DEFAULT_ASSIGNED_SLOT;
            if (object.has("slot") && object.get("slot").isJsonPrimitive()) {
                int configuredSlot = object.get("slot").getAsInt();
                if (configuredSlot >= 1 && configuredSlot <= 9) {
                    slot = configuredSlot;
                }
            }
            addLoadedRow(object.get("id").getAsString(), slot);
        }
    }

    private void addLoadedRow(String storageId, int slot) {
        if (storageId == null || storageId.isEmpty()) {
            return;
        }
        if (getItems().contains(storageId) && !allowsDuplicates(storageId)) {
            return;
        }
        getItems().add(storageId);
        assignedSlots.add(slot);
    }

    @Override
    public JsonArray toJsonArray() {
        syncSlots();
        JsonArray array = new JsonArray();
        List<String> items = getItems();
        for (int i = 0; i < items.size(); i++) {
            JsonObject object = new JsonObject();
            object.addProperty("id", items.get(i));
            object.add("slot", new JsonPrimitive(getAssignedSlot(i)));
            array.add(object);
        }
        return array;
    }
}
