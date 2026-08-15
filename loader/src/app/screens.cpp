#include "screens.hpp"
#include "app/process_list.hpp"
#include "window/window.hpp"
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

static void draw_shimmer_bar(DrawList& dl, Rect r, float elapsed,
                              Color track, Color fill, float alpha)
{
    float radius = r.h * 0.5f;
    dl.fill_rounded_rect(r, track.with_alpha(track.a * alpha), radius);

    const float period = 1.4f;
    const float shimW  = r.w * 0.38f;
    float phase = std::fmod(elapsed, period) / period;
    float ease  = phase < 0.5f
        ? 4.0f * phase * phase * phase
        : 1.0f - std::pow(-2.0f * phase + 2.0f, 3.0f) / 2.0f;
    float shimX = r.x - shimW + (r.w + shimW) * ease;

    dl.push_clip(r);
    dl.fill_rounded_rect({ shimX, r.y, shimW, r.h },
                         fill.with_alpha(fill.a * alpha), radius);
    dl.pop_clip();
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
                        const Image& logo, float dt, ID3D11Device* device)
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
    {
        state.screen       = Screen::ProcessSelect;
        state.prevScreen   = Screen::Splash;
        state.slideInT     = 1.0f;
        state.slideOutT    = 1.0f;
        state.release_process_icons();
        state.processes    = enumerate_targets(device);
        state.refreshAccum = 0.0f;
    }
}

static void draw_title_bar(DrawList& dl, ScreenFonts /*fonts*/,
                            const Rect& wr, const Image& logo, float alpha)
{
    if (!logo.valid()) return;
    const float pad    = 15.0f;
    const float iconSz = 22.0f;
    float logoW = iconSz * (static_cast<float>(logo.width) / static_cast<float>(logo.height));
    dl.draw_image(logo, { wr.x + pad, wr.y + 12.0f, logoW, iconSz }, alpha);
}

static void draw_process_select_content(DrawList& dl, AppState& state,
                                         const InputState& input, ScreenFonts fonts,
                                         const Rect& wr, float alpha, float dt)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;

    float cx  = wr.x + wr.w * 0.5f;
    float pad = t.windowPadding;

    float contentTop = wr.y + 48.0f;
    float listW   = wr.w - pad * 2.0f;
    float listX   = wr.x + pad;

    dl.draw_text("Select Minecraft", { listX, vcenter_text(fn, contentTop, fn.lineHeight()) },
                 t.text.with_alpha(alpha), fn);

    float listTop = contentTop + fn.lineHeight() + 8.0f;
    float rowH    = 48.0f;
    float rowGap  =  4.0f;

    if (state.processes.empty())
    {
        float midY = listTop + (wr.bottom() - listTop) * 0.35f;
        float ty   = vcenter_text(fn, midY - fn.capHeight(), fn.capHeight() * 2.0f);
        draw_text_centered(dl, fn, "No Minecraft instances found", cx, ty,
                           t.textSecond.with_alpha(alpha));
        draw_text_centered(dl, fn, "Launch Minecraft and it will appear here",
                           cx, ty + fn.lineHeight() + 4.0f,
                           t.textDisable.with_alpha(alpha));
        return;
    }

    int count = static_cast<int>(state.processes.size());
    for (int i = 0; i < count; ++i)
    {
        float rowY = listTop + static_cast<float>(i) * (rowH + rowGap);
        Rect  row  = { listX, rowY, listW, rowH };

        bool hovered  = row.contains(input.mousePos);
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

        const float iconSz  = 28.0f;
        const float iconPad = 12.0f;
        float iconX = row.x + iconPad;
        float iconY = row.y + (row.h - iconSz) * 0.5f;

        if (pe.icon.valid())
            dl.draw_image(pe.icon, { iconX, iconY, iconSz, iconSz }, alpha);
        else
            dl.fill_rounded_rect({ iconX, iconY, iconSz, iconSz },
                                 t.buttonBorder.with_alpha(alpha), iconSz * 0.5f);

        float textX  = iconX + iconSz + iconPad;
        float gap    = 3.0f;
        float blockH = fn.capHeight() + gap + fn.capHeight();
        float blockY = row.y + (row.h - blockH) * 0.5f;
        float line1  = blockY - (fn.ascender() - fn.capHeight());
        float line2  = line1 + fn.capHeight() + gap;

        dl.draw_text(pe.display,  { textX, line1 },
                     t.text.lerp(t.text.lightened(0.08f), hov * 0.5f).with_alpha(alpha), fn);
        dl.draw_text(pe.subtitle, { textX, line2 },
                     t.textSecond.with_alpha(alpha), fn);

        if (hovered && input.lmbReleased)
            state.selectedIdx = i;
    }

    float btnY = listTop + static_cast<float>(count) * (rowH + rowGap) + 12.0f;
    Rect  btnR = { listX, btnY, listW, t.buttonH };

    if (state.selectedIdx >= 0)
    {
        bool hovered = btnR.contains(input.mousePos);
        bool pressed = hovered && input.lmbDown;
        state.continueHover.set(hovered ? 1.0f : 0.0f);
        state.continueHover.advance(dt);
        float hov = state.continueHover.value();

        Color bg = pressed ? t.accentPress : t.accent.lerp(t.accentHover, hov);
        dl.fill_rounded_rect(btnR, bg, t.buttonRadius);
        dl.stroke_rounded_rect(btnR, bg.darkened(0.2f), t.buttonRadius, 1.0f);
        draw_text_in_box(dl, fn, "Continue", btnR, t.text.with_alpha(alpha));

        if (hovered && input.lmbReleased)
        {
            state.select_process(state.selectedIdx);
            state.begin_loading();
        }
    }
    else
    {
        dl.fill_rounded_rect(btnR, t.buttonBg,     t.buttonRadius);
        dl.stroke_rounded_rect(btnR, t.buttonBorder, t.buttonRadius, 1.0f);
        draw_text_in_box(dl, fn, "Continue", btnR, t.textDisable.with_alpha(alpha));
    }
}

static void draw_loading_content(DrawList& dl, AppState& state,
                                  const InputState& input, ScreenFonts fonts,
                                  const Rect& wr, float alpha, float dt)
{
    const Theme& t  = g_theme;
    FontAtlas&   fn = fonts.normal;

    float cx         = wr.x + wr.w * 0.5f;
    float pad        = t.windowPadding;
    float contentTop = wr.y + wr.h * 0.36f;

    if (state.loadFailed)
    {
        draw_text_centered(dl, fn, state.statusText, cx, contentTop,
                           t.danger.with_alpha(alpha));
        float helpTop = contentTop + fn.lineHeight() + 12.0f;
        draw_text_centered(dl, fn, "How to fix", cx, helpTop,
                           t.accent.with_alpha(alpha));
        draw_text_centered(dl, fn, state.solutionText, cx,
                           helpTop + fn.lineHeight() + 5.0f,
                           t.textSecond.with_alpha(alpha));
    }
    else
    {
        draw_text_centered(dl, fn, "Loading", cx, contentTop,
                           t.textSecond.with_alpha(alpha));

        float barW = wr.w * 0.62f;
        float barY = contentTop + fn.lineHeight() + 14.0f;
        Rect  barR = { cx - barW * 0.5f, barY, barW, t.progressH };
        draw_shimmer_bar(dl, barR, state.spinElapsed, t.trackBg, t.trackFill, alpha);
    }

    // Back link
    float backY = wr.bottom() - pad - fn.capHeight();
    Rect  backR = { wr.x + pad, backY - (fn.ascender() - fn.capHeight()), 50.0f, fn.lineHeight() };
    bool  backH = backR.contains(input.mousePos);

    state.backHover.set(backH ? 1.0f : 0.0f);
    state.backHover.advance(dt);

    dl.draw_text("< Back", { backR.x, backR.y },
                 t.textDisable.lerp(t.textSecond, state.backHover.value()).with_alpha(alpha), fn);

    if (backH && input.lmbReleased && !state.loadFailed == false)
    {
        // only allow back if injection hasn't started yet — loadFailed means it errored
        // so always allow back on failure
    }
    if (backH && input.lmbReleased && state.loadFailed)
    {
        state.selectedIdx = -1;
        state.continueHover.snap(0.0f);
        state.backHover.snap(0.0f);
        state.transition_to(Screen::ProcessSelect, -1.0f);
    }
}

static float slide_offset(float t, float dir, float width)
{
    float ease = ease_out_quart(t);
    return (1.0f - ease) * width * 0.15f * dir;
}

void draw_screen(DrawList& dl, AppState& state, const InputState& input,
                 ScreenFonts fonts, const Rect& wr, const Image& logo,
                 float dt, bool& closeRequested, Window* window,
                 ID3D11Device* device)
{
    state.advance_tweens(dt);

    float bgOpacity = 1.0f;
    if (state.screen == Screen::Closing && state.closeTween > 0.4f)
        bgOpacity = clamp(1.0f - (state.closeTween - 0.4f) / 0.3f, 0.0f, 1.0f);

    dl.fill_rounded_rect(wr, g_theme.surface.with_alpha(bgOpacity), g_theme.windowRadius);
    dl.stroke_rounded_rect(wr, g_theme.surfaceBorder.with_alpha(bgOpacity), g_theme.windowRadius, 1.0f);
    dl.fill_rect({ wr.x + g_theme.windowRadius * 0.5f, wr.y + 1.0f,
                   wr.w - g_theme.windowRadius, 1.0f },
                 Color(0xFFFFFF).with_alpha(0.04f * bgOpacity));

    if (state.screen == Screen::Splash)
    {
        draw_splash(dl, state, fonts, wr, logo, dt, device);
        if (state.logoTween >= 1.0f)
            closeRequested |= draw_chrome(dl, input, wr, fonts, window, state, dt);
        return;
    }

    bool transitioning = state.slideInT < 1.0f || state.slideOutT < 1.0f;

    // Outgoing: starts at rest (offset 0), slides away in slideDirection
    if (transitioning && state.slideOutT < 1.0f)
    {
        float outAlpha  = 1.0f - ease_out_quart(state.slideOutT);
        float outOffset = slide_offset(state.slideOutT, state.slideDirection, wr.w) * -1.0f
                        + ease_out_quart(state.slideOutT) * wr.w * 0.15f * state.slideDirection;

        Rect outWr = wr.translated(outOffset, 0.0f);
        dl.push_clip(wr);
        draw_title_bar(dl, fonts, outWr, logo, outAlpha);
        switch (state.prevScreen)
        {
        case Screen::ProcessSelect:
            draw_process_select_content(dl, state, input, fonts, outWr, outAlpha, 0.0f);
            break;
        case Screen::Loading:
            draw_loading_content(dl, state, input, fonts, outWr, outAlpha, 0.0f);
            break;
        case Screen::Splash:
        case Screen::Closing:
            break;
        }
        dl.pop_clip();
    }

    // Incoming: arrives from opposite side of slideDirection
    {
        float inAlpha  = ease_out_quart(state.slideInT);
        float inOffset = slide_offset(state.slideInT, -state.slideDirection, wr.w);

        Rect inWr = wr.translated(inOffset, 0.0f);
        dl.push_clip(wr);
        draw_title_bar(dl, fonts, inWr, logo, inAlpha);
        switch (state.screen)
        {
        case Screen::ProcessSelect:
            draw_process_select_content(dl, state, input, fonts, inWr, inAlpha, dt);
            break;
        case Screen::Loading:
            draw_loading_content(dl, state, input, fonts, inWr, inAlpha, dt);
            break;
        case Screen::Closing:
            state.tick_closing(dt);
            {
                float uiAlpha = clamp(1.0f - state.closeTween * 3.0f, 0.0f, 1.0f);
                if (uiAlpha > 0.01f)
                    draw_loading_content(dl, state, input, fonts, inWr, uiAlpha, 0.0f);
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
