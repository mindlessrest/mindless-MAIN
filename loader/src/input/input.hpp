#pragma once
#include "core/math.hpp"
#include <cstdint>
#include <string>

namespace mindless
{

// Represents the complete input state for one frame.
// The application fills this from Win32 messages and passes it to the UI.
struct InputState
{
    Vec2  mousePos   = {};
    Vec2  mouseDelta = {};
    float mouseWheel = 0.0f;

    bool  lmbDown    = false;   // currently held
    bool  rmbDown    = false;

    bool  lmbPressed  = false;  // went down this frame
    bool  lmbReleased = false;  // went up this frame
    bool  rmbPressed  = false;
    bool  rmbReleased = false;

    // Text input accumulated this frame (UTF-8)
    std::string textInput;

    // Virtual key states — indexed by Win32 VK code
    bool keys[256] = {};
    bool keysPressed[256] = {};
    bool keysRepeat[256] = {};

    bool key_down(int vk)     const { return vk >= 0 && vk < 256 && keys[vk]; }
    bool key_pressed(int vk)  const { return vk >= 0 && vk < 256 && keysPressed[vk]; }

    // True on the initial press and again on every auto-repeat Windows delivers while held.
    bool key_repeat(int vk)   const { return vk >= 0 && vk < 256 && keysRepeat[vk]; }
};

// Manages frame-to-frame input transitions.
// The window feeds raw events; call next_frame() at the end of each frame.
class Input
{
public:
    void set_mouse_pos(float x, float y);
    void set_mouse_button(int button, bool down);  // 0=LMB, 1=RMB
    void add_mouse_wheel(float delta);
    void set_key(int vk, bool down);
    void add_text(wchar_t ch);
    void release_mouse();
    void release_all();
    void next_frame();

    const InputState& state() const { return current_; }

private:
    InputState current_ = {};
    InputState next_    = {};
    Vec2       lastMousePos_ = {};
    bool       firstFrame_   = true;
};

} // namespace mindless
