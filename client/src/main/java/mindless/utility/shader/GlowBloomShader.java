package mindless.utility.shader;

import mindless.utility.Diagnostics;
import mindless.utility.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
public class GlowBloomShader {
    private static final Minecraft mc = Minecraft.getMinecraft();
private static final int BLUR_DIVISOR = 2;

    private static final int MODE_BLUR = 0;
    private static final int MODE_COMPOSITE = 1;

    private final BlurPass pass = new BlurPass();
    private Framebuffer horizontal;
    private Framebuffer vertical;

    public boolean isValid() {
        return pass.isValid();
    }
public void render(Framebuffer silhouette, float radius, float intensity, int r, int g, int b) {
        if (!pass.isValid() || silhouette == null || radius <= 0.0f) return;

        Diagnostics.gl("glow: entering (errors from earlier passes)");
        horizontal = RenderUtils.createScaledFrameBuffer(horizontal, BLUR_DIVISOR, false);
        vertical = RenderUtils.createScaledFrameBuffer(vertical, BLUR_DIVISOR, false);
        if (horizontal == null || vertical == null) return;
        horizontal.setFramebufferFilter(GL11.GL_LINEAR);
        vertical.setFramebufferFilter(GL11.GL_LINEAR);
        Diagnostics.gl("glow: blur buffers ready");
        GlStateManager.disableBlend();
        RenderUtils.setAlphaLimit(0.0f);
        horizontal.framebufferClear();
        horizontal.bindFramebuffer(true);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setTexelSize(silhouette.framebufferWidth, silhouette.framebufferHeight);
        pass.setDirection(1.0f, 0.0f, MODE_BLUR);
        Diagnostics.gl("glow: horizontal uniforms set");
        RenderUtils.drawFramebufferFullscreen(silhouette);
        Diagnostics.gl("glow: horizontal draw");
        pass.stop();
        vertical.framebufferClear();
        vertical.bindFramebuffer(true);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setTexelSize(horizontal.framebufferWidth, horizontal.framebufferHeight);
        pass.setDirection(0.0f, 1.0f, MODE_BLUR);
        RenderUtils.drawFramebufferFullscreen(horizontal);
        Diagnostics.gl("glow: vertical draw");
        pass.stop();
        mc.getFramebuffer().bindFramebuffer(true);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        pass.use();
        pass.setTint(r, g, b);
        pass.setShape(radius, intensity);
        pass.setDirection(0.0f, 0.0f, MODE_COMPOSITE);
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
                "  vec2 sampleStep = direction * texelSize * (radius / 8.0);\n" +
                "  float acc = texture2D(tex, uv).a;\n" +
                "  for (int i = 1; i <= 8; i++) {\n" +
                "    float fi = float(i);\n" +
                "    float w = exp(-fi * fi / 18.0);\n" +
                "    vec2 d = sampleStep * fi;\n" +
                "    acc += (texture2D(tex, uv + d).a + texture2D(tex, uv - d).a) * w;\n" +
                "  }\n" +
                "  gl_FragColor = vec4(tint, acc * 0.13357122);\n" +
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

        private void setTexelSize(int width, int height) {
            int location = uniform("texelSize");
            if (location >= 0) {
                GL20.glUniform2f(location, 1.0f / Math.max(1, width), 1.0f / Math.max(1, height));
            }
        }

        private void setDirection(float x, float y, int mode) {
            int location = uniform("direction");
            if (location >= 0) GL20.glUniform2f(location, x, y);
            location = uniform("mode");
            if (location >= 0) GL20.glUniform1i(location, mode);
        }
    }
}
