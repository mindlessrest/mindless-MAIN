package dev.authsys;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * IPC client used by the software to receive a session token from the loader.
 *
 * <p>Connects to the local TCP socket specified by the channel name (format: "localhost:PORT").
 * The token is encrypted with AES-256-GCM using SHA-256(hwid) as the key.
 *
 * <p>Wire format expected: [4-byte payload length][12-byte GCM nonce][ciphertext+tag]
 */
public class IPCClient implements Closeable {

    private final String host;
    private final int port;
    private Socket socket;

    /**
     * @param channelName the IPC channel name from the loader (format: "localhost:PORT")
     */
    public IPCClient(String channelName) {
        String[] parts = channelName.split(":", 2);
        this.host = parts[0];
        this.port = Integer.parseInt(parts[1]);
    }

    /**
     * Connects to the loader and receives the encrypted session token.
     *
     * <p>Flow:
     * <ol>
     *   <li>Connect to the loader's IPC socket</li>
     *   <li>Read the payload length and encrypted data</li>
     *   <li>Derive the decryption key: SHA-256(hwid)</li>
     *   <li>Decrypt with AES-256-GCM</li>
     * </ol>
     *
     * @param hwid the hardware ID (must match the one the loader used to encrypt)
     * @return the decrypted session token
     * @throws IOException   if the connection or read fails
     * @throws AuthException if decryption fails (HWID mismatch or tampered data)
     */
    public String receiveToken(String hwid) throws IOException, AuthException {
        this.socket = new Socket(InetAddress.getLoopbackAddress(), port);

        DataInputStream in = new DataInputStream(socket.getInputStream());

        int payloadLength = in.readInt();
        byte[] encrypted = new byte[payloadLength];
        in.readFully(encrypted);

        // Derive the same key the loader used
        byte[] key = CryptoUtil.sha256(hwid.getBytes(StandardCharsets.UTF_8));

        byte[] tokenBytes = CryptoUtil.aesGcmDecrypt(encrypted, key);
        return new String(tokenBytes, StandardCharsets.UTF_8);
    }

    /** Closes the socket connection. */
    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
