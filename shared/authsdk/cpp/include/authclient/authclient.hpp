#pragma once

#include <memory>
#include <string>
#include <vector>

#include "authclient/types.hpp"

namespace authclient {

/// Full API client for the AuthSys backend.
///
/// Covers every endpoint: authentication, account management, software
/// (session validate, file downloads, ping), and admin operations.
///
/// Uses the pimpl idiom so callers never need to include libcurl headers.
///
/// Thread safety: a single AuthClient instance is NOT thread-safe.  If you
/// need concurrent requests, create one client per thread or serialise access.
///
/// Usage:
/// @code
///   authclient::AuthClient client("https://api.example.com");
///   auto login = client.login("myuser", "P@ssw0rd!");
///   auto data  = client.downloadFile("some-uuid");
/// @endcode
class AuthClient {
public:
    /// @param base_url  Root URL of the AuthSys server, no trailing slash.
    explicit AuthClient(const std::string& base_url);
    ~AuthClient();

    AuthClient(const AuthClient&) = delete;
    AuthClient& operator=(const AuthClient&) = delete;

    // -- Authentication ------------------------------------------------------

    /// POST /auth/login — authenticate and receive a session token.
    LoginResult login(const std::string& login, const std::string& password);

    /// POST /auth/logout — revoke the current session.
    void logout();

    /// POST /auth/register — create a new account.
    /// @param invite_code  Pass empty string if invite-only is disabled.
    void registerAccount(const std::string& username,
                         const std::string& email,
                         const std::string& password,
                         const std::string& invite_code = "");

    /// POST /auth/verify-email — confirm an email verification token.
    void verifyEmail(const std::string& token);

    /// POST /auth/forgot-username — request username reminder by email.
    void forgotUsername(const std::string& email);

    /// POST /auth/forgot-password — request password reset link by email.
    void forgotPassword(const std::string& email);

    /// POST /auth/reset-password — set a new password using a reset token.
    void resetPassword(const std::string& token,
                       const std::string& new_password);

    // -- Account management (requires auth) ----------------------------------

    /// POST /auth/change-password
    void changePassword(const std::string& current_password,
                        const std::string& new_password);

    /// POST /auth/change-email
    void changeEmail(const std::string& new_email,
                     const std::string& password);

    /// POST /auth/reset-hwid — reset own HWID binding.
    void resetHWID(const std::string& password);

    /// POST /auth/reset-ip — reset own IP binding.
    void resetIP(const std::string& password);

    /// GET /auth/me — info about the current user.
    UserInfo getCurrentUser();

    // -- Software endpoints --------------------------------------------------

    /// GET /software/session-validate — confirm the session is still live.
    SessionInfo validateSession();

    /// GET /software/download/:file_id — download (and auto-decrypt if the
    /// server indicates encryption).
    std::vector<uint8_t> downloadFile(const std::string& file_id);

    /// GET /software/loader — download the loader binary.
    std::vector<uint8_t> downloadLoader();

    /// GET /software/ping — health check / clock sync.  No auth required.
    PingResult ping();

    // -- Public config (no auth, no nonce) -----------------------------------

    /// GET /api/public/config
    PublicConfig getPublicConfig();

    // -- Admin endpoints (require admin auth) --------------------------------

    /// GET /admin/users — paginated user list.
    UserListResult listUsers(int page = 1, int limit = 20,
                             const std::string& search = "");

    /// GET /admin/users/:id — full user detail.
    AdminUserDetail getUser(const std::string& user_id);

    /// POST /admin/users/:id/ban
    void banUser(const std::string& user_id, const std::string& reason);

    /// POST /admin/users/:id/unban
    void unbanUser(const std::string& user_id);

    /// POST /admin/users/:id/reset-hwid
    void adminResetHwid(const std::string& user_id);

    /// POST /admin/users/:id/reset-ip
    void adminResetIp(const std::string& user_id);

    /// POST /admin/invites — generate invite codes.
    InviteResult createInvites(int count = 1,
                               const std::string& expires_at = "");

    /// DELETE /admin/invites/:code
    void deleteInvite(const std::string& code);

    /// GET /admin/files — list all uploaded files.
    FileListResult listFiles();

    /// POST /admin/files — upload a file.  `file_path` is a local filesystem
    /// path.
    UploadResult uploadFile(const std::string& file_path,
                            const std::string& display_name = "",
                            bool is_public = false,
                            bool encrypted = true);

    /// DELETE /admin/files/:id
    void deleteFile(const std::string& file_id);

    /// POST /admin/loader — upload or replace the loader binary.
    UploadResult uploadLoader(const std::string& file_path);

    /// GET /admin/audit-log — paginated audit log.
    AuditLogResult getAuditLog(int page = 1, int limit = 20,
                               const std::string& action = "",
                               const std::string& actor_id = "");

    // -- Webhook reporting ---------------------------------------------------

    /// POST /api/webhooks/report — report a security event from the client.
    /// @param event_type  One of the WebhookEventType enum values.
    /// @param metadata    Optional key-value pairs with additional context.
    void reportEvent(WebhookEventType event_type,
                     const std::vector<std::pair<std::string, std::string>>& metadata = {});

    // -- Token management ----------------------------------------------------

    /// Manually set the Bearer token (e.g. after receiving it via IPC).
    void setToken(const std::string& token);

    /// Returns the current Bearer token, or empty string if none.
    std::string getToken() const;

    /// Clears the stored token.
    void clearToken();

    /// Returns the nonce and timestamp from the most recent server response.
    LastResponse getLastResponse() const;

    /// Set the HWID to send in X-HWID headers on software endpoints.
    void setHWID(const std::string& hwid);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace authclient
