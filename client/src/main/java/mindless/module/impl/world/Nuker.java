package mindless.module.impl.world;

import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Break the blocks around you.
 *
 * The order matters more than the reach. Nearest-first looks like nothing in particular and
 * leaves a crater; Flatten works down a layer at a time, which is what you want when you are
 * clearing ground rather than digging a hole; and Down only touches what is beneath you.
 *
 * Deliberately distinct from Bed Aura, which is a different job: that one hunts a specific bed
 * with its own priority handling and mixin-level break-speed overrides. This one has no target,
 * only a radius.
 */
public class Nuker extends Module {
    private static final String[] MODES = new String[]{"Nearest", "Flatten", "Down"};
    private static final int MODE_NEAREST = 0;
    private static final int MODE_FLATTEN = 1;
    private static final int MODE_DOWN = 2;

    private final SliderSetting mode;
    private final SliderSetting range;
    private final SliderSetting perTick;
    private final ButtonSetting rotate;
    private final ButtonSetting onlyVisible;
    private final ButtonSetting swing;

    private final GroupSetting filterGroup;
    private final ButtonSetting ignoreLiquids;
    private final ButtonSetting ignoreUnbreakable;
    private final ButtonSetting ignoreContainers;

    private final ButtonSetting renderTarget;
    private final ColorSetting targetColor;

    /** The block currently being broken, so a rotation is not recomputed every frame. */
    private BlockPos current;
    private EnumFacing currentFace;

    public Nuker() {
        super("Nuker", "Breaks the blocks around you.", category.world, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_NEAREST, MODES));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 1.0, 6.0, 0.1));
        this.registerSetting(perTick = new SliderSetting("Blocks per tick", 1, 1, 8, 1));
        this.registerSetting(rotate = new ButtonSetting("Rotate", true));
        this.registerSetting(onlyVisible = new ButtonSetting("Only visible", true));
        this.registerSetting(swing = new ButtonSetting("Swing", true));

        this.registerSetting(filterGroup = new GroupSetting("Filter"));
        this.registerSetting(ignoreLiquids = new ButtonSetting(filterGroup, "Ignore liquids", true));
        this.registerSetting(ignoreUnbreakable = new ButtonSetting(filterGroup, "Ignore unbreakable", true));
        this.registerSetting(ignoreContainers = new ButtonSetting(filterGroup, "Ignore containers", true));

        this.registerSetting(renderTarget = new ButtonSetting("Render target", true));
        this.registerSetting(targetColor = new ColorSetting("Target color", 255, 80, 80, 140));
    }

    @Override
    public void onDisable() {
        current = null;
        currentFace = null;
        if (Utils.nullCheck()) {
            mc.playerController.resetBlockRemoving();
        }
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()].toLowerCase();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        List<BlockPos> targets = collectTargets();
        if (targets.isEmpty()) {
            current = null;
            currentFace = null;
            mc.playerController.resetBlockRemoving();
            return;
        }

        int budget = Math.max(1, (int) perTick.getInput());
        for (int i = 0; i < budget && i < targets.size(); i++) {
            BlockPos pos = targets.get(i);
            EnumFacing face = exposedFace(pos);
            if (face == null) {
                continue;
            }
            if (i == 0) {
                current = pos;
                currentFace = face;
            }
            mc.playerController.onPlayerDamageBlock(pos, face);
            if (swing.isToggled()) {
                mc.thePlayer.swingItem();
            }
        }
    }

    /**
     * Aim at the block being broken.
     *
     * Silent, like the rest of the client: the rotation goes out with the packet and the camera
     * is left alone, so the module does not fight the player for the mouse.
     */
    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        if (!rotate.isToggled() || current == null || currentFace == null || !Utils.nullCheck()) {
            return;
        }
        float[] rotations = rotationsTo(current, currentFace);
        float[] fixed = RotationUtils.fixRotation(rotations[0], rotations[1],
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
        event.setYaw(fixed[0]);
        event.setPitch(fixed[1]);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!renderTarget.isToggled() || current == null) {
            return;
        }
        RenderUtils.renderBlock(current, targetColor.getRGB() | (targetColor.getAlpha() << 24), true, false);
    }

    /**
     * Every breakable block in reach, in the order the mode wants them.
     *
     * Reach is measured from the eyes to the block centre, which is what the server checks, so a
     * block that passes here is one the server will accept rather than one that merely looks
     * close on screen.
     */
    private List<BlockPos> collectTargets() {
        double reach = range.getInput();
        int limit = MathHelper.ceiling_double_int(reach);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        BlockPos origin = new BlockPos(mc.thePlayer);

        List<BlockPos> found = new ArrayList<BlockPos>();
        for (int x = -limit; x <= limit; x++) {
            for (int y = -limit; y <= limit; y++) {
                for (int z = -limit; z <= limit; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if ((int) mode.getInput() == MODE_DOWN && pos.getY() >= origin.getY()) {
                        continue;
                    }
                    if (!isBreakable(pos)) {
                        continue;
                    }
                    if (eyes.distanceTo(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) > reach) {
                        continue;
                    }
                    if (exposedFace(pos) == null) {
                        continue;
                    }
                    found.add(pos);
                }
            }
        }

        final Vec3 from = eyes;
        final int origy = origin.getY();
        if ((int) mode.getInput() == MODE_FLATTEN) {
            // Highest first, then nearest within the layer, so the ground comes down evenly
            // instead of leaving pillars standing where the scan happened to start.
            found.sort(new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos a, BlockPos b) {
                    if (a.getY() != b.getY()) {
                        return Integer.compare(b.getY(), a.getY());
                    }
                    return Double.compare(distanceTo(from, a), distanceTo(from, b));
                }
            });
        }
        else {
            found.sort(new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos a, BlockPos b) {
                    return Double.compare(distanceTo(from, a), distanceTo(from, b));
                }
            });
        }
        return found;
    }

    private static double distanceTo(Vec3 eyes, BlockPos pos) {
        return eyes.squareDistanceTo(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private boolean isBreakable(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.air) {
            return false;
        }
        if (ignoreLiquids.isToggled() && (block == Blocks.water || block == Blocks.lava
                || block == Blocks.flowing_water || block == Blocks.flowing_lava)) {
            return false;
        }
        if (ignoreUnbreakable.isToggled() && block.getBlockHardness(mc.theWorld, pos) < 0.0f) {
            return false;
        }
        if (ignoreContainers.isToggled() && (block == Blocks.chest || block == Blocks.trapped_chest
                || block == Blocks.ender_chest || block == Blocks.furnace || block == Blocks.lit_furnace
                || block == Blocks.hopper || block == Blocks.dispenser || block == Blocks.dropper
                || block == Blocks.beacon)) {
            return false;
        }
        return true;
    }

    /**
     * A face of the block that can actually be reached.
     *
     * With Only visible on this is a real raytrace, so the module refuses blocks behind cover the
     * way the server would. With it off any air-adjacent face is accepted, which digs faster and
     * is obvious to anyone watching.
     */
    private EnumFacing exposedFace(BlockPos pos) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos neighbour = pos.offset(facing);
            if (mc.theWorld.getBlockState(neighbour).getBlock() != Blocks.air) {
                continue;
            }
            if (!onlyVisible.isToggled()) {
                return facing;
            }
            float[] rotations = rotationsTo(pos, facing);
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
            Vec3 look = Utils.getLookVec(rotations[0], rotations[1]);
            double reach = range.getInput();
            Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);
            if (hit != null
                    && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.getBlockPos().equals(pos)) {
                return facing;
            }
        }
        return null;
    }

    private float[] rotationsTo(BlockPos pos, EnumFacing facing) {
        double x = pos.getX() + 0.5 + facing.getFrontOffsetX() * 0.5;
        double y = pos.getY() + 0.5 + facing.getFrontOffsetY() * 0.5;
        double z = pos.getZ() + 0.5 + facing.getFrontOffsetZ() * 0.5;

        double dx = x - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = z - mc.thePlayer.posZ;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90f, 90f)};
    }
}
