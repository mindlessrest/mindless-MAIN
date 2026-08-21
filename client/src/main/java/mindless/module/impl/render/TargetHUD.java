package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.network.Backtrack;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Theme;
import mindless.utility.Timer;
import mindless.utility.Utils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.IOException;

public class TargetHUD extends Module {
    private SliderSetting mode;
    private SliderSetting theme;
    private SliderSetting glowSize;
    private ButtonSetting renderEsp;
    private ButtonSetting showDifference;
    private ButtonSetting showStatus;
    private ButtonSetting healthColor;

    private static final long POP_IN_MS = 250L;
    private static final long POP_OUT_MS = 200L;

    private Timer fadeTimer;
    private Timer healthBarTimer = null;
    private EntityLivingBase target;
    private long lastAliveMS;
    private double lastHealth;
    private float lastHealthBar;
    private long popInStart = -1;
    public int posX = 70;
    public int posY = 30;

    private String[] modes = new String[]{ "Modern", "Legacy" };

    public TargetHUD() {
        super("TargetHUD", category.render);
        this.liteModule = true;
        this.registerSetting(new DescriptionSetting("Only works with KillAura."));
        this.registerSetting(mode = new SliderSetting("Mode", 1, modes));
        this.registerSetting(theme = new SliderSetting("Theme", 0, Theme.THEMES_SETTING));
        this.registerSetting(glowSize = new SliderSetting("Glow size", 9.0, 2.0, 20.0, 0.5));
        this.registerSetting(new ButtonSetting("Edit position", () -> {
            mc.displayGuiScreen(new EditScreen());
        }));
        this.registerSetting(renderEsp = new ButtonSetting("Render ESP", true));
        this.registerSetting(showDifference = new ButtonSetting("Show difference", true));
        this.registerSetting(showStatus = new ButtonSetting("Show win or loss", true));
        this.registerSetting(healthColor = new ButtonSetting("Traditional health color", false));
    }

    @Override
    public void guiUpdate() {
        glowSize.setVisible(mode.getInput() == 0, this);
    }

    public void onDisable() {
        reset();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (!Utils.nullCheck()) {
            reset();
            return;
        }
        if (ev.phase == TickEvent.Phase.END) {
            if (mc.currentScreen != null) {
                reset();
                return;
            }
            if (KillAura.attackingEntity != null) {
                target = KillAura.attackingEntity;
                lastAliveMS = System.currentTimeMillis();
                fadeTimer = null;
                if (popInStart < 0) popInStart = System.currentTimeMillis();
            } else if (target != null) {
                if (System.currentTimeMillis() - lastAliveMS >= 400 && fadeTimer == null) {
                    (fadeTimer = new Timer((int) POP_OUT_MS)).start();
                }
            }
            else {
                return;
            }
            String playerInfo = target.getDisplayName().getFormattedText();
            double health = target.getHealth() / target.getMaxHealth();
            if (target.isDead) {
                health = 0;
            }
            if (health != lastHealth) {
                (healthBarTimer = new Timer(mode.getInput() == 0 ? 500 : 350)).start();
            }
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(target, true);
            drawTargetHUD(fadeTimer, playerInfo, health);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent renderWorldLastEvent) {
        if (!renderEsp.isToggled() || !Utils.nullCheck()) {
            return;
        }
        EntityLivingBase auraTarget = KillAura.target;

        if (auraTarget == null) {
            return;
        }

        if (ModuleManager.backtrack.isRenderingServerPositionFor(auraTarget)) {
            return;
        }

        drawPillEsp(auraTarget);
    }

    private void drawPillEsp(EntityLivingBase entity) {
        float partialTicks = mindless.runtime.AccessorBridge.Minecraft_getTimer(mc).renderPartialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - mc.getRenderManager().viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;

        float entityHeight = entity.height;
        double time = (System.currentTimeMillis() % 2000L) / 2000.0;
        float bounce = (float) (Math.sin(time * Math.PI * 2.0) * 0.5 + 0.5);
        float ringY = bounce * entityHeight;

        float radius = entity.width * 0.7f;
        int color = Theme.getGradient((int) theme.getInput(), 0);
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glDepthMask(false);

        int trailCount = 5;
        for (int trail = trailCount; trail >= 0; trail--) {
            float trailOffset = trail * 0.06f;
            float trailBounce = (float) (Math.sin((time - trailOffset) * Math.PI * 2.0) * 0.5 + 0.5);
            float trailY = trailBounce * entityHeight;
            float alpha = trail == 0 ? 1.0f : (1.0f - (float) trail / trailCount) * 0.35f;
            float lineWidth = trail == 0 ? 5.0f : 3.0f;

            GL11.glLineWidth(lineWidth);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glColor4f(r, g, b, alpha);
            int segments = 40;
            for (int i = 0; i < segments; i++) {
                double angle = Math.PI * 2.0 * i / segments;
                float px = (float) (Math.cos(angle) * radius);
                float pz = (float) (Math.sin(angle) * radius);
                GL11.glVertex3f(px, trailY, pz);
            }
            GL11.glEnd();
        }

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawTargetHUD(Timer fadeTimer, String string, double health) {
        if (showDifference.isToggled() && target != null) {
            float enemyHealth = target.isDead ? 0 : Utils.getTotalHealth(target);
            float playerHealth = Utils.getTotalHealth(mc.thePlayer);

            double diff = playerHealth - enemyHealth;
            double percent = (playerHealth / mc.thePlayer.getMaxHealth()) - (enemyHealth / target.getMaxHealth());

            diff = Utils.round(diff, 1);

            String color = percent < -0.4 ? "§c" : percent < -0.15 ? "§6" : percent <= 0.15 ? "§e" : percent <= 0.4 ? "§a" : "§2";

            if (diff > 0) {
                string += " " + color + "+" + Utils.asWholeNum(diff);
            }
            else if (diff < 0) {
                string += " " + color + "-" + Utils.asWholeNum(Math.abs(diff));
            }
        }
        if (showStatus.isToggled()) {
            string = string + " " + ((health <= Utils.getTotalHealth(mc.thePlayer) / mc.thePlayer.getMaxHealth()) ? "§aW" : "§cL");
        }
        final ScaledResolution scaledResolution = new ScaledResolution(mc);
        final int padding = 8;
        final int headSize = mc.fontRendererObj.FONT_HEIGHT + 18;
        final int targetStrWithPadding = mc.fontRendererObj.getStringWidth(string) + padding + headSize + 10;
        final int x = (scaledResolution.getScaledWidth() / 2 - targetStrWithPadding / 2) + posX;
        final int y = (scaledResolution.getScaledHeight() / 2 + 15) + posY;
        final int n6 = x - padding;
        final int n7 = y - padding;
        final int n8 = x + targetStrWithPadding;
        final int n9 = y + (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + padding;

        float popProgress;
        if (fadeTimer == null) {
            long elapsed = System.currentTimeMillis() - popInStart;
            popProgress = Math.min(1.0f, (float) elapsed / POP_IN_MS);
            popProgress = easeOutBack(popProgress);
        } else {
            float raw = fadeTimer.getValueFloat(0.0f, 1.0f, 1);
            popProgress = 1.0f - raw;
            popProgress = Math.max(0.0f, popProgress * popProgress);
        }

        if (popProgress <= 0.001f) {
            target = null;
            healthBarTimer = null;
            popInStart = -1;
            return;
        }

        int alpha = (int) (255 * popProgress);
        float scale = popProgress;
        float centerX = (n6 + n8) * 0.5f;
        float centerY = (n7 + n9 + 13) * 0.5f;

        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        GlStateManager.translate(-centerX, -centerY, 0.0f);

        final int maxAlphaOutline = Math.min(alpha, 110);
        final int maxAlphaBackground = Math.min(alpha, 210);
        final int[] gradientColors = Theme.getGradients((int) theme.getInput());
        switch ((int) mode.getInput()) {
            case 0: {
                float w = Math.abs((float) n6 - n8);
                float h = Math.abs((float) n7 - (n9 + 13));
                float thudRadius = 8.0f * ThemeManager.roundingScale();
                BlurUtils.prepareBlur();
                RoundedUtils.drawRound((float) n6, (float) n7, w, h, thudRadius, new Color(0, 0, 0, 255));
                BlurUtils.blurEnd(1, 1.4f, 0.60f);
                RoundedUtils.drawRound((float) n6, (float) n7, w, h, thudRadius, new Color(0, 0, 0, (int)(maxAlphaBackground * 0.4f)));
                break;
            }
            case 1:
                RenderUtils.drawRoundedGradientOutlinedRectangle((float) n6, (float) n7, (float) n8, (float) (n9 + 13), 10.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline), Utils.mergeAlpha(gradientColors[0], alpha), Utils.mergeAlpha(gradientColors[1], alpha));
                break;
        }
        final int n13 = n6 + 5 + headSize + 7;
        final int n14 = n8 - 6;
        final int n15 = n9;

        if (target instanceof EntityPlayer) {
            int headX = n6 + 5;
            int headY = n7 + 5;
            drawPlayerHead((EntityPlayer) target, headX, headY, headSize, headSize, alpha);
        }

        RenderUtils.drawRoundedRectangle((float) n13, (float) n15, (float) n14, (float) (n15 + 5), 4.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline));
        int mergedGradientLeft = Utils.mergeAlpha(gradientColors[0], maxAlphaBackground);
        int mergedGradientRight = Utils.mergeAlpha(gradientColors[1], maxAlphaBackground);
        float healthBar = (float) (int) (n14 + (n13 - n14) * (1 - health));
        boolean smoothBack = false;
        if (healthBar != lastHealthBar && lastHealthBar - n13 >= 3 && healthBarTimer != null ) {
            int type = mode.getInput() == 0 ? 4 : 1;
            float diff = lastHealthBar - healthBar;
            if (diff > 0) {
                lastHealthBar = lastHealthBar - healthBarTimer.getValueFloat(0, diff, type);
            }
            else {
                smoothBack = true;
                lastHealthBar = healthBarTimer.getValueFloat(lastHealthBar, healthBar, type);
            }
        }
        else {
            lastHealthBar = healthBar;
        }
        if (healthColor.isToggled()) {
            mergedGradientLeft = mergedGradientRight = Utils.mergeAlpha(Utils.getColorForHealth(health), maxAlphaBackground);
        }
        if (lastHealthBar > n14) {
            lastHealthBar = n14;
        }

        switch ((int) mode.getInput()) {
            case 0:
                RenderUtils.drawRoundedRectangle((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, Utils.darkenColor(mergedGradientRight, 25));
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, smoothBack ? lastHealthBar : healthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                break;
            case 1:
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                break;
        }
        GL11.glEnable(GL11.GL_BLEND);
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mindless.utility.font.RavenFontRenderer hudFont = HUD.getHudFontRenderer();
        hudFont.drawString(string, (float) n13, (float) y, (new Color(220, 220, 220, 255).getRGB() & 0xFFFFFF) | Utils.clamp(alpha + 15) << 24, true);
        GL11.glDisable(GL11.GL_BLEND);

        GlStateManager.popMatrix();
    }

    private void drawPlayerHead(EntityPlayer player, int x, int y, int width, int height, int alpha) {
        try {
            ResourceLocation skin;
            if (player instanceof AbstractClientPlayer) {
                skin = ((AbstractClientPlayer) player).getLocationSkin();
            } else {
                NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                if (playerInfo == null) return;
                skin = playerInfo.getLocationSkin();
            }
            if (skin == null) return;
            boolean depthEnabled = GL11.glIsEnabled(2929);
            boolean blendEnabled = GL11.glIsEnabled(3042);
            boolean cullEnabled = GL11.glIsEnabled(2884);
            boolean depthMask = GL11.glGetBoolean(2930);
            try {
                RenderUtils.prepareGuiTextureRenderState();
                GlStateManager.disableCull();
                mc.getTextureManager().bindTexture(skin);
                GlStateManager.color(1.0f, 1.0f, 1.0f, (float) alpha / 255.0f);
                float cornerRadius = Math.max(2.0f, (float) Math.min(width, height) * 0.14f);
                drawRoundedSkinLayer(x, y, width, height, cornerRadius, 8.0f, 8.0f, alpha);
                drawRoundedSkinLayer(x, y, width, height, cornerRadius, 40.0f, 8.0f, alpha);
            } finally {
                RenderUtils.restoreGuiRenderState(depthEnabled, blendEnabled, depthMask);
                if (cullEnabled) GlStateManager.enableCull();
                else GlStateManager.disableCull();
            }
        } catch (Exception ignored) {}
    }

    private void drawRoundedSkinLayer(float x, float y, float width, float height, float radius, float textureU, float textureV, int alpha) {
        float r = Math.min(radius, Math.min(width, height) * 0.5f);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, (float) alpha / 255.0f);
        GL11.glBegin(6); // GL_TRIANGLE_FAN
        addSkinVertex(x + width * 0.5f, y + height * 0.5f, x, y, width, height, textureU, textureV);
        addSkinCorner(x + width - r, y + r,          r, -90.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + width - r, y + height - r, r,   0.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + r,         y + height - r, r,  90.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + r,         y + r,          r, 180.0, x, y, width, height, textureU, textureV);
        addSkinVertex(x + width - r, y, x, y, width, height, textureU, textureV);
        GL11.glEnd();
    }

    private void addSkinCorner(float centerX, float centerY, float radius, double startDegrees,
                                float x, float y, float width, float height, float textureU, float textureV) {
        for (int segment = 0; segment <= 4; segment++) {
            double angle = Math.toRadians(startDegrees + segment * 22.5);
            addSkinVertex((float) (centerX + Math.cos(angle) * radius),
                          (float) (centerY + Math.sin(angle) * radius),
                          x, y, width, height, textureU, textureV);
        }
    }

    private void addSkinVertex(float vertexX, float vertexY, float x, float y, float width, float height, float textureU, float textureV) {
        float normalizedX = (vertexX - x) / width;
        float normalizedY = (vertexY - y) / height;
        GL11.glTexCoord2f((textureU + normalizedX * 8.0f) / 64.0f, (textureV + normalizedY * 8.0f) / 64.0f);
        GL11.glVertex2f(vertexX, vertexY);
    }

    private void reset() {
        fadeTimer = null;
        target = null;
        healthBarTimer = null;
        popInStart = -1;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float tm1 = t - 1.0f;
        return 1.0f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
    }

    public boolean isEspActiveFor(EntityLivingBase entity) {
        return this.isEnabled() && this.renderEsp.isToggled() && entity != null && KillAura.target == entity;
    }

    public Color getCurrentEspColor(int alpha) {
        int color = Theme.getGradient((int) theme.getInput(), 0);
        int safeAlpha = Math.max(0, Math.min(255, alpha));

        return new Color(color >> 16 & 255, color >> 8 & 255, color & 255, safeAlpha);
    }

    class EditScreen extends GuiScreen {
        GuiButtonExt resetPosition;
        boolean d = false;
        int miX = 0;
        int miY = 0;
        int maX = 0;
        int maY = 0;
        int aX = 70;
        int aY = 30;
        int laX = 0;
        int laY = 0;
        int lmX = 0;
        int lmY = 0;
        int clickMinX = 0;

        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            this.aX = posX;
            this.aY = posY;
        }

        public void drawScreen(int mX, int mY, float pt) {
            ScaledResolution res = new ScaledResolution(this.mc);
            drawRect(0, 0, this.width, this.height, -1308622848);
            int miX = this.aX;
            int miY = this.aY;
            String playerInfo = mc.thePlayer.getDisplayName().getFormattedText();
            double health = mc.thePlayer.getHealth() / mc.thePlayer.getMaxHealth();
            if (mc.thePlayer.isDead) {
                health = 0;
            }
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(mc.thePlayer, true);
            drawTargetHUD(null, playerInfo, health);
            if (showStatus.isToggled()) {
                playerInfo = playerInfo + " " + ((health <= Utils.getTotalHealth(mc.thePlayer) / mc.thePlayer.getMaxHealth()) ? "§aW" : "§cL");
            }
            int editHeadSize = mc.fontRendererObj.FONT_HEIGHT + 18;
            int totalContentWidth = mc.fontRendererObj.getStringWidth(playerInfo) + 8 + editHeadSize + 10;
            int maX = res.getScaledWidth() / 2 + miX + totalContentWidth / 2;
            int maY = (res.getScaledHeight() / 2 + 15) +  miY + (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + 8;
            this.miX = miX;
            this.miY = miY;
            this.maX = maX;
            this.maY = maY;
            this.clickMinX = miX;
            posX = miX;
            posY = miY;
            String edit = "Edit the HUD position by dragging.";
            int x = res.getScaledWidth() / 2 - fontRendererObj.getStringWidth(edit) / 2;
            int y = res.getScaledHeight() / 2 - 20;
            RenderUtils.drawColoredString(edit, '-', x, y, 2L, 0L, true, this.mc.fontRendererObj);

            try {
                this.handleInput();
            }
            catch (IOException var12) {
            }

            super.drawScreen(mX, mY, pt);
        }

        protected void mouseClickMove(int mX, int mY, int b, long t) {
            super.mouseClickMove(mX, mY, b, t);
            if (b == 0) {
                if (this.d) {
                    this.aX = this.laX + (mX - this.lmX);
                    this.aY = this.laY + (mY - this.lmY);
                }
                else if (mX > this.clickMinX && mX < this.maX && mY > this.miY && mY < this.maY) {
                    this.d = true;
                    this.lmX = mX;
                    this.lmY = mY;
                    this.laX = this.aX;
                    this.laY = this.aY;
                }

            }
        }

        protected void mouseReleased(int mX, int mY, int s) {
            super.mouseReleased(mX, mY, s);
            if (s == 0) {
                this.d = false;
            }

        }

        public void actionPerformed(GuiButton b) {
            if (b == this.resetPosition) {
                this.aX = posX = 70;
                this.aY = posY = 30;
            }

        }

        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
