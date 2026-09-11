package mindless.module.impl.player;

import mindless.module.setting.Setting;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoClutchModuleTest {
    @Test
    public void exposesExactlyTheRavenAutoClutchConfiguration() {
        Clutch module = new Clutch();
        Set<String> settingNames = new HashSet<>();
        for (Setting setting : module.getSettings()) {
            settingNames.add(setting.getName());
        }

        assertEquals("AutoClutch", module.getName());
        assertEquals(new HashSet<>(Arrays.asList(
                "Theme", "Render Block", "Mode", "Max fall", "Clutch Cooldown", "Speed",
                "Max distance", "Rotation Tolerance", "Anti-slip-off", "Anti-slip duration",
                "Legit Mode", "Aim Linger", "Stop mode", "Simulate future position", "Debug Logs"
        )), settingNames);
    }

    @Test
    public void keepsTheRavenPathAndRotationMechanics() throws Exception {
        assertEquals(4.5, doubleConstant("REACH"), 0.0);
        assertEquals(0, intConstant("BLOCK_DELAY"));
        assertEquals(0.03404715, doubleConstant("SENS_GCD"), 0.0);
        Clutch.class.getDeclaredField("currentPath");
        Clutch.class.getDeclaredMethod("resetPath");
        Clutch.class.getDeclaredMethod("quantizeDelta", float.class);
    }

    @Test
    public void identifiesOnlyTheRepeatedYawDeltasThatGrimFlags() throws Exception {
        java.lang.reflect.Method method = Clutch.class.getDeclaredMethod("isRepeatedPlacementYawDelta", float.class, float.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, 3.0f, 3.0f));
        assertTrue((Boolean) method.invoke(null, 3.0f, 3.00005f));
        assertFalse((Boolean) method.invoke(null, 3.0f, 3.0002f));
        assertFalse((Boolean) method.invoke(null, 2.0f, 2.0f));
    }

    @Test
    public void steersAgainstKnockbackInServerRelativeCoordinates() throws Exception {
        java.lang.reflect.Method method = Clutch.class.getDeclaredMethod("inputAgainstMotion", double.class, double.class, float.class);
        method.setAccessible(true);

        float[] westAtNorth = (float[]) method.invoke(null, 1.0D, 0.0D, 0.0F);
        float[] westAtEast = (float[]) method.invoke(null, 1.0D, 0.0D, 90.0F);

        assertEquals(0.0F, westAtNorth[0], 0.0F);
        assertEquals(-1.0F, westAtNorth[1], 0.0F);
        assertEquals(1.0F, westAtEast[0], 0.0F);
        assertEquals(0.0F, westAtEast[1], 0.0F);
    }

    @Test
    public void requiresThreeConsecutiveTicksOnSolidGroundBeforeLandingIsSafe() throws Exception {
        java.lang.reflect.Method method = Clutch.class.getDeclaredMethod("nextSafeLandingTicks", int.class, boolean.class);
        method.setAccessible(true);

        int ticks = 0;
        for (int i = 0; i < 2; i++) ticks = (Integer) method.invoke(null, ticks, true);
        assertEquals(2, ticks);
        assertEquals(0, ((Integer) method.invoke(null, ticks, false)).intValue());
        for (int i = 0; i < 3; i++) ticks = (Integer) method.invoke(null, ticks, true);
        assertEquals(3, ticks);
    }

    @Test
    public void releasesKnockbackRecoveryWhenHorizontalMotionReverses() throws Exception {
        java.lang.reflect.Method method = Clutch.class.getDeclaredMethod(
                "isDirectionReversed", double.class, double.class, double.class, double.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, 1.0D, 0.0D, -0.1D, 0.0D));
        assertFalse((Boolean) method.invoke(null, 1.0D, 0.0D, 0.1D, 0.0D));
    }

    private static double doubleConstant(String name) throws Exception {
        Field field = Clutch.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(null);
    }

    private static int intConstant(String name) throws Exception {
        Field field = Clutch.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
