#pragma once
#include "app/app_state.hpp"
#include "core/anim.hpp"
#include "core/math.hpp"
#include "input/input.hpp"
#include "renderer/image.hpp"
#include "ui/draw_list.hpp"
#include "ui/theme.hpp"
#include "renderer/font_atlas.hpp"
#include <d3d11.h>

namespace mindless
{

class Window;

struct ScreenFonts
{
    FontAtlas& normal;
    FontAtlas& title;
};

// Panel height that fits the current list, so the window can be sized to its contents rather
// than leaving a gap under one row or cutting the fifth one off.
float process_select_panel_height(int rowCount, FontAtlas& font);

bool draw_chrome(ui::DrawList& dl, const InputState& input,
                 const Rect& windowRect, ScreenFonts fonts, Window* window,
                 AppState& state, float dt);

void draw_screen(ui::DrawList& dl, AppState& state, const InputState& input,
                 ScreenFonts fonts, const Rect& windowRect, const Image& logo,
                 float dt, bool& closeRequested, Window* window,
                 ID3D11Device* device);

} // namespace mindless
