package keystrokesmod.accountmanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Minimal HTTP + JSON helper shared by NiceAlts and Localts screens.
 * No external dependencies beyond standard Java + JDK.
 */
public final class AltShopHttp {
    private AltShopHttp() {}

    public static String post(String urlStr, String jsonBody) throws Exception {
        return post(urlStr, jsonBody, null, null);
    }

    public static String post(String urlStr, String jsonBody, String headerKey, String headerVal) throws Exception {
        HttpURLConnection conn = open(urlStr, headerKey, headerVal);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    public static String get(String urlStr, String headerKey, String headerVal) throws Exception {
        HttpURLConnection conn = open(urlStr, headerKey, headerVal);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        return readResponse(conn);
    }

    public static String get(String urlStr) throws Exception {
        return get(urlStr, null, null);
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream in = status == 200 ? conn.getInputStream() : conn.getErrorStream();
        String body = in != null ? readAll(in) : "";
        if (status != 200) throw new IOException("HTTP " + status + ": " + body);
        return body;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static HttpURLConnection open(String urlStr, String headerKey, String headerVal) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, null, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java; Minecraft 1.8.9)");
        conn.setRequestProperty("Accept", "application/json");
        if (headerKey != null) conn.setRequestProperty(headerKey, headerVal);
        return conn;
    }

    /** Naive JSON string-field extractor — avoids a Gson dependency. */
    public static String field(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        idx += search.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        char c = json.charAt(idx);
        if (c == '"') {
            int end = json.indexOf('"', idx + 1);
            return end == -1 ? null : json.substring(idx + 1, end);
        }
        int end = idx;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(idx, end).trim();
    }

    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
