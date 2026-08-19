package mindless.backend;

import com.google.gson.JsonObject;
import mindless.command.impl.Irc;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;

/**
 * Listens for incoming IRC messages from the backend and displays them in chat.
 */
public class IrcListener {
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;

        BackendClient backend = BackendClient.getInstance();
        backend.on("irc_message", IrcListener::handleMessage);
        System.out.println("[IRC] listener registered");
    }

    private static void handleMessage(JsonObject payload) {
        String uuid = payload.has("uuid") ? payload.get("uuid").getAsString() : "";
        String name = payload.has("name") ? payload.get("name").getAsString() : "???";
        String data = payload.has("data") ? payload.get("data").getAsString() : "";

        if (data.isEmpty()) return;

        // Don't show our own messages (we already see them when we send)
        String ourUuid = "";
        try {
            ourUuid = Minecraft.getMinecraft().getSession().getPlayerID();
        } catch (Exception ignored) {}

        if (uuid.equals(ourUuid)) {
            // Show as sent confirmation
            String decrypted = Irc.decrypt(data);
            if (decrypted != null) {
                Minecraft.getMinecraft().addScheduledTask(() ->
                    Utils.sendRawMessage("&8[&bIRC&8] &7You&8: &f" + decrypted)
                );
            }
            return;
        }

        // Decrypt and display
        String decrypted = Irc.decrypt(data);
        if (decrypted != null) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                Utils.sendRawMessage("&8[&bIRC&8] &a" + name + "&8: &f" + decrypted)
            );
        } else {
            System.out.println("[IRC] failed to decrypt message from " + name);
        }
    }
}
