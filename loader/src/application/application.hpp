#pragma once
#include "window/window.hpp"
#include "renderer/d3d11_renderer.hpp"
#include "renderer/font_atlas.hpp"
#include "renderer/image.hpp"
#include "ui/draw_list.hpp"
#include "app/app_state.hpp"
#include "injection/injection_session.hpp"
#include <chrono>

namespace mindless
{

class Application
{
public:
    Application() = default;
    ~Application() = default;

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

    using Clock     = std::chrono::steady_clock;
    using TimePoint = Clock::time_point;
    TimePoint   lastFrame_;

    void draw_frame(float dt);
    void show_completion_toast();
};

} // namespace mindless

