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
    Color color;
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
        cmds_.push_back({ DrawCmdType::FillRect, r, c });
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
        cmds_.push_back({ DrawCmdType::PushClip, r, {} });
    }

    void pop_clip()
    {
        cmds_.push_back({ DrawCmdType::PopClip, {}, {} });
    }

private:
    std::vector<DrawCmd> cmds_;
};

} // namespace mindless::ui
