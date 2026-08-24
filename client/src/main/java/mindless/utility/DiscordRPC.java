package mindless.utility;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mod-friendly Discord RPC client.
 *
 * DESIGN:
 * - Exactly ONE background worker thread ever touches the named pipes. All pipe I/O
 *   (handshake, write, read reply) happens strictly sequentially on that thread.
 *   This matters: doing concurrent read+write on the same pipe handle from two
 *   threads can hang the write indefinitely on Windows (that bit us earlier).
 * - Your game/mod thread(s) NEVER touch the pipe directly. Call setPresence(...)
 *   from anywhere (tick handler, event listener, whatever) - it just drops a value
 *   into a queue and returns immediately. Non-blocking, safe from any thread.
 * - Debounced: if you call setPresence() 60 times a second (e.g. from a render/tick
 *   loop), only the LATEST value is kept and actually sent, on a minimum interval
 *   (default 15s, matching Discord's own recommended RPC update rate) - so you don't
 *   spam the pipe or trip rate limits.
 * - Auto-reconnects if a Discord client restarts (pipe closes) - retries handshake
 *   every RETRY_INTERVAL_MS while there's a queued/pending presence.
 *
 * USAGE (from your mod):
 *   DiscordRPC rpc = new DiscordRPC("YOUR_CLIENT_ID");
 *   rpc.start(); // spins up the worker thread, call once on mod init
 *   ...
 *   rpc.setPresence(new RichPresence().details("In the Nether").state("Y: 42")); // call anytime, any thread
 *   ...
 *   rpc.shutdown(); // call once on mod shutdown / game close
 */
public class DiscordRPC {
    private static final long MIN_UPDATE_INTERVAL_MS = 4_000; // fast enough for a 5s rotation, still avoids spamming the pipe
    private static final long RETRY_INTERVAL_MS = 10_000;
    private static final long POLL_TIMEOUT_MS = 1_000;

    private final String clientId;
    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>(1); // holds at most the latest RichPresence (or POISON)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private static final Object SHUTDOWN = new Object();

    public DiscordRPC(String clientId) {
        this.clientId = clientId;
    }

    /** Call once, e.g. from your mod's init. Starts the single background worker thread. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::workerLoop, "DiscordRPC-Worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Call from ANY thread, ANY time (tick loop, event handler, etc). Non-blocking.
     * Only the most recent call matters - older un-sent updates are replaced, not queued up.
     */
    public void setPresence(RichPresence presence) {
        inbox.poll();          // drop any stale not-yet-sent update
        inbox.offer(presence); // queue the latest one (offer never blocks, capacity 1)
    }

    /** Call once on shutdown. Clears presence on all connected clients and stops the worker thread. */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            inbox.poll();
            inbox.offer(SHUTDOWN);
            try { if (worker != null) worker.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Worker thread - the ONLY thread that ever touches a pipe.
    // ---------------------------------------------------------------------

    private void workerLoop() {
        List<Connection> connections = new ArrayList<>();
        long lastSendTime = 0;
        RichPresence lastSent = null;
        long lastConnectAttempt = 0;

        while (running.get() || !inbox.isEmpty()) {
            // (Re)connect if we have nothing connected yet, throttled to avoid hammering on error
            if (connections.isEmpty() && System.currentTimeMillis() - lastConnectAttempt > RETRY_INTERVAL_MS) {
                lastConnectAttempt = System.currentTimeMillis();
                connections = connectAll();
            }

            Object item;
            try {
                item = inbox.poll(POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }

            if (item == SHUTDOWN) {
                break;
            }

            if (item instanceof RichPresence) {
                RichPresence presence = (RichPresence) item;
                long now = System.currentTimeMillis();

                if (now - lastSendTime < MIN_UPDATE_INTERVAL_MS) {
                    // too soon - put it back so it gets sent once the interval passes,
                    // unless something newer replaces it first
                    inbox.offer(presence);
                    try { Thread.sleep(Math.min(POLL_TIMEOUT_MS, MIN_UPDATE_INTERVAL_MS - (now - lastSendTime))); }
                    catch (InterruptedException ignored) {}
                    continue;
                }

                if (connections.isEmpty()) {
                    // no Discord instance available right now - drop it, next setPresence() call (or retry loop) will catch up
                    continue;
                }

                for (Connection c : new ArrayList<>(connections)) {
                    boolean ok = c.update(presence);
                    if (!ok) connections.remove(c); // connection died - will be re-established next loop
                }
                lastSendTime = now;
                lastSent = presence;
            }
        }

        // Cleanup: clear presence and close pipes
        for (Connection c : connections) {
            c.clearAndClose();
        }
    }

    private List<Connection> connectAll() {
        List<Connection> result = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Connection c = new Connection(clientId, i);
            if (c.connect()) result.add(c);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Connection - one pipe. Only ever called from the worker thread.
    // ---------------------------------------------------------------------

    private static class Connection {
        private final String clientId;
        private final int pipeIndex;
        private RandomAccessFile pipe;
        private String label = "";

        Connection(String clientId, int pipeIndex) {
            this.clientId = clientId;
            this.pipeIndex = pipeIndex;
        }

        boolean connect() {
            String pipePath = "\\\\?\\pipe\\discord-ipc-" + pipeIndex;
            try {
                pipe = new RandomAccessFile(pipePath, "rw");
                sendPacket(0, "{\"v\":1,\"client_id\":\"" + clientId + "\"}");

                String response = readFrame();
                if (response == null || response.contains("\"evt\":\"ERROR\"")) {
                    pipe.close();
                    return false;
                }

                label = "pipe " + pipeIndex;
                String username = extractField(response, "username");
                if (username != null) label += " (" + username + ")";
                return true;
            } catch (Exception e) {
                try { if (pipe != null) pipe.close(); } catch (Exception ignored) {}
                return false;
            }
        }

        /** Returns false if the connection died and should be dropped/reconnected. */
        boolean update(RichPresence presence) {
            try {
                long pid = getPid();
                String json = "{"
                        + "\"cmd\":\"SET_ACTIVITY\","
                        + "\"args\":{"
                        + "\"pid\": " + pid + ","
                        + "\"activity\": {"
                        + (presence.details != null ? "\"details\": \"" + escape(presence.details) + "\"," : "")
                        + (presence.state != null ? "\"state\": \"" + escape(presence.state) + "\"," : "")
                        + (presence.startTimestamp > 0 ? "\"timestamps\": {\"start\": " + presence.startTimestamp + "}," : "")
                        + "\"assets\": {"
                        + (presence.largeImage != null ? "\"large_image\": \"" + escape(presence.largeImage) + "\"," : "")
                        + (presence.largeText != null ? "\"large_text\": \"" + escape(presence.largeText) + "\"," : "")
                        + (presence.smallImage != null ? "\"small_image\": \"" + escape(presence.smallImage) + "\"," : "")
                        + (presence.smallText != null ? "\"small_text\": \"" + escape(presence.smallText) + "\"" : "")
                        + "}"
                        + "}"
                        + "},"
                        + "\"nonce\": \"" + System.currentTimeMillis() + "\""
                        + "}";
                json = json.replace(",}", "}").replace(",\"assets\": {}", "");

                sendPacket(1, json);
                readFrame(); // consume the reply so the pipe buffer doesn't build up; ignore content for speed
                return true;
            } catch (Exception e) {
                try { pipe.close(); } catch (Exception ignored) {}
                return false;
            }
        }

        void clearAndClose() {
            try {
                sendPacket(1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + getPid() + "},\"nonce\":\"" + System.currentTimeMillis() + "\"}");
                readFrame();
            } catch (Exception ignored) {}
            try {
                byte[] closeHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(0).array();
                pipe.write(closeHeader);
                pipe.close();
            } catch (Exception ignored) {}
        }

        private static long getPid() {
            try {
                String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                int idx = name.indexOf('@');
                if (idx != -1) {
                    return Long.parseLong(name.substring(0, idx));
                }
            } catch (Exception ignored) {}
            return 0L;
        }

        private void sendPacket(int opcode, String json) throws Exception {
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(8 + jsonBytes.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(opcode);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            pipe.write(buffer.array());
        }

        private String readFrame() throws Exception {
            byte[] lenBuf = new byte[8];
            if (!readFully(lenBuf)) return null;
            ByteBuffer bb = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN);
            bb.getInt();
            int len = bb.getInt();
            if (len <= 0 || len >= 16384) return null;
            byte[] data = new byte[len];
            if (!readFully(data)) return null;
            return new String(data, StandardCharsets.UTF_8);
        }

        private boolean readFully(byte[] buf) throws Exception {
            int off = 0;
            while (off < buf.length) {
                int n = pipe.read(buf, off, buf.length - off);
                if (n == -1) return false;
                off += n;
            }
            return true;
        }

        private static String extractField(String json, String field) {
            String key = "\"" + field + "\":\"";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = json.indexOf('"', start);
            if (end == -1) return null;
            return json.substring(start, end);
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static class RichPresence {
        public String details;
        public String state;
        public long startTimestamp;
        public String largeImage;
        public String largeText;
        public String smallImage;
        public String smallText;

        public RichPresence details(String details) { this.details = details; return this; }
        public RichPresence state(String state) { this.state = state; return this; }
        public RichPresence startTimestamp(long timestamp) { this.startTimestamp = timestamp; return this; }
        public RichPresence largeImage(String key, String text) { this.largeImage = key; this.largeText = text; return this; }
        public RichPresence smallImage(String key, String text) { this.smallImage = key; this.smallText = text; return this; }
    }

    // ---------------------------------------------------------------------
    // Rotator - cycles the `state` (line 2) through a list of suppliers every
    // `intervalMs`, while `details` (line 1) and the asset stay fixed. Use this
    // when you want the profile card to swap between "Bed intact | 5 kills",
    // "Gold: 340", "Spectating", etc without you manually timing anything.
    // ---------------------------------------------------------------------
    public static class Rotator {
        private final DiscordRPC rpc;
        private final List<java.util.function.Supplier<String>> lines = new ArrayList<>();
        private final long intervalMs;
        private java.util.function.Supplier<String> detailsSupplier = () -> null;
        private String largeImageKey;
        private String largeImageText;
        private long startTimestamp;
        private Thread rotatorThread;
        private volatile boolean running = false;
        private int index = 0;

        public Rotator(DiscordRPC rpc, long intervalMs) {
            this.rpc = rpc;
            this.intervalMs = intervalMs;
        }

        /** Line 1 - e.g. the map name. Called fresh each rotation, so it can change (new match, etc). */
        public Rotator details(java.util.function.Supplier<String> supplier) {
            this.detailsSupplier = supplier;
            return this;
        }

        /** Add one rotating line-2 state, e.g. () -> "Kills: " + killCount. Evaluated fresh each time it's shown. */
        public Rotator addLine(java.util.function.Supplier<String> supplier) {
            lines.add(supplier);
            return this;
        }

        public Rotator asset(String key, String text) {
            this.largeImageKey = key;
            this.largeImageText = text;
            return this;
        }

        public Rotator matchStartTimestamp(long startTimestamp) {
            this.startTimestamp = startTimestamp;
            return this;
        }

        /** Starts the rotation timer. Call once you've configured details/lines/asset. */
        public void start() {
            if (running) return;
            running = true;
            index = 0;
            rotatorThread = new Thread(() -> {
                while (running) {
                    if (!lines.isEmpty()) {
                        String state = lines.get(index % lines.size()).get();
                        index++;

                        RichPresence p = new RichPresence()
                                .details(detailsSupplier.get())
                                .state(state)
                                .startTimestamp(startTimestamp);
                        if (largeImageKey != null) p.largeImage(largeImageKey, largeImageText);
                        rpc.setPresence(p);
                    }
                    try { Thread.sleep(intervalMs); } catch (InterruptedException e) { break; }
                }
            }, "DiscordRPC-Rotator");
            rotatorThread.setDaemon(true);
            rotatorThread.start();
        }

        public void stop() {
            running = false;
            if (rotatorThread != null) rotatorThread.interrupt();
        }
    }

    // ---------------------------------------------------------------------
    // Example: how you'd wire this into a Fabric/Forge mod
    // ---------------------------------------------------------------------
    /*
    // Simple version - manually call setPresence() when something changes:
    public class YourMod {
        private static DiscordRPC rpc;
        private static final long START = System.currentTimeMillis() / 1000L;

        public static void onInitialize() { // or Forge FMLClientSetupEvent
            rpc = new DiscordRPC("YOUR_APPLICATION_ID");
            rpc.start();
        }

        public static void onClientStopping() { // Forge: FMLClientStoppingEvent
            rpc.shutdown();
        }
    }

    // Bedwars rotator version - map name stays put, line 2 cycles through
    // kills/gold/bed-status/spectating every 5s, auto-refreshing live values:
    public class BedwarsPresence {
        private static DiscordRPC rpc;
        private static DiscordRPC.Rotator rotator;
        private static long matchStart;

        // stand-ins for however you actually read match state (scoreboard/tab list parsing etc)
        static String currentMap = "Cliffside";
        static int kills = 0;
        static int finalKills = 0;
        static int gold = 0;
        static boolean bedIntact = true;
        static boolean spectating = false;

        public static void onInitialize() {
            rpc = new DiscordRPC("YOUR_APPLICATION_ID");
            rpc.start();

            rotator = new DiscordRPC.Rotator(rpc, 5_000)
                    .details(() -> "Bed Wars — " + currentMap)
                    .asset("large_image", "Bed Wars")
                    .matchStartTimestamp(matchStart)
                    .addLine(() -> spectating ? "Spectating" : (bedIntact ? "Bed intact" : "Bed destroyed 💀"))
                    .addLine(() -> "Kills: " + kills + " | Finals: " + finalKills)
                    .addLine(() -> "Gold: " + gold);
        }

        // call this once when a new match starts (reset timer + start rotating)
        public static void onMatchStart(String mapName) {
            currentMap = mapName;
            matchStart = System.currentTimeMillis() / 1000L;
            kills = 0; finalKills = 0; gold = 0; bedIntact = true; spectating = false;
            rotator.start();
        }

        public static void onMatchEnd() {
            rotator.stop();
        }

        // wire these into your kill/gold/bed event listeners - just update the fields,
        // the rotator re-reads them fresh every 5s, no need to call setPresence yourself
        public static void onKill() { kills++; }
        public static void onFinalKill() { finalKills++; }
        public static void onGoldChanged(int newGold) { gold = newGold; }
        public static void onBedDestroyed() { bedIntact = false; }
        public static void onPlayerDied() { spectating = true; }
    }
    */
}