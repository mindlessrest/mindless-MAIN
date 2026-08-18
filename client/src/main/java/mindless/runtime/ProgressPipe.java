package mindless.runtime;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class ProgressPipe {
    private static volatile OutputStream out;
    private static volatile boolean tried;

    private ProgressPipe() {}

    public static void connect() {
        if (tried) return;
        tried = true;
        try {
            out = new FileOutputStream("\\\\.\\pipe\\MindlessProgress");
        } catch (Exception ignored) {}
    }

    public static void report(float progress, String message) {
        if (!tried) connect();
        OutputStream stream = out;
        if (stream == null) return;
        try {
            String line = "PROGRESS:" + progress + ":" + message + "\n";
            stream.write(line.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception e) {
            out = null;
        }
    }

    public static void close() {
        OutputStream stream = out;
        out = null;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }
}
