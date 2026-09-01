package mindless.utility.font;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
public final class GlyphBatch {
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int STRIDE = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int MAX_QUADS = 4096;

    private static final FloatBuffer VERTICES =
            BufferUtils.createFloatBuffer(MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

    private static int depth;
    private static int quads;
    private static int texture;

    private GlyphBatch() {
    }

    public static void begin() {
        if (depth++ > 0) {
            return;
        }

        quads = 0;
        texture = 0;
        VERTICES.clear();
    }

    public static void end() {
        if (depth == 0 || --depth > 0) {
            return;
        }

        flush();
    }
public static void quad(int textureId, float x, float y, float width, float height,
                            float u0, float v0, float u1, float v1,
                            float red, float green, float blue, float alpha) {
        if (textureId == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }

        if (textureId != texture || quads >= MAX_QUADS) {
            flush();
            texture = textureId;
        }

        float right = x + width;
        float bottom = y + height;
        vertex(x, y, u0, v0, red, green, blue, alpha);
        vertex(x, bottom, u0, v1, red, green, blue, alpha);
        vertex(right, bottom, u1, v1, red, green, blue, alpha);
        vertex(right, y, u1, v0, red, green, blue, alpha);
        quads++;
    }

    private static void vertex(float x, float y, float u, float v, float red, float green, float blue, float alpha) {
        VERTICES.put(x).put(y).put(u).put(v).put(red).put(green).put(blue).put(alpha);
    }
private static void flush() {
        if (quads == 0 || texture == 0) {
            VERTICES.clear();
            quads = 0;
            return;
        }

        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableTexture2D();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GlStateManager.bindTexture(texture);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        VERTICES.position(0);
        GL11.glVertexPointer(2, STRIDE, VERTICES);
        VERTICES.position(2);
        GL11.glTexCoordPointer(2, STRIDE, VERTICES);
        VERTICES.position(4);
        GL11.glColorPointer(4, STRIDE, VERTICES);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, quads * VERTICES_PER_QUAD);

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        GlStateManager.bindTexture(0);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        VERTICES.clear();
        quads = 0;
    }
}
