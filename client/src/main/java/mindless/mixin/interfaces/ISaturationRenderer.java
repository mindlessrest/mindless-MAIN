package mindless.mixin.interfaces;

import net.minecraft.client.shader.ShaderGroup;

public interface ISaturationRenderer {
    ShaderGroup mindless$getSaturationShader();

    void mindless$setSaturationShader(ShaderGroup shader);
}
