package mindless.effect.impl;

import mindless.effect.Effect;
import mindless.effect.EffectRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Random;

public final class ReassembleEffect extends Effect {

    private static final double GRAVITY = 0.010;
    private static final float BURST_END = 0.24f;
    private static final float SCATTER_END = 0.46f;
    private static final float ASSEMBLE_END = 0.73f;
    private static final float FIGURE_START = 0.58f;
    private static final float RISE_START = 0.70f;
    private static final float DISSOLVE_START = 0.82f;

    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final float[] tx;
    private final float[] ty;
    private final float[] tz;
    private final float[] delay;
    private final float[] phase;
    private final float[] trail;
    private final double particleSize;
    private final int rgb;
    private final int brightRgb;
    private final float glow;
    private final float yaw;
    private final ResourceLocation skin;
    private final boolean slim;

    private static ModelPlayer classicModel;
    private static ModelPlayer slimModel;

    public ReassembleEffect(double x, double y, double z, int durationTicks,
                            int count, double speed, double particleSize, int rgb, float glow,
                            float yaw, ResourceLocation skin, boolean slim, Random random) {
        super(x, y, z, durationTicks);
        int n = Math.max(16, Math.min(360, count));
        this.vx = new float[n];
        this.vy = new float[n];
        this.vz = new float[n];
        this.tx = new float[n];
        this.ty = new float[n];
        this.tz = new float[n];
        this.delay = new float[n];
        this.phase = new float[n];
        this.trail = new float[n];
        this.particleSize = particleSize;
        this.rgb = rgb;
        this.brightRgb = brighten(rgb, 0.42f);
        this.glow = Math.max(0.0f, Math.min(3.0f, glow));
        this.yaw = yaw;
        this.skin = skin;
        this.slim = slim;

        for (int i = 0; i < n; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double horizontal = (0.58 + random.nextDouble() * 0.82) * speed;
            vx[i] = (float) (Math.cos(angle) * horizontal);
            vz[i] = (float) (Math.sin(angle) * horizontal);
            vy[i] = (float) ((0.22 + random.nextDouble() * 0.92) * speed * 1.8);
            delay[i] = random.nextFloat() * 0.055f;
            phase[i] = random.nextFloat() * (float) (Math.PI * 2.0);
            trail[i] = random.nextFloat();
            sampleTarget(i, random);
        }
    }

    @Override
    public void render(float partialTicks) {
        float life = progress(partialTicks);
        if (life >= 1.0f) {
            return;
        }

        float rise = smooth((life - RISE_START) / (1.0f - RISE_START));
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        drawRings(life, rise);
        EffectRenderer.beginSparks();
        float scatterTicks = Math.min(26.0f, Math.max(10.0f, durationTicks * SCATTER_END));
        float travelTicks = Math.min(scatterTicks, elapsed(partialTicks));
        for (int i = 0; i < vx.length; i++) {
            double originX = tx[i] * 0.42;
            double originY = ty[i] * 0.88;
            double originZ = tz[i] * 0.42;
            double scatterX = originX + vx[i] * scatterTicks;
            double scatterZ = originZ + vz[i] * scatterTicks;
            double scatterY = Math.max(0.025,
                    originY + vy[i] * scatterTicks - GRAVITY * scatterTicks * scatterTicks);
            double px;
            double py;
            double pz;
            float gather;

            if (life < SCATTER_END) {
                px = x + originX + vx[i] * travelTicks;
                pz = z + originZ + vz[i] * travelTicks;
                py = y + Math.max(0.025,
                        originY + vy[i] * travelTicks - GRAVITY * travelTicks * travelTicks);
                gather = 0.0f;
            }
            else {
                float bodyDelay = delay[i] + ty[i] * 0.028f;
                gather = smooth((life - SCATTER_END - bodyDelay)
                        / Math.max(0.08f, ASSEMBLE_END - SCATTER_END - bodyDelay));
                double swirl = Math.sin(gather * Math.PI) * (0.16 + trail[i] * 0.28);
                double angle = phase[i] + gather * Math.PI * (2.0 + trail[i] * 1.5);
                px = x + lerp(scatterX, tx[i], gather) + Math.cos(angle) * swirl;
                py = y + lerp(scatterY, ty[i], gather);
                pz = z + lerp(scatterZ, tz[i], gather) + Math.sin(angle) * swirl;
            }

            if (gather > 0.0f) {
                py += rise * (0.42 + trail[i] * 0.95);
            }

            float localDissolveStart = DISSOLVE_START + trail[i] * 0.105f;
            float localDissolve = smooth((life - localDissolveStart)
                    / Math.max(0.02f, 1.0f - localDissolveStart));
            float appear = smooth(life / 0.055f);
            float pulse = 0.78f + 0.22f * (float) Math.sin(phase[i] + life * 38.0f);
            float alpha = appear * pulse * (1.0f - localDissolve);
            double size = particleSize * (0.82 + trail[i] * 0.38)
                    * (1.0 + Math.sin(Math.PI * gather) * 0.28);
            int color = trail[i] > 0.68f ? brightRgb : rgb;

            if (glow > 0.0f) {
                EffectRenderer.spark(px, py, pz, size * (1.85 + glow * 0.72), color,
                        alpha * Math.min(0.46f, glow * 0.15f));
            }
            EffectRenderer.spark(px, py, pz, size, color, alpha);
        }
        EffectRenderer.endSparks();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);

        if (life >= FIGURE_START) {
            float figureIn = smooth((life - FIGURE_START) / (ASSEMBLE_END - FIGURE_START));
            float figureOut = 1.0f - smooth((life - DISSOLVE_START) / (1.0f - DISSOLVE_START));
            drawFigure(figureIn * figureOut * 0.78f, rise * 1.18f);
        }
    }

    private void drawRings(float life, float rise) {
        if (life < BURST_END) {
            float burst = smooth(life / BURST_END);
            double radius = 0.18 + burst * 2.45;
            EffectRenderer.groundRing(x, y + 0.018, z, Math.max(0.0, radius - 0.19), radius,
                    brightRgb, (1.0f - burst) * (0.48f + glow * 0.12f));
        }
        if (life >= SCATTER_END && life < ASSEMBLE_END + 0.05f) {
            float gather = smooth((life - SCATTER_END) / (ASSEMBLE_END - SCATTER_END));
            double radius = 2.45 - gather * 2.12;
            float alpha = (float) Math.sin(gather * Math.PI) * (0.52f + glow * 0.12f);
            EffectRenderer.groundRing(x, y + 0.024, z, Math.max(0.05, radius - 0.15), radius,
                    rgb, alpha);
        }
        if (life >= RISE_START && life < 0.93f) {
            float lift = smooth((life - RISE_START) / (0.93f - RISE_START));
            double radius = 0.28 + lift * 0.92;
            EffectRenderer.facingRing(x, y + 0.92 + rise * 0.9, z,
                    Math.max(0.05, radius - 0.13), radius, brightRgb,
                    (1.0f - lift) * (0.35f + glow * 0.10f));
        }
    }

    private void sampleTarget(int index, Random random) {
        double centerX;
        double centerY;
        double centerZ = 0.0;
        double halfX;
        double halfY;
        double halfZ;
        int part = random.nextInt(100);

        if (part < 19) {
            centerX = 0.0;
            centerY = 1.55;
            halfX = 0.25;
            halfY = 0.25;
            halfZ = 0.25;
        }
        else if (part < 49) {
            centerX = 0.0;
            centerY = 1.05;
            halfX = 0.25;
            halfY = 0.35;
            halfZ = 0.13;
        }
        else if (part < 73) {
            centerX = random.nextBoolean() ? 0.375 : -0.375;
            centerY = 1.05;
            halfX = 0.125;
            halfY = 0.38;
            halfZ = 0.125;
        }
        else {
            centerX = random.nextBoolean() ? 0.125 : -0.125;
            centerY = 0.35;
            halfX = 0.125;
            halfY = 0.35;
            halfZ = 0.125;
        }

        double localX = centerX + (random.nextDouble() * 2.0 - 1.0) * halfX;
        double localY = centerY + (random.nextDouble() * 2.0 - 1.0) * halfY;
        double localZ = centerZ + (random.nextDouble() * 2.0 - 1.0) * halfZ;
        double rotation = Math.toRadians(-yaw);
        double sin = Math.sin(rotation);
        double cos = Math.cos(rotation);
        tx[index] = (float) (localX * cos - localZ * sin);
        ty[index] = (float) localY;
        tz[index] = (float) (localX * sin + localZ * cos);
    }

    private void drawFigure(float alpha, float lift) {
        if (alpha <= 0.003f) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ModelPlayer model = model(slim);
        prepareModel(model);
        float red = ((rgb >> 16) & 0xFF) / 255.0f;
        float green = ((rgb >> 8) & 0xFF) / 255.0f;
        float blue = (rgb & 0xFF) / 255.0f;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01f);
        GlStateManager.enableCull();
        GlStateManager.translate(x, y + 1.5 + lift, z);
        GlStateManager.rotate(-yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        GlStateManager.scale(-1.0f, -1.0f, 1.0f);

        if (glow > 0.0f) {
            GlStateManager.disableTexture2D();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            int shells = Math.max(1, Math.min(6, (int) Math.ceil(glow * 2.0f)));
            for (int i = shells; i >= 1; i--) {
                float shell = 1.0f + i * (0.008f + glow * 0.0035f);
                float shellAlpha = alpha * glow * 0.075f / (float) Math.sqrt(i);
                GlStateManager.pushMatrix();
                GlStateManager.scale(shell, shell, shell);
                GlStateManager.color(red, green, blue, shellAlpha);
                renderModel(model);
                GlStateManager.popMatrix();
            }
        }

        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        if (skin != null && mc.getTextureManager() != null) {
            GlStateManager.enableTexture2D();
            mc.getTextureManager().bindTexture(skin);
        }
        else {
            GlStateManager.disableTexture2D();
        }
        GlStateManager.color(red, green, blue, alpha);
        renderModel(model);

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
    }

    private static void prepareModel(ModelPlayer model) {
        model.isChild = false;
        model.bipedHead.rotateAngleX = 0.0f;
        model.bipedHead.rotateAngleY = 0.0f;
        model.bipedRightArm.rotateAngleX = 0.0f;
        model.bipedLeftArm.rotateAngleX = 0.0f;
        model.bipedRightLeg.rotateAngleX = 0.0f;
        model.bipedLeftLeg.rotateAngleX = 0.0f;
        model.bipedRightArm.rotateAngleZ = 0.10f;
        model.bipedLeftArm.rotateAngleZ = -0.10f;
    }

    private static void renderModel(ModelPlayer model) {
        float scale = 0.0625f;
        model.bipedHead.render(scale);
        model.bipedHeadwear.render(scale);
        model.bipedBody.render(scale);
        model.bipedRightArm.render(scale);
        model.bipedLeftArm.render(scale);
        model.bipedRightLeg.render(scale);
        model.bipedLeftLeg.render(scale);
    }

    private static ModelPlayer model(boolean slim) {
        if (slim) {
            if (slimModel == null) {
                slimModel = new ModelPlayer(0.0f, true);
            }
            return slimModel;
        }
        if (classicModel == null) {
            classicModel = new ModelPlayer(0.0f, false);
        }
        return classicModel;
    }

    private static float smooth(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    private static double lerp(double from, double to, float amount) {
        return from + (to - from) * amount;
    }

    private static int brighten(int color, float amount) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        red += Math.round((255 - red) * amount);
        green += Math.round((255 - green) * amount);
        blue += Math.round((255 - blue) * amount);
        return red << 16 | green << 8 | blue;
    }
}
