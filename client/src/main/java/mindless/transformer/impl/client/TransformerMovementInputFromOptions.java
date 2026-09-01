package mindless.transformer.impl.client;

import mindless.event.PostPlayerInputEvent;
import mindless.event.PrePlayerInputEvent;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraftforge.common.MinecraftForge;

@CTransformer(MovementInputFromOptions.class)
public abstract class TransformerMovementInputFromOptions {
    @CShadow
    private GameSettings gameSettings;

    @COverride("updatePlayerMoveState")
    public void updatePlayerMoveState() {
        net.minecraft.util.MovementInput self = (net.minecraft.util.MovementInput) (Object) this;
        self.moveStrafe = 0.0F;
        self.moveForward = 0.0F;
        if (this.gameSettings.keyBindForward.isKeyDown()) ++self.moveForward;
        if (this.gameSettings.keyBindBack.isKeyDown()) --self.moveForward;
        if (this.gameSettings.keyBindLeft.isKeyDown()) ++self.moveStrafe;
        if (this.gameSettings.keyBindRight.isKeyDown()) --self.moveStrafe;
        self.jump = this.gameSettings.keyBindJump.isKeyDown();
        self.sneak = this.gameSettings.keyBindSneak.isKeyDown();

        PrePlayerInputEvent moveInputEvent = new PrePlayerInputEvent(
                self.moveForward, self.moveStrafe, self.jump, self.sneak, 0.3D);
        MinecraftForge.EVENT_BUS.post(moveInputEvent);

        double sneakMultiplier = moveInputEvent.getSneakSlowDownMultiplier();
        self.moveForward = moveInputEvent.getForward();
        self.moveStrafe = moveInputEvent.getStrafe();
        self.jump = moveInputEvent.isJump();
        self.sneak = moveInputEvent.isSneak();

        if (self.sneak) {
            self.moveStrafe = (float) ((double) self.moveStrafe * sneakMultiplier);
            self.moveForward = (float) ((double) self.moveForward * sneakMultiplier);
        }
    }

    @CInline
    @CInject(method = "updatePlayerMoveState", target = @CTarget("RETURN"))
    private void onUpdatePlayerMoveState(InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new PostPlayerInputEvent());
    }
}
