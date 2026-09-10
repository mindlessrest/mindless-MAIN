#pragma once
#include "app/process_list.hpp"
#include "core/anim.hpp"
#include <string>
#include <string_view>
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
    std::string statusText   = "Preparing";
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

    // Which phase the marker on the loading screen is sitting over, as a float so it can
    // be between two of them mid-slide.
    Tween phaseSlide;

    // Back link hover tween
    Tween backHover;
    Tween retryHover;

    static constexpr int MaxFieldLength = 48;

    // Caret and anchor are indices into text; a selection is whatever lies between them, so the
    // anchor staying put while the caret moves is all shift-selection needs to be.
    struct TextField
    {
        std::string text;
        int         caret  = 0;
        int         anchor = 0;
        float       scroll = 0.0f;

        int  length()        const { return static_cast<int>(text.size()); }
        bool has_selection() const { return caret != anchor; }
        int  sel_begin()     const { return caret < anchor ? caret : anchor; }
        int  sel_end()       const { return caret < anchor ? anchor : caret; }

        void move_to(int index, bool keepAnchor)
        {
            caret = index < 0 ? 0 : (index > length() ? length() : index);
            if (!keepAnchor) anchor = caret;
        }

        void select_all() { anchor = 0; caret = length(); }

        void erase_selection()
        {
            if (!has_selection()) return;
            int b = sel_begin();
            text.erase(static_cast<size_t>(b), static_cast<size_t>(sel_end() - b));
            caret = anchor = b;
        }

        std::string selected() const
        {
            if (!has_selection()) return {};
            int b = sel_begin();
            return text.substr(static_cast<size_t>(b), static_cast<size_t>(sel_end() - b));
        }

        // The atlas is byte-indexed over printable ASCII, so anything else would render as a
        // hole. Dropping it keeps what is shown equal to what is stored.
        void insert(std::string_view chars)
        {
            // Guarded, or holding a modifier that produces no character would wipe a selection.
            if (chars.empty()) return;

            erase_selection();
            for (char c : chars)
            {
                if (length() >= MaxFieldLength) break;
                unsigned char u = static_cast<unsigned char>(c);
                if (u < 32 || u > 126) continue;
                text.insert(text.begin() + caret, c);
                ++caret;
            }
            anchor = caret;
        }

        // Word motion stops where a run of non-spaces begins, which is where every text box the
        // user has already used puts it.
        int word_left() const
        {
            int i = caret;
            while (i > 0 && text[static_cast<size_t>(i - 1)] == ' ') --i;
            while (i > 0 && text[static_cast<size_t>(i - 1)] != ' ') --i;
            return i;
        }

        int word_right() const
        {
            int i = caret, n = length();
            while (i < n && text[static_cast<size_t>(i)] != ' ') ++i;
            while (i < n && text[static_cast<size_t>(i)] == ' ') ++i;
            return i;
        }
    };

    TextField username;
    TextField password;
    int       focusField = 0;
    float     caretPhase = 0.0f;

    Tween userFocus;
    Tween passFocus;
    Tween userHover;
    Tween passHover;
    Tween signInHover;
    Tween rememberHover;
    Tween rememberCheck;

    // Auth state
    bool        rememberMe     = true;
    bool        authInProgress = false;
    bool        authComplete   = false;
    std::string authError;
    std::string authToken;
    std::string authHwid;
    std::vector<uint8_t> dllBytes;

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
        rememberHover.speed   = 14.0f;
        rememberCheck.speed   = 18.0f;
        rememberCheck.snap(1.0f);
    }

    void advance_tweens(float dt)
    {
        uiElapsed += dt;

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

    TextField& active_field() { return focusField == 0 ? username : password; }

    void focus_field(int index)
    {
        focusField = index;
        caretPhase = 0.0f;
    }

    void sign_in()
    {
        caretPhase = 0.0f;
        signInHover.snap(0.0f);
        authInProgress = true;
        authComplete   = false;
        authError.clear();
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
        // Snapped, not eased: a retry should open on the first phase rather than sliding back
        // from wherever the failed attempt stopped.
        phaseSlide.snap(0.0f);
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
