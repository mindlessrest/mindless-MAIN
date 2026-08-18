package mindless.runtime;

import net.minecraft.item.ItemStack;

import java.util.Random;

public final class ItemPhysicsState {
    public static final Random random = new Random();
    public static long lastNano = System.nanoTime();
    public static double rotation;

    private ItemPhysicsState() {}

    public static int getModelCount(ItemStack stack) {
        if (stack.stackSize > 48) return 5;
        if (stack.stackSize > 32) return 4;
        if (stack.stackSize > 16) return 3;
        if (stack.stackSize > 1) return 2;
        return 1;
    }
}
