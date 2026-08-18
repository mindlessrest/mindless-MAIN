package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
import mindless.module.impl.player.SafeWalk;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@CTransformer(GameSettings.class)
public class TransformerGameSettings {
    @COverride
    public static boolean isKeyDown(KeyBinding key) {
        SafeWalk safewalk = ModuleManager.safeWalk;
        if (key == Minecraft.getMinecraft().gameSettings.keyBindSneak && safewalk != null && safewalk.isEnabled() && safewalk.sneak.isToggled() && safewalk.isSneaking) {
            return true;
        }
        return key.getKeyCode() != 0 && (key.getKeyCode() < 0 ? Mouse.isButtonDown(key.getKeyCode() + 100) : Keyboard.isKeyDown(key.getKeyCode()));
    }
}
