package mindless.module.impl.movement;

import mindless.event.PostUpdateEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Stasis extends Module {

    private double frozenX, frozenY, frozenZ;
    private boolean stored;

    public Stasis() {
        super("Air Stuck", category.movement);
    }

    @Override
    public void onEnable() {
        stored = false;
        if (Utils.nullCheck()) store();
    }

    @Override
    public void onDisable() {
        stored = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreInput(PrePlayerInputEvent e) {
        // Kill all movement input so physics never applies velocity
        e.setForward(0);
        e.setStrafe(0);
        e.setJump(false);
        e.setSneak(false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent e) {
        if (!Utils.nullCheck()) return;
        if (!stored) store();

        // Hard-reset after physics ran
        mc.thePlayer.motionX = 0;
        mc.thePlayer.motionY = 0;
        mc.thePlayer.motionZ = 0;
        mc.thePlayer.posX = frozenX;
        mc.thePlayer.posY = frozenY;
        mc.thePlayer.posZ = frozenZ;
        mc.thePlayer.lastTickPosX = frozenX;
        mc.thePlayer.lastTickPosY = frozenY;
        mc.thePlayer.lastTickPosZ = frozenZ;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C03PacketPlayer)
            e.setCanceled(true);
    }

    @SubscribeEvent
    public void onReceivePacket(mindless.event.ReceivePacketEvent e) {
        if (e.getPacket() instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) e.getPacket();
            frozenX = p.getX();
            frozenY = p.getY();
            frozenZ = p.getZ();
        }
    }

    private void store() {
        frozenX = mc.thePlayer.posX;
        frozenY = mc.thePlayer.posY;
        frozenZ = mc.thePlayer.posZ;
        mc.thePlayer.motionX = 0;
        mc.thePlayer.motionY = 0;
        mc.thePlayer.motionZ = 0;
        stored = true;
    }

    public boolean shouldFreezeLocalMovement() {
        return isEnabled() && Utils.nullCheck();
    }
}
