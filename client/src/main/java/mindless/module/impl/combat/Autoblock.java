package mindless.module.impl.combat;

import mindless.event.PostUpdateEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class Autoblock extends Module {
    private final SliderSetting hurtTime;
    private boolean blocking;
    private boolean reblockPending;

    public Autoblock() {
        super("Autoblock", "Allows normal sword blocking once your hurt time reaches the configured tick.", category.combat, 0);
        this.registerSetting(hurtTime = new SliderSetting("Hurt time", " tick", 3.0, 0.0, 10.0, 1.0));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        blocking = false;
        reblockPending = false;
        ReflectionUtils.setItemInUse(false);
    }

    @Override
    public void onDisable() {
        stopBlocking();
    }

    public boolean isActive() {
        return isEnabled() && blocking;
    }

    public boolean isOperational() {
        return isEnabled() && (ModuleManager.myauBlock == null || !ModuleManager.myauBlock.isOperational());
    }

    public boolean allowsNoSlow() {
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!isOperational()) {
            resetBlocking();
            return;
        }
        if (canBlock()) {
            if (!reblockPending) {
                startBlocking();
            }
        }
        else {
            resetBlocking();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent event) {
        if (!blocking || !(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        stopBlocking();
        reblockPending = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        if (!reblockPending) {
            return;
        }
        reblockPending = false;
        if (canBlock()) {
            startBlocking();
        }
    }

    private boolean canBlock() {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.isDead
                || !Utils.holdingSword() || mc.thePlayer.hurtTime > (int) hurtTime.getInput()) {
            return false;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        }
        EntityPlayer target = CombatTargeting.findTarget(16.0, true);
        boolean auraAttacking = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && !ModuleManager.killAura.isRequireMouseDown();
        return target != null && (Mouse.isButtonDown(0) || auraAttacking);
    }

    private void startBlocking() {
        if (blocking || !Utils.holdingSword()) {
            return;
        }
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, true);
        KeyBinding.onTick(keyCode);
        blocking = true;
        ReflectionUtils.setItemInUse(true);
    }

    private void stopBlocking() {
        if (!blocking || mc.gameSettings == null) {
            return;
        }
        boolean physicalUse = Mouse.isButtonDown(1) && mc.currentScreen == null;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), physicalUse);
        blocking = false;
        ReflectionUtils.setItemInUse(physicalUse && Utils.nullCheck() && Utils.holdingSword());
    }

    private void resetBlocking() {
        stopBlocking();
        reblockPending = false;
    }
}
