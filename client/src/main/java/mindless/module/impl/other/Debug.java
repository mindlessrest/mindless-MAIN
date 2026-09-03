package mindless.module.impl.other;

import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class Debug extends Module {
    private ButtonSetting debugBlock;
    public ButtonSetting debugBPS;
    public ButtonSetting debugEntities;
    private ButtonSetting debugAttacked;
    private ButtonSetting debugHeld;
    private ButtonSetting alertPost;
    private ButtonSetting spawnDummy;

    private static final int HELD_MAX_LINE_WIDTH = 300;
    private static final int HELD_LINE_HEIGHT = 10;

    private final ArrayDeque<String> postQueue = new ArrayDeque<>();
    public EntityOtherPlayerMP fakeEntity = null;
    private boolean sentFlying;

    public Debug() {
        super("Debug", "Developer tools for entities and events.", category.other);
        this.registerSetting(spawnDummy = new ButtonSetting("Spawn dummy", true));
        this.registerSetting(new DescriptionSetting("Visuals"));
        this.registerSetting(alertPost = new ButtonSetting("Alert post", false));
        this.registerSetting(debugBlock = new ButtonSetting("Debug block", true));
        this.registerSetting(debugHeld = new ButtonSetting("Debug held", false));
        this.registerSetting(debugBPS = new ButtonSetting("Show speed", true));
        this.registerSetting(debugEntities = new ButtonSetting("Render all entities", false));
        this.registerSetting(debugAttacked = new ButtonSetting("Debug attacked", true));
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
        if (ev.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }

        if (debugBlock.isToggled()) {
            renderBlockDebug();
        }

        if (debugHeld.isToggled()) {
            renderHeldDebug();
        }
    }

    private void renderBlockDebug() {
        MovingObjectPosition mouse = RotationUtils.rayCast(mc.playerController.getBlockReachDistance(), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false);
        if (mouse == null || mouse.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mouse.getBlockPos() == null) {
            return;
        }

        BlockPos pos = mouse.getBlockPos();
        Block block = BlockUtils.getBlock(pos);
        if (block == null || block == Blocks.air) {
            return;
        }

        IBlockState state = mc.theWorld.getBlockState(pos);

        mc.fontRendererObj.drawStringWithShadow("§7BlockPos: §b" + pos.getX() + "§7, §b" + pos.getY() + "§7, §b" + pos.getZ(), 30, 20, -1);
        mc.fontRendererObj.drawStringWithShadow("§7HitVec: §b" + Utils.round(mouse.hitVec.xCoord, 3) + "§7, §b" + Utils.round(mouse.hitVec.yCoord, 3) + "§7, §b" + Utils.round(mouse.hitVec.zCoord, 3), 30, 30, -1);
        mc.fontRendererObj.drawStringWithShadow("§7Face: §b" + mouse.sideHit.name(), 30, 40, -1);
        mc.fontRendererObj.drawStringWithShadow("§7Unlocalized Name: §b" + block.getUnlocalizedName(), 30, 50, -1);
        mc.fontRendererObj.drawStringWithShadow("§7Registry Name: §b" + block.getRegistryName(), 30, 60, -1);

        int y = 70;

        for (IProperty<?> property : block.getBlockState().getProperties()) {
            Class<?> valueClass = property.getValueClass();
            String propName = property.getName();
            Object currentValue = state.getValue(property);
            Collection<?> allowedValues = property.getAllowedValues();

            mc.fontRendererObj.drawStringWithShadow("§7Property Name: §b" + propName, 30, y, -1);
            y += 10;
            mc.fontRendererObj.drawStringWithShadow("§7Value Type: §b" + valueClass.getName(), 30, y, -1);
            y += 10;

            if (currentValue != null) {
                mc.fontRendererObj.drawStringWithShadow("§7Current Value: §b" + currentValue, 30, y, -1);
                y += 10;

                if (valueClass.isEnum()) {
                    Enum<?> enumValue = (Enum<?>) currentValue;
                    mc.fontRendererObj.drawStringWithShadow("§7Current Enum Name: §b" + enumValue.name(), 30, y, -1);
                    y += 10;
                    mc.fontRendererObj.drawStringWithShadow("§7Current Enum Ordinal: §b" + enumValue.ordinal(), 30, y, -1);
                    y += 10;
                }
            }

            mc.fontRendererObj.drawStringWithShadow("§7Allowed Values: §b" + allowedValues, 30, y, -1);
            y += 10;

            if (valueClass.isEnum()) {
                Object[] enumConstants = valueClass.getEnumConstants();
                if (Arrays.toString(enumConstants).equals(allowedValues.toString())) {
                    break;
                }
                mc.fontRendererObj.drawStringWithShadow("§7Enum Constants: §b" + Arrays.toString(enumConstants), 30, y, -1);
                y += 10;
            }

            y += 5;
        }
    }

    private void renderHeldDebug() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) {
            return;
        }
        List<String> lines = buildHeldDebugLines(stack);
        ScaledResolution resolution = new ScaledResolution(mc);
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
        }

        int x = Math.max(30, resolution.getScaledWidth() - width - 30);
        int y = 20;
        for (String line : lines) {
            mc.fontRendererObj.drawStringWithShadow(line, x, y, -1);
            y += HELD_LINE_HEIGHT;
        }
    }

    private List<String> buildHeldDebugLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        Item item = stack.getItem();
        addHeldLine(lines, "§7Display Name: §b" + stack.getDisplayName());
        addHeldLine(lines, "§7Registry Name: §b" + Item.itemRegistry.getNameForObject(item));
        addHeldLine(lines, "§7Numeric ID: §b" + Item.getIdFromItem(item));
        addHeldLine(lines, "§7Metadata: §b" + stack.getMetadata());
        addHeldLine(lines, "§7Unlocalized Name: §b" + stack.getUnlocalizedName());
        addHeldLine(lines, "§7Class: §b" + item.getClass().getName());
        addHeldLine(lines, "§7Held Slot: §b" + (mc.thePlayer.inventory.currentItem + 1));
        addHeldLine(lines, "§7Stack Size: §b" + stack.stackSize + "§7/§b" + stack.getMaxStackSize());
        addHeldLine(lines, "§7Rarity: §b" + stack.getRarity().rarityName);
        addHeldLine(lines, "§7Damage: §b" + stack.getItemDamage() + "§7/§b" + stack.getMaxDamage());
        addHeldLine(lines, "§7Damageable: §b" + stack.isItemStackDamageable());
        if (stack.isItemStackDamageable()) {
            addHeldLine(lines, "§7Durability Remaining: §b" + (stack.getMaxDamage() - stack.getItemDamage()));
        }
        addHeldLine(lines, "§7Repair Cost: §b" + stack.getRepairCost());
        addHeldLine(lines, "§7Enchanted: §b" + stack.isItemEnchanted());
        addHeldLine(lines, "§7Enchantability: §b" + item.getItemEnchantability(stack));
        addHeldLine(lines, "§7Has Effect: §b" + stack.hasEffect());
        addHeldLine(lines, "§7Use Action: §b" + stack.getItemUseAction());
        addHeldLine(lines, "§7Max Use Duration: §b" + stack.getMaxItemUseDuration());

        Multimap<String, AttributeModifier> modifiers = stack.getAttributeModifiers();
        addHeldLine(lines, "§7Attribute Modifiers: §b" + modifiers.size());
        if (modifiers.isEmpty()) {
            addHeldLine(lines, "§7Attributes: §bNone");
        }
        else {
            for (Map.Entry<String, AttributeModifier> entry : modifiers.entries()) {
                AttributeModifier modifier = entry.getValue();
                addHeldLine(lines, "§7Attribute Name: §b" + entry.getKey());
                addHeldLine(lines, "§7Modifier Name: §b" + modifier.getName());
                addHeldLine(lines, "§7Amount: §b" + Utils.round(modifier.getAmount(), 6));
                addHeldLine(lines, "§7Operation: §b" + modifier.getOperation());
                addHeldLine(lines, "§7UUID: §b" + modifier.getID());
            }
        }

        addHeldLine(lines, "§7NBT: §b" + (stack.hasTagCompound() ? stack.getTagCompound() : "None"));
        return lines;
    }

    private void addHeldLine(List<String> lines, String line) {
        List<String> wrapped = mc.fontRendererObj.listFormattedStringToWidth(line, HELD_MAX_LINE_WIDTH);
        if (wrapped.isEmpty()) {
            lines.add("");
        }
        else {
            lines.addAll(wrapped);
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
