package mindless.command.impl;

import mindless.backend.BackendClient;
import mindless.command.Command;
import mindless.command.CommandInput;
import net.minecraft.client.Minecraft;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class Irc extends Command {
    // Shared encryption key — all Mindless clients use the same key.
    // AES-256-GCM. Key is derived from this seed.
    private static final byte[] KEY = deriveKey("mindless-irc-v1-shared-secret-key!");

    public Irc() {
        super("irc", "irc", "chat");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0) {
            reply("&7Usage: &f.irc <message>");
            return;
        }

        BackendClient backend = BackendClient.getInstance();
        if (!backend.isConnected()) {
            reply("&cNot connected to backend");
            return;
        }

        String message = input.joinArguments(0);
        String playerName = Minecraft.getMinecraft().getSession().getUsername();

        // Encrypt the message
        String encrypted = encrypt(message);
        if (encrypted == null) {
            reply("&cFailed to encrypt message");
            return;
        }

        Map<String, String> payload = new HashMap<>();
        payload.put("name", playerName);
        payload.put("data", encrypted);
        backend.send("irc_send", payload);
    }

    /**
     * Decrypt an incoming IRC message. Called by the IRC listener.
     */
    public static String decrypt(String data) {
        try {
            byte[] raw = Base64.getDecoder().decode(data);
            // First 12 bytes = nonce, rest = ciphertext + tag
            byte[] nonce = new byte[12];
            byte[] ciphertext = new byte[raw.length - 12];
            System.arraycopy(raw, 0, nonce, 0, 12);
            System.arraycopy(raw, 12, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), spec);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String encrypt(String message) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), spec);
            byte[] ciphertext = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));

            // Prepend nonce to ciphertext
            byte[] result = new byte[12 + ciphertext.length];
            System.arraycopy(nonce, 0, result, 0, 12);
            System.arraycopy(ciphertext, 0, result, 12, ciphertext.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] deriveKey(String seed) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Fallback — should never happen
            return new byte[32];
        }
    }
}
