package mindless.backend;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mindless.Raven;
import net.minecraft.client.Minecraft;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent WebSocket connection to the Mindless backend.
 * Modules register handlers for message types they care about.
 */
public class BackendClient {
    private static final String SERVER_URL = "wss://api.mindless.rest/ws";
    private static final int MAX_RECONNECT_DELAY = 30_000;
    private static final Gson GSON = new Gson();

    private static BackendClient instance;
    private WebSocketClient ws;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageHandler>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> connectListeners = new CopyOnWriteArrayList<>();

    public interface MessageHandler {
        void handle(JsonObject payload);
    }

    private BackendClient() {}

    public static BackendClient getInstance() {
        if (instance == null) {
            instance = new BackendClient();
        }
        return instance;
    }

    /**
     * Connect to the backend. Call once during client init.
     */
    public void connect() {
        if (connected.get()) return;
        shouldReconnect.set(true);
        doConnect();
    }

    /**
     * Disconnect cleanly. Call on client shutdown.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        if (ws != null) {
            ws.close();
        }
    }

    /**
     * Register a handler for a specific message type.
     */
    public void on(String type, MessageHandler handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Register a callback for when connection is established.
     */
    public void onConnect(Runnable listener) {
        connectListeners.add(listener);
    }

    /**
     * Send a typed message to the backend.
     */
    public void send(String type, Object payload) {
        if (!connected.get() || ws == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("payload", GSON.toJsonTree(payload));
        try {
            ws.send(msg.toString());
        } catch (Exception ignored) {}
    }

    /**
     * Check if connected to backend.
     */
    public boolean isConnected() {
        return connected.get();
    }

    private void doConnect() {
        Raven.getCachedExecutor().execute(() -> {
            try {
                String uuid = getPlayerUUID();
                if (uuid == null || uuid.isEmpty()) {
                    // Retry after delay — session might not be ready
                    scheduleReconnect();
                    return;
                }

                URI uri = new URI(SERVER_URL + "?uuid=" + uuid);
                ws = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        connected.set(true);
                        reconnectAttempts.set(0);
                        for (Runnable listener : connectListeners) {
                            try { listener.run(); } catch (Exception ignored) {}
                        }
                    }

                    @Override
                    public void onMessage(String message) {
                        try {
                            JsonObject json = new JsonParser().parse(message).getAsJsonObject();
                            String type = json.get("type").getAsString();
                            JsonElement payloadElem = json.get("payload");
                            JsonObject payload = payloadElem != null && payloadElem.isJsonObject()
                                    ? payloadElem.getAsJsonObject() : new JsonObject();

                            CopyOnWriteArrayList<MessageHandler> typeHandlers = handlers.get(type);
                            if (typeHandlers != null) {
                                for (MessageHandler handler : typeHandlers) {
                                    try { handler.handle(payload); } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        connected.set(false);
                        if (shouldReconnect.get()) {
                            scheduleReconnect();
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        connected.set(false);
                    }
                };
                ws.setConnectionLostTimeout(30);
                ws.connect();
            } catch (Exception e) {
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(1000L * (1 << Math.min(attempt, 5)), MAX_RECONNECT_DELAY);
        Raven.getCachedExecutor().execute(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(delay);
            } catch (InterruptedException ignored) {}
            if (shouldReconnect.get()) {
                doConnect();
            }
        });
    }

    private static String getPlayerUUID() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getSession() != null) {
                return mc.getSession().getPlayerID();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
