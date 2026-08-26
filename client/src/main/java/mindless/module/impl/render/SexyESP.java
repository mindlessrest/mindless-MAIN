package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.runtime.LunarEventBridge;
import mindless.module.Module;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.impl.world.AntiBot;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.shader.GlowBloomShader;
import mindless.utility.shader.GlowShader;
import mindless.utility.shader.KawaseBloom;
import mindless.utility.shader.SeparableOutlineShader;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.text.DecimalFormat;

/**
 * OpenMyau-style 2D entity ESP, adapted to Mindless' render and setting systems.
 */
public class SexyESP extends Module {
    private static SexyESP instance;
    private final DecimalFormat healthFormat = new DecimalFormat("0.0");
    private final double[] projectedPoint = new double[3];
    private final Bounds projectedBounds = new Bounds();
    private final RectBatch rectBatch = new RectBatch();
    static RenderUtils.ProjectionContext projectionContext;

    public static void captureForWorldProjection(int scaleFactor) {
        projectionContext = RenderUtils.captureProjectionContext(projectionContext, scaleFactor);
    }

    private final ButtonSetting outline;
    private final SliderSetting boxMode;
    private final ButtonSetting healthBar;
    private final ButtonSetting absorption;
    private final ButtonSetting healthNumber;
    private final SliderSetting healthNumberMode;
    private final ButtonSetting armorBar;
    private final SliderSetting armorBarMode;
    private final ButtonSetting armorItems;
    private final ButtonSetting armorDurability;
    private final SliderSetting armorPosition;
    private final ButtonSetting tags;
    private final ButtonSetting tagBackground;
    private final ButtonSetting itemTags;
    private final SliderSetting fontScale;
    private final ButtonSetting distanceTextScale;
    private final ButtonSetting textBorder;
    private final ButtonSetting localPlayer;
    private final ButtonSetting droppedItems;
    private final ButtonSetting showInvisible;
    private final ButtonSetting teamCheck;
    private final SliderSetting colorMode;
    private final ColorSetting color;
    private final SliderSetting maxDistance;

    private final ButtonSetting outlineEnabled;
    private final SliderSetting outlineGlowSize;
    private final SliderSetting outlineGlowStrength;
    private final ButtonSetting outlineEdge;
    private final ButtonSetting outlineTeamColor;
    private final ColorSetting outlineColor;

    public static boolean renderingOutlinePass = false;
    private Framebuffer outlineFramebuffer;
    private final SeparableOutlineShader separableOutlineShader = new SeparableOutlineShader();
    private final GlowBloomShader glowBloomShader = new GlowBloomShader();
    private final GlowShader glowShader = new GlowShader();

    public SexyESP() {
        super("Player ESP", category.render, 0);
        instance = this;

        GroupSetting boxGroup = new GroupSetting("Box");
        registerSetting(boxGroup);
        registerSetting(outline = new ButtonSetting(boxGroup, "Outline", true));
        registerSetting(boxMode = new SliderSetting(boxGroup, "Mode", 0, new String[]{"Box", "Corners"}));

        GroupSetting healthGroup = new GroupSetting("Health");
        registerSetting(healthGroup);
        registerSetting(healthBar = new ButtonSetting(healthGroup, "Health bar", true));
        registerSetting(absorption = new ButtonSetting(healthGroup, "Absorption", true));
        registerSetting(healthNumber = new ButtonSetting(healthGroup, "Health number", true));
        registerSetting(healthNumberMode = new SliderSetting(healthGroup, "Number mode", 0, new String[]{"Health", "Percent"}));

        GroupSetting armorGroup = new GroupSetting("Armor");
        registerSetting(armorGroup);
        registerSetting(armorBar = new ButtonSetting(armorGroup, "Armor bar", true));
        registerSetting(armorBarMode = new SliderSetting(armorGroup, "Bar mode", 0, new String[]{"Total", "Items"}));
        registerSetting(armorItems = new ButtonSetting(armorGroup, "Armor items", true));
        registerSetting(armorDurability = new ButtonSetting(armorGroup, "Durability numbers", false));
        registerSetting(armorPosition = new SliderSetting(armorGroup, "Position", 0, new String[]{"Right side", "Above name"}));

        GroupSetting tagGroup = new GroupSetting("Tags");
        registerSetting(tagGroup);
        registerSetting(tags = new ButtonSetting(tagGroup, "Names", true));
        registerSetting(tagBackground = new ButtonSetting(tagGroup, "Background", false));
        registerSetting(itemTags = new ButtonSetting(tagGroup, "Held item", true));
        registerSetting(fontScale = new SliderSetting(tagGroup, "Font scale", 0.5, 0.25, 1.0, 0.05));
        registerSetting(distanceTextScale = new ButtonSetting(tagGroup, "Distance scaling", true));
        registerSetting(textBorder = new ButtonSetting(tagGroup, "Black text outline", true));

        GroupSetting outlineGroup = new GroupSetting("Outline");
        registerSetting(outlineGroup);
        registerSetting(outlineEnabled = new ButtonSetting(outlineGroup, "Enabled", false));
        registerSetting(outlineGlowSize = new SliderSetting(outlineGroup, "Glow size", 4.0, 0.0, 10.0, 0.5));
        registerSetting(outlineGlowStrength = new SliderSetting(outlineGroup, "Glow strength", 1.0, 0.1, 3.0, 0.1));
        registerSetting(outlineEdge = new ButtonSetting(outlineGroup, "Edge", false));
        registerSetting(outlineTeamColor = new ButtonSetting(outlineGroup, "Team color", false));
        registerSetting(outlineColor = new ColorSetting(outlineGroup, "Color", 180, 0, 255));

        registerSetting(localPlayer = new ButtonSetting("Local player", true));
        registerSetting(droppedItems = new ButtonSetting("Dropped items", false));
        registerSetting(showInvisible = new ButtonSetting("Show invisible", false));
        registerSetting(teamCheck = new ButtonSetting("Team check", false));
        registerSetting(colorMode = new SliderSetting("Color mode", 0, new String[]{"Custom", "Rainbow", "Team"}));
        registerSetting(color = new ColorSetting("Color", 255, 255, 255));
        registerSetting(maxDistance = new SliderSetting("Max distance", 128.0, 16.0, 256.0, 8.0));
    }

    /** Avoids drawing a second full nametag/armor overlay for the same players. */
    public static boolean replacesStandaloneNametags() {
        return instance != null && instance.isEnabled() && instance.tags.isToggled();
    }

    public boolean isGlowEnabled() { return outlineEnabled.isToggled(); }
    public boolean isRenderSelf() { return localPlayer.isToggled(); }
    public boolean isRainbow() { return (int) colorMode.getInput() == 1; }
    public boolean isTeamColor() { return (int) colorMode.getInput() == 2; }
    public boolean isRedOnDamage() { return false; }
    public boolean isShowInvis() { return showInvisible.isToggled(); }
    public int getColorRGB() { return color.getColor(); }

    @Override
    public void onDisable() {
        if (outlineFramebuffer != null) {
            outlineFramebuffer.deleteFramebuffer();
            outlineFramebuffer = null;
        }
        glowBloomShader.delete();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || mc.theWorld == null || mc.entityRenderer == null) {
            return;
        }
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            return;
        }

        if (outlineEnabled.isToggled()) {
            runOutlinePass(event.partialTicks);
        }

        ScaledResolution resolution = ScaledResolutionCache.get();
        if (!LunarEventBridge.isDirectLunar() || projectionContext == null) {
            AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, event.partialTicks, 0);
            projectionContext = RenderUtils.captureProjectionContext(projectionContext, resolution.getScaleFactor());
        }
        mc.entityRenderer.setupOverlayRendering();

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);

        double maxDistanceSq = maxDistance.getInput() * maxDistance.getInput();
        RenderManager renderManager = mc.getRenderManager();
        Entity viewEntity = mc.getRenderViewEntity();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            renderCandidate(player, viewEntity, renderManager, maxDistanceSq, event.partialTicks, resolution);
        }
        if (droppedItems.isToggled()) {
            for (Entity entity : mc.theWorld.loadedEntityList) {
                if (entity instanceof EntityItem) {
                    renderCandidate(entity, viewEntity, renderManager, maxDistanceSq, event.partialTicks, resolution);
                }
            }
        }

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.popMatrix();
    }

    private boolean isValidEntity(Entity entity) {
        if (entity == null || entity.isDead) return false;
        if (entity.ticksExisted <= 0) return false;
        if (entity.getEntityBoundingBox() == null) return false;
        if (Double.isNaN(entity.posX) || Double.isNaN(entity.posY) || Double.isNaN(entity.posZ)
                || Double.isInfinite(entity.posX) || Double.isInfinite(entity.posY) || Double.isInfinite(entity.posZ)) return false;
        if (entity.isInvisible() && !showInvisible.isToggled()) return false;
        if (entity instanceof EntityItem) return droppedItems.isToggled();
        if (!(entity instanceof EntityPlayer)) return false;
        if (entity != mc.thePlayer && AntiBot.isBot(entity)) return false;
        if (teamCheck.isToggled() && Utils.isTeammate(entity)) return false;
        if (entity == mc.thePlayer) return localPlayer.isToggled() && mc.gameSettings.thirdPersonView != 0;
        if (entity == mc.getRenderViewEntity()) return false;
        return true;
    }

    private void renderCandidate(Entity entity, Entity viewEntity, RenderManager renderManager,
                                 double maxDistanceSq, float partialTicks, ScaledResolution resolution) {
        if (!isValidEntity(entity)) return;
        if (viewEntity.getDistanceSqToEntity(entity) > maxDistanceSq) return;
        // The camera matrices are interpolated while entity/frustum positions
        // can be one tick apart. A small pad prevents edge-of-screen players
        // from flickering out without projecting every off-screen entity.
        if (!RenderUtils.isInViewFrustum(entity.getEntityBoundingBox().expand(0.35D, 0.35D, 0.35D))) return;
        if (projectBounds(entity, renderManager, partialTicks, resolution, projectedBounds)) {
            renderEntity(entity, projectedBounds);
        }
    }

    private boolean projectBounds(Entity entity, RenderManager renderManager, float partialTicks,
                                  ScaledResolution resolution, Bounds output) {
        // Entity feet position in camera space (camera is the origin).
        double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - renderManager.viewerPosX;
        double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - renderManager.viewerPosY;
        double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - renderManager.viewerPosZ;

        double h  = entity.height;
        double hw = entity.width / 2.0;

        double minX = Double.MAX_VALUE,  maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE,  maxY = -Double.MAX_VALUE;
        int valid = 0;

        // Reuse the pre-allocated buffer — it is consumed before the next iteration overwrites it.
        double[] pt = projectedPoint;

        for (int xi = -1; xi <= 1; xi += 2) {
            for (int zi = -1; zi <= 1; zi += 2) {
                for (int yi = 0; yi <= 1; yi++) {
                    if (!RenderUtils.projectTo2D(projectionContext,
                            ex + xi * hw, ey + yi * h, ez + zi * hw, pt))
                        continue;

                    double d = pt[2];
                    // Depth 0 = near plane, 1 = far plane.
                    // Corners within ~0.005 depth of the near plane diverge under
                    // perspective division and must be excluded.  With Minecraft's
                    // typical 0.05-near / 128-far setup, 0.005 depth ≈ 0.7 blocks —
                    // safely past the near clip even at close range.
                    if (d <= 0.005 || d >= 1.0) continue;

                    if (pt[0] < minX) minX = pt[0];
                    if (pt[0] > maxX) maxX = pt[0];
                    if (pt[1] < minY) minY = pt[1];
                    if (pt[1] > maxY) maxY = pt[1];
                    valid++;
                }
            }
        }

        // Require at least 4 valid corners.  Fewer means the camera is inside
        // or clipping through the AABB — the projection is not meaningful.
        if (valid < 4) return false;

        output.set(minX, minY, maxX, maxY);
        return true;
    }

    private void renderEntity(Entity entity, Bounds b) {
        restoreFlatOverlayState();
        rectBatch.begin();
        try {
            int renderColor = getEntityColor(entity);
            if (outline.isToggled()) drawBox(b, renderColor);

            if (entity instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) entity;
                drawLivingDetails(living, b);
            } else if (entity instanceof EntityItem) {
                drawDroppedItem((EntityItem) entity, b);
            }
        }
        finally {
            rectBatch.flush();
        }
    }

    private void drawBox(Bounds b, int renderColor) {
        if ((int) boxMode.getInput() == 0) {
            drawOutlinedRect(b.left, b.top, b.right, b.bottom, renderColor);
            return;
        }

        double lineW = b.width() / 3.0;
        double lineH = b.height() / 4.0;
        drawSegment(b.left, b.top, b.left + lineW, b.top, renderColor);
        drawSegment(b.left, b.top, b.left, b.top + lineH, renderColor);
        drawSegment(b.right - lineW, b.top, b.right, b.top, renderColor);
        drawSegment(b.right, b.top, b.right, b.top + lineH, renderColor);
        drawSegment(b.left, b.bottom, b.left + lineW, b.bottom, renderColor);
        drawSegment(b.left, b.bottom - lineH, b.left, b.bottom, renderColor);
        drawSegment(b.right - lineW, b.bottom, b.right, b.bottom, renderColor);
        drawSegment(b.right, b.bottom - lineH, b.right, b.bottom, renderColor);
    }

    private void drawOutlinedRect(double left, double top, double right, double bottom, int renderColor) {
        drawFlatRect(left - 1, top - 1, right + 1, top + 1, 0xFF000000);
        drawFlatRect(left - 1, bottom - 1, right + 1, bottom + 1, 0xFF000000);
        drawFlatRect(left - 1, top, left + 1, bottom, 0xFF000000);
        drawFlatRect(right - 1, top, right + 1, bottom, 0xFF000000);
        drawFlatRect(left, top, right, top + 0.5, renderColor);
        drawFlatRect(left, bottom - 0.5, right, bottom, renderColor);
        drawFlatRect(left, top, left + 0.5, bottom, renderColor);
        drawFlatRect(right - 0.5, top, right, bottom, renderColor);
    }

    private void drawSegment(double x1, double y1, double x2, double y2, int renderColor) {
        if (x1 == x2) {
            drawFlatRect(x1 - 1, Math.min(y1, y2) - 1, x1 + 1, Math.max(y1, y2) + 1, 0xFF000000);
            drawFlatRect(x1 - 0.25, Math.min(y1, y2), x1 + 0.25, Math.max(y1, y2), renderColor);
        } else {
            drawFlatRect(Math.min(x1, x2) - 1, y1 - 1, Math.max(x1, x2) + 1, y1 + 1, 0xFF000000);
            drawFlatRect(Math.min(x1, x2), y1 - 0.25, Math.max(x1, x2), y1 + 0.25, renderColor);
        }
    }

    private void drawLivingDetails(EntityLivingBase living, Bounds b) {
        float maxHealth = Math.max(1.0F, living.getMaxHealth());
        float health = MathHelper.clamp_float(living.getHealth(), 0.0F, maxHealth);
        double healthRatio = health / maxHealth;
        double healthY = b.bottom - b.height() * healthRatio;

        if (healthBar.isToggled()) {
            // Always use one continuous, fixed-width bar. OpenMyau's Dots mode
            // split tall/nearby projections into sections, making its shape
            // change with distance.
            drawFlatRect(b.left - 3.5, b.top - 0.5, b.left - 1.5, b.bottom + 0.5, 0x78000000);
            int healthColor = Color.HSBtoRGB((float) (healthRatio / 3.0), 1.0F, 1.0F) | 0xFF000000;
            drawFlatRect(b.left - 3, healthY, b.left - 2, b.bottom, healthColor);
            if (absorption.isToggled() && living.getAbsorptionAmount() > 0) {
                double absorptionHeight = Math.min(b.height(), b.height() * living.getAbsorptionAmount() / maxHealth);
                drawFlatRect(b.left - 3, b.bottom - absorptionHeight, b.left - 2, b.bottom, 0xFFFFD700);
            }
        }

        if (armorBar.isToggled()) drawArmorBar(living, b);

        if (healthNumber.isToggled()) {
            String hpText = (int) healthNumberMode.getInput() == 0
                    ? healthFormat.format(health) + " \u00A7c\u2764"
                    : (int) (healthRatio * 100) + "%";
            double scale = fontScale.getInput();
            drawScaledString(hpText, b.left - 5 - mc.fontRendererObj.getStringWidth(hpText) * scale,
                    healthY - mc.fontRendererObj.FONT_HEIGHT * scale / 2.0, scale, false);
        }

        if (armorItems.isToggled() && b.height() > 32.0) drawArmorItems(living, b);

        if (tags.isToggled()) {
            String name = living.getDisplayName().getFormattedText();
            double tagScale = getTagScale(b);
            drawTag(name, b.left + b.width() / 2.0,
                    b.top - 2 - mc.fontRendererObj.FONT_HEIGHT * tagScale,
                    tagScale);
        }
        if (itemTags.isToggled() && living.getHeldItem() != null) {
            drawTag(living.getHeldItem().getDisplayName(), b.left + b.width() / 2.0,
                    b.bottom + 2, getTagScale(b));
        }
    }

    private void drawArmorBar(EntityLivingBase living, Bounds b) {
        double ratio = MathHelper.clamp_double(living.getTotalArmorValue() / 20.0, 0, 1);
        drawFlatRect(b.right + 1.5, b.top - 0.5, b.right + 4, b.bottom + 0.5, 0x78000000);
        if (ratio > 0) {
            drawFlatRect(b.right + 2, b.bottom - b.height() * ratio, b.right + 3.5, b.bottom, 0xFF00FFFF);
        }
    }

    private void drawArmorItems(EntityLivingBase living, Bounds b) {
        final double itemScale = 0.5
                + MathHelper.clamp_double((b.height() - 48.0) / 144.0, 0.0, 0.25);
        double itemSize = 16.0 * itemScale;
        final double itemGap = 1.0;
        int slotCount = 0;
        for (int slot = 4; slot > 0; slot--) {
            if (living.getEquipmentInSlot(slot) != null) slotCount++;
        }
        if (slotCount == 0) return;

        boolean aboveName = armorPosition != null && (int) armorPosition.getInput() == 1;

        double startX, startY;
        if (aboveName) {
            double armorWidth = slotCount * itemSize + (slotCount - 1) * itemGap;
            startX = b.left + b.width() / 2.0 - armorWidth / 2.0;
            double tagScale = getTagScale(b);
            double nameY = b.top - 2 - mc.fontRendererObj.FONT_HEIGHT * tagScale;
            startY = nameY - itemSize - 3;
        } else {
            final double stackHeight = itemSize * 4.0 + itemGap * 3.0;
            startY = b.top + (b.height() - stackHeight) / 2.0;
            startX = b.right + (armorBar.isToggled() ? 5 : 2);
        }

        rectBatch.flush();
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            int rendered = 0;
            for (int slot = 4; slot > 0; slot--) {
                ItemStack stack = living.getEquipmentInSlot(slot);
                if (stack == null) continue;
                double x = aboveName ? startX + rendered * (itemSize + itemGap) : startX;
                double y = aboveName ? startY : startY + (4 - slot) * (itemSize + itemGap);
                renderItemRaw(stack, x, y, itemScale);
                rendered++;
            }
        }
        finally {
            RenderHelper.disableStandardItemLighting();
            restoreFlatOverlayState();
        }

        if (armorDurability.isToggled()) {
            int rendered = 0;
            for (int slot = 4; slot > 0; slot--) {
                ItemStack stack = living.getEquipmentInSlot(slot);
                if (stack == null || stack.getMaxDamage() <= 0) continue;
                double durX = aboveName ? startX + rendered * (itemSize + itemGap) : startX;
                double durY = aboveName ? startY : startY + (4 - slot) * (itemSize + itemGap);
                int durability = stack.getMaxDamage() - stack.getItemDamage();
                double durabilityScale = Math.min(0.35, fontScale.getInput());
                double durabilityY = durY + (itemSize - mc.fontRendererObj.FONT_HEIGHT * durabilityScale) / 2.0;
                drawScaledString(String.valueOf(durability), durX + itemSize + 1.0,
                        durabilityY, durabilityScale, false);
                rendered++;
            }
        }
    }

    private void drawDroppedItem(EntityItem entity, Bounds b) {
        ItemStack stack = entity.getEntityItem();
        if (stack == null) return;
        if (armorBar.isToggled() && stack.isItemStackDamageable()) {
            double ratio = 1.0 - stack.getItemDamage() / (double) stack.getMaxDamage();
            drawFlatRect(b.right + 1.5, b.top - 0.5, b.right + 4, b.bottom + 0.5, 0x78000000);
            drawFlatRect(b.right + 2, b.bottom - b.height() * ratio, b.right + 3.5, b.bottom, 0xFF00FFFF);
        }
        if (itemTags.isToggled()) drawTag(stack.getDisplayName(), b.left + b.width() / 2.0,
                b.bottom + 2, getTagScale(b));
    }

    private double getTagScale(Bounds b) {
        double scale = fontScale.getInput();
        if (distanceTextScale.isToggled()) {
            // Keep distant text readable; scaling is intentionally subtle.
            scale *= MathHelper.clamp_double(b.height() / 48.0, 0.9, 1.1);
        }
        return scale;
    }

    private void drawTag(String text, double centerX, double y, double scale) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        double width = mc.fontRendererObj.getStringWidth(text) * scale;
        if (tagBackground.isToggled()) {
            drawFlatRect(centerX - width / 2 - 2, y - 2, centerX + width / 2 + 2,
                    y + mc.fontRendererObj.FONT_HEIGHT * scale + 1, 0x80000000);
        }
        rectBatch.flush();
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX - width / 2.0, y, 0);
        GlStateManager.scale(scale, scale, 1);
        if (textBorder.isToggled()) {
            // Strip formatting for the outline so team color codes cannot turn
            // the supposed black border into another colored copy of the text.
            String outlineText = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(text);
            int border = 0xF0000000;
            mc.fontRendererObj.drawString(outlineText, -1, -1, border, false);
            mc.fontRendererObj.drawString(outlineText, 0, -1, border, false);
            mc.fontRendererObj.drawString(outlineText, 1, -1, border, false);
            mc.fontRendererObj.drawString(outlineText, -1, 0, border, false);
            mc.fontRendererObj.drawString(outlineText, 1, 0, border, false);
            mc.fontRendererObj.drawString(outlineText, -1, 1, border, false);
            mc.fontRendererObj.drawString(outlineText, 0, 1, border, false);
            mc.fontRendererObj.drawString(outlineText, 1, 1, border, false);
        }
        mc.fontRendererObj.drawString(text, 0, 0, 0xFFFFFFFF, false);
        GlStateManager.popMatrix();
        GlStateManager.disableTexture2D();
    }

    private void drawScaledString(String text, double x, double y, double scale, boolean centered) {
        rectBatch.flush();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        float drawX = centered ? -mc.fontRendererObj.getStringWidth(text) / 2.0F : 0;
        mc.fontRendererObj.drawString(text, drawX - 0.5F, 0, 0xFF000000, false);
        mc.fontRendererObj.drawString(text, drawX + 0.5F, 0, 0xFF000000, false);
        mc.fontRendererObj.drawString(text, drawX, -0.5F, 0xFF000000, false);
        mc.fontRendererObj.drawString(text, drawX, 0.5F, 0xFF000000, false);
        mc.fontRendererObj.drawString(text, drawX, 0, 0xFFFFFFFF, false);
        GlStateManager.popMatrix();
        GlStateManager.disableTexture2D();
    }

    private void renderItemRaw(ItemStack stack, double x, double y, double scale) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, scale);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        }
        finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawFlatRect(double left, double top, double right, double bottom, int color) {
        rectBatch.add(left, top, right, bottom, color);
    }

    /** Restores the unlit state used by the projected 2D overlay. */
    private void restoreFlatOverlayState() {
        GlStateManager.disableLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private int getEntityColor(Entity entity) {
        if (entity instanceof EntityPlayer && Utils.isFriended((EntityPlayer) entity)) return 0xFF5599FF;
        switch ((int) colorMode.getInput()) {
            case 1:
                return 0xFF000000 | Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000.0F, 1.0F, 1.0F);
            case 2:
                int team = Utils.getColorFromEntity(entity);
                return team == -1 ? 0xFFFFFFFF : 0xFF000000 | team;
            default:
                return 0xFF000000 | color.getRGB();
        }
    }

    private static final class Bounds {
        private double left;
        private double top;
        private double right;
        private double bottom;

        private void set(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private double width() { return right - left; }
        private double height() { return bottom - top; }
    }

    /** Collects all flat colored quads for an entity into as few GPU submissions as possible. */
    private int getEntityColor(EntityPlayer player) {
        int mode = (int) colorMode.getInput();
        int rgb;
        if (mode == 2) rgb = Utils.getColorFromEntity(player);
        else if (mode == 1) rgb = Utils.getChroma(2L, 0L);
        else rgb = color.getColor();
        return Utils.mergeAlpha(rgb, 255);
    }

    private void runOutlinePass(float partialTicks) {
        if (mindless.utility.Diagnostics.isEnabled() && !glowBloomShader.isValid()) {
            mindless.utility.Diagnostics.log("esp", "glow shader unavailable, falling back to bloom");
        }
        if (!glowShader.isValid()) return;
        if (!glowBloomShader.isValid() && !separableOutlineShader.isValid()) return;

        outlineFramebuffer = createOutlineFramebuffer(outlineFramebuffer);
        if (outlineFramebuffer == null) return;

        mc.getFramebuffer().bindFramebuffer(true);
        // Both matrices are saved, not just the modelview. setupCameraTransform below replaces the
        // projection with the camera's perspective, and the composite at the end needs an
        // orthographic one -- every fullscreen helper draws its quad in scaled-GUI coordinates, so
        // under a perspective projection that quad lands on the camera's near plane and none of the
        // glow ever reaches the screen. The setupOverlayRendering call was deleted once for leaving
        // the projection in ortho and breaking freelook; putting the saved matrices back afterwards
        // fixes that without giving up the projection the composite depends on.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        outlineFramebuffer.framebufferClear();
        outlineFramebuffer.bindFramebuffer(true);
        AccessorBridge.EntityRenderer_callSetupCameraTransform(mc.entityRenderer, partialTicks, 0);
        boolean shadows = mc.gameSettings.entityShadows;
        mc.gameSettings.entityShadows = false;
        renderingOutlinePass = true;

        int outCol = outlineColor.getColor() | 0xFF000000;
        int oR = (outCol >> 16) & 0xFF;
        int oG = (outCol >> 8) & 0xFF;
        int oB = outCol & 0xFF;
        boolean useTeamColorOutline = outlineTeamColor.isToggled();

        double maxDistSq = maxDistance.getInput() * maxDistance.getInput();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidEntity(player)) continue;
            if (!RenderUtils.isInViewFrustum(player)) continue;
            if (!RenderUtils.isWithinDistanceSqToRenderView(player, maxDistSq)) continue;
            int pR = oR, pG = oG, pB = oB;
            if (useTeamColorOutline) {
                int teamCol = Utils.getColorFromEntity(player);
                if (teamCol != -1) {
                    pR = (teamCol >> 16) & 0xFF;
                    pG = (teamCol >> 8) & 0xFF;
                    pB = teamCol & 0xFF;
                }
            }
            glowShader.use();
            mindless.utility.Diagnostics.gl("esp: bound glow program");
            glowShader.setColor(pR, pG, pB, 255);
            mindless.utility.Diagnostics.gl("esp: set silhouette colour");
            boolean invis = player.isInvisible();
            if (showInvisible.isToggled()) player.setInvisible(false);
            mc.getRenderManager().renderEntityStatic(player, partialTicks, true);
            player.setInvisible(invis);
            // Built inside the check: the concatenation runs before the call, so leaving it
            // bare pays for a string per player per frame with diagnostics switched off.
            if (mindless.utility.Diagnostics.isEnabled()) {
                mindless.utility.Diagnostics.gl("esp: drew silhouette for " + player.getName());
            }
            glowShader.stop();
        }
        mindless.utility.Diagnostics.gl("esp: silhouette pass");
        renderingOutlinePass = false;

        mc.gameSettings.entityShadows = shadows;
        mc.entityRenderer.disableLightmap();
        // Ortho for the composite. Done while the outline buffer is still bound, because this also
        // clears depth and that buffer has no depth attachment for it to damage.
        mc.entityRenderer.setupOverlayRendering();
        mc.getFramebuffer().bindFramebuffer(true);

        // A Gaussian smear of the silhouette's coverage, tinted and added over the scene. The
        // Kawase bloom that used to run here wrote its result straight onto the main framebuffer
        // and was then overdrawn by a two-texel dilation of the same silhouette, so what survived
        // was a hard traced edge with the soft part fighting it rather than a glow.
        //
        // Glow size is in screen pixels; the multiplier turns the slider's 0-10 into a reach wide
        // enough to read as a halo rather than as a traced edge.
        float glowSize = (float) outlineGlowSize.getInput();
        if (glowSize > 0.0f && glowBloomShader.isValid()) {
            mc.getFramebuffer().bindFramebuffer(false);
            glowBloomShader.render(outlineFramebuffer, glowSize * 4.0f,
                    (float) outlineGlowStrength.getInput(), oR, oG, oB);
        }
        else if (glowSize > 0.0f) {
            // Fall back to the old bloom if the driver would not build the Gaussian. Worse
            // looking, but a glow that renders beats one that vanishes with no explanation.
            KawaseBloom.renderBlur(outlineFramebuffer.framebufferTexture,
                    Math.max(1, Math.round(glowSize)), glowSize);
        }
        mc.getFramebuffer().bindFramebuffer(false);
        // The crisp traced edge is now opt-in: it reads as an outline, which is the opposite of
        // what the glow is for, but it sharpens the silhouette when both are wanted together.
        if (outlineEdge.isToggled()) {
            mc.getFramebuffer().bindFramebuffer(false);
            separableOutlineShader.render(outlineFramebuffer);
        }
        outlineFramebuffer.framebufferClear();
        mc.getFramebuffer().bindFramebuffer(true);

        RenderUtils.popAttrib();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        // glPopAttrib puts real GL back, but GlStateManager never sees it happen, so its cache
        // still holds whatever this pass left behind and the next enableTexture2D or color is
        // skipped as redundant -- which is what drew the ESP nametags flat and black afterwards.
        RenderUtils.syncGlState();
        mindless.utility.Diagnostics.gl("esp: outline pass complete");
    }

    private Framebuffer createOutlineFramebuffer(Framebuffer framebuffer) {
        // Display-sized, like every other glow pass here. At three quarters of the width the blur
        // reached three quarters as far sideways as it did vertically, because both halves share a
        // single texel-size uniform taken from the display, and the halo came out an ellipse.
        framebuffer = RenderUtils.createFrameBuffer(framebuffer, false);
        if (framebuffer == null) return null;
        framebuffer.setFramebufferColor(0.0f, 0.0f, 0.0f, 0.0f);
        // Linear filtering: at a wide radius the seventeen taps sit several pixels apart, and
        // nearest sampling turns that spacing into visible rings.
        framebuffer.setFramebufferFilter(GL11.GL_LINEAR);
        return framebuffer;
    }

    private static final class RectBatch {
        private final Tessellator tessellator = Tessellator.getInstance();
        private final WorldRenderer renderer = tessellator.getWorldRenderer();
        private boolean drawing;

        private void begin() {
            if (drawing) return;
            renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            drawing = true;
        }

        private void add(double left, double top, double right, double bottom, int color) {
            if (!drawing) begin();
            int alpha = color >>> 24 & 255;
            int red = color >>> 16 & 255;
            int green = color >>> 8 & 255;
            int blue = color & 255;
            renderer.pos(left, bottom, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(right, bottom, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(right, top, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(left, top, 0.0D).color(red, green, blue, alpha).endVertex();
        }

        private void flush() {
            if (!drawing) return;
            tessellator.draw();
            drawing = false;
        }
    }
}
