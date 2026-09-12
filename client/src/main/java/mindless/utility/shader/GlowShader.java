package mindless.utility.shader;

import org.lwjgl.opengl.GL20;

public class GlowShader extends OutlineESPShader {
private static final String FRAG = "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec4 tint;\n" +
            "void main() {\n" +
            "  vec4 texel = texture2D(tex, gl_TexCoord[0].st);\n" +
            // A twentieth, not a tenth. The threshold decides which texels count as part of the
            // silhouette, and an item sampled from a mipmapped atlas at a fraction of its native
            // size averages its transparent border into every texel it keeps: at a tenth, sparse
            // textures -- a tool head on a stick, a thin blade -- fell under it and vanished while
            // solid ones stayed, which is exactly the "works on some items" pattern.
            "  if (texel.a < 0.05) discard;\n" +
            "  gl_FragColor = vec4(tint.rgb, tint.a * texel.a);\n" +
            "}";

    public GlowShader() {
        super(FRAG);
    }

    @Override
    public void onLink() {
        cacheUniform("tex");
        cacheUniform("tint");
    }

    @Override
    public void onUse() {
        GL20.glUseProgram(programId);
        int u = uniform("tex");
        if (u >= 0) GL20.glUniform1i(u, 0);
    }

    public void setColor(int r, int g, int b, int a) {
        int u = uniform("tint");
        if (u >= 0) GL20.glUniform4f(u, r / 255f, g / 255f, b / 255f, a / 255f);
    }

    public void setColorFromARGB(int argb) {
        setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >> 24) & 0xFF);
    }
}
