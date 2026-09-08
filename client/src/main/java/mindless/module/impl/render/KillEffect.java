package mindless.module.impl.render;

import mindless.event.AttackEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Something to look at when a player dies.
 *
 * Purely cosmetic, and built to stay that way: effects are pooled, drawn in one batch, and expire
 * on a timer, so a busy fight cannot turn into a slideshow. Nothing here touches the player, the
 * world or a packet.
 *
 * The trigger is deathTime crossing one, which happens on the client for anyone in render
 * distance, rather than a death message -- so it works in any gamemode and does not depend on
 * parsing chat.
 */
public class KillEffect extends Module {
    private static final String[] MODES = new String[]{"Blood", "Lightning", "Soul"};
    private static final int MODE_BLOOD = 0;
    private static final int MODE_LIGHTNING = 1;
    private static final int MODE_SOUL = 2;

    private static final int MAX_EFFECTS = 6;
    private static final int PARTICLES_PER_EFFECT = 28;

    private final SliderSetting mode;
    private final SliderSetting duration;
    private final SliderSetting size;
    private final ButtonSetting onlyOwnKills;
    private final ButtonSetting useCustomColor;
    private final ColorSetting customColor;

    private final Random random = new Random();
    private final List<Effect> effects = new ArrayList<Effect>();
    /** Entities already given an effect, so one death does not spawn one per frame. */
    private final Set<Integer> handled = new HashSet<Integer>();
    /** Entities we hit recently, for the own-kills filter. */
    private final Set<Integer> attacked = new HashSet<Integer>();

    public KillEffect() {
        super("Kill Effect", "Plays an effect where a player dies.", category.render, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_BLOOD, MODES));
        this.registerSetting(duration = new SliderSetting("Duration", "s", 1.2, 0.3, 4.0, 0.1));
        this.registerSetting(size = new SliderSetting("Size", 1.0, 0.3, 3.0, 0.05));
        this.registerSetting(onlyOwnKills = new ButtonSetting("Only your kills", false));
        this.registerSetting(useCustomColor = new ButtonSetting("Custom color", false));
        this.registerSetting(customColor = new ColorSetting("Color", 220, 40, 40, 255));
    }

    @Override
    public void guiUpdate() {
        if (customColor != null) {
            customColor.setVisible(useCustomColor != null && useCustomColor.isToggled(), this);
        }
    }

    @Override
    public void onDisable() {
        effects.clear();
        handled.clear();
        attacked.clear();
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()].toLowerCase();
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        if (event.target != null) {
            attacked.add(event.target.getEntityId());
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        spawnForDeaths();
        drawEffects();
    }

    /** Watch for players entering their death animation. */
    private void spawnForDeaths() {
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) entity;
            int id = player.getEntityId();
            if (player.deathTime <= 0 || player.deathTime > 2) {
                if (player.deathTime == 0) {
                    handled.remove(id);
                }
                continue;
            }
            if (!handled.add(id)) {
                continue;
            }
            if (onlyOwnKills.isToggled() && !attacked.contains(id)) {
                continue;
            }
            attacked.remove(id);
            spawn(player.posX, player.posY, player.posZ);
        }
    }

    private void spawn(double x, double y, double z) {
        if (effects.size() >= MAX_EFFECTS) {
            effects.remove(0);
        }
        effects.add(new Effect(x, y, z, (int) mode.getInput(),
                (float) duration.getInput() * 1000.0f, (float) size.getInput(), random));
    }

    private void drawEffects() {
        if (effects.isEmpty()) {
            return;
        }

        double viewX = mc.getRenderManager().viewerPosX;
        double viewY = mc.getRenderManager().viewerPosY;
        double viewZ = mc.getRenderManager().viewerPosZ;
        long now = System.currentTimeMillis();
        int rgb = useCustomColor.isToggled() ? (customColor.getRGB() & 0xFFFFFF) : -1;

        GlStateManager.pushMatrix();
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        Iterator<Effect> iterator = effects.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            if (!effect.draw(worldRenderer, now, viewX, viewY, viewZ, rgb)) {
                iterator.remove();
            }
        }

        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** One burst. All three modes are the same particles under different starting conditions. */
    private static final class Effect {
        private final double x;
        private final double y;
        private final double z;
        private final int style;
        private final float lifeMs;
        private final float scale;
        private final long born;
        private final float[] dirX = new float[PARTICLES_PER_EFFECT];
        private final float[] dirY = new float[PARTICLES_PER_EFFECT];
        private final float[] dirZ = new float[PARTICLES_PER_EFFECT];
        private final float[] speed = new float[PARTICLES_PER_EFFECT];
        private final int[] tint = new int[PARTICLES_PER_EFFECT];

        Effect(double x, double y, double z, int style, float lifeMs, float scale, Random random) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.style = style;
            this.lifeMs = Math.max(100.0f, lifeMs);
            this.scale = scale;
            this.born = System.currentTimeMillis();

            for (int i = 0; i < PARTICLES_PER_EFFECT; i++) {
                double theta = random.nextDouble() * Math.PI * 2.0;
                double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
                dirX[i] = (float) (Math.sin(phi) * Math.cos(theta));
                dirZ[i] = (float) (Math.sin(phi) * Math.sin(theta));
                dirY[i] = (float) Math.cos(phi);

                switch (style) {
                    case MODE_LIGHTNING:
                        // Squashed flat and thrown hard: a ring rather than a sphere.
                        dirY[i] *= 0.15f;
                        speed[i] = 2.4f + random.nextFloat() * 2.0f;
                        tint[i] = 0x99CCFF;
                        break;
                    case MODE_SOUL:
                        // Slow, always upward, wandering.
                        dirY[i] = 0.6f + random.nextFloat() * 0.5f;
                        dirX[i] *= 0.35f;
                        dirZ[i] *= 0.35f;
                        speed[i] = 0.5f + random.nextFloat() * 0.4f;
                        tint[i] = 0xCFE8FF;
                        break;
                    case MODE_BLOOD:
                    default:
                        speed[i] = 1.2f + random.nextFloat() * 1.6f;
                        tint[i] = random.nextFloat() < 0.3f ? 0xFF5555 : 0xAA0000;
                        break;
                }
            }
        }

        /** @return false once it has expired and should be dropped. */
        boolean draw(WorldRenderer worldRenderer, long now, double viewX, double viewY, double viewZ, int forced) {
            float age = (now - born) / lifeMs;
            if (age >= 1.0f) {
                return false;
            }
            float fade = 1.0f - age;
            float gravity = style == MODE_SOUL ? -0.15f : 1.6f;

            for (int i = 0; i < PARTICLES_PER_EFFECT; i++) {
                float travel = speed[i] * age * scale;
                double px = x + dirX[i] * travel;
                double py = y + 1.0 + dirY[i] * travel - gravity * age * age * scale;
                double pz = z + dirZ[i] * travel;

                float radius = 0.055f * scale * (style == MODE_LIGHTNING ? 1.0f + age : 1.0f - age * 0.4f);
                int rgb = forced >= 0 ? forced : tint[i];
                int alpha = Math.round(255 * fade * fade);
                if (alpha <= 2) {
                    continue;
                }
                quad(worldRenderer, px - viewX, py - viewY, pz - viewZ, radius, rgb, alpha);
            }
            return true;
        }

        /**
         * A camera-facing square.
         *
         * Built on the view matrix's own right and up vectors rather than on world axes, so it
         * stays square to the eye from any angle without a per-particle rotation.
         */
        private static void quad(WorldRenderer worldRenderer, double x, double y, double z,
                                 float radius, int rgb, int alpha) {
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;

            net.minecraft.client.renderer.entity.RenderManager manager =
                    net.minecraft.client.Minecraft.getMinecraft().getRenderManager();
            // Billboard basis from the render manager's rotation, which is the camera's.
            float yaw = manager.playerViewY;
            float pitch = manager.playerViewX;
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);

            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);
            double upX = -Math.sin(yawRad) * Math.sin(pitchRad);
            double upY = Math.cos(pitchRad);
            double upZ = Math.cos(yawRad) * Math.sin(pitchRad);

            for (int corner = 0; corner < 6; corner++) {
                // Two triangles: 0,1,2 and 0,2,3.
                int index = corner < 3 ? corner : (corner == 3 ? 0 : corner - 1);
                double sx = (index == 0 || index == 3) ? -1 : 1;
                double sy = (index == 0 || index == 1) ? -1 : 1;
                worldRenderer
                        .pos(x + (rightX * sx + upX * sy) * radius,
                             y + (upY * sy) * radius,
                             z + (rightZ * sx + upZ * sy) * radius)
                        .color(red, green, blue, alpha)
                        .endVertex();
            }
        }
    }
}
