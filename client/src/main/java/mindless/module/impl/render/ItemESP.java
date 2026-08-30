package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AccessorBridge;
import mindless.runtime.LunarEventBridge;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dropped-item ESP drawn as flat cards on the HUD rather than boxes in the world.
 *
 * <p>A card carries the item's own icon, how many of it are lying there, and optionally its name
 * and distance. Items of the same kind close together collapse into one card with a combined count,
 * so a scattered pile of forty ingots reads as one number instead of forty overlapping boxes.
 *
 * <p>The layout follows M1CK3Y's ResourceESP script for Mindless bS (GPL-3.0), reimplemented against
 * this client's own rendering rather than the script API. Two things are done differently on
 * purpose. Items are identified by their {@link Item} and, for potions, by the effect they carry,
 * where the script compared lowercased display-name substrings -- that misses anything the server
 * has renamed and matches things it should not, and "bow" had to be special-cased against "bowl".
 * And the icon is the stack that is actually on the ground, not a stand-in built per category, so
 * an enchanted or damaged item looks like itself.
 */
public class ItemESP extends Module {
    /**
     * What a dropped stack counts as, and the colour its card is trimmed in.
     *
     * <p>Order matters: the first match wins, so the specific entries sit above the general ones.
     */
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

    private final SliderSetting font;
    private final SliderSetting maxDistance;
    private final SliderSetting iconScale;
    private final SliderSetting backgroundAlpha;
    private final ButtonSetting stackLayout;
    private final ButtonSetting outline;
    private final ButtonSetting showName;
    private final ButtonSetting showDistance;
    private final ButtonSetting showCount;
    private final ButtonSetting hideInGui;

    /**
     * What is on the ground, rebuilt on the tick rather than the frame.
     *
     * <p>Scanning every loaded entity, grouping it and boxing a map key is tick-rate work: the
     * world only changes twenty times a second, and doing it per frame repeated all of it up to
     * ten times for an identical answer, allocating a card and a boxed key each time round. The
     * frame keeps the part that genuinely changes per frame -- interpolating each anchor and
     * projecting it -- which is what makes the cards track smoothly instead of stepping.
     */
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<Card> cards = new ArrayList<Card>();
    private final Map<Long, Entry> groups = new HashMap<Long, Entry>();
    private final double[] projected = new double[3];
    private RenderUtils.ProjectionContext projectionContext;

    public ItemESP() {
        super("ItemESP", category.render);
        this.liteModule = true;

        GroupSetting items = new GroupSetting("Items");
        registerSetting(items);
        for (Category c : Category.values()) {
            registerSetting(c.setting = new ButtonSetting(items, c.label, true));
        }

        GroupSetting card = new GroupSetting("Card");
        registerSetting(card);
        registerSetting(font = new SliderSetting(card, "Font", 0, ModuleFont.options()));
        registerSetting(showCount = new ButtonSetting(card, "Show count", true));
        registerSetting(showName = new ButtonSetting(card, "Show name", false));
        registerSetting(showDistance = new ButtonSetting(card, "Show distance", false));
        registerSetting(outline = new ButtonSetting(card, "Outline", true));
        registerSetting(iconScale = new SliderSetting(card, "Icon scale", "x", 1.0, 0.7, 2.0, 0.05));
        registerSetting(backgroundAlpha = new SliderSetting(card, "Background alpha", 170, 0, 255, 5));

        registerSetting(stackLayout = new ButtonSetting("Stack layout", true));
        registerSetting(hideInGui = new ButtonSetting("Hide in GUI", true));
        registerSetting(maxDistance = new SliderSetting("Max distance", 128.0, 16.0, 512.0, 8.0));
    }

    @Override
    public void onDisable() {
        entries.clear();
        cards.clear();
        groups.clear();
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
        if (!LunarEventBridge.isDirectLunar() || projectionContext == null) {
            AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, event.partialTicks, 0);
            projectionContext = RenderUtils.captureProjectionContext(projectionContext, resolution.getScaleFactor());
        }
        if (projectionContext == null) return;

        project(event.partialTicks);
        if (cards.isEmpty()) return;

        // Far cards first, so the near ones end up on top of them.
        Collections.sort(cards, FAR_FIRST);
        mc.entityRenderer.setupOverlayRendering();
        draw(resolution);
        cards.clear();
    }

    private static final Comparator<Card> FAR_FIRST = new Comparator<Card>() {
        @Override
        public int compare(Card a, Card b) {
            return Double.compare(b.distance, a.distance);
        }
    };

    /** Rebuilds the snapshot: which stacks are on the ground, and how they group. */
    private void collect() {
        entries.clear();
        groups.clear();

        double maxDistSq = maxDistance.getInput() * maxDistance.getInput();
        boolean stack = stackLayout.isToggled();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityItem)) continue;
            // A stack spends its first couple of ticks interpolating in from wherever the server
            // said it spawned, which is rarely where it lands.
            if (entity.ticksExisted < 3 || entity.isDead) continue;
            if (!RenderUtils.isWithinDistanceSqToRenderView(entity, maxDistSq)) continue;

            ItemStack itemStack = ((EntityItem) entity).getEntityItem();
            if (itemStack == null || itemStack.stackSize <= 0) continue;

            Category matched = categoryOf(itemStack);
            if (matched == null || matched.setting == null || !matched.setting.isToggled()) continue;

            if (stack) {
                // Three-block cells. Loose enough that a burst of drops from one broken block lands
                // in one cell, tight enough that two separate piles stay two cards.
                long cell = (((long) Math.floor(entity.posX / 3.0) & 0x1FFFFF) << 42)
                        | (((long) Math.floor(entity.posY / 3.0) & 0x1FFFFF) << 21)
                        | ((long) Math.floor(entity.posZ / 3.0) & 0x1FFFFF);
                long key = cell * 31L + matched.ordinal();
                Entry existing = groups.get(key);
                if (existing != null) {
                    existing.count += itemStack.stackSize;
                    continue;
                }
                Entry entry = new Entry(matched, itemStack, itemStack.stackSize, entity);
                groups.put(key, entry);
                entries.add(entry);
                continue;
            }

            entries.add(new Entry(matched, itemStack, itemStack.stackSize, entity));
        }
    }

    /**
     * Puts the snapshot on the screen at this frame's camera.
     *
     * <p>Anchors are interpolated here rather than in the snapshot, so a card follows a bouncing
     * item smoothly instead of stepping twenty times a second.
     *
     * <p>The depth guard is the same one the player ESP uses: a point within about five thousandths
     * of the near plane diverges under perspective division, and anything at or past the far plane
     * is behind the camera.
     */
    private void project(float partialTicks) {
        cards.clear();
        net.minecraft.client.renderer.entity.RenderManager renderManager = mc.getRenderManager();

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entity anchor = entry.anchor;
            if (anchor == null || anchor.isDead) continue;

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

    /** Cards are rebuilt every frame, so they are reused rather than reallocated. */
    private final List<Card> cardPool = new ArrayList<Card>();

    private Card newPooledCard() {
        Card card = new Card();
        cardPool.add(card);
        return card;
    }

    private void draw(ScaledResolution resolution) {
        MindlessFontRenderer text = FontManager.getHudRenderer(ModuleFont.nameOf(font), 1.0f);
        float scale = (float) iconScale.getInput();
        float icon = 16f * scale;
        float padding = 2f * scale;
        int alpha = (int) backgroundAlpha.getInput();
        boolean drawOutline = outline.isToggled();
        boolean drawCount = showCount.isToggled();
        boolean drawName = showName.isToggled();
        boolean drawDistance = showDistance.isToggled();

        float screenW = resolution.getScaledWidth();
        float screenH = resolution.getScaledHeight();

        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            // A generous margin rather than the exact edge: a card whose anchor is just off screen
            // still has most of its body on it.
            if (card.screenX < -150f || card.screenX > screenW + 150f
                    || card.screenY < -150f || card.screenY > screenH + 150f) {
                continue;
            }

            String count = drawCount ? String.valueOf(card.count) : "";
            float countW = drawCount ? text.getStringWidth(count) : 0f;
            float width = Math.max(icon + padding * 2f, countW + icon * .5f + padding * 2f);
            float height = icon + padding * 2f;
            float left = card.screenX - width / 2f;
            float top = card.screenY - height / 2f;

            if (alpha > 0) {
                RoundedUtils.drawRound(left, top, width, height, 3f,
                        (alpha << 24) | 0x121214);
            }
            if (drawOutline) {
                RoundedUtils.drawRoundOutline(left, top, width, height, 3f, 1f,
                        new java.awt.Color(0, 0, 0, 0),
                        new java.awt.Color((card.category.color >> 16) & 0xFF,
                                (card.category.color >> 8) & 0xFF, card.category.color & 0xFF, 200));
            }

            drawIcon(card.icon, card.screenX - icon / 2f, card.screenY - icon / 2f, scale);

            if (drawCount) {
                text.drawString(count, left + width - countW - padding,
                        top + height - text.getFontHeight() - padding * .5f, 0xFFFFFFFF, true);
            }
            if (drawName) {
                String name = card.icon.getDisplayName();
                text.drawString(name, card.screenX - text.getStringWidth(name) / 2f,
                        top - text.getFontHeight() - 1f, 0xFFFFFFFF, true);
            }
            if (drawDistance) {
                String distance = ((int) card.distance) + "m";
                text.drawString(distance, card.screenX - text.getStringWidth(distance) / 2f,
                        top + height + 1f, 0xFFCCCCCC, true);
            }
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /**
     * The item's own icon, at whatever size the slider asks for.
     *
     * <p>renderItemAndEffectIntoGUI draws a sixteen-pixel sprite at integer coordinates, so the
     * scaling and the fractional placement both have to happen in the matrix. It also leaves item
     * lighting and depth behind it, which the flat cards drawn afterwards cannot have.
     */
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
            // A malformed stack from a server-side item is not worth losing the frame over.
        }
        GlStateManager.popMatrix();
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        RenderUtils.restoreGuiRenderState(depth, blend, depthMask);
    }

    /** @return the category this stack belongs to, or null when it is not one this module shows */
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

    /**
     * Which potion this is, read from the effect it carries rather than its damage value.
     *
     * <p>Every strength and duration of one brew is a different damage value, and splash doubles
     * the set again; the effect id is the same across all of them.
     */
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

    /** One card's worth of the world, as of the last tick. */
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
        private int count;
        private float screenX;
        private float screenY;
        private double distance;
    }
}
