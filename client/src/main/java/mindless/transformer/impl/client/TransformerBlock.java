package mindless.transformer.impl.client;

import mindless.event.CollisionEvent;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

@CTransformer(Block.class)
public abstract class TransformerBlock {
    @CShadow
    public abstract AxisAlignedBB getCollisionBoundingBox(World worldIn, BlockPos pos, IBlockState state);

    @COverride
    public void addCollisionBoxesToList(World worldIn, BlockPos pos, IBlockState state, AxisAlignedBB mask, List<AxisAlignedBB> list, Entity collidingEntity) {
        AxisAlignedBB axisalignedbb = this.getCollisionBoundingBox(worldIn, pos, state);
        CollisionEvent event = new CollisionEvent(pos, (Block)(Object)this, axisalignedbb);
        MinecraftForge.EVENT_BUS.post(event);
        axisalignedbb = event.boundingBox;
        if (axisalignedbb != null && mask.intersectsWith(axisalignedbb)) {
            list.add(axisalignedbb);
        }
    }
}
