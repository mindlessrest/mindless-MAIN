package mindless.utility.media;

import mindless.module.impl.client.SpotifyMiniPlayer;
public final class MediaPlayerRenderer {
    private MediaPlayerRenderer() {
    }

    private static boolean widgetStyle() {
        return SpotifyMiniPlayer.widgetStyle == null || (int) SpotifyMiniPlayer.widgetStyle.getInput() == 0;
    }

    public static void render() {
        if (widgetStyle()) {
            SpotifyWidgetRenderer.render();
        }
        else {
            SpotifyMiniPlayerRenderer.render();
        }
    }

    public static float[] renderPreview() {
        return widgetStyle()
                ? SpotifyWidgetRenderer.renderPreview()
                : SpotifyMiniPlayerRenderer.renderPreview();
    }
public static float[] getLyricsRect() {
        return widgetStyle() ? SpotifyWidgetRenderer.getLyricsRect() : null;
    }

    public static float[] getCurrentRect() {
        return widgetStyle()
                ? SpotifyWidgetRenderer.getCurrentRect()
                : SpotifyMiniPlayerRenderer.getCurrentRect();
    }
}
