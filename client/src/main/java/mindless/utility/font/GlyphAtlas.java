package mindless.utility.font;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
public final class GlyphAtlas {
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int PADDING = 2;
    /**
     * Mip levels kept on a mipmapped atlas, and the gutter that keeps them clean.
     *
     * Each level halves the gutter between glyphs, so three levels need eight pixels of it or the
     * smallest levels start averaging neighbouring letters into each other.
     */
    private static final int MIP_LEVELS = 3;
    private static final int MIP_PADDING = 1 << MIP_LEVELS;
    private static final int MIN_PAGE_SIZE = 64;
private static final int MAX_PAGE_SIZE = 1024;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int CHANNEL_MASK = 0xFF;
public static final class Region {
        public final int textureId;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;

        private Region(int textureId, float u0, float v0, float u1, float v1) {
            this.textureId = textureId;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    private final int pageWidth;
    private final int pageHeight;
    private final boolean mipmapped;
    private final int padding;
    private final List<Page> pages = new ArrayList<Page>();
    private final int[] slot = new int[2];
    private boolean finished;
public GlyphAtlas(int[] pageSize) {
        this(pageSize, false);
    }

    /**
     * An atlas that can be sampled well below its native size.
     *
     * Text drawn in the world shrinks with distance. Sampled through a single linear level, a glyph
     * shown at a fifth of the size it was rasterised at skips most of its texels, which is the
     * shimmering, broken lettering on a name tag a few dozen blocks away. The mip chain is built
     * once when the page is uploaded, so the cost is paid at font load and never per frame.
     */
    public GlyphAtlas(int[] pageSize, boolean mipmapped) {
        this.pageWidth = clampSide(pageSize[0]);
        this.pageHeight = clampSide(pageSize[1]);
        this.mipmapped = mipmapped && mipmapsSupported();
        this.padding = this.mipmapped ? MIP_PADDING : PADDING;
    }

    private static boolean mipmapsSupported() {
        try {
            org.lwjgl.opengl.ContextCapabilities caps = org.lwjgl.opengl.GLContext.getCapabilities();
            return caps.OpenGL30 || caps.GL_EXT_framebuffer_object;
        } catch (Throwable noContext) {
            return false;
        }
    }

    private static void generateMipmaps() {
        org.lwjgl.opengl.ContextCapabilities caps = org.lwjgl.opengl.GLContext.getCapabilities();
        if (caps.OpenGL30) {
            org.lwjgl.opengl.GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } else {
            org.lwjgl.opengl.EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
        }
    }
public Region add(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > pageWidth || height > pageHeight) {
            return null;
        }

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page.place(width + padding, height + padding, slot)) {
                return page.write(image, slot[0], slot[1]);
            }
        }

        Page page = new Page(pageWidth, pageHeight, mipmapped, padding);
        pages.add(page);
        if (finished) {
            page.upload();
        }
        if (!page.place(width + padding, height + padding, slot)) {
            return null;
        }

        return page.write(image, slot[0], slot[1]);
    }
public void finish() {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).upload();
        }
        finished = true;
        mindless.utility.Diagnostics.gl("font: atlas upload " + pageWidth + "x" + pageHeight
                + " x" + pages.size());
    }

    public void delete() {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).delete();
        }
        pages.clear();
    }
public static int[] chooseSize(List<int[]> glyphSizes) {
        return chooseSize(glyphSizes, false);
    }

    public static int[] chooseSize(List<int[]> glyphSizes, boolean mipmapped) {
        int gutter = mipmapped ? MIP_PADDING : PADDING;
        long area = 0;
        int widest = 1;
        int tallest = 1;

        for (int i = 0; i < glyphSizes.size(); i++) {
            int[] size = glyphSizes.get(i);
            int width = size[0] + gutter;
            int height = size[1] + gutter;
            area += (long) width * height;
            widest = Math.max(widest, width);
            tallest = Math.max(tallest, height);
        }

        long padded = (long) Math.ceil(area * 1.2);
        int width = clampSide(Math.max((int) Math.ceil(Math.sqrt(padded)), widest));
        int height = clampSide(Math.max((int) Math.ceil(padded / (double) width), tallest));
        return new int[]{width, height};
    }

    private static int clampSide(int size) {
        return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, nextPowerOfTwo(size)));
    }

    private static int nextPowerOfTwo(int value) {
        int result = MIN_PAGE_SIZE;
        while (result < value && result < MAX_PAGE_SIZE) {
            result <<= 1;
        }
        return result;
    }

    private static final class Page {
        private final int width;
        private final int height;
        private final boolean mipmapped;
        private final int gutter;
        private ByteBuffer staging;
        private int textureId;
        private int cursorX;
        private int shelfY;
        private int shelfHeight;

        private Page(int width, int height, boolean mipmapped, int gutter) {
            this.width = width;
            this.height = height;
            this.mipmapped = mipmapped;
            this.gutter = gutter;
            this.staging = BufferUtils.createByteBuffer(width * height * BYTES_PER_PIXEL);
            this.textureId = GL11.glGenTextures();
        }

        private boolean place(int slotWidth, int slotHeight, int[] out) {
            if (slotWidth > width || slotHeight > height) {
                return false;
            }

            if (cursorX + slotWidth > width) {
                shelfY += shelfHeight;
                shelfHeight = 0;
                cursorX = 0;
            }

            if (shelfY + slotHeight > height) {
                return false;
            }

            // Offset into the slot by half the gutter, so a glyph has clear space on every side
            // rather than only to its right and below.
            out[0] = cursorX + gutter / 2;
            out[1] = shelfY + gutter / 2;
            cursorX += slotWidth;
            shelfHeight = Math.max(shelfHeight, slotHeight);
            return true;
        }

        private Region write(BufferedImage image, int x, int y) {
            int glyphWidth = image.getWidth();
            int glyphHeight = image.getHeight();

            if (staging != null) {
                blitToStaging(image, x, y);
            }
            else {
                uploadSubImage(image, x, y);
            }

            return new Region(textureId,
                    x / (float) width, y / (float) height,
                    (x + glyphWidth) / (float) width, (y + glyphHeight) / (float) height);
        }

        private void blitToStaging(BufferedImage image, int x, int y) {
            int glyphWidth = image.getWidth();
            int glyphHeight = image.getHeight();
            int[] pixels = image.getRGB(0, 0, glyphWidth, glyphHeight,
                    new int[glyphWidth * glyphHeight], 0, glyphWidth);

            for (int row = 0; row < glyphHeight; row++) {
                staging.position((((y + row) * width) + x) * BYTES_PER_PIXEL);
                int rowStart = row * glyphWidth;

                for (int column = 0; column < glyphWidth; column++) {
                    int pixel = pixels[rowStart + column];
                    staging.put((byte) ((pixel >> 16) & CHANNEL_MASK));
                    staging.put((byte) ((pixel >> 8) & CHANNEL_MASK));
                    staging.put((byte) (pixel & CHANNEL_MASK));
                    staging.put((byte) ((pixel >>> 24) & CHANNEL_MASK));
                }
            }
        }

        private void uploadSubImage(BufferedImage image, int x, int y) {
            if (textureId == 0) {
                return;
            }

            ByteBuffer buffer = toBuffer(image);
            GlStateManager.bindTexture(textureId);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, image.getWidth(), image.getHeight(),
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            if (mipmapped) generateMipmaps();
            mindless.utility.Diagnostics.gl("font: glyph upload at " + x + "," + y
                    + " " + image.getWidth() + "x" + image.getHeight()
                    + " into " + width + "x" + height);
        }

        private static ByteBuffer toBuffer(BufferedImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, new int[width * height], 0, width);
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * BYTES_PER_PIXEL);

            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                buffer.put((byte) ((pixel >> 16) & CHANNEL_MASK));
                buffer.put((byte) ((pixel >> 8) & CHANNEL_MASK));
                buffer.put((byte) (pixel & CHANNEL_MASK));
                buffer.put((byte) ((pixel >>> 24) & CHANNEL_MASK));
            }

            buffer.flip();
            return buffer;
        }

        private void upload() {
            if (staging == null) {
                return;
            }

            staging.position(0);
            staging.limit(width * height * BYTES_PER_PIXEL);
            GlStateManager.bindTexture(textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            if (mipmapped) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, MIP_LEVELS);
                // A slight negative bias: pure trilinear picks the smaller level early and softens
                // text that is only mildly minified, which is most name tags at fighting range.
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL14.GL_TEXTURE_LOD_BIAS, -0.4f);
            }
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
            if (mipmapped) generateMipmaps();
            staging = null;
        }

        private void delete() {
            staging = null;
            if (textureId != 0) {
                GL11.glDeleteTextures(textureId);
                textureId = 0;
            }
        }
    }
}
