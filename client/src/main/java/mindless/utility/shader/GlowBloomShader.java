package mindless.utility.shader;

import mindless.utility.Diagnostics;
import mindless.utility.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * A soft glow radiating outward from a rendered silhouette.
 *
 * The previous approach dilated the silhouette by a couple of texels and masked the middle out,
 * which can only ever produce a hard traced edge -- there is no falloff in it at all. A glow needs
 * the silhouette's coverage smeared over a wide radius so that it is bright where it meets the
 * body and fades to nothing further out, which is what a Gaussian blur gives.
 *
 * The blur is separable: a horizontal pass, then a vertical one. Doing it as two one-dimensional
 * passes costs thirty-three samples each instead of the one thousand and eighty-nine a square
 * kernel of the same width would need.
 *
 * Both blur passes run at half resolution in each axis. Thirty-three taps per pixel is a lot of
 * texture traffic to spend on an image whose entire purpose is to have no detail in it: at 1080p
 * two full-resolution passes fetch about a hundred and forty million texels every frame, and three
 * quarters of that buys nothing a wide Gaussian can express. The composite that follows is the one
 * pass that stays full resolution -- it multiplies by the silhouette's own coverage to keep the
 * glow outside the body, and that edge is the one place in the effect where a soft boundary would
 * actually be visible.
 *
 * Only coverage is blurred, never colour. The silhouette is drawn in a flat tint and its alpha is
 * the only channel that carries shape, so the passes accumulate alpha and re-apply the tint at the
 * end. That keeps the glow a single clean hue rather than letting the body's colours bleed out.
 */
public class GlowBloomShader {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** How far each axis of the blur buffers is scaled down. Two is four times less fill. */
    private static final int BLUR_DIVISOR = 2;

    private static final int MODE_BLUR = 0;
    private static final int MODE_COMPOSITE = 1;

    private final BlurPass pass = new BlurPass();
    private Framebuffer horizontal;
    private Framebuffer vertical;

    public boolean isValid() {
        return pass.isValid();
    }

    /**
     * @param silhouette buffer holding the tinted shapes, coverage in alpha
     * @param radius     blur reach in pixels of the display
     * @param intensity  brightness multiplier applied to the accumulated coverage
     */
    public void render(Framebuffer silhouette, float radius, float intensity, int r, int g, int b) {
        if (!pass.isValid() || silhouette == null || radius <= 0.0f) return;

        Diagnostics.gl("glow: entering (errors from earlier passes)");
        horizontal = RenderUtils.createScaledFrameBuffer(horizontal, BLUR_DIVISOR, false);
        vertical = RenderUtils.createScaledFrameBuffer(vertical, BLUR_DIVISOR, false);
        if (horizontal == null || vertical == null) return;
        // Linear, because the composite reads these back stretched to full size.
        horizontal.setFramebufferFilter(GL11.GL_LINEAR);
        vertical.setFramebufferFilter(GL11.GL_LINEAR);
        Diagnostics.gl("glow: blur buffers ready");

        // Both blur halves are written opaquely: blending here would mix a partial result with
        // whatever the buffer already held.
        GlStateManager.disableBlend();
        RenderUtils.setAlphaLimit(0.0f);

        // Horizontal, reading the full-resolution silhouette into the half-size buffer. The step
        // between taps is a fraction of the texture rather than a count of texels, so reading a
        // smaller target does not change how far across the screen the blur reaches.
        horizontal.framebufferClear();
        horizontal.bindFramebuffer(true);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(1.0f, 0.0f, MODE_BLUR);
        Diagnostics.gl("glow: horizontal uniforms set");
        RenderUtils.drawFramebufferFullscreen(silhouette);
        Diagnostics.gl("glow: horizontal draw");
        pass.stop();

        // Vertical, half size to half size.
        vertical.framebufferClear();
        vertical.bindFramebuffer(true);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(0.0f, 1.0f, MODE_BLUR);
        RenderUtils.drawFramebufferFullscreen(horizontal);
        Diagnostics.gl("glow: vertical draw");
        pass.stop();

        // Composite over the scene, at full resolution and two texture reads deep. Additive rather
        // than alpha-over, because a glow is light being added to what is behind it rather than a
        // film laid on top: it should brighten dark ground and leave bright ground much as it was.
        //
        // Binding with the viewport reset, not without: the two passes above left the viewport at
        // half size, and a composite drawn into that would land in the bottom-left quarter.
        mc.getFramebuffer().bindFramebuffer(true);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(0.0f, 0.0f, MODE_COMPOSITE);
        // Unit 2, not 16. OpenGL only guarantees sixteen fragment texture units, numbered
        // zero to fifteen, and GL_MAX_TEXTURE_IMAGE_UNITS is commonly exactly sixteen, so a
        // sampler pointed at unit 16 is out of range: the draw raises GL_INVALID_OPERATION
        // and produces nothing. Unit 1 is off limits too -- that is the lightmap.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        RenderUtils.bindTexture(silhouette.framebufferTexture);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        Diagnostics.gl("glow: composite uniforms and textures bound");
        RenderUtils.drawFramebufferFullscreen(vertical);
        Diagnostics.gl("glow: composite draw");
        pass.stop();

        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Diagnostics.gl("glow: pass complete");
    }

    public void delete() {
        if (horizontal != null) {
            horizontal.deleteFramebuffer();
            horizontal = null;
        }
        if (vertical != null) {
            vertical.deleteFramebuffer();
            vertical = null;
        }
    }

    private static final class BlurPass extends OutlineESPShader {
        private static final String FRAG = "#version 120\n" +
                "uniform sampler2D tex;\n" +
                "uniform sampler2D original;\n" +
                "uniform vec2 texelSize;\n" +
                "uniform vec2 direction;\n" +
                "uniform float radius;\n" +
                "uniform float intensity;\n" +
                "uniform vec3 tint;\n" +
                "uniform int mode;\n" +
                "void main() {\n" +
                "  vec2 uv = gl_TexCoord[0].xy;\n" +
                "  if (mode == 1) {\n" +
                "    float glow = texture2D(tex, uv).a;\n" +
                "    float mask = 1.0 - texture2D(original, uv).a;\n" +
                "    glow *= mask;\n" +
                "    glow = pow(clamp(glow * intensity, 0.0, 1.0), 1.4) * 0.7;\n" +
                "    gl_FragColor = vec4(tint, glow);\n" +
                "    return;\n" +
                "  }\n" +
                "  vec2 sampleStep = direction * texelSize * (radius / 16.0);\n" +
                "  float acc = 0.0;\n" +
                "  float weightSum = 0.0;\n" +
                "  for (int i = -16; i <= 16; i++) {\n" +
                "    float fi = float(i);\n" +
                "    float w = exp(-fi * fi / 72.0);\n" +
                "    acc += texture2D(tex, uv + sampleStep * fi).a * w;\n" +
                "    weightSum += w;\n" +
                "  }\n" +
                "  gl_FragColor = vec4(tint, acc / weightSum);\n" +
                "}";

        private BlurPass() {
            super(FRAG);
        }

        @Override
        public void onLink() {
            cacheUniform("tex");
            cacheUniform("original");
            cacheUniform("texelSize");
            cacheUniform("direction");
            cacheUniform("radius");
            cacheUniform("intensity");
            cacheUniform("tint");
            cacheUniform("mode");
        }

        @Override
        public void onUse() {
            GL20.glUseProgram(programId);
            int location = uniform("tex");
            if (location >= 0) GL20.glUniform1i(location, 0);
            location = uniform("original");
            if (location >= 0) GL20.glUniform1i(location, 2);
            // Display texel size, whatever resolution the pass is actually writing. Texture
            // coordinates are normalised, so a step expressed as a fraction of the display covers
            // the same distance on screen no matter how large the buffer being sampled is.
            location = uniform("texelSize");
            if (location >= 0) GL20.glUniform2f(location, 1.0f / mc.displayWidth, 1.0f / mc.displayHeight);
        }

        private void setTint(int r, int g, int b) {
            int location = uniform("tint");
            if (location >= 0) GL20.glUniform3f(location, r / 255f, g / 255f, b / 255f);
        }

        private void setShape(float radius, float intensity) {
            int location = uniform("radius");
            if (location >= 0) GL20.glUniform1f(location, radius);
            location = uniform("intensity");
            if (location >= 0) GL20.glUniform1f(location, intensity);
        }

        private void setDirection(float x, float y, int mode) {
            int location = uniform("direction");
            if (location >= 0) GL20.glUniform2f(location, x, y);
            location = uniform("mode");
            if (location >= 0) GL20.glUniform1i(location, mode);
        }
    }
}
