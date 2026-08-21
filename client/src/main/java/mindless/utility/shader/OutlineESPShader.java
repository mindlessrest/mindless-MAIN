package mindless.utility.shader;

import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

public abstract class OutlineESPShader {
    private static final String VERT = "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n" +
            "}";

    protected int programId = -1;
    private String error;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public OutlineESPShader(String fragSrc) {
        int v = compile(VERT, GL20.GL_VERTEX_SHADER);
        int f = compile(fragSrc, GL20.GL_FRAGMENT_SHADER);
        if (v < 0 || f < 0) {
            report("compile");
            return;
        }
        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, v);
        GL20.glAttachShader(programId, f);
        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            error = GL20.glGetProgramInfoLog(programId, 4096);
            programId = -1;
            report("link");
            return;
        }
        onLink();
    }

    private int compile(String src, int type) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            error = GL20.glGetShaderInfoLog(id, 4096);
            return -1;
        }
        return id;
    }

    /**
     * A shader that fails to build used to leave programId at -1 and say nothing at all, so the
     * effect simply did not appear and there was no way to tell that from it being switched off.
     */
    private void report(String stage) {
        System.err.println("[mindless] " + getClass().getSimpleName() + " " + stage
                + " failed: " + (error == null ? "no driver message" : error.trim()));
    }

    /** Driver message from the failed build, or null when the shader is fine. */
    public String getError() {
        return error;
    }

    protected void cacheUniform(String name) {
        if (programId >= 0) uniforms.put(name, GL20.glGetUniformLocation(programId, name));
    }

    protected int uniform(String name) {
        return uniforms.getOrDefault(name, -1);
    }

    public abstract void onLink();

    public abstract void onUse();

    public void use() {
        if (programId >= 0) onUse();
    }

    public void stop() {
        GL20.glUseProgram(0);
    }

    public boolean isValid() {
        return programId >= 0;
    }
}
