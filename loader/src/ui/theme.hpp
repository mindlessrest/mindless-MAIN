#pragma once
#include "core/color.hpp"

namespace mindless::ui
{

struct Theme
{
    // ------------------------------------------------------------------ colors
    Color background  = 0x0C0D10;   // window / outer background
    Color surface     = 0x13151A;   // panel surface
    Color surfaceBorder = 0x1F2128; // panel border — very subtle

    Color text        = 0xF0F1F5;   // primary text
    Color textSecond  = 0x8E919B;   // secondary / label text
    Color textDisable = 0x3A3C44;

    Color accent      = 0xC9A0DC;   // soft pastel violet
    Color accentHover = 0xD8B4EC;
    Color accentPress = 0xA87BBF;
    Color accentDim   = Color(0xC9A0DC).with_alpha(0.15f);
    Color accentGlow  = 0xE6A8D4;   // warmer than the accent — reads as light pink once diffused
    Color accentText  = 0x1A1424;   // sits on the accent fill, which is far too light for white

    Color buttonBg    = 0x1A1C22;
    Color buttonHover = 0x22242C;
    Color buttonPress = 0x131518;
    Color buttonBorder= 0x282A33;

    Color success     = 0x4CAF78;
    Color warning     = 0xE09B52;
    Color danger      = 0xE05252;

    Color trackBg     = 0x1A1C22;
    Color trackFill   = 0xC9A0DC;

    Color selectionBg   = Color(0xC9A0DC).with_alpha(0.12f);
    Color selectionRing = Color(0xC9A0DC).with_alpha(0.35f);

    Color glowColor   = Color(0x000000).with_alpha(0.62f);

    // ---------------------------------------------------------------- geometry
    // The window is the panel plus glowMargin on every side. That gutter is transparent and
    // exists only so the glow has somewhere to fade out; it is not part of the layout.
    float glowSpread    = 34.0f;
    float glowMargin    = 38.0f;

    float windowRadius  = 10.0f;
    float cardRadius    =  8.0f;
    float buttonRadius  =  6.0f;
    float tagRadius     =  4.0f;
    float borderWidth   =  1.0f;

    float windowPadding = 24.0f;
    float itemSpacing   =  8.0f;
    float sectionGap    = 20.0f;

    float buttonH       = 36.0f;
    float progressH     =  4.0f;

    // -------------------------------------------------------------- typography
    float fontSizeNormal = 13.0f;
    float fontSizeTitle  = 18.0f;
    float fontSizeSmall  = 11.0f;
};

extern Theme g_theme;

} // namespace mindless::ui
