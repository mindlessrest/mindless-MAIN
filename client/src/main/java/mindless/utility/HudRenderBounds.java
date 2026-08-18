package mindless.utility;

/** Screen-space bounds shared by HUD renderers and overlay GUI effects. */
public final class HudRenderBounds {
    private static float scoreboardLeft;
    private static float scoreboardTop;
    private static float scoreboardRight;
    private static float scoreboardBottom;
    private static boolean scoreboardVisible;

    private HudRenderBounds() {
    }

    public static void setScoreboard(float left, float top, float right, float bottom) {
        scoreboardLeft = left;
        scoreboardTop = top;
        scoreboardRight = right;
        scoreboardBottom = bottom;
        scoreboardVisible = right > left && bottom > top;
    }

    public static void clearScoreboard() {
        scoreboardVisible = false;
    }

    public static float[] getScoreboard(float padding) {
        if (!scoreboardVisible) {
            return null;
        }
        return new float[] {
                scoreboardLeft - padding,
                scoreboardTop - padding,
                scoreboardRight + padding,
                scoreboardBottom + padding
        };
    }
}
