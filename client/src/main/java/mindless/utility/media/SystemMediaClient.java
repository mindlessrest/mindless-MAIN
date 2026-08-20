package mindless.utility.media;

import mindless.Raven;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
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
    private static final long MEDIA_STALE_GRACE_MS = 1800L;
    private static final int MAX_ALBUM_ART_SIZE = 256;
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
    private String requestedAlbumArtKey = "";
    private DecodedAlbumArt decodedAlbumArt;
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
        String keyToUpload = null;

        synchronized (albumArtLock) {
            final String albumArtKey = mediaInfo.getAlbumArtKey();
            if (albumArtKey.equals(uploadedAlbumArtKey) && uploadedAlbumArtLocation != null) {
                return uploadedAlbumArtLocation;
            }

            if (decodedAlbumArt != null && albumArtKey.equals(decodedAlbumArt.key)) {
                imageToUpload = decodedAlbumArt.image;
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
                        synchronized (albumArtLock) {
                            if (albumArtKey.equals(requestedAlbumArtKey)) {
                                decodedAlbumArt = image == null ? null : new DecodedAlbumArt(albumArtKey, image);
                                albumArtDecodeTask = null;
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

    private void pollNow() {
        if (nativeBridge == null) {
            handleHelperUnavailable();
            handleUnavailablePoll();
            return;
        }

        try {
            SystemMediaInfo mediaInfo = nativeBridge.poll();
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
        uploadedAlbumArtTexture = null;
        uploadedAlbumArtKey = "";
    }

    private void cancelAlbumArtDecodeLocked() {
        if (albumArtDecodeTask != null) {
            albumArtDecodeTask.cancel(true);
            albumArtDecodeTask = null;
        }
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

        private DecodedAlbumArt(String key, BufferedImage image) {
            this.key = key;
            this.image = image;
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

        if (previous == null || !previous.isAvailable() || trackChanged) {
            return incoming;
        }
        if (!isSameTrack(previous, incoming)) {
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
            } else if (incomingLive >= prevLive - 750L) {
                mergedPosition = incomingLive;
            } else {
                // Native is far behind interpolated — stale report, keep clock running
                mergedPosition = prevLive;
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
