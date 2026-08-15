package keystrokesmod.runtime;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/** Cached reflective access that cannot live as fields on an already-loaded game class. */
public final class EntityPlayerSPReflection {
    private static volatile java.lang.reflect.Method pushOutOfBlocksMethod;
    private static volatile java.lang.reflect.Field flyToggleTimerField;
    private static volatile java.lang.reflect.Field inPortalField;

    private EntityPlayerSPReflection() {}

    /**
     * Bytecode marker replaced with INVOKESPECIAL by RavenTransformerManager.
     * It must never survive into a transformed EntityPlayerSP method.
     */
    public static void callSuperOnLivingUpdateMarker(EntityPlayerSP self) {
        throw new AssertionError("super.onLivingUpdate marker was not rewritten");
    }

    public static void callPushOutOfBlocks(Entity entity, double x, double y, double z) {
        try {
            java.lang.reflect.Method method = pushOutOfBlocksMethod;
            if (method == null) {
                synchronized (EntityPlayerSPReflection.class) {
                    method = pushOutOfBlocksMethod;
                    if (method == null) {
                        try {
                            method = Entity.class.getDeclaredMethod(
                                    "pushOutOfBlocks", double.class, double.class, double.class);
                        } catch (NoSuchMethodException missingMcpName) {
                            method = Entity.class.getDeclaredMethod(
                                    "func_145771_j", double.class, double.class, double.class);
                        }
                        method.setAccessible(true);
                        pushOutOfBlocksMethod = method;
                    }
                }
            }
            method.invoke(entity, x, y, z);
        } catch (Throwable ignored) {
            // Best effort: vanilla simply skips this collision nudge if reflection is blocked.
        }
    }

    public static int getFlyToggleTimer(EntityPlayer player) {
        try {
            return flyToggleTimerField().getInt(player);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setFlyToggleTimer(EntityPlayer player, int value) {
        try {
            flyToggleTimerField().setInt(player, value);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    public static boolean getInPortal(Entity entity) {
        try {
            return inPortalField().getBoolean(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setInPortal(Entity entity, boolean value) {
        try {
            inPortalField().setBoolean(entity, value);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private static java.lang.reflect.Field inPortalField() throws NoSuchFieldException {
        java.lang.reflect.Field field = inPortalField;
        if (field != null) return field;
        synchronized (EntityPlayerSPReflection.class) {
            field = inPortalField;
            if (field == null) {
                try {
                    field = Entity.class.getDeclaredField("inPortal");
                } catch (NoSuchFieldException missingMcpName) {
                    field = Entity.class.getDeclaredField("field_71087_bX");
                }
                field.setAccessible(true);
                inPortalField = field;
            }
        }
        return field;
    }

    private static java.lang.reflect.Field flyToggleTimerField() throws NoSuchFieldException {
        java.lang.reflect.Field field = flyToggleTimerField;
        if (field != null) return field;
        synchronized (EntityPlayerSPReflection.class) {
            field = flyToggleTimerField;
            if (field == null) {
                try {
                    field = EntityPlayer.class.getDeclaredField("flyToggleTimer");
                } catch (NoSuchFieldException missingMcpName) {
                    field = EntityPlayer.class.getDeclaredField("field_71101_bC");
                }
                field.setAccessible(true);
                flyToggleTimerField = field;
            }
        }
        return field;
    }
}
