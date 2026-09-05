package dev.authsys;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Cryptographic utilities: SHA-256, HKDF-SHA256, AES-256-GCM, hex encoding,
 * and nonce generation. All methods are stateless and thread-safe.
 */
public final class CryptoUtil {

    private static final int GCM_NONCE_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtil() {}

    /**
     * HKDF-SHA256 key derivation (RFC 5869).
     *
     * Two-phase process:
     *   1. Extract: condense the input key material into a fixed-length pseudorandom key (PRK)
     *      using HMAC-SHA256 with the salt as the HMAC key.
     *   2. Expand: generate the output key material by repeatedly HMACing the PRK
     *      with an incrementing counter byte, chaining each block's output into the next.
     *
     * @param ikm    input key material (e.g. raw session token bytes)
     * @param salt   optional salt (e.g. file_id as UTF-8 bytes); empty array if none
     * @param info   context string (e.g. "file-download" as UTF-8 bytes)
     * @param length desired output length in bytes (max 255 * 32 = 8160)
     * @return derived key of the requested length
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            // --- Extract phase ---
            // PRK = HMAC-SHA256(salt, ikm)
            // If salt is empty, RFC 5869 says use a zero-filled string of HashLen bytes
            byte[] actualSalt = (salt == null || salt.length == 0)
                    ? new byte[32]
                    : salt;
            byte[] prk = hmacSha256(actualSalt, ikm);

            // --- Expand phase ---
            // T(0) = empty
            // T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)  where i is a single byte 0x01..0xFF
            // Output = first 'length' bytes of T(1) || T(2) || ...
            int hashLen = 32;
            int blocksNeeded = (int) Math.ceil((double) length / hashLen);
            byte[] output = new byte[blocksNeeded * hashLen];
            byte[] previousBlock = new byte[0];

            for (int i = 1; i <= blocksNeeded; i++) {
                // Build input: previousBlock + info + counter byte
                byte[] hmacInput = new byte[previousBlock.length + info.length + 1];
                System.arraycopy(previousBlock, 0, hmacInput, 0, previousBlock.length);
                System.arraycopy(info, 0, hmacInput, previousBlock.length, info.length);
                hmacInput[hmacInput.length - 1] = (byte) i;

                previousBlock = hmacSha256(prk, hmacInput);
                System.arraycopy(previousBlock, 0, output, (i - 1) * hashLen, hashLen);
            }

            return Arrays.copyOf(output, length);
        } catch (Exception e) {
            throw new RuntimeException("HKDF-SHA256 failed", e);
        }
    }

    /**
     * Decrypts AES-256-GCM data. The first 12 bytes are the nonce; the rest is
     * ciphertext with the 16-byte GCM authentication tag appended.
     *
     * @param data combined [12-byte nonce][ciphertext+tag]
     * @param key  32-byte AES key
     * @return decrypted plaintext
     * @throws AuthException if GCM tag verification fails (data was tampered with)
     */
    public static byte[] aesGcmDecrypt(byte[] data, byte[] key) throws AuthException {
        try {
            if (data.length < GCM_NONCE_SIZE + 16) {
                throw new AuthException("DECRYPT_ERROR", "Data too short for GCM nonce + tag");
            }

            byte[] nonce = Arrays.copyOfRange(data, 0, GCM_NONCE_SIZE);
            byte[] ciphertext = Arrays.copyOfRange(data, GCM_NONCE_SIZE, data.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));

            return cipher.doFinal(ciphertext);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("DECRYPT_ERROR", "AES-GCM decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts plaintext with AES-256-GCM. Generates a random 12-byte nonce and
     * prepends it to the output. Wire format: [12-byte nonce][ciphertext+tag].
     *
     * @param plaintext data to encrypt
     * @param key       32-byte AES key
     * @return wire-format bytes ready to send
     * @throws AuthException if encryption fails
     */
    public static byte[] aesGcmEncrypt(byte[] plaintext, byte[] key) throws AuthException {
        try {
            byte[] nonce = new byte[GCM_NONCE_SIZE];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));

            byte[] ciphertextWithTag = cipher.doFinal(plaintext);

            // Prepend nonce to ciphertext
            byte[] result = new byte[GCM_NONCE_SIZE + ciphertextWithTag.length];
            System.arraycopy(nonce, 0, result, 0, GCM_NONCE_SIZE);
            System.arraycopy(ciphertextWithTag, 0, result, GCM_NONCE_SIZE, ciphertextWithTag.length);

            return result;
        } catch (Exception e) {
            throw new AuthException("ENCRYPT_ERROR", "AES-GCM encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * SHA-256 hash of the input string, returned as lowercase hex.
     *
     * @param input string to hash
     * @return 64-character lowercase hex digest
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * SHA-256 hash of raw bytes, returned as raw bytes.
     *
     * @param input bytes to hash
     * @return 32-byte hash
     */
    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a cryptographically random nonce: 32 random bytes, hex encoded.
     *
     * @return 64-character lowercase hex string
     */
    public static String generateNonce() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    /** Hex-encode a byte array to a lowercase hex string. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** Decode a hex string to a byte array. */
    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return result;
    }

    /** HMAC-SHA256: returns 32-byte MAC. */
    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
