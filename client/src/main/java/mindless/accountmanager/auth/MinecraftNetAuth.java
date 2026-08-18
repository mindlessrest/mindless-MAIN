package mindless.accountmanager.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLDecoder;
import mindless.accountmanager.auth.CookieHttpClient;
import mindless.accountmanager.auth.CookieStore;
import org.apache.commons.lang3.StringUtils;

final class MinecraftNetAuth {
    private static final String[] LOGIN_ENTRY_URLS = new String[]{"https://www.minecraft.net/msaproxy/login/signin?returnUrl=https%3A%2F%2Fwww.minecraft.net%2Fen-us%2Fprofile", "https://www.minecraft.net/en-us/login", "https://login.live.com/oauth20_authorize.srf?client_id=000000004C12AE8F&redirect_uri=https%3A%2F%2Fwww.minecraft.net%2Flogin&response_type=code&scope=XboxLive.Signin%20XboxLive.offline_access&prompt=none", "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&response_type=code&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&prompt=none"};
    private static final String[] PROFILE_URLS = new String[]{"https://www.minecraft.net/en-us/profile", "https://www.minecraft.net/en-us/msaprofile/mygames/editprofile"};

    private MinecraftNetAuth() {
    }

    static String loginForMinecraftToken(CookieStore store) throws Exception {
        String token;
        CookieHttpClient client = new CookieHttpClient(store);
        Exception lastError = null;
        for (String entryUrl : LOGIN_ENTRY_URLS) {
            try {
                client.followRedirects(entryUrl, 20);
                token = MinecraftNetAuth.extractMinecraftAccessToken(store);
                if (token == null) continue;
                return token;
            }
            catch (Exception e) {
                lastError = e;
            }
        }
        for (String profileUrl : PROFILE_URLS) {
            try {
                client.followRedirects(profileUrl, 10);
                token = MinecraftNetAuth.extractMinecraftAccessToken(store);
                if (token == null) continue;
                return token;
            }
            catch (Exception e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    static String extractMinecraftAccessToken(CookieStore store) {
        String bearerToken = store.findMinecraftNetValue("bearer_token");
        if (MinecraftNetAuth.looksLikeJwt(bearerToken)) {
            return bearerToken;
        }
        String accessTokenCookie = store.findMinecraftNetValue("access_token");
        if (StringUtils.isBlank((CharSequence)accessTokenCookie)) {
            return null;
        }
        String decoded = accessTokenCookie;
        try {
            decoded = URLDecoder.decode(accessTokenCookie, "UTF-8");
        }
        catch (Exception exception) {
            // empty catch block
        }
        if (MinecraftNetAuth.looksLikeJwt(decoded)) {
            return decoded;
        }
        try {
            String token;
            String token2;
            JsonObject user;
            JsonElement rootElement = new JsonParser().parse(decoded);
            if (!rootElement.isJsonObject()) {
                return null;
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (root.has("user") && root.get("user").isJsonObject() && (user = root.getAsJsonObject("user")).has("accessToken") && MinecraftNetAuth.looksLikeJwt(token2 = user.get("accessToken").getAsString())) {
                return token2;
            }
            if (root.has("accessToken") && MinecraftNetAuth.looksLikeJwt(token = root.get("accessToken").getAsString())) {
                return token;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    private static boolean looksLikeJwt(String value) {
        return value != null && value.startsWith("eyJ") && value.split("\\.").length == 3;
    }
}

