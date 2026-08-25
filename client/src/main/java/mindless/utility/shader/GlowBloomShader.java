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
 * The blur is separable: a horizontal pass into a scratch buffer, then a vertical pass composited
 * straight over the scene. Doing it as two one-dimensional passes costs seventeen samples each
 * instead of the two hundred and eighty-nine a square kernel of the same width would need.
 *
 * Only coverage is blurred, never colour. The silhouette is drawn in a flat tint and its alpha is
 * the only channel that carries shape, so the passes accumulate alpha and re-apply the tint at the
 * end. That keeps the glow a single clean hue rather than letting the body's colours bleed out.
 *
 * The final pass multiplies by the inverse of the original coverage, so the glow lives outside the
 * silhouette and the player underneath stays clean instead of being washed flat.
 */
public class GlowBloomShader {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final BlurPass pass = new BlurPass();
    private Framebuffer scratch;

    public boolean isValid() {
        return pass.isValid();
    }

    /**
     * @param silhouette buffer holding the tinted shapes, coverage in alpha
     * @param radius     blur reach in texels of the silhouette buffer
     * @param intensity  brightness multiplier applied to the accumulated coverage
     */
    public void render(Framebuffer silhouette, float radius, float intensity, int r, int g, int b) {
        if (!pass.isValid() || silhouette == null || radius <= 0.0f) return;

        Diagnostics.gl("glow: entering (errors from earlier passes)");
        scratch = RenderUtils.createFrameBuffer(scratch, false);
        if (scratch == null) return;
        scratch.setFramebufferFilter(GL11.GL_LINEAR);
        Diagnostics.gl("glow: scratch buffer ready");

        // Horizontal half, written opaquely into the scratch buffer: blending here would mix the
        // partial result with whatever the buffer already held.
        scratch.framebufferClear();
        scratch.bindFramebuffer(false);
        GlStateManager.disableBlend();
        RenderUtils.setAlphaLimit(0.0f);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(1.0f, 0.0f, false);
        Diagnostics.gl("glow: horizontal uniforms set");
        RenderUtils.drawFramebufferFullscreen(silhouette);
        Diagnostics.gl("glow: horizontal draw");
        pass.stop();

        // Vertical half, composited over the scene. Additive rather than alpha-over, because a
        // glow is light being added to what is behind it rather than a film laid on top: it should
        // brighten dark ground and leave bright ground much as it was.
        mc.getFramebuffer().bindFramebuffer(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(0.0f, 1.0f, true);
        // Unit 2, not 16. OpenGL only guarantees sixteen fragment texture units, numbered
        // zero to fifteen, and GL_MAX_TEXTURE_IMAGE_UNITS is commonly exactly sixteen, so a
        // sampler pointed at unit 16 is out of range: the draw raises GL_INVALID_OPERATION
        // and produces nothing. Unit 1 is off limits too -- that is the lightmap.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        RenderUtils.bindTexture(silhouette.framebufferTexture);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        Diagnostics.gl("glow: vertical uniforms and textures bound");
        RenderUtils.drawFramebufferFullscreen(scratch);
        Diagnostics.gl("glow: vertical draw");
        pass.stop();

        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Diagnostics.gl("glow: pass complete");
    }

    public void delete() {
        if (scratch != null) {
            scratch.deleteFramebuffer();
            scratch = null;
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
                "uniform int finalPass;\n" +
                "void main() {\n" +
                "  vec2 uv = gl_TexCoord[0].xy;\n" +
                "  vec2 sampleStep = direction * texelSize * (radius / 12.0);\n" +
                "  float acc = 0.0;\n" +
                "  float weightSum = 0.0;\n" +
                "  for (int i = -12; i <= 12; i++) {\n" +
                "    float fi = float(i);\n" +
                "    float w = exp(-fi * fi / 50.0);\n" +
                "    acc += texture2D(tex, uv + sampleStep * fi).a * w;\n" +
                "    weightSum += w;\n" +
                "  }\n" +
                "  float glow = acc / weightSum;\n" +
                "  if (finalPass == 0) {\n" +
                "    gl_FragColor = vec4(tint, glow);\n" +
                "    return;\n" +
                "  }\n" +
                "  glow *= 1.0 - texture2D(original, uv).a;\n" +
                "  glow = pow(clamp(glow * intensity, 0.0, 1.0), 2.0) * 0.7;\n" +
                "  gl_FragColor = vec4(tint, glow);\n" +
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
            cacheUniform("finalPass");
        }

        @Override
        public void onUse() {
            GL20.glUseProgram(programId);
            int location = uniform("tex");
            if (location >= 0) GL20.glUniform1i(location, 0);
            location = uniform("original");
            if (location >= 0) GL20.glUniform1i(location, 2);
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

        private void setDirection(float x, float y, boolean finalPass) {
            int location = uniform("direction");
            if (location >= 0) GL20.glUniform2f(location, x, y);
            location = uniform("finalPass");
            if (location >= 0) GL20.glUniform1i(location, finalPass ? 1 : 0);
        }
    }
}
