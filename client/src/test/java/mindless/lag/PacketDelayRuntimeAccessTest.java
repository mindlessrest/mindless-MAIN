package mindless.lag;

import mindless.lag.service.PacketDelayService;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PacketDelayRuntimeAccessTest {
    @Test
    public void networkChannelUsesTheRuntimeAccessorBridge() throws Exception {
        Path sourcePath = Paths.get("src", "main", "java", "mindless", "lag", "service", "PacketDelayService.java");
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        assertFalse(source.contains(".channel()"));
        assertTrue(source.contains("AccessorBridge.NetworkManager_getChannel"));
    }

    @Test
    public void onlyPlayProtocolPacketsEnterTheDelayService() throws Exception {
        Method method = PacketDelayService.class.getDeclaredMethod("isPlayPacketClassName", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, "net.minecraft.network.play.server.S08PacketPlayerPosLook"));
        assertTrue((Boolean) method.invoke(null, "net.minecraft.network.play.client.C03PacketPlayer"));
        assertFalse((Boolean) method.invoke(null, "net.minecraft.network.login.server.S00PacketDisconnect"));
        assertFalse((Boolean) method.invoke(null, "net.minecraft.network.status.server.S00PacketServerInfo"));
    }
}
