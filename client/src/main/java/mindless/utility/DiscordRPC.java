package mindless.utility;

import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes Rich Presence to every Discord client running on this machine.
 *
 * <p>Discord listens on a named pipe per instance, numbered from zero, so stable, PTB, Canary and
 * a second account each get their own. All of them are offered the same presence.
 *
 * <p>Everything touching a pipe happens on the worker thread. It used to happen on the game
 * thread, which is what made this unreliable: a named pipe read blocks with no timeout, so any
 * Discord that was slow to answer took the game down with it, and any Discord that went away took
 * the presence down for the rest of the session. See {@link #pump} for what the worker guarantees.
 */
public class DiscordRPC {
    /** Discord numbers its pipes from zero. Ten covers more clients than anyone runs at once. */
    private static final int MAX_PIPES = 10;
    /** How long to wait before hunting for pipes again after finding none. */
    private static final long RECONNECT_INTERVAL_MS = 5000L;
    /** Worker cycle. Fast enough that a presence change lands promptly, slow enough to be free. */
    private static final long POLL_INTERVAL_MS = 250L;
    /**
     * How long the worker may go without completing a cycle before it is assumed wedged.
     *
     * <p>The only way it can wedge is a pipe read that never returns. Closing the pipe from
     * another thread makes that read throw, which is how the watchdog frees it.
     */
    private static final long STALL_TIMEOUT_MS = 10000L;
    /**
     * Discord rate limits activity updates to five in any twenty seconds and quietly drops the
     * rest, so the presence would stick on whatever was sent when the budget ran out. A bucket of
     * five refilling one per four seconds is the same allowance, spent deliberately: a burst when
     * you change game, then a trickle.
     */
    private static final int RATE_LIMIT_BURST = 5;
    private static final long RATE_LIMIT_REFILL_MS = 4000L;
    /** Frames to look through for our own reply before giving up on a connection. */
    private static final int MAX_REPLY_SCAN = 8;

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;

    private final String clientId;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();

    private volatile RichPresence desired;
    private volatile boolean running;
    private volatile long lastCycleAt;
    private Thread worker;

    public DiscordRPC(String clientId) {
        this.clientId = clientId;
    }

    /** Starts the worker. Returns immediately; the pipes are found on the worker's own time. */
    public void connect() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            running = true;
            lastCycleAt = System.currentTimeMillis();
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "Mindless-DiscordRPC");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    /**
     * Hands the worker the presence to aim for. Safe to call every tick; it never blocks and never
     * touches a pipe.
     *
     * <p>This is a target, not a send. If Discord is missing, or the rate limit budget is spent,
     * the worker keeps this and sends it when it can, so the presence always converges on the
     * latest state rather than getting stuck on whatever happened to fit through.
     */
    public void update(RichPresence presence) {
        desired = presence;
        if (!running) {
            connect();
            return;
        }

        // A worker that has not finished a cycle in this long is blocked in a pipe read. Closing
        // the pipes under it is what makes that read throw so it can recover.
        if (System.currentTimeMillis() - lastCycleAt > STALL_TIMEOUT_MS) {
            System.out.println("[discord rpc] worker stalled, dropping connections to free it");
            for (Connection c : connections) {
                c.forceClose();
            }
        }
    }

    public void close() {
        synchronized (lifecycleLock) {
            running = false;
            worker = null;
        }
        desired = null;
        for (Connection c : connections) {
            c.forceClose();
        }
        connections.clear();
    }

    /**
     * The worker.
     *
     * <p>Three things it promises, each of which was broken before. A connection that dies is
     * dropped from the list rather than left in it marked dead, because a list of dead connections
     * is not empty and so never triggered a reconnect -- one hiccup and the presence was gone
     * until the module was toggled. A send is only counted as done once a Discord actually
     * accepted it, so a failed send is retried instead of being remembered as sent. And no send
     * goes out without a rate limit token, because the updates over the limit were not queued,
     * they were discarded.
     */
    private void pump() {
        int tokens = RATE_LIMIT_BURST;
        long lastRefillAt = System.currentTimeMillis();
        long nextConnectAt = 0L;
        String sentSignature = null;

        try {
            while (running) {
                long now = System.currentTimeMillis();
                lastCycleAt = now;

                long elapsedRefills = (now - lastRefillAt) / RATE_LIMIT_REFILL_MS;
                if (elapsedRefills > 0) {
                    tokens = (int) Math.min(RATE_LIMIT_BURST, tokens + elapsedRefills);
                    lastRefillAt += elapsedRefills * RATE_LIMIT_REFILL_MS;
                }

                dropDeadConnections();

                if (connections.isEmpty() && now >= nextConnectAt) {
                    nextConnectAt = now + RECONNECT_INTERVAL_MS;
                    findPipes();
                    // A fresh Discord has no presence set, so whatever we last sent no longer
                    // applies and has to go out again.
                    sentSignature = null;
                }

                RichPresence target = desired;
                if (target != null && !connections.isEmpty() && tokens > 0) {
                    String signature = target.signature();
                    if (!signature.equals(sentSignature)) {
                        tokens--;
                        if (send(target)) {
                            sentSignature = signature;
                            // Only reached when the presence actually changed and Discord took
                            // it, so this cannot spam the way a per-update log would.
                            System.out.println("[discord rpc] set: " + target.details
                                    + " / " + target.state);
                        }
                    }
                }

                sleep(POLL_INTERVAL_MS);
            }
        }
        catch (Throwable ignored) {
        }
        finally {
            for (Connection c : connections) {
                c.forceClose();
            }
            connections.clear();
            synchronized (lifecycleLock) {
                running = false;
                worker = null;
            }
        }
    }

    /** @return true when at least one Discord took the update. */
    private boolean send(RichPresence presence) {
        String json = buildActivityJson(presence);
        boolean delivered = false;
        for (Connection c : connections) {
            if (c.send(json)) {
                delivered = true;
            }
        }
        return delivered;
    }

    private void dropDeadConnections() {
        for (Iterator<Connection> it = connections.iterator(); it.hasNext(); ) {
            Connection c = it.next();
            if (!c.isAlive()) {
                c.forceClose();
                connections.remove(c);
                System.out.println("[discord rpc] lost " + c.label + ", will look again");
            }
        }
    }

    private void findPipes() {
        for (int i = 0; i < MAX_PIPES; i++) {
            Connection c = new Connection(clientId, i);
            if (c.connect()) {
                connections.add(c);
            }
        }
    }

    /**
     * Builds the SET_ACTIVITY payload.
     *
     * <p>Fields are collected and joined rather than concatenated with trailing commas that get
     * mopped up afterwards. The old version stripped every {@code ,}} in the finished document,
     * which also reached inside the user's own text -- a map or template containing that pair came
     * out quietly altered.
     */
    private String buildActivityJson(RichPresence presence) {
        List<String> activity = new ArrayList<>(6);
        if (presence.details != null && !presence.details.isEmpty()) {
            activity.add(quoted("details", presence.details));
        }
        if (presence.state != null && !presence.state.isEmpty()) {
            activity.add(quoted("state", presence.state));
        }
        if (presence.startTimestamp > 0) {
            activity.add("\"timestamps\":{\"start\":" + presence.startTimestamp + "}");
        }
        if (presence.partyMax > 0) {
            int size = Math.max(1, Math.min(presence.partySize, presence.partyMax));
            activity.add("\"party\":{\"size\":[" + size + "," + presence.partyMax + "]}");
        }

        List<String> assets = new ArrayList<>(4);
        addAsset(assets, "large_image", presence.largeImage);
        addAsset(assets, "large_text", presence.largeText);
        addAsset(assets, "small_image", presence.smallImage);
        addAsset(assets, "small_text", presence.smallText);
        if (!assets.isEmpty()) {
            activity.add("\"assets\":{" + join(assets) + "}");
        }

        return "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + getPid()
                + ",\"activity\":{" + join(activity) + "}},\"nonce\":\"" + nextNonce() + "\"}";
    }

    private static void addAsset(List<String> into, String key, String value) {
        if (value != null && !value.isEmpty()) {
            into.add(quoted(key, value));
        }
    }

    private static String quoted(String key, String value) {
        return "\"" + key + "\":\"" + escape(value) + "\"";
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private static long nonceCounter;

    private static synchronized String nextNonce() {
        return System.currentTimeMillis() + "-" + (++nonceCounter);
    }

    private static long getPid() {
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Object current = ph.getMethod("current").invoke(null);
            return (Long) ph.getMethod("pid").invoke(current);
        }
        catch (Throwable t) {
            try {
                String name = ManagementFactory.getRuntimeMXBean().getName();
                int idx = name.indexOf('@');
                if (idx != -1) {
                    return Long.parseLong(name.substring(0, idx));
                }
            }
            catch (Throwable ignored) {
            }
            return 0L;
        }
    }

    /**
     * Escapes a string for JSON.
     *
     * <p>Quotes and backslashes were handled; control characters were not, so a stray newline or
     * tab anywhere in an item name or a template produced a malformed frame that Discord rejected
     * whole. The presence then simply did not change, with nothing to say why.
     */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** One connection to a single Discord instance. Only ever touched by the worker. */
    private static final class Connection {
        private final String clientId;
        private final int pipeIndex;
        private volatile RandomAccessFile pipe;
        private volatile boolean alive;
        String label = "";

        Connection(String clientId, int pipeIndex) {
            this.clientId = clientId;
            this.pipeIndex = pipeIndex;
            this.label = "pipe " + pipeIndex;
        }

        boolean isAlive() {
            return alive;
        }

        boolean connect() {
            String[] prefixes = {"\\\\.\\pipe\\discord-ipc-", "\\\\?\\pipe\\discord-ipc-"};
            for (String prefix : prefixes) {
                try {
                    pipe = new RandomAccessFile(prefix + pipeIndex, "rw");
                    sendPacket(OP_HANDSHAKE, "{\"v\":1,\"client_id\":\"" + clientId + "\"}");

                    Frame response = readFrame();
                    if (response == null || response.payload.contains("\"evt\":\"ERROR\"")) {
                        if (response != null) {
                            System.out.println("[discord rpc] " + label + " refused: " + response.payload);
                        }
                        closeQuietly();
                        continue;
                    }

                    String endpoint = extractField(response.payload, "api_endpoint");
                    String username = extractField(response.payload, "username");
                    label = "pipe " + pipeIndex;
                    if (endpoint != null) {
                        label += " [" + endpoint.replace("//", "").replace("/api", "") + "]";
                    }
                    if (username != null) {
                        label += " (" + username + ")";
                    }

                    System.out.println("[discord rpc] connected: " + label);
                    alive = true;
                    return true;
                }
                catch (Exception e) {
                    closeQuietly();
                }
            }
            return false;
        }

        /** @return true when Discord acknowledged this update. */
        boolean send(String json) {
            if (!alive) {
                return false;
            }
            try {
                sendPacket(OP_FRAME, json);

                // Discord is free to send events we did not ask for, and used to send a PING that
                // went unanswered. Taking the next frame as the reply meant one stray frame put
                // every later read one behind, so replies drifted further out of step until the
                // connection was useless. Frames are now matched, and anything else handled.
                for (int i = 0; i < MAX_REPLY_SCAN; i++) {
                    Frame frame = readFrame();
                    if (frame == null) {
                        alive = false;
                        return false;
                    }
                    if (frame.op == OP_CLOSE) {
                        System.out.println("[discord rpc] " + label + " closed by Discord: " + frame.payload);
                        alive = false;
                        return false;
                    }
                    if (frame.op == OP_PING) {
                        sendPacket(OP_PONG, frame.payload);
                        continue;
                    }
                    if (frame.op != OP_FRAME) {
                        continue;
                    }
                    if (frame.payload.contains("\"cmd\":\"SET_ACTIVITY\"")) {
                        if (frame.payload.contains("\"evt\":\"ERROR\"")) {
                            System.out.println("[discord rpc] " + label + " rejected update: " + frame.payload);
                            return false;
                        }
                        return true;
                    }
                }

                // Never found our reply. The stream is out of step, so the connection is finished
                // rather than left to drift.
                System.out.println("[discord rpc] " + label + " lost sync, reconnecting");
                alive = false;
                return false;
            }
            catch (Exception e) {
                System.out.println("[discord rpc] " + label + " failed: " + e);
                alive = false;
                return false;
            }
        }

        /** Closes the pipe from any thread, which also frees a worker blocked in a read. */
        void forceClose() {
            alive = false;
            RandomAccessFile open = pipe;
            if (open == null) {
                return;
            }
            try {
                open.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(OP_CLOSE).putInt(0).array());
            }
            catch (Exception ignored) {
            }
            closeQuietly();
        }

        private void closeQuietly() {
            RandomAccessFile open = pipe;
            pipe = null;
            alive = false;
            try {
                if (open != null) {
                    open.close();
                }
            }
            catch (Exception ignored) {
            }
        }

        private void sendPacket(int opcode, String json) throws Exception {
            RandomAccessFile open = pipe;
            if (open == null) {
                throw new IllegalStateException("pipe closed");
            }
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(8 + jsonBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(opcode);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            open.write(buffer.array());
        }

        private Frame readFrame() throws Exception {
            byte[] header = new byte[8];
            if (!readFully(header)) {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int op = bb.getInt();
            int len = bb.getInt();
            if (len < 0 || len >= 65536) {
                return null;
            }
            if (len == 0) {
                return new Frame(op, "");
            }
            byte[] data = new byte[len];
            if (!readFully(data)) {
                return null;
            }
            return new Frame(op, new String(data, StandardCharsets.UTF_8));
        }

        private boolean readFully(byte[] buf) throws Exception {
            RandomAccessFile open = pipe;
            if (open == null) {
                return false;
            }
            int off = 0;
            while (off < buf.length) {
                int n = open.read(buf, off, buf.length - off);
                if (n == -1) {
                    return false;
                }
                off += n;
            }
            return true;
        }

        private static String extractField(String json, String field) {
            String key = "\"" + field + "\":\"";
            int start = json.indexOf(key);
            if (start == -1) {
                return null;
            }
            start += key.length();
            int end = json.indexOf('"', start);
            return end == -1 ? null : json.substring(start, end);
        }
    }

    private static final class Frame {
        final int op;
        final String payload;

        Frame(int op, String payload) {
            this.op = op;
            this.payload = payload;
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
        public int partySize;
        public int partyMax;

        public RichPresence details(String details) { this.details = details; return this; }
        public RichPresence state(String state) { this.state = state; return this; }
        public RichPresence startTimestamp(long timestamp) { this.startTimestamp = timestamp; return this; }
        public RichPresence largeImage(String key, String text) { this.largeImage = key; this.largeText = text; return this; }
        public RichPresence smallImage(String key, String text) { this.smallImage = key; this.smallText = text; return this; }

        /** Renders next to the state as "(1 of 10)". A max of zero leaves the party off entirely. */
        public RichPresence party(int size, int max) { this.partySize = size; this.partyMax = max; return this; }

        /** Everything the pipe actually sends, so an unchanged presence is not sent twice. */
        public String signature() {
            return details + " " + state + " " + largeImage + " " + largeText
                    + " " + smallImage + " " + smallText
                    + " " + partySize + "/" + partyMax + " " + startTimestamp;
        }
    }
}
