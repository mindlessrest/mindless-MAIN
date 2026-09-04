package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.BlockListSetting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RenderUtils;
import mindless.utility.SharedBlockHighlightCache;
import mindless.utility.Utils;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class BlockESP extends Module {
    private final BlockListSetting blockList;
    private final ButtonSetting outline;
    private final ButtonSetting shade;
    private final SliderSetting range;
    private final SliderSetting maxRenders;
    private final SliderSetting scanSpeed;

    private int prevListHash;

    public BlockESP() {
        super("Block ESP", "Shows your chosen blocks through walls.", category.render);
        this.registerSetting(blockList = new BlockListSetting("Blocks"));
        this.registerSetting(outline = new ButtonSetting("Outline", true));
        this.registerSetting(shade = new ButtonSetting("Shade", false));
        this.registerSetting(range = new SliderSetting("Range", 64, 8, 256, 8));
        this.registerSetting(maxRenders = new SliderSetting("Max renders", 2048, 128, 8192, 128));
        this.registerSetting(scanSpeed = new SliderSetting("Scan speed", 8, 1, 32, 1));
    }

    @Override
    public void onEnable() {
        SharedBlockHighlightCache cache = SharedBlockHighlightCache.get();
        cache.attachBlockList(blockList);
        prevListHash = blockList.getBlocks().hashCode();
        if (!blockList.getBlocks().isEmpty()) {
            cache.enqueueLoadedChunks();
        }
    }

    @Override
    public void onDisable() {
        SharedBlockHighlightCache.get().detachBlockList();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) return;
        int h = blockList.getBlocks().hashCode();
        if (h != prevListHash) {
            prevListHash = h;
            SharedBlockHighlightCache.get().onBlockListSettingsChanged();
        }
    }

    public int getScanSpeedBudget() {
        return isEnabled() && !blockList.getBlocks().isEmpty() ? (int) scanSpeed.getInput() : 0;
    }

    @Override
    public String getInfo() {
        int total = SharedBlockHighlightCache.get().totalBlockList();
        return total > 0 ? String.valueOf(total) : "";
    }

    /** Chunks the scan has to reach for this module's range to mean anything. */
    public int getScanRadiusChunks() {
        // Same gate as the budget: with nothing selected there is nothing to look for, so do not
        // make the shared scan walk a wider ring on this module's behalf.
        if (!isEnabled() || blockList.getBlocks().isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(range.getInput() / 16.0) + 1;
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent ev) {
        SharedBlockHighlightCache cache = SharedBlockHighlightCache.get();
        if (!cache.anyConsumerActive() || !Utils.nullCheck()) return;
        double rangeSq = range.getInput() * range.getInput();
        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY;
        double pz = mc.thePlayer.posZ;
        boolean doOutline = outline.isToggled();
        boolean doShade = shade.isToggled();
        int max = (int) maxRenders.getInput();
        int rendered = 0;

        for (Map.Entry<Long, Set<BlockPos>> entry : cache.entriesBlockList()) {
            if (rendered >= max) break;
            for (BlockPos pos : entry.getValue()) {
                if (rendered >= max) break;
                double dx = pos.getX() + 0.5 - px;
                double dy = pos.getY() + 0.5 - py;
                double dz = pos.getZ() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
                AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
                if (box != null && !RenderUtils.isInViewFrustum(box)) continue;
                EnumSet<EnumFacing> visibleFaces = EnumSet.noneOf(EnumFacing.class);
                for (EnumFacing f : EnumFacing.VALUES) {
                    if (!cache.containsBlockList(pos.offset(f))) visibleFaces.add(f);
                }
                if (visibleFaces.isEmpty()) continue;
                IBlockState state = mc.theWorld.getBlockState(pos);
                MapColor mapColor = state.getBlock().getMapColor(state);
                int rgb = mapColor != null ? (0xFF << 24 | mapColor.colorValue) : 0xFFFF0000;
                RenderUtils.renderBlockShape(pos, state, rgb, doOutline, doShade, visibleFaces);
                rendered++;
            }
        }
    }
}
