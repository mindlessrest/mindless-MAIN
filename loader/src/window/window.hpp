#pragma once
#include "input/input.hpp"
#include <Windows.h>
#include <functional>

namespace mindless
{

// A borderless Win32 window.
// No caption, no system frame — the application draws its own chrome.
// Dragging is handled by returning HTCAPTION from WM_NCHITTEST over the
// drag region set by set_drag_rect().
class Window
{
public:
    Window() = default;
    ~Window();

    Window(const Window&) = delete;
    Window& operator=(const Window&) = delete;

    bool create(const wchar_t* title, int width, int height);
    void show();
    void destroy();

    // Pump messages. Returns false when the window should close.
    bool poll_events();

    void set_drag_rect(int x, int y, int w, int h);
    void set_icon(HICON icon);   // Sets both big and small window icon.

    void minimize() const;
    void close();

    HWND hwnd()          const { return hwnd_; }
    int  width()         const { return width_; }
    int  height()        const { return height_; }
    bool should_close()  const { return shouldClose_; }

    Input& input() { return input_; }

    // Fired when the client area changes size.
    std::function<void(int, int)> onResize;

private:
    HWND  hwnd_        = nullptr;
    int   width_       = 0;
    int   height_      = 0;
    bool  shouldClose_ = false;

    // Drag region in client coordinates — reported as HTCAPTION.
    int   dragX_ = 0, dragY_ = 0, dragW_ = 0, dragH_ = 0;

    Input input_;

    static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    LRESULT handle_message(UINT msg, WPARAM wp, LPARAM lp);
};

} // namespace mindless
