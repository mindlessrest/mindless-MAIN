package mindless.module.impl.world;

import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

public class AntiVoid extends Module {
    private static final String[] MODES = { "Blink", "Toggle Stuck", "Toggle Scaffold" };

    private final SliderSetting mode;
    private final SliderSetting fallDistance;

    private double savedX, savedY, savedZ;
    private boolean falling;
    private boolean toggled;
    private final List<C03PacketPlayer> heldPackets = new ArrayList<>();

    public AntiVoid() {
        super("Anti Void", "Catches you before you fall into the void.", category.world);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(fallDistance = new SliderSetting("Fall distance", " blocks", 4.0, 1.0, 10.0, 0.5));
    }

    @Override
    public void onDisable() {
        releasePackets();
        if (toggled) untoggle();
        falling = false;
        toggled = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.thePlayer.capabilities.allowFlying || mc.thePlayer.capabilities.isFlying) return;

        boolean overVoid = isOverVoid();

        if (!falling && overVoid && !mc.thePlayer.onGround) {
            falling = true;
            savedX = mc.thePlayer.posX;
            savedY = mc.thePlayer.posY;
            savedZ = mc.thePlayer.posZ;
        }

        if (falling && (mc.thePlayer.onGround || !overVoid)) {
            releasePackets();
            if (toggled) untoggle();
            falling = false;
            toggled = false;
            return;
        }

        if (!falling) return;

        double fell = savedY - mc.thePlayer.posY;
        if (fell < fallDistance.getInput()) return;

        int m = (int) mode.getInput();
        switch (m) {
            case 0:
                if (!heldPackets.isEmpty()) {
                    heldPackets.clear();
                    mc.thePlayer.setPosition(savedX, savedY, savedZ);
                    mc.thePlayer.motionX = 0;
                    mc.thePlayer.motionY = 0;
                    mc.thePlayer.motionZ = 0;
                    falling = false;
                }
                break;
            case 1:
                if (!toggled && ModuleManager.stasis != null && !ModuleManager.stasis.isEnabled()) {
                    ModuleManager.stasis.setEnabled(true);
                    toggled = true;
                }
                break;
            case 2:
                if (!toggled && ModuleManager.scaffold != null && !ModuleManager.scaffold.isEnabled()) {
                    ModuleManager.scaffold.setEnabled(true);
                    toggled = true;
                }
                break;
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (!falling || (int) mode.getInput() != 0) return;
        if (!(e.getPacket() instanceof C03PacketPlayer)) return;
        heldPackets.add((C03PacketPlayer) e.getPacket());
        e.setCanceled(true);
    }

    private void releasePackets() {
        if (heldPackets.isEmpty()) return;
        for (C03PacketPlayer packet : heldPackets) {
            mc.thePlayer.sendQueue.getNetworkManager().sendPacket(packet);
        }
        heldPackets.clear();
    }

    private void untoggle() {
        int m = (int) mode.getInput();
        if (m == 1 && ModuleManager.stasis != null && ModuleManager.stasis.isEnabled()) {
            ModuleManager.stasis.setEnabled(false);
        } else if (m == 2 && ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            ModuleManager.scaffold.setEnabled(false);
        }
        toggled = false;
    }

    private boolean isOverVoid() {
        double px = mc.thePlayer.posX;
        double pz = mc.thePlayer.posZ;
        for (double y = mc.thePlayer.posY; y > 0; y--) {
            BlockPos pos = new BlockPos(px, y, pz);
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (block.getMaterial().isSolid()) return false;
        }
        return true;
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }
}
