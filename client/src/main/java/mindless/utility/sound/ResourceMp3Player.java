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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ResourceMp3Player {
    private static final String RESOURCE = "/assets/mindless/sounds/mommy_asmr.mp3";
    private static final String ALIAS = "mindless_mommy_asmr";
    private static volatile File extracted;
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

    /**
     * One thread for the life of the process, not one per kill.
     *
     * MCI ties a device to the thread that opened it. A thread that plays a sound and then
     * exits leaves behind a device that nothing can stop or close again, and the next kill
     * blocks trying -- which, because play is synchronized, then blocks every kill after it.
     */
    public static void playMommyAsmr(final float volume) {
        try {
            PLAYBACK.execute(new Runnable() {
                @Override
                public void run() {
                    play(volume);
                }
            });
        }
        catch (RuntimeException ignored) {
        }
    }

    private static synchronized void play(float volume) {
        try {
            File file = extract();
            if (file == null) {
                return;
            }
            Winmm.INSTANCE.mciSendStringW(new WString("stop " + ALIAS), null, 0, null);
            Winmm.INSTANCE.mciSendStringW(new WString("close " + ALIAS), null, 0, null);
            String path = file.getAbsolutePath().replace("\"", "");
            int result = Winmm.INSTANCE.mciSendStringW(
                    new WString("open \"" + path + "\" type mpegvideo alias " + ALIAS),
                    null, 0, null);
            if (result == 0) {
                int level = Math.max(0, Math.min(1000, Math.round(volume * 1000.0f)));
                Winmm.INSTANCE.mciSendStringW(
                        new WString("setaudio " + ALIAS + " volume to " + level),
                        null, 0, null);
                Winmm.INSTANCE.mciSendStringW(new WString("play " + ALIAS + " from 0"),
                        null, 0, null);
            }
        }
        catch (Throwable ignored) {
        }
    }

    private static File extract() throws IOException {
        if (extracted != null && extracted.isFile()) {
            return extracted;
        }

        InputStream input = ResourceMp3Player.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            return null;
        }

        File dataDir = Minecraft.getMinecraft() == null
                ? new File(System.getProperty("java.io.tmpdir"))
                : Minecraft.getMinecraft().mcDataDir;
        File directory = new File(dataDir, "mindless/cache");
        if (!directory.exists() && !directory.mkdirs()) {
            input.close();
            return null;
        }

        File target = new File(directory, "mommy_asmr.mp3");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            extracted = target;
            return target;
        }
        finally {
            input.close();
            if (output != null) {
                output.close();
            }
        }
    }

    private interface Winmm extends Library {
        Winmm INSTANCE = (Winmm) Native.loadLibrary("winmm", Winmm.class);

        int mciSendStringW(WString command, char[] result, int resultLength, Pointer callback);
    }
}
