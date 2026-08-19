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
    private static final String TAG = "[BackendClient]";

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
        System.out.println(TAG + " connect() called, initiating connection...");
        doConnect();
    }

    /**
     * Disconnect cleanly. Call on client shutdown.
     */
    public void disconnect() {
        System.out.println(TAG + " disconnect() called");
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
        System.out.println(TAG + " registered handler for: " + type);
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
        if (!connected.get() || ws == null) {
            System.out.println(TAG + " SEND FAILED (not connected): " + type);
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("payload", GSON.toJsonTree(payload));
        String json = msg.toString();
        System.out.println(TAG + " >>> " + type + " | " + json);
        try {
            ws.send(json);
        } catch (Exception e) {
            System.out.println(TAG + " send error: " + e.getMessage());
        }
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
                    System.out.println(TAG + " UUID not available yet, will retry...");
                    scheduleReconnect();
                    return;
                }

                String url = SERVER_URL + "?uuid=" + uuid;
                System.out.println(TAG + " connecting to: " + url);

                URI uri = new URI(url);
                ws = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        connected.set(true);
                        reconnectAttempts.set(0);
                        System.out.println(TAG + " CONNECTED! status=" + handshake.getHttpStatus());
                        for (Runnable listener : connectListeners) {
                            try { listener.run(); } catch (Exception e) {
                                System.out.println(TAG + " onConnect listener error: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onMessage(String message) {
                        System.out.println(TAG + " <<< " + message);
                        try {
                            JsonObject json = new JsonParser().parse(message).getAsJsonObject();
                            String type = json.get("type").getAsString();
                            JsonElement payloadElem = json.get("payload");
                            JsonObject payload = payloadElem != null && payloadElem.isJsonObject()
                                    ? payloadElem.getAsJsonObject() : new JsonObject();

                            CopyOnWriteArrayList<MessageHandler> typeHandlers = handlers.get(type);
                            if (typeHandlers != null) {
                                for (MessageHandler handler : typeHandlers) {
                                    try { handler.handle(payload); } catch (Exception e) {
                                        System.out.println(TAG + " handler error for '" + type + "': " + e.getMessage());
                                    }
                                }
                            } else {
                                System.out.println(TAG + " no handler for type: " + type);
                            }
                        } catch (Exception e) {
                            System.out.println(TAG + " parse error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        connected.set(false);
                        System.out.println(TAG + " DISCONNECTED code=" + code + " reason=" + reason + " remote=" + remote);
                        if (shouldReconnect.get()) {
                            scheduleReconnect();
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        connected.set(false);
                        System.out.println(TAG + " ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    }
                };
                ws.setConnectionLostTimeout(30);
                ws.connect();
            } catch (Exception e) {
                System.out.println(TAG + " doConnect exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(1000L * (1 << Math.min(attempt, 5)), MAX_RECONNECT_DELAY);
        System.out.println(TAG + " reconnect attempt #" + attempt + " in " + delay + "ms");
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
                String uuid = mc.getSession().getPlayerID();
                System.out.println(TAG + " player UUID: " + uuid);
                return uuid;
            }
        } catch (Exception e) {
            System.out.println(TAG + " getPlayerUUID error: " + e.getMessage());
        }
        return null;
    }
}
