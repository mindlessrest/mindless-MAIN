package mindless.module.impl.render;

import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.renderer.WorldRenderer;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class VisualGeometryTest {
    @Test
    public void connectedRowsRoundBothSidesOfAStepWithoutOverlapping() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        Method fill = method(HUD.class, "fillRow", WorldRenderer.class, float.class, float.class,
                float.class, float.class, float.class, float.class, float.class, float.class, int.class);
        fill.invoke(null, renderer, 0f, 0f, 100f, 20f, 4f, 4f, 0f, 4f, 0xAA000000);
        int firstRowEnd = renderer.points.size();
        fill.invoke(null, renderer, 20f, 20f, 100f, 40f, -4f, 0f, 4f, 4f, 0xAA000000);
        boolean inwardCorner = false;
        for (int i = 0; i < renderer.points.size(); i += 3) {
            assertTrue(signedArea(renderer.points.get(i), renderer.points.get(i + 1),
                    renderer.points.get(i + 2)) < 0);
        }
        for (int i = 0; i < renderer.points.size(); i++) {
            double[] point = renderer.points.get(i);
            if (i < firstRowEnd) assertTrue(point[1] <= 20);
            else {
                assertTrue(point[1] >= 20);
                if (point[1] > 20 && point[1] < 24 && point[0] < 20) inwardCorner = true;
            }
        }
        assertTrue("The inward corner must extend smoothly into the step", inwardCorner);
    }

    @Test
    public void roundedBackgroundKeepsTextInsideTheCornerInset() throws Exception {
        Field rounded = field(HUD.class, "roundedBackground");
        Field radius = field(HUD.class, "cornerRadius");
        Object previousRounded = rounded.get(null);
        Object previousRadius = radius.get(null);
        try {
            rounded.set(null, new ButtonSetting("Rounded background", true));
            for (double size : new double[]{0, 4, 8, 12}) {
                radius.set(null, new SliderSetting("Corner radius", size, 0, 12, 0.5));
                float actual = (Float) method(HUD.class, "getBackgroundRadius", float.class).invoke(null, 24f);
                int padding = (Integer) method(HUD.class, "getHudHorizontalTextPadding").invoke(null);
                assertEquals(size, actual, 0.001);
                assertTrue(padding >= actual);
            }
        } finally {
            rounded.set(null, previousRounded);
            radius.set(null, previousRadius);
        }
    }

    @Test
    public void hybridRingHasLevelEdgesAndVisibleSideThickness() throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        TargetHUD hud = (TargetHUD) unsafe.allocateInstance(TargetHUD.class);
        field(TargetHUD.class, "ringView").set(hud,
                new SliderSetting("Ring view", 2, new String[]{"3D", "2D", "Hybrid"}));
        method(TargetHUD.class, "ensureCircle", int.class).invoke(hud, 16);
        RecordingRenderer renderer = new RecordingRenderer();
        method(TargetHUD.class, "emitRing", WorldRenderer.class, float.class, float.class,
                float.class, float.class, float.class, int.class, float.class, float.class,
                float.class, float.class, int.class, int.class, boolean.class)
                .invoke(hud, renderer, 0f, 1f, 0f, 0.5f, 0.04f, 16, 0f, 0f, 0f, 0f,
                        0xFFFFFFFF, 255, true);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] point : renderer.points) {
            min = Math.min(min, point[1]);
            max = Math.max(max, point[1]);
            assertTrue(Math.abs(point[1] - 1) < 0.021);
        }
        assertEquals(0.04, max - min, 0.0001);
    }

    private static double signedArea(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class RecordingRenderer extends WorldRenderer {
        final List<double[]> points = new ArrayList<>();

        RecordingRenderer() { super(256); }

        @Override
        public WorldRenderer pos(double x, double y, double z) {
            points.add(new double[]{x, y, z});
            return this;
        }

        @Override
        public WorldRenderer color(int red, int green, int blue, int alpha) { return this; }

        @Override
        public void endVertex() {}
    }
}
