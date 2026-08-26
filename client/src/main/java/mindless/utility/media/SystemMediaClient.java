package mindless.utility.media;

import mindless.Raven;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SystemMediaClient {
    // The displayed position interpolates every rendered frame; the native
    // session only needs periodic correction and track/seek updates.
    private static final long POLL_INTERVAL_MS = 50L;
    /** A position change larger than this is a seek, not clock drift, and is applied at once. */
    private static final long SEEK_SNAP_MS = 2000L;
    /** Largest single correction applied to the playback clock, so drift is never a visible jump. */
    private static final long MAX_DRIFT_STEP_MS = 300L;
    private static final long MEDIA_STALE_GRACE_MS = 1800L;
    private static final int MAX_ALBUM_ART_SIZE = 256;
    /**
     * How few pixels the softened copy keeps.
     *
     * <p>Small enough that no feature of the cover survives as a feature, large enough that the
     * wash still moves across the panel rather than being one flat colour.
     */
    /**
     * The softened copy is a wide band, not a square.
     *
     * <p>It gets stretched across a panel four times wider than it is tall. Taken as a square,
     * that stretch is a fourfold horizontal smear and every feature in it turns into a streak.
     * Cropping the cover to roughly the shape it will be drawn at first -- which is what any
     * background-size: cover does -- leaves only a slight stretch to absorb.
     */
    private static final int ALBUM_ART_BLUR_WIDTH = 96;
    private static final int ALBUM_ART_BLUR_HEIGHT = 32;
    /** Passes of box blur. Three is the usual stand-in for a Gaussian. */
    private static final int ALBUM_ART_BLUR_PASSES = 3;
    private static final int ALBUM_ART_BLUR_RADIUS = 5;
    private static final SystemMediaClient INSTANCE = new SystemMediaClient();

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Object lifecycleLock = new Object();
    private final Object albumArtLock = new Object();
    private final TimedLyricsManager timedLyricsManager = new TimedLyricsManager();

    private volatile SystemMediaInfo currentInfo = SystemMediaInfo.unavailable();
    private volatile boolean enabled;
    private volatile boolean helperUnavailableLogged;
    private volatile boolean helperAvailable;
    private volatile String statusMessage = "No media detected";
    private volatile long lastSuccessfulPollAt;
    private volatile String lastNativeTrackKey = "";
    /**
     * What the enabled modules actually intend to draw.
     *
     * <p>Lyrics cost a network lookup and artwork costs a PNG decode, and neither is worth paying
     * for to fill a panel nobody is showing. Each consumer declares its own need and the bridge is
     * told the union, so switching synced lyrics off in the mini player stops the lookups outright
     * rather than fetching them and discarding the result.
     */
    private volatile boolean lyricsWanted;
    private volatile boolean playerWantsArtwork;
    private volatile boolean visualizerWantsArtwork;
    private volatile long lastNativePositionMs = Long.MIN_VALUE;
    private final ScheduledExecutorService mediaExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Raven-MediaPoll");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private ScheduledFuture<?> pollTask;
    private volatile NativeMediaBridge nativeBridge;

    private String uploadedAlbumArtKey = "";
    private ResourceLocation uploadedAlbumArtLocation;
    private DynamicTexture uploadedAlbumArtTexture;
    private ResourceLocation uploadedBlurLocation;
    private DynamicTexture uploadedBlurTexture;
    private String requestedAlbumArtKey = "";
    private DecodedAlbumArt decodedAlbumArt;
    /** Accent pulled from the current artwork, for anything that wants to match the record. */
    private volatile int albumAccentColor;
    private volatile String albumAccentKey = "";
    private Future<?> albumArtDecodeTask;

    private SystemMediaClient() {
    }

    public static SystemMediaClient getInstance() {
        return INSTANCE;
    }

    public void setEnabled(boolean enabled) {
        synchronized (lifecycleLock) {
            if (this.enabled == enabled) {
                return;
            }

            this.enabled = enabled;
            if (enabled) {
                helperUnavailableLogged = false;
                helperAvailable = false;
                nativeBridge = null;
                timedLyricsManager.updateTrack(SystemMediaInfo.unavailable());
                statusMessage = "Connecting to media";
                // Load the native bridge on a background thread so the game
                // thread never blocks on DLL extraction + antivirus scan.
                mediaExecutor.execute(() -> {
                    NativeMediaBridge bridge = NativeMediaBridge.tryLoad();
                    synchronized (lifecycleLock) {
                        if (!this.enabled) return;
                        nativeBridge = bridge;
                        statusMessage = bridge != null ? "Loading..." : "Bridge unavailable";
                        startPolling();
                    }
                });
            }
            else {
                stopPolling();
                currentInfo = SystemMediaInfo.unavailable();
                timedLyricsManager.updateTrack(currentInfo);
                helperAvailable = false;
                nativeBridge = null;
                statusMessage = "Disabled";
                lastSuccessfulPollAt = 0L;
                resetNativePositionAnchor();
                clearAlbumArtTexture();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SystemMediaInfo getCurrentInfo() {
        return currentInfo;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public boolean isHelperAvailable() {
        return helperAvailable;
    }

    public TimedLyrics getTimedLyrics() {
        return timedLyricsManager.getCurrentLyrics();
    }

    public ResourceLocation getAlbumArtTextureLocation() {
        if (!enabled) {
            return null;
        }

        SystemMediaInfo mediaInfo = currentInfo;
        if (!mediaInfo.hasAlbumArt()) {
            synchronized (albumArtLock) {
                cancelAlbumArtDecodeLocked();
                requestedAlbumArtKey = "";
                decodedAlbumArt = null;
                clearAlbumArtTextureLocked();
            }
            return null;
        }

        BufferedImage imageToUpload = null;
        BufferedImage washToUpload = null;
        String keyToUpload = null;

        synchronized (albumArtLock) {
            final String albumArtKey = mediaInfo.getAlbumArtKey();
            if (albumArtKey.equals(uploadedAlbumArtKey) && uploadedAlbumArtLocation != null) {
                return uploadedAlbumArtLocation;
            }

            if (decodedAlbumArt != null && albumArtKey.equals(decodedAlbumArt.key)) {
                imageToUpload = decodedAlbumArt.image;
                washToUpload = decodedAlbumArt.wash;
                keyToUpload = decodedAlbumArt.key;
                decodedAlbumArt = null;
                clearAlbumArtTextureLocked();
            }

            if (!albumArtKey.equals(requestedAlbumArtKey)) {
                cancelAlbumArtDecodeLocked();
                requestedAlbumArtKey = albumArtKey;
                decodedAlbumArt = null;
                clearAlbumArtTextureLocked();
                final byte[] encodedImage = mediaInfo.getAlbumArtBytes();
                albumArtDecodeTask = Raven.getCachedExecutor().submit(new Runnable() {
                    @Override
                    public void run() {
                        BufferedImage image = decodeAndResizeAlbumArt(encodedImage);
                        // Sampled here rather than at draw time: the pixels are already in hand
                        // on a background thread, and the answer only changes once per track.
                        int accent = image == null ? 0 : extractAccentColor(image);
                        // Same reasoning for the wash. It is a downscale and three blur passes,
                        // which is a couple of milliseconds -- nothing on a worker thread, but a
                        // dropped frame if it were done on the render thread at the moment of the
                        // upload, which is exactly when a track changes and the panel is on screen.
                        BufferedImage wash = softenAlbumArt(image);
                        synchronized (albumArtLock) {
                            if (albumArtKey.equals(requestedAlbumArtKey)) {
                                decodedAlbumArt = image == null ? null : new DecodedAlbumArt(albumArtKey, image, wash);
                                albumArtDecodeTask = null;
                                albumAccentColor = accent;
                                albumAccentKey = accent == 0 ? "" : albumArtKey;
                            }
                        }
                    }
                });
            }
        }

        if (imageToUpload != null && keyToUpload != null) {
            try {
                uploadedAlbumArtTexture = new DynamicTexture(imageToUpload);
                uploadedAlbumArtLocation = mc.getTextureManager().getDynamicTextureLocation("mindless_media_album_art", uploadedAlbumArtTexture);

                if (washToUpload != null) {
                    uploadedBlurTexture = new DynamicTexture(washToUpload);
                    uploadedBlurLocation = mc.getTextureManager().getDynamicTextureLocation("mindless_media_album_blur", uploadedBlurTexture);
                }

                uploadedAlbumArtKey = keyToUpload;
                return uploadedAlbumArtLocation;
            }
            catch (Exception ignored) {
                synchronized (albumArtLock) {
                    clearAlbumArtTextureLocked();
                }
                return null;
            }
        }

        return null;
    }

    /**
     * A colour that reads as belonging to the current album art, or 0 when there is none.
     *
     * <p>RGB only -- callers supply their own alpha.
     */
    public int getAlbumAccentColor() {
        return albumAccentKey.isEmpty() ? 0 : albumAccentColor;
    }

    /**
     * Picks the colour a person would say the cover "is".
     *
     * <p>An average is the obvious approach and the wrong one: averaging a cover produces mud,
     * because opposing hues cancel. This buckets pixels by hue instead and takes the heaviest
     * bucket, weighting each pixel by how colourful it is, so a mostly-grey sleeve with one red
     * detail comes back red rather than grey. Near-black and near-white pixels are skipped
     * entirely; they carry no hue and every cover has plenty of both.
     */
    private static int extractAccentColor(BufferedImage image) {
        final int buckets = 24;
        double[] weight = new double[buckets];
        double[] sumRed = new double[buckets];
        double[] sumGreen = new double[buckets];
        double[] sumBlue = new double[buckets];

        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(1, Math.min(width, height) / 48);
        float[] hsb = new float[3];

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 24) & 0xFF) < 128) continue;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                Color.RGBtoHSB(red, green, blue, hsb);
                if (hsb[1] < 0.18F || hsb[2] < 0.12F || hsb[2] > 0.96F) continue;

                int bucket = Math.min(buckets - 1, (int) (hsb[0] * buckets));
                double pixelWeight = hsb[1] * (0.35 + 0.65 * hsb[2]);
                weight[bucket] += pixelWeight;
                sumRed[bucket] += red * pixelWeight;
                sumGreen[bucket] += green * pixelWeight;
                sumBlue[bucket] += blue * pixelWeight;
            }
        }

        int best = -1;
        for (int i = 0; i < buckets; i++) {
            if (best < 0 || weight[i] > weight[best]) best = i;
        }
        if (best < 0 || weight[best] <= 0.0) {
            return 0;
        }

        int red = (int) (sumRed[best] / weight[best]);
        int green = (int) (sumGreen[best] / weight[best]);
        int blue = (int) (sumBlue[best] / weight[best]);

        // Lift it clear of the dark panel it will be drawn on, without washing out the hue.
        Color.RGBtoHSB(red, green, blue, hsb);
        int lifted = Color.HSBtoRGB(hsb[0], Math.min(1.0F, Math.max(0.55F, hsb[1])),
                Math.min(1.0F, Math.max(0.72F, hsb[2])));
        return lifted & 0xFFFFFF;
    }

    public void playPause() {
    }

    public void nextTrack() {
    }

    public void previousTrack() {
    }

    public void seekTo(long positionMs) {
    }

    private void startPolling() {
        stopPolling();
        pollTask = mediaExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!enabled) {
                    return;
                }
                pollNow();
            }
        }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /** The loaded bridge, or null when the helper is unavailable. For the visualiser pump. */
    NativeMediaBridge getNativeBridge() {
        return nativeBridge;
    }

    public void setLyricsWanted(boolean wanted) {
        this.lyricsWanted = wanted;
    }

    public void setPlayerWantsArtwork(boolean wanted) {
        this.playerWantsArtwork = wanted;
    }

    public void setVisualizerWantsArtwork(boolean wanted) {
        this.visualizerWantsArtwork = wanted;
    }

    private void pollNow() {
        if (nativeBridge == null) {
            handleHelperUnavailable();
            handleUnavailablePoll();
            return;
        }

        try {
            SystemMediaInfo mediaInfo = nativeBridge.poll(
                    lyricsWanted, playerWantsArtwork || visualizerWantsArtwork);
            if (mediaInfo == null || !mediaInfo.isAvailable()) {
                handleHelperUnavailable();
                handleUnavailablePoll();
                return;
            }
            helperUnavailableLogged = false;
            helperAvailable = true;
            currentInfo = mergeMediaInfo(currentInfo, mediaInfo);
            lastSuccessfulPollAt = System.currentTimeMillis();
            timedLyricsManager.updateTrackFromNative(currentInfo,
                    mediaInfo.isNativeLyricsAvailable(), mediaInfo.getNativeLyricsLines());
            statusMessage = mediaInfo.getStatus().isEmpty() ? "Playing" : mediaInfo.getStatus();
        }
        catch (Throwable ignored) {
            handleHelperUnavailable();
            handleUnavailablePoll();
        }
    }

    private void clearAlbumArtTexture() {
        synchronized (albumArtLock) {
            cancelAlbumArtDecodeLocked();
            requestedAlbumArtKey = "";
            decodedAlbumArt = null;
            clearAlbumArtTextureLocked();
        }
    }

    private void clearAlbumArtTextureLocked() {
        if (uploadedAlbumArtLocation != null) {
            mc.getTextureManager().deleteTexture(uploadedAlbumArtLocation);
            uploadedAlbumArtLocation = null;
        }
        if (uploadedBlurLocation != null) {
            mc.getTextureManager().deleteTexture(uploadedBlurLocation);
            uploadedBlurLocation = null;
        }
        uploadedAlbumArtTexture = null;
        uploadedBlurTexture = null;
        uploadedAlbumArtKey = "";
    }

    /**
     * A heavily softened copy of the artwork, for drawing behind text.
     *
     * <p>It is the same picture reduced to a handful of pixels. Drawn back at panel size with
     * linear filtering, the hardware interpolates between those few samples and the result is a
     * smooth wash of the record's colours -- which is what a CSS blur of the cover looks like, at
     * a fraction of the cost of actually blurring anything.
     *
     * <p>Only meaningful once {@link #getAlbumArtTextureLocation} has run for the current track,
     * since both are uploaded together.
     */
    public ResourceLocation getAlbumArtBlurTextureLocation() {
        return enabled ? uploadedBlurLocation : null;
    }

    private void cancelAlbumArtDecodeLocked() {
        if (albumArtDecodeTask != null) {
            albumArtDecodeTask.cancel(true);
            albumArtDecodeTask = null;
        }
    }

    /**
     * Turns the cover into the wash that sits behind the card.
     *
     * <p>Shrinking a cover to a handful of pixels is not the same as blurring it. What comes back
     * out when those pixels are stretched across the panel is straight lines between neighbouring
     * samples -- flat facets meeting at creases, which is what made it read as a stretched
     * thumbnail rather than as blurred artwork. Blurring at a size with something left in it and
     * then magnifying gives smooth gradients instead, because there is no detail left at the pixel
     * scale for the magnification to expose. Three box passes stand in for a Gaussian.
     *
     * <p>The whole cover goes in rather than a centre band, so the wash carries the record's full
     * palette. Squashing it out of shape costs nothing once it is this far gone.
     *
     * <p>Averaging also walks every pixel towards grey, mixing a sleeve's lights and darks
     * together, and the wash came out muddy for it. Saturation goes back on afterwards.
     */
    private static BufferedImage softenAlbumArt(BufferedImage source) {
        if (source == null) {
            return null;
        }
        try {
            BufferedImage small = downscale(source, ALBUM_ART_BLUR_WIDTH, ALBUM_ART_BLUR_HEIGHT);
            for (int pass = 0; pass < ALBUM_ART_BLUR_PASSES; pass++) {
                boxBlur(small, ALBUM_ART_BLUR_RADIUS);
            }
            saturate(small, 1.75F, 1.12F);
            return small;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Halves repeatedly before the final step.
     *
     * <p>One bilinear draw from a 600px cover down to under a hundred reads four neighbouring
     * pixels and ignores the rest, so most of the artwork never reaches the result and what does
     * is whichever pixels happened to land under the sample points. Halving averages everything on
     * the way down, which is what makes the wash the colour of the record rather than the colour
     * of an arbitrary scattering of its pixels.
     */
    private static BufferedImage downscale(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();

        while (width > targetWidth * 2 && height > targetHeight * 2) {
            width = Math.max(targetWidth, width / 2);
            height = Math.max(targetHeight, height / 2);
            current = redraw(current, width, height);
        }
        return redraw(current, targetWidth, targetHeight);
    }

    private static BufferedImage redraw(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        }
        finally {
            graphics.dispose();
        }
        return out;
    }

    /**
     * Separable box blur, edges held rather than wrapped.
     *
     * <p>Run over a few thousand pixels once per track, so the running-sum form is not needed for
     * speed so much as for staying obviously correct at the edges: a row is read into a buffer
     * first and clamped at both ends, so the left of the wash never picks up the right of it.
     */
    private static void boxBlur(BufferedImage image, int radius) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (radius < 1 || width < 2 || height < 2) {
            return;
        }

        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        blurAxis(pixels, width, height, radius, true);
        blurAxis(pixels, width, height, radius, false);
        image.setRGB(0, 0, width, height, pixels, 0, width);
    }

    private static void blurAxis(int[] pixels, int width, int height, int radius, boolean horizontal) {
        int lineLength = horizontal ? width : height;
        int lineCount = horizontal ? height : width;
        int[] line = new int[lineLength];
        int span = radius * 2 + 1;

        for (int outer = 0; outer < lineCount; outer++) {
            for (int i = 0; i < lineLength; i++) {
                line[i] = horizontal ? pixels[outer * width + i] : pixels[i * width + outer];
            }

            for (int i = 0; i < lineLength; i++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int index = i + k;
                    // Held, not wrapped: a wash whose left edge samples its right edge shows the
                    // seam as a bright band down whichever side it borrowed from.
                    index = index < 0 ? 0 : (index >= lineLength ? lineLength - 1 : index);
                    int argb = line[index];
                    a += (argb >>> 24) & 0xFF;
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                }
                int blended = ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                if (horizontal) {
                    pixels[outer * width + i] = blended;
                }
                else {
                    pixels[i * width + outer] = blended;
                }
            }
        }
    }

    /** Pushes every pixel away from its own grey, and lifts it a little. */
    private static void saturate(BufferedImage image, float saturation, float brightness) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                float grey = 0.2126F * r + 0.7152F * g + 0.0722F * b;
                r = clamp255((grey + (r - grey) * saturation) * brightness);
                g = clamp255((grey + (g - grey) * saturation) * brightness);
                b = clamp255((grey + (b - grey) * saturation) * brightness);

                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    private static int clamp255(float value) {
        return value < 0.0F ? 0 : (value > 255.0F ? 255 : Math.round(value));
    }

    private static BufferedImage decodeAndResizeAlbumArt(byte[] encodedImage) {
        if (encodedImage == null || encodedImage.length == 0 || Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encodedImage));
            if (image == null || Thread.currentThread().isInterrupted()) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int longestSide = Math.max(width, height);
            if (longestSide <= MAX_ALBUM_ART_SIZE) {
                return image;
            }

            double scale = MAX_ALBUM_ART_SIZE / (double) longestSide;
            int resizedWidth = Math.max(1, (int) Math.round(width * scale));
            int resizedHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage resized = new BufferedImage(resizedWidth, resizedHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, resizedWidth, resizedHeight, null);
            }
            finally {
                graphics.dispose();
            }
            image.flush();
            return resized;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static final class DecodedAlbumArt {
        private final String key;
        private final BufferedImage image;
        /** The blurred backdrop, built on the same background thread as the decode. */
        private final BufferedImage wash;

        private DecodedAlbumArt(String key, BufferedImage image, BufferedImage wash) {
            this.key = key;
            this.image = image;
            this.wash = wash;
        }
    }

    private void handleHelperUnavailable() {
        helperAvailable = false;
        statusMessage = nativeBridge == null ? "Media bridge unavailable" : "No media detected";
        if (!helperUnavailableLogged) {
            helperUnavailableLogged = true;
            String loadFailure = NativeMediaBridge.getLastLoadFailure();
            if (loadFailure != null && !loadFailure.isEmpty()) {
                System.out.println("[Mindless] Native media bridge is unavailable. " + loadFailure);
            }
            else {
                System.out.println("[Mindless] Native media bridge is unavailable.");
            }
        }
    }

    private void handleUnavailablePoll() {
        SystemMediaInfo previous = currentInfo;
        long now = System.currentTimeMillis();
        if (previous != null && previous.isAvailable() && now - lastSuccessfulPollAt <= MEDIA_STALE_GRACE_MS) {
            statusMessage = previous.isPaused() ? "Paused" : (previous.getStatus().isEmpty() ? "Playing" : previous.getStatus());
            return;
        }

        currentInfo = SystemMediaInfo.unavailable();
        resetNativePositionAnchor();
        timedLyricsManager.updateTrack(currentInfo);
    }

    private SystemMediaInfo mergeMediaInfo(SystemMediaInfo previous, SystemMediaInfo incoming) {
        if (incoming == null || !incoming.isAvailable()) {
            resetNativePositionAnchor();
            return SystemMediaInfo.unavailable();
        }

        String incomingTrackKey = buildTrackKey(incoming);
        boolean trackChanged = !incomingTrackKey.equals(lastNativeTrackKey);
        long incomingNativePosition = incoming.getPositionMs();
        boolean nativePositionChanged = trackChanged || incomingNativePosition != lastNativePositionMs;
        lastNativeTrackKey = incomingTrackKey;
        lastNativePositionMs = incomingNativePosition;

        if (previous == null || !previous.isAvailable()) {
            return incoming;
        }
        if (trackChanged || !isSameTrack(previous, incoming)) {
            // During track transitions SMTC may briefly report the new track with an empty
            // title before metadata populates. Showing that for 1-2 frames causes a visible
            // flicker (panel shrinks / "Nothing playing" flash). Keep the old info until the
            // new track has a real title.
            String title = incoming.getTitle();
            if (title == null || title.trim().isEmpty()) {
                return previous;
            }
            return incoming;
        }

        // Some media sessions only publish a new raw position every ~5 seconds.
        // Continue the prior local clock while that raw value is repeated, but
        // re-anchor immediately when the service publishes a new value. This
        // gives a smooth per-second display without refusing real corrections.
        //
        // Additionally: if the native position is more than 750ms BEHIND the
        // current interpolated value, keep interpolating instead of snapping.
        // This prevents position fluctuations in SMTC (which can briefly report
        // a stale value ~300-500ms behind the running clock) from causing the
        // lyric view to flicker/reset.
        long mergedPosition;
        if (incoming.isPlaying()) {
            long prevLive = previous.getLivePositionMs();
            long incomingLive = incoming.getLivePositionMs();
            if (!nativePositionChanged) {
                mergedPosition = prevLive;
            } else {
                // Ease onto the newly published position instead of snapping to it.
                //
                // SMTC republishes every few seconds and its value is routinely a couple of
                // hundred ms off the running clock in either direction. Jumping straight to it
                // shifted the lyric clock by up to 750ms at a time, which is why lines landed
                // early on one refresh and late on the next. The old rule was also asymmetric:
                // corrections more than 750ms *backwards* were discarded outright, so genuine
                // drift in that direction could never be recovered.
                long error = incomingLive - prevLive;
                if (Math.abs(error) > SEEK_SNAP_MS) {
                    mergedPosition = incomingLive; // a real seek, not drift
                } else {
                    long step = Math.round(error * 0.5d);
                    if (step > MAX_DRIFT_STEP_MS) step = MAX_DRIFT_STEP_MS;
                    if (step < -MAX_DRIFT_STEP_MS) step = -MAX_DRIFT_STEP_MS;
                    mergedPosition = prevLive + step;
                }
            }
        } else {
            mergedPosition = incoming.getLivePositionMs();
        }

        long durationMs = incoming.getDurationMs();
        if (durationMs > 0L) {
            mergedPosition = Math.min(durationMs, mergedPosition);
        }

        return new SystemMediaInfo(
                true,
                incoming.getSourceApp(),
                incoming.getTitle(),
                incoming.getArtist(),
                incoming.getAlbum(),
                incoming.getStatus(),
                mergedPosition,
                durationMs,
                incoming.getAlbumArtKey(),
                incoming.getAlbumArtBytes(),
                incoming.isNativeLyricsAvailable(),
                incoming.getNativeLyricsLines()
        );
    }

    private void resetNativePositionAnchor() {
        lastNativeTrackKey = "";
        lastNativePositionMs = Long.MIN_VALUE;
    }

    private static String buildTrackKey(SystemMediaInfo info) {
        if (info == null || !info.isAvailable()) {
            return "";
        }
        return normalizeKeyPart(info.getSourceApp()) + '\u0001'
                + normalizeKeyPart(info.getTitle()) + '\u0001'
                + normalizeKeyPart(info.getArtist()) + '\u0001'
                + normalizeKeyPart(info.getAlbum());
    }

    private static String normalizeKeyPart(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isSameTrack(SystemMediaInfo first, SystemMediaInfo second) {
        return equalsIgnoreCaseTrimmed(first.getSourceApp(), second.getSourceApp())
                && equalsIgnoreCaseTrimmed(first.getTitle(), second.getTitle())
                && equalsIgnoreCaseTrimmed(first.getArtist(), second.getArtist())
                && equalsIgnoreCaseTrimmed(first.getAlbum(), second.getAlbum());
    }

    private static boolean equalsIgnoreCaseTrimmed(String first, String second) {
        String normalizedFirst = first == null ? "" : first.trim();
        String normalizedSecond = second == null ? "" : second.trim();
        return normalizedFirst.equalsIgnoreCase(normalizedSecond);
    }
}
