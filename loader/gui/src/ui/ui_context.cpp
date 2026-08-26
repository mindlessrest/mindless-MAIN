#include "ui_context.hpp"
#include <cstring>
#include <algorithm>

namespace mindless::ui
{

void UiContext::begin(Renderer& renderer, const InputState& input, FontAtlas& font, FontAtlas& fontTitle)
{
    renderer_   = &renderer;
    input_      = &input;
    font_       = &font;
    fontTitle_  = &fontTitle;
    activePanel_ = nullptr;
}

void UiContext::end()
{
    // Nothing persistent to flush at context level — panels handle their own.
}

uint32_t UiContext::hash_id(std::string_view s)
{
    uint32_t hash = 2166136261u;
    for (char ch : s)
        hash = (hash ^ static_cast<uint32_t>(static_cast<unsigned char>(ch))) * 16777619u;
    return hash;
}

void UiContext::begin_panel(std::string_view id, Rect bounds)
{
    // Allocate on stack — callers must nest panels properly (no re-entry).
    panelStorage_.id      = id;
    panelStorage_.bounds  = bounds;
    panelStorage_.cursor  = { bounds.x + g_theme.panelPadding, bounds.y + g_theme.panelPadding };
    panelStorage_.scroll  = 0;
    panelStorage_.open    = true;
    activePanel_ = &panelStorage_;

    // Draw panel background
    renderer_->draw_rounded_rect(bounds, g_theme.panel, g_theme.cornerRadius);
    renderer_->draw_rounded_rect_border(bounds, g_theme.panelBorder, g_theme.cornerRadius, g_theme.borderWidth);
}

void UiContext::end_panel()
{
    activePanel_ = nullptr;
}

Rect UiContext::next_rect(float height)
{
    if (!activePanel_) return {};

    float x = activePanel_->cursor.x;
    float y = activePanel_->cursor.y;
    float w = activePanel_->bounds.w - g_theme.panelPadding * 2;

    activePanel_->cursor.y += height + g_theme.itemSpacing;
    return { x, y, w, height };
}

WidgetState UiContext::check_widget(Rect r, uint32_t id)
{
    WidgetState s;
    Vec2 mouse = input_->mousePos;

    s.hovered = r.contains(mouse);

    if (s.hovered && input_->lmbPressed)
        activeId_ = id;

    if (activeId_ == id)
        s.pressed = true;

    if (activeId_ == id && input_->lmbReleased)
    {
        activeId_ = 0;
        if (s.hovered) s.clicked = true;
    }

    if (s.hovered) hotId_ = id;

    return s;
}

FontAtlas& UiContext::font_for_size(float size)
{
    if (size >= g_theme.fontSizeTitle * 0.9f)
        return *fontTitle_;
    return *font_;
}

void UiContext::text(std::string_view str, float fontSize)
{
    FontAtlas& f = fontSize > 0 ? font_for_size(fontSize) : *font_;
    float lineH  = f.lineHeight();
    Rect r = next_rect(lineH);

    std::string s(str);
    renderer_->draw_text(s.c_str(), { r.x, r.y }, g_theme.text, f);
}

void UiContext::text_colored(std::string_view str, Color color, float fontSize)
{
    FontAtlas& f = fontSize > 0 ? font_for_size(fontSize) : *font_;
    float lineH  = f.lineHeight();
    Rect r = next_rect(lineH);

    std::string s(str);
    renderer_->draw_text(s.c_str(), { r.x, r.y }, color, f);
}

void UiContext::label(std::string_view str)
{
    float lineH = font_->lineHeight();
    Rect r = next_rect(lineH);

    std::string s(str);
    renderer_->draw_text(s.c_str(), { r.x, r.y }, g_theme.textSecond, *font_);
}

void UiContext::separator()
{
    Rect r = next_rect(1.0f);
    renderer_->draw_rect(r, g_theme.panelBorder);
    // Add a bit of extra space around it
    activePanel_->cursor.y += g_theme.itemSpacing;
}

void UiContext::spacing(float pixels)
{
    float amount = pixels > 0 ? pixels : g_theme.itemSpacing;
    if (activePanel_)
        activePanel_->cursor.y += amount;
}

WidgetState UiContext::draw_button_base(Rect r, Color bg, Color border, float radius)
{
    renderer_->draw_rounded_rect(r, bg, radius);
    renderer_->draw_rounded_rect_border(r, border, radius, g_theme.borderWidth);
    return {};
}

bool UiContext::button(std::string_view lbl)
{
    Rect r = next_rect(g_theme.buttonHeight);
    uint32_t id = hash_id(lbl);
    WidgetState s = check_widget(r, id);

    Color bg = s.pressed ? g_theme.accentPress
             : s.hovered ? g_theme.accentHover
             :              g_theme.accent;

    Color border = bg.darkened(0.2f);

    renderer_->draw_rounded_rect(r, bg, g_theme.smallRadius);
    renderer_->draw_rounded_rect_border(r, border, g_theme.smallRadius, g_theme.borderWidth);

    // Center label
    std::string text(lbl);
    float textW = font_->measure_text_width(text.c_str());
    float textX = r.x + (r.w - textW) * 0.5f;
    float textY = r.y + (r.h - font_->lineHeight()) * 0.5f;
    renderer_->draw_text(text.c_str(), { textX, textY }, g_theme.text, *font_);

    return s.clicked;
}

bool UiContext::button_secondary(std::string_view lbl)
{
    Rect r = next_rect(g_theme.buttonHeight);
    uint32_t id = hash_id(lbl);
    WidgetState s = check_widget(r, id);

    Color bg = s.pressed ? g_theme.buttonPress
             : s.hovered ? g_theme.buttonHover
             :              g_theme.buttonBg;

    renderer_->draw_rounded_rect(r, bg, g_theme.smallRadius);
    renderer_->draw_rounded_rect_border(r, g_theme.buttonBorder, g_theme.smallRadius, g_theme.borderWidth);

    std::string text(lbl);
    float textW = font_->measure_text_width(text.c_str());
    float textX = r.x + (r.w - textW) * 0.5f;
    float textY = r.y + (r.h - font_->lineHeight()) * 0.5f;
    renderer_->draw_text(text.c_str(), { textX, textY }, g_theme.text, *font_);

    return s.clicked;
}

void UiContext::checkbox(std::string_view lbl, bool* value)
{
    float h = std::max(g_theme.checkboxSize, font_->lineHeight());
    Rect r = next_rect(h);
    uint32_t id = hash_id(lbl);
    WidgetState s = check_widget(r, id);

    if (s.clicked) *value = !(*value);

    float sz = g_theme.checkboxSize;
    Rect box = { r.x, r.y + (h - sz) * 0.5f, sz, sz };

    Color boxBg = *value ? g_theme.accent
                : s.hovered ? g_theme.buttonHover
                :              g_theme.checkBg;

    Color boxBorder = *value ? g_theme.accent.darkened(0.2f) : g_theme.checkBorder;

    renderer_->draw_rounded_rect(box, boxBg, g_theme.smallRadius * 0.5f);
    renderer_->draw_rounded_rect_border(box, boxBorder, g_theme.smallRadius * 0.5f, g_theme.borderWidth);

    // Draw checkmark as two rects when checked
    if (*value)
    {
        float mx = box.x + box.w * 0.2f;
        float my = box.y + box.h * 0.55f;
        float t = 2.0f;

        // Short arm of check
        renderer_->draw_rect({ mx,       my,       box.w*0.3f,  t }, g_theme.text);
        // Long arm
        renderer_->draw_rect({ mx + box.w*0.25f, my - box.h*0.3f, t, box.h*0.45f }, g_theme.text);
    }

    // Label to the right
    std::string text(lbl);
    float textX = box.right() + g_theme.itemSpacing;
    float textY = r.y + (h - font_->lineHeight()) * 0.5f;

    Color labelColor = s.hovered ? g_theme.text : g_theme.textSecond;
    renderer_->draw_text(text.c_str(), { textX, textY }, labelColor, *font_);
}

void UiContext::slider(std::string_view lbl, float* value, float minVal, float maxVal)
{
    // Label row
    {
        float lineH = font_->lineHeight();
        Rect labelRect = next_rect(lineH);
        std::string text(lbl);
        renderer_->draw_text(text.c_str(), { labelRect.x, labelRect.y }, g_theme.textSecond, *font_);

        // Value on the right
        char valBuf[32];
        snprintf(valBuf, sizeof(valBuf), "%.2f", static_cast<double>(*value));
        float valW = font_->measure_text_width(valBuf);
        renderer_->draw_text(valBuf, { labelRect.right() - valW, labelRect.y }, g_theme.textSecond, *font_);
    }

    float h = g_theme.sliderHeight;
    Rect r = next_rect(h);
    uint32_t id = hash_id(lbl);

    float trackY  = r.y + (h - 4.0f) * 0.5f;
    Rect  track   = { r.x, trackY, r.w, 4.0f };

    float thumbSz = g_theme.sliderThumb;
    float range   = maxVal - minVal;
    float t       = (range > 0) ? (*value - minVal) / range : 0;
    float thumbX  = r.x + t * (r.w - thumbSz);
    Rect  thumb   = { thumbX, r.y + (h - thumbSz) * 0.5f, thumbSz, thumbSz };

    WidgetState s = check_widget(r, id);

    // Dragging
    if (s.pressed && input_->lmbDown && range > 0)
    {
        float relX  = input_->mousePos.x - r.x - thumbSz * 0.5f;
        float newT  = relX / (r.w - thumbSz);
        *value = minVal + mindless::clamp(newT, 0.0f, 1.0f) * range;
        t = (*value - minVal) / range;
        thumbX = r.x + t * (r.w - thumbSz);
        thumb.x = thumbX;
    }

    // Draw track
    renderer_->draw_rounded_rect(track, g_theme.track, 2.0f);

    // Filled portion
    Rect filled = { track.x, track.y, thumbX + thumbSz * 0.5f - track.x, track.h };
    renderer_->draw_rounded_rect(filled, g_theme.accent, 2.0f);

    // Thumb
    Color thumbColor = s.hovered || s.pressed ? g_theme.thumbHover : g_theme.thumb;
    renderer_->draw_rounded_rect(thumb, thumbColor, thumbSz * 0.5f);
    renderer_->draw_rounded_rect_border(thumb, thumbColor.darkened(0.2f), thumbSz * 0.5f, g_theme.borderWidth);
}

} // namespace mindless::ui
