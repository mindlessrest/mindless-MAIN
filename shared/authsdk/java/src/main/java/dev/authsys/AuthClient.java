package dev.authsys;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.authsys.model.*;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Client for the AuthSys API. Covers all auth, software, and admin endpoints.
 *
 * <p>Every request to protected endpoints automatically includes anti-replay
 * headers (nonce + timestamp). Software endpoints include the X-Client-Type
 * and X-HWID headers when HWID is set.
 *
 * <p>Usage:
 * <pre>
 *   AuthClient client = new AuthClient("https://api.example.com");
 *   LoginResult result = client.login("myuser", "password123");
 *   // Token is set automatically after login
 *   SessionInfo session = client.validateSession();
 * </pre>
 */
public class AuthClient {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;

    private String token;
    private String hwid;
    private LastResponse lastResponse;

    /**
     * Creates a new client pointing at the given server.
     *
     * @param baseUrl server root URL (e.g. "https://api.example.com"). No trailing slash.
     */
    public AuthClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    // ─── Token management ─────────────────────────────────────────────

    /** Sets the session token for authenticated requests. */
    public void setToken(String token) {
        this.token = token;
    }

    /** Returns the current session token, or null if not set. */
    public String getToken() {
        return token;
    }

    /** Clears the session token. */
    public void clearToken() {
        this.token = null;
    }

    /**
     * Sets the hardware ID sent with software requests (X-HWID header).
     * Call this before making software endpoint calls when HWID locking is enabled.
     */
    public void setHwid(String hwid) {
        this.hwid = hwid;
    }

    /** Alias for setHwid. */
    public void setHWID(String hwid) {
        setHwid(hwid);
    }

    /** Returns the last server response nonce and timestamp for caller verification. */
    public LastResponse getLastResponse() {
        return lastResponse;
    }

    // ─── Auth endpoints ───────────────────────────────────────────────

    /**
     * Authenticates with username/email and password.
     * On success, the session token is set automatically.
     *
     * @param login    username or email
     * @param password account password
     * @return login result containing the session token and expiry
     * @throws AuthException on invalid credentials, banned, locked, etc.
     */
    public LoginResult login(String login, String password) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("login", login);
        body.addProperty("password", password);

        JsonObject data = postJson("/auth/login", body, false);
        String tok = data.get("token").getAsString();
        String expiresAt = data.get("expires_at").getAsString();

        this.token = tok;
        return new LoginResult(tok, expiresAt);
    }

    /**
     * Revokes the current session. Clears the local token.
     *
     * @throws AuthException if the server rejects the request
     */
    public void logout() throws AuthException {
        postJson("/auth/logout", null, true);
        this.token = null;
    }

    /**
     * Registers a new account. Does not log in — the user must verify their email first.
     *
     * @param username   3-32 chars, alphanumeric + underscore
     * @param email      valid email address
     * @param password   min 12 chars, must meet complexity requirements
     * @param inviteCode invite code (null if not required)
     * @throws AuthException on validation errors, conflict, etc.
     */
    public void register(String username, String email, String password, String inviteCode)
            throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("email", email);
        body.addProperty("password", password);
        if (inviteCode != null) {
            body.addProperty("invite_code", inviteCode);
        }
        postJson("/auth/register", body, false);
    }

    /**
     * Verifies an email using the token from the verification email.
     *
     * @param verificationToken hex token from the email link
     * @throws AuthException if the token is invalid or expired
     */
    public void verifyEmail(String verificationToken) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("token", verificationToken);
        postJson("/auth/verify-email", body, false);
    }

    /**
     * Requests a username reminder email. Always succeeds (200) regardless of whether
     * the email exists, to prevent account enumeration.
     *
     * @param email the email address to send the reminder to
     * @throws AuthException on request failure
     */
    public void forgotUsername(String email) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        postJson("/auth/forgot-username", body, false);
    }

    /**
     * Requests a password reset email. Always succeeds (200) regardless of whether
     * the email exists, to prevent account enumeration.
     *
     * @param email the email address to send the reset link to
     * @throws AuthException on request failure
     */
    public void forgotPassword(String email) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        postJson("/auth/forgot-password", body, false);
    }

    /**
     * Resets the password using a token from the reset email.
     * Revokes all existing sessions for the user.
     *
     * @param resetToken  hex token from the reset email
     * @param newPassword the new password (must meet complexity requirements)
     * @throws AuthException if the token is invalid or password is too weak
     */
    public void resetPassword(String resetToken, String newPassword) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("token", resetToken);
        body.addProperty("new_password", newPassword);
        postJson("/auth/reset-password", body, false);
    }

    /**
     * Changes the authenticated user's password. Revokes all other sessions.
     *
     * @param currentPassword the current password
     * @param newPassword     the new password
     * @throws AuthException on wrong current password or weak new password
     */
    public void changePassword(String currentPassword, String newPassword) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("current_password", currentPassword);
        body.addProperty("new_password", newPassword);
        postJson("/auth/change-password", body, true);
    }

    /**
     * Starts an email change. Sends a verification link to the new address.
     * The change isn't applied until the verification link is clicked.
     *
     * @param newEmail the new email address
     * @param password current password for confirmation
     * @throws AuthException on invalid email, wrong password, or conflict
     */
    public void changeEmail(String newEmail, String password) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("new_email", newEmail);
        body.addProperty("password", password);
        postJson("/auth/change-email", body, true);
    }

    /**
     * Resets the user's own HWID binding on all sessions.
     * Only works when hwid_locking is enabled.
     *
     * @param password current password for confirmation
     * @throws AuthException on wrong password or feature disabled
     */
    public void resetHWID(String password) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("password", password);
        postJson("/auth/reset-hwid", body, true);
    }

    /**
     * Resets the user's own IP binding on all sessions.
     * Only works when ip_locking is enabled.
     *
     * @param password current password for confirmation
     * @throws AuthException on wrong password or feature disabled
     */
    public void resetIP(String password) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("password", password);
        postJson("/auth/reset-ip", body, true);
    }

    /**
     * Returns info about the currently authenticated user.
     *
     * @return user info including id, username, email, admin status, etc.
     * @throws AuthException if not authenticated
     */
    public UserInfo getCurrentUser() throws AuthException {
        JsonObject data = getJson("/auth/me", true, false);
        return parseUserInfo(data);
    }

    // ─── Software endpoints ───────────────────────────────────────────

    /**
     * Health check and clock sync. No auth required.
     * Use the server_time in the response to correct for clock skew.
     *
     * @return ping result with server health status and current time
     * @throws AuthException on request failure
     */
    public PingResult ping() throws AuthException {
        JsonObject data = getJson("/software/ping", false, true);
        boolean ok = data.get("ok").getAsBoolean();
        String serverTime = data.get("server_time").getAsString();
        return new PingResult(ok, serverTime);
    }

    /**
     * Validates the current session. Used by the heartbeat and after IPC handoff.
     *
     * @return session info with validity, username, and expiry
     * @throws AuthException if the session is invalid, banned, or HWID/IP mismatch
     */
    public SessionInfo validateSession() throws AuthException {
        JsonObject data = getJson("/software/session-validate", true, true);
        return new SessionInfo(
                data.get("valid").getAsBoolean(),
                data.get("username").getAsString(),
                data.get("expires_at").getAsString()
        );
    }

    /**
     * Downloads a file by ID. Automatically decrypts if the server indicates
     * the response is encrypted (X-Encrypted: true header).
     *
     * @param fileId UUID of the file to download
     * @return raw file bytes (decrypted if necessary)
     * @throws AuthException on not found, banned, or decryption failure
     */
    public byte[] downloadFile(String fileId) throws AuthException {
        return downloadBinary("/software/download/" + fileId, fileId);
    }

    /**
     * Downloads the loader file. Never encrypted by the server, so the bytes
     * are returned as-is.
     *
     * @return raw loader bytes
     * @throws AuthException on not found (NO_LOADER) or other errors
     */
    public byte[] downloadLoader() throws AuthException {
        // The loader is never encrypted -- its key would come from the caller's
        // session token, which they don't have yet when fetching it. No file ID
        // to salt with, so a null here makes an unexpected X-Encrypted fail loudly.
        return downloadBinary("/software/loader", null);
    }

    // ─── Public endpoint ──────────────────────────────────────────────

    /**
     * Returns the server's public configuration. No auth or nonce required.
     * Call once on startup and cache the result.
     *
     * @return public config with feature flags
     * @throws AuthException on request failure
     */
    public PublicConfig getPublicConfig() throws AuthException {
        // This endpoint has no auth, no nonce — simple GET
        Request request = new Request.Builder()
                .url(baseUrl + "/api/public/config")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw parseError(responseBody, response.code());
            }

            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonObject data = json.getAsJsonObject("data");

            return new PublicConfig(
                    data.get("app_name").getAsString(),
                    data.get("hwid_locking").getAsBoolean(),
                    data.get("ip_locking").getAsBoolean(),
                    data.get("invite_only").getAsBoolean()
            );
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException("NETWORK_ERROR", "Failed to fetch public config: " + e.getMessage(), e);
        }
    }

    // ─── Admin endpoints ──────────────────────────────────────────────

    /**
     * Lists users with optional pagination and search.
     *
     * @param page   page number (1-based), or null for default
     * @param limit  results per page (max 100), or null for default
     * @param search username or email prefix filter, or null
     * @return paginated user list
     * @throws AuthException if not admin or other error
     */
    public UserListResult listUsers(Integer page, Integer limit, String search) throws AuthException {
        StringBuilder path = new StringBuilder("/admin/users?");
        if (page != null) path.append("page=").append(page).append("&");
        if (limit != null) path.append("limit=").append(limit).append("&");
        if (search != null) path.append("search=").append(search).append("&");

        JsonObject data = getJson(path.toString(), true, false);
        JsonArray usersArr = data.getAsJsonArray("users");
        List<UserInfo> users = new ArrayList<>();
        for (JsonElement el : usersArr) {
            users.add(parseUserInfo(el.getAsJsonObject()));
        }
        return new UserListResult(
                users,
                data.get("total").getAsInt(),
                data.get("page").getAsInt(),
                data.get("limit").getAsInt()
        );
    }

    /**
     * Gets detailed info for a specific user (admin).
     *
     * @param userId UUID of the user
     * @return user info with session count
     * @throws AuthException if not admin or user not found
     */
    public UserInfo getUser(String userId) throws AuthException {
        JsonObject data = getJson("/admin/users/" + userId, true, false);
        JsonObject userObj = data.getAsJsonObject("user");
        int sessionCount = data.get("session_count").getAsInt();
        return parseUserInfoWithSessionCount(userObj, sessionCount);
    }

    /**
     * Bans a user, revokes all their sessions.
     *
     * @param userId UUID of the user to ban
     * @param reason ban reason (sent to the user via email)
     * @throws AuthException if not admin or user not found
     */
    public void banUser(String userId, String reason) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason);
        postJson("/admin/users/" + userId + "/ban", body, true);
    }

    /**
     * Unbans a user.
     *
     * @param userId UUID of the user to unban
     * @throws AuthException if not admin or user not found
     */
    public void unbanUser(String userId) throws AuthException {
        postJson("/admin/users/" + userId + "/unban", null, true);
    }

    /**
     * Admin resets HWID binding for a target user.
     *
     * @param userId UUID of the user
     * @throws AuthException if not admin
     */
    public void adminResetHwid(String userId) throws AuthException {
        postJson("/admin/users/" + userId + "/reset-hwid", null, true);
    }

    /**
     * Admin resets IP binding for a target user.
     *
     * @param userId UUID of the user
     * @throws AuthException if not admin
     */
    public void adminResetIp(String userId) throws AuthException {
        postJson("/admin/users/" + userId + "/reset-ip", null, true);
    }

    /**
     * Creates one or more invite codes.
     *
     * @param count     number of codes to generate (1-100)
     * @param expiresAt optional ISO 8601 expiry (null for no expiry)
     * @return list of generated invite codes
     * @throws AuthException if not admin
     */
    public List<String> createInvites(int count, String expiresAt) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("count", count);
        if (expiresAt != null) {
            body.addProperty("expires_at", expiresAt);
        }

        JsonObject data = postJson("/admin/invites", body, true);
        JsonArray codesArr = data.getAsJsonArray("codes");
        List<String> codes = new ArrayList<>();
        for (JsonElement el : codesArr) {
            codes.add(el.getAsString());
        }
        return codes;
    }

    /**
     * Deletes an unused invite code.
     *
     * @param code the invite code to delete
     * @throws AuthException if not admin or code not found
     */
    public void deleteInvite(String code) throws AuthException {
        deleteRequest("/admin/invites/" + code);
    }

    /**
     * Lists all files with metadata.
     *
     * @return list of file info
     * @throws AuthException if not admin
     */
    public List<FileInfo> listFiles() throws AuthException {
        JsonObject data = getJson("/admin/files", true, false);
        JsonArray filesArr = data.getAsJsonArray("files");
        List<FileInfo> files = new ArrayList<>();
        for (JsonElement el : filesArr) {
            JsonObject f = el.getAsJsonObject();
            files.add(new FileInfo(
                    f.get("id").getAsString(),
                    f.get("name").getAsString(),
                    f.has("public") && f.get("public").getAsBoolean(),
                    f.has("encrypted") && f.get("encrypted").getAsBoolean(),
                    f.has("uploaded_by") ? f.get("uploaded_by").getAsString() : null,
                    f.has("created_at") ? f.get("created_at").getAsString() : null
            ));
        }
        return files;
    }

    /**
     * Uploads a file via multipart form.
     *
     * @param file      the file to upload
     * @param name      display name (null to use the file's own name)
     * @param isPublic  whether the file is publicly accessible
     * @param encrypted whether the file should be encrypted on download
     * @return the file ID and name from the server
     * @throws AuthException on validation errors or not admin
     */
    public FileInfo uploadFile(File file, String name, boolean isPublic, boolean encrypted)
            throws AuthException {
        MultipartBody.Builder formBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.get("application/octet-stream")))
                .addFormDataPart("public", String.valueOf(isPublic))
                .addFormDataPart("encrypted", String.valueOf(encrypted));

        if (name != null) {
            formBuilder.addFormDataPart("name", name);
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + "/admin/files")
                .post(formBuilder.build());

        addNonceHeaders(reqBuilder);
        addAuthHeader(reqBuilder);

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            readResponseHeaders(response);

            if (!response.isSuccessful()) {
                throw parseError(responseBody, response.code());
            }

            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonObject data = json.getAsJsonObject("data");
            return new FileInfo(
                    data.get("id").getAsString(),
                    data.get("name").getAsString(),
                    false, false, null, null
            );
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException("NETWORK_ERROR", "File upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a file by ID.
     *
     * @param fileId UUID of the file to delete
     * @throws AuthException if not admin or file not found
     */
    public void deleteFile(String fileId) throws AuthException {
        deleteRequest("/admin/files/" + fileId);
    }

    /**
     * Uploads or replaces the loader file.
     *
     * @param file the loader binary to upload
     * @return the loader file ID
     * @throws AuthException if not admin
     */
    public String uploadLoader(File file) throws AuthException {
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.get("application/octet-stream")))
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + "/admin/loader")
                .post(body);

        addNonceHeaders(reqBuilder);
        addAuthHeader(reqBuilder);

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            readResponseHeaders(response);

            if (!response.isSuccessful()) {
                throw parseError(responseBody, response.code());
            }

            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonObject data = json.getAsJsonObject("data");
            return data.get("id").getAsString();
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException("NETWORK_ERROR", "Loader upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the paginated audit log with optional filters.
     *
     * @param page    page number (null for default)
     * @param limit   results per page (null for default, max 100)
     * @param action  filter by action type (e.g. "login", "ban_user"), or null
     * @param actorId filter by actor UUID, or null
     * @return list of audit log entries plus pagination info
     * @throws AuthException if not admin
     */
    public AuditLogResult getAuditLog(Integer page, Integer limit, String action, String actorId)
            throws AuthException {
        StringBuilder path = new StringBuilder("/admin/audit-log?");
        if (page != null) path.append("page=").append(page).append("&");
        if (limit != null) path.append("limit=").append(limit).append("&");
        if (action != null) path.append("action=").append(action).append("&");
        if (actorId != null) path.append("actor_id=").append(actorId).append("&");

        JsonObject data = getJson(path.toString(), true, false);
        JsonArray logsArr = data.getAsJsonArray("logs");
        List<AuditLogEntry> entries = new ArrayList<>();
        for (JsonElement el : logsArr) {
            JsonObject o = el.getAsJsonObject();
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = o.has("metadata") && !o.get("metadata").isJsonNull()
                    ? gson.fromJson(o.get("metadata"), Map.class)
                    : new HashMap<>();
            entries.add(new AuditLogEntry(
                    o.get("id").getAsString(),
                    o.get("action").getAsString(),
                    o.has("actor_id") && !o.get("actor_id").isJsonNull() ? o.get("actor_id").getAsString() : null,
                    o.has("target_id") && !o.get("target_id").isJsonNull() ? o.get("target_id").getAsString() : null,
                    o.has("ip") ? o.get("ip").getAsString() : null,
                    metadata,
                    o.has("created_at") ? o.get("created_at").getAsString() : null
            ));
        }

        return new AuditLogResult(
                entries,
                data.get("total").getAsInt(),
                data.get("page").getAsInt(),
                data.get("limit").getAsInt()
        );
    }

    /**
     * Simple container for paginated audit log results.
     */
    public static class AuditLogResult {
        private final List<AuditLogEntry> logs;
        private final int total;
        private final int page;
        private final int limit;

        public AuditLogResult(List<AuditLogEntry> logs, int total, int page, int limit) {
            this.logs = logs;
            this.total = total;
            this.page = page;
            this.limit = limit;
        }

        public List<AuditLogEntry> getLogs() { return logs; }
        public int getTotal() { return total; }
        public int getPage() { return page; }
        public int getLimit() { return limit; }
    }

    // ─── Webhook reporting ─────────────────────────────────────────────

    /**
     * Reports a security event to the server (e.g. crack attempt, debugger detected).
     * Heavily rate-limited server-side. Automatically attaches user info and HWID
     * if available.
     *
     * @param eventType the type of security event
     * @param metadata  optional key-value pairs with additional context (may be null)
     * @throws AuthException on request failure
     */
    public void reportEvent(WebhookEventType eventType, Map<String, String> metadata) throws AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("event_type", eventType.name());

        if (token != null) {
            try {
                UserInfo user = getCurrentUser();
                if (user.getId() != null) body.addProperty("user_id", user.getId());
                if (user.getUsername() != null) body.addProperty("username", user.getUsername());
            } catch (AuthException ignored) {
                // Not authenticated or failed — send without user info
            }
        }

        if (hwid != null && !hwid.isEmpty()) {
            body.addProperty("hwid", hwid);
        }

        if (metadata != null && !metadata.isEmpty()) {
            JsonObject meta = new JsonObject();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                meta.addProperty(entry.getKey(), entry.getValue());
            }
            body.add("metadata", meta);
        }

        postJson("/api/webhooks/report", body, false);
    }

    /**
     * Reports a security event without additional metadata.
     *
     * @param eventType the type of security event
     * @throws AuthException on request failure
     */
    public void reportEvent(WebhookEventType eventType) throws AuthException {
        reportEvent(eventType, null);
    }

    // ─── Internal HTTP helpers ────────────────────────────────────────

    /**
     * Sends a GET request and parses the "data" object from the JSON response.
     *
     * @param path          URL path (appended to baseUrl)
     * @param auth          whether to include the Authorization header
     * @param softwareType  whether to include X-Client-Type: software
     */
    private JsonObject getJson(String path, boolean auth, boolean softwareType) throws AuthException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + path)
                .get();

        addNonceHeaders(reqBuilder);
        if (auth) addAuthHeader(reqBuilder);
        if (softwareType) {
            reqBuilder.header("X-Client-Type", "software");
            if (hwid != null) reqBuilder.header("X-HWID", hwid);
        }

        return executeAndParse(reqBuilder.build());
    }

    /**
     * Sends a POST request with a JSON body and parses the "data" object.
     *
     * @param path URL path
     * @param body JSON body (null for empty body)
     * @param auth whether to include the Authorization header
     * @return the "data" JsonObject from the response (may be null for message-only)
     */
    private JsonObject postJson(String path, JsonObject body, boolean auth) throws AuthException {
        RequestBody requestBody;
        if (body != null) {
            requestBody = RequestBody.create(gson.toJson(body), JSON_MEDIA);
        } else {
            requestBody = RequestBody.create("", JSON_MEDIA);
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + path)
                .post(requestBody);

        addNonceHeaders(reqBuilder);
        if (auth) addAuthHeader(reqBuilder);

        return executeAndParse(reqBuilder.build());
    }

    /** Sends a DELETE request. */
    private void deleteRequest(String path) throws AuthException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + path)
                .delete();

        addNonceHeaders(reqBuilder);
        addAuthHeader(reqBuilder);

        executeAndParse(reqBuilder.build());
    }

    /**
     * Downloads binary data, automatically decrypting if X-Encrypted header is present.
     *
     * @param path   URL path
     * @param fileId used as HKDF salt for decryption
     */
    private byte[] downloadBinary(String path, String fileId) throws AuthException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + path)
                .get();

        addNonceHeaders(reqBuilder);
        addAuthHeader(reqBuilder);
        reqBuilder.header("X-Client-Type", "software");
        if (hwid != null) reqBuilder.header("X-HWID", hwid);

        try {
            Response response = httpClient.newCall(reqBuilder.build()).execute();
            readResponseHeaders(response);

            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                response.close();
                throw parseError(responseBody, response.code());
            }

            byte[] data = response.body() != null ? response.body().bytes() : new byte[0];
            response.close();

            // Check if the response is encrypted
            String encrypted = response.header("X-Encrypted");
            if ("true".equalsIgnoreCase(encrypted)) {
                if (token == null) {
                    throw new AuthException("DECRYPT_ERROR", "Cannot decrypt: no session token set");
                }
                if (fileId == null) {
                    throw new AuthException("DECRYPT_ERROR",
                            "Response marked encrypted but this endpoint has no file ID to salt with");
                }

                // HKDF key derivation:
                // IKM = session token as raw hex-decoded bytes
                // salt = file_id as UTF-8 bytes
                // info = "file-download" as UTF-8 bytes
                byte[] ikm = CryptoUtil.fromHex(token);
                byte[] salt = fileId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] info = "file-download".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] key = CryptoUtil.hkdfSha256(ikm, salt, info, 32);

                return CryptoUtil.aesGcmDecrypt(data, key);
            }

            return data;
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException("NETWORK_ERROR", "Download failed: " + e.getMessage(), e);
        }
    }

    /** Executes a request and parses the standard JSON response envelope. */
    private JsonObject executeAndParse(Request request) throws AuthException {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            readResponseHeaders(response);

            if (!response.isSuccessful()) {
                throw parseError(responseBody, response.code());
            }

            if (responseBody.isEmpty()) {
                return null;
            }

            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonElement dataEl = json.get("data");
            if (dataEl == null || dataEl.isJsonNull()) {
                return null;
            }
            return dataEl.getAsJsonObject();
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException("NETWORK_ERROR", "Request failed: " + e.getMessage(), e);
        }
    }

    /** Adds X-Request-Nonce and X-Request-Timestamp headers. */
    private void addNonceHeaders(Request.Builder builder) {
        builder.header("X-Request-Nonce", CryptoUtil.generateNonce());
        builder.header("X-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
    }

    /** Adds the Authorization: Bearer header if a token is set. */
    private void addAuthHeader(Request.Builder builder) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    /** Reads X-Response-Nonce and X-Response-Timestamp from the response. */
    private void readResponseHeaders(Response response) {
        String nonce = response.header("X-Response-Nonce");
        String timestampStr = response.header("X-Response-Timestamp");
        long timestamp = 0;
        if (timestampStr != null) {
            try {
                timestamp = Long.parseLong(timestampStr);
            } catch (NumberFormatException ignored) {}
        }
        this.lastResponse = new LastResponse(nonce != null ? nonce : "", timestamp);
    }

    /** Parses an error response body into an AuthException. */
    private AuthException parseError(String responseBody, int httpCode) {
        try {
            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            String code = json.has("code") ? json.get("code").getAsString() : "HTTP_" + httpCode;
            String message = json.has("error") ? json.get("error").getAsString() : "HTTP " + httpCode;
            return new AuthException(code, message);
        } catch (Exception e) {
            return new AuthException("HTTP_" + httpCode, "HTTP " + httpCode + ": " + responseBody);
        }
    }

    /** Parses a user JSON object into UserInfo. */
    private UserInfo parseUserInfo(JsonObject obj) {
        return parseUserInfoWithSessionCount(obj, 0);
    }

    /** Parses a user JSON object into UserInfo with a session count. */
    private UserInfo parseUserInfoWithSessionCount(JsonObject obj, int sessionCount) {
        return new UserInfo(
                obj.has("id") ? obj.get("id").getAsString() : null,
                obj.has("uid") && !obj.get("uid").isJsonNull() ? obj.get("uid").getAsLong() : -1L,
                obj.has("username") ? obj.get("username").getAsString() : null,
                obj.has("email") ? obj.get("email").getAsString() : null,
                obj.has("email_verified") && obj.get("email_verified").getAsBoolean(),
                obj.has("is_admin") && obj.get("is_admin").getAsBoolean(),
                obj.has("banned") && obj.get("banned").getAsBoolean(),
                obj.has("created_at") ? obj.get("created_at").getAsString() : null,
                sessionCount
        );
    }
}
