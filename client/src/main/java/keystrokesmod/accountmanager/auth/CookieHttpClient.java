package keystrokesmod.accountmanager.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLSocketFactory;
import keystrokesmod.accountmanager.auth.CookieStore;
import keystrokesmod.accountmanager.utils.SSLUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

final class CookieHttpClient {
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom().setConnectionRequestTimeout(30000).setConnectTimeout(30000).setSocketTimeout(30000).build();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
    private final CookieStore store;

    CookieHttpClient(CookieStore store) {
        this.store = store;
    }

    CookieStore getStore() {
        return this.store;
    }

    void followRedirects(String startUrl, int maxRedirects) throws Exception {
        try (CloseableHttpClient client = CookieHttpClient.createHttpClient()) {
            String currentUrl = startUrl;
            for (int hop = 0; hop < maxRedirects; ++hop) {
                URI uri = URI.create(currentUrl);
                HttpGet request = new HttpGet(uri);
                request.setConfig(CookieHttpClient.REQUEST_CONFIG);
                request.setHeader("Host", uri.getHost());
                request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36");
                request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                request.setHeader("Accept-Language", "en-US,en;q=0.9");
                request.setHeader("Connection", "keep-alive");
                String cookieHeader = this.store.buildCookieHeader(uri);
                if (StringUtils.isNotBlank((CharSequence)cookieHeader)) {
                    request.setHeader("Cookie", cookieHeader);
                }
                CloseableHttpResponse response = client.execute((HttpUriRequest)request);
                int statusCode = response.getStatusLine().getStatusCode();
                this.mergeResponseCookies(response, uri);
                String location = response.getFirstHeader("Location") != null ? response.getFirstHeader("Location").getValue() : null;
                EntityUtils.consume((HttpEntity)response.getEntity());
                response.close();
                if (location != null) {
                    currentUrl = CookieHttpClient.resolveRedirectUrl(currentUrl, location);
                    continue;
                }
                if (statusCode >= 200 && statusCode < 300) {
                    return;
                }
                throw new IOException("Request failed (" + statusCode + ") at " + currentUrl);
            }
            throw new IOException("Too many redirects while requesting " + startUrl);
        }
    }

    String followOAuthRedirects(String startUrl, int maxRedirects, List<String> preferredOrder) throws Exception {
        try (CloseableHttpClient client = CookieHttpClient.createHttpClient();){
            String currentUrl = startUrl;
            for (int hop = 0; hop < maxRedirects; ++hop) {
                String location;
                block26: {
                    int statusCode;
                    block25: {
                        String cookieHeader;
                        URI uri = URI.create(currentUrl);
                        HttpGet request = new HttpGet(uri);
                        request.setConfig(REQUEST_CONFIG);
                        request.setHeader("Host", uri.getHost());
                        request.setHeader("User-Agent", USER_AGENT);
                        request.setHeader("Accept", "*/*");
                        request.setHeader("Accept-Language", "en-US,en;q=0.9");
                        request.setHeader("Connection", "keep-alive");
                        String string = cookieHeader = preferredOrder == null ? this.store.buildCookieHeader(uri) : this.store.buildCookieHeader(uri, preferredOrder);
                        if (StringUtils.isNotBlank((CharSequence)cookieHeader)) {
                            request.setHeader("Cookie", cookieHeader);
                        }
                        CloseableHttpResponse response = client.execute((HttpUriRequest)request);
                        statusCode = response.getStatusLine().getStatusCode();
                        this.mergeResponseCookies(response, uri);
                        location = response.getFirstHeader("Location") != null ? response.getFirstHeader("Location").getValue() : null;
                        EntityUtils.consume((HttpEntity)response.getEntity());
                        response.close();
                        if (location == null) break block25;
                        String oauthError = CookieHttpClient.extractOAuthError(location);
                        if (oauthError != null) {
                            throw new IOException(oauthError);
                        }
                        String token = CookieHttpClient.extractAccessToken(location);
                        if (token != null) {
                            String string2 = token;
                            return string2;
                        }
                        if (statusCode == 302 || statusCode == 303 || statusCode == 301 || statusCode == 307) break block26;
                    }
                    if (statusCode == 200) {
                        String string = null;
                        return string;
                    }
                    break;
                }
                currentUrl = CookieHttpClient.resolveRedirectUrl(currentUrl, location);
            }
        }
        return null;
    }

    private void mergeResponseCookies(CloseableHttpResponse response, URI requestUri) {
        for (Header header : response.getHeaders("Set-Cookie")) {
            this.parseSetCookie(header.getValue(), requestUri);
        }
    }

    private void parseSetCookie(String headerValue, URI requestUri) {
        if (StringUtils.isBlank((CharSequence)headerValue)) {
            return;
        }
        String[] parts = headerValue.split(";", -1);
        if (parts.length == 0) {
            return;
        }
        String nameValue = parts[0].trim();
        int equals = nameValue.indexOf(61);
        if (equals <= 0) {
            return;
        }
        String name = nameValue.substring(0, equals).trim();
        String value = nameValue.substring(equals + 1).trim();
        String domain = requestUri.getHost();
        String path = "/";
        boolean secure = false;
        for (int i = 1; i < parts.length; ++i) {
            String attrValue;
            String attribute = parts[i].trim();
            if (attribute.isEmpty()) continue;
            int attrEquals = attribute.indexOf(61);
            String key = attrEquals > 0 ? attribute.substring(0, attrEquals).trim().toLowerCase(Locale.ROOT) : attribute.toLowerCase(Locale.ROOT);
            String string = attrValue = attrEquals > 0 ? attribute.substring(attrEquals + 1).trim() : "";
            if ("domain".equals(key) && !attrValue.isEmpty()) {
                domain = attrValue;
                continue;
            }
            if ("path".equals(key) && !attrValue.isEmpty()) {
                path = attrValue;
                continue;
            }
            if (!"secure".equals(key)) continue;
            secure = true;
        }
        this.store.put(domain, path, name, value, secure);
    }

    private static String resolveRedirectUrl(String currentUrl, String location) throws Exception {
        return URI.create(currentUrl).resolve(location).toString();
    }

    private static String extractOAuthError(String location) {
        String query = location;
        if (location.contains("#")) {
            query = location.split("#", 2)[1];
        } else if (location.contains("?")) {
            query = location.split("\\?", 2)[1];
        }
        String error = null;
        String description = null;
        for (String param : query.split("&")) {
            if (param.startsWith("error=")) {
                error = param.substring("error=".length());
                continue;
            }
            if (!param.startsWith("error_description=")) continue;
            description = param.substring("error_description=".length());
        }
        if (error == null) {
            return null;
        }
        try {
            error = URLDecoder.decode(error, "UTF-8");
            if (description != null) {
                description = URLDecoder.decode(description, "UTF-8");
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return description != null ? error + ": " + description : error;
    }

    private static String extractAccessToken(String location) throws Exception {
        if (location.contains("#")) {
            String fragment = location.split("#", 2)[1];
            for (String param : fragment.split("&")) {
                if (!param.startsWith("access_token=")) continue;
                return URLDecoder.decode(param.substring("access_token=".length()), "UTF-8");
            }
        }
        if (location.contains("access_token=")) {
            int start = location.indexOf("access_token=") + "access_token=".length();
            int end = location.indexOf(38, start);
            String token = end == -1 ? location.substring(start) : location.substring(start, end);
            return URLDecoder.decode(token, "UTF-8");
        }
        return null;
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
}

