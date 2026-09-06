#pragma once
#include "window/window.hpp"
#include "renderer/d3d11_renderer.hpp"
#include "renderer/font_atlas.hpp"
#include "renderer/image.hpp"
#include "ui/draw_list.hpp"
#include "app/app_state.hpp"
#include "injection/injection_session.hpp"
#include <authclient/authclient.hpp>
#include <chrono>
#include <thread>
#include <atomic>
#include <memory>

namespace mindless
{

class Application
{
public:
    Application() = default;
    ~Application();

    Application(const Application&) = delete;
    Application& operator=(const Application&) = delete;

    bool init();
    int  run();

private:
    Window      window_;
    Renderer    renderer_;
    FontAtlas   fontNormal_;
    FontAtlas   fontTitle_;
    FontAtlas   fontCaption_;
    Image       logo_;
    ui::DrawList drawList_;
    AppState    state_;
    InjectionSession injection_;
    HICON       appIcon_ = nullptr;
    bool        notificationSent_ = false;
    bool        injectGateLogged_ = false;

    // Auth
    std::thread authThread_;
    std::atomic<bool> authDone_{false};
    std::string pendingAuthToken_;
    std::string pendingAuthHwid_;
    std::string pendingAuthError_;
    bool pendingAuthComplete_ = false;
    HANDLE authSection_ = nullptr;

    // DLL download (deferred to injection time)
    std::thread downloadThread_;
    std::atomic<bool> downloadDone_{false};
    bool downloadStarted_ = false;
    std::vector<uint8_t> pendingDllBytes_;
    std::string pendingDownloadStatus_;
    std::string pendingDownloadSolution_;
    bool pendingDownloadFailed_ = false;

    void start_download();

    // Persistent auth client for protection webhook reporting
    std::unique_ptr<authclient::AuthClient> authClient_;

    using Clock     = std::chrono::steady_clock;
    using TimePoint = Clock::time_point;
    TimePoint   lastFrame_;

    void draw_frame(float dt);
    void show_completion_toast();
    void start_auth();
};

} // namespace mindless
