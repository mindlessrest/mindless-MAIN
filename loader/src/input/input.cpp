#include "input.hpp"
#include <windows.h>

namespace mindless
{

void Input::set_mouse_pos(float x, float y)
{
    next_.mousePos = { x, y };
}

void Input::set_mouse_button(int button, bool down)
{
    if (button == 0) {
        bool was = next_.lmbDown;
        next_.lmbDown = down;
        if (down && !was)  next_.lmbPressed  = true;
        if (!down && was)  next_.lmbReleased = true;
    } else if (button == 1) {
        bool was = next_.rmbDown;
        next_.rmbDown = down;
        if (down && !was)  next_.rmbPressed  = true;
        if (!down && was)  next_.rmbReleased = true;
    }
}

void Input::add_mouse_wheel(float delta)
{
    next_.mouseWheel += delta;
}

void Input::set_key(int vk, bool down)
{
    if (vk < 0 || vk >= 256) return;
    bool was = next_.keys[vk];
    next_.keys[vk] = down;
    if (down)         next_.keysRepeat[vk]  = true;
    if (down && !was) next_.keysPressed[vk] = true;
}

void Input::add_text(wchar_t ch)
{
    // Convert single wide char to UTF-8 and append
    char buf[5] = {};
    int n = WideCharToMultiByte(CP_UTF8, 0, &ch, 1, buf, sizeof(buf) - 1, nullptr, nullptr);
    if (n > 0)
        next_.textInput.append(buf, static_cast<size_t>(n));
}

void Input::release_mouse()
{
    next_.lmbPressed = false;
    next_.rmbPressed = false;
    set_mouse_button(0, false);
    set_mouse_button(1, false);
}

void Input::release_all()
{
    release_mouse();

    for (int vk = 0; vk < 256; ++vk)
    {
        next_.keys[vk] = false;
        next_.keysPressed[vk] = false;
        next_.keysRepeat[vk] = false;
    }

    next_.mouseWheel = 0.0f;
    next_.textInput.clear();
}

void Input::next_frame()
{
    Vec2 prev = firstFrame_ ? next_.mousePos : current_.mousePos;
    firstFrame_ = false;

    current_ = next_;
    current_.mouseDelta = { next_.mousePos.x - prev.x, next_.mousePos.y - prev.y };

    // Clear per-frame events for next frame's accumulation
    next_.lmbPressed  = false;
    next_.lmbReleased = false;
    next_.rmbPressed  = false;
    next_.rmbReleased = false;
    next_.mouseWheel  = 0.0f;
    next_.textInput.clear();

    for (int i = 0; i < 256; ++i)
    {
        next_.keysPressed[i] = false;
        next_.keysRepeat[i]  = false;
    }
}

} // namespace mindless
