package keystrokesmod.runtime;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.shader.ShaderGroup;

import java.util.Map;
import java.util.WeakHashMap;

/** External replacement for the ISaturationRenderer Mixin interface/state. */
public final class SaturationShaderRuntime {
    private static final Map<EntityRenderer, ShaderGroup> SHADERS = new WeakHashMap<>();
    private SaturationShaderRuntime() {}
    public static synchronized ShaderGroup get(EntityRenderer renderer) { return SHADERS.get(renderer); }
    public static synchronized void set(EntityRenderer renderer, ShaderGroup shader) {
        if (shader == null) SHADERS.remove(renderer); else SHADERS.put(renderer, shader);
    }
}
