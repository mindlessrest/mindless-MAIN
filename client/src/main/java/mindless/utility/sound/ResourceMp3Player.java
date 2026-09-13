package mindless.utility.sound;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ResourceMp3Player {
    private static final String ALIAS = "mindless_kill_sound";
    private static final Map<String, File> EXTRACTED = new HashMap<String, File>();
    private static final ExecutorService PLAYBACK = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "Mindless Kill Sound");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private ResourceMp3Player() {
    }

    public static void playResource(final String resource, final float volume) {
        enqueue(new SoundSource() {
            @Override
            public File resolve() throws IOException {
                return extract(resource);
            }
        }, volume);
    }

    public static void playFile(final File file, final float volume) {
        enqueue(new SoundSource() {
            @Override
            public File resolve() {
                return file != null && file.isFile() ? file : null;
            }
        }, volume);
    }

    private static void enqueue(final SoundSource source, final float volume) {
        try {
            PLAYBACK.execute(new Runnable() {
                @Override
                public void run() {
                    play(source, volume);
                }
            });
        }
        catch (RuntimeException ignored) {
        }
    }

    private static synchronized void play(SoundSource source, float volume) {
        try {
            File file = source.resolve();
            if (file == null) {
                return;
            }
            Winmm.INSTANCE.mciSendStringW(new WString("stop " + ALIAS), null, 0, null);
            Winmm.INSTANCE.mciSendStringW(new WString("close " + ALIAS), null, 0, null);
            String path = file.getAbsolutePath().replace("\"", "");
            int result = Winmm.INSTANCE.mciSendStringW(
                    new WString("open \"" + path + "\" alias " + ALIAS), null, 0, null);
            if (result == 0) {
                int level = Math.max(0, Math.min(1000, Math.round(volume * 1000.0f)));
                Winmm.INSTANCE.mciSendStringW(
                        new WString("setaudio " + ALIAS + " volume to " + level), null, 0, null);
                Winmm.INSTANCE.mciSendStringW(
                        new WString("play " + ALIAS + " from 0"), null, 0, null);
            }
        }
        catch (Throwable ignored) {
        }
    }

    private static File extract(String resource) throws IOException {
        File cached = EXTRACTED.get(resource);
        if (cached != null && cached.isFile()) {
            return cached;
        }

        InputStream input = ResourceMp3Player.class.getResourceAsStream(resource);
        if (input == null) {
            return null;
        }

        File dataDir = Minecraft.getMinecraft() == null
                ? new File(System.getProperty("java.io.tmpdir"))
                : Minecraft.getMinecraft().mcDataDir;
        File directory = new File(dataDir, "mindless/cache/kill-sounds");
        if (!directory.exists() && !directory.mkdirs()) {
            input.close();
            return null;
        }

        String name = resource.substring(resource.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(directory, name);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            EXTRACTED.put(resource, target);
            return target;
        }
        finally {
            input.close();
            if (output != null) {
                output.close();
            }
        }
    }

    private interface SoundSource {
        File resolve() throws IOException;
    }

    private interface Winmm extends Library {
        Winmm INSTANCE = (Winmm) Native.loadLibrary("winmm", Winmm.class);

        int mciSendStringW(WString command, char[] result, int resultLength, Pointer callback);
    }
}
