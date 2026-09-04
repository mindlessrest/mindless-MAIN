package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.runtime.LunarEventBridge;
import mindless.module.Module;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
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
    private final SliderSetting barColorMode;
    private final ColorSetting barColor;
    private final ColorSetting barColorLow;
    private final ColorSetting barColorHigh;
    private final SliderSetting barGradientAxis;
    private final SliderSetting barWidth;
    private final SliderSetting barSide;
    private final ColorSetting barBackground;
    private final ButtonSetting barOutline;
    private final ButtonSetting absorption;
    private final ColorSetting absorptionColor;
    private final ButtonSetting healthNumber;
    private final SliderSetting healthNumberMode;
    private final ButtonSetting armorBar;
    private final SliderSetting armorBarMode;
    private final ButtonSetting armorItems;
    private final ButtonSetting armorDurability;
    private final SliderSetting armorPosition;
    private final ButtonSetting tags;
    private final SliderSetting nameSource;
    private final SliderSetting nameColorMode;
    private final ColorSetting nameColor;
    private final ColorSetting friendColor;
    private final ColorSetting enemyColor;
    private final ButtonSetting tagDistance;
    private final ButtonSetting tagPing;
    private final ButtonSetting tagBackground;
    private final ColorSetting tagBackgroundColor;
    private final SliderSetting tagBackgroundRadius;
    private final SliderSetting tagPadding;

    private final ButtonSetting playerStats;
    private final SliderSetting statsLayout;
    private final SliderSetting statsPosition;
    private final SliderSetting statsScale;
    private final ButtonSetting statsColorByFkdr;
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

    private static final String[] BAR_COLOR_MODES = {"Health gradient", "Static", "Custom gradient", "Array list"};
    private static final int BAR_HSB = 0;
    private static final int BAR_STATIC = 1;
    private static final int BAR_CUSTOM = 2;
    private static final int BAR_ARRAYLIST = 3;

    private static final String[] NAME_COLOR_MODES = {"Team", "Friend / enemy", "Health", "Custom", "Array list"};
    private static final int NAME_TEAM = 0;
    private static final int NAME_RELATION = 1;
    private static final int NAME_HEALTH = 2;
    private static final int NAME_CUSTOM = 3;
    private static final int NAME_ARRAYLIST = 4;

    private static final String[] STATS_LAYOUTS = {"Star + FKDR", "FKDR", "Star", "Star + FKDR + WS"};
    private static final int STATS_STAR_FKDR = 0;
    private static final int STATS_FKDR = 1;
    private static final int STATS_STAR = 2;
    private static final int STATS_FULL = 3;

    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();
    private final SliderSetting font;
private MindlessFontRenderer espFont() {
        if (font == null) return FontManager.getNametagRenderer(FONT_OPTIONS[0]);
        int index = (int) Math.max(0, Math.min(FONT_OPTIONS.length - 1, font.getInput()));
        return FontManager.getNametagRenderer(FONT_OPTIONS[index]);
    }

    public static boolean renderingOutlinePass = false;
    private Framebuffer outlineFramebuffer;
private final java.util.List<EntityPlayer> outlineCandidates = new java.util.ArrayList<EntityPlayer>();
    private final SeparableOutlineShader separableOutlineShader = new SeparableOutlineShader();
    private final GlowBloomShader glowBloomShader = new GlowBloomShader();
    private final GlowShader glowShader = new GlowShader();

    public SexyESP() {
        super("Player ESP", "Shows players, and items, through walls.", category.render, 0);
        instance = this;

        GroupSetting boxGroup = new GroupSetting("Box");
        registerSetting(boxGroup);
        registerSetting(outline = new ButtonSetting(boxGroup, "Outline", true));
        registerSetting(boxMode = new SliderSetting(boxGroup, "Mode", 0, new String[]{"Box", "Corners"}));

        GroupSetting healthGroup = new GroupSetting("Health");
        registerSetting(healthGroup);
        registerSetting(healthBar = new ButtonSetting(healthGroup, "Health bar", true));
        registerSetting(barColorMode = new SliderSetting(healthGroup, "Bar color", BAR_HSB, BAR_COLOR_MODES));
        registerSetting(barColor = new ColorSetting(healthGroup, "Bar static color", 85, 255, 85));
        registerSetting(barColorLow = new ColorSetting(healthGroup, "Bar low color", 255, 60, 60));
        registerSetting(barColorHigh = new ColorSetting(healthGroup, "Bar high color", 85, 255, 85));
        registerSetting(barGradientAxis = new SliderSetting(healthGroup, "Gradient axis", 0,
                new String[]{"Vertical", "Horizontal"}));
        registerSetting(barWidth = new SliderSetting(healthGroup, "Bar width", 1.0, 0.5, 5.0, 0.25));
        registerSetting(barSide = new SliderSetting(healthGroup, "Bar side", 0, new String[]{"Left", "Right"}));
        registerSetting(barBackground = new ColorSetting(healthGroup, "Bar background", 0, 0, 0, 120));
        registerSetting(barOutline = new ButtonSetting(healthGroup, "Bar outline", false));
        registerSetting(absorption = new ButtonSetting(healthGroup, "Absorption", true));
        registerSetting(absorptionColor = new ColorSetting(healthGroup, "Absorption color", 255, 215, 0));
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
        registerSetting(font = new SliderSetting(tagGroup, "Font", 0, FONT_OPTIONS));
        registerSetting(nameSource = new SliderSetting(tagGroup, "Name source", 0,
                new String[]{"Display name", "Username"}));
        registerSetting(nameColorMode = new SliderSetting(tagGroup, "Name color", NAME_TEAM, NAME_COLOR_MODES));
        registerSetting(nameColor = new ColorSetting(tagGroup, "Custom name color", 255, 255, 255));
        registerSetting(friendColor = new ColorSetting(tagGroup, "Friend color", 85, 255, 85));
        registerSetting(enemyColor = new ColorSetting(tagGroup, "Enemy color", 255, 85, 85));
        registerSetting(tagDistance = new ButtonSetting(tagGroup, "Distance in name", false));
        registerSetting(tagPing = new ButtonSetting(tagGroup, "Ping in name", false));
        registerSetting(tagBackground = new ButtonSetting(tagGroup, "Background", false));
        registerSetting(tagBackgroundColor = new ColorSetting(tagGroup, "Background color", 0, 0, 0, 128));
        registerSetting(tagBackgroundRadius = new SliderSetting(tagGroup, "Background radius", 2.0, 0.0, 8.0, 0.5));
        registerSetting(tagPadding = new SliderSetting(tagGroup, "Background padding", 2.0, 0.0, 8.0, 0.5));
        registerSetting(itemTags = new ButtonSetting(tagGroup, "Held item", true));
        registerSetting(fontScale = new SliderSetting(tagGroup, "Font scale", 0.5, 0.25, 1.0, 0.05));
        registerSetting(distanceTextScale = new ButtonSetting(tagGroup, "Distance scaling", true));
        registerSetting(textBorder = new ButtonSetting(tagGroup, "Black text outline", true));

        GroupSetting statsGroup = new GroupSetting("Stats");
        registerSetting(statsGroup);
        registerSetting(playerStats = new ButtonSetting(statsGroup, "Player stats", false));
        registerSetting(statsLayout = new SliderSetting(statsGroup, "Line", STATS_STAR_FKDR, STATS_LAYOUTS));
        registerSetting(statsPosition = new SliderSetting(statsGroup, "Position", 0, new String[]{"Above name", "Below name"}));
        registerSetting(statsScale = new SliderSetting(statsGroup, "Scale", "x", 0.8, 0.4, 1.5, 0.05));
        registerSetting(statsColorByFkdr = new ButtonSetting(statsGroup, "Color by FKDR", true));

        GroupSetting outlineGroup = new GroupSetting("Outline");
        registerSetting(outlineGroup);
        registerSetting(outlineEnabled = new ButtonSetting(outlineGroup, "Enabled", false));
        registerSetting(outlineGlowSize = new SliderSetting(outlineGroup, "Glow size", 2.5, 0.0, 10.0, 0.5));
        registerSetting(outlineGlowStrength = new SliderSetting(outlineGroup, "Glow strength", 1.0, 0.1, 3.0, 0.1));
        registerSetting(outlineEdge = new ButtonSetting(outlineGroup, "Edge", true));
        registerSetting(outlineTeamColor = new ButtonSetting(outlineGroup, "Team color", false));
        registerSetting(outlineColor = new ColorSetting(outlineGroup, "Color", 180, 0, 255));

        registerSetting(localPlayer = new ButtonSetting("Local player", true));
        registerSetting(droppedItems = new ButtonSetting("Dropped items", false));
        registerSetting(showInvisible = new ButtonSetting("Show invisible", false));
        registerSetting(teamCheck = new ButtonSetting("Team check", false));
        registerSetting(colorMode = new SliderSetting("Color mode", 0, new String[]{"Custom", "Rainbow", "Team"}));
        registerSetting(color = new ColorSetting("Color", 255, 255, 255));
        registerSetting(maxDistance = new SliderSetting("Max distance", 128.0, 16.0, 512.0, 8.0));
    }
@Override
    public void guiUpdate() {
        int barMode = (int) barColorMode.getInput();
        boolean bar = healthBar.isToggled();
        barColorMode.setVisible(bar, this);
        barColor.setVisible(bar && barMode == BAR_STATIC, this);
        barColorLow.setVisible(bar && barMode == BAR_CUSTOM, this);
        barColorHigh.setVisible(bar && barMode == BAR_CUSTOM, this);
        barGradientAxis.setVisible(bar && (barMode == BAR_CUSTOM || barMode == BAR_ARRAYLIST), this);
        barWidth.setVisible(bar, this);
        barSide.setVisible(bar, this);
        barBackground.setVisible(bar, this);
        barOutline.setVisible(bar, this);
        absorption.setVisible(bar, this);
        absorptionColor.setVisible(bar && absorption.isToggled(), this);
        healthNumberMode.setVisible(healthNumber.isToggled(), this);

        int nameMode = (int) nameColorMode.getInput();
        boolean named = tags.isToggled();
        nameSource.setVisible(named, this);
        nameColorMode.setVisible(named, this);
        nameColor.setVisible(named && (nameMode == NAME_CUSTOM || nameMode == NAME_RELATION), this);
        friendColor.setVisible(named && nameMode == NAME_RELATION, this);
        enemyColor.setVisible(named && nameMode == NAME_RELATION, this);
        tagDistance.setVisible(named, this);
        tagPing.setVisible(named, this);
        tagBackgroundColor.setVisible(tagBackground.isToggled(), this);
        tagBackgroundRadius.setVisible(tagBackground.isToggled(), this);
        tagPadding.setVisible(tagBackground.isToggled(), this);

        boolean stats = playerStats.isToggled();
        statsLayout.setVisible(stats, this);
        statsPosition.setVisible(stats, this);
        statsScale.setVisible(stats, this);
        statsColorByFkdr.setVisible(stats && (int) statsLayout.getInput() != STATS_STAR, this);

        armorBarMode.setVisible(armorBar.isToggled(), this);
        armorDurability.setVisible(armorItems.isToggled(), this);
        armorPosition.setVisible(armorItems.isToggled(), this);

        boolean glow = outlineEnabled.isToggled();
        outlineGlowSize.setVisible(glow, this);
        outlineGlowStrength.setVisible(glow, this);
        outlineEdge.setVisible(glow, this);
        outlineTeamColor.setVisible(glow, this);
        outlineColor.setVisible(glow && !outlineTeamColor.isToggled(), this);

        color.setVisible((int) colorMode.getInput() == 0, this);
    }

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
        if (!RenderUtils.isInViewFrustum(entity.getEntityBoundingBox().expand(0.35D, 0.35D, 0.35D))) return;
        if (projectBounds(entity, renderManager, partialTicks, resolution, projectedBounds)) {
            renderEntity(entity, projectedBounds);
        }
    }

    private boolean projectBounds(Entity entity, RenderManager renderManager, float partialTicks,
                                  ScaledResolution resolution, Bounds output) {
        double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - renderManager.viewerPosX;
        double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - renderManager.viewerPosY;
        double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - renderManager.viewerPosZ;

        double h  = entity.height;
        double hw = entity.width / 2.0;

        double minX = Double.MAX_VALUE,  maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE,  maxY = -Double.MAX_VALUE;
        int valid = 0;
        double[] pt = projectedPoint;

        for (int xi = -1; xi <= 1; xi += 2) {
            for (int zi = -1; zi <= 1; zi += 2) {
                for (int yi = 0; yi <= 1; yi++) {
                    if (!RenderUtils.projectTo2D(projectionContext,
                            ex + xi * hw, ey + yi * h, ez + zi * hw, pt))
                        continue;

                    double d = pt[2];
                    if (d <= 0.005 || d >= 1.0) continue;

                    if (pt[0] < minX) minX = pt[0];
                    if (pt[0] > maxX) maxX = pt[0];
                    if (pt[1] < minY) minY = pt[1];
                    if (pt[1] > maxY) maxY = pt[1];
                    valid++;
                }
            }
        }
        if (valid < 4) return false;

        output.set(minX, minY, maxX, maxY);

        // Two more projections per entity, down the middle rather than round the corners. Cheap
        // next to the eight already done, and the only way to get a line that starts at the feet
        // and stops at the top of the head whatever angle the camera is at.
        double footY, headY;
        if (RenderUtils.projectTo2D(projectionContext, ex, ey, ez, pt)
                && pt[2] > 0.005 && pt[2] < 1.0) {
            footY = pt[1];
        }
        else {
            footY = maxY;
        }
        if (RenderUtils.projectTo2D(projectionContext, ex, ey + h, ez, pt)
                && pt[2] > 0.005 && pt[2] < 1.0) {
            headY = pt[1];
        }
        else {
            headY = minY;
        }
        output.setAxis(Math.min(footY, headY), Math.max(footY, headY));
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
        double edge = Math.min(1.0, Math.min(Math.abs(right - left), Math.abs(bottom - top)) / 2.0);
        double inner = Math.min(0.5, edge);
        drawFlatRect(left - edge, top - edge, right + edge, top + edge, 0xFF000000);
        drawFlatRect(left - edge, bottom - edge, right + edge, bottom + edge, 0xFF000000);
        drawFlatRect(left - edge, top, left + edge, bottom, 0xFF000000);
        drawFlatRect(right - edge, top, right + edge, bottom, 0xFF000000);
        drawFlatRect(left, top, right, top + inner, renderColor);
        drawFlatRect(left, bottom - inner, right, bottom, renderColor);
        drawFlatRect(left, top, left + inner, bottom, renderColor);
        drawFlatRect(right - inner, top, right, bottom, renderColor);
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
        double barTop = b.axisTop;
        double barBottom = b.axisBottom;
        double barSpan = Math.max(0.5, b.axisHeight());
        double healthY = barBottom - barSpan * healthRatio;

        boolean barOnRight = (int) barSide.getInput() == 1;
        double width = Math.max(0.5, barWidth.getInput());
        double trackOuter = barOnRight ? b.right + 1.5 + width + 1.0 : b.left - 1.5 - width - 1.0;
        double trackInner = barOnRight ? b.right + 1.5 : b.left - 1.5;
        double fillOuter = barOnRight ? trackInner + 0.5 : trackInner - 0.5;
        double fillInner = barOnRight ? fillOuter + width : fillOuter - width;
        double trackLeft = Math.min(trackOuter, trackInner);
        double trackRight = Math.max(trackOuter, trackInner);
        double fillLeft = Math.min(fillOuter, fillInner);
        double fillRight = Math.max(fillOuter, fillInner);

        if (healthBar.isToggled()) {
            drawFlatRect(trackLeft, barTop, trackRight, barBottom, barBackground.getColor());
            drawHealthFill(fillLeft, healthY, fillRight, barBottom, healthRatio, b);
            if (absorption.isToggled() && living.getAbsorptionAmount() > 0) {
                double absorptionHeight = Math.min(barSpan, barSpan * living.getAbsorptionAmount() / maxHealth);
                drawFlatRect(fillLeft, barBottom - absorptionHeight, fillRight, barBottom,
                        0xFF000000 | absorptionColor.getRGB());
            }
            if (barOutline.isToggled()) {
                drawOutlinedRect(trackLeft, barTop, trackRight, barBottom, 0xFF000000);
            }
        }

        if (armorBar.isToggled()) drawArmorBar(living, b);

        if (healthNumber.isToggled()) {
            String hpText = (int) healthNumberMode.getInput() == 0
                    ? healthFormat.format(health) + " \u00A7c\u2764"
                    : (int) (healthRatio * 100) + "%";
            double scale = fontScale.getInput();
            double numberX = barOnRight
                    ? trackRight + 2.0
                    : b.left - 5 - espFont().getStringWidth(hpText) * scale;
            drawScaledString(hpText, numberX,
                    healthY - espFont().getFontHeight() * scale / 2.0, scale, false);
        }

        if (armorItems.isToggled() && b.height() > 32.0) drawArmorItems(living, b);

        double tagScale = getTagScale(b);
        double nameHeight = espFont().getFontHeight() * tagScale;
        double nameY = b.top - 2 - nameHeight;
        boolean statsAbove = (int) statsPosition.getInput() == 0;

        if (tags.isToggled()) {
            boolean ownColour = (int) nameColorMode.getInput() != NAME_TEAM;
            buildNameSegments(nameLabel(living), nameTagColor(living, healthRatio, b), ownColour);
            drawNameTag(b.left + b.width() / 2.0, nameY, tagScale);
        }

        if (playerStats.isToggled() && living instanceof EntityPlayer) {
            String stats = statsLabel((EntityPlayer) living);
            if (stats != null) {
                double statsTagScale = tagScale * statsScale.getInput();
                double statsHeight = espFont().getFontHeight() * statsTagScale;
                double statsY = statsAbove ? nameY - statsHeight - 1.0 : nameY + nameHeight + 1.0;
                drawTag(stats, b.left + b.width() / 2.0, statsY, statsTagScale, 0xFFFFFFFF);
            }
        }

        if (itemTags.isToggled() && living.getHeldItem() != null) {
            drawTag(living.getHeldItem().getDisplayName(), b.left + b.width() / 2.0,
                    b.bottom + 2, getTagScale(b), 0xFFFFFFFF);
        }
    }

    /**
     * Paints the filled part of the bar.
     *
     * The gradient modes used to resolve to a single colour for the whole bar, so a "gradient"
     * was really just a flat blend picked by health. It now runs along the bar itself, and the
     * axis chooses whether that is up its length or across its width -- the bar is vertical, so
     * along its length is the one that actually reads.
     */
    private void drawHealthFill(double left, double top, double right, double bottom,
                                double healthRatio, Bounds b) {
        int mode = (int) barColorMode.getInput();
        if (mode != BAR_CUSTOM && mode != BAR_ARRAYLIST) {
            drawFlatRect(left, top, right, bottom, healthBarColor(healthRatio, b));
            return;
        }

        boolean vertical = (int) barGradientAxis.getInput() == 0;
        int steps = vertical ? 12 : 6;
        double span = vertical ? bottom - top : right - left;
        if (span <= 0.01) {
            return;
        }

        for (int i = 0; i < steps; i++) {
            double t0 = i / (double) steps;
            double t1 = (i + 1) / (double) steps;
            // Sampled at the middle of each band so the two ends keep their true colours.
            int color = gradientSample(mode, (t0 + t1) * 0.5, b, vertical);
            if (vertical) {
                drawFlatRect(left, top + span * t0, right, top + span * t1, color);
            }
            else {
                drawFlatRect(left + span * t0, top, left + span * t1, bottom, color);
            }
        }
    }

    private int gradientSample(int mode, double t, Bounds b, boolean vertical) {
        if (mode == BAR_CUSTOM) {
            // Low colour at the start of the run, high colour at the end.
            return 0xFF000000 | lerpRgb(barColorLow.getRGB(), barColorHigh.getRGB(), (float) t);
        }
        double along = vertical
                ? b.axisTop + b.axisHeight() * t
                : b.left + (b.right - b.left) * t;
        return 0xFF000000 | (HUD.getHudColor(HUD.hudWavePhase(0.0, along)) & 0xFFFFFF);
    }

    /**
     * Array-list mode reads HUD.getHudColor with the bar's own screen position as the phase, so a
     * wave or gradient running through the module list carries on across the bars rather than
     * restarting at every player.
     */
    private int healthBarColor(double healthRatio, Bounds b) {
        switch ((int) barColorMode.getInput()) {
            case BAR_STATIC:
                return 0xFF000000 | barColor.getRGB();
            case BAR_CUSTOM:
                return 0xFF000000 | lerpRgb(barColorLow.getRGB(), barColorHigh.getRGB(), (float) healthRatio);
            case BAR_ARRAYLIST:
                return 0xFF000000 | (HUD.getHudColor(HUD.hudWavePhase(0.0, b.left)) & 0xFFFFFF);
            case BAR_HSB:
            default:
                return Color.HSBtoRGB((float) (healthRatio / 3.0), 1.0F, 1.0F) | 0xFF000000;
        }
    }

    private int nameTagColor(EntityLivingBase living, double healthRatio, Bounds b) {
        switch ((int) nameColorMode.getInput()) {
            case NAME_RELATION:
                if (living instanceof EntityPlayer) {
                    EntityPlayer player = (EntityPlayer) living;
                    if (Utils.isFriended(player)) return 0xFF000000 | friendColor.getRGB();
                    if (Utils.isEnemy(player)) return 0xFF000000 | enemyColor.getRGB();
                }
                return 0xFF000000 | nameColor.getRGB();
            case NAME_HEALTH:
                return Color.HSBtoRGB((float) (healthRatio / 3.0), 1.0F, 1.0F) | 0xFF000000;
            case NAME_CUSTOM:
                return 0xFF000000 | nameColor.getRGB();
            case NAME_ARRAYLIST:
                return 0xFF000000 | (HUD.getHudColor(HUD.hudWavePhase(0.0, b.left)) & 0xFFFFFF);
            case NAME_TEAM:
            default:
                return 0xFFFFFFFF;
        }
    }

    private static final int[] SECTION_COLOURS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final String SECTION_CODES = "0123456789abcdef";

    private static final class NameSegment {
        String text;
        int color;
    }

    private final java.util.List<NameSegment> nameSegments = new java.util.ArrayList<NameSegment>();
    private int nameSegmentCount;
    private final StringBuilder namePlain = new StringBuilder();
    private final StringBuilder nameRun = new StringBuilder();

    /**
     * Splits a formatted name into plain runs with their colours resolved.
     *
     * The old tag drew its black outline from EnumChatFormatting.getTextWithoutFormattingCodes and
     * the coloured text from the raw string. Those two disagree: the helper strips only *valid*
     * codes, while the renderer skips a section sign and the character after it whatever that
     * character is. Hypixel display names carry section signs followed by non-codes, so the
     * outline kept glyphs the fill dropped and painted them in black beside the name, with
     * everything after them shifted.
     *
     * Building the runs once and drawing both passes from them removes the possibility: there is
     * only one glyph sequence now, and no pass ever sees a control code.
     */
    private void buildNameSegments(String formatted, int defaultColor, boolean forceColor) {
        nameSegmentCount = 0;
        namePlain.setLength(0);
        nameRun.setLength(0);
        if (formatted == null) {
            return;
        }

        int color = defaultColor;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);

            if (c == '\u00a7') {
                if (i + 1 >= formatted.length()) {
                    break;
                }
                char code = Character.toLowerCase(formatted.charAt(++i));
                if (forceColor) {
                    continue;
                }
                int index = SECTION_CODES.indexOf(code);
                if (index >= 0) {
                    pushNameRun(color);
                    color = SECTION_COLOURS[index];
                }
                else if (code == 'r') {
                    pushNameRun(color);
                    color = defaultColor;
                }
                continue;
            }

            if (Character.isISOControl(c)) {
                continue;
            }
            nameRun.append(c);
            namePlain.append(c);
        }
        pushNameRun(color);
    }

    private void pushNameRun(int color) {
        if (nameRun.length() == 0) {
            return;
        }
        NameSegment segment;
        if (nameSegmentCount < nameSegments.size()) {
            segment = nameSegments.get(nameSegmentCount);
        }
        else {
            segment = new NameSegment();
            nameSegments.add(segment);
        }
        segment.text = nameRun.toString();
        segment.color = color;
        nameSegmentCount++;
        nameRun.setLength(0);
    }

    /**
     * Draws the built runs: background, then the outline from the concatenated plain text in one
     * call per offset, then each run in its own colour. The outline and the fill share a glyph
     * sequence by construction, so they cannot drift apart.
     */
    private void drawNameTag(double centerX, double y, double scale) {
        if (nameSegmentCount == 0) {
            return;
        }

        MindlessFontRenderer tagFont = espFont();
        String plain = namePlain.toString();
        double width = tagFont.getStringWidth(plain) * scale;

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (tagBackground.isToggled()) {
            double pad = tagPadding.getInput() * scale;
            double padY = Math.max(0.5, pad * 0.75);
            drawTagBackground(centerX - width / 2 - pad, y - padY,
                    centerX + width / 2 + pad, y + tagFont.getFontHeight() * scale + padY,
                    tagBackgroundRadius.getInput() * scale, tagBackgroundColor.getColor());
        }

        rectBatch.flush();
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX - width / 2.0, y, 0);
        GlStateManager.scale(scale, scale, 1);

        if (textBorder.isToggled()) {
            int border = 0xF0000000;
            tagFont.drawString(plain, -1, -1, border, false);
            tagFont.drawString(plain, 0, -1, border, false);
            tagFont.drawString(plain, 1, -1, border, false);
            tagFont.drawString(plain, -1, 0, border, false);
            tagFont.drawString(plain, 1, 0, border, false);
            tagFont.drawString(plain, -1, 1, border, false);
            tagFont.drawString(plain, 0, 1, border, false);
            tagFont.drawString(plain, 1, 1, border, false);
        }

        float penX = 0f;
        for (int i = 0; i < nameSegmentCount; i++) {
            NameSegment segment = nameSegments.get(i);
            tagFont.drawString(segment.text, penX, 0, 0xFF000000 | (segment.color & 0xFFFFFF), false);
            penX += tagFont.getStringWidth(segment.text);
        }

        GlStateManager.popMatrix();
        GlStateManager.disableTexture2D();
    }

    /** The name plus whichever extras are switched on, as one line. */
    private String nameLabel(EntityLivingBase living) {
        StringBuilder label = new StringBuilder(nameBase(living));
        if (tagDistance.isToggled() && mc.thePlayer != null) {
            label.append(" \u00A77").append((int) mc.thePlayer.getDistanceToEntity(living)).append('m');
        }
        if (tagPing.isToggled()) {
            int ping = pingOf(living);
            if (ping >= 0) {
                label.append(" \u00A78").append(ping).append("ms");
            }
        }
        return label.toString();
    }

    /**
     * Username only skips the scoreboard team prefix and suffix entirely, which is where the
     * stray section signs live on most servers.
     */
    private String nameBase(EntityLivingBase living) {
        if ((int) nameSource.getInput() == 1) {
            String name = living.getName();
            return name == null ? "" : name;
        }
        return living.getDisplayName().getFormattedText();
    }

    private int pingOf(EntityLivingBase living) {
        if (!(living instanceof EntityPlayer) || mc.getNetHandler() == null) return -1;
        net.minecraft.client.network.NetworkPlayerInfo info =
                mc.getNetHandler().getPlayerInfo(living.getUniqueID());
        return info == null ? -1 : info.getResponseTime();
    }

    /**
     * Bed Wars stats, read from the cache the Overlay module already fills. Nothing is fetched
     * unless an API key has been set over there, so this stays silent on other servers rather
     * than opening a second connection per player.
     */
    private String statsLabel(EntityPlayer player) {
        String name = player.getName();
        if (name == null || name.isEmpty()) return null;
        mindless.module.impl.bedwars.Overlay.requestStats(name);
        mindless.utility.HypixelBedWars.BedwarsPlayer stats =
                mindless.module.impl.bedwars.Overlay.statsFor(name);
        if (stats == null) return null;

        int star = stats.level == null ? 0 : stats.level.level;
        double fkdr = stats.overall == null ? 0.0 : stats.overall.finalKillDeathRatio;
        long streak = stats.overall == null ? 0L : stats.overall.winstreak;
        String fkdrText = String.format("%.2f", fkdr);
        String fkdrColored = statsColorByFkdr.isToggled() ? fkdrColor(fkdr) + fkdrText : fkdrText;

        switch ((int) statsLayout.getInput()) {
            case STATS_FKDR:
                return fkdrColored;
            case STATS_STAR:
                return "\u00A76" + star + "\u272B";
            case STATS_FULL:
                return "\u00A76" + star + "\u272B \u00A7r" + fkdrColored + " \u00A7bWS " + streak;
            case STATS_STAR_FKDR:
            default:
                return "\u00A76" + star + "\u272B \u00A7r" + fkdrColored;
        }
    }

    private static String fkdrColor(double fkdr) {
        if (fkdr < 1.0) return "\u00A77";
        if (fkdr < 3.0) return "\u00A7a";
        if (fkdr < 6.0) return "\u00A7e";
        if (fkdr < 10.0) return "\u00A7c";
        return "\u00A75";
    }

    private static int lerpRgb(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private void drawArmorBar(EntityLivingBase living, Bounds b) {
        double ratio = MathHelper.clamp_double(living.getTotalArmorValue() / 20.0, 0, 1);
        double span = Math.max(0.5, b.axisHeight());
        drawFlatRect(b.right + 1.5, b.axisTop, b.right + 4, b.axisBottom, 0x78000000);
        if (ratio > 0) {
            drawFlatRect(b.right + 2, b.axisBottom - span * ratio, b.right + 3.5, b.axisBottom, 0xFF00FFFF);
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
            double nameY = b.top - 2 - espFont().getFontHeight() * tagScale;
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
                double durabilityY = durY + (itemSize - espFont().getFontHeight() * durabilityScale) / 2.0;
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
                b.bottom + 2, getTagScale(b), 0xFFFFFFFF);
    }

    private double getTagScale(Bounds b) {
        double scale = fontScale.getInput();
        if (distanceTextScale.isToggled()) {
            scale *= MathHelper.clamp_double(b.height() / 48.0, 0.9, 1.1);
        }
        return scale;
    }

    private void drawTag(String text, double centerX, double y, double scale, int textColor) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        MindlessFontRenderer tagFont = espFont();
        double width = tagFont.getStringWidth(text) * scale;
        if (tagBackground.isToggled()) {
            double pad = tagPadding.getInput() * scale;
            double padY = Math.max(0.5, pad * 0.75);
            double left = centerX - width / 2 - pad;
            double top = y - padY;
            double right = centerX + width / 2 + pad;
            double bottom = y + tagFont.getFontHeight() * scale + padY;
            double radius = tagBackgroundRadius.getInput() * scale;
            drawTagBackground(left, top, right, bottom, radius, tagBackgroundColor.getColor());
        }
        rectBatch.flush();
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX - width / 2.0, y, 0);
        GlStateManager.scale(scale, scale, 1);
        if (textBorder.isToggled()) {
            // Stripped the same way the renderer skips codes, not with the formatting helper --
            // that only removes valid ones, so a stray section sign used to survive into the
            // outline and paint glyphs the coloured pass never drew.
            String outlineText = stripSectionCodes(text);
            int border = 0xF0000000;
            tagFont.drawString(outlineText, -1, -1, border, false);
            tagFont.drawString(outlineText, 0, -1, border, false);
            tagFont.drawString(outlineText, 1, -1, border, false);
            tagFont.drawString(outlineText, -1, 0, border, false);
            tagFont.drawString(outlineText, 1, 0, border, false);
            tagFont.drawString(outlineText, -1, 1, border, false);
            tagFont.drawString(outlineText, 0, 1, border, false);
            tagFont.drawString(outlineText, 1, 1, border, false);
        }
        tagFont.drawString(text, 0, 0, textColor, false);
        GlStateManager.popMatrix();
        GlStateManager.disableTexture2D();
    }

    /** Drops every section sign and the character after it, valid code or not. */
    private static String stripSectionCodes(String text) {
        if (text == null || text.indexOf('\u00a7') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Rounded corners are approximated by stacking inset bars rather than tessellating a disc --
     * the tag batch is flat-rect only, and a shader round here would force a flush per nametag.
     */
    private void drawTagBackground(double left, double top, double right, double bottom,
                                   double radius, int color) {
        double r = Math.max(0.0, Math.min(radius, Math.min(right - left, bottom - top) / 2.0));
        if (r <= 0.15) {
            drawFlatRect(left, top, right, bottom, color);
            return;
        }
        drawFlatRect(left, top + r, right, bottom - r, color);

        // Four horizontal slices per cap, each inset to the circle at its widest edge. Enough to
        // read as a curve at nametag scale without a shader round, which would cost a batch flush
        // for every tag on screen.
        final int steps = 4;
        for (int i = 0; i < steps; i++) {
            double y0 = r * i / steps;
            double y1 = r * (i + 1) / steps;
            double dy = r - y1;
            double inset = r - Math.sqrt(Math.max(0.0, r * r - dy * dy));
            drawFlatRect(left + inset, top + y0, right - inset, top + y1, color);
            drawFlatRect(left + inset, bottom - y1, right - inset, bottom - y0, color);
        }
    }

    private void drawScaledString(String text, double x, double y, double scale, boolean centered) {
        MindlessFontRenderer stringFont = espFont();
        rectBatch.flush();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        float drawX = centered ? -stringFont.getStringWidth(text) / 2.0F : 0;
        stringFont.drawString(text, drawX - 0.5F, 0, 0xFF000000, false);
        stringFont.drawString(text, drawX + 0.5F, 0, 0xFF000000, false);
        stringFont.drawString(text, drawX, -0.5F, 0xFF000000, false);
        stringFont.drawString(text, drawX, 0.5F, 0xFF000000, false);
        stringFont.drawString(text, drawX, 0, 0xFFFFFFFF, false);
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

        // Head and feet as projected down the entity's own centre line, separate from the box.
        // The box is the 2D hull of all eight AABB corners, and under perspective the near-side
        // corners project much further out than the model actually reaches -- harmless for an
        // outline, wrong for anything that is supposed to measure the player.
        private double axisTop;
        private double axisBottom;

        private void set(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.axisTop = top;
            this.axisBottom = bottom;
        }

        private void setAxis(double axisTop, double axisBottom) {
            this.axisTop = axisTop;
            this.axisBottom = axisBottom;
        }

        private double width() { return right - left; }
        private double height() { return bottom - top; }
        private double axisHeight() { return axisBottom - axisTop; }
    }

    private void runOutlinePass(float partialTicks) {
        float glowSize = (float) outlineGlowSize.getInput();
        boolean drawEdge = outlineEdge.isToggled();
        if (glowSize <= 0.0f && !drawEdge) return;
        if (mindless.utility.Diagnostics.isEnabled() && !glowBloomShader.isValid()) {
            mindless.utility.Diagnostics.log("esp", "glow shader unavailable, falling back to bloom");
        }
        if (!glowShader.isValid()) return;
        if (!glowBloomShader.isValid() && !separableOutlineShader.isValid()) return;
        collectOutlineCandidates();
        if (outlineCandidates.isEmpty()) return;

        outlineFramebuffer = createOutlineFramebuffer(outlineFramebuffer, drawEdge ? 1 : 2);
        if (outlineFramebuffer == null) {
            outlineCandidates.clear();
            return;
        }

        mc.getFramebuffer().bindFramebuffer(true);
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

        glowShader.use();
        try {
            for (int i = 0; i < outlineCandidates.size(); i++) {
                EntityPlayer player = outlineCandidates.get(i);
                int pR = oR, pG = oG, pB = oB;
                if (useTeamColorOutline) {
                    int teamCol = Utils.getColorFromEntity(player);
                    if (teamCol != -1) {
                        pR = (teamCol >> 16) & 0xFF;
                        pG = (teamCol >> 8) & 0xFF;
                        pB = teamCol & 0xFF;
                    }
                }
                glowShader.setColor(pR, pG, pB, 255);
                boolean invis = player.isInvisible();
                try {
                    if (showInvisible.isToggled()) player.setInvisible(false);
                    mc.getRenderManager().renderEntityStatic(player, partialTicks, true);
                }
                finally {
                    player.setInvisible(invis);
                }
            }
        }
        finally {
            glowShader.stop();
            renderingOutlinePass = false;
            outlineCandidates.clear();
        }
        mindless.utility.Diagnostics.gl("esp: silhouette pass");

        mc.gameSettings.entityShadows = shadows;
        mc.entityRenderer.disableLightmap();
        mc.entityRenderer.setupOverlayRendering();
        mc.getFramebuffer().bindFramebuffer(true);
        if (glowSize > 0.0f && glowBloomShader.isValid()) {
            mc.getFramebuffer().bindFramebuffer(false);
            glowBloomShader.render(outlineFramebuffer, glowSize * 4.0f,
                    (float) outlineGlowStrength.getInput(), oR, oG, oB);
        }
        else if (glowSize > 0.0f) {
            KawaseBloom.renderBlur(outlineFramebuffer.framebufferTexture,
                    Math.max(1, Math.round(glowSize)), glowSize);
        }
        mc.getFramebuffer().bindFramebuffer(false);
        if (drawEdge) {
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
        RenderUtils.syncGlState();
        mindless.utility.Diagnostics.gl("esp: outline pass complete");
    }

    private void collectOutlineCandidates() {
        outlineCandidates.clear();
        double maxDistSq = maxDistance.getInput() * maxDistance.getInput();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidEntity(player)) continue;
            if (!RenderUtils.isInViewFrustum(player)) continue;
            if (!RenderUtils.isWithinDistanceSqToRenderView(player, maxDistSq)) continue;
            outlineCandidates.add(player);
        }
    }

    private Framebuffer createOutlineFramebuffer(Framebuffer framebuffer, int divisor) {
        framebuffer = RenderUtils.createScaledFrameBuffer(framebuffer, divisor, false);
        if (framebuffer == null) return null;
        framebuffer.setFramebufferColor(0.0f, 0.0f, 0.0f, 0.0f);
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
