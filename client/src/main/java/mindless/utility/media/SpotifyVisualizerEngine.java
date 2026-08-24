package mindless.utility.media;

import java.util.Arrays;

/**
 * Turns the Spotify audio tap into bar heights, on its own thread.
 *
 * <p>One engine serves both places the visualiser can appear. The mini player and the standalone
 * overlay are two views of this, not two visualisers: a second one would mean a second capture
 * session on the same process and twice the analysis for identical numbers.
 *
 * <p>Consumers call {@link #requestFrame()} while they are drawing. The capture thread starts on
 * the first request and shuts itself down a couple of seconds after the last one, so a visualiser
 * that is switched off, hidden behind a GUI, or simply not on screen costs nothing and holds no
 * audio session open.
 *
 * <p>Nothing here touches OpenGL or the render thread. The renderer reads one volatile array
 * reference per frame, which is published whole and never mutated after publication.
 */
public final class SpotifyVisualizerEngine {
    private static final SpotifyVisualizerEngine INSTANCE = new SpotifyVisualizerEngine();

    /** Shut the capture down this long after the last frame was asked for. */
    private static final long IDLE_SHUTDOWN_MS = 2000L;
    /** Ceiling on how much audio one analysis frame will swallow, in stereo frames. */
    private static final int MAX_POLL_FRAMES = 8192;
    private static final int LOW_CUT_OFF_HZ = 50;
    private static final int HIGH_CUT_OFF_HZ = 10000;
    private static final float[] NO_BARS = new float[0];

    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private volatile long lastRequestedAt;
    private volatile float[] published = NO_BARS;
    private volatile int status = SpotifyAudioTap.STATUS_STOPPED;
    private volatile String statusText = "Stopped";
    private volatile boolean loadFailed;

    // Configuration, written by the module and read by the capture thread.
    private volatile int desiredBars = 48;
    private volatile double desiredNoiseReduction = 0.77;
    private volatile int desiredUpdateRate = 60;

    private Thread worker;
    private SpotifyAudioTap tap;

    private SpotifyVisualizerEngine() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "Mindless-VisualizerShutdown"));
    }

    public static SpotifyVisualizerEngine getInstance() {
        return INSTANCE;
    }

    /**
     * Applies the settings the analysis depends on.
     *
     * <p>Only the ones that change the numbers live here. Colours, spacing and the rest are the
     * renderer's business and never reach this thread.
     */
    public void configure(int bars, double noiseReduction, int updateRate) {
        this.desiredBars = Math.max(2, Math.min(256, bars));
        this.desiredNoiseReduction = Math.max(0.0, Math.min(1.0, noiseReduction));
        this.desiredUpdateRate = Math.max(15, Math.min(240, updateRate));
    }

    /**
     * Says that something is drawing the visualiser right now, and starts capture if needed.
     *
     * <p>Called from the render thread, so it does no work beyond a timestamp and, at most, one
     * thread start.
     */
    public void requestFrame() {
        lastRequestedAt = System.currentTimeMillis();
        if (running) {
            return;
        }
        synchronized (lifecycleLock) {
            if (running) return;
            running = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    captureLoop();
                }
            }, "Mindless-SpotifyVisualizer");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    /**
     * The most recent bar heights, roughly 0..1, lowest frequency first.
     *
     * <p>The array is never modified after being published, so the caller may read it without
     * copying or locking. It may be empty before the first frame arrives.
     */
    public float[] getBars() {
        return published;
    }

    /** Whether audio is actually arriving from Spotify at the moment. */
    public boolean isCapturing() {
        return status == SpotifyAudioTap.STATUS_CAPTURING;
    }

    public int getStatus() {
        return status;
    }

    /** A short line explaining the current state, for the settings panel. */
    public String getStatusText() {
        return statusText;
    }

    /** Stops capture and releases the native tap. Safe to call when already stopped. */
    public void shutdown() {
        Thread toJoin;
        synchronized (lifecycleLock) {
            if (!running) return;
            running = false;
            toJoin = worker;
            worker = null;
        }
        if (toJoin != null) {
            try {
                toJoin.join(1500L);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------------------ capture loop

    private void captureLoop() {
        SpotifyAudioTap localTap = SpotifyAudioTap.tryLoad();
        synchronized (lifecycleLock) {
            tap = localTap;
        }

        if (localTap == null) {
            loadFailed = true;
            status = SpotifyAudioTap.STATUS_FAILED;
            String failure = SpotifyAudioTap.getLastLoadFailure();
            statusText = failure == null || failure.isEmpty()
                    ? "Audio tap unavailable"
                    : "Audio tap unavailable: " + failure;
            published = NO_BARS;
            running = false;
            return;
        }

        loadFailed = false;
        localTap.start();

        final float[] pcm = new float[MAX_POLL_FRAMES * 2];
        double[] mono = new double[MAX_POLL_FRAMES];
        double[] out = null;
        float[] scratch = null;

        CavaCore cava = null;
        int planBars = -1;
        int planRate = -1;
        double planNoiseReduction = -1.0;
        int rejectedBars = -1;
        int rejectedRate = -1;

        try {
            while (running) {
                long frameStarted = System.nanoTime();

                if (System.currentTimeMillis() - lastRequestedAt > IDLE_SHUTDOWN_MS) {
                    break;
                }

                status = localTap.getStatus();
                statusText = localTap.describeStatus();

                int rate = localTap.getSampleRate();
                int bars = desiredBars;
                double noiseReduction = desiredNoiseReduction;

                boolean planStale = cava == null || planBars != bars || planRate != rate
                        || planNoiseReduction != noiseReduction;
                boolean alreadyRejected = bars == rejectedBars && rate == rejectedRate;
                if (planStale && !alreadyRejected) {
                    try {
                        cava = new CavaCore(bars, rate, true, noiseReduction, LOW_CUT_OFF_HZ,
                                HIGH_CUT_OFF_HZ, CavaCore.SCALING_LINEAR);
                        planBars = bars;
                        planRate = rate;
                        planNoiseReduction = noiseReduction;
                        out = new double[bars];
                        scratch = new float[bars];
                        rejectedBars = -1;
                        rejectedRate = -1;
                    }
                    catch (IllegalArgumentException error) {
                        // A bar count this sample rate cannot support. Remember the pairing so it
                        // is not retried every frame, and keep whatever plan we already had rather
                        // than dropping the visualiser entirely.
                        rejectedBars = bars;
                        rejectedRate = rate;
                    }
                }

                if (cava == null) {
                    published = NO_BARS;
                    sleepUntilNextFrame(frameStarted);
                    continue;
                }

                int frames = localTap.poll(pcm, MAX_POLL_FRAMES);

                // Spotify renders in stereo and the display is one row of bars, so fold to mono
                // here rather than analysing a channel whose result would be thrown away.
                int samples = Math.min(frames, mono.length);
                int offset = Math.max(0, frames - samples) * 2;
                for (int i = 0; i < samples; i++) {
                    mono[i] = (pcm[offset + i * 2] + pcm[offset + i * 2 + 1]) * 0.5;
                }

                if (frames == 0) {
                    if (status == SpotifyAudioTap.STATUS_CAPTURING) {
                        // Attached, but this poll landed between packets -- WASAPI hands over
                        // about fifty a second and we ask more often than that. Passing no samples
                        // is what cavacore expects here; it keeps its framerate estimate honest and
                        // leaves the analysis window alone. Injecting silence instead would punch
                        // gaps into a signal that is playing perfectly well.
                        samples = 0;
                    }
                    else {
                        // Genuinely nothing to listen to: Spotify is closed or the tap is not
                        // attached. Feed real silence so the bars fall away rather than freezing
                        // at whatever they last showed.
                        samples = Math.max(1, rate / Math.max(1, desiredUpdateRate));
                        if (samples > mono.length) samples = mono.length;
                        Arrays.fill(mono, 0, samples, 0.0);
                    }
                }

                cava.execute(mono, samples, out);

                for (int i = 0; i < scratch.length; i++) {
                    double value = out[i];
                    scratch[i] = value <= 0.0 ? 0.0f : (value >= 1.0 ? 1.0f : (float) value);
                }
                // Published whole, and never touched again, so readers need no lock.
                published = Arrays.copyOf(scratch, scratch.length);

                sleepUntilNextFrame(frameStarted);
            }
        }
        catch (Throwable error) {
            status = SpotifyAudioTap.STATUS_FAILED;
            statusText = "Visualizer stopped: " + error.getClass().getSimpleName();
        }
        finally {
            try {
                localTap.stop();
            }
            catch (Throwable ignored) {
            }
            synchronized (lifecycleLock) {
                if (tap == localTap) tap = null;
                running = false;
                worker = null;
            }
            published = NO_BARS;
            status = SpotifyAudioTap.STATUS_STOPPED;
            if (!loadFailed) statusText = "Stopped";
        }
    }

    private void sleepUntilNextFrame(long frameStartedNanos) {
        long budget = 1000000000L / Math.max(1, desiredUpdateRate);
        long remaining = budget - (System.nanoTime() - frameStartedNanos);
        if (remaining <= 0) return;
        try {
            Thread.sleep(remaining / 1000000L, (int) (remaining % 1000000L));
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
