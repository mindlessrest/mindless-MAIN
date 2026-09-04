package mindless.runtime;

public final class EnvironmentGuard {
    private static final String API_URL = "https://api.mindless.rest";

    private EnvironmentGuard() {}

    public static boolean check() {
        String token = System.getProperty("mindless.auth.token");
        String apiUrl = System.getProperty("mindless.auth.apiUrl");
        String hwid = System.getProperty("mindless.auth.hwid");
        return token != null && token.length() >= 16 && token.length() <= 4096
                && API_URL.equals(apiUrl)
                && hwid != null && !hwid.trim().isEmpty() && hwid.length() <= 512;
    }
}
