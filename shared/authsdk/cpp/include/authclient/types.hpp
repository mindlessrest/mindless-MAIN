#pragma once

#include <cstdint>
#include <exception>
#include <string>
#include <vector>

namespace authclient {

// ---------------------------------------------------------------------------
// Exception type for all API and crypto errors
// ---------------------------------------------------------------------------

/// Thrown on API errors (non-2xx responses) and local failures (decryption,
/// IPC, etc.).  Carries the machine-readable `code` from the server's JSON
/// error body, plus the human-readable `message`.
class AuthException : public std::exception {
public:
    AuthException(std::string code, std::string message)
        : code_(std::move(code)),
          message_(std::move(message)),
          what_(code_ + ": " + message_) {}

    const char* what() const noexcept override { return what_.c_str(); }

    /// Machine-readable error code, e.g. "INVALID_CREDENTIALS", "BANNED".
    const std::string& code() const { return code_; }

    /// Human-readable description of the error.
    const std::string& message() const { return message_; }

private:
    std::string code_;
    std::string message_;
    std::string what_;
};

// ---------------------------------------------------------------------------
// Response structs — one per distinct response shape
// ---------------------------------------------------------------------------

/// Returned by POST /auth/login.
struct LoginResult {
    std::string token;       ///< 64-char hex session token.
    std::string expires_at;  ///< ISO-8601 expiry timestamp.
};

/// Returned by GET /software/session-validate.
struct SessionInfo {
    bool        valid;
    std::string username;
    std::string expires_at;
};

/// Returned by GET /api/public/config.
struct PublicConfig {
    bool        hwid_locking;
    bool        ip_locking;
    bool        invite_only;
    std::string app_name;
};

/// Returned by GET /software/ping.
struct PingResult {
    bool        ok;
    std::string server_time;  ///< ISO-8601 string as returned by the server.
};

/// Stores the nonce and timestamp from the most recent server response so
/// callers can verify the response came from the real server.
struct LastResponse {
    std::string nonce;      ///< X-Response-Nonce header value (64 hex chars).
    int64_t     timestamp;  ///< X-Response-Timestamp header value (unix secs).
};

/// Returned by GET /auth/me.
struct UserInfo {
    std::string id;
    std::string username;
    std::string email;
    bool        email_verified;
    bool        is_admin;
    std::string created_at;
};

/// A single entry in the admin audit log.
struct AuditLogEntry {
    std::string id;
    std::string action;
    std::string actor_id;
    std::string target_id;
    std::string ip;
    std::string metadata_json;  ///< Raw JSON string of the metadata object.
    std::string created_at;
};

/// Paginated result from GET /admin/audit-log.
struct AuditLogResult {
    std::vector<AuditLogEntry> logs;
    int total;
    int page;
    int limit;
};

/// Metadata for one uploaded file.
struct FileInfo {
    std::string id;
    std::string name;
    bool        is_public;
    bool        encrypted;
    std::string uploaded_by;
    std::string created_at;
};

/// Result from GET /admin/files.
struct FileListResult {
    std::vector<FileInfo> files;
};

/// Admin user record (slightly more detail than UserInfo).
struct AdminUserInfo {
    std::string id;
    std::string username;
    std::string email;
    bool        email_verified;
    bool        is_admin;
    bool        banned;
    std::string created_at;
};

/// Result from GET /admin/users.
struct UserListResult {
    std::vector<AdminUserInfo> users;
    int total;
    int page;
    int limit;
};

/// Result from GET /admin/users/:id.
struct AdminUserDetail {
    AdminUserInfo user;
    int           session_count;
};

/// Result from POST /admin/invites.
struct InviteResult {
    std::vector<std::string> codes;
};

/// Result from POST /admin/files (upload) or POST /admin/loader.
struct UploadResult {
    std::string id;
    std::string name;  ///< May be empty for loader uploads.
};

/// Webhook event types for reporting security events.
enum class WebhookEventType {
    CRACK_ATTEMPT,
    INTEGRITY_VIOLATION,
    DEBUG_DETECTED,
    INJECTION_DETECTED,
    TAMPER_DETECTED,
    SUSPICIOUS_ACTIVITY
};

/// Converts a WebhookEventType enum to its string representation.
inline std::string webhookEventTypeToString(WebhookEventType type) {
    switch (type) {
        case WebhookEventType::CRACK_ATTEMPT:       return "CRACK_ATTEMPT";
        case WebhookEventType::INTEGRITY_VIOLATION:  return "INTEGRITY_VIOLATION";
        case WebhookEventType::DEBUG_DETECTED:       return "DEBUG_DETECTED";
        case WebhookEventType::INJECTION_DETECTED:   return "INJECTION_DETECTED";
        case WebhookEventType::TAMPER_DETECTED:      return "TAMPER_DETECTED";
        case WebhookEventType::SUSPICIOUS_ACTIVITY:  return "SUSPICIOUS_ACTIVITY";
        default:                                      return "UNKNOWN";
    }
}

/// Configuration for the session heartbeat loop.
struct HeartbeatConfig {
    int interval_seconds    = 60;  ///< How often to ping (seconds).
    int max_failures        = 3;   ///< Consecutive failures before giving up.
    int retry_delay_seconds = 10;  ///< Delay between retries on failure.
};

}  // namespace authclient
