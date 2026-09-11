package mindless.rotation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RotationArbiterTest {
    @Test
    public void selectsTheHigherPriorityRequestRegardlessOfSubmissionOrder() {
        RotationArbiter arbiter = new RotationArbiter();

        arbiter.request(RotationSource.AIM_ASSIST, 20.0F, 10.0F);
        arbiter.request(RotationSource.CLUTCH, 60.0F, 40.0F);

        assertEquals(Float.valueOf(60.0F), arbiter.resolveYaw(null));
        assertEquals(Float.valueOf(40.0F), arbiter.resolvePitch(null));
        assertEquals(RotationSource.CLUTCH, arbiter.getYawSource());
        assertEquals(RotationSource.CLUTCH, arbiter.getPitchSource());

        RotationArbiter reversed = new RotationArbiter();
        reversed.request(RotationSource.CLUTCH, 60.0F, 40.0F);
        reversed.request(RotationSource.AIM_ASSIST, 20.0F, 10.0F);

        assertEquals(Float.valueOf(60.0F), reversed.resolveYaw(null));
        assertEquals(Float.valueOf(40.0F), reversed.resolvePitch(null));
    }

    @Test
    public void resolvesYawAndPitchIndependently() {
        RotationArbiter arbiter = new RotationArbiter();

        arbiter.request(RotationSource.KILL_AURA, 90.0F, null);
        arbiter.request(RotationSource.AIM_ASSIST, null, 30.0F);

        assertEquals(Float.valueOf(90.0F), arbiter.resolveYaw(null));
        assertEquals(Float.valueOf(30.0F), arbiter.resolvePitch(null));
        assertEquals(RotationSource.KILL_AURA, arbiter.getYawSource());
        assertEquals(RotationSource.AIM_ASSIST, arbiter.getPitchSource());
    }

    @Test
    public void mergesRepeatedPartialRequestsFromOneSource() {
        RotationArbiter arbiter = new RotationArbiter();

        assertTrue(arbiter.request(RotationSource.SCRIPT, 45.0F, null));
        assertTrue(arbiter.request(RotationSource.SCRIPT, null, 15.0F));

        assertEquals(Float.valueOf(45.0F), arbiter.resolveYaw(null));
        assertEquals(Float.valueOf(15.0F), arbiter.resolvePitch(null));
    }

    @Test
    public void preservesFallbackValuesWhenNoSourceRequestsAnAxis() {
        RotationArbiter arbiter = new RotationArbiter();

        assertNull(arbiter.resolveYaw(null));
        assertEquals(Float.valueOf(10.0F), arbiter.resolvePitch(10.0F));
    }

    @Test
    public void rejectsInvalidRequests() {
        RotationArbiter arbiter = new RotationArbiter();

        assertFalse(arbiter.request(RotationSource.SCAFFOLD, Float.NaN, 10.0F));
        assertFalse(arbiter.request(RotationSource.SCAFFOLD, Float.POSITIVE_INFINITY, null));
        assertFalse(arbiter.request(RotationSource.SCAFFOLD, null, null));
        assertFalse(arbiter.hasRequest());
    }
}
