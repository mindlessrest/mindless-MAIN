#include "authclient/authclient.hpp"
#include "authclient/crypto.hpp"
#include "authclient/hwid.hpp"

#include <chrono>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <sstream>

using json = nlohmann::json;

namespace authclient {

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

static const long CONNECT_TIMEOUT_SECS = 10;
static const long REQUEST_TIMEOUT_SECS = 30;

// ---------------------------------------------------------------------------
// RAII wrapper for a curl easy handle
// ---------------------------------------------------------------------------

struct CurlHandle {
    CURL* handle;
    CurlHandle() : handle(curl_easy_init()) {
        if (!handle) throw AuthException("CURL_ERROR", "curl_easy_init failed");
    }
    ~CurlHandle() { if (handle) curl_easy_cleanup(handle); }
    CurlHandle(const CurlHandle&) = delete;
    CurlHandle& operator=(const CurlHandle&) = delete;
};

// ---------------------------------------------------------------------------
// RAII wrapper for curl_slist (header list)
// ---------------------------------------------------------------------------

struct CurlHeaders {
    curl_slist* list = nullptr;
    CurlHeaders() = default;
    void append(const std::string& header) {
        list = curl_slist_append(list, header.c_str());
    }
    ~CurlHeaders() { if (list) curl_slist_free_all(list); }
    CurlHeaders(const CurlHeaders&) = delete;
    CurlHeaders& operator=(const CurlHeaders&) = delete;
};

// ---------------------------------------------------------------------------
// Impl (pimpl body)
// ---------------------------------------------------------------------------

struct AuthClient::Impl {
    std::string base_url;
    std::string token;
    std::string hwid;
    LastResponse last_response{};
    bool last_response_encrypted = false;  // set by header callback when X-Encrypted: true

    Impl(const std::string& url) : base_url(url) {}

    // -- Low-level HTTP helpers -----------------------------------------------

    /// Curl write callback — appends data to a std::string.
    static size_t writeCallback(void* data, size_t size, size_t nmemb, void* userp) {
        size_t total = size * nmemb;
        static_cast<std::string*>(userp)->append(static_cast<char*>(data), total);
        return total;
    }

    /// Curl header callback — captures X-Response-Nonce and X-Response-Timestamp.
    static size_t headerCallback(char* buffer, size_t size, size_t nitems, void* userp) {
        size_t total = size * nitems;
        std::string line(buffer, total);
        auto* impl = static_cast<Impl*>(userp);

        // Headers arrive as "Key: Value\r\n"
        auto colon = line.find(':');
        if (colon == std::string::npos) return total;

        std::string key = line.substr(0, colon);
        std::string val = line.substr(colon + 1);
        // Trim
        val.erase(0, val.find_first_not_of(" \t"));
        val.erase(val.find_last_not_of(" \t\r\n") + 1);

        // Case-insensitive comparison for header names
        for (auto& c : key) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

        if (key == "x-response-nonce") {
            impl->last_response.nonce = val;
        } else if (key == "x-response-timestamp") {
            try { impl->last_response.timestamp = std::stoll(val); } catch (...) {}
        } else if (key == "x-encrypted") {
            impl->last_response_encrypted = (val == "true");
        }
        return total;
    }

    /// Generates the nonce and timestamp, returns a pair.
    std::pair<std::string, std::string> generateNonce() {
        auto nonce_bytes = crypto::randomBytes(32);
        std::string nonce = crypto::toHex(nonce_bytes);

        auto now = std::chrono::system_clock::now();
        auto epoch = std::chrono::duration_cast<std::chrono::seconds>(
                         now.time_since_epoch()).count();

        return {nonce, std::to_string(epoch)};
    }

    /// Perform an HTTP request and return (status_code, response_body).
    /// The `body` parameter is the request body (empty for GET/DELETE).
    /// The `method` is "GET", "POST", or "DELETE".
    /// `needs_auth`, `needs_nonce`, `is_software` control which headers are sent.
    /// `content_type` defaults to "application/json" for POST with body.
    struct RequestOpts {
        std::string method = "GET";
        std::string url;
        std::string body;
        bool needs_auth    = false;
        bool needs_nonce   = true;
        bool is_software   = false;
        bool is_multipart  = false;
        long timeout_secs  = REQUEST_TIMEOUT_SECS;
        std::string file_path;       // for multipart upload
        std::string form_name;       // form field name for file
        std::vector<std::pair<std::string, std::string>> form_fields;
    };

    struct Response {
        long status;
        std::string body;
    };

    Response request(const RequestOpts& opts) {
        CurlHandle curl;
        CurlHeaders headers;
        std::string response_body;

        // Reset per-request header state before the new request
        last_response_encrypted = false;

        curl_easy_setopt(curl.handle, CURLOPT_URL, opts.url.c_str());
        curl_easy_setopt(curl.handle, CURLOPT_CONNECTTIMEOUT, CONNECT_TIMEOUT_SECS);
        curl_easy_setopt(curl.handle, CURLOPT_TIMEOUT, opts.timeout_secs);
        curl_easy_setopt(curl.handle, CURLOPT_WRITEFUNCTION, writeCallback);
        curl_easy_setopt(curl.handle, CURLOPT_WRITEDATA, &response_body);
        curl_easy_setopt(curl.handle, CURLOPT_HEADERFUNCTION, headerCallback);
        curl_easy_setopt(curl.handle, CURLOPT_HEADERDATA, this);

        if (opts.method == "POST") {
            curl_easy_setopt(curl.handle, CURLOPT_POST, 1L);
            if (!opts.is_multipart && !opts.body.empty()) {
                curl_easy_setopt(curl.handle, CURLOPT_POSTFIELDS, opts.body.c_str());
                curl_easy_setopt(curl.handle, CURLOPT_POSTFIELDSIZE,
                                 static_cast<long>(opts.body.size()));
            }
        } else if (opts.method == "DELETE") {
            curl_easy_setopt(curl.handle, CURLOPT_CUSTOMREQUEST, "DELETE");
        }

        // Anti-replay headers
        if (opts.needs_nonce) {
            auto [nonce, timestamp] = generateNonce();
            headers.append("X-Request-Nonce: " + nonce);
            headers.append("X-Request-Timestamp: " + timestamp);
        }

        // Auth header
        if (opts.needs_auth && !token.empty()) {
            headers.append("Authorization: Bearer " + token);
        }

        // Software client type
        if (opts.is_software) {
            headers.append("X-Client-Type: software");
            if (!hwid.empty()) {
                headers.append("X-HWID: " + hwid);
            }
        }

        // Content type for JSON POST
        if (opts.method == "POST" && !opts.is_multipart && !opts.body.empty()) {
            headers.append("Content-Type: application/json");
        }

        // Multipart form upload
        curl_mime* mime = nullptr;
        if (opts.is_multipart) {
            mime = curl_mime_init(curl.handle);

            // Add the file part
            if (!opts.file_path.empty()) {
                curl_mimepart* part = curl_mime_addpart(mime);
                curl_mime_name(part, opts.form_name.empty() ? "file" : opts.form_name.c_str());
                curl_mime_filedata(part, opts.file_path.c_str());
            }

            // Add extra string form fields
            for (auto& [name, value] : opts.form_fields) {
                curl_mimepart* part = curl_mime_addpart(mime);
                curl_mime_name(part, name.c_str());
                curl_mime_data(part, value.c_str(), CURL_ZERO_TERMINATED);
            }

            curl_easy_setopt(curl.handle, CURLOPT_MIMEPOST, mime);
        }

        curl_easy_setopt(curl.handle, CURLOPT_HTTPHEADER, headers.list);

        CURLcode res = curl_easy_perform(curl.handle);
        if (mime) curl_mime_free(mime);

        if (res != CURLE_OK) {
            throw AuthException("NETWORK_ERROR", curl_easy_strerror(res));
        }

        long status = 0;
        curl_easy_getinfo(curl.handle, CURLINFO_RESPONSE_CODE, &status);

        return {status, response_body};
    }

    /// Parse a successful JSON response and return the "data" field.
    /// Throws AuthException for error responses (non-2xx).
    json parseResponse(const Response& resp) {
        if (resp.status >= 200 && resp.status < 300) {
            if (resp.body.empty()) return json(nullptr);
            auto j = json::parse(resp.body, nullptr, false);
            if (j.is_discarded()) return json(nullptr);
            if (j.contains("data")) return j["data"];
            return json(nullptr);
        }

        // Error response
        std::string code = "UNKNOWN";
        std::string message = "HTTP " + std::to_string(resp.status);

        auto j = json::parse(resp.body, nullptr, false);
        if (!j.is_discarded()) {
            if (j.contains("code")) code = j["code"].get<std::string>();
            if (j.contains("error")) message = j["error"].get<std::string>();
        }
        throw AuthException(code, message);
    }

    /// Shorthand for a JSON POST request to an auth endpoint.
    json authPost(const std::string& path, const json& body,
                  bool needs_auth = false) {
        RequestOpts opts;
        opts.method = "POST";
        opts.url = base_url + path;
        opts.body = body.dump();
        opts.needs_auth = needs_auth;
        opts.needs_nonce = true;
        return parseResponse(request(opts));
    }

    /// Shorthand for a GET request to an auth endpoint.
    json authGet(const std::string& path, bool needs_auth = true) {
        RequestOpts opts;
        opts.method = "GET";
        opts.url = base_url + path;
        opts.needs_auth = needs_auth;
        opts.needs_nonce = true;
        return parseResponse(request(opts));
    }

    /// Shorthand for a GET to a software endpoint.
    json softwareGet(const std::string& path, bool needs_auth = true) {
        RequestOpts opts;
        opts.method = "GET";
        opts.url = base_url + path;
        opts.needs_auth = needs_auth;
        opts.needs_nonce = true;
        opts.is_software = true;
        return parseResponse(request(opts));
    }

    /// Raw GET for binary downloads (files, loader). Returns the raw response.
    Response softwareGetRaw(const std::string& path) {
        RequestOpts opts;
        opts.method = "GET";
        opts.url = base_url + path;
        opts.needs_auth = true;
        opts.needs_nonce = true;
        opts.is_software = true;
        opts.timeout_secs = 180;
        return request(opts);
    }

    /// Shorthand for admin POST.
    json adminPost(const std::string& path, const json& body = json::object()) {
        RequestOpts opts;
        opts.method = "POST";
        opts.url = base_url + path;
        if (!body.empty() && !body.is_null()) opts.body = body.dump();
        opts.needs_auth = true;
        opts.needs_nonce = true;
        return parseResponse(request(opts));
    }

    /// Shorthand for admin GET.
    json adminGet(const std::string& path) {
        RequestOpts opts;
        opts.method = "GET";
        opts.url = base_url + path;
        opts.needs_auth = true;
        opts.needs_nonce = true;
        return parseResponse(request(opts));
    }

    /// Shorthand for admin DELETE.
    json adminDelete(const std::string& path) {
        RequestOpts opts;
        opts.method = "DELETE";
        opts.url = base_url + path;
        opts.needs_auth = true;
        opts.needs_nonce = true;
        return parseResponse(request(opts));
    }

    /// Admin multipart upload.
    json adminUpload(const std::string& path, const std::string& file_path,
                     const std::vector<std::pair<std::string, std::string>>& fields = {}) {
        RequestOpts opts;
        opts.method = "POST";
        opts.url = base_url + path;
        opts.needs_auth = true;
        opts.needs_nonce = true;
        opts.is_multipart = true;
        opts.file_path = file_path;
        opts.form_name = "file";
        opts.form_fields = fields;
        return parseResponse(request(opts));
    }
};

// ---------------------------------------------------------------------------
// AuthClient public interface
// ---------------------------------------------------------------------------

AuthClient::AuthClient(const std::string& base_url)
    : impl_(std::make_unique<Impl>(base_url)) {}

AuthClient::~AuthClient() = default;

// -- Auth endpoints ----------------------------------------------------------

LoginResult AuthClient::login(const std::string& login,
                               const std::string& password) {
    auto data = impl_->authPost("/auth/login",
                                {{"login", login}, {"password", password}});
    return LoginResult{data["token"].get<std::string>(),
                       data["expires_at"].get<std::string>()};
}

void AuthClient::logout() {
    impl_->authPost("/auth/logout", json::object(), true);
    impl_->token.clear();
}

void AuthClient::registerAccount(const std::string& username,
                                  const std::string& email,
                                  const std::string& password,
                                  const std::string& invite_code) {
    json body = {{"username", username}, {"email", email}, {"password", password}};
    if (!invite_code.empty()) body["invite_code"] = invite_code;
    impl_->authPost("/auth/register", body);
}

void AuthClient::verifyEmail(const std::string& token) {
    impl_->authPost("/auth/verify-email", {{"token", token}});
}

void AuthClient::forgotUsername(const std::string& email) {
    impl_->authPost("/auth/forgot-username", {{"email", email}});
}

void AuthClient::forgotPassword(const std::string& email) {
    impl_->authPost("/auth/forgot-password", {{"email", email}});
}

void AuthClient::resetPassword(const std::string& token,
                                const std::string& new_password) {
    impl_->authPost("/auth/reset-password",
                    {{"token", token}, {"new_password", new_password}});
}

void AuthClient::changePassword(const std::string& current_password,
                                 const std::string& new_password) {
    impl_->authPost("/auth/change-password",
                    {{"current_password", current_password},
                     {"new_password", new_password}}, true);
}

void AuthClient::changeEmail(const std::string& new_email,
                              const std::string& password) {
    impl_->authPost("/auth/change-email",
                    {{"new_email", new_email}, {"password", password}}, true);
}

void AuthClient::resetHWID(const std::string& password) {
    impl_->authPost("/auth/reset-hwid", {{"password", password}}, true);
}

void AuthClient::resetIP(const std::string& password) {
    impl_->authPost("/auth/reset-ip", {{"password", password}}, true);
}

UserInfo AuthClient::getCurrentUser() {
    auto data = impl_->authGet("/auth/me", true);
    return UserInfo{
        data["id"].get<std::string>(),
        data["username"].get<std::string>(),
        data["email"].get<std::string>(),
        data["email_verified"].get<bool>(),
        data["is_admin"].get<bool>(),
        data["created_at"].get<std::string>()
    };
}

// -- Software endpoints ------------------------------------------------------

SessionInfo AuthClient::validateSession() {
    auto data = impl_->softwareGet("/software/session-validate");
    return SessionInfo{
        data["valid"].get<bool>(),
        data["username"].get<std::string>(),
        data["expires_at"].get<std::string>()
    };
}

PingResult AuthClient::ping() {
    auto data = impl_->softwareGet("/software/ping", false);
    return PingResult{
        data["ok"].get<bool>(),
        data["server_time"].get<std::string>()
    };
}

std::vector<uint8_t> AuthClient::downloadFile(const std::string& file_id) {
    auto resp = impl_->softwareGetRaw("/software/download/" + file_id);

    if (resp.status < 200 || resp.status >= 300) {
        impl_->parseResponse(resp);  // will throw
    }

    auto body_bytes = std::vector<uint8_t>(resp.body.begin(), resp.body.end());

    // Only decrypt when the server explicitly says the response is encrypted.
    // The header callback sets last_response_encrypted from X-Encrypted: true.
    // If decryption fails (GCM tag mismatch), propagate the error — don't
    // silently return garbage ciphertext bytes.
    if (impl_->last_response_encrypted) {
        auto ikm  = crypto::fromHex(impl_->token);
        auto salt = std::vector<uint8_t>(file_id.begin(), file_id.end());
        auto info = std::vector<uint8_t>{'f','i','l','e','-','d','o','w','n','l','o','a','d'};
        auto key  = crypto::hkdfSha256(ikm, salt, info, 32);
        return crypto::aesGcmDecrypt(body_bytes, key);
    }

    return body_bytes;
}

std::vector<uint8_t> AuthClient::downloadLoader() {
    auto resp = impl_->softwareGetRaw("/software/loader");

    if (resp.status < 200 || resp.status >= 300) {
        impl_->parseResponse(resp);  // will throw
    }

    auto body_bytes = std::vector<uint8_t>(resp.body.begin(), resp.body.end());

    // The loader is never encrypted: the key would come from the caller's session
    // token, and the loader is what people fetch before they have one. The old
    // "loader" salt here could never have matched the server either -- it salts
    // with the file's UUID. Fail loudly rather than hand back ciphertext as an exe.
    if (impl_->last_response_encrypted) {
        throw AuthException("DECRYPTION_FAILED",
                            "loader response was marked encrypted; the loader is never encrypted");
    }

    return body_bytes;
}

// -- Public config (no auth, no nonce) ----------------------------------------

PublicConfig AuthClient::getPublicConfig() {
    Impl::RequestOpts opts;
    opts.method = "GET";
    opts.url = impl_->base_url + "/api/public/config";
    opts.needs_auth = false;
    opts.needs_nonce = false;

    auto data = impl_->parseResponse(impl_->request(opts));
    return PublicConfig{
        data["hwid_locking"].get<bool>(),
        data["ip_locking"].get<bool>(),
        data["invite_only"].get<bool>(),
        data["app_name"].get<std::string>()
    };
}

// -- Admin endpoints ---------------------------------------------------------

UserListResult AuthClient::listUsers(int page, int limit,
                                      const std::string& search) {
    std::string path = "/admin/users?page=" + std::to_string(page)
                     + "&limit=" + std::to_string(limit);
    if (!search.empty()) path += "&search=" + search;

    auto data = impl_->adminGet(path);
    UserListResult result;
    result.total = data["total"].get<int>();
    result.page  = data["page"].get<int>();
    result.limit = data["limit"].get<int>();

    for (auto& u : data["users"]) {
        result.users.push_back(AdminUserInfo{
            u["id"].get<std::string>(),
            u["username"].get<std::string>(),
            u["email"].get<std::string>(),
            u.value("email_verified", false),
            u.value("is_admin", false),
            u.value("banned", false),
            u.value("created_at", "")
        });
    }
    return result;
}

AdminUserDetail AuthClient::getUser(const std::string& user_id) {
    auto data = impl_->adminGet("/admin/users/" + user_id);
    auto& u = data["user"];
    AdminUserDetail detail;
    detail.user = AdminUserInfo{
        u["id"].get<std::string>(),
        u["username"].get<std::string>(),
        u.value("email", ""),
        u.value("email_verified", false),
        u.value("is_admin", false),
        u.value("banned", false),
        u.value("created_at", "")
    };
    detail.session_count = data["session_count"].get<int>();
    return detail;
}

void AuthClient::banUser(const std::string& user_id, const std::string& reason) {
    impl_->adminPost("/admin/users/" + user_id + "/ban", {{"reason", reason}});
}

void AuthClient::unbanUser(const std::string& user_id) {
    impl_->adminPost("/admin/users/" + user_id + "/unban");
}

void AuthClient::adminResetHwid(const std::string& user_id) {
    impl_->adminPost("/admin/users/" + user_id + "/reset-hwid");
}

void AuthClient::adminResetIp(const std::string& user_id) {
    impl_->adminPost("/admin/users/" + user_id + "/reset-ip");
}

InviteResult AuthClient::createInvites(int count,
                                        const std::string& expires_at) {
    json body = {{"count", count}};
    if (!expires_at.empty()) body["expires_at"] = expires_at;
    auto data = impl_->adminPost("/admin/invites", body);
    InviteResult result;
    for (auto& c : data["codes"]) {
        result.codes.push_back(c.get<std::string>());
    }
    return result;
}

void AuthClient::deleteInvite(const std::string& code) {
    impl_->adminDelete("/admin/invites/" + code);
}

FileListResult AuthClient::listFiles() {
    auto data = impl_->adminGet("/admin/files");
    FileListResult result;
    for (auto& f : data["files"]) {
        result.files.push_back(FileInfo{
            f["id"].get<std::string>(),
            f["name"].get<std::string>(),
            f.value("public", false),
            f.value("encrypted", false),
            f.value("uploaded_by", ""),
            f.value("created_at", "")
        });
    }
    return result;
}

UploadResult AuthClient::uploadFile(const std::string& file_path,
                                     const std::string& display_name,
                                     bool is_public, bool encrypted) {
    std::vector<std::pair<std::string, std::string>> fields;
    if (!display_name.empty()) fields.emplace_back("name", display_name);
    fields.emplace_back("public", is_public ? "true" : "false");
    fields.emplace_back("encrypted", encrypted ? "true" : "false");

    auto data = impl_->adminUpload("/admin/files", file_path, fields);
    return UploadResult{data["id"].get<std::string>(),
                        data.value("name", "")};
}

void AuthClient::deleteFile(const std::string& file_id) {
    impl_->adminDelete("/admin/files/" + file_id);
}

UploadResult AuthClient::uploadLoader(const std::string& file_path) {
    auto data = impl_->adminUpload("/admin/loader", file_path);
    return UploadResult{data["id"].get<std::string>(), ""};
}

AuditLogResult AuthClient::getAuditLog(int page, int limit,
                                         const std::string& action,
                                         const std::string& actor_id) {
    std::string path = "/admin/audit-log?page=" + std::to_string(page)
                     + "&limit=" + std::to_string(limit);
    if (!action.empty())   path += "&action=" + action;
    if (!actor_id.empty()) path += "&actor_id=" + actor_id;

    auto data = impl_->adminGet(path);
    AuditLogResult result;
    result.total = data["total"].get<int>();
    result.page  = data["page"].get<int>();
    result.limit = data["limit"].get<int>();

    for (auto& l : data["logs"]) {
        result.logs.push_back(AuditLogEntry{
            l["id"].get<std::string>(),
            l["action"].get<std::string>(),
            l.value("actor_id", ""),
            l.value("target_id", ""),
            l.value("ip", ""),
            l.contains("metadata") ? l["metadata"].dump() : "{}",
            l.value("created_at", "")
        });
    }
    return result;
}

// -- Webhook reporting -------------------------------------------------------

void AuthClient::reportEvent(WebhookEventType event_type,
                             const std::vector<std::pair<std::string, std::string>>& metadata) {
    json body;
    body["event_type"] = webhookEventTypeToString(event_type);

    if (!impl_->token.empty()) {
        // Try to get user info to attach to the report
        try {
            auto user = getCurrentUser();
            body["user_id"] = user.id;
            body["username"] = user.username;
        } catch (...) {
            // Not authenticated or failed — send without user info
        }
    }

    if (!impl_->hwid.empty()) {
        body["hwid"] = impl_->hwid;
    }

    if (!metadata.empty()) {
        json meta = json::object();
        for (auto& [k, v] : metadata) {
            meta[k] = v;
        }
        body["metadata"] = meta;
    }

    Impl::RequestOpts opts;
    opts.method = "POST";
    opts.url = impl_->base_url + "/api/webhooks/report";
    opts.body = body.dump();
    opts.needs_auth = false;
    opts.needs_nonce = true;
    impl_->parseResponse(impl_->request(opts));
}

// -- Token management --------------------------------------------------------

void AuthClient::setToken(const std::string& token) { impl_->token = token; }
std::string AuthClient::getToken() const { return impl_->token; }
void AuthClient::clearToken() { impl_->token.clear(); }
LastResponse AuthClient::getLastResponse() const { return impl_->last_response; }
void AuthClient::setHWID(const std::string& hwid) { impl_->hwid = hwid; }

}  // namespace authclient
