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
    private final List<Page> pages = new ArrayList<Page>();
    private final int[] slot = new int[2];
    private boolean finished;
public GlyphAtlas(int[] pageSize) {
        this.pageWidth = clampSide(pageSize[0]);
        this.pageHeight = clampSide(pageSize[1]);
    }
public Region add(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > pageWidth || height > pageHeight) {
            return null;
        }

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page.place(width + PADDING, height + PADDING, slot)) {
                return page.write(image, slot[0], slot[1]);
            }
        }

        Page page = new Page(pageWidth, pageHeight);
        pages.add(page);
        if (finished) {
            page.upload();
        }
        if (!page.place(width + PADDING, height + PADDING, slot)) {
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
        long area = 0;
        int widest = 1;
        int tallest = 1;

        for (int i = 0; i < glyphSizes.size(); i++) {
            int[] size = glyphSizes.get(i);
            int width = size[0] + PADDING;
            int height = size[1] + PADDING;
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
        private ByteBuffer staging;
        private int textureId;
        private int cursorX;
        private int shelfY;
        private int shelfHeight;

        private Page(int width, int height) {
            this.width = width;
            this.height = height;
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

            out[0] = cursorX;
            out[1] = shelfY;
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
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
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
