package mindless.runtime;

import net.minecraftforge.fml.common.eventhandler.Event;
import org.junit.Assert;
import org.junit.Test;
public class ForgeEventRegistrationCompatibilityTest {
    private static final String[] MINDLESS_EVENTS = {
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
    public void everyMindlessForgeEventHasPublicZeroArgumentConstructor()
            throws Exception {
        for (String simpleName : MINDLESS_EVENTS) {
            Class<?> type = Class.forName("mindless.event." + simpleName);
            Assert.assertTrue(type.getName() + " must extend Forge Event",
                    Event.class.isAssignableFrom(type));
            Object instance = type.getConstructor().newInstance();
            Assert.assertNotNull(type.getName() + " constructor returned null", instance);
        }
    }
}
