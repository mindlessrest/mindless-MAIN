package mindless.accountmanager.utils;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * At-rest encryption for accounts.json.
 *
 * The file holds Microsoft refresh and access tokens. It was written as plain text in .minecraft,
 * where any process running as the user -- or anything that walks the game directory -- could read
 * every account.
 *
 * Windows DPAPI, scoped to the current user, is what the loader already uses for auth.dat. The
 * ciphertext is bound to the Windows account, so copying accounts.json to another machine or
 * another user yields nothing. There is no key for us to store, lose or leak.
 *
 * Off Windows, or if Crypt32 will not bind, the file stays plain text -- no worse than before, and
 * the caller is told so it can say something rather than pretending.
 */
public final class AccountVault {

    /** Marks a file this class wrote, so a legacy plaintext file is recognisable on sight. */
    private static final String MAGIC = "MINDLESSVAULT1:";

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private static volatile Boolean available;
    private static volatile String unavailableReason = "not attempted";

    private AccountVault() {
    }

    public interface Crypt32 extends Library {
        Crypt32 INSTANCE = Native.load("Crypt32", Crypt32.class);

        boolean CryptProtectData(DataBlob in, WString description, Pointer entropy, Pointer reserved,
                                 Pointer prompt, int flags, DataBlob out);

        boolean CryptUnprotectData(DataBlob in, PointerByReference description, Pointer entropy,
                                   Pointer reserved, Pointer prompt, int flags, DataBlob out);
    }

    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer LocalFree(Pointer handle);
    }

    public static class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cbData", "pbData");
        }
    }

    /** Whether the file can actually be encrypted on this machine. */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                unavailableReason = "DPAPI is Windows only";
            }
            else {
                // Round-trip a probe rather than trusting the load; a missing or blocked Crypt32
                // shows up here instead of the first time someone saves an account.
                byte[] probe = "mindless".getBytes(StandardCharsets.UTF_8);
                byte[] sealed = protect(probe);
                ok = sealed != null && Arrays.equals(probe, unprotect(sealed));
                if (!ok) {
                    unavailableReason = "Crypt32 round trip failed";
                }
            }
        }
        catch (Throwable t) {
            unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            ok = false;
        }
        available = ok;
        return ok;
    }

    public static String unavailableReason() {
        return unavailableReason;
    }

    /**
     * Reads the file, decrypting when it was written by this class.
     *
     * A plaintext file is returned as-is so an existing accounts.json keeps working; the caller is
     * expected to save afterwards, which seals it.
     */
    public static String read(File file) throws IOException {
        if (file == null || !file.exists()) {
            return "";
        }
        byte[] raw = Files.readAllBytes(file.toPath());
        if (raw.length == 0) {
            return "";
        }

        byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (raw.length > magic.length && startsWith(raw, magic)) {
            byte[] payload = Arrays.copyOfRange(raw, magic.length, raw.length);
            byte[] plain = unprotect(java.util.Base64.getDecoder().decode(payload));
            if (plain == null) {
                // Same user, same machine, still undecryptable: the profile was rebuilt or the
                // file came from elsewhere. Better to report it than to silently wipe accounts.
                throw new IOException("accounts.json could not be decrypted on this Windows profile");
            }
            return new String(plain, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** Writes the file, encrypted where DPAPI is available and plain where it is not. */
    public static void write(File file, String contents) throws IOException {
        byte[] plain = contents.getBytes(StandardCharsets.UTF_8);
        if (isAvailable()) {
            byte[] sealed = protect(plain);
            if (sealed != null) {
                byte[] encoded = java.util.Base64.getEncoder().encode(sealed);
                byte[] out = new byte[MAGIC.length() + encoded.length];
                System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII), 0, out, 0, MAGIC.length());
                System.arraycopy(encoded, 0, out, MAGIC.length(), encoded.length);
                Files.write(file.toPath(), out);
                return;
            }
        }
        Files.write(file.toPath(), plain);
    }

    /** True when the file on disk is still unencrypted and could be sealed. */
    public static boolean isPlaintext(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        try {
            byte[] head = Files.readAllBytes(file.toPath());
            return !startsWith(head, MAGIC.getBytes(StandardCharsets.US_ASCII));
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] protect(byte[] plain) {
        DataBlob in = new DataBlob();
        DataBlob out = new DataBlob();
        Memory buffer = new Memory(Math.max(1, plain.length));
        try {
            buffer.write(0, plain, 0, plain.length);
            in.cbData = plain.length;
            in.pbData = buffer;
            boolean ok = Crypt32.INSTANCE.CryptProtectData(in, new WString("Mindless accounts"),
                    null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out);
            if (!ok || out.pbData == null) {
                return null;
            }
            try {
                return out.pbData.getByteArray(0, out.cbData);
            }
            finally {
                Kernel32.INSTANCE.LocalFree(out.pbData);
            }
        }
        catch (Throwable t) {
            return null;
        }
        finally {
            buffer.clear();
        }
    }

    private static byte[] unprotect(byte[] sealed) {
        DataBlob in = new DataBlob();
        DataBlob out = new DataBlob();
        Memory buffer = new Memory(Math.max(1, sealed.length));
        try {
            buffer.write(0, sealed, 0, sealed.length);
            in.cbData = sealed.length;
            in.pbData = buffer;
            boolean ok = Crypt32.INSTANCE.CryptUnprotectData(in, null, null, null, null,
                    CRYPTPROTECT_UI_FORBIDDEN, out);
            if (!ok || out.pbData == null) {
                return null;
            }
            try {
                return out.pbData.getByteArray(0, out.cbData);
            }
            finally {
                Kernel32.INSTANCE.LocalFree(out.pbData);
            }
        }
        catch (Throwable t) {
            return null;
        }
        finally {
            buffer.clear();
        }
    }
}
