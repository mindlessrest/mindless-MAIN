package mindless.utility;

import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
public class DiscordRPC {
private static final int MAX_PIPES = 10;
private static final long RECONNECT_INTERVAL_MS = 5000L;
private static final long POLL_INTERVAL_MS = 100L;
private static final long STALL_TIMEOUT_MS = 10000L;
private static final int RATE_LIMIT_BURST = 5;
    private static final long RATE_LIMIT_REFILL_MS = 4000L;
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
private volatile Connection connectingPipe;
    private Thread worker;

    public DiscordRPC(String clientId) {
        this.clientId = clientId;
    }
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
public void update(RichPresence presence) {
        desired = presence;
        if (!running) {
            connect();
            return;
        }
        if (System.currentTimeMillis() - lastCycleAt > STALL_TIMEOUT_MS) {
            System.out.println("[discord rpc] worker stalled, dropping connections to free it");
            for (Connection c : connections) {
                c.forceClose();
            }
            Connection stuck = connectingPipe;
            if (stuck != null) {
                stuck.forceClose();
            }
        }
    }

    public boolean isConnected() {
        return !connections.isEmpty();
    }
public List<String> describeConnections() {
        List<String> out = new ArrayList<>();
        for (Connection c : connections) {
            out.add(c.label);
        }
        return out;
    }
public RichPresence getDesired() {
        return desired;
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
                if (now >= nextConnectAt) {
                    nextConnectAt = now + RECONNECT_INTERVAL_MS;
                    int before = connections.size();
                    findPipes();
                    if (connections.size() != before) {
                        sentSignature = null;
                    }
                }

                RichPresence target = desired;
                if (target != null && !connections.isEmpty() && tokens > 0) {
                    String signature = target.signature();
                    if (!signature.equals(sentSignature)) {
                        tokens--;
                        if (send(target)) {
                            sentSignature = signature;
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
        Set<Integer> already = new HashSet<>();
        for (Connection c : connections) {
            already.add(c.getPipeIndex());
        }
        for (int i = 0; i < MAX_PIPES; i++) {
            if (already.contains(i)) {
                continue;
            }
            Connection c = new Connection(clientId, i);
            connectingPipe = c;
            try {
                if (c.connect()) {
                    connections.add(c);
                }
            }
            finally {
                connectingPipe = null;
            }
        }
    }
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

        int getPipeIndex() {
            return pipeIndex;
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
boolean send(String json) {
            if (!alive) {
                return false;
            }
            try {
                sendPacket(OP_FRAME, json);
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
public RichPresence party(int size, int max) { this.partySize = size; this.partyMax = max; return this; }
public String signature() {
            return details + " " + state + " " + largeImage + " " + largeText
                    + " " + smallImage + " " + smallText
                    + " " + partySize + "/" + partyMax + " " + startTimestamp;
        }
    }
}