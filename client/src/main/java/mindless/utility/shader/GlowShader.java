package mindless.utility.shader;

import org.lwjgl.opengl.GL20;

public class GlowShader extends OutlineESPShader {
    /**
     * Flattens whatever it is drawing to a single colour, keeping only the shape.
     *
     * <p>It has to read the texture to know what the shape is. It used to bind {@code tex}, declare
     * it, and then write {@code tint} for every fragment without ever sampling it -- so the shape
     * it recorded was not the entity but the quads the entity is assembled from. Armour layers are
     * mostly transparent texture on an enlarged copy of the body, so an armoured player silhouetted
     * as a solid box, and the glow sat around the player rather than on them. Held and dropped
     * items are a sprite on a single quad, so they came out as squares.
     *
     * <p>The cutoff is the one vanilla applies to entities (GL_GREATER, 0.1). Above it the texel's
     * own alpha still scales the tint, which softens the rim by the same fraction the texture does
     * and keeps the blur from starting on a hard step.
     */
    private static final String FRAG = "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec4 tint;\n" +
            "void main() {\n" +
            "  vec4 texel = texture2D(tex, gl_TexCoord[0].st);\n" +
            "  if (texel.a < 0.1) discard;\n" +
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
