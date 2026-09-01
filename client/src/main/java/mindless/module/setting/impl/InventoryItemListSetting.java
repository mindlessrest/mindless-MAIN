package mindless.module.setting.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
public class InventoryItemListSetting extends ItemListSetting {
    private static final int DEFAULT_ASSIGNED_SLOT = 1;
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
