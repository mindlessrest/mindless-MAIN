package mindless.accountmanager.auth;

import java.net.URI;
import java.util.Locale;

final class StoredCookie {
    final String domain;
    final String path;
    final String name;
    final String value;
    final boolean secure;

    StoredCookie(String domain, String path, String name, String value, boolean secure) {
        this.domain = StoredCookie.normalizeDomain(domain);
        this.path = StoredCookie.normalizePath(path);
        this.name = name;
        this.value = value;
        this.secure = secure;
    }

    boolean matches(URI uri) {
        if (uri == null || this.name == null || this.name.isEmpty()) {
            return false;
        }
        if (this.secure && !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        if (!this.domainMatches(host = host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String requestPath = uri.getPath();
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }
        return requestPath.startsWith(this.path);
    }

    private boolean domainMatches(String host) {
        if (this.domain == null || this.domain.isEmpty()) {
            return true;
        }
        String normalized = this.domain.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            String bare = normalized.substring(1);
            return host.equals(bare) || host.endsWith(normalized);
        }
        return host.equals(normalized);
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) {
            return "";
        }
        if ((domain = domain.trim().toLowerCase(Locale.ROOT)).isEmpty()) {
            return "";
        }
        if (!domain.startsWith(".") && domain.contains(".")) {
            return "." + domain;
        }
        return domain;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}

