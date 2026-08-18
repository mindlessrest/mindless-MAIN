package mindless.runtime;

import net.minecraftforge.fml.common.eventhandler.Event;
import org.junit.Assert;
import org.junit.Test;

/**
 * Forge 1.8.9 EventBus.register asks every subscribed event type for a public
 * zero-argument constructor to obtain its ListenerList. Without it Forge
 * silently skips that handler after logging NoSuchMethodException.
 */
public class ForgeEventRegistrationCompatibilityTest {
    private static final String[] RAVEN_EVENTS = {
            "AntiCheatFlagEvent",
            "ClickMouseEvent",
            "ClientRotationEvent",
            "GameTickEvent",
            "GuiUpdateEvent",
            "JumpEvent",
            "NoEventPacketEvent",
            "PostMotionEvent",
            "PostPlayerInputEvent",
            "PostProfileLoadEvent",
            "PostUpdateEvent",
            "PreInputEvent",
            "PreMotionEvent",
            "PrePlayerInputEvent",
            "PrePlayerInteractEvent",
            "PreSlotScrollEvent",
            "PreUpdateEvent",
            "ReceivePacketEvent",
            "RightClickMouseEvent",
            "SendPacketEvent",
            "SlotUpdateEvent",
            "StrafeEvent",
            "UseItemEvent"
    };

    @Test
    public void everyRavenForgeEventHasPublicZeroArgumentConstructor()
            throws Exception {
        for (String simpleName : RAVEN_EVENTS) {
            Class<?> type = Class.forName("mindless.event." + simpleName);
            Assert.assertTrue(type.getName() + " must extend Forge Event",
                    Event.class.isAssignableFrom(type));
            Object instance = type.getConstructor().newInstance();
            Assert.assertNotNull(type.getName() + " constructor returned null", instance);
        }
    }
}
