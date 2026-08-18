package mindless.module.impl.other;

import com.mojang.authlib.GameProfile;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;

public class Debug extends Module {
    private ButtonSetting debugBlock;
    private ButtonSetting debugAttacked;
    private ButtonSetting alertPost;
    private ButtonSetting spawnDummy;

    private final ArrayDeque<String> postQueue = new ArrayDeque<>();
    public EntityOtherPlayerMP fakeEntity = null;
    private boolean sentFlying;

    public Debug() {
        super("Debug", category.other);
        this.registerSetting(debugBlock = new ButtonSetting("Debug block", true));
        this.registerSetting(debugAttacked = new ButtonSetting("Debug attacked", true));
        this.registerSetting(alertPost = new ButtonSetting("Alert post", false));
        this.registerSetting(spawnDummy = new ButtonSetting("Spawn dummy", true));
    }

    @Override
    public void onDisable() {
        resetState();
        if (fakeEntity != null) {
            mc.theWorld.removeEntity(fakeEntity);
            fakeEntity = null;
        }
    }

    @Override
    public void onEnable() {
        if (!Utils.nullCheck()) return;
        if (spawnDummy.isToggled()) {
            fakeEntity = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(mc.thePlayer.getUniqueID(), "Dummy"));
            fakeEntity.copyLocationAndAnglesFrom(mc.thePlayer);
            mc.theWorld.addEntityToWorld(-8008, fakeEntity);
            fakeEntity.inventory.armorInventory[0] = new ItemStack(Items.golden_helmet);
            fakeEntity.setCurrentItemOrArmor(0, new ItemStack(Blocks.wool));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSendPacket(SendPacketEvent e) {
        if (!alertPost.isToggled()) { resetState(); return; }
        if (!Utils.nullCheck()) { resetState(); return; }
        if (e.isCanceled()) return;
        Packet<?> packet = e.getPacket();
        if (packet == null) return;

        if (isTickPacket(packet)) { postQueue.clear(); sentFlying = true; return; }
        if (isTransactionPacket(packet)) {
            if (sentFlying && !postQueue.isEmpty()) Utils.sendMessage("&7Post packet: &b" + postQueue.peekFirst());
            postQueue.clear(); sentFlying = false; return;
        }
        if (sentFlying && isPostCheckPacket(packet)) postQueue.add(packet.getClass().getSimpleName());
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (!debugBlock.isToggled()) return;

        MovingObjectPosition mouse = RotationUtils.rayCast(mc.playerController.getBlockReachDistance(), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false);
        if (mouse == null || mouse.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mouse.getBlockPos() == null) return;

        BlockPos pos = mouse.getBlockPos();
        Block block = BlockUtils.getBlock(pos);
        if (block == null || block == Blocks.air) return;

        IBlockState state = mc.theWorld.getBlockState(pos);
        mc.fontRendererObj.drawStringWithShadow("\u00a77BlockPos: \u00a7b" + pos.getX() + "\u00a77, \u00a7b" + pos.getY() + "\u00a77, \u00a7b" + pos.getZ(), 30, 20, -1);
        mc.fontRendererObj.drawStringWithShadow("\u00a77Unlocalized Name: \u00a7b" + block.getUnlocalizedName(), 30, 30, -1);
        net.minecraft.util.ResourceLocation regLoc = (net.minecraft.util.ResourceLocation) net.minecraft.block.Block.blockRegistry.getNameForObject(block);
        mc.fontRendererObj.drawStringWithShadow("\u00a77Registry Name: \u00a7b" + (regLoc != null ? regLoc.toString() : "unknown"), 30, 40, -1);

        int y = 50;
        for (IProperty<?> property : block.getBlockState().getProperties()) {
            Class<?> valueClass = property.getValueClass();
            String propName = property.getName();
            Object currentValue = state.getValue(property);
            Collection<?> allowedValues = property.getAllowedValues();
            mc.fontRendererObj.drawStringWithShadow("\u00a77Property: \u00a7b" + propName + " \u00a77= \u00a7b" + currentValue, 30, y, -1);
            y += 10;
            mc.fontRendererObj.drawStringWithShadow("\u00a77Allowed: \u00a7b" + allowedValues, 30, y, -1);
            y += 15;
        }
    }

    public boolean shouldDebugAttacked() { return debugAttacked.isToggled(); }

    private static boolean isPostCheckPacket(Packet<?> packet) {
        return packet instanceof C13PacketPlayerAbilities || packet instanceof C09PacketHeldItemChange
                || packet instanceof C02PacketUseEntity || packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C07PacketPlayerDigging || packet instanceof C0APacketAnimation
                || packet instanceof C0EPacketClickWindow || packet instanceof C0BPacketEntityAction;
    }

    private static boolean isTickPacket(Packet<?> packet) { return packet instanceof C03PacketPlayer; }
    private static boolean isTransactionPacket(Packet<?> packet) { return packet instanceof C0FPacketConfirmTransaction; }
    private void resetState() { postQueue.clear(); sentFlying = false; }
}
