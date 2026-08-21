package mindless.utility.shader;

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

        scratch = RenderUtils.createFrameBuffer(scratch, false);
        if (scratch == null) return;

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
        RenderUtils.drawFramebufferFullscreen(silhouette);
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
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE16);
        RenderUtils.bindTexture(silhouette.framebufferTexture);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        RenderUtils.drawFramebufferFullscreen(scratch);
        pass.stop();

        GlStateManager.bindTexture(0);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
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
                // Seventeen taps spread across the radius. The loop bound has to be a compile-time
                // constant in GLSL 120, so reach is varied by scaling the step instead.
                "  vec2 step = direction * texelSize * (radius / 8.0);\n" +
                "  float acc = 0.0;\n" +
                "  float weightSum = 0.0;\n" +
                "  for (int i = -8; i <= 8; i++) {\n" +
                "    float fi = float(i);\n" +
                "    float w = exp(-fi * fi / 24.0);\n" +
                "    acc += texture2D(tex, uv + step * fi).a * w;\n" +
                "    weightSum += w;\n" +
                "  }\n" +
                "  float glow = acc / weightSum;\n" +
                "  if (finalPass == 0) {\n" +
                "    gl_FragColor = vec4(tint, glow);\n" +
                "    return;\n" +
                "  }\n" +
                // The body stays clean; the glow belongs outside the silhouette.
                "  glow *= 1.0 - texture2D(original, uv).a;\n" +
                // Raising coverage to a power below one lifts the faint tail without touching the
                // bright core, which is what makes the falloff read as light rather than as a band.
                "  glow = pow(clamp(glow * intensity, 0.0, 1.0), 0.75);\n" +
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
            if (location >= 0) GL20.glUniform1i(location, 16);
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
