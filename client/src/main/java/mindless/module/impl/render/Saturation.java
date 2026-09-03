package mindless.module.impl.render;

import mindless.runtime.AccessorBridge;
import mindless.runtime.SaturationShaderRuntime;
import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.List;

public class Saturation extends Module {
    private static final ResourceLocation SHADER_LOCATION = new ResourceLocation("minecraft:shaders/post/color_convolve.json");

    private SliderSetting saturationSlider;
    private float lastSaturation = 1.0f;

    public Saturation() {
        super("Saturation", "Adjusts how saturated the world looks.", category.render);
        this.registerSetting(saturationSlider = new SliderSetting("Saturation", 1.0, -1.0, 5.0, 0.05));
    }

    @Override
    public void onEnable() {
        loadShader();
    }

    @Override
    public void onDisable() {
        removeShader();
    }

    @Override
    public void onUpdate() {
        float current = (float) saturationSlider.getInput();
        if (!isShaderActive()) {
            loadShader();
        }
        if (current != lastSaturation) {
            lastSaturation = current;
            applySaturation();
        }
    }

    private void loadShader() {
        if (mc.theWorld == null) return;
        if (isShaderActive()) return;
        try {
            ShaderGroup shader = new ShaderGroup(
                    mc.getTextureManager(),
                    mc.getResourceManager(),
                    mc.getFramebuffer(),
                    SHADER_LOCATION
            );
            shader.createBindFramebuffers(mc.displayWidth, mc.displayHeight);
            SaturationShaderRuntime.set(mc.entityRenderer, shader);
            lastSaturation = (float) saturationSlider.getInput();
            applySaturation();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void removeShader() {
        if (mc.entityRenderer == null) return;
        ShaderGroup shader = SaturationShaderRuntime.get(mc.entityRenderer);
        if (shader != null) {
            shader.deleteShaderGroup();
        }
        SaturationShaderRuntime.set(mc.entityRenderer, null);
    }

    private void applySaturation() {
        if (mc.entityRenderer == null) return;
        ShaderGroup shader = SaturationShaderRuntime.get(mc.entityRenderer);
        if (shader == null) return;
        List<Shader> shaders = AccessorBridge.ShaderGroup_getListShaders(shader);
        if (shaders == null) return;
        for (Shader s : shaders) {
            ShaderUniform su = s.getShaderManager().getShaderUniform("Saturation");
            if (su != null) {
                su.set(lastSaturation);
            }
        }
    }

    private boolean isShaderActive() {
        return mc.entityRenderer != null && SaturationShaderRuntime.get(mc.entityRenderer) != null;
    }
}
