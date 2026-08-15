#include "window.hpp"
#include "resource.h"
#include <windowsx.h>
#include <dwmapi.h>

#pragma comment(lib, "dwmapi.lib")

// DWM backdrop types (Win11 22H2+). Guard against SDK versions that already define these.
#ifndef DWMWA_SYSTEMBACKDROP_TYPE
#define DWMWA_SYSTEMBACKDROP_TYPE 38
#endif
#ifndef DWMSBT_ACRYLIC
enum DWM_SYSTEMBACKDROP_TYPE_LOCAL
{
    DWMSBT_AUTO_L    = 0,
    DWMSBT_NONE_L    = 1,
    DWMSBT_MICA_L    = 2,
    DWMSBT_ACRYLIC   = 3,
    DWMSBT_TABBED_L  = 4,
};
#endif

namespace mindless
{

static const wchar_t* ClassName = L"MindlessWindow";

Window::~Window()
{
    destroy();
}

bool Window::create(const wchar_t* title, int width, int height)
{
    width_  = width;
    height_ = height;

    WNDCLASSEXW wc   = {};
    wc.cbSize        = sizeof(wc);
    wc.style         = 0;
    wc.lpfnWndProc   = wnd_proc;
    wc.hInstance     = GetModuleHandleW(nullptr);
    wc.hCursor       = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = ClassName;
    wc.hIcon         = LoadIconW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APPICON));
    wc.hIconSm       = wc.hIcon;
    RegisterClassExW(&wc);

    // WS_POPUP = no system frame.
    // WS_THICKFRAME = DWM shadow + resize hit-testing still works.
    DWORD style   = WS_POPUP | WS_THICKFRAME | WS_MINIMIZEBOX;
    DWORD exStyle = 0;

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int x = (screenW - width)  / 2;
    int y = (screenH - height) / 2;

    hwnd_ = CreateWindowExW(
        exStyle, ClassName, title,
        style,
        x, y, width, height,
        nullptr, nullptr, GetModuleHandleW(nullptr), this);

    if (!hwnd_) return false;

    // Extend DWM frame into the client area — 1px keeps the shadow.
    MARGINS margins = { 1, 1, 1, 1 };
    DwmExtendFrameIntoClientArea(hwnd_, &margins);

    // Try Win11 Acrylic backdrop first.
    int backdrop = DWMSBT_ACRYLIC;
    if (FAILED(DwmSetWindowAttribute(hwnd_, DWMWA_SYSTEMBACKDROP_TYPE,
                                     &backdrop, sizeof(backdrop))))
    {
        // Fallback: legacy blur-behind (works on Win10).
        DWM_BLURBEHIND bb = {};
        bb.dwFlags  = DWM_BB_ENABLE;
        bb.fEnable  = TRUE;
        bb.hRgnBlur = nullptr;
        DwmEnableBlurBehindWindow(hwnd_, &bb);
    }

    return true;
}

void Window::show()
{
    ShowWindow(hwnd_, SW_SHOW);
}

void Window::destroy()
{
    if (hwnd_) {
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
    UnregisterClassW(ClassName, GetModuleHandleW(nullptr));
}

bool Window::poll_events()
{
    input_.next_frame();

    MSG msg;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE))
    {
        if (msg.message == WM_QUIT) {
            shouldClose_ = true;
            return false;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    return !shouldClose_;
}

void Window::set_icon(HICON icon)
{
    SendMessageW(hwnd_, WM_SETICON, ICON_BIG,   reinterpret_cast<LPARAM>(icon));
    SendMessageW(hwnd_, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));
}

void Window::set_drag_rect(int x, int y, int w, int h)
{
    dragX_ = x;  dragY_ = y;
    dragW_ = w;  dragH_ = h;
}

void Window::minimize() const { ShowWindow(hwnd_, SW_MINIMIZE); }

void Window::close()
{
    shouldClose_ = true;
    PostQuitMessage(0);
}

LRESULT CALLBACK Window::wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    Window* self = nullptr;
    if (msg == WM_NCCREATE)
    {
        auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
        self = static_cast<Window*>(cs->lpCreateParams);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        self->hwnd_ = hwnd;
    }
    else
    {
        self = reinterpret_cast<Window*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    }

    if (self) return self->handle_message(msg, wp, lp);
    return DefWindowProcW(hwnd, msg, wp, lp);
}

LRESULT Window::handle_message(UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg)
    {
    case WM_NCCALCSIZE:
        // Return 0 with wParam=TRUE → client area == window area (no NC border).
        if (wp == TRUE) return 0;
        break;

    case WM_NCHITTEST:
    {
        LRESULT hit = DefWindowProcW(hwnd_, msg, wp, lp);
        if (hit == HTCLIENT)
        {
            POINT pt = { GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
            ScreenToClient(hwnd_, &pt);
            if (pt.x >= dragX_ && pt.x < dragX_ + dragW_ &&
                pt.y >= dragY_ && pt.y < dragY_ + dragH_)
            {
                return HTCAPTION;
            }
        }
        return hit;
    }

    case WM_CLOSE:
    case WM_DESTROY:
        shouldClose_ = true;
        PostQuitMessage(0);
        return 0;

    case WM_SIZE:
        if (wp != SIZE_MINIMIZED)
        {
            width_  = LOWORD(lp);
            height_ = HIWORD(lp);
            if (onResize) onResize(width_, height_);
        }
        return 0;

    case WM_MOUSEMOVE:
        input_.set_mouse_pos(static_cast<float>(GET_X_LPARAM(lp)),
                              static_cast<float>(GET_Y_LPARAM(lp)));
        return 0;

    case WM_LBUTTONDOWN:
        SetCapture(hwnd_);
        input_.set_mouse_button(0, true);
        return 0;

    case WM_LBUTTONUP:
        ReleaseCapture();
        input_.set_mouse_button(0, false);
        return 0;

    case WM_RBUTTONDOWN: input_.set_mouse_button(1, true);  return 0;
    case WM_RBUTTONUP:   input_.set_mouse_button(1, false); return 0;

    case WM_MOUSEWHEEL:
        input_.add_mouse_wheel(
            static_cast<float>(GET_WHEEL_DELTA_WPARAM(wp)) /
            static_cast<float>(WHEEL_DELTA));
        return 0;

    case WM_KEYDOWN:
    case WM_SYSKEYDOWN: input_.set_key(static_cast<int>(wp), true);  return 0;
    case WM_KEYUP:
    case WM_SYSKEYUP:   input_.set_key(static_cast<int>(wp), false); return 0;

    case WM_CHAR:
        if (wp >= 32 && wp != 127)
            input_.add_text(static_cast<wchar_t>(wp));
        return 0;

    case WM_ERASEBKGND:
        return 1;

    case WM_PAINT:
    {
        PAINTSTRUCT ps;
        BeginPaint(hwnd_, &ps);
        EndPaint(hwnd_, &ps);
        return 0;
    }
    }

    return DefWindowProcW(hwnd_, msg, wp, lp);
}

} // namespace mindless
