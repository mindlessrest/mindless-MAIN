#include "application.hpp"
#include "app/screens.hpp"
#include "app/process_list.hpp"
#include "auth/xorstr.hpp"
#include "auth/auth_shared.hpp"
#include "resource.h"
#include "ui/theme.hpp"
#include <authclient/authclient.hpp>
#include <authclient/hwid.hpp>
#include <chrono>
#include <cmath>
#include <shellapi.h>

namespace mindless
{

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
    if (authThread_.joinable()) authThread_.join();
    if (authSection_) CloseHandle(authSection_);
}

bool Application::init()
{
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

    std::string user = state_.username.text;
    std::string pass = state_.password.text;

    authThread_ = std::thread([this, user, pass] {
        if (user.empty() || pass.empty())
        {
            state_.authError = "Please enter username and password";
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
                state_.authError = "Session invalid after login";
                authDone_ = true;
                return;
            }

            auto dllBytes = client.downloadFile("mindless-native");

            if (dllBytes.empty())
            {
                state_.authError = "Failed to download client files";
                authDone_ = true;
                return;
            }

            state_.authToken = login.token;
            state_.authHwid  = hwid;
            state_.dllBytes  = std::move(dllBytes);
            state_.authComplete = true;
        }
        catch (const authclient::AuthException& e)
        {
            state_.authError = e.message().empty() ? e.code() : e.message();
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[MindlessLoader] Auth failed: %s (%s)\n", e.code().c_str(), e.message().c_str());
            OutputDebugStringA(dbg);
        }
        catch (const std::exception& e)
        {
            state_.authError = e.what();
            char dbg[512];
            snprintf(dbg, sizeof(dbg), "[MindlessLoader] Exception: %s\n", e.what());
            OutputDebugStringA(dbg);
        }
        authDone_ = true;
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

        // Auth flow — runs on Login screen after user clicks sign in
        if (state_.screen == Screen::Login && state_.authInProgress)
        {
            if (!authDone_)
            {
                // Auth thread hasn't started yet — kick it off
                if (!authThread_.joinable())
                    start_auth();
            }
            else
            {
                if (authThread_.joinable()) authThread_.join();
                state_.authInProgress = false;

                if (state_.authComplete && state_.authError.empty())
                {
                    state_.statusText = "Authenticated";
                    transition_to_process_select:
                    state_.transition_to(Screen::ProcessSelect, 1.0f);
                }
                else
                {
                    state_.statusText = state_.authError.empty()
                        ? "Authentication failed" : state_.authError;
                    state_.statusFade.reset(0.16f);
                }
            }
        }

        if (state_.screen == Screen::Loading)
        {
            if (injection_.phase() == InjectionPhase::Idle &&
                state_.selectedIdx >= 0 &&
                state_.selectedIdx < static_cast<int>(state_.processes.size()))
            {
                uint32_t pid = state_.processes[state_.selectedIdx].pid;

                // Create shared memory section with auth data for the DLL
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

                injection_.start(pid, state_.dllBytes.data(), state_.dllBytes.size());
            }

            if (state_.retryRequested)
            {
                state_.retryRequested = false;
                injection_.reset();
                state_.begin_loading();
                notificationSent_ = false;
            }

            injection_.tick();
            state_.spinElapsed += dt;
            if (state_.statusText != injection_.status())
            {
                state_.statusText = injection_.status();
                state_.statusFade.reset(0.16f);
            }
            state_.statusFade.tick(dt);
            state_.solutionText = injection_.solution();
            state_.loadFailed = injection_.phase() == InjectionPhase::Failed;

            if (!state_.loadFailed)
            {
                float target = injection_.progress();
                float step   = (target - state_.loadProgress) * (1.0f - std::exp(-6.0f * dt));
                float limit  = dt * AppState::MaxProgressRate;
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
    draw_screen(drawList_, state_, input, fonts, panel, logo_, dt, closeRequested, &window_, renderer_.device());

    if (closeRequested)
        window_.close();

    execute_draw_list(renderer_, drawList_);
    window_.set_drag_rect(static_cast<int>(margin), static_cast<int>(margin),
                          static_cast<int>(panel.w) - 90, 48);
}

} // namespace mindless
