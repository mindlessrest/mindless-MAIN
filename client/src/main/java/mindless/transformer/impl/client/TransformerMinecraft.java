package mindless.transformer.impl.client;

import mindless.alt.AltSessionController;
import mindless.event.ClickMouseEvent;
import mindless.event.GameTickEvent;
import mindless.event.GuiUpdateEvent;
import mindless.event.PreInputEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.RightClickDelayTickEvent;
import mindless.event.RightClickMouseEvent;
import mindless.event.PreSlotScrollEvent;
import mindless.event.PreAttackEvent;
import mindless.event.AttackEvent;
import mindless.helper.RotationHelper;
import mindless.module.impl.player.DelayRemover;
import mindless.runtime.LunarEventBridge;
import mindless.utility.Utils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.Session;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@CTransformer(Minecraft.class)
public class TransformerMinecraft {
    @CShadow
    private int leftClickCounter;

    @CInline
    @CInject(method = "getSession", target = @CTarget("RETURN"),
            cancellable = true)
    private void onGetSession(InjectionCallback ci) {
        Object returned = ci.getReturnValue();
        Session vanillaSession = returned instanceof Session
                ? (Session) returned : null;
        Session resolved = AltSessionController.resolveSession(vanillaSession);
        if (resolved != null && resolved != vanillaSession) {
            ci.setReturnValue(resolved);
        }
    }

    @CInline
    @CInject(method = "runTick", target = @CTarget("HEAD"))
    private void onRunTickHead(InjectionCallback ci) {
        LunarEventBridge.postClientTick(TickEvent.Phase.START);
        Utils.advanceBaseClientTick();
        MinecraftForge.EVENT_BUS.post(new GameTickEvent());
    }

    @CInline
    @CInject(method = "runTick", target = @CTarget("RETURN"))
    private void onRunTickTail(InjectionCallback ci) {
        LunarEventBridge.postClientTick(TickEvent.Phase.END);
    }

    @CInline
    @CRedirect(method = "runTick",
            target = @CTarget(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", optional = true))
    private boolean bridgeLunarMouseEvents() {
        return LunarEventBridge.nextMouseEvent();
    }

    @CInline
    @CInject(method = "runTick",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V",
                    shift = CTarget.Shift.BEFORE,
                    optional = true))
    private void beforeMouseOver(InjectionCallback ci) {
        RotationHelper.get().updateServerRotations();
    }

    @CInline
    @CInject(method = "runTick",
            target = @CTarget(value = "FIELD",
                    target = "Lnet/minecraft/client/settings/GameSettings;chatVisibility:Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;",
                    optional = true))
    private void beforePlayerInteraction(InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new PrePlayerInteractEvent());
    }

    @CInline
    @CInject(method = "clickMouse", target = @CTarget("HEAD"), cancellable = true)
    private void onClickMouse(InjectionCallback ci) {
        if (DelayRemover.shouldRemoveHitDelay()) this.leftClickCounter = 0;
        if (Utils.shouldSuppressManualClicksForModulePlacementTick()) {
            ci.setCancelled(true);
            return;
        }
        Minecraft mc = (Minecraft) (Object) this;
        PreAttackEvent preAttackEvent = new PreAttackEvent(mc.objectMouseOver);
        MinecraftForge.EVENT_BUS.post(preAttackEvent);
        if (preAttackEvent.isCanceled()) {
            ci.setCancelled(true);
            return;
        }
        ClickMouseEvent clickEvent = new ClickMouseEvent();
        MinecraftForge.EVENT_BUS.post(clickEvent);
        if (clickEvent.isCanceled()) {
            ci.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "clickMouse", target = @CTarget("RETURN"))
    private void clearClickDelayAfterClick(InjectionCallback ci) {
        if (DelayRemover.shouldRemoveHitDelay()) this.leftClickCounter = 0;
    }

    @CInline
    @CInject(method = "rightClickMouse", target = @CTarget("HEAD"), cancellable = true)
    private void onRightClickMouse(InjectionCallback ci) {
        if (Utils.shouldSuppressManualClicksForModulePlacementTick()) {
            ci.setCancelled(true);
            return;
        }
        RightClickMouseEvent event = new RightClickMouseEvent();
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.setCancelled(true);
    }

    @CInline
    @CInject(method = "runTick",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V",
                    ordinal = 0,
                    shift = CTarget.Shift.BEFORE,
                    optional = true))
    private void afterRightClickDelay(InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new RightClickDelayTickEvent());
    }

    @CInline
    @CInject(method = "runTick",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    ordinal = 2,
                    optional = true))
    private void onRunTick(InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new PreInputEvent());
    }

    @CInline
    @CInject(method = "displayGuiScreen", target = @CTarget("HEAD"))
    public void onDisplayGuiScreen(GuiScreen guiScreen, InjectionCallback ci) {
        Minecraft mc = (Minecraft) (Object) this;
        GuiScreen previousGui = mc.currentScreen;
        GuiScreen setGui = guiScreen;
        boolean opened = setGui != null;
        if (!opened) setGui = previousGui;
        MinecraftForge.EVENT_BUS.post(new GuiUpdateEvent(setGui, opened));
    }

    @CInline
    @CRedirect(method = "runTick",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/InventoryPlayer;changeCurrentItem(I)V",
                    optional = true))
    public void changeCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        PreSlotScrollEvent event = new PreSlotScrollEvent(slot, inventoryPlayer.currentItem);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;
        inventoryPlayer.changeCurrentItem(slot);
    }
}
