package mindless.utility.media;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class MascotMedia {
    private static final int MAX_DIMENSION = 320;
    private static final int MAX_FRAMES = 360;
    private static final long MAX_TOTAL_PIXELS = 16000000L;
    private static final long MAX_FILE_BYTES = 128L * 1024L * 1024L;
    private static final String[] EXTENSIONS = new String[]{"png", "jpg", "jpeg", "gif", "bmp", "mp4", "mov", "m4v"};

    private final List<BufferedImage> frames;
    private final long[] frameStarts;
    private final long durationMs;
    private final long startedAt;

    private MascotMedia(List<BufferedImage> frames, List<Long> delays) {
        this.frames = frames;
        this.frameStarts = new long[frames.size()];
        long total = 0L;
        for (int i = 0; i < frames.size(); i++) {
            frameStarts[i] = total;
            total += Math.max(16L, delays.get(i));
        }
        this.durationMs = Math.max(16L, total);
        this.startedAt = System.currentTimeMillis();
    }

    public static MascotMedia load(File file) throws IOException {
        if (!supports(file) || file.length() > MAX_FILE_BYTES) {
            throw new IOException("Unsupported mascot media");
        }
        String extension = extension(file);
        if ("gif".equals(extension)) {
            return loadGif(file);
        }
        if ("mp4".equals(extension) || "mov".equals(extension) || "m4v".equals(extension)) {
            return loadVideo(file);
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Unreadable mascot image");
        }
        List<BufferedImage> frames = new ArrayList<BufferedImage>();
        List<Long> delays = new ArrayList<Long>();
        frames.add(scale(image));
        delays.add(1000L);
        return new MascotMedia(frames, delays);
    }

    public static boolean supports(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String extension = extension(file);
        for (String supported : EXTENSIONS) {
            if (supported.equals(extension)) {
                return true;
            }
        }
        return false;
    }

    public int frameIndex(long now) {
        if (frames.size() <= 1) {
            return 0;
        }
        long elapsed = Math.floorMod(now - startedAt, durationMs);
        int low = 0;
        int high = frameStarts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (frameStarts[middle] <= elapsed) {
                low = middle + 1;
            }
            else {
                high = middle - 1;
            }
        }
        return Math.max(0, high);
    }

    public BufferedImage frame(int index) {
        return frames.get(Math.max(0, Math.min(frames.size() - 1, index)));
    }

    public int frameCount() {
        return frames.size();
    }

    public float aspect() {
        BufferedImage image = frames.get(0);
        return image.getHeight() == 0 ? 1.0f : (float) image.getWidth() / image.getHeight();
    }

    private static MascotMedia loadVideo(File file) throws IOException {
        List<BufferedImage> frames = new ArrayList<BufferedImage>();
        List<Long> delays = new ArrayList<Long>();
        SeekableByteChannel channel = null;
        try {
            channel = NIOUtils.readableChannel(file);
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            DemuxerTrackMeta metadata = grab.getVideoTrack().getMeta();
            double fps = metadata.getTotalDuration() > 0.0 && metadata.getTotalFrames() > 0
                    ? metadata.getTotalFrames() / metadata.getTotalDuration() : 30.0;
            long delay = Math.max(16L, Math.min(1000L, Math.round(1000.0 / Math.max(1.0, fps))));
            long pixels = 0L;
            Picture picture;
            while (frames.size() < MAX_FRAMES && (picture = grab.getNativeFrame()) != null) {
                BufferedImage image = scale(AWTUtil.toBufferedImage(picture));
                long nextPixels = (long) image.getWidth() * image.getHeight();
                if (!frames.isEmpty() && pixels + nextPixels > MAX_TOTAL_PIXELS) {
                    break;
                }
                frames.add(image);
                delays.add(delay);
                pixels += nextPixels;
            }
        }
        catch (Exception error) {
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Video decode failed", error);
        }
        finally {
            NIOUtils.closeQuietly(channel);
        }
        if (frames.isEmpty()) {
            throw new IOException("No video frames decoded");
        }
        return new MascotMedia(frames, delays);
    }

    private static MascotMedia loadGif(File file) throws IOException {
        ImageInputStream input = ImageIO.createImageInputStream(file);
        if (input == null) {
            throw new IOException("Unreadable GIF");
        }
        ImageReader reader = null;
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No GIF reader");
            }
            reader = readers.next();
            reader.setInput(input, false, false);
            int count = Math.min(MAX_FRAMES, reader.getNumImages(true));
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if ((long) width * height > 32000000L) {
                throw new IOException("GIF dimensions too large");
            }

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            BufferedImage restore = null;
            String previousDisposal = "none";
            int previousLeft = 0;
            int previousTop = 0;
            int previousWidth = width;
            int previousHeight = height;
            List<BufferedImage> frames = new ArrayList<BufferedImage>();
            List<Long> delays = new ArrayList<Long>();
            long pixels = 0L;

            for (int i = 0; i < count; i++) {
                if ("restoreToBackgroundColor".equals(previousDisposal)) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(AlphaComposite.Clear);
                    clear.fillRect(previousLeft, previousTop, previousWidth, previousHeight);
                    clear.dispose();
                }
                else if ("restoreToPrevious".equals(previousDisposal) && restore != null) {
                    canvas = copy(restore);
                }

                IIOMetadata metadata = reader.getImageMetadata(i);
                Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
                Node descriptor = child(root, "ImageDescriptor");
                Node control = child(root, "GraphicControlExtension");
                int left = integerAttribute(descriptor, "imageLeftPosition", 0);
                int top = integerAttribute(descriptor, "imageTopPosition", 0);
                String disposal = stringAttribute(control, "disposalMethod", "none");
                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                BufferedImage image = reader.read(i);
                Graphics2D graphics = canvas.createGraphics();
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(image, left, top, null);
                graphics.dispose();

                BufferedImage scaled = scale(copy(canvas));
                long nextPixels = (long) scaled.getWidth() * scaled.getHeight();
                if (!frames.isEmpty() && pixels + nextPixels > MAX_TOTAL_PIXELS) {
                    break;
                }
                frames.add(scaled);
                delays.add(Math.max(20L, integerAttribute(control, "delayTime", 10) * 10L));
                pixels += nextPixels;
                restore = before;
                previousDisposal = disposal;
                previousLeft = left;
                previousTop = top;
                previousWidth = image.getWidth();
                previousHeight = image.getHeight();
            }
            if (frames.isEmpty()) {
                throw new IOException("No GIF frames decoded");
            }
            return new MascotMedia(frames, delays);
        }
        finally {
            if (reader != null) {
                reader.dispose();
            }
            input.close();
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, MAX_DIMENSION / (double) Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        if (targetWidth == width && targetHeight == height && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return target;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return target;
    }

    private static Node child(Node root, String name) {
        if (root == null) {
            return null;
        }
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (name.equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }

    private static int integerAttribute(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(stringAttribute(node, name, String.valueOf(fallback)));
        }
        catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stringAttribute(Node node, String name, String fallback) {
        if (node == null) {
            return fallback;
        }
        NamedNodeMap attributes = node.getAttributes();
        Node attribute = attributes == null ? null : attributes.getNamedItem(name);
        return attribute == null ? fallback : attribute.getNodeValue();
    }

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
