package mindless.module.impl.combat;

import mindless.event.PostUpdateEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ReflectionUtils;
import mindless.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Autoblock extends Module {
    private final SliderSetting hurtTime;
    private boolean blocking;
    private boolean reblockPending;

    public Autoblock() {
        super("Autoblock", "Cycles sword blocking after your hurt time reaches the configured tick.", category.combat, 0);
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
        return isEnabled();
    }

    public boolean allowsNoSlow() {
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreUpdate(PreUpdateEvent event) {
        if (!canBlock()) {
            stopBlocking();
            return;
        }
        if (!blocking && !reblockPending) {
            sendBlock();
        }
        syncVisual();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        if (!reblockPending) {
            return;
        }
        if (canBlock()) {
            sendBlock();
        }
        else {
            stopBlocking();
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
        if (!canBlock()) {
            stopBlocking();
            return;
        }
        send(unblockPacket());
        blocking = false;
        reblockPending = true;
        ReflectionUtils.setItemInUse(false);
    }

    private boolean canBlock() {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.isDead
                || !Utils.holdingSword() || mc.thePlayer.hurtTime > (int) hurtTime.getInput()) {
            return false;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        }
        if (ModuleManager.killAura == null || !ModuleManager.killAura.isEnabled()) {
            return false;
        }
        EntityLivingBase target = KillAura.attackingEntity != null ? KillAura.attackingEntity : KillAura.target;
        return target != null && !target.isDead && target.getHealth() > 0.0f;
    }

    private void sendBlock() {
        if (!Utils.holdingSword()) {
            return;
        }
        send(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        blocking = true;
        reblockPending = false;
        syncVisual();
    }

    private void stopBlocking() {
        if (blocking && Utils.nullCheck()) {
            send(unblockPacket());
        }
        blocking = false;
        reblockPending = false;
        ReflectionUtils.setItemInUse(false);
    }

    private void syncVisual() {
        ReflectionUtils.setItemInUse(blocking);
    }

    private C07PacketPlayerDigging unblockPacket() {
        return new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN);
    }

    private void send(Packet<?> packet) {
        if (!Utils.nullCheck()) {
            return;
        }
        mc.thePlayer.sendQueue.addToSendQueue(packet);
    }
}
