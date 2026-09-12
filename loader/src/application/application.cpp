#include "application.hpp"
#include "app/screens.hpp"
#include "app/process_list.hpp"
#include "auth/xorstr.hpp"
#include "auth/auth_shared.hpp"
#include "auth/saved_credentials.hpp"
#include "protection/protection.hpp"
#include "resource.h"
#include "ui/theme.hpp"
#include <authclient/authclient.hpp>
#include <authclient/crypto.hpp>
#include <authclient/hwid.hpp>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <shellapi.h>

#ifndef _NONPROD
#define OutputDebugStringA(...) ((void)0)
#endif

namespace mindless
{

struct SoftwareFile
{
    std::string id;
    std::string name;
};

struct LocalProfilePayload
{
    std::string json;
    std::string username;
    std::vector<uint8_t> avatar;
};

static size_t append_response(void* data, size_t size, size_t count, void* output)
{
    size_t total = size * count;
    static_cast<std::string*>(output)->append(static_cast<char*>(data), total);
    return total;
}

struct BoundedResponse
{
    std::string bytes;
    size_t maximum;
};

static size_t append_bounded_response(void* data, size_t size, size_t count, void* output)
{
    if (count != 0 && size > SIZE_MAX / count) return 0;
    size_t total = size * count;
    auto* response = static_cast<BoundedResponse*>(output);
    if (total > response->maximum - std::min(response->maximum, response->bytes.size())) return 0;
    response->bytes.append(static_cast<char*>(data), total);
    return total;
}

static std::string http_get(const std::string& url, curl_slist* headers, size_t maximum)
{
    CURL* curl = curl_easy_init();
    if (!curl) throw std::runtime_error("Unable to initialize profile request");
    BoundedResponse response{{}, maximum};
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 20L);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 3L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_bounded_response);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    if (headers) curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    CURLcode result = curl_easy_perform(curl);
    long status = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    curl_easy_cleanup(curl);
    if (result != CURLE_OK) {
        if (result == CURLE_WRITE_ERROR) throw std::runtime_error("Profile response is too large");
        throw std::runtime_error(curl_easy_strerror(result));
    }
    if (status < 200 || status >= 300)
        throw std::runtime_error("Profile request failed with HTTP " + std::to_string(status));
    return std::move(response.bytes);
}

static bool safe_cdn_part(const std::string& value, bool digitsOnly)
{
    if (value.empty() || value.size() > 128) return false;
    for (unsigned char c : value)
    {
        if (digitsOnly ? (c < '0' || c > '9')
                       : !(std::isalnum(c) || c == '_')) return false;
    }
    return true;
}

static std::string profile_text(const nlohmann::json& object, const char* key, size_t maximum,
                                bool digitsOnly = false)
{
    if (!object.is_object() || !object.contains(key) || !object[key].is_string()) return {};
    const std::string source = object[key].get<std::string>();
    std::string result;
    result.reserve(std::min(source.size(), maximum));
    for (size_t i = 0; i < source.size() && result.size() < maximum;)
    {
        unsigned char c = static_cast<unsigned char>(source[i]);
        size_t sequence = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
        if (i + sequence > source.size() || result.size() + sequence > maximum) break;
        if (digitsOnly)
        {
            if (sequence == 1 && c >= '0' && c <= '9') result.push_back(static_cast<char>(c));
        }
        else if (sequence > 1 || (c >= 32 && c != 127))
        {
            result.append(source, i, sequence);
        }
        i += sequence;
    }
    return result;
}

static LocalProfilePayload fetch_local_profile(const std::string& api,
                                               const std::string& token,
                                               const std::string& hwid)
{
    auto nonce = authclient::crypto::toHex(authclient::crypto::randomBytes(32));
    auto now = std::chrono::system_clock::now();
    auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();
    curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, ("Authorization: Bearer " + token).c_str());
    headers = curl_slist_append(headers, "X-Client-Type: software");
    headers = curl_slist_append(headers, ("X-HWID: " + hwid).c_str());
    headers = curl_slist_append(headers, ("X-Request-Nonce: " + nonce).c_str());
    headers = curl_slist_append(headers, ("X-Request-Timestamp: " + std::to_string(timestamp)).c_str());
    std::string response;
    try
    {
        response = http_get(api + "/auth/me", headers, 64 * 1024);
    }
    catch (...)
    {
        curl_slist_free_all(headers);
        throw;
    }
    curl_slist_free_all(headers);

    auto body = nlohmann::json::parse(response, nullptr, false);
    if (!body.is_object() || !body.contains("data") || !body["data"].is_object())
        throw std::runtime_error("Invalid profile response");
    const auto& data = body["data"];
    nlohmann::json local = nlohmann::json::object();
    std::string username = profile_text(data, "username", 32);
    local["username"] = username;

    std::string discordId;
    std::string avatarHash;
    if (data.contains("discord") && data["discord"].is_object())
    {
        const auto& discord = data["discord"];
        local["discord_display_name"] = profile_text(discord, "discord_global_name", 128);
        local["discord_username"] = profile_text(discord, "discord_username", 128);
        discordId = profile_text(discord, "discord_user_id", 32, true);
        avatarHash = profile_text(discord, "discord_avatar", 128);
        local["discord_id"] = discordId;
    }

    LocalProfilePayload payload;
    payload.json = local.dump();
    payload.username = username;
    if (safe_cdn_part(discordId, true) && safe_cdn_part(avatarHash, false))
    {
        try
        {
            std::string avatarUrl = "https://cdn.discordapp.com/avatars/" + discordId + "/"
                + avatarHash + ".png?size=128";
            std::string bytes = http_get(avatarUrl, nullptr, 2 * 1024 * 1024);
            static const unsigned char png[] = {137, 80, 78, 71, 13, 10, 26, 10};
            if (bytes.size() >= sizeof(png)
                && std::equal(std::begin(png), std::end(png),
                              reinterpret_cast<const unsigned char*>(bytes.data())))
                payload.avatar.assign(bytes.begin(), bytes.end());
        }
        catch (...) {}
    }
    return payload;
}

static std::vector<SoftwareFile> list_software_files(const std::string& api,
                                                     const std::string& token,
                                                     const std::string& hwid)
{
    CURL* curl = curl_easy_init();
    if (!curl) throw std::runtime_error("Unable to initialize file request");

    std::string response;
    std::string url = api + "/software/files";
    auto nonce = authclient::crypto::toHex(authclient::crypto::randomBytes(32));
    auto now = std::chrono::system_clock::now();
    auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();

    curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, ("Authorization: Bearer " + token).c_str());
    headers = curl_slist_append(headers, "X-Client-Type: software");
    headers = curl_slist_append(headers, ("X-HWID: " + hwid).c_str());
    headers = curl_slist_append(headers, ("X-Request-Nonce: " + nonce).c_str());
    headers = curl_slist_append(headers, ("X-Request-Timestamp: " + std::to_string(timestamp)).c_str());

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_response);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    CURLcode result = curl_easy_perform(curl);
    long status = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (result != CURLE_OK) throw std::runtime_error(curl_easy_strerror(result));

    auto body = nlohmann::json::parse(response, nullptr, false);
    if (status < 200 || status >= 300)
    {
        if (body.is_object() && body.contains("error"))
            throw std::runtime_error(body["error"].get<std::string>());
        throw std::runtime_error("File request failed with HTTP " + std::to_string(status));
    }
    if (!body.is_object() || !body.contains("data") || !body["data"].contains("files"))
        throw std::runtime_error("Invalid file list response");

    std::vector<SoftwareFile> files;
    for (const auto& file : body["data"]["files"])
    {
        if (!file.contains("id") || !file.contains("name")) continue;
        files.push_back({ file["id"].get<std::string>(), file["name"].get<std::string>() });
    }
    return files;
}

static std::pair<const void*, size_t> get_resource(int id, const wchar_t* type)
{
    HMODULE mod  = GetModuleHandleW(nullptr);
    HRSRC   rsrc = FindResourceW(mod, MAKEINTRESOURCEW(id), type);
    if (!rsrc) return { nullptr, 0 };

    HGLOBAL hgl  = LoadResource(mod, rsrc);
    if (!hgl) return { nullptr, 0 };

    return { LockResource(hgl), SizeofResource(mod, rsrc) };
}

Application::~Application()
{
    protection::shutdown();
    if (authThread_.joinable()) authThread_.join();
    if (downloadThread_.joinable()) downloadThread_.join();
    if (authSection_) CloseHandle(authSection_);
}

bool Application::init()
{
    if (!protection::init() || !protection::checkAll()) return false;

    const int margin = static_cast<int>(ui::g_theme.glowMargin) * 2;
    if (!window_.create(L"Mindless", 120 + margin, 120 + margin))
        return false;

    HICON icon = LoadIconW(GetModuleHandleW(nullptr),
                           MAKEINTRESOURCEW(IDI_APPICON));
    if (icon)
    {
        appIcon_ = icon;
        window_.set_icon(icon);
    }

    window_.onResize = [this](int w, int h) { renderer_.resize(w, h); };

    if (!renderer_.init(window_.hwnd(), window_.width(), window_.height()))
        return false;

    const auto& t = ui::g_theme;

    auto [fontData, fontSize] = get_resource(IDR_FONT_NORMAL, RT_RCDATA);
    if (!fontData) return false;

    if (!fontNormal_.load_from_memory(fontData, fontSize,
                                       t.fontSizeNormal, renderer_.device()))
        return false;
    if (!fontTitle_.load_from_memory(fontData, fontSize,
                                      t.fontSizeTitle, renderer_.device()))
        return false;
    if (!fontCaption_.load_from_memory(fontData, fontSize,
                                        t.fontSizeSmall, renderer_.device()))
        return false;

    auto [logoData, logoSize] = get_resource(IDR_LOGO_PNG, RT_RCDATA);
    if (logoData)
        logo_ = load_image_from_memory(logoData, logoSize, renderer_.device());

    std::string savedUser, savedPass;
    bool remember = false;
    if (load_credentials(savedUser, savedPass, remember))
    {
        state_.username.text = savedUser;
        state_.username.move_to(static_cast<int>(savedUser.size()), false);
        state_.password.text = savedPass;
        state_.password.move_to(static_cast<int>(savedPass.size()), false);
        state_.rememberMe = remember;
        state_.rememberCheck.snap(remember ? 1.0f : 0.0f);

        // Filled in, never submitted. Signing in on its own took the choice away: there
        // was no moment to reach the browser button, to clear Remember me, or to sign in
        // as somebody else, because the loader had already moved past the screen.
        if (!savedUser.empty())
        {
            state_.focus_field(savedPass.empty() ? 1 : 0);
        }
    }

    state_.fadeIn.reset(0.0f);
    lastFrame_ = Clock::now();

    renderer_.begin_frame();
    draw_frame(0.0f);
    renderer_.end_frame();
    renderer_.present(true);

    window_.show();

    return true;
}

void Application::start_auth()
{
    if (authThread_.joinable()) authThread_.join();
    authDone_ = false;
    pendingAuthToken_.clear();
    pendingAuthHwid_.clear();
    pendingAuthError_.clear();
    pendingAuthComplete_ = false;
    pendingProfileJson_.clear();
    pendingProfileUsername_.clear();
    pendingAvatarBytes_.clear();
    pendingProfileReady_ = false;

    std::string user = state_.username.text;
    std::string pass = state_.password.text;

    authThread_ = std::thread([this, user, pass] {
        if (user.empty() || pass.empty())
        {
            pendingAuthError_ = "Please enter username and password";
            authDone_ = true;
            return;
        }

        try
        {
            const char* api = XORSTR("https://api.mindless.rest");
            authclient::AuthClient client(api);

            std::string hwid = authclient::getHWID();
            client.setHWID(hwid);

            auto login = client.login(user, pass);
            client.setToken(login.token);

            auto session = client.validateSession();
            if (!session.valid)
            {
                pendingAuthError_ = "Session invalid after login";
                authDone_ = true;
                return;
            }

            pendingAuthToken_ = login.token;
            pendingAuthHwid_ = hwid;
            try
            {
                LocalProfilePayload profile = fetch_local_profile(api, login.token, hwid);
                pendingProfileJson_ = std::move(profile.json);
                pendingProfileUsername_ = std::move(profile.username);
                pendingAvatarBytes_ = std::move(profile.avatar);
                pendingProfileReady_ = true;
            }
            catch (const std::exception& e)
            {
                OutputDebugStringA((std::string("[MindlessLoader] Profile sync failed: ") + e.what() + "\n").c_str());
            }
            pendingAuthComplete_ = true;
        }
        catch (const authclient::AuthException& e)
        {
            pendingAuthError_ = e.message().empty() ? e.code() : e.message();
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[MindlessLoader] Auth failed: %s (%s)\n", e.code().c_str(), e.message().c_str());
            OutputDebugStringA(dbg);
        }
        catch (const std::exception& e)
        {
            pendingAuthError_ = e.what();
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[MindlessLoader] Exception: %s\n", e.what());
            OutputDebugStringA(dbg);
        }
        catch (...)
        {
            pendingAuthError_ = "Authentication failed unexpectedly";
            OutputDebugStringA("[MindlessLoader] Unknown authentication exception\n");
        }
        authDone_ = true;
    });
}

void Application::start_download()
{
    if (downloadThread_.joinable()) downloadThread_.join();
    downloadDone_ = false;
    downloadStarted_ = true;
    pendingDllBytes_.clear();
    pendingDownloadStatus_.clear();
    pendingDownloadSolution_.clear();
    pendingDownloadFailed_ = false;

    std::string token = state_.authToken;
    std::string hwid = state_.authHwid;

    downloadThread_ = std::thread([this, token, hwid] {
        try
        {
            OutputDebugStringA("[Mindless] Download: creating client\n");
            const char* api = XORSTR("https://api.mindless.rest");
            authclient::AuthClient client(api);
            client.setToken(token);
            if (!hwid.empty()) client.setHWID(hwid);

            OutputDebugStringA("[Mindless] Download: listing files\n");
            auto files = list_software_files(api, token, hwid);

            std::string fileId;
            for (const auto& f : files)
            {
                if (f.name == "mindless-native" || f.name == "MindlessNative.dll" || f.name == "mindless")
                {
                    fileId = f.id;
                    break;
                }
            }
            if (fileId.empty())
            {
                OutputDebugStringA("[Mindless] Download: no matching file found\n");
                pendingDownloadStatus_ = "Payload not found";
                pendingDownloadSolution_ = "Upload MindlessNative.dll to the panel.";
                pendingDownloadFailed_ = true;
                downloadDone_ = true;
                return;
            }

            char dbg2[256];
            snprintf(dbg2, sizeof(dbg2), "[Mindless] Download: file id = %s\n", fileId.c_str());
            OutputDebugStringA(dbg2);

            OutputDebugStringA("[Mindless] Download: calling downloadFile\n");
            auto dllBytes = client.downloadFile(fileId);

            char dbg[128];
            snprintf(dbg, sizeof(dbg), "[Mindless] Download: got %zu bytes\n", dllBytes.size());
            OutputDebugStringA(dbg);

            if (dllBytes.empty())
            {
                pendingDownloadStatus_ = "Payload not found";
                pendingDownloadSolution_ = "Upload MindlessNative.dll to the panel.";
                pendingDownloadFailed_ = true;
            }
            else
            {
                pendingDllBytes_ = std::move(dllBytes);
            }
        }
        catch (const authclient::AuthException& e)
        {
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[Mindless] Download AuthException: %s (%s)\n",
                     e.code().c_str(), e.message().c_str());
            OutputDebugStringA(dbg);
            pendingDownloadStatus_ = "Download failed";
            pendingDownloadSolution_ = e.message().empty() ? e.code() : e.message();
            pendingDownloadFailed_ = true;
        }
        catch (const std::exception& e)
        {
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[Mindless] Download exception: %s\n", e.what());
            OutputDebugStringA(dbg);
            pendingDownloadStatus_ = "Download failed";
            pendingDownloadSolution_ = e.what();
            pendingDownloadFailed_ = true;
        }
        catch (...)
        {
            OutputDebugStringA("[Mindless] Unknown download exception\n");
            pendingDownloadStatus_ = "Download failed";
            pendingDownloadSolution_ = "An unexpected error interrupted the download.";
            pendingDownloadFailed_ = true;
        }
        downloadDone_ = true;
    });
}

void Application::show_completion_toast()
{
    NOTIFYICONDATAW nid = {};
    nid.cbSize = sizeof(nid);
    nid.hWnd = window_.hwnd();
    nid.uID = 0x4D4C;
    nid.uFlags = NIF_ICON | NIF_TIP | NIF_INFO | NIF_MESSAGE;
    nid.uCallbackMessage = WM_APP + 42;
    nid.hIcon = appIcon_ ? appIcon_ : LoadIconW(nullptr, IDI_APPLICATION);
    nid.dwInfoFlags = NIIF_INFO | NIIF_NOSOUND | NIIF_LARGE_ICON;
    wcsncpy_s(nid.szInfoTitle, _countof(nid.szInfoTitle), L"Mindless", _TRUNCATE);
    wcsncpy_s(nid.szInfo, _countof(nid.szInfo), L"Client loaded successfully", _TRUNCATE);
    wcscpy_s(nid.szTip, _countof(nid.szTip), L"Mindless");
    Shell_NotifyIconW(NIM_ADD, &nid);
    nid.uFlags = NIF_ICON;
    Shell_NotifyIconW(NIM_DELETE, &nid);
}

int Application::run()
{
    while (window_.poll_events())
    {
        auto  now = Clock::now();
        float dt  = std::chrono::duration<float>(now - lastFrame_).count();
        lastFrame_ = now;
        dt = std::min(dt, 0.1f);

        if (protection::isCompromised() && state_.screen != Screen::Login
            && state_.screen != Screen::Closing)
        {
            injection_.reset();
            if (!state_.dllBytes.empty())
            {
                SecureZeroMemory(state_.dllBytes.data(), state_.dllBytes.size());
                state_.dllBytes.clear();
            }
            state_.authComplete = false;
            state_.authError = std::string("Security validation failed: ") + protection::lastReason();
            state_.transition_to(Screen::Login, -1.0f);
        }

        // Auth flow — runs on Login screen after user clicks sign in
        if (state_.screen == Screen::Login && state_.authInProgress)
        {
            if (authDone_)
            {
                if (authThread_.joinable())
                {
                    // Thread just finished — process the result
                    authThread_.join();
                    state_.authInProgress = false;
                    state_.authToken = std::move(pendingAuthToken_);
                    state_.authHwid = std::move(pendingAuthHwid_);
                    state_.authError = std::move(pendingAuthError_);
                    state_.authComplete = pendingAuthComplete_;

                    if (state_.authComplete && state_.authError.empty())
                    {
                        authClient_ = std::make_unique<authclient::AuthClient>(
                            XORSTR("https://api.mindless.rest"));
                        authClient_->setToken(state_.authToken);
                        if (!state_.authHwid.empty())
                            authClient_->setHWID(state_.authHwid);
                        protection::setAuthClient(authClient_.get());
                        if (!protection::checkAll())
                        {
                            state_.authComplete = false;
                            state_.authError = "Security validation failed";
                        }
                        else
                        {
                            protection::startWatchdog();
                            save_credentials(state_.username.text, state_.password.text, state_.rememberMe);
                            mindless::save_session_username(pendingProfileReady_ && !pendingProfileUsername_.empty()
                                ? pendingProfileUsername_ : state_.username.text);
                            if (pendingProfileReady_)
                                mindless::save_local_profile(pendingProfileJson_, pendingAvatarBytes_);
                            else
                                mindless::clear_local_profile();
                            state_.release_process_icons();
                            state_.processes = enumerate_targets(renderer_.device());
                            state_.refreshAccum = 0.0f;
                            state_.selectedIdx = -1;
                            state_.statusText = "Authenticated";
                            // Fetch the payload now rather than on the loading screen. It is over
                            // 50MB and the transfer used to be entirely serial in front of the
                            // user: pick a process, press load, then wait out the whole download.
                            // Starting it here overlaps it with choosing a process, which is dead
                            // time otherwise. Nothing else changes -- same token, same request,
                            // still only ever held in memory.
                            if (!downloadStarted_ && state_.dllBytes.empty())
                            {
                                start_download();
                            }
                            state_.transition_to(Screen::ProcessSelect, 1.0f);
                        }
                    }
                    else
                    {
                        // Show error on the login screen (authError is rendered there)
                        if (state_.authError.empty())
                            state_.authError = "Authentication failed";
                    }
                }
                else
                {
                    // authDone_ is stale from a previous attempt (thread already joined).
                    // sign_in() was called again — start a fresh auth.
                    start_auth();
                }
            }
            else if (!authThread_.joinable())
            {
                // First attempt — kick off auth thread
                start_auth();
            }
            // else: thread is running, wait for authDone_
        }

        if (state_.screen == Screen::Loading)
        {
            // Step 1: kick off DLL download if we don't have it yet
            if (!downloadStarted_ && state_.dllBytes.empty() && !state_.loadFailed)
            {
                state_.statusText = "Gathering resources";
                start_download();
            }

            // Step 2: once download is done, start injection
            if (downloadStarted_ && downloadDone_ && !state_.loadFailed)
            {
                if (downloadThread_.joinable()) downloadThread_.join();
                state_.dllBytes = std::move(pendingDllBytes_);
                state_.statusText = std::move(pendingDownloadStatus_);
                state_.solutionText = std::move(pendingDownloadSolution_);
                state_.loadFailed = pendingDownloadFailed_;
                downloadStarted_ = false;
            }

            if (injection_.phase() == InjectionPhase::Idle &&
                !state_.dllBytes.empty() &&
                (state_.selectedIdx < 0 ||
                 state_.selectedIdx >= static_cast<int>(state_.processes.size())))
            {
                if (!injectGateLogged_)
                {
                    injectGateLogged_ = true;
                    char dbg[192];
                    snprintf(dbg, sizeof(dbg),
                             "[Mindless] Inject: payload ready but no target selected "
                             "(selectedIdx=%d, processes=%zu)\n",
                             state_.selectedIdx, state_.processes.size());
                    OutputDebugStringA(dbg);
                }
            }

            if (injection_.phase() == InjectionPhase::Idle &&
                !state_.dllBytes.empty() &&
                state_.selectedIdx >= 0 &&
                state_.selectedIdx < static_cast<int>(state_.processes.size()))
            {
                injectGateLogged_ = false;
                uint32_t pid = state_.processes[state_.selectedIdx].pid;

                if (authSection_) { CloseHandle(authSection_); authSection_ = nullptr; }

                AuthSharedData shared = {};
                strncpy_s(shared.token, sizeof(shared.token),
                          state_.authToken.c_str(), _TRUNCATE);
                strncpy_s(shared.hwid, sizeof(shared.hwid),
                          state_.authHwid.c_str(), _TRUNCATE);
                {
                    const char* api = XORSTR("https://api.mindless.rest");
                    strncpy_s(shared.api_url, sizeof(shared.api_url), api, _TRUNCATE);
                }
                authSection_ = create_auth_section(pid, shared);
                SecureZeroMemory(&shared, sizeof(shared));
                {
                    char dbg[160];
                    snprintf(dbg, sizeof(dbg),
                             "[Mindless] Inject: target pid=%lu authSection=%s payload=%zu\n",
                             (unsigned long)pid, authSection_ ? "ok" : "NULL",
                             state_.dllBytes.size());
                    OutputDebugStringA(dbg);
                }

                injection_.start(pid, state_.dllBytes.data(), state_.dllBytes.size());
            }

            if (state_.retryRequested)
            {
                state_.retryRequested = false;
                if (downloadThread_.joinable()) downloadThread_.join();
                downloadStarted_ = false;
                downloadDone_ = false;
                state_.dllBytes.clear();
                injection_.reset();
                state_.begin_loading();
                notificationSent_ = false;
            }

            state_.spinElapsed += dt;
            state_.statusFade.tick(dt);

            bool downloading = downloadStarted_ && !downloadDone_;
            float target = 0.0f;

            if (state_.loadFailed)
            {
                target = state_.loadProgress;
            }
            else if (downloading)
            {
                // Preparing: 0% → 50% (slow asymptotic crawl so it never stalls visually)
                if (state_.statusText != "Gathering resources")
                {
                    state_.statusText = "Gathering resources";
                    state_.statusFade.reset(0.16f);
                }
                target = 0.45f;
            }
            else
            {
                injection_.tick();
                const std::string visibleStatus = injection_.phase() == InjectionPhase::Injecting
                    ? "Transforming layers"
                    : injection_.status();
                if (state_.statusText != visibleStatus)
                {
                    state_.statusText = visibleStatus;
                    state_.statusFade.reset(0.16f);
                }
                state_.solutionText = injection_.solution();
                if (injection_.phase() == InjectionPhase::Failed)
                    state_.loadFailed = true;

                // Injecting: 50% → 85%, Complete: 85% → 100%
                if (injection_.phase() == InjectionPhase::Injecting)
                    target = 0.48f + injection_.progress() * 0.50f;
                else if (injection_.phase() == InjectionPhase::Complete)
                    target = 1.0f;
                else
                    target = state_.loadProgress;
            }

            if (!state_.loadFailed)
            {
                float step  = (target - state_.loadProgress) * (1.0f - std::exp(-4.0f * dt));
                float limit = dt * AppState::MaxProgressRate;
                state_.loadProgress += clamp(step, -limit, limit);
            }

            if (injection_.phase() == InjectionPhase::Complete)
            {
                if (!notificationSent_)
                {
                    notificationSent_ = true;
                    show_completion_toast();
                }
                state_.loadProgress = std::min(1.0f,
                    state_.loadProgress + dt * 2.5f);
                state_.loadElapsed += dt;
                if (state_.loadProgress >= 0.999f && state_.loadElapsed >= 0.45f)
                {
                    // Wipe DLL bytes from memory
                    if (!state_.dllBytes.empty())
                    {
                        SecureZeroMemory(state_.dllBytes.data(), state_.dllBytes.size());
                        state_.dllBytes.clear();
                        state_.dllBytes.shrink_to_fit();
                    }

                    state_.screen = Screen::Closing;
                    state_.prevScreen  = Screen::Loading;
                    state_.slideInT    = 1.0f;
                    state_.slideOutT   = 1.0f;
                    state_.closeTween = 0.0f;
                }
            }
        }

        if (state_.screen == Screen::ProcessSelect)
        {
            if (injection_.phase() != InjectionPhase::Idle)
                injection_.reset();

            state_.refreshAccum += dt;
            if (state_.refreshAccum >= AppState::RefreshInterval)
            {
                state_.refreshAccum = 0.0f;

                std::vector<uint32_t> pids = enumerate_target_pids();
                bool changed = pids.size() != state_.processes.size();
                for (size_t i = 0; !changed && i < pids.size(); ++i)
                    changed = pids[i] != state_.processes[i].pid;

                if (changed)
                {
                    uint32_t selectedPid = state_.selectedIdx >= 0 &&
                        state_.selectedIdx < static_cast<int>(state_.processes.size())
                        ? state_.processes[state_.selectedIdx].pid : 0;

                    bool wasEmpty = state_.processes.empty();
                    state_.release_process_icons();
                    state_.processes = enumerate_targets(renderer_.device());
                    state_.reselect_pid(selectedPid);
                    if (wasEmpty && !state_.processes.empty())
                        state_.fadeIn.reset(0.35f);
                }
            }
        }

        if (state_.screen == Screen::Login ||
            state_.screen == Screen::ProcessSelect ||
            state_.screen == Screen::Loading)
        {
            int margin = static_cast<int>(ui::g_theme.glowMargin) * 2;
            int desiredW = 460 + margin;
            int desiredH = 330 + margin;

            RECT cur;
            GetWindowRect(window_.hwnd(), &cur);
            int curW = cur.right - cur.left;
            int curH = cur.bottom - cur.top;
            if (curW != desiredW || curH != desiredH)
            {
                int newX = cur.left + (curW - desiredW) / 2;
                int newY = cur.top + (curH - desiredH) / 2;
                SetWindowPos(window_.hwnd(), nullptr, newX, newY, desiredW, desiredH,
                             SWP_NOZORDER | SWP_NOACTIVATE);
            }
        }

        if (state_.screen == Screen::Splash)
        {
            float ease = ease_out_quart(state_.logoTween);
            int screenW = GetSystemMetrics(SM_CXSCREEN);
            int screenH = GetSystemMetrics(SM_CYSCREEN);
            int cx = screenW / 2;
            int cy = screenH / 2;

            int margin = static_cast<int>(ui::g_theme.glowMargin) * 2;
            int newW = static_cast<int>(lerp(120.0f, 460.0f, ease)) + margin;
            int newH = static_cast<int>(lerp(120.0f, 330.0f, ease)) + margin;
            int newX = cx - newW / 2;
            int newY = cy - newH / 2;

            RECT cur;
            GetWindowRect(window_.hwnd(), &cur);
            if (cur.right - cur.left != newW || cur.bottom - cur.top != newH)
                SetWindowPos(window_.hwnd(), nullptr, newX, newY, newW, newH, SWP_NOZORDER | SWP_NOACTIVATE);
        }

        if (state_.screen == Screen::Closing)
        {
            if (state_.closeOrigW == 0)
            {
                RECT rect;
                GetWindowRect(window_.hwnd(), &rect);
                state_.closeOrigX = rect.left;
                state_.closeOrigY = rect.top;
                state_.closeOrigW = rect.right - rect.left;
                state_.closeOrigH = rect.bottom - rect.top;
            }

            int newW = state_.closeOrigW;
            int newH = state_.closeOrigH;
            int newX = state_.closeOrigX;
            int newY = state_.closeOrigY;

            if (state_.closeTween > 0.7f)
            {
                float shrinkT = (state_.closeTween - 0.7f) / 0.3f;
                float ease = ease_in_out_cubic(shrinkT);
                newW = std::max(1, static_cast<int>(lerp(static_cast<float>(state_.closeOrigW), 0.0f, ease)));
                newH = std::max(1, static_cast<int>(lerp(static_cast<float>(state_.closeOrigH), 0.0f, ease)));
                newX = state_.closeOrigX + (state_.closeOrigW - newW) / 2;
                newY = state_.closeOrigY + (state_.closeOrigH - newH) / 2;
            }

            SetWindowPos(window_.hwnd(), nullptr, newX, newY, newW, newH, SWP_NOZORDER | SWP_NOACTIVATE);
        }

        renderer_.begin_frame();
        draw_frame(dt);
        renderer_.end_frame();
        renderer_.present(true);

        if (state_.should_close())
            window_.close();
    }
    return 0;
}

void Application::draw_frame(float dt)
{
    const InputState& input = window_.input().state();
    const int W = renderer_.width();
    const int H = renderer_.height();

    float margin = ui::g_theme.glowMargin;
    Rect panel = { margin, margin,
                   static_cast<float>(W) - margin * 2.0f,
                   static_cast<float>(H) - margin * 2.0f };

    drawList_.clear();

    ScreenFonts fonts { fontNormal_, fontTitle_, fontCaption_ };

    bool closeRequested = false;
    draw_screen(drawList_, state_, input, fonts, panel, logo_, dt, closeRequested, &window_);

    if (closeRequested)
        window_.close();

    execute_draw_list(renderer_, drawList_);
    window_.set_drag_rect(static_cast<int>(margin), static_cast<int>(margin),
                          static_cast<int>(panel.w) - 90, 48);
}

} // namespace mindless
