package mindless.utility;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
public class DiscordRPC {
private static final int MAX_PIPES = 10;
    // The browser/websocket transport. Forks that do not publish a named pipe (Dorion, arRPC based
    // setups) listen here instead, and official Discord answers on it too.
    private static final int SOCKET_PORT_FIRST = 6463;
    private static final int SOCKET_PORT_LAST = 6472;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 200;
    private static final int SOCKET_READ_TIMEOUT_MS = 1500;
private static final long RECONNECT_INTERVAL_MS = 5000L;
private static final long MAX_RECONNECT_INTERVAL_MS = 60000L;
private static final int MAX_CONSECUTIVE_RECONNECT_FAILURES = 10;
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
    private final List<RpcLink> connections = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();

    private volatile RichPresence desired;
    private volatile boolean running;
    private volatile long lastCycleAt;
private volatile RpcLink connectingPipe;
    private volatile int lastWorkingPipeIndex = -1;
    private volatile long reconnectSignal;
    private Thread worker;

    /**
     * Report a connection event to both the console and the diagnostics log.
     *
     * Standard output does not reach a readable file under Lunar, so everything this class
     * had to say about which endpoint answered and what it replied was going nowhere. The
     * diagnostics log is the one place a connection problem here can actually be read back.
     */
    private static void note(String message) {
        if (!mindless.runtime.LunarEventBridge.isDirectLunar()) {
            System.out.println("[discord rpc] " + message);
        }
        mindless.utility.Diagnostics.log("rpc", message);
    }

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
            note("worker stalled, dropping connections to free it");
            for (RpcLink c : connections) {
                c.forceClose();
            }
            RpcLink stuck = connectingPipe;
            if (stuck != null) {
                stuck.forceClose();
            }
        }
    }

    /** Resume discovery after an external state change, such as joining a world. */
    public void requestReconnect() {
        reconnectSignal++;
    }

    public boolean isConnected() {
        return !connections.isEmpty();
    }
public List<String> describeConnections() {
        List<String> out = new ArrayList<>();
        for (RpcLink c : connections) {
            out.add(c.label());
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
        for (RpcLink c : connections) {
            c.forceClose();
        }
        connections.clear();
    }
private void pump() {
        int tokens = RATE_LIMIT_BURST;
        long lastRefillAt = System.currentTimeMillis();
        long nextConnectAt = 0L;
        long observedReconnectSignal = reconnectSignal;
        int consecutiveReconnectFailures = 0;
        boolean reconnectSuspended = false;
        String sentSignature = null;

        try {
            while (running) {
                long now = System.currentTimeMillis();
                lastCycleAt = now;

                if (observedReconnectSignal != reconnectSignal) {
                    observedReconnectSignal = reconnectSignal;
                    consecutiveReconnectFailures = 0;
                    reconnectSuspended = false;
                    nextConnectAt = 0L;
                }

                long elapsedRefills = (now - lastRefillAt) / RATE_LIMIT_REFILL_MS;
                if (elapsedRefills > 0) {
                    tokens = (int) Math.min(RATE_LIMIT_BURST, tokens + elapsedRefills);
                    lastRefillAt += elapsedRefills * RATE_LIMIT_REFILL_MS;
                }

                dropDeadConnections();
                if (connections.isEmpty() && nextConnectAt == Long.MAX_VALUE) {
                    nextConnectAt = now;
                }
                if (connections.isEmpty() && !reconnectSuspended && now >= nextConnectAt) {
                    int before = connections.size();
                    findPipes();
                    int after = connections.size();
                    if (after != before) {
                        sentSignature = null;
                    }

                    if (after > 0) {
                        consecutiveReconnectFailures = 0;
                        nextConnectAt = Long.MAX_VALUE;
                    }
                    else {
                        consecutiveReconnectFailures++;
                        if (consecutiveReconnectFailures >= MAX_CONSECUTIVE_RECONNECT_FAILURES) {
                            reconnectSuspended = true;
                            note("Discord unavailable; discovery paused");
                        }
                        else {
                            nextConnectAt = now + reconnectDelay(consecutiveReconnectFailures);
                        }
                    }
                }

                RichPresence target = desired;
                if (target != null && !connections.isEmpty() && tokens > 0) {
                    String signature = target.signature();
                    if (!signature.equals(sentSignature)) {
                        tokens--;
                        if (send(target)) {
                            sentSignature = signature;
                            note("set: " + target.details
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
            for (RpcLink c : connections) {
                c.forceClose();
            }
            connections.clear();
            synchronized (lifecycleLock) {
                running = false;
                worker = null;
            }
        }
    }

    private static long reconnectDelay(int failures) {
        int shift = Math.max(0, Math.min(4, failures - 1));
        return Math.min(MAX_RECONNECT_INTERVAL_MS, RECONNECT_INTERVAL_MS << shift);
    }
private boolean send(RichPresence presence) {
        String json = buildActivityJson(presence);
        boolean delivered = false;
        for (RpcLink c : connections) {
            if (c.send(json)) {
                delivered = true;
            }
        }
        return delivered;
    }

    private void dropDeadConnections() {
        for (Iterator<RpcLink> it = connections.iterator(); it.hasNext(); ) {
            RpcLink c = it.next();
            if (!c.isAlive()) {
                c.forceClose();
                connections.remove(c);
                note("lost " + c.label() + ", will look again");
            }
        }
    }
private void findPipes() {
        Set<String> already = new HashSet<>();
        for (RpcLink c : connections) {
            already.add(c.key());
        }
        int preferredPipe = lastWorkingPipeIndex;
        if (preferredPipe >= 0 && preferredPipe < MAX_PIPES) {
            tryLink(already, new PipeConnection(clientId, preferredPipe));
        }
        for (int i = 0; i < MAX_PIPES; i++) {
            if (i != preferredPipe) {
                tryLink(already, new PipeConnection(clientId, i));
            }
        }
        // Every client that answers gets its own link, so official, Canary, Vesktop, Dorion and
        // anything else running at the same time all show the presence.
        for (int port = SOCKET_PORT_FIRST; port <= SOCKET_PORT_LAST; port++) {
            tryLink(already, new SocketConnection(clientId, port));
        }
    }

    private void tryLink(Set<String> already, RpcLink link) {
        if (already.contains(link.key())) {
            return;
        }
        connectingPipe = link;
        try {
            if (link.connect()) {
                connections.add(link);
                if (link instanceof PipeConnection) {
                    lastWorkingPipeIndex = ((PipeConnection) link).pipeIndex;
                }
            }
        }
        finally {
            connectingPipe = null;
        }
    }

    private interface RpcLink {
        boolean isAlive();

        String key();

        String label();

        boolean connect();

        boolean send(String json);

        void forceClose();
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
        }
    }
private static final class PipeConnection implements RpcLink {
        private final String clientId;
        private final int pipeIndex;
        private volatile RandomAccessFile pipe;
        private volatile boolean alive;
        String label = "";

        PipeConnection(String clientId, int pipeIndex) {
            this.clientId = clientId;
            this.pipeIndex = pipeIndex;
            this.label = "pipe " + pipeIndex;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public String key() {
            return "pipe:" + pipeIndex;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean connect() {
            String[] prefixes = {"\\\\.\\pipe\\discord-ipc-", "\\\\?\\pipe\\discord-ipc-"};
            for (String prefix : prefixes) {
                try {
                    pipe = new RandomAccessFile(prefix + pipeIndex, "rw");
                    sendPacket(OP_HANDSHAKE, "{\"v\":1,\"client_id\":\"" + clientId + "\"}");

                    Frame response = readFrame();
                    if (response == null || response.payload.contains("\"evt\":\"ERROR\"")) {
                        if (response != null) {
                            note("" + label + " refused: " + response.payload);
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

                    note("connected: " + label);
                    alive = true;
                    return true;
                }
                catch (Exception e) {
                    closeQuietly();
                }
            }
            return false;
        }
@Override
        public boolean send(String json) {
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
                        note("" + label + " closed by Discord: " + frame.payload);
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
                            note("" + label + " rejected update: " + frame.payload);
                            return false;
                        }
                        return true;
                    }
                }
                note("" + label + " lost sync, reconnecting");
                alive = false;
                return false;
            }
            catch (Exception e) {
                note("" + label + " failed: " + e);
                alive = false;
                return false;
            }
        }
@Override
        public void forceClose() {
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


    /**
     * Discord's other RPC transport: a websocket on 127.0.0.1:6463-6472.
     *
     * Clients that never publish a \\.\pipe\discord-ipc-N pipe -- Dorion, and arRPC based setups
     * generally -- are only reachable this way. There is no handshake frame here; the client id
     * travels in the upgrade URL and the connection is ready once Discord dispatches READY. After
     * that the payloads are exactly the ones the pipe transport sends, so nothing above this class
     * has to know which kind of link it got.
     */
    private static final class SocketConnection implements RpcLink {
        private static final SecureRandom RANDOM = new SecureRandom();

        private final String clientId;
        private final int port;
        private volatile Socket socket;
        private volatile InputStream in;
        private volatile OutputStream out;
        private volatile boolean alive;
        private String label;

        SocketConnection(String clientId, int port) {
            this.clientId = clientId;
            this.port = port;
            this.label = "socket " + port;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public String key() {
            return "socket:" + port;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean connect() {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_CONNECT_TIMEOUT_MS);
                s.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                socket = s;
                in = s.getInputStream();
                out = s.getOutputStream();

                if (!upgrade()) {
                    closeQuietly();
                    return false;
                }

                // Wait for READY before claiming the link. A port that answers HTTP but is not a
                // Discord client will simply never send it and time out.
                for (int i = 0; i < MAX_REPLY_SCAN; i++) {
                    String payload = readText();
                    if (payload == null) {
                        closeQuietly();
                        return false;
                    }
                    if (payload.contains("\"evt\":\"ERROR\"")) {
                        note("" + label + " refused: " + payload);
                        closeQuietly();
                        return false;
                    }
                    if (payload.contains("\"evt\":\"READY\"")) {
                        String username = extractField(payload, "username");
                        if (username != null) {
                            label = "socket " + port + " (" + username + ")";
                        }
                        note("connected: " + label);
                        alive = true;
                        return true;
                    }
                }
                closeQuietly();
                return false;
            }
            catch (Exception e) {
                closeQuietly();
                return false;
            }
        }

        private boolean upgrade() throws Exception {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);

            String request = "GET /?v=1&client_id=" + clientId + "&encoding=json HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: https://discord.com\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder head = new StringBuilder(256);
            int consecutive = 0;
            while (head.length() < 4096) {
                int b = in.read();
                if (b == -1) {
                    return false;
                }
                head.append((char) b);
                if (b == '\n' || b == '\r') {
                    consecutive++;
                    if (consecutive == 4) {
                        break;
                    }
                }
                else {
                    consecutive = 0;
                }
            }
            String response = head.toString();
            int lineEnd = response.indexOf('\r');
            String statusLine = lineEnd == -1 ? response : response.substring(0, lineEnd);
            return statusLine.contains("101");
        }

        @Override
        public boolean send(String json) {
            if (!alive) {
                return false;
            }
            try {
                writeText(json);
                for (int i = 0; i < MAX_REPLY_SCAN; i++) {
                    String payload = readText();
                    if (payload == null) {
                        alive = false;
                        return false;
                    }
                    if (payload.contains("\"cmd\":\"SET_ACTIVITY\"")) {
                        if (payload.contains("\"evt\":\"ERROR\"")) {
                            note("" + label + " rejected update: " + payload);
                            return false;
                        }
                        return true;
                    }
                }
                alive = false;
                return false;
            }
            catch (Exception e) {
                note("" + label + " failed: " + e);
                alive = false;
                return false;
            }
        }

        private void writeText(String json) throws Exception {
            OutputStream stream = out;
            if (stream == null) {
                throw new IllegalStateException("socket closed");
            }
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(payload.length + 14);
            buffer.put((byte) 0x81);

            // Client frames are always masked, per RFC 6455.
            if (payload.length < 126) {
                buffer.put((byte) (0x80 | payload.length));
            }
            else if (payload.length <= 0xFFFF) {
                buffer.put((byte) (0x80 | 126));
                buffer.put((byte) (payload.length >>> 8));
                buffer.put((byte) payload.length);
            }
            else {
                buffer.put((byte) (0x80 | 127));
                buffer.putLong(payload.length);
            }

            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            buffer.put(mask);
            for (int i = 0; i < payload.length; i++) {
                buffer.put((byte) (payload[i] ^ mask[i & 3]));
            }

            buffer.flip();
            byte[] frame = new byte[buffer.remaining()];
            buffer.get(frame);
            stream.write(frame);
            stream.flush();
        }

        /** Reads frames until a text one arrives, answering pings and stopping on close. */
        private String readText() throws Exception {
            for (int guard = 0; guard < 16; guard++) {
                int first = in.read();
                if (first == -1) {
                    return null;
                }
                int opcode = first & 0x0F;
                int second = in.read();
                if (second == -1) {
                    return null;
                }
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((long) readByte() << 8) | readByte();
                }
                else if (length == 127) {
                    length = 0L;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | readByte();
                    }
                }
                if (length < 0 || length > 1 << 20) {
                    return null;
                }

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) length];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                if (opcode == 0x8) {
                    return null;
                }
                if (opcode == 0x9) {
                    writePong(payload);
                    continue;
                }
                if (opcode == 0x1 || opcode == 0x0) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        private void writePong(byte[] payload) throws Exception {
            OutputStream stream = out;
            if (stream == null) {
                return;
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            ByteBuffer buffer = ByteBuffer.allocate(payload.length + 6);
            buffer.put((byte) 0x8A);
            buffer.put((byte) (0x80 | Math.min(125, payload.length)));
            buffer.put(mask);
            for (int i = 0; i < payload.length && i < 125; i++) {
                buffer.put((byte) (payload[i] ^ mask[i & 3]));
            }
            buffer.flip();
            byte[] frame = new byte[buffer.remaining()];
            buffer.get(frame);
            stream.write(frame);
            stream.flush();
        }

        private int readByte() throws Exception {
            int b = in.read();
            if (b == -1) {
                throw new IllegalStateException("socket closed");
            }
            return b;
        }

        private void readFully(byte[] buf) throws Exception {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n == -1) {
                    throw new IllegalStateException("socket closed");
                }
                off += n;
            }
        }

        @Override
        public void forceClose() {
            alive = false;
            closeQuietly();
        }

        private void closeQuietly() {
            Socket open = socket;
            socket = null;
            in = null;
            out = null;
            alive = false;
            try {
                if (open != null) {
                    open.close();
                }
            }
            catch (Exception ignored) {
            }
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
