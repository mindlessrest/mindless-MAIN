package mindless.module.impl.world;

import mindless.module.Module;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class Particles extends Module {
    public SliderSetting mode;
    public SliderSetting count;
    public SliderSetting size;
    public ColorSetting color;

    private ResourceLocation snowTex;
    private ResourceLocation heartTex;
    private ResourceLocation starTex;

    private final List<AmbientParticle> particles = new ArrayList<>();
    private final Random random = new Random();
    private final BlockPos.MutableBlockPos collisionPos = new BlockPos.MutableBlockPos();
    private int lastMode = -1;
    private double windAngle;

    public Particles() {
        super("Particles", category.world);
        this.registerSetting(mode = new SliderSetting("Mode", 0, new String[]{"Rain", "Snow", "Hearts", "Stars"}));
        this.registerSetting(count = new SliderSetting("Count", 200, 10, 1000, 10));
        this.registerSetting(size = new SliderSetting("Size", 0.5, 0.1, 2.0, 0.05));
        this.registerSetting(color = new ColorSetting("Color", 255, 255, 255, 200));
    }

    @Override
    public void onEnable() {
        loadTextures();
        particles.clear();
        lastMode = -1;
    }

    @Override
    public void onDisable() {
        particles.clear();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) return;

        int currentMode = (int) mode.getInput();
        if (currentMode != lastMode) {
            particles.clear();
            lastMode = currentMode;
        }

        int maxCount = (int) count.getInput();

        while (particles.size() > maxCount) {
            particles.remove(0);
        }

        int batch = Math.max(1, maxCount / 15);
        int toSpawn = Math.min(batch, maxCount - particles.size());
        for (int i = 0; i < toSpawn; i++) {
            AmbientParticle p = createParticle(currentMode);
            if (p != null) {
                particles.add(p);
            }
        }

        windAngle += 0.005;

        Iterator<AmbientParticle> it = particles.iterator();
        while (it.hasNext()) {
            AmbientParticle p = it.next();
            p.prevX = p.x;
            p.prevY = p.y;
            p.prevZ = p.z;
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.age++;
            p.rotation += p.rotSpeed;

            collisionPos.set(MathHelper.floor_double(p.x), MathHelper.floor_double(p.y), MathHelper.floor_double(p.z));
            Block block = mc.theWorld.getBlockState(collisionPos).getBlock();
            Material mat = block.getMaterial();
            if (mat != Material.air && mat != Material.glass && mat != Material.water) {
                p.collided = true;
            }

            if (p.age >= p.maxAge || p.collided) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || particles.isEmpty()) return;

        int currentMode = (int) mode.getInput();
        boolean isRain = currentMode == 0;
        int argb = color.getColor();
        float colorAlpha = ((argb >> 24) & 0xFF) / 255.0f;
        float cr = ((argb >> 16) & 0xFF) / 255.0f;
        float cg = ((argb >> 8) & 0xFF) / 255.0f;
        float cb = (argb & 0xFF) / 255.0f;
        float sz = (float) size.getInput();
        float partialTicks = e.partialTicks;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();

        if (isRain) {
            GlStateManager.disableAlpha();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        } else {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            ResourceLocation tex = getTex(currentMode);
            mc.getTextureManager().bindTexture(tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        if (isRain) {
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        } else {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        }

        // Match the exact transforms used by the original per-particle path:
        // rotate(-playerViewY, Y), then rotate(playerViewX, X).
        float yaw = (float) Math.toRadians(mc.getRenderManager().playerViewY);
        float pitch = (float) Math.toRadians(mc.getRenderManager().playerViewX);
        float sinYaw = MathHelper.sin(yaw);
        float cosYaw = MathHelper.cos(yaw);
        float sinPitch = MathHelper.sin(pitch);
        float cosPitch = MathHelper.cos(pitch);
        float cameraRightX = cosYaw;
        float cameraRightZ = sinYaw;
        float cameraUpX = -sinYaw * sinPitch;
        float cameraUpY = cosPitch;
        float cameraUpZ = cosYaw * sinPitch;

        for (AmbientParticle p : particles) {
            float lifeRatio = 1.0f - (float) p.age / p.maxAge;
            if (lifeRatio <= 0.0f) continue;

            float alpha = lifeRatio * colorAlpha;
            if (alpha <= 0.01f) continue;

            double ix = p.prevX + (p.x - p.prevX) * partialTicks - mc.getRenderManager().viewerPosX;
            double iy = p.prevY + (p.y - p.prevY) * partialTicks - mc.getRenderManager().viewerPosY;
            double iz = p.prevZ + (p.z - p.prevZ) * partialTicks - mc.getRenderManager().viewerPosZ;

            if (isRain) {
                appendRainLines(buffer, ix, iy, iz, sz, alpha, cr, cg, cb);
            } else {
                float half = sz * (0.55f + 0.25f * lifeRatio);
                float rotation = (currentMode == 2 || currentMode == 3)
                        ? (float) Math.toRadians(p.rotation) : 0.0F;
                float cos = MathHelper.cos(rotation);
                float sin = MathHelper.sin(rotation);
                appendBillboardVertex(buffer, ix, iy, iz, -half, -half, cos, sin,
                        cameraRightX, cameraRightZ, cameraUpX, cameraUpY, cameraUpZ,
                        0.0, 0.0, cr, cg, cb, alpha);
                appendBillboardVertex(buffer, ix, iy, iz, half, -half, cos, sin,
                        cameraRightX, cameraRightZ, cameraUpX, cameraUpY, cameraUpZ,
                        1.0, 0.0, cr, cg, cb, alpha);
                appendBillboardVertex(buffer, ix, iy, iz, half, half, cos, sin,
                        cameraRightX, cameraRightZ, cameraUpX, cameraUpY, cameraUpZ,
                        1.0, 1.0, cr, cg, cb, alpha);
                appendBillboardVertex(buffer, ix, iy, iz, -half, half, cos, sin,
                        cameraRightX, cameraRightZ, cameraUpX, cameraUpY, cameraUpZ,
                        0.0, 1.0, cr, cg, cb, alpha);
            }
        }

        tessellator.draw();

        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void appendBillboardVertex(WorldRenderer buffer, double x, double y, double z,
                                       float localX, float localY, float cos, float sin,
                                       float rightX, float rightZ, float upX, float upY, float upZ,
                                       double u, double v, float r, float g, float b, float a) {
        float rotatedX = localX * cos - localY * sin;
        float rotatedY = localX * sin + localY * cos;
        buffer.pos(x + rightX * rotatedX + upX * rotatedY,
                        y + upY * rotatedY,
                        z + rightZ * rotatedX + upZ * rotatedY)
                .tex(u, v).color(r, g, b, a).endVertex();
    }

    private void appendRainLines(WorldRenderer buffer, double x, double y, double z,
                                 float size, float alpha, float r, float g, float b) {
        float len = size * 2.5f;
        float topY = (float) y;
        float botY = (float) y - len;
        float sp = size * 0.02f;

        appendLine(buffer, x - sp * 5, topY + len * 0.3, z,
                x - sp * 5, botY + len * 0.1, z, r, g, b, alpha * 0.06f);
        appendLine(buffer, x - sp * 2.5, topY + len * 0.15, z,
                x - sp * 2.5, botY, z, r, g, b, alpha * 0.18f);
        appendLine(buffer, x, topY + len * 0.05, z,
                x, botY - len * 0.05, z, r, g, b, alpha * 0.5f);
        appendLine(buffer, x + sp, topY, z,
                x + sp, botY - len * 0.1, z, r, g, b, alpha);
    }

    private void appendLine(WorldRenderer buffer, double x1, double y1, double z1,
                            double x2, double y2, double z2,
                            float r, float g, float b, float a) {
        buffer.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.pos(x2, y2, z2).color(r, g, b, a).endVertex();
    }

    private AmbientParticle createParticle(int m) {
        double px, py, pz, vx, vy, vz;
        int maxAge;
        double radius = m == 0 ? 60.0 : 32.0;

        switch (m) {
            case 0:
                px = mc.thePlayer.posX + (random.nextDouble() - 0.5) * radius * 2.0;
                py = mc.thePlayer.posY + 6.0 + random.nextDouble() * 30.0;
                pz = mc.thePlayer.posZ + (random.nextDouble() - 0.5) * radius * 2.0;
                vx = 0.0;
                vy = -1.5 - random.nextDouble() * 1.0;
                vz = 0.0;
                maxAge = 20 + random.nextInt(25);
                break;
            case 1:
                double wx = Math.cos(windAngle + random.nextDouble() * 0.5);
                double wz = Math.sin(windAngle + random.nextDouble() * 0.5);
                px = mc.thePlayer.posX + (random.nextDouble() - 0.5) * radius * 2.0;
                py = mc.thePlayer.posY + 8.0 + random.nextDouble() * 30.0;
                pz = mc.thePlayer.posZ + (random.nextDouble() - 0.5) * radius * 2.0;
                vx = wx * (0.02 + random.nextDouble() * 0.06);
                vy = -0.05 - random.nextDouble() * 0.08;
                vz = wz * (0.02 + random.nextDouble() * 0.06);
                maxAge = 200 + random.nextInt(250);
                break;
            case 2:
                px = mc.thePlayer.posX + (random.nextDouble() - 0.5) * radius * 1.6;
                py = mc.thePlayer.posY + random.nextDouble() * 20.0;
                pz = mc.thePlayer.posZ + (random.nextDouble() - 0.5) * radius * 1.6;
                vx = (random.nextDouble() - 0.5) * 0.05;
                vy = (random.nextDouble() - 0.5) * 0.04;
                vz = (random.nextDouble() - 0.5) * 0.05;
                maxAge = 100 + random.nextInt(100);
                break;
            case 3:
                px = mc.thePlayer.posX + (random.nextDouble() - 0.5) * radius * 1.6;
                py = mc.thePlayer.posY + random.nextDouble() * 20.0;
                pz = mc.thePlayer.posZ + (random.nextDouble() - 0.5) * radius * 1.6;
                vx = (random.nextDouble() - 0.5) * 0.04;
                vy = (random.nextDouble() - 0.5) * 0.04;
                vz = (random.nextDouble() - 0.5) * 0.04;
                maxAge = 100 + random.nextInt(100);
                break;
            default:
                return null;
        }

        collisionPos.set(MathHelper.floor_double(px), MathHelper.floor_double(py), MathHelper.floor_double(pz));
        Block spawnBlock = mc.theWorld.getBlockState(collisionPos).getBlock();
        Material spawnMat = spawnBlock.getMaterial();
        if (spawnMat != Material.air && spawnMat != Material.glass && spawnMat != Material.water) {
            return null;
        }

        AmbientParticle p = new AmbientParticle();
        p.x = p.prevX = px;
        p.y = p.prevY = py;
        p.z = p.prevZ = pz;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.maxAge = maxAge;
        p.age = 0;
        p.rotation = random.nextFloat() * 360.0f;
        p.rotSpeed = (random.nextFloat() - 0.5f) * 3.0f;
        return p;
    }

    private ResourceLocation getTex(int m) {
        switch (m) {
            case 1: return snowTex;
            case 2: return heartTex;
            case 3: return starTex;
            default: return null;
        }
    }

    private void loadTextures() {
        if (snowTex == null) snowTex = loadTintMask("snowflake.png", "snow");
        if (heartTex == null) heartTex = loadTintMask("heart.png", "heart");
        if (starTex == null) starTex = loadTintMask("star.png", "star");
    }

    /** Converts baked black backgrounds to transparency and white art to a tintable alpha mask. */
    private ResourceLocation loadTintMask(String fileName, String name) {
        String path = "/assets/mindless/textures/particles/" + fileName;
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream == null) return new ResourceLocation("mindless", "textures/particles/" + fileName);
            BufferedImage source = ImageIO.read(stream);
            BufferedImage mask = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int pixel = source.getRGB(x, y);
                    int sourceAlpha = (pixel >>> 24) & 0xFF;
                    int red = (pixel >> 16) & 0xFF;
                    int green = (pixel >> 8) & 0xFF;
                    int blue = pixel & 0xFF;
                    int luminance = Math.max(red, Math.max(green, blue));
                    int alpha = luminance < 8 ? 0 : sourceAlpha * luminance / 255;
                    mask.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
                }
            }
            return mc.getTextureManager().getDynamicTextureLocation(
                    "raven_particle_mask_" + name, new DynamicTexture(mask));
        } catch (Exception ignored) {
            return new ResourceLocation("mindless", "textures/particles/" + fileName);
        }
    }

    @Override
    public String getInfo() {
        return mode.getOptions()[(int) mode.getInput()];
    }

    private static class AmbientParticle {
        double x, y, z;
        double prevX, prevY, prevZ;
        double vx, vy, vz;
        int age, maxAge;
        float rotation, rotSpeed;
        boolean collided;
    }
}
