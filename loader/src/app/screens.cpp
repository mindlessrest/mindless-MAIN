#include "screens.hpp"
#include "app/process_list.hpp"
#include "window/window.hpp"
#include <windows.h>
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

    const float period = 3.4f;
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

static void draw_loading_status(DrawList& dl, FontAtlas& font,
                                const std::string& status, float cx,
                                float lineTopY, Color color, float elapsed)
{
    bool animated = status == "Gathering resources" || status == "Transforming layers";
    if (!animated)
    {
        draw_text_centered(dl, font, status, cx, lineTopY, color);
        return;
    }

    int dotCount = static_cast<int>(elapsed / 0.72f) % 4;
    std::string dots(static_cast<size_t>(dotCount), '.');
    std::string widest = status + "...";
    float startX = cx - font.measure_text_width(widest.c_str()) * 0.5f;
    dl.draw_text(status, { startX, lineTopY }, color, font);
    if (!dots.empty())
    {
        float dotX = startX + font.measure_text_width(status.c_str());
        dl.draw_text(dots, { dotX, lineTopY }, color, font);
    }
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

    const float buttonSize = 24.0f;
    const float gap = 4.0f;
    const float right = wr.right() - 14.0f;
    const float top = wr.y + 11.0f;
    Rect clR = { right - buttonSize, top, buttonSize, buttonSize };
    Rect mnR = { clR.x - gap - buttonSize, top, buttonSize, buttonSize };

    bool mnHov = mnR.contains(input.mousePos);
    bool clHov = clR.contains(input.mousePos);

    state.chromeMinHover.set(mnHov ? 1.0f : 0.0f);
    state.chromeCloseHover.set(clHov ? 1.0f : 0.0f);
    state.chromeMinHover.advance(dt);
    state.chromeCloseHover.advance(dt);

    float minT   = state.chromeMinHover.value();
    float closeT = state.chromeCloseHover.value();

    if (minT > 0.001f)
        dl.fill_rounded_rect(mnR, t.buttonHover.with_alpha(minT), t.buttonRadius);
    if (closeT > 0.001f)
        dl.fill_rounded_rect(clR, t.danger.with_alpha(0.16f * closeT), t.buttonRadius);

    const float dashW = 8.0f;
    const float dashH = 1.5f;
    dl.fill_rounded_rect({ mnR.x + (mnR.w - dashW) * 0.5f, mnR.y + (mnR.h - dashH) * 0.5f,
                           dashW, dashH },
                         t.textSecond.lerp(t.text, minT), dashH * 0.5f);

    const char* xStr = "\xC3\x97";
    float xw = fonts.normal.measure_text_width(xStr);
    float xt = vcenter_text(fonts.normal, clR.y, clR.h);
    dl.draw_text(xStr, { clR.x + (clR.w - xw) * 0.5f, xt },
                 t.textSecond.lerp(t.text, closeT), fonts.normal);

    if (mnHov && input.lmbReleased && window)
        window->minimize();

    return clHov && input.lmbReleased;
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

static void draw_title_bar(DrawList& dl, ScreenFonts fonts, const Rect& wr,
                           const Image& logo, float alpha)
{
    const float pad   = 18.0f;
    const float lineH = 20.0f;
    const float top   = wr.y + 13.0f;

    float wordX = wr.x + pad;
    if (logo.valid())
    {
        const float logoH = 16.0f;
        const float logoW = logoH * static_cast<float>(logo.width) / static_cast<float>(logo.height);
        dl.draw_image(logo, { wordX, top + (lineH - logoH) * 0.5f, logoW, logoH }, alpha);
        wordX += logoW + 8.0f;
    }
    dl.draw_text("Mindless", { wordX, vcenter_text(fonts.normal, top, lineH) },
                 g_theme.text.with_alpha(alpha), fonts.normal);
}

static const float kRowHeight = 48.0f;
static const float kRowGap = 4.0f;
static const float kListGap = 14.0f;

static const float kContentTop = 54.0f;



// Both glyphs are built from the primitives the draw list already has: a dome is a rounded
// rect with everything below its shoulder clipped away.
static void draw_user_glyph(DrawList& dl, Rect box, Color c)
{
    float cx = box.x + box.w * 0.5f;

    const float headSz = 5.0f;
    dl.fill_rounded_rect({ cx - headSz * 0.5f, box.y + 2.5f, headSz, headSz }, c, headSz * 0.5f);

    const float bodyW = 10.0f;
    float bodyY = box.y + 9.0f;
    dl.push_clip({ box.x, bodyY, box.w, 4.0f });
    dl.fill_rounded_rect({ cx - bodyW * 0.5f, bodyY, bodyW, 8.0f }, c, 4.0f);
    dl.pop_clip();
}

static void draw_lock_glyph(DrawList& dl, Rect box, Color c)
{
    float cx = box.x + box.w * 0.5f;

    const float shackleW = 7.0f;
    float shackleY = box.y + 1.5f;
    dl.push_clip({ box.x, shackleY, box.w, 5.0f });
    dl.stroke_rounded_rect({ cx - shackleW * 0.5f, shackleY, shackleW, 9.0f },
                           c, shackleW * 0.5f, 2.0f);
    dl.pop_clip();

    const float bodyW = 10.0f;
    dl.fill_rounded_rect({ cx - bodyW * 0.5f, box.y + 6.5f, bodyW, 7.0f }, c, 2.0f);
}

// Masked fields advance by a fixed dot pitch rather than by glyph, so both measurements go
// through here and the caret lands in the same place either way.
static float field_advance(FontAtlas& fn, const std::string& text, int from, int to,
                           bool mask, float dotStep)
{
    if (to <= from) return 0.0f;
    if (mask) return static_cast<float>(to - from) * dotStep;
    return fn.measure_text_width(text.substr(static_cast<size_t>(from),
                                             static_cast<size_t>(to - from)).c_str());
}

// The caret goes to whichever gap is nearest, so clicking the right half of a character puts it
// after that character rather than before it.
static int field_index_at(FontAtlas& fn, const std::string& text, bool mask, float dotStep, float x)
{
    int n = static_cast<int>(text.size());
    for (int i = 0; i < n; ++i)
    {
        float a = field_advance(fn, text, 0, i, mask, dotStep);
        float b = field_advance(fn, text, 0, i + 1, mask, dotStep);
        if (x < (a + b) * 0.5f) return i;
    }
    return n;
}

// Kept out of draw_text_field so every glow is laid down before any field fill: drawn inline,
// the lower field would paint over the half of the upper field's glow that spills onto it.
static void draw_field_glow(DrawList& dl, Rect r, float focus, float alpha)
{
    if (focus <= 0.004f) return;

    const Theme& t = g_theme;
    dl.glow_rounded_rect(r, t.accentGlow.with_alpha(0.16f * focus * alpha), t.buttonRadius, 20.0f);
}

static bool draw_text_field(DrawList& dl, FontAtlas& fn, Rect r,
                             std::string_view placeholder, AppState::TextField& field,
                             bool focused, bool mask, Tween& hover, Tween& focus,
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

    Color bg     = t.buttonBg.lerp(t.buttonHover, h * 0.55f).lerp(t.accent, f * 0.05f);
    Color border = t.buttonBorder.lerp(t.accent, f);

    dl.fill_rounded_rect(r, bg.with_alpha(bg.a * alpha), t.buttonRadius);
    dl.stroke_rounded_rect(r, border.with_alpha(border.a * alpha), t.buttonRadius, 1.0f);

    const float glyphBox = 14.0f;
    const float padL  = 12.0f + glyphBox + 10.0f;
    const float padR  = 12.0f;
    const float dotSz = 5.0f;
    const float dotStep = dotSz + 4.0f;

    Rect  glyphR = { r.x + 12.0f, r.y + (r.h - glyphBox) * 0.5f, glyphBox, glyphBox };
    Color glyphC = t.textDisable.lerp(t.accent, std::max(f, h * 0.35f));
    if (mask)
        draw_lock_glyph(dl, glyphR, glyphC.with_alpha(glyphC.a * alpha));
    else
        draw_user_glyph(dl, glyphR, glyphC.with_alpha(glyphC.a * alpha));

    float viewW    = r.w - padL - padR;
    float contentW = field_advance(fn, field.text, 0, field.length(), mask, dotStep);
    float caretX   = field_advance(fn, field.text, 0, field.caret, mask, dotStep);

    // Follow the caret, then give back any slack at the right, or deleting from the end leaves
    // the field scrolled with blank space against its own edge.
    if (caretX - field.scroll > viewW - 1.0f) field.scroll = caretX - viewW + 1.0f;
    if (caretX - field.scroll < 0.0f)         field.scroll = caretX;
    if (contentW - field.scroll < viewW)      field.scroll = std::max(0.0f, contentW - viewW);
    if (contentW <= viewW)                    field.scroll = 0.0f;

    float textX = r.x + padL - field.scroll;

    dl.push_clip({ r.x + padL - 2.0f, r.y, viewW + 4.0f, r.h });

    if (field.has_selection())
    {
        float a = field_advance(fn, field.text, 0, field.sel_begin(), mask, dotStep);
        float b = field_advance(fn, field.text, 0, field.sel_end(),   mask, dotStep);
        float selH = fn.capHeight() + 9.0f;
        dl.fill_rounded_rect({ textX + a, r.y + (r.h - selH) * 0.5f, b - a, selH },
                             t.accent.with_alpha(0.24f * alpha), 3.0f);
    }

    if (field.text.empty() && !focused)
    {
        dl.draw_text(placeholder, { r.x + padL, vcenter_text(fn, r.y, r.h) },
                     t.textDisable.with_alpha(alpha), fn);
    }
    else if (mask)
    {
        float dotY = r.y + (r.h - dotSz) * 0.5f;
        for (int i = 0; i < field.length(); ++i)
            dl.fill_rounded_rect({ textX + static_cast<float>(i) * dotStep, dotY, dotSz, dotSz },
                                 t.text.with_alpha(0.85f * alpha), dotSz * 0.5f);
    }
    else
    {
        dl.draw_text(field.text, { textX, vcenter_text(fn, r.y, r.h) },
                     t.text.with_alpha(alpha), fn);
    }

    if (focused && std::fmod(caretPhase, 1.06f) < 0.58f)
    {
        float caretH = fn.capHeight() + 4.0f;
        dl.fill_rect({ textX + caretX, r.y + (r.h - caretH) * 0.5f, 1.0f, caretH },
                     t.text.with_alpha(0.9f * alpha));
    }

    dl.pop_clip();

    if (hovered && input.lmbPressed)
    {
        field.move_to(field_index_at(fn, field.text, mask, dotStep,
                                     input.mousePos.x - (r.x + padL) + field.scroll),
                      input.key_down(VK_SHIFT));
    }

    return hovered && input.lmbReleased;
}

static std::string clipboard_text()
{
    if (!OpenClipboard(nullptr))
        return {};

    std::string out;

    if (HANDLE data = GetClipboardData(CF_UNICODETEXT))
    {
        if (const wchar_t* wide = static_cast<const wchar_t*>(GlobalLock(data)))
        {
            int n = WideCharToMultiByte(CP_UTF8, 0, wide, -1, nullptr, 0, nullptr, nullptr);
            if (n > 1)
            {
                out.resize(static_cast<size_t>(n));
                WideCharToMultiByte(CP_UTF8, 0, wide, -1, out.data(), n, nullptr, nullptr);
                out.pop_back();
            }
            GlobalUnlock(data);
        }
    }

    CloseClipboard();
    return out;
}

static void set_clipboard_text(const std::string& text)
{
    if (!OpenClipboard(nullptr))
        return;

    EmptyClipboard();

    int n = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, nullptr, 0);
    if (n > 0)
    {
        if (HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, static_cast<size_t>(n) * sizeof(wchar_t)))
        {
            void* dst = GlobalLock(mem);
            if (dst)
            {
                MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, static_cast<wchar_t*>(dst), n);
                GlobalUnlock(mem);
            }

            // The clipboard takes ownership of the block only once SetClipboardData succeeds.
            if (!dst || !SetClipboardData(CF_UNICODETEXT, mem))
                GlobalFree(mem);
        }
    }

    CloseClipboard();
}

static bool draw_checkbox(DrawList& dl, FontAtlas& cap, Rect r, const char* label,
                          bool checked, Tween& hover, Tween& checkAnim,
                          const InputState& input, float dt, float alpha)
{
    const Theme& t = g_theme;
    bool hovered = r.contains(input.mousePos);
    hover.set(hovered ? 1.0f : 0.0f);
    hover.advance(dt);
    checkAnim.set(checked ? 1.0f : 0.0f);
    checkAnim.advance(dt);

    float boxSize = 14.0f;
    Rect boxR = { r.x, r.y + (r.h - boxSize) * 0.5f, boxSize, boxSize };

    float h = hover.value();
    float c = checkAnim.value();

    Color boxBg = t.buttonBg.lerp(t.buttonHover, h).lerp(t.accent, c);
    Color boxBorder = t.buttonBorder.lerp(t.accent, c);

    if (c > 0.01f)
        dl.glow_rounded_rect(boxR, t.accent.with_alpha(0.25f * c * alpha), 3.0f, 6.0f);

    dl.fill_rounded_rect(boxR, boxBg.with_alpha(alpha), 3.0f);
    dl.stroke_rounded_rect(boxR, boxBorder.with_alpha(alpha), 3.0f, 1.0f);

    if (c > 0.01f)
    {
        float cx = boxR.x + boxR.w * 0.5f;
        float cy = boxR.y + boxR.h * 0.5f;
        dl.fill_rounded_rect({ cx - 3.0f * c, cy - 3.0f * c, 6.0f * c, 6.0f * c },
                             t.accentText.with_alpha(c * alpha), 1.5f);
    }

    float textX = boxR.right() + 7.0f;
    float textY = vcenter_text(cap, r.y, r.h);
    Color textColor = t.textSecond.lerp(t.text, h).with_alpha(alpha);
    dl.draw_text(label, { textX, textY }, textColor, cap);

    return hovered && input.lmbReleased;
}

static void draw_login_content(DrawList& dl, AppState& state,
                                const InputState& input, ScreenFonts fonts,
                                const Rect& wr, float alpha, float dt)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;
    FontAtlas&   cap= fonts.caption;

    float pad    = t.windowPadding;
    float fieldX = wr.x + pad;
    float fieldW = wr.w - pad * 2.0f;

    float contentTop = wr.y + kContentTop;

    Rect btnR = { fieldX, wr.bottom() - pad - t.buttonH, fieldW, t.buttonH };

    const float fieldH   = 38.0f;
    const float checkH   = 16.0f;
    const float checkGap = 9.0f;
    const float hintGap  = 8.0f;
    dl.draw_text("Welcome back", { fieldX, contentTop }, t.text.with_alpha(alpha), fonts.title);
    dl.draw_text("Sign in to continue to Mindless", { fieldX, contentTop + 25.0f },
                 t.textSecond.with_alpha(alpha), cap);

    float groupY = contentTop + 50.0f;

    Rect userR = { fieldX, groupY,                 fieldW, fieldH };
    Rect passR = { fieldX, userR.bottom() + 10.0f, fieldW, fieldH };

    draw_field_glow(dl, userR, state.userFocus.value(), alpha);
    draw_field_glow(dl, passR, state.passFocus.value(), alpha);

    if (draw_text_field(dl, fn, userR, "Username", state.username, state.focusField == 0,
                        false, state.userHover, state.userFocus, input, dt, alpha, state.caretPhase))
        state.focus_field(0);

    if (draw_text_field(dl, fn, passR, "Password", state.password, state.focusField == 1,
                        true, state.passHover, state.passFocus, input, dt, alpha, state.caretPhase))
        state.focus_field(1);

    float checkY = passR.bottom() + checkGap;
    Rect checkR  = { fieldX, checkY, 130.0f, checkH };

    if (draw_checkbox(dl, cap, checkR, "Remember me", state.rememberMe,
                      state.rememberHover, state.rememberCheck, input, dt, alpha))
    {
        state.rememberMe = !state.rememberMe;
    }

    float hintY   = checkR.bottom() + hintGap;
    float hintTop = vcenter_text(cap, hintY, cap.lineHeight());

    if (!state.authError.empty())
    {
        float errW = cap.measure_text_width(state.authError.c_str());
        float errX = wr.x + (wr.w - errW) * 0.5f;
        dl.draw_text(state.authError, { errX, hintTop }, t.danger.with_alpha(alpha), cap);
    }
    else
    {
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
    }

    std::string_view btnText = state.authInProgress ? "Signing in..." : "Sign in";
    bool submit = draw_button(dl, fn, btnR, btnText, input, state.signInHover, dt, alpha, true);

    if (dt > 0.0f)
    {
        state.caretPhase += dt;

        AppState::TextField& field = state.active_field();

        bool ctrl   = input.key_down(VK_CONTROL);
        bool shift  = input.key_down(VK_SHIFT);
        bool masked = state.focusField == 1;

        if (ctrl && input.key_pressed('A'))
            field.select_all();

        // Nothing reads a masked field back out, so the password never reaches the clipboard.
        bool cut = ctrl && input.key_pressed('X');
        if (!masked && field.has_selection() && (cut || input.key_pressed('C')))
        {
            set_clipboard_text(field.selected());
            if (cut)
            {
                field.erase_selection();
                state.caretPhase = 0.0f;
            }
        }

        if (ctrl && input.key_pressed('V'))
        {
            field.insert(clipboard_text());
            state.caretPhase = 0.0f;
        }

        // An unshifted arrow against a selection collapses it to the corresponding end rather
        // than stepping one further, which is what every other text box does.
        if (input.key_repeat(VK_LEFT))
        {
            if (field.has_selection() && !shift) field.move_to(field.sel_begin(), false);
            else field.move_to(ctrl ? field.word_left() : field.caret - 1, shift);
            state.caretPhase = 0.0f;
        }
        if (input.key_repeat(VK_RIGHT))
        {
            if (field.has_selection() && !shift) field.move_to(field.sel_end(), false);
            else field.move_to(ctrl ? field.word_right() : field.caret + 1, shift);
            state.caretPhase = 0.0f;
        }
        if (input.key_pressed(VK_HOME))
        {
            field.move_to(0, shift);
            state.caretPhase = 0.0f;
        }
        if (input.key_pressed(VK_END))
        {
            field.move_to(field.length(), shift);
            state.caretPhase = 0.0f;
        }

        field.insert(input.textInput);

        if (input.key_repeat(VK_BACK))
        {
            if (field.has_selection())
            {
                field.erase_selection();
            }
            else if (field.caret > 0)
            {
                int to = ctrl ? field.word_left() : field.caret - 1;
                field.text.erase(field.text.begin() + to, field.text.begin() + field.caret);
                field.move_to(to, false);
            }
            state.caretPhase = 0.0f;
        }

        if (input.key_repeat(VK_DELETE))
        {
            if (field.has_selection())
            {
                field.erase_selection();
            }
            else if (field.caret < field.length())
            {
                int to = ctrl ? field.word_right() : field.caret + 1;
                field.text.erase(field.text.begin() + field.caret, field.text.begin() + to);
            }
            state.caretPhase = 0.0f;
        }

        if (input.key_pressed(VK_TAB))
        {
            state.focus_field(state.focusField ^ 1);
            AppState::TextField& next = state.active_field();
            next.move_to(next.length(), false);
        }

        submit = submit || input.key_pressed(VK_RETURN);
    }

    if (submit && !state.authInProgress)
    {
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

    float listW   = wr.w - pad * 2.0f;
    float listX   = wr.x + pad;

    float headingY = wr.y + kContentTop;
    dl.draw_text("Select Minecraft", { listX, headingY }, t.text.with_alpha(alpha), fonts.title);
    dl.draw_text("Choose the instance to load into", { listX, headingY + 25.0f },
                 t.textSecond.with_alpha(alpha), fonts.caption);

    float listTop = headingY + 50.0f;
    float rowH    = kRowHeight;
    float rowGap  = kRowGap;

    // Continue is pinned to the bottom of the panel; the list gets whatever is left. Laying the
    // button out after the last row is what pushed it off the window once a fifth one appeared.
    Rect  btnR    = { listX, wr.bottom() - pad - t.buttonH, listW, t.buttonH };
    Rect  listArea = { listX, listTop, listW, std::max(rowH, btnR.y - kListGap - listTop) };

    if (state.processes.empty())
    {
        // Centred in the space the list would have used, so the panel does not read as a card
        // stranded at the top of an empty page.
        const float emptyH = kRowHeight + 20.0f + 2.0f;
        float       emptyY = listArea.y + (listArea.h - emptyH) * 0.5f;

        Rect ghost = { listX, emptyY, listW, kRowHeight };
        dl.fill_rounded_rect(ghost, t.background.with_alpha(0.45f * alpha), t.cardRadius);
        dl.stroke_rounded_rect(ghost, t.buttonBorder.with_alpha(0.5f * alpha), t.cardRadius, 1.0f);

        const float tileSz = 32.0f;
        float tileX = ghost.x + 11.0f;
        dl.fill_rounded_rect({ tileX, ghost.y + (ghost.h - tileSz) * 0.5f, tileSz, tileSz },
                             t.surfaceRaised.with_alpha(alpha), 8.0f);

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
        if (selected)
            dl.fill_rounded_rect({ row.x, row.y + 8.0f, 2.5f, row.h - 16.0f },
                                 t.accent.with_alpha(alpha), 1.25f);

        const auto& pe = state.processes[i];

        const float tileSz  = 32.0f;
        const float iconSz  = 22.0f;
        const float iconPad = 11.0f;
        float tileX = row.x + iconPad;
        float tileY = row.y + (row.h - tileSz) * 0.5f;

        // A chip raised off the row rather than a hole punched into it. The old fill was black
        // over an already dark row, so a monochrome mark had nothing to be seen against.
        Rect  tile   = { tileX, tileY, tileSz, tileSz };
        Color tileBg = t.buttonHover.lerp(t.buttonBorder, 0.35f);
        dl.fill_rounded_rect(tile, tileBg.with_alpha(tileBg.a * alpha), 9.0f);
        dl.stroke_rounded_rect(tile, t.divider.with_alpha(alpha), 9.0f, 1.0f);

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

    float top = wr.y + kContentTop;
    dl.draw_text("Loading Mindless", { barX, top }, t.text.with_alpha(alpha), fonts.title);
    dl.draw_text(state.targetDisplay.empty() ? "Minecraft" : state.targetDisplay,
                 { barX, top + 25.0f }, t.textSecond.with_alpha(alpha), fonts.caption);

    Rect statusCard = { barX, top + 58.0f, barW, 92.0f };
    dl.fill_rounded_rect(statusCard, t.surfaceRaised.with_alpha(alpha), t.cardRadius);
    dl.stroke_rounded_rect(statusCard, t.buttonBorder.with_alpha(alpha), t.cardRadius, 1.0f);

    float statusY = statusCard.y + 20.0f;
    draw_loading_status(dl, fn, state.statusText, cx, statusY,
                        t.text.with_alpha(alpha), state.spinElapsed);

    float barY = statusCard.bottom() - 25.0f;
    Rect track = { statusCard.x + 16.0f, barY, statusCard.w - 32.0f, t.progressH };
    dl.fill_rounded_rect(track, t.trackBg.with_alpha(alpha), t.progressH * 0.5f);
    float progress = clamp(state.loadProgress, 0.0f, 1.0f);
    if (progress > 0.001f)
        dl.fill_rounded_rect({ track.x, track.y, track.w * progress, track.h },
                             t.trackFill.with_alpha(alpha), track.h * 0.5f);

    float footY = statusCard.bottom() + 12.0f;
    float pidW  = fonts.caption.measure_text_width(state.targetPid.c_str());
    dl.draw_text(state.targetPid, { cx - pidW * 0.5f, footY },
                 t.textDisable.with_alpha(alpha), fonts.caption);
}

static const float kSlideTravel = 0.11f;

void draw_screen(DrawList& dl, AppState& state, const InputState& input,
                 ScreenFonts fonts, const Rect& wr, const Image& logo,
                 float dt, bool& closeRequested, Window* window)
{
    state.advance_tweens(dt);

    float bgOpacity = 1.0f;
    if (state.screen == Screen::Closing && state.closeTween > 0.4f)
        bgOpacity = clamp(1.0f - (state.closeTween - 0.4f) / 0.3f, 0.0f, 1.0f);

    dl.glow_rounded_rect(wr, g_theme.glowColor.with_alpha(g_theme.glowColor.a * bgOpacity),
                         g_theme.windowRadius, g_theme.glowSpread);
    dl.fill_rounded_rect(wr, g_theme.surface.with_alpha(bgOpacity), g_theme.windowRadius);
    dl.stroke_rounded_rect(wr, g_theme.surfaceBorder.with_alpha(bgOpacity), g_theme.windowRadius, 1.0f);
    // Inset by the full radius and faded at both ends: a straight line any closer to the
    // corner overhangs the curve at y+1 and leaves a bright nub outside the panel.
    Rect  sheen  = { wr.x + g_theme.windowRadius, wr.y + 1.0f,
                     wr.w - g_theme.windowRadius * 2.0f, 1.0f };
    Color sheenA = g_theme.text.with_alpha(0.0f);
    Color sheenB = g_theme.text.with_alpha(0.05f * bgOpacity);
    float sheenH = sheen.w * 0.5f;
    dl.fill_rounded_rect_gradient(sheen, { sheen.x, sheen.y, sheenH, sheen.h },
                                  sheenA, sheenB, 0.5f);
    dl.fill_rounded_rect_gradient(sheen, { sheen.x + sheenH, sheen.y, sheenH, sheen.h },
                                  sheenB, sheenA, 0.5f);

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
            draw_login_content(dl, state, idleInput, fonts, outWr, outAlpha, 0.0f);
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
            draw_login_content(dl, state, liveInput, fonts, inWr, inAlpha, dt);
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
