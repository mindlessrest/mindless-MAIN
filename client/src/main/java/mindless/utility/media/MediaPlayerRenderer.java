package mindless.utility.media;

import mindless.module.impl.client.SpotifyMiniPlayer;

/**
 * Picks which player is on screen.
 *
 * <p>There are two, and they are not variations on each other: the client's own panel, and a port
 * of the OBS widget. Everything that draws the player or asks where it is goes through here, so
 * the choice lives in one place rather than being made again at each of the seven call sites --
 * and so the drag-to-move screen can never end up outlining one while the other is drawn.
 */
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

    /** The lyric bubble's own rectangle, when it is showing and is a separate thing to drag. */
    public static float[] getLyricsRect() {
        return widgetStyle() ? SpotifyWidgetRenderer.getLyricsRect() : null;
    }

    public static float[] getCurrentRect() {
        return widgetStyle()
                ? SpotifyWidgetRenderer.getCurrentRect()
                : SpotifyMiniPlayerRenderer.getCurrentRect();
    }
}
