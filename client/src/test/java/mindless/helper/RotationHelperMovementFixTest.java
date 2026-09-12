package mindless.helper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RotationHelperMovementFixTest {
    @Test
    public void silentKeepsForwardWorldDirectionAcrossQuarterTurn() {
        float[] fixed = RotationHelper.remapMovement(0.0F, 90.0F,
                1.0F, 0.0F, false, 1.0F);
        assertEquals(0.0F, fixed[0], 0.0001F);
        assertEquals(1.0F, fixed[1], 0.0001F);
    }

    @Test
    public void silentPreservesAnalogMagnitude() {
        float[] fixed = RotationHelper.remapMovement(35.0F, -22.0F,
                0.3F, 0.3F, false, 0.3F);
        double before = Math.sqrt(0.3F * 0.3F + 0.3F * 0.3F);
        double after = Math.sqrt(fixed[0] * fixed[0] + fixed[1] * fixed[1]);
        assertEquals(before, after, 0.0001);
    }

    @Test
    public void strictUsesEightDirectionInputs() {
        float[] fixed = RotationHelper.remapMovement(0.0F, 63.0F,
                1.0F, 0.0F, true, 1.0F);
        assertEquals(1.0F, Math.abs(fixed[0]), 0.0F);
        assertEquals(1.0F, Math.abs(fixed[1]), 0.0F);
    }
}
