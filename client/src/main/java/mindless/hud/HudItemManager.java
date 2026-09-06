package mindless.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class HudItemManager implements Iterable<HudItem> {
    private final List<HudItem> items = new ArrayList<HudItem>();

    public void register(HudItem item) {
        if (item == null) return;
        HudItem existing = get(item.getHudItemName());
        if (existing != null && existing != item) items.remove(existing);
        if (!items.contains(item)) items.add(item);
    }

    public void unregister(HudItem item) {
        items.remove(item);
    }

    public HudItem get(String name) {
        if (name == null) return null;
        for (HudItem item : items) {
            if (name.equalsIgnoreCase(item.getHudItemName())) return item;
        }
        return null;
    }

    public List<HudItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void resetAll() {
        for (HudItem item : items) item.resetHudItem();
    }

    @Override
    public Iterator<HudItem> iterator() {
        return getItems().iterator();
    }
}
