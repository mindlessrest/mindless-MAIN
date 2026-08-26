#pragma once
#include "core/math.hpp"
#include "input/input.hpp"
#include "renderer/d3d11_renderer.hpp"
#include "renderer/font_atlas.hpp"
#include "ui/theme.hpp"
#include <string>
#include <string_view>
#include <functional>

namespace mindless::ui
{

// Widget interaction result
struct WidgetState
{
    bool hovered  = false;
    bool pressed  = false;  // currently held down
    bool clicked  = false;  // released this frame
    bool focused  = false;
};

// A panel is a positioned, scrollable container.
struct Panel
{
    std::string id;
    Rect        bounds;     // screen rect
    Vec2        cursor;     // current layout position inside panel
    float       contentH;  // total content height (for scroll)
    float       scroll;    // current scroll offset
    bool        open;
};

// UiContext ties rendering, input, and layout together.
// It is immediate-mode: rebuild every frame.
class UiContext
{
public:
    UiContext() = default;

    // Must call each frame, in order.
    void begin(Renderer& renderer, const InputState& input, FontAtlas& font, FontAtlas& fontTitle);
    void end();

    // --- Layout ---
    // Opens a positioned panel. Must be matched with end_panel().
    void begin_panel(std::string_view id, Rect bounds);
    void end_panel();

    // --- Widgets ---
    void  text(std::string_view str, float fontSize = 0);  // 0 = normal
    void  text_colored(std::string_view str, Color color, float fontSize = 0);
    void  label(std::string_view str);   // secondary-colored smaller text
    void  separator();
    void  spacing(float pixels = 0);     // 0 = default item spacing

    bool  button(std::string_view label);
    bool  button_secondary(std::string_view label);

    void  checkbox(std::string_view label, bool* value);
    void  slider(std::string_view label, float* value, float minVal, float maxVal);

    // --- Direct draw helpers (for custom rendering) ---
    Renderer& renderer() { return *renderer_; }

private:
    Renderer*       renderer_   = nullptr;
    const InputState* input_    = nullptr;
    FontAtlas*      font_       = nullptr;
    FontAtlas*      fontTitle_  = nullptr;

    Panel           panelStorage_;  // single panel slot (no nesting needed)
    Panel*          activePanel_ = nullptr;

    // Unique ID for input focus/hover tracking (just the label string hash)
    uint32_t        hotId_   = 0;  // hovered
    uint32_t        activeId_= 0;  // being pressed

    // Returns the next widget rect and advances the cursor.
    Rect  next_rect(float height);

    // Check hover/press for a rect and id.
    WidgetState check_widget(Rect r, uint32_t id);

    // FNV-1a hash of a string — good enough for widget IDs.
    static uint32_t hash_id(std::string_view s);

    // Helper: draw a button-shaped widget, return state.
    WidgetState draw_button_base(Rect r, Color bg, Color border, float radius);

    FontAtlas& font_for_size(float size);
};

} // namespace mindless::ui
