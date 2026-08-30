package mindless.runtime;

import net.minecraft.client.Minecraft;

/** Prevents live class retransformation while a multiplayer world is rendering. */
public final class TransformSafety {
    private static final long MAX_WAIT_MS = 300_000L;
    private static final long STABLE_MENU_MS = 1_000L;

    private TransformSafety() {}

    public static boolean awaitSafeWindow() {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        long stableSince = 0L;
        boolean announced = false;
        while (System.currentTimeMillis() < deadline) {
            Minecraft minecraft = Minecraft.getMinecraft();
            boolean ready = minecraft != null
                    && minecraft.theWorld == null
                    && minecraft.currentScreen != null
                    && minecraft.fontRendererObj != null
                    && minecraft.getFramebuffer() != null
                    && minecraft.displayWidth > 0
                    && minecraft.displayHeight > 0;
            long now = System.currentTimeMillis();
            if (ready) {
                if (stableSince == 0L) stableSince = now;
                if (now - stableSince >= STABLE_MENU_MS) return true;
            } else {
                stableSince = 0L;
                if (!announced && minecraft != null && minecraft.theWorld != null) {
                    System.out.println("[MindlessNative] Waiting for the user to return to a menu before transforming render classes");
                    announced = true;
                }
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
