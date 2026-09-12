package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.ItemListSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.ItemEffectRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ItemEffects extends Module {
    public final ButtonSetting dropped;
    public final ButtonSetting held;
    public final ButtonSetting inventory;
    public final ButtonSetting glow;
    public final ButtonSetting outline;
    public final ButtonSetting blur;
    public final ColorSetting color;
    public final SliderSetting outlineThickness;
    public final SliderSetting glowRadius;
    public final SliderSetting glowStrength;
    public final SliderSetting blurRadius;
    public final SliderSetting blurStrength;
    public final SliderSetting distance;
    public final SliderSetting filterMode;
    public final ItemListSetting items;

    public ItemEffects() {
        super("Item Effects", "Restyles dropped, held and inventory item models.", category.render);
        GroupSetting targets = new GroupSetting("Targets");
        registerSetting(targets);
        registerSetting(dropped = new ButtonSetting(targets, "Dropped items", true));
        registerSetting(held = new ButtonSetting(targets, "Held item", true));
        registerSetting(inventory = new ButtonSetting(targets, "Inventory items", true));
        registerSetting(distance = new SliderSetting(targets, "Dropped distance", 48, 8, 128, 4));

        GroupSetting appearance = new GroupSetting("Appearance");
        registerSetting(appearance);
        registerSetting(color = new ColorSetting(appearance, "Effect color", 111, 190, 255));
        registerSetting(outline = new ButtonSetting(appearance, "Outline", true));
        registerSetting(outlineThickness = new SliderSetting(appearance, "Outline thickness", 1.5, 0.5, 4.0, 0.25));
        registerSetting(glow = new ButtonSetting(appearance, "Glow", true));
        registerSetting(glowRadius = new SliderSetting(appearance, "Glow radius", 4.0, 1.0, 10.0, 0.5));
        registerSetting(glowStrength = new SliderSetting(appearance, "Glow strength", 1.0, 0.2, 2.0, 0.1));
        registerSetting(blur = new ButtonSetting(appearance, "Soft blur", false));
        registerSetting(blurRadius = new SliderSetting(appearance, "Blur radius", 2.5, 1.0, 8.0, 0.5));
        registerSetting(blurStrength = new SliderSetting(appearance, "Blur strength", 0.35, 0.1, 1.0, 0.05));

        GroupSetting filtering = new GroupSetting("Filter");
        registerSetting(filtering);
        registerSetting(filterMode = new SliderSetting(filtering, "Mode", 0,
                new String[]{"All items", "Only listed", "Exclude listed"}));
        registerSetting(items = new ItemListSetting(filtering, "Items"));
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        int mode = (int) filterMode.getInput();
        if (mode == 0) return true;
        boolean listed = items.matches(stack);
        return mode == 1 ? listed : !listed;
    }

    public boolean hasEffects() {
        return outline.isToggled() || glow.isToggled() || blur.isToggled();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorld(RenderWorldLastEvent event) {
        ItemEffectRenderer.renderDropped(this, event.partialTicks);
    }

    @Override
    public void onDisable() {
        ItemEffectRenderer.release();
    }
}
