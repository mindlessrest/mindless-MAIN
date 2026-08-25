package mindless.utility;

import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects to EVERY Discord client pipe it finds (stable, PTB, Canary, multiple
 * accounts, etc.) and pushes the same Rich Presence to all of them.
 */
public class DiscordRPC {
    private final String clientId;
    private final List<Connection> connections = new ArrayList<>();

    public DiscordRPC(String clientId) {
        this.clientId = clientId;
    }

    public synchronized void connect() {
        connections.clear();
        for (int i = 0; i < 10; i++) {
            Connection c = new Connection(clientId, i);
            if (c.connect()) {
                connections.add(c);
            }
        }
        if (connections.isEmpty()) {
            System.out.println("[discord rpc error] no Discord instances found on pipes 0-9. Is Discord running?");
        } else {
            System.out.println("[discord rpc] connected to " + connections.size() + " Discord instance(s)");
        }
    }

    public synchronized void update(RichPresence presence) {
        if (connections.isEmpty()) connect();
        for (Connection c : connections) {
            c.update(presence);
        }
    }

    public synchronized void close() {
        for (Connection c : connections) {
            c.close();
        }
        connections.clear();
    }

    /** One connection to a single Discord instance (one pipe). All I/O is sequential - no threads. */
    private static class Connection {
        private final String clientId;
        private final int pipeIndex;
        private RandomAccessFile pipe;
        private boolean running = false;
        private String label = "";

        Connection(String clientId, int pipeIndex) {
            this.clientId = clientId;
            this.pipeIndex = pipeIndex;
        }

        boolean connect() {
            String[] prefixes = { "\\\\.\\pipe\\discord-ipc-", "\\\\?\\pipe\\discord-ipc-" };
            for (String prefix : prefixes) {
                String pipePath = prefix + pipeIndex;
                try {
                    pipe = new RandomAccessFile(pipePath, "rw");

                    String handshake = "{\"v\":1,\"client_id\":\"" + clientId + "\"}";
                    sendPacket(0, handshake);

                    String response = readFrame("handshake");
                    if (response == null) {
                        pipe.close();
                        continue;
                    }

                    label = "pipe " + pipeIndex;
                    String endpoint = extractField(response, "api_endpoint");
                    String username = extractField(response, "username");
                    if (endpoint != null) label += " [" + endpoint.replace("//", "").replace("/api", "") + "]";
                    if (username != null) label += " (" + username + ")";

                    if (response.contains("\"evt\":\"ERROR\"")) {
                        System.out.println("[discord rpc] " + label + " -> ERROR: " + response);
                        pipe.close();
                        continue;
                    }

                    System.out.println("[discord rpc] connected: " + label);
                    running = true;
                    return true;
                } catch (Exception e) {
                    try { if (pipe != null) pipe.close(); } catch (Exception ignored) {}
                }
            }
            return false;
        }

        void update(RichPresence presence) {
            if (!running) {
                System.out.println("[discord rpc] " + label + " update() called but running=false, skipping");
                return;
            }
            try {
                long pid = getPid();

                String activityJson = "{"
                        + "\"cmd\":\"SET_ACTIVITY\","
                        + "\"args\":{"
                        + "\"pid\": " + pid + ","
                        + "\"activity\": {"
                        + (presence.details != null ? "\"details\": \"" + escape(presence.details) + "\"," : "")
                        + (presence.state != null ? "\"state\": \"" + escape(presence.state) + "\"," : "")
                        + (presence.startTimestamp > 0 ? "\"timestamps\": {\"start\": " + presence.startTimestamp + "}," : "")
                        + (presence.partyMax > 0 ? "\"party\": {\"size\": ["
                                + Math.max(1, Math.min(presence.partySize, presence.partyMax))
                                + ", " + presence.partyMax + "]}," : "")
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
                activityJson = activityJson.replace(",}", "}").replace(",\"assets\": {}", "");

                sendPacket(1, activityJson);
                String reply = readFrame("SET_ACTIVITY reply");
                if (reply != null && reply.contains("\"evt\":\"ERROR\"")) {
                    System.out.println("[discord rpc] *** " + label + " REJECTED: " + reply + " ***");
                } else {
                    System.out.println("[discord rpc] " + label + " updated: " + presence.details + " / " + presence.state);
                }
            } catch (Exception e) {
                System.out.println("[discord rpc error] " + label + " update() threw: " + e);
                close();
            }
        }

        void close() {
            running = false;
            try {
                if (pipe != null) {
                    byte[] closeHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(0).array();
                    pipe.write(closeHeader);
                    pipe.close();
                }
            } catch (Exception ignored) {}
        }

        private static long getPid() {
            try {
                Class<?> ph = Class.forName("java.lang.ProcessHandle");
                Object current = ph.getMethod("current").invoke(null);
                return (Long) ph.getMethod("pid").invoke(current);
            } catch (Throwable t) {
                try {
                    String name = ManagementFactory.getRuntimeMXBean().getName();
                    int idx = name.indexOf('@');
                    if (idx != -1) {
                        return Long.parseLong(name.substring(0, idx));
                    }
                } catch (Throwable ignored) {}
                return 0L;
            }
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

        private String readFrame(String what) throws Exception {
            byte[] lenBuf = new byte[8];
            if (!readFully(lenBuf)) {
                System.out.println("[discord rpc] pipe " + pipeIndex + ": EOF reading " + what + " header");
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN);
            bb.getInt();
            int len = bb.getInt();
            if (len <= 0 || len >= 16384) {
                System.out.println("[discord rpc] pipe " + pipeIndex + ": " + what + " gave bad length " + len);
                return null;
            }
            byte[] data = new byte[len];
            if (!readFully(data)) {
                System.out.println("[discord rpc] pipe " + pipeIndex + ": EOF reading " + what + " body");
                return null;
            }
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

        /** Everything the pipe actually sends, so the module can skip identical updates. */
        public String signature() {
            return details + " " + state + " " + largeImage + " " + largeText
                    + " " + smallImage + " " + smallText
                    + " " + partySize + "/" + partyMax + " " + startTimestamp;
        }
    }
}