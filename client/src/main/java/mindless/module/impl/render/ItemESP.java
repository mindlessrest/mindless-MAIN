package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AccessorBridge;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.ModuleFont;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
public class ItemESP extends Module {
private enum Category {
        DIAMOND("Diamond", 0x00E5FF),
        EMERALD("Emerald", 0x2ECC71),
        GOLD("Gold", 0xFFD700),
        IRON("Iron", 0xE0E0E0),
        SWORD("Swords", 0xB0C4DE),
        BOW("Bow", 0xE6C300),
        PEARL("Ender pearl", 0x00CED1),
        GAPPLE("Golden apple", 0xFFAA00),
        FIREBALL("Fireball", 0xFF4500),
        TNT("TNT", 0xFF2222),
        BEDBUG("Bedbug", 0x9AA0A6),
        TOWER("Tower", 0xC08040),
        INVIS("Invisibility potion", 0xB39DDB),
        SPEED("Speed potion", 0x7FC7FF),
        JUMP("Jump potion", 0x9CCC65);

        private final String label;
        private final int color;
        private ButtonSetting setting;

        Category(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final String[] STYLES = {"Card", "Icon only", "Text only"};
    private static final int STYLE_CARD = 0;
    private static final int STYLE_ICON_ONLY = 1;
    private static final int STYLE_TEXT_ONLY = 2;

    private static final String[] COUNT_FORMATS = {"64", "x64", "64x"};
    private static final String[] NAME_STYLES = {"Full", "Short"};

    private final SliderSetting style;
    private final SliderSetting font;
    private final SliderSetting maxDistance;
    private final SliderSetting iconScale;
    private final SliderSetting backgroundAlpha;
    private final ButtonSetting stackLayout;
    private final ButtonSetting outline;
    private final SliderSetting outlineThickness;
    private final ButtonSetting showName;
    private final SliderSetting nameStyle;
    private final ColorSetting nameColor;
    private final ButtonSetting showDistance;
    private final ColorSetting distanceColor;
    private final ButtonSetting showCount;
    private final SliderSetting countFormat;
    private final ButtonSetting hideSingleCount;
    private final ColorSetting countColor;
    private final ButtonSetting hideInGui;
private static final double STACK_RADIUS_SQ = 9.0D;
    private static final double TIGHT_RADIUS_SQ = 2.25D;
    private static final float SPREAD_GAP = 4.0F;

    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<Card> cards = new ArrayList<Card>();
    private final List<Card> cluster = new ArrayList<Card>();
    private boolean[] clustered = new boolean[64];
    private final double[] projected = new double[3];
    private RenderUtils.ProjectionContext projectionContext;

    public ItemESP() {
        super("Resource ESP", "Shows dropped BedWars resources and utility items.", category.render);
        this.liteModule = true;

        GroupSetting items = new GroupSetting("Items");
        registerSetting(items);
        for (Category c : Category.values()) {
            registerSetting(c.setting = new ButtonSetting(items, c.label, true));
        }

        GroupSetting card = new GroupSetting("Card");
        registerSetting(card);
        registerSetting(style = new SliderSetting(card, "Style", STYLE_CARD, STYLES));
        registerSetting(font = new SliderSetting(card, "Font", 0, ModuleFont.options()));
        registerSetting(showCount = new ButtonSetting(card, "Show count", true));
        registerSetting(countFormat = new SliderSetting(card, "Count format", 0, COUNT_FORMATS));
        registerSetting(hideSingleCount = new ButtonSetting(card, "Hide count of 1", false));
        registerSetting(countColor = new ColorSetting(card, "Count color", 255, 255, 255));
        registerSetting(showName = new ButtonSetting(card, "Show name", false));
        registerSetting(nameStyle = new SliderSetting(card, "Name style", 0, NAME_STYLES));
        registerSetting(nameColor = new ColorSetting(card, "Name color", 255, 255, 255));
        registerSetting(showDistance = new ButtonSetting(card, "Show distance", false));
        registerSetting(distanceColor = new ColorSetting(card, "Distance color", 204, 204, 204));
        registerSetting(outline = new ButtonSetting(card, "Outline", true));
        registerSetting(outlineThickness = new SliderSetting(card, "Outline thickness", 1.0, 0.5, 5.0, 0.25));
        registerSetting(iconScale = new SliderSetting(card, "Icon scale", "x", 1.0, 0.0, 2.0, 0.05));
        registerSetting(backgroundAlpha = new SliderSetting(card, "Background alpha", 170, 0, 255, 5));

        registerSetting(stackLayout = new ButtonSetting("Stack layout", true));
        registerSetting(hideInGui = new ButtonSetting("Hide in GUI", true));
        registerSetting(maxDistance = new SliderSetting("Max distance", 128.0, 16.0, 512.0, 8.0));
    }

    @Override
    public void guiUpdate() {
        int mode = (int) style.getInput();
        boolean icon = mode != STYLE_TEXT_ONLY;
        boolean chrome = mode == STYLE_CARD;

        iconScale.setVisible(icon, this);
        outline.setVisible(chrome, this);
        outlineThickness.setVisible(chrome && outline.isToggled(), this);
        backgroundAlpha.setVisible(chrome, this);

        boolean count = showCount.isToggled();
        countFormat.setVisible(count, this);
        hideSingleCount.setVisible(count, this);
        countColor.setVisible(count, this);

        boolean name = showName.isToggled();
        nameStyle.setVisible(name, this);
        nameColor.setVisible(name, this);

        distanceColor.setVisible(showDistance.isToggled(), this);
    }

    /**
     * Icon-only drops every text row as well as the panel, so the drop reads as the item and
     * nothing else. Text-only keeps the rows and drops the icon.
     */
    private boolean drawIcons() {
        return (int) style.getInput() != STYLE_TEXT_ONLY;
    }

    private boolean drawChrome() {
        return (int) style.getInput() == STYLE_CARD;
    }

    private boolean drawLabels() {
        return (int) style.getInput() != STYLE_ICON_ONLY;
    }

    private String countLabel(int count) {
        if (hideSingleCount.isToggled() && count <= 1) return "";
        switch ((int) countFormat.getInput()) {
            case 1: return "x" + count;
            case 2: return count + "x";
            default: return String.valueOf(count);
        }
    }

    /**
     * "Short" keeps the last word of the display name -- "Diamond Sword" becomes "Sword" -- which
     * is what tells two drops apart in a pile without a name wider than the card under it.
     */
    private String nameLabel(ItemStack stack) {
        if (stack == null) return "";
        String name = stack.getDisplayName();
        if (name == null) return "";
        if ((int) nameStyle.getInput() != 1) return name;
        int cut = name.lastIndexOf(' ');
        return cut >= 0 && cut < name.length() - 1 ? name.substring(cut + 1) : name;
    }

    @Override
    public void onDisable() {
        entries.clear();
        cards.clear();
        cluster.clear();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!Utils.nullCheck() || mc.theWorld == null) {
            entries.clear();
            return;
        }
        collect();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || mc.theWorld == null || mc.entityRenderer == null) return;
        if (hideInGui.isToggled() && mc.currentScreen != null) return;

        ScaledResolution resolution = ScaledResolutionCache.get();
        AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, event.partialTicks, 0);
        projectionContext = RenderUtils.captureProjectionContext(projectionContext, resolution.getScaleFactor());
        if (projectionContext == null) return;

        project(event.partialTicks);
        if (cards.isEmpty()) return;
        Collections.sort(cards, FAR_FIRST);
        mc.entityRenderer.setupOverlayRendering();
        draw(resolution);
        cards.clear();
        AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, event.partialTicks, 0);
    }

    private static final Comparator<Card> FAR_FIRST = new Comparator<Card>() {
        @Override
        public int compare(Card a, Card b) {
            return Double.compare(b.distance, a.distance);
        }
    };
private void collect() {
        entries.clear();

        double maxDistSq = maxDistance.getInput() * maxDistance.getInput();
        double radiusSq = stackLayout.isToggled() ? STACK_RADIUS_SQ : TIGHT_RADIUS_SQ;

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityItem)) continue;
            if (entity.ticksExisted < 3 || entity.isDead) continue;
            if (!RenderUtils.isWithinDistanceSqToRenderView(entity, maxDistSq)) continue;

            ItemStack itemStack = ((EntityItem) entity).getEntityItem();
            if (itemStack == null || itemStack.stackSize <= 0) continue;

            Category matched = categoryOf(itemStack);
            if (matched == null || matched.setting == null || !matched.setting.isToggled()) continue;

            // Always merge same-item drops that share a spot. A generator pile is dozens of
            // one-item entities and only means anything as a total; the setting widens the radius
            // rather than deciding whether merging happens at all.
            Entry host = null;
            for (int i = 0; i < entries.size(); i++) {
                Entry candidate = entries.get(i);
                if (candidate.category != matched || candidate.anchor == null) continue;
                double dx = candidate.anchor.posX - entity.posX;
                double dy = candidate.anchor.posY - entity.posY;
                double dz = candidate.anchor.posZ - entity.posZ;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    host = candidate;
                    break;
                }
            }
            if (host != null) {
                host.count += itemStack.stackSize;
                continue;
            }

            entries.add(new Entry(matched, itemStack, itemStack.stackSize, entity));
        }
    }
private void project(float partialTicks) {
        cards.clear();
        net.minecraft.client.renderer.entity.RenderManager renderManager = mc.getRenderManager();

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entity anchor = entry.anchor;
            if (anchor == null || anchor.isDead) continue;
            if (!RenderUtils.isInViewFrustum(anchor.getEntityBoundingBox().expand(0.2D, 0.2D, 0.2D))) continue;

            double x = anchor.lastTickPosX + (anchor.posX - anchor.lastTickPosX) * partialTicks;
            double y = anchor.lastTickPosY + (anchor.posY - anchor.lastTickPosY) * partialTicks;
            double z = anchor.lastTickPosZ + (anchor.posZ - anchor.lastTickPosZ) * partialTicks;

            double dx = x - renderManager.viewerPosX;
            double dy = y - renderManager.viewerPosY;
            double dz = z - renderManager.viewerPosZ;
            if (!RenderUtils.projectTo2D(projectionContext, dx, dy + 0.35, dz, projected)) continue;

            double depth = projected[2];
            if (depth <= 0.005 || depth >= 1.0) continue;

            Card card = i < cardPool.size() ? cardPool.get(i) : newPooledCard();
            card.category = entry.category;
            card.icon = entry.icon;
            card.count = entry.count;
            card.screenX = (float) projected[0];
            card.screenY = (float) projected[1];
            card.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            cards.add(card);
        }
    }
private final List<Card> cardPool = new ArrayList<Card>();

    private Card newPooledCard() {
        Card card = new Card();
        cardPool.add(card);
        return card;
    }

    private void draw(ScaledResolution resolution) {
        MindlessFontRenderer text = FontManager.getHudRenderer(ModuleFont.nameOf(font), 1.0f);
        boolean labels = drawLabels();
        boolean chrome = drawChrome();
        float scale = drawIcons() ? (float) iconScale.getInput() : 0f;
        float icon = 16f * scale;
        float padding = chrome ? Math.max(2f, 3f * scale) : 0f;
        float gap = icon > 0f ? Math.max(2f, 3f * scale) : 0f;
        float fontHeight = text.getFontHeight();
        int alpha = chrome ? (int) backgroundAlpha.getInput() : 0;
        boolean drawOutline = chrome && outline.isToggled();
        float outlineWidth = (float) outlineThickness.getInput();
        boolean drawCount = labels && showCount.isToggled();
        boolean drawName = labels && showName.isToggled();
        boolean drawDistance = labels && showDistance.isToggled();

        float screenW = resolution.getScaledWidth();
        float screenH = resolution.getScaledHeight();

        measureCards(text, icon, padding, gap, fontHeight, drawCount, drawName, drawDistance);
        // Merging changes the counts, which changes the widths, which can bring further cards into
        // contact. Repeat until it settles rather than leaving a half-merged row behind.
        for (int round = 0; round < 4 && mergeStackedCards(); round++) {
            measureCards(text, icon, padding, gap, fontHeight, drawCount, drawName, drawDistance);
        }
        spreadOverlapping();

        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            if (card.screenX < -150f || card.screenX > screenW + 150f
                    || card.screenY < -150f || card.screenY > screenH + 150f) {
                continue;
            }

            float width = card.width;
            float height = card.height;
            float left = card.screenX - width / 2f;
            float top = card.screenY - height / 2f;

            if (alpha > 0) {
                RoundedUtils.drawRound(left, top, width, height, 3f,
                        (alpha << 24) | 0x121214);
            }
            if (drawOutline) {
                RoundedUtils.drawRoundOutline(left, top, width, height, 3f, outlineWidth,
                        new java.awt.Color(0, 0, 0, 0),
                        new java.awt.Color((card.category.color >> 16) & 0xFF,
                                (card.category.color >> 8) & 0xFF, card.category.color & 0xFF, 200));
            }

            if (icon > 0f) {
                drawIcon(card.icon, left + padding, card.screenY - icon / 2f, scale);
            }

            if (drawCount && !card.label.isEmpty()) {
                text.drawString(card.label, left + padding + icon + gap,
                        card.screenY - fontHeight / 2f, 0xFF000000 | countColor.getRGB(), true);
            }
            if (drawName) {
                String name = nameLabel(card.icon);
                text.drawString(name, card.screenX - text.getStringWidth(name) / 2f,
                        top - fontHeight - 1f, 0xFF000000 | nameColor.getRGB(), true);
            }
            if (drawDistance) {
                String distance = ((int) card.distance) + "m";
                text.drawString(distance, card.screenX - text.getStringWidth(distance) / 2f,
                        top + height + 1f, 0xFF000000 | distanceColor.getRGB(), true);
            }
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /**
     * Measures the pill and, separately, how wide the card actually draws.
     *
     * The name and distance labels are centred on the card and are far wider than the pill, so
     * testing overlap against the pill alone let a pile of identical drops sit as separate cards
     * whose names ran straight through each other.
     */
    private void measureCards(MindlessFontRenderer text, float icon, float padding, float gap,
                              float fontHeight, boolean drawCount, boolean drawName,
                              boolean drawDistance) {
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            card.label = drawCount ? countLabel(card.count) : "";
            boolean hasLabel = !card.label.isEmpty();
            float labelW = hasLabel ? text.getStringWidth(card.label) : 0f;
            card.width = padding * 2f + icon + (hasLabel ? gap + labelW : 0f);
            card.height = Math.max(icon, fontHeight) + padding * 2f;

            float extent = card.width;
            if (drawName && card.icon != null) {
                extent = Math.max(extent, text.getStringWidth(nameLabel(card.icon)));
            }
            if (drawDistance) {
                extent = Math.max(extent, text.getStringWidth(((int) card.distance) + "m"));
            }
            card.extent = extent;
            card.span = card.height
                    + (drawName ? fontHeight + 1f : 0f)
                    + (drawDistance ? fontHeight + 1f : 0f);
        }
    }

    /**
     * Sums cards of the same item that land on top of each other into one total. A generator pile
     * is dozens of separate one-item entities; without this they are dozens of separate cards,
     * and spreading them out turns a pile of iron into a screen-wide row of "1"s.
     */
    private boolean mergeStackedCards() {
        int size = cards.size();
        if (size < 2) return false;

        boolean merged = false;
        for (int i = 0; i < size; i++) {
            Card host = cards.get(i);
            if (host == null) continue;

            for (int j = i + 1; j < size; j++) {
                Card other = cards.get(j);
                if (other == null || other.category != host.category) continue;
                if (Math.abs(other.screenX - host.screenX) >= (host.extent + other.extent) * 0.5f
                        || Math.abs(other.screenY - host.screenY) >= (host.span + other.span) * 0.5f) {
                    continue;
                }
                host.count += other.count;
                if (other.distance < host.distance) {
                    host.icon = other.icon;
                    host.screenX = other.screenX;
                    host.screenY = other.screenY;
                    host.distance = other.distance;
                }
                cards.set(j, null);
                merged = true;
            }
        }

        if (!merged) return false;

        int write = 0;
        for (int i = 0; i < size; i++) {
            Card card = cards.get(i);
            if (card != null) {
                cards.set(write++, card);
            }
        }
        for (int i = size - 1; i >= write; i--) {
            cards.remove(i);
        }
        return true;
    }

    /**
     * Lays cards that land on top of each other out in a row instead. The pile keeps its screen
     * position -- the row is centred on where the cards already were -- so a gold and an iron
     * stack sitting on the same generator read as two labels side by side rather than one
     * unreadable overlap. Same-item cards are already summed by mergeStackedCards, so what is
     * left in a cluster is one card per item type.
     */
    private void spreadOverlapping() {
        int size = cards.size();
        if (size < 2) return;
        if (clustered.length < size) {
            clustered = new boolean[Math.max(size, clustered.length * 2)];
        }
        for (int i = 0; i < size; i++) clustered[i] = false;

        for (int i = 0; i < size; i++) {
            if (clustered[i]) continue;
            Card anchor = cards.get(i);
            cluster.clear();
            cluster.add(anchor);
            clustered[i] = true;

            for (int j = i + 1; j < size; j++) {
                if (clustered[j]) continue;
                Card other = cards.get(j);
                if (Math.abs(other.screenX - anchor.screenX) < (anchor.extent + other.extent) * 0.5f
                        && Math.abs(other.screenY - anchor.screenY) < (anchor.span + other.span) * 0.5f) {
                    cluster.add(other);
                    clustered[j] = true;
                }
            }

            if (cluster.size() < 2) continue;

            sortClusterByCategory();

            // Space by the drawn extent, not the pill, or the labels overlap even once the pills
            // have been pulled apart.
            float total = SPREAD_GAP * (cluster.size() - 1);
            float meanX = 0f;
            float meanY = 0f;
            for (int k = 0; k < cluster.size(); k++) {
                Card card = cluster.get(k);
                total += card.extent;
                meanX += card.screenX;
                meanY += card.screenY;
            }
            meanX /= cluster.size();
            meanY /= cluster.size();

            float cursor = meanX - total * 0.5f;
            for (int k = 0; k < cluster.size(); k++) {
                Card card = cluster.get(k);
                card.screenX = cursor + card.extent * 0.5f;
                card.screenY = meanY;
                cursor += card.extent + SPREAD_GAP;
            }
        }
    }

    /**
     * Insertion sort in place. Collections.sort would copy the list to an array every frame for
     * what is never more than a handful of cards.
     */
    private void sortClusterByCategory() {
        for (int i = 1; i < cluster.size(); i++) {
            Card card = cluster.get(i);
            int ordinal = card.category.ordinal();
            int j = i - 1;
            while (j >= 0 && cluster.get(j).category.ordinal() > ordinal) {
                cluster.set(j + 1, cluster.get(j));
                j--;
            }
            cluster.set(j + 1, card);
        }
    }
private void drawIcon(ItemStack itemStack, float x, float y, float scale) {
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        RenderUtils.prepareGuiTextureRenderState();
        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        try {
            mc.getRenderItem().renderItemAndEffectIntoGUI(itemStack, 0, 0);
        } catch (Throwable ignored) {
        }
        GlStateManager.popMatrix();
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        RenderUtils.restoreGuiRenderState(depth, blend, depthMask);
    }
private static Category categoryOf(ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (item == null) return null;

        if (item == Items.diamond) return Category.DIAMOND;
        if (item == Items.emerald) return Category.EMERALD;
        if (item == Items.gold_ingot) return Category.GOLD;
        if (item == Items.iron_ingot) return Category.IRON;
        if (item == Items.ender_pearl) return Category.PEARL;
        if (item == Items.golden_apple) return Category.GAPPLE;
        if (item == Items.fire_charge) return Category.FIREBALL;
        if (item == Items.spawn_egg) return Category.BEDBUG;
        if (item == Item.getItemFromBlock(Blocks.tnt)) return Category.TNT;
        if (item == Item.getItemFromBlock(Blocks.chest)
                || item == Item.getItemFromBlock(Blocks.ender_chest)) return Category.TOWER;
        if (item instanceof ItemSword) return Category.SWORD;
        if (item instanceof ItemBow) return Category.BOW;
        if (item instanceof ItemPotion) return potionCategory(itemStack);
        return null;
    }
private static Category potionCategory(ItemStack itemStack) {
        List<?> effects;
        try {
            effects = ((ItemPotion) itemStack.getItem()).getEffects(itemStack);
        } catch (Throwable unreadable) {
            return null;
        }
        if (effects == null) return null;
        for (Object entry : effects) {
            if (!(entry instanceof PotionEffect)) continue;
            int id = ((PotionEffect) entry).getPotionID();
            if (id == Potion.invisibility.id) return Category.INVIS;
            if (id == Potion.moveSpeed.id) return Category.SPEED;
            if (id == Potion.jump.id) return Category.JUMP;
        }
        return null;
    }
private static final class Entry {
        private final Category category;
        private final ItemStack icon;
        private final Entity anchor;
        private int count;

        Entry(Category category, ItemStack icon, int count, Entity anchor) {
            this.category = category;
            this.icon = icon;
            this.count = count;
            this.anchor = anchor;
        }
    }

    private static final class Card {
        private Category category;
        private ItemStack icon;
        private String label;
        private int count;
        private float screenX;
        private float screenY;
        private float width;
        private float height;
        private float extent;
        private float span;
        private double distance;
    }
}
