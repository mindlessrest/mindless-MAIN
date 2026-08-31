#include "screens.hpp"
#include "app/process_list.hpp"
#include "window/window.hpp"
#include <windows.h>
#include <d3d11.h>
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <string>

namespace mindless
{

using namespace ui;

static float vcenter_text(const FontAtlas& f, float boxY, float boxH)
{
    float baseline = boxY + (boxH + f.capHeight()) * 0.5f;
    return baseline - f.ascender();
}

static void draw_text_centered(DrawList& dl, FontAtlas& font,
                                std::string_view text, float cx,
                                float lineTopY, Color color)
{
    std::string s(text);
    float w = font.measure_text_width(s.c_str());
    dl.draw_text(s, { cx - w * 0.5f, lineTopY }, color, font);
}

static void draw_text_in_box(DrawList& dl, FontAtlas& font,
                              std::string_view text, Rect box, Color color)
{
    std::string s(text);
    float tw = font.measure_text_width(s.c_str());
    float tx = box.x + (box.w - tw) * 0.5f;
    float ty = vcenter_text(font, box.y, box.h);
    dl.draw_text(s, { tx, ty }, color, font);
}

// A short segment sweeping left to right, off the end, and back round again.
//
// The segment is one pill, but it is drawn as two halves over the same mask so the colour can
// ramp up and then back down across it. A flat pill has hard vertical ends that read as a
// sliding block; fading both ends turns it into a light passing over the track.
static void draw_sweep_bar(DrawList& dl, Rect r, float elapsed,
                            Color track, Color fill, float alpha)
{
    float radius = r.h * 0.5f;
    dl.fill_rounded_rect(r, track.with_alpha(track.a * alpha), radius);

    const float period = 1.35f;
    float segW  = r.w * 0.34f;
    float phase = std::fmod(elapsed, period) / period;

    // Eased, so the segment is slowest at the two ends of its travel — which is exactly when
    // it is off the track and nobody can see it stall.
    float segX = r.x - segW + (r.w + segW) * ease_in_out_cubic(phase);

    Rect  seg  = { segX, r.y, segW, r.h };
    float mid  = segX + segW * 0.5f;

    Color edge = fill.with_alpha(0.0f);
    Color peak = fill.with_alpha(fill.a * alpha);

    dl.push_clip(r);
    dl.fill_rounded_rect_gradient(seg, { segX, r.y, segW * 0.5f, r.h }, edge, peak, radius);
    dl.fill_rounded_rect_gradient(seg, { mid,  r.y, segW * 0.5f, r.h }, peak, edge, radius);
    dl.pop_clip();
}

static bool draw_button(DrawList& dl, FontAtlas& fn, Rect r, std::string_view label,
                         const InputState& input, Tween& hover, float dt, float alpha,
                         bool accent)
{
    const Theme& t = g_theme;

    bool hovered = r.contains(input.mousePos);
    hover.set(hovered ? 1.0f : 0.0f);
    hover.advance(dt);
    float h = hover.value();

    bool pressed = hovered && input.lmbDown;
    Color bg = accent
        ? (pressed ? t.accentPress : t.accent.lerp(t.accentHover, h))
        : t.buttonBg.lerp(t.buttonHover, h);
    Color border = accent
        ? bg.darkened(0.28f)
        : t.buttonBorder.lerp(t.buttonBorder.lightened(0.12f), h);

    if (accent && h > 0.01f)
        dl.glow_rounded_rect(r, t.accent.with_alpha(0.2f * h * alpha), t.buttonRadius, 16.0f);

    dl.fill_rounded_rect(r, bg.with_alpha(bg.a * alpha), t.buttonRadius);
    dl.stroke_rounded_rect(r, border.with_alpha(border.a * alpha), t.buttonRadius, 1.0f);
    draw_text_in_box(dl, fn, label, r, (accent ? t.accentText : t.text).with_alpha(alpha));

    return hovered && input.lmbReleased;
}

bool draw_chrome(DrawList& dl, const InputState& input,
                 const Rect& wr, ScreenFonts fonts, Window* window,
                 AppState& state, float dt)
{
    const Theme& t = g_theme;

    const float pillH  = 26.0f;
    const float btnW   = 30.0f;
    const float pillW  = btnW * 2.0f;
    const float pillX  = wr.right() - pillW - 14.0f;
    const float pillY  = wr.y + 11.0f;
    const float radius = pillH * 0.5f;

    Rect pill = { pillX,        pillY, pillW, pillH };
    Rect mnR  = { pillX,        pillY, btnW,  pillH };
    Rect clR  = { pillX + btnW, pillY, btnW,  pillH };

    bool mnHov = mnR.contains(input.mousePos);
    bool clHov = clR.contains(input.mousePos);

    state.chromeMinHover.set(mnHov ? 1.0f : 0.0f);
    state.chromeCloseHover.set(clHov ? 1.0f : 0.0f);
    state.chromeMinHover.advance(dt);
    state.chromeCloseHover.advance(dt);

    float minT   = state.chromeMinHover.value();
    float closeT = state.chromeCloseHover.value();

    dl.fill_rounded_rect(pill, Color(0x1A1C22), radius);

    if (minT > 0.001f)
    {
        dl.push_clip(pill);
        dl.fill_rounded_rect({ mnR.x - 1.0f, mnR.y, mnR.w + 1.0f, mnR.h },
                             t.buttonHover.with_alpha(minT), radius);
        dl.pop_clip();
    }
    if (closeT > 0.001f)
    {
        dl.push_clip(pill);
        dl.fill_rounded_rect({ clR.x, clR.y, clR.w + 1.0f, clR.h },
                             t.buttonHover.with_alpha(closeT), radius);
        dl.pop_clip();
    }

    dl.stroke_rounded_rect(pill, Color(0x282B36), radius, 1.0f);

    float dashW = 7.0f;
    float dashY = mnR.y + mnR.h * 0.5f - 0.6f;
    dl.fill_rect({ mnR.x + (btnW - dashW) * 0.5f, dashY, dashW, 1.5f },
                 t.textSecond.lerp(t.text, minT * 0.6f));

    const char* xStr = "\xD7";
    float xw = fonts.normal.measure_text_width(xStr);
    float xt = vcenter_text(fonts.normal, clR.y, clR.h);
    dl.draw_text(xStr, { clR.x + (clR.w - xw) * 0.5f, xt },
                 t.textSecond.lerp(t.text, closeT * 0.6f), fonts.normal);

    if (mnR.contains(input.mousePos) && input.lmbReleased && window)
        window->minimize();

    return clR.contains(input.mousePos) && input.lmbReleased;
}

static void draw_splash(DrawList& dl, AppState& state,
                        ScreenFonts fonts, const Rect& wr,
                        const Image& logo, float dt)
{
    bool done  = state.tick_splash(dt);
    float ease = ease_out_quart(state.logoTween);

    float cx = wr.x + wr.w * 0.5f;
    float cy = wr.y + wr.h * 0.5f;

    const float splashSz = 64.0f;
    float splashW = logo.valid()
        ? splashSz * (static_cast<float>(logo.width) / static_cast<float>(logo.height))
        : splashSz;

    float imgX = cx - splashW * 0.5f;
    float imgY = cy - splashSz * 0.5f;

    float fadeIn  = ease_out_quad(std::min(state.splashElapsed / 0.3f, 1.0f));
    float fadeOut = 1.0f - ease_in_quart(ease);
    float alpha   = fadeIn * fadeOut;

    if (logo.valid())
        dl.draw_image(logo, { imgX, imgY, splashW, splashSz }, alpha);
    else
        draw_text_centered(dl, fonts.title, "Mindless", cx,
                           vcenter_text(fonts.title, imgY, splashSz),
                           g_theme.text.with_alpha(alpha));

    if (done)
        state.transition_to(Screen::Login, 1.0f);
}

static void draw_title_bar(DrawList& dl, ScreenFonts fonts,
                            const Rect& wr, const Image& logo, float alpha)
{
    const float pad    = 15.0f;
    const float iconSz = 20.0f;
    const float iconY  = wr.y + 13.0f;

    float textX = wr.x + pad;

    if (logo.valid())
    {
        float logoW = iconSz * (static_cast<float>(logo.width) / static_cast<float>(logo.height));
        dl.draw_image(logo, { wr.x + pad, iconY, logoW, iconSz }, alpha);
        textX += logoW + 9.0f;
    }

    dl.draw_text("mindless", { textX, vcenter_text(fonts.normal, iconY, iconSz) },
                 g_theme.textSecond.with_alpha(0.85f * alpha), fonts.normal);
}

static const float kRowHeight = 48.0f;
static const float kRowGap = 4.0f;
static const float kListGap = 14.0f;
static const int   kMaxVisibleRows = 4;

static const float kHeadingTop  = 42.0f;
static const float kHeadingRule = 10.0f;

// Every screen is headed the same way: an accent tick in the left gutter, the title, and a rule
// that dissolves as it runs right. The same tick marks the selected row further down.
static float draw_heading(DrawList& dl, FontAtlas& titleFont, float x, float top,
                          float width, std::string_view text, float alpha)
{
    const Theme& t  = g_theme;
    float        lh = titleFont.lineHeight();

    float tickH = titleFont.capHeight() + 2.0f;
    dl.fill_rounded_rect({ x - 12.0f, top + (lh - tickH) * 0.5f, 2.0f, tickH },
                         t.accent.with_alpha(0.9f * alpha), 1.0f);

    dl.draw_text(text, { x, vcenter_text(titleFont, top, lh) }, t.text.with_alpha(alpha), titleFont);

    Rect rule = { x - 12.0f, top + lh + kHeadingRule, width + 12.0f, 1.0f };
    dl.fill_rounded_rect_gradient(rule, rule,
                                  t.accent.with_alpha(0.30f * alpha),
                                  t.accent.with_alpha(0.0f), 0.5f);

    return rule.y;
}

static float list_top_offset(FontAtlas& titleFont)
{
    return kHeadingTop + titleFont.lineHeight() + kHeadingRule + 15.0f;
}



static bool draw_text_field(DrawList& dl, FontAtlas& fn, Rect r,
                             std::string_view placeholder, const std::string& text,
                             bool focused, bool mask, bool selected, Tween& hover, Tween& focus,
                             const InputState& input, float dt, float alpha, float caretPhase)
{
    const Theme& t = g_theme;

    bool hovered = r.contains(input.mousePos);
    hover.set(hovered ? 1.0f : 0.0f);
    hover.advance(dt);
    focus.set(focused ? 1.0f : 0.0f);
    focus.advance(dt);

    float h = hover.value();
    float f = focus.value();

    Color bg     = t.buttonBg.lerp(t.buttonHover, h * 0.55f);
    Color border = t.buttonBorder.lerp(t.accent, f);

    dl.fill_rounded_rect(r, bg.with_alpha(bg.a * alpha), t.buttonRadius);
    dl.stroke_rounded_rect(r, border.with_alpha(border.a * alpha), t.buttonRadius, 1.0f);

    if (f > 0.004f)
        dl.stroke_rounded_rect(r.inset(-2.5f),
                               t.accentDim.with_alpha(t.accentDim.a * f * alpha),
                               t.buttonRadius + 2.5f, 1.5f);

    const float padX = 12.0f;
    const float dotSz = 5.0f;
    const float dotStep = dotSz + 4.0f;

    float contentW = mask
        ? static_cast<float>(text.size()) * dotStep
        : fn.measure_text_width(text.c_str());

    float maxW  = r.w - padX * 2.0f;
    float textX = r.x + padX - std::max(0.0f, contentW - maxW);

    dl.push_clip({ r.x + 2.0f, r.y, r.w - 4.0f, r.h });

    if (selected && !text.empty())
    {
        float selH = fn.capHeight() + 9.0f;
        dl.fill_rounded_rect({ textX - 3.0f, r.y + (r.h - selH) * 0.5f, contentW + 6.0f, selH },
                             t.accent.with_alpha(0.24f * alpha), 3.0f);
    }

    if (text.empty() && !focused)
    {
        dl.draw_text(placeholder, { r.x + padX, vcenter_text(fn, r.y, r.h) },
                     t.textDisable.with_alpha(alpha), fn);
    }
    else if (mask)
    {
        float dotY = r.y + (r.h - dotSz) * 0.5f;
        for (size_t i = 0; i < text.size(); ++i)
            dl.fill_rounded_rect({ textX + static_cast<float>(i) * dotStep, dotY, dotSz, dotSz },
                                 t.text.with_alpha(0.85f * alpha), dotSz * 0.5f);
    }
    else
    {
        dl.draw_text(text, { textX, vcenter_text(fn, r.y, r.h) }, t.text.with_alpha(alpha), fn);
    }

    if (focused && !selected && std::fmod(caretPhase, 1.06f) < 0.58f)
    {
        float caretH = fn.capHeight() + 4.0f;
        dl.fill_rect({ textX + contentW + 1.0f, r.y + (r.h - caretH) * 0.5f, 1.0f, caretH },
                     t.text.with_alpha(0.9f * alpha));
    }

    dl.pop_clip();

    return hovered && input.lmbReleased;
}

static void draw_login_content(DrawList& dl, AppState& state,
                                const InputState& input, ScreenFonts fonts,
                                const Rect& wr, float alpha, float dt,
                                ID3D11Device* device)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;

    float pad    = t.windowPadding;
    float fieldX = wr.x + pad;
    float fieldW = wr.w - pad * 2.0f;

    float ruleY = draw_heading(dl, fonts.title, fieldX, wr.y + kHeadingTop, fieldW,
                               "Sign in", alpha);

    Rect btnR = { fieldX, wr.bottom() - pad - t.buttonH, fieldW, t.buttonH };

    const float fieldH  = 38.0f;
    const float hintGap = 14.0f;
    float groupH = fieldH * 2.0f + 10.0f + hintGap + fn.lineHeight();
    float groupY = ruleY + (btnR.y - ruleY - groupH) * 0.5f;

    Rect userR = { fieldX, groupY,                 fieldW, fieldH };
    Rect passR = { fieldX, userR.bottom() + 10.0f, fieldW, fieldH };

    if (draw_text_field(dl, fn, userR, "Username", state.username, state.focusField == 0,
                        false, state.selectAll && state.focusField == 0,
                        state.userHover, state.userFocus, input, dt, alpha, state.caretPhase))
        state.focus_field(0);

    if (draw_text_field(dl, fn, passR, "Password", state.password, state.focusField == 1,
                        true, state.selectAll && state.focusField == 1,
                        state.passHover, state.passFocus, input, dt, alpha, state.caretPhase))
        state.focus_field(1);

    FontAtlas& cap = fonts.caption;
    float hintY   = passR.bottom() + hintGap;
    float hintTop = vcenter_text(cap, hintY, cap.lineHeight());

    const char* hintLeft  = "Tab to switch";
    const char* hintRight = "Enter to sign in";
    float leftW  = cap.measure_text_width(hintLeft);
    float rightW = cap.measure_text_width(hintRight);
    float dotGap = 9.0f;
    float hintW  = leftW + dotGap * 2.0f + 3.0f + rightW;
    float hintX  = wr.x + (wr.w - hintW) * 0.5f;

    Color hintColor = t.textDisable.with_alpha(alpha);
    dl.draw_text(hintLeft, { hintX, hintTop }, hintColor, cap);
    dl.fill_rounded_rect({ hintX + leftW + dotGap, hintY + cap.lineHeight() * 0.5f - 1.5f, 3.0f, 3.0f },
                         hintColor, 1.5f);
    dl.draw_text(hintRight, { hintX + leftW + dotGap * 2.0f + 3.0f, hintTop }, hintColor, cap);

    bool submit = draw_button(dl, fn, btnR, "Sign in", input, state.signInHover, dt, alpha, true);

    if (dt > 0.0f)
    {
        state.caretPhase += dt;

        std::string& field = state.focusField == 0 ? state.username : state.password;

        if (input.key_down(VK_CONTROL) && input.key_pressed('A'))
            state.selectAll = !field.empty();

        if (!input.textInput.empty() && state.selectAll)
        {
            field.clear();
            state.selectAll = false;
        }

        for (char c : input.textInput)
            if (field.size() < AppState::MaxFieldLength) field.push_back(c);

        if (input.key_repeat(VK_BACK) && !field.empty())
        {
            if (state.selectAll)
            {
                field.clear();
                state.selectAll = false;
            }
            else
            {
                field.pop_back();
            }
            state.caretPhase = 0.0f;
        }

        if (input.key_pressed(VK_TAB))
            state.focus_field(state.focusField ^ 1);

        submit = submit || input.key_pressed(VK_RETURN);
    }

    if (submit)
    {
        state.release_process_icons();
        state.processes    = enumerate_targets(device);
        state.refreshAccum = 0.0f;
        state.selectedIdx  = -1;
        state.sign_in();
    }
}

static void draw_continue_button(DrawList& dl, FontAtlas& fn, Rect r, AppState& state,
                                  const InputState& input, float dt, float alpha)
{
    const Theme& t = g_theme;

    if (state.selectedIdx < 0)
    {
        dl.fill_rounded_rect(r, t.buttonBg.with_alpha(alpha), t.buttonRadius);
        dl.stroke_rounded_rect(r, t.buttonBorder.with_alpha(alpha), t.buttonRadius, 1.0f);
        draw_text_in_box(dl, fn, "Continue", r, t.textDisable.with_alpha(alpha));
        return;
    }

    if (draw_button(dl, fn, r, "Continue", input, state.continueHover, dt, alpha, true))
    {
        state.select_process(state.selectedIdx);
        state.begin_loading();
    }
}

static void draw_process_select_content(DrawList& dl, AppState& state,
                                         const InputState& input, ScreenFonts fonts,
                                         const Rect& wr, float alpha, float dt)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;

    float pad = t.windowPadding;

    float contentTop = wr.y + kHeadingTop;
    float listW   = wr.w - pad * 2.0f;
    float listX   = wr.x + pad;

    draw_heading(dl, fonts.title, listX, contentTop, listW, "Select Minecraft", alpha);

    float listTop = wr.y + list_top_offset(fonts.title);
    float rowH    = kRowHeight;
    float rowGap  = kRowGap;

    // Continue is pinned to the bottom of the panel; the list gets whatever is left. Laying the
    // button out after the last row is what pushed it off the window once a fifth one appeared.
    Rect  btnR    = { listX, wr.bottom() - pad - t.buttonH, listW, t.buttonH };
    Rect  listArea = { listX, listTop, listW, std::max(rowH, btnR.y - kListGap - listTop) };

    if (state.processes.empty())
    {
        Rect ghost = { listX, listTop, listW, kRowHeight };
        dl.fill_rounded_rect(ghost, Color(0x000000).with_alpha(0.16f * alpha), t.cardRadius);
        dl.stroke_rounded_rect(ghost, t.buttonBorder.with_alpha(0.5f * alpha), t.cardRadius, 1.0f);

        const float tileSz = 32.0f;
        float tileX = ghost.x + 11.0f;
        dl.fill_rounded_rect({ tileX, ghost.y + (ghost.h - tileSz) * 0.5f, tileSz, tileSz },
                             Color(0x000000).with_alpha(0.2f * alpha), 8.0f);

        FontAtlas& cap = fonts.caption;
        float textX  = tileX + tileSz + 11.0f;
        float gap    = 4.0f;
        float blockH = fn.capHeight() + gap + cap.capHeight();
        float blockY = ghost.y + (ghost.h - blockH) * 0.5f;
        float line1  = blockY - (fn.ascender() - fn.capHeight());
        float line2  = blockY + fn.capHeight() + gap - (cap.ascender() - cap.capHeight());

        dl.draw_text("No Minecraft instances found", { textX, line1 },
                     t.textSecond.with_alpha(alpha), fn);
        dl.draw_text("Launch the game and it will show up here", { textX, line2 },
                     t.textDisable.with_alpha(alpha), cap);

        draw_sweep_bar(dl, { listX, ghost.bottom() + 20.0f, listW, 2.0f },
                       state.uiElapsed, t.trackBg, t.accent, 0.55f * alpha);

        draw_continue_button(dl, fn, btnR, state, input, dt, alpha);
        return;
    }

    int count = static_cast<int>(state.processes.size());
    float contentH = count * rowH + (count - 1) * rowGap;
    float maxScroll = std::max(0.0f, contentH - listArea.h);

    if (dt > 0.0f && listArea.contains(input.mousePos) && input.mouseWheel != 0.0f)
        state.listScroll -= input.mouseWheel * (rowH + rowGap) * 0.75f;
    state.listScroll = clamp(state.listScroll, 0.0f, maxScroll);

    dl.push_clip(listArea);
    for (int i = 0; i < count; ++i)
    {
        float rowY = listTop - state.listScroll + static_cast<float>(i) * (rowH + rowGap);
        Rect  row  = { listX, rowY, listW, rowH };

        if (row.bottom() < listArea.y || row.y > listArea.bottom()) continue;

        bool hovered  = row.contains(input.mousePos) && listArea.contains(input.mousePos);
        bool selected = (state.selectedIdx == i);

        if (i < kMaxProcessRows)
        {
            state.rowHover[i].set(hovered ? 1.0f : 0.0f);
            state.rowHover[i].advance(dt);
        }
        float hov = (i < kMaxProcessRows) ? state.rowHover[i].value() : (hovered ? 1.0f : 0.0f);

        Color rowBg = selected
            ? t.selectionBg.lerp(t.buttonHover, hov * 0.3f)
            : t.buttonBg.lerp(t.buttonHover, hov);
        Color rowBorder = selected
            ? t.selectionRing.lerp(t.selectionRing.lightened(0.15f), hov * 0.4f)
            : t.buttonBorder.lerp(t.buttonBorder.lightened(0.1f), hov);

        dl.fill_rounded_rect(row, rowBg.with_alpha(rowBg.a * alpha),           t.cardRadius);
        dl.stroke_rounded_rect(row, rowBorder.with_alpha(rowBorder.a * alpha), t.cardRadius, 1.0f);

        const auto& pe = state.processes[i];

        if (selected)
        {
            float barH = row.h - 18.0f;
            dl.fill_rounded_rect({ row.x + 1.0f, row.y + (row.h - barH) * 0.5f, 2.0f, barH },
                                 t.accent.with_alpha(alpha), 1.0f);
        }

        const float tileSz  = 32.0f;
        const float iconSz  = 22.0f;
        const float iconPad = 11.0f;
        float tileX = row.x + iconPad;
        float tileY = row.y + (row.h - tileSz) * 0.5f;

        dl.fill_rounded_rect({ tileX, tileY, tileSz, tileSz },
                             Color(0x000000).with_alpha(0.22f * alpha), 8.0f);

        float iconX = tileX + (tileSz - iconSz) * 0.5f;
        float iconY = tileY + (tileSz - iconSz) * 0.5f;

        if (pe.icon.valid())
            dl.draw_image(pe.icon, { iconX, iconY, iconSz, iconSz }, alpha);
        else
            dl.fill_rounded_rect({ iconX, iconY, iconSz, iconSz },
                                 t.buttonBorder.with_alpha(alpha), iconSz * 0.5f);

        FontAtlas& cap = fonts.caption;
        float textX  = tileX + tileSz + iconPad;
        float gap    = 4.0f;
        float blockH = fn.capHeight() + gap + cap.capHeight();
        float blockY = row.y + (row.h - blockH) * 0.5f;
        float line1  = blockY - (fn.ascender() - fn.capHeight());
        float line2  = blockY + fn.capHeight() + gap - (cap.ascender() - cap.capHeight());

        dl.draw_text(pe.display,  { textX, line1 },
                     t.text.lerp(t.text.lightened(0.08f), hov * 0.5f).with_alpha(alpha), fn);
        dl.draw_text(pe.subtitle, { textX, line2 },
                     t.textSecond.with_alpha(0.9f * alpha), cap);

        if (hovered && input.lmbReleased)
            state.selectedIdx = i;
    }
    dl.pop_clip();

    if (maxScroll > 0.5f)
    {
        float trackH = listArea.h;
        float thumbH = std::max(20.0f, trackH * (listArea.h / contentH));
        float thumbY = listArea.y + (trackH - thumbH) * (state.listScroll / maxScroll);
        dl.fill_rounded_rect({ listArea.right() - 3.0f, thumbY, 3.0f, thumbH },
                             t.textDisable.with_alpha(0.9f * alpha), 1.5f);
    }

    draw_continue_button(dl, fn, btnR, state, input, dt, alpha);
}

static void draw_loading_content(DrawList& dl, AppState& state,
                                  const InputState& input, ScreenFonts fonts,
                                  const Rect& wr, float alpha, float dt)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;

    float cx   = wr.x + wr.w * 0.5f;
    float pad  = t.windowPadding;
    float barX = wr.x + pad;
    float barW = wr.w - pad * 2.0f;

    if (state.loadFailed)
    {
        float top = wr.y + wr.h * 0.28f;
        draw_text_centered(dl, fn, state.statusText, cx, top, t.danger.with_alpha(alpha));

        float helpTop = top + fn.lineHeight() + 12.0f;
        draw_text_centered(dl, fn, "How to fix", cx, helpTop, t.accent.with_alpha(alpha));
        draw_text_centered(dl, fn, state.solutionText, cx,
                           helpTop + fn.lineHeight() + 5.0f,
                           t.textSecond.with_alpha(alpha));

        float btnY  = wr.bottom() - pad - t.buttonH;
        float gap   = 8.0f;
        float halfW = (barW - gap) * 0.5f;
        Rect  backR  = { barX, btnY, halfW, t.buttonH };
        Rect  retryR = { barX + halfW + gap, btnY, halfW, t.buttonH };

        if (draw_button(dl, fn, backR, "Back", input, state.backHover, dt, alpha, false))
        {
            state.selectedIdx = -1;
            state.continueHover.snap(0.0f);
            state.backHover.snap(0.0f);
            state.transition_to(Screen::ProcessSelect, -1.0f);
        }
        if (draw_button(dl, fn, retryR, "Retry", input, state.retryHover, dt, alpha, true))
            state.retryRequested = true;
        return;
    }

    float top = wr.y + wr.h * 0.34f;
    draw_text_centered(dl, fn, state.targetDisplay.empty() ? "Minecraft" : state.targetDisplay,
                       cx, top, t.textSecond.with_alpha(alpha));

    float statusY = top + fn.lineHeight() + 4.0f;
    draw_text_centered(dl, fn, state.statusText, cx, statusY, t.text.with_alpha(alpha));

    float barY = statusY + fn.lineHeight() + 20.0f;
    draw_sweep_bar(dl, { barX, barY, barW, t.progressH }, state.spinElapsed,
                   t.trackBg, t.trackFill, alpha);

    float footY = barY + t.progressH + 12.0f;
    float pidW  = fonts.caption.measure_text_width(state.targetPid.c_str());
    dl.draw_text(state.targetPid, { cx - pidW * 0.5f, footY },
                 t.textDisable.with_alpha(alpha), fonts.caption);
}

static const float kSlideTravel = 0.11f;

void draw_screen(DrawList& dl, AppState& state, const InputState& input,
                 ScreenFonts fonts, const Rect& wr, const Image& logo,
                 float dt, bool& closeRequested, Window* window,
                 ID3D11Device* device)
{
    state.advance_tweens(dt);

    float bgOpacity = 1.0f;
    if (state.screen == Screen::Closing && state.closeTween > 0.4f)
        bgOpacity = clamp(1.0f - (state.closeTween - 0.4f) / 0.3f, 0.0f, 1.0f);

    dl.glow_rounded_rect(wr, g_theme.glowColor.with_alpha(g_theme.glowColor.a * bgOpacity),
                         g_theme.windowRadius, g_theme.glowSpread);
    dl.fill_rounded_rect(wr, g_theme.surface.with_alpha(bgOpacity), g_theme.windowRadius);
    dl.stroke_rounded_rect(wr, g_theme.surfaceBorder.with_alpha(bgOpacity), g_theme.windowRadius, 1.0f);
    dl.fill_rect({ wr.x + g_theme.windowRadius * 0.5f, wr.y + 1.0f,
                   wr.w - g_theme.windowRadius, 1.0f },
                 Color(0xFFFFFF).with_alpha(0.04f * bgOpacity));

    // The mark, blown up and cropped by the panel, gives the empty half of every screen
    // something to sit on. Clipped short of the corner radius so it never squares them off.
    if (logo.valid() && state.screen != Screen::Splash)
    {
        float markH = wr.h * 0.82f;
        float markW = markH * (static_cast<float>(logo.width) / static_cast<float>(logo.height));
        Rect  mark  = { wr.right() - markW * 0.55f, wr.bottom() - markH * 0.6f, markW, markH };

        dl.push_clip(wr.inset(g_theme.windowRadius));
        dl.draw_image(logo, mark, 0.035f * bgOpacity);
        dl.pop_clip();
    }

    if (state.screen == Screen::Splash)
    {
        draw_splash(dl, state, fonts, wr, logo, dt);
        if (state.logoTween >= 1.0f)
            closeRequested |= draw_chrome(dl, input, wr, fonts, window, state, dt);
        return;
    }

    bool transitioning = state.slideInT < 1.0f || state.slideOutT < 1.0f;

    // While a transition runs the screen underneath must not react to the cursor, or a click
    // lands on whichever of the two happens to be under it.
    static const InputState idleInput;
    const InputState& liveInput = transitioning ? idleInput : input;

    const float handover = AppState::SlideHandover;

    // Outgoing: leaves towards -slideDirection and is fully gone before the next one appears.
    if (state.slideOutT < handover)
    {
        float p         = ease_out_quad(state.slideOutT / handover);
        float outAlpha  = 1.0f - p;
        float outOffset = -p * wr.w * kSlideTravel * state.slideDirection;

        Rect outWr = wr.translated(outOffset, 0.0f);
        dl.push_clip(wr);
        draw_title_bar(dl, fonts, outWr, logo, outAlpha);
        switch (state.prevScreen)
        {
        case Screen::Login:
            draw_login_content(dl, state, idleInput, fonts, outWr, outAlpha, 0.0f, device);
            break;
        case Screen::ProcessSelect:
            draw_process_select_content(dl, state, idleInput, fonts, outWr, outAlpha, 0.0f);
            break;
        case Screen::Loading:
            draw_loading_content(dl, state, idleInput, fonts, outWr, outAlpha, 0.0f);
            break;
        case Screen::Splash:
        case Screen::Closing:
            break;
        }
        dl.pop_clip();
    }

    // Incoming: arrives from +slideDirection once the outgoing screen has cleared.
    {
        float raw = state.slideInT >= 1.0f
            ? 1.0f
            : clamp((state.slideInT - handover) / (1.0f - handover), 0.0f, 1.0f);

        float inAlpha  = ease_out_quad(raw);
        float inOffset = (1.0f - ease_out_quart(raw)) * wr.w * kSlideTravel * state.slideDirection;

        Rect inWr = wr.translated(inOffset, 0.0f);
        dl.push_clip(wr);
        draw_title_bar(dl, fonts, inWr, logo, inAlpha);
        switch (state.screen)
        {
        case Screen::Login:
            draw_login_content(dl, state, liveInput, fonts, inWr, inAlpha, dt, device);
            break;
        case Screen::ProcessSelect:
            draw_process_select_content(dl, state, liveInput, fonts, inWr, inAlpha, dt);
            break;
        case Screen::Loading:
            draw_loading_content(dl, state, liveInput, fonts, inWr, inAlpha, dt);
            break;
        case Screen::Closing:
            state.tick_closing(dt);
            {
                float uiAlpha = clamp(1.0f - state.closeTween * 3.0f, 0.0f, 1.0f);
                if (uiAlpha > 0.01f)
                    draw_loading_content(dl, state, idleInput, fonts, inWr, uiAlpha, 0.0f);
            }
            if (state.should_close()) closeRequested = true;
            break;
        case Screen::Splash:
            break;
        }
        dl.pop_clip();
    }

    if (state.screen != Screen::Closing)
        closeRequested |= draw_chrome(dl, input, wr, fonts, window, state, dt);
}

} // namespace mindless
