package mindless.effect.impl;

import mindless.effect.Effect;
import mindless.effect.EffectRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Random;

/**
 * Orbs scatter, gather, and the player comes back.
 *
 * Three phases over one lifetime: the orbs are thrown outward and fall, then they are pulled back
 * toward the body, then the skin fades in as they arrive. Each orb keeps its own target on the
 * silhouette, so they land spread over the model rather than all at its navel.
 *
 * The skin is captured when the kill happens, not when the model is drawn. By the time the figure
 * appears the entity is long gone from the world, and asking for its texture then gets nothing.
 */
public final class ReassembleEffect extends Effect {

    private static final double GRAVITY = 0.035;
    private static final float SCATTER_END = 0.42f;
    private static final float FIGURE_START = 0.66f;

    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final float[] tx;
    private final float[] ty;
    private final float[] tz;
    private final double orbSize;
    private final int rgb;
    private final float yaw;
    private final ResourceLocation skin;
    private final boolean slim;

    private static ModelPlayer classicModel;
    private static ModelPlayer slimModel;

    public ReassembleEffect(double x, double y, double z, int durationTicks,
                            int count, double speed, double orbSize, int rgb,
                            float yaw, ResourceLocation skin, boolean slim, Random random) {
        super(x, y, z, durationTicks);
        int n = Math.max(1, Math.min(160, count));
        this.vx = new float[n];
        this.vy = new float[n];
        this.vz = new float[n];
        this.tx = new float[n];
        this.ty = new float[n];
        this.tz = new float[n];
        this.orbSize = orbSize;
        this.rgb = rgb;
        this.yaw = yaw;
        this.skin = skin;
        this.slim = slim;

        for (int i = 0; i < n; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double horizontal = (0.4 + random.nextDouble() * 0.6) * speed;
            vx[i] = (float) (Math.cos(angle) * horizontal);
            vz[i] = (float) (Math.sin(angle) * horizontal);
            vy[i] = (float) ((0.5 + random.nextDouble() * 0.5) * speed * 1.6);

            // Targets are spread over a body-sized column so the gather reads as a shape filling
            // in rather than as a point sucking everything into it.
            double spin = random.nextDouble() * Math.PI * 2.0;
            double radius = 0.12 + random.nextDouble() * 0.22;
            tx[i] = (float) (Math.cos(spin) * radius);
            tz[i] = (float) (Math.sin(spin) * radius);
            ty[i] = (float) (0.15 + random.nextDouble() * 1.6);
        }
    }

    @Override
    public void render(float partialTicks) {
        float life = progress(partialTicks);
        if (life >= 1.0f) {
            return;
        }
        float t = elapsed(partialTicks);

        float gather = life <= SCATTER_END ? 0.0f
                : Math.min(1.0f, (life - SCATTER_END) / (FIGURE_START - SCATTER_END));
        float ease = 1.0f - (1.0f - gather) * (1.0f - gather) * (1.0f - gather);

        EffectRenderer.beginSparks();
        for (int i = 0; i < vx.length; i++) {
            double px = x + vx[i] * t;
            double pz = z + vz[i] * t;
            double py = y + vy[i] * t - GRAVITY * t * t;
            if (py < y) {
                py = y;
            }
            if (ease > 0.0f) {
                px += (x + tx[i] - px) * ease;
                py += (y + ty[i] - py) * ease;
                pz += (z + tz[i] - pz) * ease;
            }
            // Orbs dim as the body they are becoming takes over, so the two never both read as
            // solid at the same moment.
            float alpha = life < SCATTER_END ? 1.0f : Math.max(0.0f, 1.0f - ease * 1.15f);
            EffectRenderer.spark(px, py, pz, orbSize, rgb, alpha);
        }
        EffectRenderer.endSparks();

        if (life > FIGURE_START && skin != null) {
            float in = (life - FIGURE_START) / (1.0f - FIGURE_START);
            // Solid by the two thirds mark and then held, rather than fading straight back out:
            // the point of the effect is the moment it finishes.
            drawFigure(Math.min(1.0f, in * 1.6f));
        }
    }

    /**
     * The skin, drawn in world space.
     *
     * The effect pass runs with texturing and the alpha test off, which is exactly wrong for a
     * textured model, so the state is set up and put back here rather than widened for everyone.
     */
    private void drawFigure(float alpha) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getTextureManager() == null) {
            return;
        }

        ModelPlayer model = model(slim);
        model.isChild = false;
        model.bipedHead.rotateAngleX = 0.0f;
        model.bipedHead.rotateAngleY = 0.0f;
        model.bipedRightArm.rotateAngleX = 0.0f;
        model.bipedLeftArm.rotateAngleX = 0.0f;
        model.bipedRightLeg.rotateAngleX = 0.0f;
        model.bipedLeftLeg.rotateAngleX = 0.0f;
        model.bipedRightArm.rotateAngleZ = 0.08f;
        model.bipedLeftArm.rotateAngleZ = -0.08f;

        GlStateManager.pushMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.02f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableCull();
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);

        mc.getTextureManager().bindTexture(skin);

        // The model is built upside down and head-first about its own origin, which is why every
        // renderer in the game flips it and lifts it by a body's height before drawing.
        GlStateManager.translate(x, y + 1.5, z);
        GlStateManager.rotate(-yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        GlStateManager.scale(-1.0f, -1.0f, 1.0f);

        float scale = 0.0625f;
        model.bipedHead.render(scale);
        model.bipedHeadwear.render(scale);
        model.bipedBody.render(scale);
        model.bipedRightArm.render(scale);
        model.bipedLeftArm.render(scale);
        model.bipedRightLeg.render(scale);
        model.bipedLeftLeg.render(scale);

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();

        // Back to what the effect pass expects, or every effect queued behind this one is drawn
        // through a bound skin with the alpha test running.
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
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
}
