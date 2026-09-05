package dev.authsys;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * IPC server used by the loader to pass a session token to the launched software.
 *
 * <p>Uses a local TCP socket bound to loopback (127.0.0.1) on a random port.
 * The channel name (passed to the software as argv[1]) is "localhost:PORT".
 *
 * <p>The token is not sent in plaintext — it's encrypted with AES-256-GCM using
 * SHA-256(hwid) as the key. Only the same machine can derive the decryption key.
 *
 * <p>Wire format: [4-byte payload length][12-byte GCM nonce][ciphertext+tag]
 */
public class IPCServer implements Closeable {

    private final ServerSocket serverSocket;
    private final String channelName;

    /**
     * Creates the IPC server bound to loopback on a random port.
     *
     * @throws IOException if the socket cannot be created
     */
    public IPCServer() throws IOException {
        // Bind to loopback only — never expose on the network
        this.serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.channelName = "localhost:" + serverSocket.getLocalPort();
    }

    /**
     * Returns the channel name to pass to the software process as a CLI argument.
     * Format: "localhost:PORT"
     */
    public String getChannelName() {
        return channelName;
    }

    /**
     * Blocks until the software connects, then sends the encrypted token.
     *
     * <p>Flow:
     * <ol>
     *   <li>Wait for incoming connection (with timeout)</li>
     *   <li>Encrypt the token: key = SHA-256(hwid), then AES-256-GCM encrypt</li>
     *   <li>Send payload length (4 bytes big-endian) followed by the encrypted payload</li>
     * </ol>
     *
     * @param token the session token to send
     * @param hwid  the hardware ID (used to derive the encryption key)
     * @throws IOException   if the connection or write fails
     * @throws AuthException if encryption fails
     */
    public void waitAndSend(String token, String hwid) throws IOException, AuthException {
        serverSocket.setSoTimeout(30000); // 30 second timeout

        try (Socket client = serverSocket.accept()) {
            // Derive encryption key from HWID — only the same machine can compute this
            byte[] key = CryptoUtil.sha256(hwid.getBytes(StandardCharsets.UTF_8));

            // Encrypt the token: wire format is [12-byte nonce][ciphertext+tag]
            byte[] encrypted = CryptoUtil.aesGcmEncrypt(
                    token.getBytes(StandardCharsets.UTF_8), key);

            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.writeInt(encrypted.length);
            out.write(encrypted);
            out.flush();
        }
    }

    /** Closes the server socket. */
    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
    }
}
