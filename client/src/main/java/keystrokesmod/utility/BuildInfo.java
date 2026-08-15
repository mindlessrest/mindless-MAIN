package keystrokesmod.utility;

import java.io.InputStream;
import java.util.Properties;

/** Reads build metadata injected by Gradle at compile time. */
public final class BuildInfo {
    private static final String DATE;
    private static final String TIME;
    private static final String VERSION;

    static {
        String date = "unknown", time = "unknown", version = "1.0";
        try (InputStream in = BuildInfo.class.getResourceAsStream(
                "/assets/keystrokesmod/build.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                date    = props.getProperty("build.date", date);
                time    = props.getProperty("build.time", time);
                version = props.getProperty("build.version", version);
            }
        } catch (Exception ignored) {}
        DATE    = date;
        TIME    = time;
        VERSION = version;
    }

    private BuildInfo() {}

    public static String getDate()    { return DATE; }
    public static String getTime()    { return TIME; }
    public static String getVersion() { return VERSION; }
    public static String getBuild()   { return DATE + "  " + TIME; }
}
