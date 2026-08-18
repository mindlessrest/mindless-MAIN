package mindless.runtime;

import mindless.module.impl.world.Weather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.util.Random;

public final class NebulaSkyRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int textureSize = 256;
    private static final ResourceLocation[] faces = new ResourceLocation[6];
    private static int textureSignature;

    private NebulaSkyRenderer() {}

    public static void render(Weather atmosphere) {
        if (atmosphere == null || !atmosphere.nebulaSky.isToggled() || mc.theWorld == null) return;
        ensureTextures(atmosphere);

        float size = Math.max(48.0F, mc.gameSettings.renderDistanceChunks * 7.0F);
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDepthRange(1.0, 1.0);
        GL11.glDepthFunc(GL11.GL_EQUAL);
        try {
            drawFace(0, -size, -size, -size, size, -size, -size,
                    size, size, -size, -size, size, -size);
            drawFace(1, size, -size, size, -size, -size, size,
                    -size, size, size, size, size, size);
            drawFace(2, -size, -size, size, -size, -size, -size,
                    -size, size, -size, -size, size, size);
            drawFace(3, size, -size, -size, size, -size, size,
                    size, size, size, size, size, -size);
            drawFace(4, -size, size, -size, size, size, -size,
                    size, size, size, -size, size, size);
            drawFace(5, -size, -size, size, size, -size, size,
                    size, -size, -size, -size, -size, -size);
        } finally {
            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    private static void drawFace(int index,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4) {
        mc.getTextureManager().bindTexture(faces[index]);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F); GL11.glVertex3f(x1, y1, z1);
        GL11.glTexCoord2f(1.0F, 0.0F); GL11.glVertex3f(x2, y2, z2);
        GL11.glTexCoord2f(1.0F, 1.0F); GL11.glVertex3f(x3, y3, z3);
        GL11.glTexCoord2f(0.0F, 1.0F); GL11.glVertex3f(x4, y4, z4);
        GL11.glEnd();
    }

    private static void ensureTextures(Weather atmosphere) {
        int signature = atmosphere.nebulaColor.getColor();
        signature = 31 * signature + atmosphere.nebulaColor2.getColor();
        signature = 31 * signature + (int) atmosphere.nebulaStars.getInput();
        signature = 31 * signature + (int) Math.round(atmosphere.nebulaBrightness.getInput() * 100.0);
        if (signature == textureSignature && faces[0] != null) return;
        release();
        textureSignature = signature;
        for (int face = 0; face < faces.length; face++) {
            BufferedImage image = createFace(atmosphere, face);
            DynamicTexture texture = new DynamicTexture(image);
            faces[face] = mc.getTextureManager().getDynamicTextureLocation(
                    "mindless_nebula_" + face, texture);
        }
    }

    private static BufferedImage createFace(Weather atmosphere, int face) {
        BufferedImage image = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
        int first = atmosphere.nebulaColor.getColor();
        int second = atmosphere.nebulaColor2.getColor();
        float brightness = (float) atmosphere.nebulaBrightness.getInput();
        Random stars = new Random(0x4D494E444C455353L + face * 7919L);

        for (int y = 0; y < textureSize; y++) {
            for (int x = 0; x < textureSize; x++) {
                float nx = x / (float) textureSize;
                float ny = y / (float) textureSize;
                float cloud = fractalNoise(nx * 4.0F + face * 2.7F, ny * 4.0F - face * 1.9F, face);
                float band = (float) Math.exp(-Math.pow((ny - 0.48F + (float) Math.sin(nx * 5.2F + face) * 0.14F) * 4.2F, 2.0));
                float mix = clamp(cloud * 0.72F + band * 0.42F);
                int red = channel(first, 16, second, mix);
                int green = channel(first, 8, second, mix);
                int blue = channel(first, 0, second, mix);
                float shade = brightness * (0.20F + cloud * 0.48F + band * 0.32F);
                red = clampColor(red * shade);
                green = clampColor(green * shade);
                blue = clampColor(blue * shade);
                image.setRGB(x, y, 0xFF000000 | red << 16 | green << 8 | blue);
            }
        }

        int count = (int) atmosphere.nebulaStars.getInput();
        for (int i = 0; i < count; i++) {
            int x = stars.nextInt(textureSize);
            int y = stars.nextInt(textureSize);
            int value = 155 + stars.nextInt(101);
            int tint = stars.nextBoolean() ? 0xD9E9FF : 0xE8DDFF;
            int star = scaleColor(tint, value / 255.0F);
            image.setRGB(x, y, 0xFF000000 | star);
            if (value > 225 && x + 1 < textureSize) image.setRGB(x + 1, y, 0xFF000000 | scaleColor(star, 0.48F));
            if (value > 235 && y + 1 < textureSize) image.setRGB(x, y + 1, 0xFF000000 | scaleColor(star, 0.42F));
        }
        return image;
    }

    private static float fractalNoise(float x, float y, int seed) {
        float value = 0.0F;
        float amplitude = 0.58F;
        float total = 0.0F;
        for (int octave = 0; octave < 4; octave++) {
            value += smoothNoise(x, y, seed + octave * 17) * amplitude;
            total += amplitude;
            x *= 2.03F;
            y *= 2.03F;
            amplitude *= 0.48F;
        }
        return value / total;
    }

    private static float smoothNoise(float x, float y, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float tx = x - x0;
        float ty = y - y0;
        tx = tx * tx * (3.0F - 2.0F * tx);
        ty = ty * ty * (3.0F - 2.0F * ty);
        float a = hash(x0, y0, seed);
        float b = hash(x0 + 1, y0, seed);
        float c = hash(x0, y0 + 1, seed);
        float d = hash(x0 + 1, y0 + 1, seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    private static float hash(int x, int y, int seed) {
        int value = x * 374761393 + y * 668265263 + seed * 1442695041;
        value = (value ^ value >>> 13) * 1274126177;
        return ((value ^ value >>> 16) & 0x7FFFFFFF) / 2147483647.0F;
    }

    private static int channel(int first, int shift, int second, float amount) {
        int a = first >> shift & 255;
        int b = second >> shift & 255;
        return Math.round(a + (b - a) * amount);
    }

    private static int scaleColor(int color, float scale) {
        return clampColor((color >> 16 & 255) * scale) << 16
                | clampColor((color >> 8 & 255) * scale) << 8
                | clampColor((color & 255) * scale);
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    public static void release() {
        for (int i = 0; i < faces.length; i++) {
            if (faces[i] != null) mc.getTextureManager().deleteTexture(faces[i]);
            faces[i] = null;
        }
        textureSignature = 0;
    }
}
