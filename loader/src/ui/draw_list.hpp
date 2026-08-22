#pragma once
#include "core/color.hpp"
#include "core/math.hpp"
#include "renderer/image.hpp"
#include <string>
#include <vector>
#include <cstdint>

// Forward-declare so draw_list.hpp doesn't pull in D3D11 headers.
namespace mindless { class FontAtlas; }

namespace mindless::ui
{

enum class DrawCmdType : uint8_t
{
    FillRect,
    FillRoundedRect,
    GradientRoundedRect,
    StrokeRoundedRect,
    GlowRoundedRect,
    Text,
    Image,
    PushClip,
    PopClip,
};

struct DrawCmd
{
    DrawCmdType type;

    Rect  rect;
    Rect  rect2;
    Color color;
    Color color2;
    float radius   = 0;
    float thick    = 1;

    // Text
    std::string    text;
    FontAtlas*     atlas  = nullptr;

    // Image
    const mindless::Image* image = nullptr;
    float imageAlpha = 1.0f;
};

// A list of draw commands accumulated during a frame.
// The Renderer walks this list and executes each command.
class DrawList
{
public:
    void clear() { cmds_.clear(); }
    const std::vector<DrawCmd>& cmds() const { return cmds_; }

    void fill_rect(Rect r, Color c)
    {
        DrawCmd cmd;
        cmd.type  = DrawCmdType::FillRect;
        cmd.rect  = r;
        cmd.color = c;
        cmds_.push_back(cmd);
    }

    void fill_rounded_rect(Rect r, Color c, float radius)
    {
        DrawCmd cmd;
        cmd.type   = DrawCmdType::FillRoundedRect;
        cmd.rect   = r;
        cmd.color  = c;
        cmd.radius = radius;
        cmds_.push_back(cmd);
    }

    // Fills the overlap of `shape` and `cover`, ramping left colour to right colour across it.
    void fill_rounded_rect_gradient(Rect shape, Rect cover, Color left, Color right, float radius)
    {
        DrawCmd cmd;
        cmd.type   = DrawCmdType::GradientRoundedRect;
        cmd.rect   = shape;
        cmd.rect2  = cover;
        cmd.color  = left;
        cmd.color2 = right;
        cmd.radius = radius;
        cmds_.push_back(cmd);
    }

    // A soft halo falling off outwards from the shape's edge.
    void glow_rounded_rect(Rect r, Color c, float radius, float spread)
    {
        DrawCmd cmd;
        cmd.type   = DrawCmdType::GlowRoundedRect;
        cmd.rect   = r;
        cmd.color  = c;
        cmd.radius = radius;
        cmd.thick  = spread;
        cmds_.push_back(cmd);
    }

    void stroke_rounded_rect(Rect r, Color c, float radius, float thickness = 1.0f)
    {
        DrawCmd cmd;
        cmd.type   = DrawCmdType::StrokeRoundedRect;
        cmd.rect   = r;
        cmd.color  = c;
        cmd.radius = radius;
        cmd.thick  = thickness;
        cmds_.push_back(cmd);
    }

    void draw_text(std::string_view str, Vec2 pos, Color c, FontAtlas& atlas)
    {
        DrawCmd cmd;
        cmd.type  = DrawCmdType::Text;
        cmd.rect  = { pos.x, pos.y, 0, 0 };
        cmd.color = c;
        cmd.text  = std::string(str);
        cmd.atlas = &atlas;
        cmds_.push_back(std::move(cmd));
    }

    void draw_image(const mindless::Image& img, Rect dest, float alpha = 1.0f)
    {
        DrawCmd cmd;
        cmd.type       = DrawCmdType::Image;
        cmd.rect       = dest;
        cmd.image      = &img;
        cmd.imageAlpha = alpha;
        cmds_.push_back(cmd);
    }

    void push_clip(Rect r)
    {
        DrawCmd cmd;
        cmd.type = DrawCmdType::PushClip;
        cmd.rect = r;
        cmds_.push_back(cmd);
    }

    void pop_clip()
    {
        DrawCmd cmd;
        cmd.type = DrawCmdType::PopClip;
        cmds_.push_back(cmd);
    }

private:
    std::vector<DrawCmd> cmds_;
};

} // namespace mindless::ui
