package mindless.utility.font;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One texture per font rather than one per glyph.
 *
 * <p>A texture bind is a state change, and a state change ends whatever batch the driver was
 * building. Giving every glyph its own texture therefore forces one draw per character no matter
 * how the geometry is submitted -- a line of forty characters cannot be fewer than forty draws.
 * Packing the whole glyph set into a single texture removes that constraint: every quad in a
 * string shares one bind, so the string is one draw.
 *
 * <p>Packing is a shelf: glyphs are laid left to right in rows, a new row starting below the
 * tallest glyph of the previous one. Fancier packers exist, but glyphs from a single font are
 * close enough in height that shelves waste very little, and the whole thing runs once when the
 * font is built.
 *
 * <p>Every glyph reserves two transparent pixels to its right and below. Without them a linear
 * filter sampling the edge of one glyph reaches half a texel into the next and drags a sliver of
 * its neighbour along -- the artefact per-glyph textures avoided with GL_CLAMP_TO_EDGE, which an
 * atlas cannot use because the clamp applies to the page and not to each glyph inside it.
 */
public final class GlyphAtlas {
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int PADDING = 2;
    private static final int MIN_PAGE_SIZE = 64;
    /**
     * Deliberately short of what a large boosted font would take if given the room.
     *
     * <p>Sides are powers of two, so a set needing a little over a million pixels would otherwise
     * be handed a 2048 square: four million pixels, sixteen megabytes, most of it empty. Capping
     * the side and letting the set spill onto a second page holds the same glyphs in a fraction of
     * that, and the second bind costs nothing in practice -- glyphs are packed in code point
     * order, so everything an ordinary line of text needs is on the first page.
     */
    private static final int MAX_PAGE_SIZE = 1024;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int CHANNEL_MASK = 0xFF;

    /** Where one glyph ended up: which page holds it, and the rectangle it occupies there. */
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

    /** @param pageSize width and height, as returned by {@link #chooseSize(List)} */
    public GlyphAtlas(int[] pageSize) {
        this.pageWidth = clampSide(pageSize[0]);
        this.pageHeight = clampSide(pageSize[1]);
    }

    /**
     * Places one rasterised glyph and returns where it landed, or null if it cannot be placed at
     * all -- which only happens for an image larger than a whole page.
     */
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
        // A page opened after the atlas was finished has missed its upload, so it takes it now --
        // empty. Everything placed on it from here writes its own rectangle straight to GL, the
        // same path the late glyph that caused this page to exist is about to take.
        if (finished) {
            page.upload();
        }
        if (!page.place(width + PADDING, height + PADDING, slot)) {
            return null;
        }

        return page.write(image, slot[0], slot[1]);
    }

    /**
     * Uploads everything staged so far and releases the staging buffers. Glyphs added afterwards
     * -- the rare characters that are only rasterised when something actually asks for them --
     * upload their own rectangle directly.
     */
    public void finish() {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).upload();
        }
        finished = true;
    }

    public void delete() {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).delete();
        }
        pages.clear();
    }

    /**
     * A page shape that holds the given glyph rectangles without waste worth caring about.
     *
     * <p>Width and height are chosen separately. Both are rounded up to a power of two, which is
     * safe on every driver, but a square page has to round both axes at once -- a set needing a
     * little over half a page then ends up in a full one. Choosing the height on its own confines
     * that rounding to a single axis, which is most of an atlas worth of memory back.
     *
     * <p>Shelf packing leaves gaps, so the raw area is optimistic; the slack below covers it.
     * Undershooting is not a failure -- a second page is allocated -- but a second page means a
     * second bind, which is the thing this class exists to avoid.
     *
     * @return width and height, in that order
     */
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
            // The name is reserved before there is anything to put in it, because every region
            // handed out below records which texture holds it and regions are handed out during
            // packing -- long before the page has storage. Waiting until upload would stamp every
            // glyph in the set with a texture id of zero, and zero means "nothing to draw".
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
