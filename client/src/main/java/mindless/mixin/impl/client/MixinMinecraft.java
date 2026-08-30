package mindless.mixin.impl.client;

import mindless.event.*;
import mindless.module.ModuleManager;
import mindless.module.impl.player.BedAura;
import mindless.module.impl.render.Freelook;
import mindless.module.impl.player.FastMine;
import org.objectweb.asm.Opcodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {

    // PostMouseSelectionEvent — fires AFTER getMouseOver; transformer only fires BEFORE. Unique.
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V", shift = At.Shift.AFTER))
    public void onRunTickMouseOver(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PostMouseSelectionEvent());
    }

    // RunGameLoopEvent — targets runGameLoop, not runTick. Unique.
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    public void onRunGameLoop(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new RunGameLoopEvent());
    }

    // FastMine passive block hit decay — unique, no transformer equivalent.
    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;sendClickBlockToController(Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void mindless$fastMinePassiveBlockHitDelay(CallbackInfo ci) {
        BedAura bedAura = ModuleManager.bedAura;
        if (bedAura != null && bedAura.shouldOverrideFastMine()) {
            return;
        }
        FastMine fm = ModuleManager.fastMine;
        if (fm != null) {
            fm.tickPassiveBlockHitDecay((Minecraft) (Object) this);
        }
    }

    // thirdPersonView PUTFIELD — CTarget has no opcode field, so transformer can't do this. Unique.
    @Redirect(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;thirdPersonView:I", opcode = Opcodes.PUTFIELD))
    private void onSetThirdPersonView(GameSettings gameSettings, int value) {
        if (ModuleManager.freelook != null && Freelook.perspectiveToggled) {
            ModuleManager.freelook.resetPerspective();
        } else {
            gameSettings.thirdPersonView = value;
        }
    }

    // SlotUpdateEvent on PUTFIELD currentItem — distinct from PreSlotScrollEvent on INVOKE changeCurrentItem. Unique.
    @Redirect(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/InventoryPlayer;currentItem:I", opcode = Opcodes.PUTFIELD))
    private void onSetCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        SlotUpdateEvent e = new SlotUpdateEvent(slot);
        MinecraftForge.EVENT_BUS.post(e);
        if (e.isCanceled()) {
            return;
        }
        inventoryPlayer.currentItem = slot;
    }

    // NOTE: The following were removed — TransformerMinecraft already handles them:
    // onBeforeGetMouseOver      → transformer beforeMouseOver (RotationHelper.updateServerRotations)
    // injectBeforeChatVisibility → transformer beforePlayerInteraction (PrePlayerInteractEvent)
    // onRunTick                 → transformer onRunTick (PreInputEvent)
    // injectClickMouse          → transformer onClickMouse (PreAttackEvent + ClickMouseEvent)
    // injectRightClickMouse     → transformer onRightClickMouse (RightClickMouseEvent)
    // onRunTickStart            → transformer onRunTickHead (GameTickEvent)
    // onRunTickAfterRightClickDelay → transformer afterRightClickDelay (RightClickDelayTickEvent)
    // onDisplayGuiScreen        → transformer onDisplayGuiScreen (GuiUpdateEvent)
    // changeCurrentItem (INVOKE redirect) → transformer changeCurrentItem (PreSlotScrollEvent)
    // Having both fire the same events caused every event to fire TWICE per tick.

}
