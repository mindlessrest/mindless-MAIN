package mindless.utility.shader;

import mindless.utility.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

public class SeparableOutlineShader {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final DirectionalShader shader = new DirectionalShader();
    private Framebuffer framebuffer;

    public boolean isValid() {
        return shader.isValid();
    }

    public void render(Framebuffer source) {
        framebuffer = RenderUtils.createFrameBuffer(framebuffer, false);
        if (framebuffer == null) return;

        framebuffer.framebufferClear();
        framebuffer.bindFramebuffer(false);
        GlStateManager.disableBlend();
        RenderUtils.setAlphaLimit(0.0f);
        shader.use();
        shader.setPass(0.0f, 1.0f, false);
        RenderUtils.drawFramebufferFullscreen(source);
        shader.stop();

        mc.getFramebuffer().bindFramebuffer(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 771);
        shader.use();
        shader.setPass(1.0f, 0.0f, true);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        RenderUtils.bindTexture(source.framebufferTexture);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        RenderUtils.drawFramebufferFullscreen(framebuffer);
        shader.stop();
        GlStateManager.bindTexture(0);
    }

    private static final class DirectionalShader extends OutlineESPShader {
        private static final String FRAG = "#version 120\n" +
                "uniform sampler2D tex;\n" +
                "uniform sampler2D original;\n" +
                "uniform vec2 texelSize;\n" +
                "uniform vec2 direction;\n" +
                "uniform int finalPass;\n" +
                "void main() {\n" +
                "  vec2 uv = gl_TexCoord[0].xy;\n" +
                "  if (finalPass == 1 && texture2D(original, uv).a > 0.0) { gl_FragColor = vec4(0.0); return; }\n" +
                "  float bestAlpha = 0.0;\n" +
                "  vec3 bestColor = vec3(0.0);\n" +
                "  for (float offset = -3.0; offset <= 3.0; offset += 1.0) {\n" +
                "    vec4 s = texture2D(tex, uv + direction * texelSize * offset);\n" +
                "    float w = s.a * (1.0 - abs(offset) / 4.0);\n" +
                "    if (w > bestAlpha) { bestAlpha = w; bestColor = s.rgb; }\n" +
                "  }\n" +
                "  gl_FragColor = vec4(bestColor, bestAlpha);\n" +
                "}";

        private DirectionalShader() {
            super(FRAG);
        }

        @Override
        public void onLink() {
            cacheUniform("tex");
            cacheUniform("original");
            cacheUniform("texelSize");
            cacheUniform("direction");
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

        private void setPass(float x, float y, boolean finalPass) {
            int location = uniform("direction");
            if (location >= 0) GL20.glUniform2f(location, x, y);
            location = uniform("finalPass");
            if (location >= 0) GL20.glUniform1i(location, finalPass ? 1 : 0);
        }
    }
}
