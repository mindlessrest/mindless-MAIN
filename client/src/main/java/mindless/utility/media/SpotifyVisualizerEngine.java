package mindless.utility.media;
public final class SpotifyVisualizerEngine {
public static final int STATUS_STOPPED = 0;
    public static final int STATUS_SEARCHING = 1;
    public static final int STATUS_CAPTURING = 2;
    public static final int STATUS_UNSUPPORTED = 3;
    public static final int STATUS_FAILED = 4;

    private static final int MAX_BARS = 256;
private static final long IDLE_SHUTDOWN_MS = 2000L;
    private static final float[] NO_BARS = new float[0];

    private static final SpotifyVisualizerEngine INSTANCE = new SpotifyVisualizerEngine();

    private final Object lifecycleLock = new Object();

    private volatile float[] published = NO_BARS;
private float[] bufferA = NO_BARS;
    private float[] bufferB = NO_BARS;
    private boolean useBufferA;
    private volatile int status = STATUS_STOPPED;
    private volatile String statusText = "";
    private volatile long lastRequestAt;
    private volatile boolean running;

    private volatile int desiredBars = 32;
    private volatile double desiredSmoothing = 0.77;
    private volatile int desiredUpdateRate = 60;

    private Thread worker;

    private SpotifyVisualizerEngine() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "Mindless-SpotifyVisualizer-Shutdown"));
    }

    public static SpotifyVisualizerEngine getInstance() {
        return INSTANCE;
    }

    public void configure(int bars, double noiseReduction, int updateRate) {
        if (bars < 1) bars = 1;
        if (bars > MAX_BARS) bars = MAX_BARS;
        if (updateRate < 10) updateRate = 10;
        if (updateRate > 240) updateRate = 240;

        this.desiredBars = bars;
        this.desiredSmoothing = noiseReduction;
        this.desiredUpdateRate = updateRate;
    }
public void requestFrame() {
        lastRequestAt = System.currentTimeMillis();
        if (running) {
            return;
        }

        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "Mindless-SpotifyVisualizer");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    public float[] getBars() {
        return published;
    }

    public boolean isCapturing() {
        return status == STATUS_CAPTURING;
    }

    public int getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
    }
public void shutdown() {
        synchronized (lifecycleLock) {
            running = false;
            worker = null;
        }

        published = NO_BARS;
        status = STATUS_STOPPED;
    }

    private void pump() {
        NativeMediaBridge bridge = null;
        float[] scratch = new float[desiredBars];
        int configuredBars = -1;
        double configuredSmoothing = Double.NaN;
        boolean tapStarted = false;

        try {
            while (running) {
                long frameStarted = System.currentTimeMillis();

                if (frameStarted - lastRequestAt > IDLE_SHUTDOWN_MS) {
                    break;
                }

                if (bridge == null) {
                    bridge = SystemMediaClient.getInstance().getNativeBridge();
                    if (bridge == null) {
                        status = STATUS_STOPPED;
                        sleepUntilNextFrame(frameStarted);
                        continue;
                    }
                }

                if (!tapStarted) {
                    bridge.audioStart();
                    tapStarted = true;
                }

                int bars = desiredBars;
                double smoothing = desiredSmoothing;
                if (bars != configuredBars || smoothing != configuredSmoothing) {
                    bridge.audioConfigure(bars, smoothing);
                    configuredBars = bars;
                    configuredSmoothing = smoothing;
                    if (scratch.length != bars) {
                        scratch = new float[bars];
                    }
                }

                int written = bridge.readSpectrum(scratch);
                status = bridge.audioStatus();

                if (status == STATUS_UNSUPPORTED || status == STATUS_FAILED) {
                    statusText = bridge.audioError();
                }
                else {
                    statusText = "";
                }

                if (written > 0) {
                    float[] snapshot = useBufferA ? bufferA : bufferB;
                    if (snapshot.length != written) {
                        snapshot = new float[written];
                        if (useBufferA) bufferA = snapshot; else bufferB = snapshot;
                    }
                    System.arraycopy(scratch, 0, snapshot, 0, written);
                    useBufferA = !useBufferA;
                    published = snapshot;
                }

                sleepUntilNextFrame(frameStarted);
            }
        }
        catch (Throwable ignored) {
        }
        finally {
            if (bridge != null && tapStarted) {
                bridge.audioStop();
            }
            published = NO_BARS;
            status = STATUS_STOPPED;
            synchronized (lifecycleLock) {
                running = false;
                worker = null;
            }
        }
    }

    private void sleepUntilNextFrame(long frameStarted) {
        long budget = 1000L / Math.max(1, desiredUpdateRate);
        long elapsed = System.currentTimeMillis() - frameStarted;
        long remaining = budget - elapsed;
        if (remaining <= 0L) {
            return;
        }

        try {
            Thread.sleep(remaining);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
