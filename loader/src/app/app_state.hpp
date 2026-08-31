#pragma once
#include "app/process_list.hpp"
#include "core/anim.hpp"
#include <string>
#include <vector>
#include <cstdio>
#include <cmath>
#include <cstdint>

namespace mindless
{

enum class Screen
{
    Splash,
    Login,
    ProcessSelect,
    Loading,
    Closing,
};

static constexpr int kMaxProcessRows = 16;

struct AppState
{
    Screen screen = Screen::Splash;

    std::vector<ProcessEntry> processes;
    int                       selectedIdx = -1;

    float       loadProgress = 0.0f;
    float       loadElapsed  = 0.0f;
    float       spinElapsed  = 0.0f;
    std::string statusText   = "Connecting to Minecraft";
    std::string solutionText;
    bool        loadFailed = false;
    bool        retryRequested = false;
    Timer       statusFade;

    // Ceiling on how fast the bar may fill, in fraction per second.
    static constexpr float MaxProgressRate = 0.55f;

    Timer fadeIn;

    float splashElapsed = 0.0f;
    static constexpr float SplashHold    = 0.9f;
    static constexpr float SplashTween   = 0.45f;

    float logoTween = 0.0f;

    std::string targetDisplay;
    std::string targetPid;

    float listScroll = 0.0f;

    float refreshAccum = 0.0f;
    static constexpr float RefreshInterval = 0.4f;

    float uiElapsed = 0.0f;

    float closeTween   = 0.0f;
    int   closeOrigX   = 0;
    int   closeOrigY   = 0;
    int   closeOrigW   = 0;
    int   closeOrigH   = 0;
    static constexpr float CloseDuration = 1.0f;

    float slideDirection = 1.0f;

    // Cross-transition: the outgoing screen clears out before the incoming one arrives, so the
    // two are never legible on top of each other.
    Screen prevScreen     = Screen::Splash;
    float  slideOutT      = 1.0f;  // 1.0 = fully gone, drives outgoing screen
    float  slideInT       = 1.0f;  // 1.0 = fully arrived, drives incoming screen
    static constexpr float SlideDuration = 0.34f;
    static constexpr float SlideHandover = 0.44f;

    // Per-row hover tweens (0→1)
    Tween rowHover[kMaxProcessRows];

    // Continue / Load button hover tween
    Tween continueHover;

    // Back link hover tween
    Tween backHover;
    Tween retryHover;

    std::string username;
    std::string password;
    int         focusField = 0;
    bool        selectAll  = false;
    float       caretPhase = 0.0f;
    static constexpr size_t MaxFieldLength = 48;

    Tween userFocus;
    Tween passFocus;
    Tween userHover;
    Tween passHover;
    Tween signInHover;

    // Chrome: minimize / close button hover tweens
    Tween chromeMinHover;
    Tween chromeCloseHover;

    AppState()
    {
        for (auto& t : rowHover)   { t.speed = 14.0f; }
        continueHover.speed   = 14.0f;
        backHover.speed       = 14.0f;
        retryHover.speed      = 14.0f;
        chromeMinHover.speed  = 14.0f;
        chromeCloseHover.speed= 14.0f;
        userFocus.speed       = 16.0f;
        passFocus.speed       = 16.0f;
        userHover.speed       = 14.0f;
        passHover.speed       = 14.0f;
        signInHover.speed     = 14.0f;
    }

    void advance_tweens(float dt)
    {
        uiElapsed += dt;

        for (auto& t : rowHover)  t.advance(dt);
        continueHover.advance(dt);
        backHover.advance(dt);
        retryHover.advance(dt);
        chromeMinHover.advance(dt);
        chromeCloseHover.advance(dt);
        userFocus.advance(dt);
        passFocus.advance(dt);
        userHover.advance(dt);
        passHover.advance(dt);
        signInHover.advance(dt);

        if (slideInT  < 1.0f) slideInT  = std::min(1.0f, slideInT  + dt / SlideDuration);
        if (slideOutT < 1.0f) slideOutT = std::min(1.0f, slideOutT + dt / SlideDuration);
    }

    void transition_to(Screen next, float dir)
    {
        prevScreen     = screen;
        screen         = next;
        slideDirection = dir;
        slideInT       = 0.0f;
        slideOutT      = 0.0f;
    }

    void focus_field(int index)
    {
        focusField = index;
        selectAll  = false;
        caretPhase = 0.0f;
    }

    void sign_in()
    {
        selectAll  = false;
        caretPhase = 0.0f;
        signInHover.snap(0.0f);
        transition_to(Screen::ProcessSelect, 1.0f);
    }

    void release_process_icons()
    {
        for (auto& pe : processes) pe.icon.release();
    }

    void reselect_pid(uint32_t pid)
    {
        selectedIdx = -1;
        if (pid == 0) return;
        for (size_t i = 0; i < processes.size(); ++i)
        {
            if (processes[i].pid == pid) { selectedIdx = static_cast<int>(i); return; }
        }
    }

    void select_process(int idx)
    {
        selectedIdx   = idx;
        targetDisplay = processes[idx].display;
        char buf[32];
        snprintf(buf, sizeof(buf), "PID %lu", static_cast<unsigned long>(processes[idx].pid));
        targetPid = buf;
    }

    void begin_loading()
    {
        loadElapsed  = 0;
        loadProgress = 0;
        spinElapsed  = 0;
        statusText   = "Connecting to Minecraft";
        solutionText.clear();
        loadFailed   = false;
        retryRequested = false;
        statusFade.reset(0.16f);
        continueHover.snap(0.0f);
        transition_to(Screen::Loading, 1.0f);
    }

    void tick_closing(float dt)
    {
        closeTween += dt / CloseDuration;
        if (closeTween > 1.0f) closeTween = 1.0f;
    }

    bool should_close() const
    {
        return screen == Screen::Closing && closeTween >= 1.0f;
    }

    bool tick_splash(float dt)
    {
        splashElapsed += dt;

        if (splashElapsed < SplashHold)
        {
            logoTween = 0.0f;
            return false;
        }

        float tweenT = (splashElapsed - SplashHold) / SplashTween;
        logoTween = std::min(tweenT, 1.0f);

        if (splashElapsed >= SplashHold + SplashTween)
        {
            logoTween = 1.0f;
            return true;
        }
        return false;
    }
};

} // namespace mindless
