package mindless.placement;

import java.util.IdentityHashMap;
import java.util.Map;
import mindless.utility.Utils;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

public final class PlacementRuntime {
    private static EntityPlayerSP hotbarPlayer;
    private static Object hotbarWorld;
    private static PlacementLease.HotbarState hotbarState;
    private static volatile PendingHotbar pendingHotbar;
    private static final Map<KeyBinding, PlacementLease.InputState> INPUTS = new IdentityHashMap<>();

    private PlacementRuntime() {
    }

    public static synchronized PlacementLease.HotbarState hotbar() {
        final EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        final Object world = Minecraft.getMinecraft().theWorld;
        if (player != hotbarPlayer || world != hotbarWorld || hotbarState == null) {
            hotbarPlayer = player;
            hotbarWorld = world;
            hotbarState = new PlacementLease.HotbarState() {
                @Override
                public int getSelectedSlot() {
                    return player == null ? -1 : player.inventory.currentItem;
                }

                @Override
                public void setSelectedSlot(int slot) {
                    if (player != null && Minecraft.getMinecraft().thePlayer == player
                            && Minecraft.getMinecraft().theWorld == world) {
                        player.inventory.currentItem = slot;
                        discardExpiredHotbar();
                        if (Minecraft.getMinecraft().playerController == null
                                || AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(Minecraft.getMinecraft().playerController) != slot
                                || pendingHotbar != null) {
                            if (pendingHotbar == null) pendingHotbar = new PendingHotbar(player, world, slot);
                            retryHotbarSync();
                        }
                    }
                }
            };
        }
        return hotbarState;
    }

    public static boolean isHotbarSynchronized() {
        discardExpiredHotbar();
        return pendingHotbar == null;
    }

    public static void onHeldItemAccepted(int slot) {
        discardExpiredHotbar();
        PendingHotbar pending = pendingHotbar;
        if (pending != null && pending.slot == slot) pendingHotbar = null;
    }

    public static void retryHotbarSync() {
        discardExpiredHotbar();
        PendingHotbar pending = pendingHotbar;
        Minecraft mc = Minecraft.getMinecraft();
        if (pending == null || pending.attempts >= 20 || pending.lastTick == Utils.getBaseClientTick()
                || mc.playerController == null || mc.getNetHandler() == null) return;
        pending.lastTick = Utils.getBaseClientTick();
        pending.attempts++;
        C09PacketHeldItemChange packet = new C09PacketHeldItemChange(pending.slot);
        mc.getNetHandler().addToSendQueue(packet);
        if (CombatPacketState.wasAccepted(packet)) {
            AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, pending.slot);
            if (pendingHotbar == pending) pendingHotbar = null;
        }
    }

    private static void discardExpiredHotbar() {
        Minecraft mc = Minecraft.getMinecraft();
        PendingHotbar pending = pendingHotbar;
        if (pending != null && (pending.player != mc.thePlayer || pending.world != mc.theWorld
                || pending.player.inventory.currentItem != pending.slot)) pendingHotbar = null;
    }

    private static final class PendingHotbar {
        private final EntityPlayerSP player;
        private final Object world;
        private final int slot;
        private int attempts;
        private int lastTick = Integer.MIN_VALUE;

        private PendingHotbar(EntityPlayerSP player, Object world, int slot) {
            this.player = player;
            this.world = world;
            this.slot = slot;
        }
    }

    public static synchronized PlacementLease.InputState input(final KeyBinding keyBinding) {
        PlacementLease.InputState state = INPUTS.get(keyBinding);
        if (state == null) {
            state = new PlacementLease.InputState() {
                @Override
                public boolean isPressed() {
                    return keyBinding.isKeyDown();
                }

                @Override
                public boolean isPhysicallyPressed() {
                    return Utils.isBindDown(keyBinding);
                }

                @Override
                public void setPressed(boolean pressed) {
                    KeyBinding.setKeyBindState(keyBinding.getKeyCode(), pressed);
                }
            };
            INPUTS.put(keyBinding, state);
        }
        return state;
    }
}
