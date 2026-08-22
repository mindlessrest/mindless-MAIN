package mindless.utility;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

public final class EnumFacingOffset {
    private final EnumFacing enumFacing;
    private final Vec3 offset;

    public EnumFacingOffset(EnumFacing enumFacing, Vec3 offset) {
        this.enumFacing = enumFacing;
        this.offset = offset;
    }

    public EnumFacing getEnumFacing() { return enumFacing; }
    public Vec3 getOffset() { return offset; }
}
