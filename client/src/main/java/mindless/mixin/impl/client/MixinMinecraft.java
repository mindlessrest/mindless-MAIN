package mindless.mixin.impl.client;

import mindless.event.*;
import mindless.Mindless;
import mindless.lag.service.PacketDelayService;
import mindless.module.ModuleManager;
import mindless.module.impl.player.BedAura;
import mindless.module.impl.render.Freelook;
import mindless.module.impl.player.FastMine;
import org.objectweb.asm.Opcodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
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
    @Inject(method = "runTick", at = @At("HEAD"))
    private void auraBaseTick(CallbackInfo ci) {
        mindless.utility.Utils.advanceBaseClientTick();
        MinecraftForge.EVENT_BUS.post(new GameTickEvent());
    }

    @Inject(method = "clickMouse", at = @At("HEAD"), cancellable = true)
    private void auraAttackInput(CallbackInfo ci) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressClicks()) { ci.cancel(); return; }
        Minecraft mc = (Minecraft) (Object) this;
        PreAttackEvent pre = new PreAttackEvent(mc.objectMouseOver);
        MinecraftForge.EVENT_BUS.post(pre);
        if (pre.isCanceled()) { ci.cancel(); return; }
        ClickMouseEvent event = new ClickMouseEvent();
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.cancel();
    }

    @Inject(method = "rightClickMouse", at = @At("HEAD"), cancellable = true)
    private void auraUseInput(CallbackInfo ci) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.shouldSuppressClicks()) { ci.cancel(); return; }
        RightClickMouseEvent event = new RightClickMouseEvent();
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.cancel();
    }

    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void mindless$discardDelayedPacketsBeforeWorldUnload(
            WorldClient nextWorld, String loadingMessage, CallbackInfo ci
    ) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (ModuleManager.bedAura != null) ModuleManager.bedAura.onWorldChange();
        if (minecraft.theWorld != null && minecraft.theWorld != nextWorld && Mindless.packetDelayService != null)
            Mindless.packetDelayService.advanceWorld();
        if (ModuleManager.killAura != null) ModuleManager.killAura.onWorldChange();
        if (nextWorld != null || minecraft.theWorld == null) return;
        if (ModuleManager.backtrack != null) ModuleManager.backtrack.onWorldUnload();
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null) service.onClientWorldUnload();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V", shift = At.Shift.AFTER))
    public void onRunTickMouseOver(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PostMouseSelectionEvent());
    }
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V"))
    private void beforeMouseOver(CallbackInfo ci) {
        mindless.helper.RotationHelper.get().updateServerRotations();
    }

    @Inject(method = "runTick", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/settings/GameSettings;chatVisibility:Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;"))
    private void beforePlayerInteraction(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PrePlayerInteractEvent());
        if (ModuleManager.killAura != null) ModuleManager.killAura.beforePlayerInteraction();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    public void onRunGameLoop(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new RunGameLoopEvent());
    }
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
        if (bedAura != null && (bedAura.shouldOverrideFastMine() || bedAura.shouldSuppressControllerMining())) {
            return;
        }
        FastMine fm = ModuleManager.fastMine;
        if (fm != null) {
            fm.tickPassiveBlockHitDecay((Minecraft) (Object) this);
        }
    }
    @Redirect(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;thirdPersonView:I", opcode = Opcodes.PUTFIELD))
    private void onSetThirdPersonView(GameSettings gameSettings, int value) {
        if (ModuleManager.freelook != null && Freelook.perspectiveToggled) {
            ModuleManager.freelook.resetPerspective();
        } else {
            gameSettings.thirdPersonView = value;
        }
    }
    @Redirect(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/InventoryPlayer;currentItem:I", opcode = Opcodes.PUTFIELD))
    private void onSetCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        SlotUpdateEvent e = new SlotUpdateEvent(slot);
        MinecraftForge.EVENT_BUS.post(e);
        if (e.isCanceled()) {
            return;
        }
        inventoryPlayer.currentItem = slot;
    }

}
