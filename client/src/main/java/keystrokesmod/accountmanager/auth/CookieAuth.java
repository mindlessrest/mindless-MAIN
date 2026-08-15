package keystrokesmod.accountmanager.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocketFactory;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.AccountType;
import keystrokesmod.accountmanager.auth.CookieHttpClient;
import keystrokesmod.accountmanager.auth.CookieStore;
import keystrokesmod.accountmanager.auth.MinecraftNetAuth;
import keystrokesmod.accountmanager.auth.SessionManager;
import keystrokesmod.accountmanager.gui.GuiAccountManager;
import keystrokesmod.accountmanager.gui.GuiCookieAuth;
import keystrokesmod.accountmanager.utils.Notification;
import keystrokesmod.accountmanager.utils.SSLUtil;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.util.Session;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class CookieAuth {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Gson GSON = new Gson();
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom().setConnectionRequestTimeout(30000).setConnectTimeout(30000).setSocketTimeout(30000).build();
    private static final List<String> COOKIE_ORDER_JSHP = Arrays.asList("__Host-MSAAUTH", "__Host-MSAAUTHP", "JSHP", "JSH", "MSPAuth", "MSPBack", "MSPProf", "MSPRequ", "MSPSoftVis", "MSPOK", "MSPShared", "MSPPre", "MSPCID", "MSPOAuthVis", "AMCSecAuth", "NAP", "ANON", "OParams", "PPLState", "WLSSC", "uaid", "pres", "LOpt");
    private static final List<String> COOKIE_ORDER_JSH = Arrays.asList("__Host-MSAAUTH", "__Host-MSAAUTHP", "JSH", "JSHP", "MSPAuth", "MSPBack", "MSPProf", "MSPRequ", "MSPSoftVis", "MSPOK", "MSPShared", "MSPPre", "MSPCID", "MSPOAuthVis", "AMCSecAuth", "NAP", "ANON", "OParams", "PPLState", "WLSSC", "uaid", "pres", "LOpt");
    private static final String OAUTH_URL_SISU = "https://login.live.com/oauth20_authorize.srf?redirect_uri=https://sisu.xboxlive.com/connect/oauth/XboxLive&response_type=token&client_id=000000004420578E&scope=XboxLive.Signin%20XboxLive.offline_access&prompt=none";
    private static final String OAUTH_URL_DESKTOP = "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&response_type=token&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&prompt=none";

    public static CompletableFuture<Boolean> addAccountFromCookieFile(File cookieFile, GuiCookieAuth gui) {
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        EXECUTOR.execute(() -> {
            try {
                gui.setStatus("&fReading cookie file...&r");
                CookieStore cookieStore = CookieAuth.parseCookieFile(cookieFile);
                if (cookieStore.isEmpty()) {
                    gui.setStatus("&cNo valid Microsoft cookies found in file&r");
                    future.complete(false);
                    return;
                }
                if (!cookieStore.hasRequiredAuthCookies()) {
                    gui.setStatus("&cMissing auth cookies (need __Host-MSAAUTH, JSH, or JSHP)&r");
                    future.complete(false);
                    return;
                }
                gui.setStatus("&fAuthenticating with Microsoft...&r");
                CookieAuth.authenticateWithCookies(cookieStore, gui).whenComplete((result, ex) -> {
                    if (ex != null) {
                        System.err.println("[CookieAuth] Authentication failed: " + ex.getMessage());
                        ex.printStackTrace();
                        gui.setStatus("&cAuthentication failed: " + ex.getMessage() + "&r");
                        future.complete(false);
                    } else {
                        future.complete((Boolean)result);
                    }
                });
            }
            catch (Exception e) {
                gui.setStatus("&cError processing cookie file: " + e.getMessage() + "&r");
                e.printStackTrace();
                future.complete(false);
            }
        });
        return future;
    }

    private static CookieStore parseCookieFile(File cookieFile) throws IOException {
        String content = CookieAuth.readFile(cookieFile);
        if (content.trim().isEmpty()) {
            return new CookieStore();
        }
        if (content.trim().startsWith("[")) {
            return CookieAuth.parseJsonCookies(content);
        }
        CookieStore cookies = CookieAuth.parseNetscapeCookies(content);
        if (!cookies.isEmpty()) {
            return cookies;
        }
        return CookieAuth.parseLooseCookies(content);
    }

    private static String readFile(File cookieFile) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(cookieFile), StandardCharsets.UTF_8));){
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static CookieStore parseJsonCookies(String content) {
        CookieStore store = new CookieStore();
        try {
            JsonArray array;
            JsonElement root = new JsonParser().parse(content);
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("cookies")) {
                array = root.getAsJsonObject().getAsJsonArray("cookies");
            } else {
                return store;
            }
            for (JsonElement element : array) {
                boolean secure;
                double expiration;
                JsonObject obj;
                if (!element.isJsonObject() || !(obj = element.getAsJsonObject()).has("name") || !obj.has("value") || obj.has("expirationDate") && (expiration = obj.get("expirationDate").getAsDouble()) > 0.0 && expiration < (double)System.currentTimeMillis() / 1000.0) continue;
                String domain = "";
                if (obj.has("domain")) {
                    domain = obj.get("domain").getAsString();
                } else if (obj.has("host")) {
                    domain = obj.get("host").getAsString();
                }
                String path = obj.has("path") ? obj.get("path").getAsString() : "/";
                String name = obj.get("name").getAsString().trim();
                String value = obj.get("value").getAsString().trim();
                boolean bl = secure = !obj.has("secure") || obj.get("secure").getAsBoolean();
                if (!CookieStore.isRelevantDomain(domain) || value.isEmpty()) continue;
                store.put(domain, path, name, value, secure);
            }
        }
        catch (Exception e) {
            System.err.println("[CookieAuth] Failed to parse JSON cookies: " + e.getMessage());
        }
        return store;
    }

    private static CookieStore parseNetscapeCookies(String content) {
        CookieStore store = new CookieStore();
        for (String line : content.split("\\r?\\n")) {
            String[] parts;
            if ((line = line.trim()).isEmpty() || line.startsWith("#") || (parts = line.split("\t", 7)).length < 7) continue;
            String domain = parts[0].trim();
            String path = parts[2].trim();
            String name = parts[5].trim();
            String value = parts[6].trim();
            boolean secure = "TRUE".equalsIgnoreCase(parts[3].trim());
            if (!CookieStore.isRelevantDomain(domain) || value.isEmpty()) continue;
            store.put(domain, path, name, value, secure);
        }
        return store;
    }

    private static CookieStore parseLooseCookies(String content) {
        String value;
        String name;
        int equals;
        CookieStore store = new CookieStore();
        String normalized = content.replace("\n", "").replace("\r", "");
        for (String segment : normalized.split(";")) {
            if (!(segment = segment.trim()).contains("=")) continue;
            equals = segment.indexOf(61);
            name = segment.substring(0, equals).trim();
            value = segment.substring(equals + 1).trim();
            if (value.isEmpty()) continue;
            store.put("", "/", name, value, true);
        }
        if (store.isEmpty()) {
            for (String line : content.split("\\r?\\n")) {
                if (!(line = line.trim()).contains("=")) continue;
                equals = line.indexOf(61);
                name = line.substring(0, equals).trim();
                value = line.substring(equals + 1).trim();
                if (value.isEmpty()) continue;
                store.put("", "/", name, value, true);
            }
        }
        return store;
    }

    public static String serializeCookies(CookieStore store) {
        return store.serialize();
    }

    public static CookieStore deserializeCookieStore(String serialized) {
        return CookieStore.deserialize(serialized);
    }

    public static CompletableFuture<Account> loginWithStoredCookies(String serializedCookies, Executor executor) {
        CookieStore store = CookieAuth.deserializeCookieStore(serializedCookies);
        if (store.isEmpty()) {
            CompletableFuture<Account> failed = new CompletableFuture<Account>();
            failed.completeExceptionally(new IOException("No saved cookie data"));
            return failed;
        }
        return CookieAuth.loginWithCookies(store, executor);
    }

    public static CompletableFuture<Account> loginWithCookies(CookieStore store, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return CookieAuth.authenticateWithStore(store);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private static Account authenticateWithStore(CookieStore store) throws Exception {
        Exception lastError = null;
        try {
            String msAccessToken = CookieAuth.getMicrosoftAccessToken(store);
            if (msAccessToken != null) {
                return CookieAuth.finishMicrosoftTokenLogin(store, msAccessToken);
            }
        }
        catch (Exception e) {
            lastError = e;
            System.err.println("[CookieAuth] Xbox OAuth login failed: " + e.getMessage());
        }
        try {
            String mcAccessToken = MinecraftNetAuth.loginForMinecraftToken(store);
            if (mcAccessToken != null) {
                return CookieAuth.finishMinecraftTokenLogin(store, mcAccessToken);
            }
        }
        catch (Exception e) {
            lastError = e;
            System.err.println("[CookieAuth] minecraft.net fallback failed: " + e.getMessage());
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Failed to authenticate with cookies (cookies may be expired)");
    }

    private static String getMicrosoftAccessToken(CookieStore store) throws Exception {
        CookieHttpClient client = new CookieHttpClient(store);
        Exception lastError = null;
        String[] oauthUrls = new String[]{OAUTH_URL_SISU, OAUTH_URL_DESKTOP};
        ArrayList<List<String>> orderings = new ArrayList<List<String>>();
        orderings.add(COOKIE_ORDER_JSHP);
        orderings.add(COOKIE_ORDER_JSH);
        for (String oauthUrl : oauthUrls) {
            for (List list : orderings) {
                try {
                    String token = client.followOAuthRedirects(oauthUrl, 12, list);
                    if (token == null) continue;
                    return token;
                }
                catch (Exception e) {
                    lastError = e;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private static Account finishMicrosoftTokenLogin(CookieStore store, String msAccessToken) throws Exception {
        Map<String, String> xbl = CookieAuth.acquireXboxLiveToken(msAccessToken);
        String xstsToken = CookieAuth.acquireXstsToken(xbl.get("Token"));
        String xblToken = "XBL3.0 x=" + xbl.get("uhs") + ";" + xstsToken;
        McResponse mcResponse = CookieAuth.postMinecraftLogin(xblToken);
        if (mcResponse == null || mcResponse.access_token == null) {
            throw new IOException("Failed to get Minecraft access token");
        }
        ProfileResponse profile = CookieAuth.getMinecraftProfile(mcResponse.access_token);
        if (profile == null || profile.name == null) {
            throw new IOException("Failed to get Minecraft profile");
        }
        return new Account(store.serialize(), mcResponse.access_token, profile.name, profile.id, 0L, AccountType.COOKIE);
    }

    private static Account finishMinecraftTokenLogin(CookieStore store, String mcAccessToken) throws Exception {
        ProfileResponse profile = CookieAuth.getMinecraftProfile(mcAccessToken);
        if (profile == null || profile.name == null) {
            throw new IOException("Failed to get Minecraft profile");
        }
        return new Account(store.serialize(), mcAccessToken, profile.name, profile.id, 0L, AccountType.COOKIE);
    }

    private static CompletableFuture<Boolean> authenticateWithCookies(CookieStore store, GuiCookieAuth gui) {
        gui.setStatus("&fAuthenticating with cookies...&r");
        return CookieAuth.loginWithCookies(store, EXECUTOR).thenApply((Account account) -> {
            Session session = new Session(account.getUsername(), account.getUuid(), account.getAccessToken(), "mojang");
            AccountManager.accounts.add((Account)account);
            AccountManager.save();
            SessionManager.set(session);
            gui.setStatus("&aSuccessfully logged in as " + session.getUsername() + "&r");
            return true;
        }).exceptionally((Throwable error) -> { Throwable cause = error.getCause() != null ? error.getCause() : error;
            System.err.println("[CookieAuth] Authentication failed: " + cause.getMessage());
            cause.printStackTrace();
            gui.setStatus("&cAuthentication failed: " + cause.getMessage() + "&r");
            return false;
        });
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static Map<String, String> acquireXboxLiveToken(String accessToken) throws Exception {
        Exception iOException;
        Exception lastError = null;
        for (String ticketPrefix : new String[]{"t=", "d="}) {
            try {
                JsonObject entity = new JsonObject();
                JsonObject properties = new JsonObject();
                properties.addProperty("AuthMethod", "RPS");
                properties.addProperty("SiteName", "user.auth.xboxlive.com");
                properties.addProperty("RpsTicket", ticketPrefix + accessToken);
                entity.add("Properties", (JsonElement)properties);
                entity.addProperty("RelyingParty", "http://auth.xboxlive.com");
                entity.addProperty("TokenType", "JWT");
                try (CloseableHttpClient client = CookieAuth.createHttpClient();){
                    HttpPost request = new HttpPost(URI.create("https://user.auth.xboxlive.com/user/authenticate"));
                    request.setConfig(REQUEST_CONFIG);
                    request.setHeader("Content-Type", "application/json");
                    request.setHeader("User-Agent", "Go-http-client/1.1");
                    request.setHeader("X-Xbl-Contract-Version", "0");
                    request.setEntity((HttpEntity)new StringEntity(entity.toString(), StandardCharsets.UTF_8));
                    CloseableHttpResponse response = client.execute((HttpUriRequest)request);
                    int code = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString((HttpEntity)response.getEntity(), (Charset)StandardCharsets.UTF_8);
                    response.close();
                    if (code != 200) {
                        throw new IOException("Xbox Live authentication failed (" + code + "): " + body);
                    }
                    JsonObject json = new JsonParser().parse(body).getAsJsonObject();
                    LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
                    result.put("Token", json.get("Token").getAsString());
                    result.put("uhs", json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString());
                    LinkedHashMap<String, String> linkedHashMap = result;
                    return linkedHashMap;
                }
            }
            catch (Exception e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            iOException = lastError;
            throw iOException;
        }
        iOException = new IOException("Xbox Live authentication failed");
        throw iOException;
    }

    private static String acquireXstsToken(String xboxToken) throws Exception {
        JsonObject entity = new JsonObject();
        JsonObject properties = new JsonObject();
        JsonArray userTokens = new JsonArray();
        userTokens.add((JsonElement)new JsonPrimitive(xboxToken));
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", (JsonElement)userTokens);
        entity.add("Properties", (JsonElement)properties);
        entity.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        entity.addProperty("TokenType", "JWT");
        try (CloseableHttpClient client = CookieAuth.createHttpClient();){
            HttpPost request = new HttpPost(URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"));
            request.setConfig(REQUEST_CONFIG);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("User-Agent", "Go-http-client/1.1");
            request.setHeader("X-Xbl-Contract-Version", "0");
            request.setEntity((HttpEntity)new StringEntity(entity.toString(), StandardCharsets.UTF_8));
            CloseableHttpResponse response = client.execute((HttpUriRequest)request);
            int code = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString((HttpEntity)response.getEntity(), (Charset)StandardCharsets.UTF_8);
            response.close();
            if (code != 200) {
                throw new IOException("XSTS authentication failed (" + code + "): " + body);
            }
            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            if (json.has("XErr")) {
                throw new IOException("XSTS error: " + json.get("XErr").getAsString());
            }
            String string = json.get("Token").getAsString();
            return string;
        }
    }

    public static McResponse postMinecraftLogin(String xblToken) throws Exception {
        GuiAccountManager.notification = new Notification(TextFormatting.translate("&7Logging into Minecraft services..."), 5000L);
        String payload = "{\"identityToken\":\"" + xblToken + "\",\"ensureLegacyEnabled\":true}";
        try (CloseableHttpClient client = CookieAuth.createHttpClient();){
            HttpPost request = new HttpPost(URI.create("https://api.minecraftservices.com/authentication/login_with_xbox"));
            request.setConfig(REQUEST_CONFIG);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("Accept", "application/json");
            request.setEntity((HttpEntity)new StringEntity(payload, StandardCharsets.UTF_8));
            CloseableHttpResponse response = client.execute((HttpUriRequest)request);
            int code = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString((HttpEntity)response.getEntity(), (Charset)StandardCharsets.UTF_8);
            response.close();
            if (code != 200) {
                throw new IOException("Minecraft login failed (" + code + "): " + body);
            }
            McResponse mcResponse = (McResponse)GSON.fromJson(body, McResponse.class);
            return mcResponse;
        }
    }

    public static ProfileResponse getMinecraftProfile(String accessToken) throws Exception {
        GuiAccountManager.notification = new Notification(TextFormatting.translate("&7Fetching Minecraft profile..."), 5000L);
        try (CloseableHttpClient client = CookieAuth.createHttpClient();){
            HttpGet request = new HttpGet(URI.create("https://api.minecraftservices.com/minecraft/profile"));
            request.setConfig(REQUEST_CONFIG);
            request.setHeader("Authorization", "Bearer " + accessToken);
            request.setHeader("Accept", "application/json");
            CloseableHttpResponse response = client.execute((HttpUriRequest)request);
            int code = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString((HttpEntity)response.getEntity(), (Charset)StandardCharsets.UTF_8);
            response.close();
            if (code != 200) {
                throw new IOException("Minecraft profile request failed (" + code + "): " + body);
            }
            ProfileResponse profileResponse = (ProfileResponse)GSON.fromJson(body, ProfileResponse.class);
            return profileResponse;
        }
    }

    private static CloseableHttpClient createHttpClient() {
        try {
            SSLSocketFactory socketFactory = SSLUtil.getSSLContext().getSocketFactory();
            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(socketFactory, new String[]{"TLSv1.2"}, null, (X509HostnameVerifier)new BrowserCompatHostnameVerifier());
            return HttpClientBuilder.create().setSSLSocketFactory((LayeredConnectionSocketFactory)sslsf).disableRedirectHandling().build();
        }
        catch (Exception e) {
            return HttpClients.custom().disableRedirectHandling().build();
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }

    public static class McResponse {
        public String access_token;
    }

    public static class ProfileResponse {
        public String name;
        public String id;
    }
}

